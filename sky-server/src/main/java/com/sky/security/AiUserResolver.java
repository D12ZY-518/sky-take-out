package com.sky.security;

import javax.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.sky.constant.JwtClaimsConstant;
import com.sky.exception.UserNotLoginException;
import com.sky.properties.JwtProperties;
import com.sky.utils.JwtUtil;

import io.jsonwebtoken.Claims;

/**
 * AI 接口统一身份解析器。
 *
 * <p>开发演示模式允许没有 token 的请求使用演示用户；生产模式必须提供合法 JWT。
 * 调用方一旦主动携带 token，即使处于演示模式也必须校验成功，不能把坏 token
 * 静默降级成演示用户，否则会掩盖前端登录态错误。</p>
 */
@Component
public class AiUserResolver {

    private static final Long DEMO_USER_ID = 1L;

    private final JwtProperties jwtProperties;
    private final boolean demoEnabled;

    public AiUserResolver(JwtProperties jwtProperties,
                          @Value("${sky.ai.demo-enabled:false}") boolean demoEnabled) {
        this.jwtProperties = jwtProperties;
        this.demoEnabled = demoEnabled;
    }

    public Long resolve(HttpServletRequest request) {
        String token = request.getHeader(jwtProperties.getUserTokenName());

        // 1. 没带 token：仅开发演示模式可以降级到固定演示用户。
        if (token == null || token.trim().isEmpty()) {
            if (demoEnabled) {
                return DEMO_USER_ID;
            }
            throw new UserNotLoginException("请先登录后再使用 AI 服务");
        }

        // 2. 带了 token：无论什么环境都必须解析成功，禁止坏 token 静默降级。
        try {
            Claims claims = JwtUtil.parseJWT(jwtProperties.getUserSecretKey(), token);
            Object userId = claims.get(JwtClaimsConstant.USER_ID);
            if (userId == null) {
                throw new IllegalArgumentException("JWT 缺少 userId");
            }
            return Long.valueOf(userId.toString());
        } catch (Exception e) {
            throw new UserNotLoginException("登录状态无效，请重新登录");
        }
    }
}
