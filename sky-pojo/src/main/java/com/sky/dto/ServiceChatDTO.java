package com.sky.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 售后客服 Agent-对话请求（Java 转发给 Python Agent 服务）
 */
@Data
public class ServiceChatDTO implements Serializable {

    //用户发送的消息
    private String message;

    //会话 id（多轮记忆按它隔离）；不传走默认会话
    private String sessionId;

    //一次用户发送动作的稳定标识；客户端重试同一动作时必须复用，不能每次重新生成
    private String requestId;
}
