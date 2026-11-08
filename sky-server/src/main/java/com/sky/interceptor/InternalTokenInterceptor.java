package com.sky.interceptor;

import java.io.PrintWriter;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import com.alibaba.fastjson.JSON;
import com.sky.result.Result;

import lombok.extern.slf4j.Slf4j;

/**
 * 内部接口令牌拦截器：只拦截 /internal/**。
 * /internal/** 已在 JWT 用户/管理员拦截器的路径之外（它们只管 /user/**、/admin/**），
 * 这里单独校验共享密钥 X-Internal-Token，把"用户登录态"与"服务间授信"两套鉴权分开。
 *
 * 【面试视角·拦截器三问】
 * 1. 为什么用拦截器不用 Filter？—— HandlerInterceptor 能拿到 handler（Controller 方法），
 *    且在 Spring 体系内注册（见 WebMvcConfiguration.addInterceptors），路径管理统一；
 *    Filter 更靠外，适合全局编码/CORS 这类与业务无关的事。
 * 2. 为什么校验失败自己写响应、不抛异常？—— 拦截器里抛普通异常会被兜成 500，
 *    而这里想精确表达 401 语义；preHandle 返回 false + 手写响应体，语义最干净。
 * 3. 密钥从哪来？—— @Value 读 application.yml 的 sky.ai.internal-token，
 *    Python 侧 .env 里 INTERNAL_TOKEN 必须同值，这就是"共享密钥"（对称授信）。
 * 【加分话题】equals 比较密钥理论上存在时序侧信道，内网演示可接受；
 * 生产可用 MessageDigest.isEqual（常量时间比较）并加密钥轮换。
 */
@Component
@Slf4j
public class InternalTokenInterceptor implements HandlerInterceptor {

    @Value("${sky.ai.internal-token}")
    private String internalToken;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        // 逐请求取头比对（对应 Python tools.py 里 _HEADERS 每次都带的 X-Internal-Token）
        String token = request.getHeader("X-Internal-Token");
        if (internalToken.equals(token)) {
            return true; // 放行 → 进入 Controller
        }
        // 失败要留痕：谁在调、调什么路径 —— 内部接口被扫也是安全事件
        log.warn("内部接口令牌校验失败：path={}, 来自={}", request.getRequestURI(), request.getRemoteAddr());
        response.setStatus(401);
        response.setContentType("application/json;charset=utf-8");
        // 手写 JSON 响应体（沿用项目 Result 格式），让 Python 侧能按 401 统一报"内部接口令牌无效"
        try (PrintWriter writer = response.getWriter()) {
            writer.write(JSON.toJSONString(Result.error("内部接口令牌无效")));
        }
        return false;
    }
}
