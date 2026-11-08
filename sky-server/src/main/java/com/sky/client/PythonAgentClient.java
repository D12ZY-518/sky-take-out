package com.sky.client;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.sky.vo.ServiceChatVO;

import lombok.extern.slf4j.Slf4j;

/**
 * Python Agent 服务 HTTP 客户端：Java → POST {python}/agent/chat。
 * 用 JDK 自带 java.net.http.HttpClient（与 AiAssistantServiceImpl 同款，不引额外依赖）。
 *
 * 【学习要点】
 * 1. 技术选型：Spring 有 RestTemplate(老化)/WebClient(需 webflux 依赖)/OpenFeign(需注册中心)，
 *    这里只有"一次 POST"的诉求，JDK 原生 HttpClient 零依赖最合适 —— 按需选型，不为炫技引依赖。
 * 2. 降级设计：Python 挂了/超时/返回异常，本方法【不抛异常】，返回 fallback 文案。
 *    客服对话属于"可用性优先"场景，宁可回"稍后再试"，不能让用户端白屏 500。
 * 3. 超时预算：connectTimeout 10s(建连) + request timeout 60s(等响应)。
 *    60s 是给 Agent 内部多轮工具往返留的（一轮对话可能查列表→查明细→建工单 3 次回调 Java）。
 */
@Component
@Slf4j
public class PythonAgentClient {

    @Value("${sky.ai.python-agent-base-url}")
    private String pythonAgentBaseUrl;

    /**
     * Java 调用 Python Agent 时也必须证明自己的服务身份。不能因为 Python
     * 最终会回调 Java，就让任何能访问 8000 端口的调用方随意传 userId。
     */
    @Value("${sky.ai.internal-token}")
    private String internalToken;

    // Agent 一轮可能发生多次工具往返，超时给到 60 秒
    // 显式锁定 HTTP/1.1：JDK HttpClient 默认会先发 h2c Upgrade 协商，uvicorn 不接受该升级，
    // 会以 422/“Unsupported upgrade request”回应；Python Agent 这边没有 HTTP/2 需求。
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10)).build();

    public ServiceChatVO chat(Long userId, String sessionId, String message, String requestId) {
        JSONObject body = new JSONObject();
        body.put("userId", userId);
        body.put("sessionId", sessionId);
        body.put("message", message);
        body.put("requestId", requestId);
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(pythonAgentBaseUrl + "/agent/chat"))
                    .header("Content-Type", "application/json")
                    .header("X-Internal-Token", internalToken)
                    .timeout(Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofString(body.toJSONString(), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = HTTP_CLIENT.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                log.error("Python Agent 返回非 200：status={} body={}", response.statusCode(), response.body());
                return fallback("Python Agent 服务响应异常（" + response.statusCode() + "），稍后再试～");
            }
            JSONObject data = JSON.parseObject(response.body());
            if (data == null || !("completed".equals(data.getString("outcome"))
                    || "unknown".equals(data.getString("outcome")))
                    || data.getString("reply") == null) {
                return fallback("Python Agent 返回结果不完整，请保留请求 ID 核对处理结果。");
            }
            List<String> toolCalls = new ArrayList<>();
            JSONArray arr = data.getJSONArray("toolCalls");
            if (arr != null) {
                for (int i = 0; i < arr.size(); i++) {
                    toolCalls.add(arr.getString(i));
                }
            }
            return ServiceChatVO.builder().reply(data.getString("reply")).toolCalls(toolCalls)
                    .outcome(data.getString("outcome")).build();
        } catch (Exception e) {
            log.error("调用 Python Agent 异常：{}", e.getMessage());
            return fallback("Python Agent 服务暂时不可用（请确认 8000 端口服务已启动），稍后再试～");
        }
    }

    private ServiceChatVO fallback(String reply) {
        return ServiceChatVO.builder().reply(reply).toolCalls(new ArrayList<>()).outcome("unknown").build();
    }
}
