package com.sky.service.impl;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import com.sky.dto.TicketCreateDTO;
import com.sky.entity.CustomerServiceTicket;
import com.sky.entity.OrderDetail;
import com.sky.entity.Orders;
import com.sky.exception.OrderAccessDeniedException;
import com.sky.exception.OrderNotFoundException;
import com.sky.exception.TicketBusinessException;
import com.sky.mapper.InternalApiMapper;
import com.sky.mapper.OrderDetailMapper;
import com.sky.service.InternalApiService;

/**
 * 内部接口服务实现：
 * 关键设计——只把"客服场景需要的精简字段"导出成 Map，
 * ① 不把 Orders 几十个字段（地址/手机号等隐私）暴露给 Python 侧；
 * ② 时间统一格式化成字符串，避免跨语言序列化差异（Jackson 默认时间数组）。
 */
@Service
public class InternalApiServiceImpl implements InternalApiService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired
    private InternalApiMapper internalApiMapper;

    @Autowired
    private OrderDetailMapper orderDetailMapper;

    @Override
    public List<Map<String, Object>> listOrders(Long userId, Integer status, Integer limit) {
        int safeLimit = (limit == null || limit <= 0 || limit > 20) ? 5 : limit;
        List<Orders> orders = internalApiMapper.listOrders(userId, status, safeLimit);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Orders o : orders) {
            result.add(toOrderMap(o));
        }
        return result;
    }

    @Override
    public Map<String, Object> getOrderDetail(String orderNumber, Long userId) {
        Orders order = internalApiMapper.getByNumber(orderNumber);
        // 归属校验两道：先判存在（404），再判归属（403）。
        // 顺序的第一原因是 null 安全：查不到单就没有 userId 可比，先比归属必 NPE。
        // 【权衡】区分 404/403 会暴露"该单号是否存在"（枚举预言机），
        // 对持密钥的内部服务可接受；若面向不可信调用方，应统一话术做模糊化。
        if (order == null) {
            throw new OrderNotFoundException("没有这张订单，请核对订单号。");
        }
        if (!order.getUserId().equals(userId)) {
            throw new OrderAccessDeniedException("无权查看该订单（订单不属于当前用户）。");
        }
        Map<String, Object> data = new HashMap<>();
        data.put("order", toOrderMap(order));
        List<Map<String, Object>> details = new ArrayList<>();
        for (OrderDetail d : orderDetailMapper.getByOrderId(order.getId())) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", d.getName());
            item.put("number", d.getNumber());
            item.put("amount", d.getAmount() == null ? "" : d.getAmount().toPlainString());
            details.add(item);
        }
        data.put("details", details);
        return data;
    }

    @Override
    public String findTicketNoByRequestId(Long userId, String requestId) {
        CustomerServiceTicket ticket = internalApiMapper.getTicketByRequestId(userId, requestId);
        return ticket == null ? null : ticket.getTicketNo();
    }

    @Override
    public String createTicket(TicketCreateDTO dto) {
        Long userId = dto.getUserId();
        if (userId == null || userId <= 0) {
            throw new TicketBusinessException("用户身份无效，无法登记工单。");
        }
        String orderNumber = normalizeOrderNumber(dto.getOrderNumber());
        // 已提交请求先恢复原结果；当前订单状态变化不应令原工单无法取回。
        CustomerServiceTicket previous = internalApiMapper.getTicketByRequestId(userId, dto.getRequestId());
        if (previous != null) {
            return replayTicket(previous, dto, orderNumber);
        }
        // 模型可以选择订单号，但 Java 必须独立校验；非空无效单号不能悄悄改成不关联订单。
        if (orderNumber != null) {
            Orders order = internalApiMapper.getByNumber(orderNumber);
            if (order == null || !Objects.equals(order.getUserId(), userId)) {
                // 统一不存在/非本人话术，避免经建单接口区分他人订单是否存在。
                throw new TicketBusinessException("未找到可关联的订单，请核对订单号。");
            }
        }
        // 一次用户发送动作只允许一张工单；同一 requestId 携不同内容属于冲突，不能静默复用。
        String ticketNo = "TK" + UUID.randomUUID().toString().replace("-", "").substring(0, 30);
        CustomerServiceTicket ticket = CustomerServiceTicket.builder()
                .ticketNo(ticketNo)
                .requestId(dto.getRequestId())
                .userId(dto.getUserId())
                .orderNumber(orderNumber)
                .category(dto.getCategory())
                .content(dto.getContent())
                .status(CustomerServiceTicket.PENDING)
                .createTime(LocalDateTime.now())
                .build();
        try {
            internalApiMapper.insertTicket(ticket);
            return ticketNo;
        } catch (DuplicateKeyException ex) {
            // 唯一约束是并发下的最终裁判：首请求可能已提交，但响应在网络中丢失。
            CustomerServiceTicket committed = internalApiMapper.getTicketByRequestId(
                    dto.getUserId(), dto.getRequestId());
            if (committed == null) {
                throw ex; // 极低概率的 ticket_no 碰撞等其他唯一键冲突，不能误报成功
            }
            return replayTicket(committed, dto, orderNumber);
        }
    }

    private String normalizeOrderNumber(String number) {
        return number == null || number.trim().isEmpty() ? null : number.trim();
    }

    private String replayTicket(CustomerServiceTicket previous, TicketCreateDTO dto, String orderNumber) {
        if (!Objects.equals(previous.getCategory(), dto.getCategory())
                || !Objects.equals(previous.getContent(), dto.getContent())
                || !Objects.equals(normalizeOrderNumber(previous.getOrderNumber()), orderNumber)) {
            throw new IllegalArgumentException("requestId 已用于另一条工单诉求");
        }
        return previous.getTicketNo();
    }

    /**
     * Orders 实体 → 客服场景精简字段（LinkedHashMap 保证 JSON 字段顺序稳定）
     */
    private Map<String, Object> toOrderMap(Orders o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("number", o.getNumber());
        m.put("status", o.getStatus());
        m.put("amount", o.getAmount() == null ? "" : o.getAmount().toPlainString());
        m.put("orderTime", o.getOrderTime() == null ? "" : FMT.format(o.getOrderTime()));
        return m;
    }
}
