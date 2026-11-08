package com.sky.service;

import java.util.List;
import java.util.Map;

import com.sky.dto.TicketCreateDTO;

/**
 * 内部接口服务层：订单查询（只返回客服场景需要的精简字段）+ 售后工单
 */
public interface InternalApiService {

    /**
     * 某用户的订单列表（精简 Map：number/status/amount/orderTime）
     */
    List<Map<String, Object>> listOrders(Long userId, Integer status, Integer limit);

    /**
     * 订单主信息 + 明细（精简 Map：order / details）。
     * 订单不存在抛 OrderNotFoundException；不属于该用户抛 OrderAccessDeniedException。
     */
    Map<String, Object> getOrderDetail(String orderNumber, Long userId);

    /**
     * 创建工单，返回工单号
     */
    String createTicket(TicketCreateDTO dto);

    /** 只读核对当前用户某次发送动作的工单；暂无记录不代表原写入已经失败。 */
    String findTicketNoByRequestId(Long userId, String requestId);
}
