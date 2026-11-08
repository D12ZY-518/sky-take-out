package com.sky.controller.user;

import io.swagger.annotations.Api;

import java.util.List;

import javax.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sky.dto.AiChatDTO;
import com.sky.entity.ShoppingCart;
import com.sky.result.Result;
import com.sky.security.AiUserResolver;
import com.sky.service.AiAssistantService;
import com.sky.vo.AiChatVO;

@RestController("userAiController")
@RequestMapping("/user/ai")
@Api(tags = "AI 点餐助手（dev 可演示，prod 强制登录）")
public class AiAssistantController {

    private final AiAssistantService aiAssistantService;
    private final AiUserResolver aiUserResolver;

    public AiAssistantController(AiAssistantService aiAssistantService, AiUserResolver aiUserResolver) {
        this.aiAssistantService = aiAssistantService;
        this.aiUserResolver = aiUserResolver;
    }

    /**
     * AI 对话：识别意图并执行（推荐/加购/看购物车）
     */
    @PostMapping("/chat")
    public Result<AiChatVO> chat(@RequestBody AiChatDTO aiChatDTO, HttpServletRequest request) {
        Long userId = aiUserResolver.resolve(request);
        return Result.success(aiAssistantService.chat(userId, aiChatDTO.getMessage()));
    }

    /**
     * 查询当前用户购物车（演示页展示加购结果用）
     */
    @GetMapping("/cart")
    public Result<List<ShoppingCart>> cart(HttpServletRequest request) {
        return Result.success(aiAssistantService.cart(aiUserResolver.resolve(request)));
    }
}
