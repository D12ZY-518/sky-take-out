package com.sky.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 售后工单（Python Agent 的 create_ticket 工具经 Java 内部接口落库）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerServiceTicket implements Serializable {

    //工单状态：1待处理 2处理中 3已完结
    public static final Integer PENDING = 1;
    public static final Integer PROCESSING = 2;
    public static final Integer FINISHED = 3;

    private static final long serialVersionUID = 1L;

    private Long id;

    //工单号（业务唯一，TK + 随机值）
    private String ticketNo;

    //同一用户一次发送动作的幂等键；旧数据允许为空
    private String requestId;

    //提单人用户 id
    private Long userId;

    //关联订单号（可为空）
    private String orderNumber;

    //工单类别：退款/催单/投诉/配送/其他
    private String category;

    //用户诉求内容
    private String content;

    //状态：1待处理 2处理中 3已完结
    private Integer status;

    //创建时间
    private LocalDateTime createTime;
}
