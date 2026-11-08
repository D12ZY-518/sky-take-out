package com.sky.controller.internal;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sky.dto.TicketCreateDTO;
import com.sky.exception.OrderAccessDeniedException;
import com.sky.exception.OrderNotFoundException;
import com.sky.result.Result;
import com.sky.service.InternalApiService;

import io.swagger.annotations.Api;
import lombok.extern.slf4j.Slf4j;

/**
 * 内部接口（供 Python Agent 服务回调）：鉴权统一走 InternalTokenInterceptor。
 * 原则：数据/权限/事务都在 Java 侧，Python 只做模型决策与编排。
 *
 * 【面试视角·两种鉴权的分界线】（这是跨语言服务设计的高频考点）
 *   /user/**   ：鉴"人"   —— JWT 登录态，userId 从 token 解析，用户不可伪造。
 *   /internal/**：鉴"服务" —— X-Internal-Token 共享密钥，userId 由调用方（Python）显式传参。
 *   为什么这里 userId 敢用参数传？因为信任主体变了：
 *   调用方不是"不可信的浏览器用户"，而是"已通过密钥授信的内部服务"；
 *   Python 侧的 userId 来自注入的 UserContext（模型碰不到），链条仍然闭环。
 *   反例：如果 Python 把"用户嘴里说的订单号 + 系统注入的 userId"一起传过来，
 *   归属校验在 Java 做最后一道闸（见 getOrderDetail 的 403），越权依然拦得住。
 */
@RestController
@RequestMapping("/internal")
@Slf4j
@Api(tags = "内部接口（Python Agent 回调，X-Internal-Token 鉴权）")
public class InternalApiController {

    @Autowired
    private InternalApiService internalApiService;

    /**
     * 订单列表：GET /internal/orders?userId=1&status=5&limit=5
     * 【对 Python 的契约】返回 Result{code,msg,data}，data 是 List<Map>（精简字段，见 ServiceImpl.toOrderMap）。
     * limit 这里给默认值 5，Service 里还有 safeLimit 夹逼（≤0 或 >20 拉回 5）——
     * "Controller 默认值 + Service 兜底"双层防御：不信任任何一个单一入口做校验。
     */
    @GetMapping("/orders")
    public Result<List<Map<String, Object>>> listOrders(@RequestParam Long userId,
                                                        @RequestParam(required = false) Integer status,
                                                        @RequestParam(required = false, defaultValue = "5") Integer limit) {
        return Result.success(internalApiService.listOrders(userId, status, limit));
    }

    /**
     * 订单明细：GET /internal/orders/{orderNumber}?userId=1
     * 不存在 → 404；非本人 → 403（Python 工具按 HTTP 状态码区分话术）
     *
     * 【面试视角·为什么这里不用全局异常处理器？】
     * 本项目全局处理器把业务异常统一折成 200 + Result.error(code,msg)（原项目风格）。
     * 但 Python 工具需要用 HTTP 状态码区分"404 没这张单"和"403 不是你的单"来生成不同话术，
     * 所以这个端点手动 catch 并映射状态码 —— 契约优先于风格统一，局部让步于调用方诉求。
     */
    @GetMapping("/orders/{orderNumber}")
    public ResponseEntity<Result<Map<String, Object>>> orderDetail(@PathVariable String orderNumber,
                                                                   @RequestParam Long userId) {
        try {
            return ResponseEntity.ok(Result.success(internalApiService.getOrderDetail(orderNumber, userId)));
        } catch (OrderNotFoundException e) {
            return ResponseEntity.status(404).body(Result.error(e.getMessage()));
        } catch (OrderAccessDeniedException e) {
            return ResponseEntity.status(403).body(Result.error(e.getMessage()));
        }
    }

    /**
     * 创建售后工单：POST /internal/tickets
     * 【写操作】Python 侧唯一会让 Java 落库的工具（insert customer_service_ticket）。
     */
    @PostMapping("/tickets")
    public Result<Map<String, String>> createTicket(@RequestBody TicketCreateDTO dto) {
        // 服务端再做一次基础校验，不信调用方一定规矩
        // 【原则】"内部服务"只代表密钥对上了，不代表传来的业务数据天然合法 —— 校验看数据，授信看信道。
        if (dto.getUserId() == null) {
            return Result.error("userId 不能为空");
        }
        if (dto.getCategory() == null || dto.getCategory().trim().isEmpty()
                || dto.getContent() == null || dto.getContent().trim().isEmpty()) {
            return Result.error("category 与 content 不能为空");
        }
        if (dto.getRequestId() == null || !dto.getRequestId().matches("[A-Za-z0-9_-]{8,64}")) {
            return Result.error("requestId 格式不合法");
        }
        String ticketNo;
        try {
            ticketNo = internalApiService.createTicket(dto);
        } catch (IllegalArgumentException ex) {
            // 同 ID 不同载荷被拒绝，只能证明本次没再插入；原请求可能已经成功。
            Result<Map<String, String>> conflict = Result.error(ex.getMessage());
            conflict.setData(Collections.singletonMap("outcome", "unknown"));
            return conflict;
        }
        log.info("售后工单已创建：{} userId={} category={}", ticketNo, dto.getUserId(), dto.getCategory());
        return Result.success(Collections.singletonMap("ticketNo", ticketNo));
    }
}
