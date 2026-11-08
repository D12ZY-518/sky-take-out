package com.sky.service;

import com.sky.entity.ShoppingCart;
import com.sky.vo.AiChatVO;

import java.util.List;

public interface AiAssistantService {

    /**
     * AI 点餐助手：接收用户消息，结合菜单快照与多轮上下文，返回意图处理后的话术
     *
     * @param userId  用户 id（对话历史与加购都按用户隔离）
     * @param message 用户消息
     * @return AI 回复（含加购等动作完成后的标准文案）
     */
    AiChatVO chat(Long userId, String message);

    /**
     * 查询指定用户的购物车（演示页展示用）
     *
     * @param userId 用户 id
     * @return 购物车列表
     */
    List<ShoppingCart> cart(Long userId);
}
