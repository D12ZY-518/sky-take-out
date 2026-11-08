package com.sky.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * AI 点餐助手-对话请求
 */
@Data
public class AiChatDTO implements Serializable {

    //用户发送的消息
    private String message;
}
