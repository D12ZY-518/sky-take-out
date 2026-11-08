package com.sky.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 内部接口入参：Python Agent → Java POST /internal/tickets
 */
@Data
public class TicketCreateDTO implements Serializable {

    //提单人用户 id（由 Python 侧从注入上下文透传，Java 仍按内部 token 授信）
    private Long userId;

    //关联订单号（可为空）
    private String orderNumber;

    //工单类别：退款/催单/投诉/配送/其他
    private String category;

    //用户诉求内容
    private String content;

    //由可信调用链透传的用户发送动作 ID，同一动作最多创建一张工单
    private String requestId;
}
