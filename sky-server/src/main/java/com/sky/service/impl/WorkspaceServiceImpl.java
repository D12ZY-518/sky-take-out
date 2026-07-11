package com.sky.service.impl;

import com.sky.constant.StatusConstant;
import com.sky.entity.Orders;
import com.sky.mapper.DishMapper;
import com.sky.mapper.OrderMapper;
import com.sky.mapper.SetmealMapper;
import com.sky.mapper.UserMapper;
import com.sky.service.WorkspaceService;
import com.sky.vo.BusinessDataVO;
import com.sky.vo.DishOverViewVO;
import com.sky.vo.OrderOverViewVO;
import com.sky.vo.SetmealOverViewVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;

@Service
@Slf4j
public class WorkspaceServiceImpl implements WorkspaceService {

    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private DishMapper dishMapper;
    @Autowired
    private SetmealMapper setmealMapper;

    /**
     * 构建查询参数Map
     */
    private Map<String, Object> buildMap(Object... keyValues) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    /**
     * 根据时间段统计营业数据
     * @param begin
     * @param end
     * @return
     */
    public BusinessDataVO getBusinessData(LocalDateTime begin, LocalDateTime end) {
        /**
         * 营业额：当日已完成订单的总金额
         * 有效订单：当日已完成订单的数量
         * 订单完成率：有效订单数 / 总订单数
         * 平均客单价：营业额 / 有效订单数
         * 新增用户：当日新增用户的数量
         */

        Map<String, Object> map = buildMap("begin", begin, "end", end);

        //查询总订单数
        Integer totalOrderCount = orderMapper.countByMap(map);

        //查询已完成订单的营业额和有效订单数
        map.put("status", Orders.COMPLETED);
        Double turnover = orderMapper.sumByMap(map);
        turnover = turnover == null ? 0.0 : turnover;

        Integer validOrderCount = orderMapper.countByMap(map);

        //计算订单完成率和平均客单价
        Double unitPrice = 0.0;
        Double orderCompletionRate = 0.0;
        if (totalOrderCount != null && totalOrderCount > 0 && validOrderCount != null && validOrderCount > 0) {
            orderCompletionRate = validOrderCount.doubleValue() / totalOrderCount;
            unitPrice = turnover / validOrderCount;
        }

        //新增用户数
        Integer newUsers = userMapper.countByMap(map);

        return BusinessDataVO.builder()
                .turnover(turnover)
                .validOrderCount(validOrderCount)
                .orderCompletionRate(orderCompletionRate)
                .unitPrice(unitPrice)
                .newUsers(newUsers)
                .build();
    }


    /**
     * 查询订单管理数据
     *
     * @return
     */
    public OrderOverViewVO getOrderOverView() {
        LocalDateTime todayBegin = LocalDateTime.now().with(LocalTime.MIN);

        //使用独立Map避免状态污染
        Integer waitingOrders = orderMapper.countByMap(buildMap("begin", todayBegin, "status", Orders.TO_BE_CONFIRMED));
        Integer deliveredOrders = orderMapper.countByMap(buildMap("begin", todayBegin, "status", Orders.CONFIRMED));
        Integer completedOrders = orderMapper.countByMap(buildMap("begin", todayBegin, "status", Orders.COMPLETED));
        Integer cancelledOrders = orderMapper.countByMap(buildMap("begin", todayBegin, "status", Orders.CANCELLED));
        Integer allOrders = orderMapper.countByMap(buildMap("begin", todayBegin, "status", null));

        return OrderOverViewVO.builder()
                .waitingOrders(waitingOrders)
                .deliveredOrders(deliveredOrders)
                .completedOrders(completedOrders)
                .cancelledOrders(cancelledOrders)
                .allOrders(allOrders)
                .build();
    }

    /**
     * 查询菜品总览
     *
     * @return
     */
    public DishOverViewVO getDishOverView() {
        Integer sold = dishMapper.countByMap(buildMap("status", StatusConstant.ENABLE));
        Integer discontinued = dishMapper.countByMap(buildMap("status", StatusConstant.DISABLE));

        return DishOverViewVO.builder()
                .sold(sold)
                .discontinued(discontinued)
                .build();
    }

    /**
     * 查询套餐总览
     *
     * @return
     */
    public SetmealOverViewVO getSetmealOverView() {
        Integer sold = setmealMapper.countByMap(buildMap("status", StatusConstant.ENABLE));
        Integer discontinued = setmealMapper.countByMap(buildMap("status", StatusConstant.DISABLE));

        return SetmealOverViewVO.builder()
                .sold(sold)
                .discontinued(discontinued)
                .build();
    }
}
