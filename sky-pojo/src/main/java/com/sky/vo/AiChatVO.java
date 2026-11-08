package com.sky.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * AI 点餐助手-对话响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiChatVO implements Serializable {

    //AI 回复文案
    private String reply;

    //当前购物车商品总件数（前端角标用）
    private Integer cartCount;
}
