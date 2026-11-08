package com.sky.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 售后客服 Agent-对话响应（Python Agent 返回，Java 原样透传给前端）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ServiceChatVO implements Serializable {

    //AI 最终回复文案
    private String reply;

    //本轮实际调用的工具名列表（观测/日志用，可为空）
    private List<String> toolCalls;

    // completed：本轮处理结束（不等于退款成功）；unknown：必须保留请求 ID 核对。
    private String outcome;
}
