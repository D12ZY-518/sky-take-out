package com.sky.service.impl;

import com.alibaba.fastjson.JSONObject;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.sky.constant.MessageConstant;
import com.sky.context.BaseContext;
import com.sky.dto.OrdersCancelDTO;
import com.sky.dto.OrdersConfirmDTO;
import com.sky.dto.OrdersPageQueryDTO;
import com.sky.dto.OrdersPaymentDTO;
import com.sky.dto.OrdersRejectionDTO;
import com.sky.dto.OrdersSubmitDTO;
import com.sky.entity.AddressBook;
import com.sky.entity.OrderDetail;
import com.sky.entity.Orders;
import com.sky.entity.ShoppingCart;
import com.sky.entity.User;
import com.sky.exception.AddressBookBusinessException;
import com.sky.exception.OrderBusinessException;
import com.sky.exception.ShoppingCartBusinessException;
import com.sky.mapper.AddressBookMapper;
import com.sky.mapper.OrderDetailMapper;
import com.sky.mapper.OrderMapper;
import com.sky.mapper.ShoppingCartMapper;
import com.sky.mapper.UserMapper;
import com.sky.result.PageResult;
import com.sky.service.OrderService;
import com.sky.utils.WeChatPayUtil;
import com.sky.vo.OrderPaymentVO;
import com.sky.vo.OrderStatisticsVO;
import com.sky.vo.OrderSubmitVO;
import com.sky.vo.OrderVO;
import com.sky.webSocket.WebSocketServer;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderServiceImpl implements OrderService {
    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private OrderDetailMapper orderDetailMapper;

    @Autowired
    private ShoppingCartMapper shoppingCartMapper;

    @Autowired
    private AddressBookMapper addressBookMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private WeChatPayUtil weChatPayUtil;    

    @Autowired
    private WebSocketServer webSocketServer;
    
    /**
     * 用户下单
     * @param ordersSubmitDTO
     * @return
     */
    @Override
    @Transactional
    public OrderSubmitVO submitOrder(OrdersSubmitDTO ordersSubmitDTO) {
        // 1. 校验地址簿：地址不存在则无法配送，直接抛业务异常中止下单
        AddressBook addressBook = addressBookMapper.getById(ordersSubmitDTO.getAddressBookId());
        if (addressBook == null) {
            throw new AddressBookBusinessException(MessageConstant.ADDRESS_BOOK_IS_NULL);
        }
        // 2. 校验购物车：为空说明没有可下单的商品，抛业务异常
        ShoppingCart shoppingCart = new ShoppingCart();
        shoppingCart.setUserId(BaseContext.getCurrentId());   
        List<ShoppingCart> shoppingCartList = shoppingCartMapper.list(shoppingCart);
        if (shoppingCartList == null || shoppingCartList.isEmpty()) {
            throw new ShoppingCartBusinessException(MessageConstant.SHOPPING_CART_IS_NULL);
        }
        // 3. 组装订单对象：拷贝下单参数后，用地址簿补齐收货人手机号/地址/姓名（前端不下发，服务端兜底）
        Orders order = new Orders();
        BeanUtils.copyProperties(ordersSubmitDTO, order);
        order.setPhone(addressBook.getPhone());
        order.setAddress(addressBook.getDetail());
        order.setConsignee(addressBook.getConsignee());
        // 4. 补全订单号/用户/状态字段后落库：insert 后主键回填到 order.getId()（下方明细靠它认父）
        order.setNumber(String.valueOf(System.currentTimeMillis()));  // 订单号 = 时间戳字符串
        order.setUserId(BaseContext.getCurrentId());                   // 当前登录用户
        order.setStatus(Orders.PENDING_PAYMENT);                      // 1 待付款（用常量，不写魔法数字）
        order.setPayStatus(Orders.UN_PAID);                           // 0 未支付
        order.setOrderTime(LocalDateTime.now());                      // 下单时间
        orderMapper.insert(order);

        // 5. 把购物车明细转成订单明细：setOrderId 认父（依赖上一步主键回填），最后批量插入明细表
        List<OrderDetail> orderDetailList = new ArrayList<>();
        for (ShoppingCart cart : shoppingCartList) {
            OrderDetail orderDetail = new OrderDetail();
            BeanUtils.copyProperties(cart, orderDetail);
            orderDetail.setOrderId(order.getId());
            orderDetailList.add(orderDetail);
        }
        orderDetailMapper.insertBatch(orderDetailList);
        // 6. WebSocket 推送来单提醒：type=1 表示新订单，商家端据此实时接单
        webSocketServer.sendToAllClient("{\"type\":1,\"orderId\":" + order.getId() + ",\"content\":\"您有新的待处理订单\"}");
        // 7. 下单成功即清空该用户购物车（防止重复下单），随后返回含回填主键的下单结果
        shoppingCartMapper.deleteByUserId(BaseContext.getCurrentId());
        return OrderSubmitVO.builder()
                .id(order.getId())
                .orderNumber(order.getNumber())
                .orderAmount(order.getAmount())
                .orderTime(order.getOrderTime())
                .build();
    }
    
    /**
     * 用户支付订单
     * @param ordersPaymentDTO
     * @return
     */
    @Override
    public OrderPaymentVO payment(OrdersPaymentDTO ordersPaymentDTO) throws Exception {
        // 1. 查当前登录用户取 openid，调微信统一下单；金额 0.01 仅为测试值（演示环境不真实扣款，正式需用订单实付金额）
        User user = userMapper.getById(BaseContext.getCurrentId());
        JSONObject jsonObject = weChatPayUtil.pay(
                ordersPaymentDTO.getOrderNumber(),
                new BigDecimal(0.01),
                "禾巷餐饮订单",
                user.getOpenid());
    // 2. 微信返回码约定：code=ORDERPAID 表示该订单此前已支付，抛异常防止用户重复下单支付
    if (jsonObject.getString("code") != null && jsonObject.getString("code").equals("ORDERPAID")) {
        throw new OrderBusinessException("该订单已支付");
    }

    // 3. 把微信预支付参数转成 OrderPaymentVO 返回前端拉起收银台；package 是保留字无法作为字段，须从 json 手动取出塞入 packageStr
    OrderPaymentVO vo = jsonObject.toJavaObject(OrderPaymentVO.class);
    vo.setPackageStr(jsonObject.getString("package"));  // package 是保留字，手动塞
    return vo;
    }   

    /**
     * 支付成功，修改订单状态
     * @param outTradeNo
     */
    @Override
    public void paySuccess(String outTradeNo) {
        // 1. 支付回调由微信服务器发起、无登录上下文，只能按订单号(outTradeNo)反查订单；查不到则抛订单不存在
        // 支付回调由微信服务器调用，无登录用户，只能按订单号查询
        Orders ordersDB = orderMapper.getByNumber(outTradeNo);
        if (ordersDB == null) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }
        // 2. 支付成功推进订单状态：待付款(1) → 待接单(2)，支付状态置为已支付(1)，并记录结账时间
        // 更新订单状态、支付状态、结账时间
        Orders orders = Orders.builder()
                .id(ordersDB.getId())
                .status(Orders.TO_BE_CONFIRMED)   // 订单状态 2 待接单
                .payStatus(Orders.PAID)           // 支付状态 1 已支付
                .checkoutTime(LocalDateTime.now())
                .build();
        orderMapper.update(orders);
    }

    @Override
    public PageResult pageQuery4User(int page, int pageSize, Integer status) {
        // 1. 组装条件并分页查询：按当前登录用户过滤、可按状态筛选，PageHelper 自动拼接分页 SQL
        OrdersPageQueryDTO ordersPageQueryDTO = new OrdersPageQueryDTO();
        ordersPageQueryDTO.setStatus(status);
        ordersPageQueryDTO.setUserId(BaseContext.getCurrentId());
        PageHelper.startPage(page, pageSize);
        Page<Orders> pages = orderMapper.pageQuery(ordersPageQueryDTO);
        // 2. 逐单拷贝为 OrderVO 并回查其明细列表（用户端订单列表需要展示明细）
        List<OrderVO> list = pages.stream().map(order -> {
            OrderVO orderVO = new OrderVO();
            BeanUtils.copyProperties(order, orderVO);
            orderVO.setOrderDetailList(orderDetailMapper.getByOrderId(order.getId()));
            return orderVO;
        }).collect(Collectors.toList());
        // 3. 返回分页结果：订单总数 + 当前页数据
        return new PageResult(pages.getTotal(), list);
    }

    @Override
    public OrderVO details(Long id) {
        // 1. 按订单 id + 当前用户 id 联合查询：校验订单存在且归属本人，防止越权查看他人订单
        Orders orders = orderMapper.getByIdAndUserId(id, BaseContext.getCurrentId());
        if (orders == null) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }
        // 2. 订单主信息拷入 VO，再按订单 id 回查明细列表
        OrderVO orderVO = new OrderVO();
        BeanUtils.copyProperties(orders, orderVO);
        orderVO.setOrderDetailList(orderDetailMapper.getByOrderId(orders.getId()));
        // 3. 明细为空说明订单数据异常，抛"订单详情为空"业务异常
        if (orderVO.getOrderDetailList().isEmpty()) {
            throw new OrderBusinessException(MessageConstant.ORDER_DETAIL_IS_NULL);
        }
        return orderVO; 
    }

    @Override
    public OrderVO adminDetails(Long id) {
        // Only the /admin/order controller calls this method; unlike user details,
        // staff may view orders from any customer after admin JWT validation.
        Orders orders = orderMapper.getById(id);
        if (orders == null) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }
        OrderVO orderVO = new OrderVO();
        BeanUtils.copyProperties(orders, orderVO);
        List<OrderDetail> details = orderDetailMapper.getByOrderId(id);
        if (details == null || details.isEmpty()) {
            throw new OrderBusinessException(MessageConstant.ORDER_DETAIL_IS_NULL);
        }
        orderVO.setOrderDetailList(details);
        return orderVO;
    }

    @Override
    public void userCancelById(Long id) throws Exception {
        // 1. 校验订单归属：按 订单id+当前用户id 查询，订单不存在或不属于本人则抛异常
        Orders orders = orderMapper.getByIdAndUserId(id, BaseContext.getCurrentId());
        if (orders == null) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }
        // 2. 状态机校验：仅"待付款(1)/待接单(2)"允许用户取消；派送中/已完成等状态不允许中途取消
        if (orders.getStatus() != Orders.PENDING_PAYMENT
        && orders.getStatus() != Orders.TO_BE_CONFIRMED) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }   
        // 3. 已支付(1)的订单取消必须先退款：按原支付单号调微信原路退回订单金额
        if (orders.getPayStatus() == Orders.PAID) {
            weChatPayUtil.refund(orders.getNumber(), orders.getNumber(), orders.getAmount(), orders.getAmount());
        }
        // 4. 状态置为已取消(6)，并记录取消原因(用户取消)与取消时间
        Orders order = Orders.builder()
                .id(orders.getId())
                .status(Orders.CANCELLED)
                .cancelReason("用户取消订单")
                .cancelTime(LocalDateTime.now())
                .build();
        orderMapper.update(order);  
    }

    @Override
    public void repetition(Long id){
        // 1. 按 id 查原订单：订单不存在则抛异常
        Orders orders = orderMapper.getById(id);
        if (orders == null) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }
        // 2. 把原订单明细快照转回购物车商品（"再来一单"）：同名属性直接搬，并重置归属用户与创建时间
        List<OrderDetail> orderDetailList = orderDetailMapper.getByOrderId(id);
        List<ShoppingCart> shoppingCartList = new ArrayList<>();
        for (OrderDetail orderDetail : orderDetailList) {
            ShoppingCart shoppingCart = new ShoppingCart();
            BeanUtils.copyProperties(orderDetail, shoppingCart);  // 明细→购物车，字段同名直接搬
            shoppingCart.setUserId(BaseContext.getCurrentId());
            shoppingCart.setCreateTime(LocalDateTime.now());
            shoppingCartList.add(shoppingCart);
        }
        // 3. 批量写回购物车表，等待用户再次结算
        shoppingCartMapper.insertBatch(shoppingCartList);   
    }

    @Override
    public void reminder(Long id){
        // 1. 按 id 查订单：订单不存在则抛业务异常
        Orders orders = orderMapper.getById(id);
        if (orders == null) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }
        // 2. 状态机校验：催单只在"待接单(2)"时有意义——商家还没接单才需要催
        if (orders.getStatus() != Orders.TO_BE_CONFIRMED) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }   
        // 3. WebSocket 推送催单消息给商家端：type=2 表示催单（区别于来单提醒 type=1）
        webSocketServer.sendToAllClient("{\"type\":2,\"orderId\":" + orders.getId() + ",\"content\":\"订单催单\"}");
    }

    @Override
    public void confirm(OrdersConfirmDTO ordersConfirmDTO) {
        // 1. 按 id 查单：订单不存在则抛业务异常
        Orders orders = orderMapper.getById(ordersConfirmDTO.getId());
        if (orders == null) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }
        // 2. 状态机校验：只有"待接单(2)"的订单才能被商家接单，其他状态抛订单状态异常
        if (orders.getStatus() != Orders.TO_BE_CONFIRMED) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }
        // 3. 状态流转：待接单(2) → 已接单(3)
        Orders order = Orders.builder()
                .id(orders.getId())
                .status(Orders.CONFIRMED)
                .build();
        orderMapper.update(order);
    }

    @Override
    public void rejection(OrdersRejectionDTO ordersRejectionDTO) throws Exception {
        // 1. 按 id 查单：订单不存在则抛业务异常
        Orders orders = orderMapper.getById(ordersRejectionDTO.getId());
        if (orders == null) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }
        // 2. 状态机校验：只有"待接单(2)"且"已支付(1)"的订单才能拒单——拒单必须把已收款退给用户
        if(orders.getPayStatus() != Orders.PAID || orders.getStatus() != Orders.TO_BE_CONFIRMED){
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }   
        // 3. 拒单即退款：按原支付单号调微信原路退回订单金额
        weChatPayUtil.refund(orders.getNumber(), orders.getNumber(), orders.getAmount(), orders.getAmount());
        // 4. 状态流转：待接单(2) → 已取消(6)，记录拒单原因与取消时间
        Orders order = Orders.builder()
                .id(orders.getId())
                .status(Orders.CANCELLED)
                .rejectionReason(ordersRejectionDTO.getRejectionReason())
                .cancelTime(LocalDateTime.now())
                .build();
        orderMapper.update(order);
    }

    @Override
    public void cancel(OrdersCancelDTO ordersCancelDTO) throws Exception {
        // 1. 按 id 查单：订单不存在则抛业务异常
        Orders orders = orderMapper.getById(ordersCancelDTO.getId());
        if (orders == null) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }
        // 2. 状态机校验：已取消(6)/已完成(5) 是终态不允许再取消；其余在途状态都可取消
        if (orders.getStatus() == Orders.CANCELLED || orders.getStatus() == Orders.COMPLETED) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }
        // 3. 状态流转：当前状态 → 已取消(6)，记录取消原因与取消时间
        Orders order = Orders.builder()
                .id(orders.getId())
                .status(Orders.CANCELLED)
                .cancelReason(ordersCancelDTO.getCancelReason())
                .cancelTime(LocalDateTime.now())
                .build();
        orderMapper.update(order);  
    }

    @Override
    public void delivery(Long id) {
        // 1. 按 id 查单：订单不存在则抛业务异常
        Orders orders = orderMapper.getById(id);
        if (orders == null) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }
        // 2. 状态机校验：只有"已接单(3)"的订单才能进入派送环节
        if (orders.getStatus() != Orders.CONFIRMED) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }
        // 3. 状态流转：已接单(3) → 派送中(4)
        Orders order = Orders.builder()
                .id(orders.getId())
                .status(Orders.DELIVERY_IN_PROGRESS)
                .build();
        orderMapper.update(order);
    }   

    @Override
    public void complete(Long id) {
        // 1. 按 id 查单：订单不存在则抛业务异常
        Orders orders = orderMapper.getById(id);
        if (orders == null) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }
        // 2. 状态机校验：只有"派送中(4)"的订单才能确认完成（未派送/已完成的单不能重复完成）
        if (orders.getStatus() != Orders.DELIVERY_IN_PROGRESS) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }
        // 3. 状态流转：派送中(4) → 已完成(5)
        Orders order = Orders.builder()
                .id(orders.getId())
                .status(Orders.COMPLETED)
                .build();
        orderMapper.update(order);
    }

    @Override
    public PageResult conditionSearch(OrdersPageQueryDTO ordersPageQueryDTO){
        // 1. 开启分页，按 DTO 条件（订单号/状态/下单时间段等）分页查询订单
        PageHelper.startPage(ordersPageQueryDTO.getPage(), ordersPageQueryDTO.getPageSize());
        Page<Orders> page = orderMapper.pageQuery(ordersPageQueryDTO);
        // 2. 逐单拷贝为 OrderVO 并回查明细列表（管理端列表/详情展示需要）
        List<OrderVO> list = page.stream().map(order -> {
            OrderVO orderVO = new OrderVO();
            BeanUtils.copyProperties(order, orderVO);
            orderVO.setOrderDetailList(orderDetailMapper.getByOrderId(order.getId()));
            return orderVO;
        }).collect(Collectors.toList());
        // 3. 返回分页结果：订单总数 + 当前页数据
        return new PageResult(page.getTotal(), list);
    }

    @Override
    public OrderStatisticsVO statistics(){
        // 1. 分别统计在办订单：待接单(2)/已接单(3)/派送中(4) 三种状态各有多少单
        Integer toBeConfirmedCount = orderMapper.countByStatus(Orders.TO_BE_CONFIRMED);
        Integer confirmedCount = orderMapper.countByStatus(Orders.CONFIRMED);
        Integer deliveryInProgressCount = orderMapper.countByStatus(Orders.DELIVERY_IN_PROGRESS);
        // 2. 把三项统计结果封装进 VO 返回给管理端首页展示
        OrderStatisticsVO orderStatisticsVO = new OrderStatisticsVO();
        orderStatisticsVO.setToBeConfirmed(toBeConfirmedCount);
        orderStatisticsVO.setConfirmed(confirmedCount);
        orderStatisticsVO.setDeliveryInProgress(deliveryInProgressCount);
        return orderStatisticsVO;
    }

}
