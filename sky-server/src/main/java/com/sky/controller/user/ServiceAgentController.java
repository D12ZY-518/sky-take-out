package com.sky.controller.user;

import javax.servlet.http.HttpServletRequest;
import java.util.UUID;
import java.util.Collections;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sky.client.PythonAgentClient;
import com.sky.dto.ServiceChatDTO;
import com.sky.result.Result;
import com.sky.security.AiUserResolver;
import com.sky.service.InternalApiService;
import com.sky.vo.ServiceChatVO;

import io.swagger.annotations.Api;
import lombok.extern.slf4j.Slf4j;

/**
 * 售后客服 Agent（Python 版）：只做转发，决策/编排/记忆都在 Python Agent 服务。
 * 与 /user/ai/chat（Java 手写版）并存，便于对比"框架 vs 手写"两条路线。
 *
 * 【面试视角·完整调用环】（讲清这张图，跨语言微服务这条线就立住了）
 *   用户 → 本接口(解析登录态拿 userId) → PythonAgentClient(HTTP) → Python /agent/chat
 *        → Python Agent 决策调工具 → Python 工具回调 Java /internal/**（X-Internal-Token 鉴权）
 *        → Java 查库/落库返回 → Python 组织回复 → 原路返回给用户
 *   职责切分：Java=数据与权限的"事实源"，Python=大模型决策编排。数据不过 Python 的手长期保存，
 *   Python 挂了 Java 有兜底文案（见 PythonAgentClient.fallback），单侧故障不影响整体可用。
 */
@RestController
@RequestMapping("/user/service")
@Slf4j
@Api(tags = "售后客服 Agent（Python LangChain，dev 可演示，prod 强制登录）")
public class ServiceAgentController {

    private final PythonAgentClient pythonAgentClient;
    private final AiUserResolver aiUserResolver;
    private final InternalApiService internalApiService;

    public ServiceAgentController(PythonAgentClient pythonAgentClient, AiUserResolver aiUserResolver,
                                  InternalApiService internalApiService) {
        this.pythonAgentClient = pythonAgentClient;
        this.aiUserResolver = aiUserResolver;
        this.internalApiService = internalApiService;
    }

    /**
     * 售后对话：转发给 Python Agent
     */
    @PostMapping("/chat-python")
    public Result<ServiceChatVO> chatPython(@RequestBody ServiceChatDTO dto, HttpServletRequest request) {
        Long userId = aiUserResolver.resolve(request);
        // 不传 sessionId 时按用户隔离默认会话，避免不同用户共用一段多轮记忆
        String sessionId = (dto.getSessionId() == null || dto.getSessionId().trim().isEmpty())
                ? "user-" + userId : dto.getSessionId();
        String requestId = (dto.getRequestId() == null || dto.getRequestId().trim().isEmpty())
                ? UUID.randomUUID().toString() : dto.getRequestId().trim();
        if (!requestId.matches("[A-Za-z0-9_-]{8,64}")) {
            return Result.error("requestId 格式不合法");
        }
        log.info("Python Agent 对话 userId={} sessionId={} requestId={}", userId, sessionId, requestId);
        return Result.success(pythonAgentClient.chat(userId, sessionId, dto.getMessage(), requestId));
    }

    /** 结果核对不经过模型，只按可信用户身份 + 原请求 ID 查已提交的工单。 */
    @GetMapping("/ticket-result")
    public Result<ServiceChatVO> ticketResult(@RequestParam String requestId, HttpServletRequest request) {
        Long userId = aiUserResolver.resolve(request);
        if (!requestId.matches("[A-Za-z0-9_-]{8,64}")) {
            return Result.error("requestId 格式不合法");
        }
        String ticketNo = internalApiService.findTicketNoByRequestId(userId, requestId);
        return Result.success(ServiceChatVO.builder()
                .reply(ticketNo == null
                        ? "暂未查到这次请求的工单，原请求可能仍在处理中；重试请保持原请求 ID。"
                        : "已核对原工单，工单号 " + ticketNo + "。本次没有重复创建，工单登记不代表退款完成。")
                .outcome(ticketNo == null ? "unknown" : "completed")
                .toolCalls(Collections.emptyList()).build());
    }

}
