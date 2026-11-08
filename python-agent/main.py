# main.py —— 门童：FastAPI 对外 HTTP 层（= Java 里的 Controller 层）
#
# 【它在架构里的位置】
#   Java 端 ServiceAgentController --HTTP--> 这里 main.py --函数调用--> agent.py --工具回调--> Java /internal/**
#   职责单一：收 JSON → 调 agent.run() → 回 JSON。不含任何业务规则。
#
# 【FastAPI vs Spring MVC 速查】
#   @app.post("/agent/chat")     ≈ @PostMapping("/agent/chat")
#   def chat(req: ChatReq)       ≈ 方法参数 @RequestBody ChatReq（Pydantic 自动反序列化+校验）
#   直接 return ChatResp(...)    ≈ 返回 DTO，FastAPI 自动序列化成 JSON（无需 @RestController）
#   BaseModel                    ≈ 带 @Data + @Valid 的 DTO：字段类型就是校验规则
import hmac
import traceback   # 打印完整异常栈用（= log.error("...", e) 的效果）
import threading    # 启动后台预热线程用（FastAPI 不阻塞主线程起服务）
import uuid

import agent as agent_module   # 导入模块本身（而非函数）：这样在测试里可以替换 agent_module.run 打桩
import config as c
import faq_rag as faq_rag_module   # 启动时后台预热 RAG 索引用（实际加载在子线程内，不阻塞）
from contextlib import asynccontextmanager
from fastapi import FastAPI, Header, HTTPException
from pydantic import BaseModel, Field, StringConstraints
from typing import Annotated, Literal
from tools import UserContext
from request_state import RequestCapacityError, RequestConflict, RequestStateCache


# FastAPI 1.x 推荐 lifespan 取代 on_event：在应用启动/关闭时跑一段代码（= Spring 的 @PostConstruct）
# 这里用 lifespan 在启动时起一个【后台守护线程】触发 RAG 预热：
#   ① 模型下载约 2.2GB（首次），主线程不阻塞，服务秒级起来；
#   ② warm_up 内部已 try-except，失败只打日志，服务依然可用（RAG 不可用时退化为前三件工具）。
@asynccontextmanager
async def lifespan(app: FastAPI):
    threading.Thread(
        target=faq_rag_module.warm_up,
        daemon=True,                  # daemon=True：进程退出时不等它，避免卡关停
        name="faq_rag_warmup",
    ).start()
    yield                              # yield 之前=启动后逻辑，yield 之后=关闭前逻辑


app = FastAPI(title="禾巷餐饮 Python Agent 服务", lifespan=lifespan)   # ≈ new SpringApplication().run()，应用入口


# 请求体：{"userId":1, "sessionId":"1", "message":"我的订单到哪了"}
# Pydantic 模型：类型注解即校验（userId 缺失/不是 int → 自动 422，不用手写 @NotNull）
class ChatReq(BaseModel):
    userId: int = Field(strict=True, gt=0)
    sessionId: Annotated[str, StringConstraints(
        strip_whitespace=True, min_length=1, max_length=128)] = "default"
    message: Annotated[str, StringConstraints(
        strip_whitespace=True, min_length=1, max_length=2000)]
    # 与 Java 建单接口格式一致；缺省仅保证本次调用内部不重复建单。
    requestId: Annotated[str, StringConstraints(
        pattern=r"^[A-Za-z0-9_-]{8,64}$")] | None = None


# 响应体：{"reply":"……", "toolCalls":["query_orders"]}
# 契约与 Java 侧 PythonAgentClient 的解析逻辑对应（reply/toolCalls 两个 key）
class ChatResp(BaseModel):
    reply: str
    toolCalls: list[str]
    # completed=本轮处理已结束，不等于工单/退款业务成功；unknown=结果需保留 ID 核对。
    outcome: Literal["completed", "unknown"]


_request_states = RequestStateCache(c.REQUEST_CACHE_TTL_SECONDS, c.REQUEST_CACHE_MAX_ENTRIES)


@app.post("/agent/chat")
def chat(req: ChatReq, x_internal_token: str | None = Header(default=None)):
    # 这里是 Java -> Python 的服务间入口。userId 由 Java 解析 JWT 后传入，
    # 因此必须先验证调用方确实是 Java 服务；否则攻击者能直接请求此接口并伪造 userId。
    # compare_digest 避免普通字符串比较在理论上泄露比较耗时差异。
    if (not c.INTERNAL_TOKEN or not x_internal_token
            or not hmac.compare_digest(x_internal_token.encode("utf-8"),
                                       c.INTERNAL_TOKEN.encode("utf-8"))):
        raise HTTPException(status_code=401, detail="invalid internal token")
    # 没配 key：接口不报 500，回一句可展示的兜底文案（与 Java 手写版同款"优雅降级"体验）
    if not c.DEEPSEEK_API_KEY:
        return ChatResp(reply="AI 服务未配置 DEEPSEEK_API_KEY，请在 python-agent/.env 中配置后重启。",
                        toolCalls=[], outcome="unknown")
    request_id = req.requestId or uuid.uuid4().hex
    if req.requestId is None:
        # 调用方没有可复用的 ID，只保留本次工具执行事实，不占用跨请求缓存。
        return _run_chat(req, UserContext(req.userId, request_id))
    try:
        with _request_states.lease(req.userId, request_id, req.sessionId, req.message) as state:
            if state.response is not None and state.response.outcome == "completed":
                return state.response
            state.response = _run_chat(req, state.context)
            return state.response
    except RequestConflict:
        return ChatResp(reply="该请求 ID 已绑定原会话和原消息，不能用于修改后的诉求；请先核对原请求结果。",
                        toolCalls=[], outcome="unknown")
    except RequestCapacityError:
        return ChatResp(reply="当前请求较多，请保留本次请求 ID，稍后重试。",
                        toolCalls=[], outcome="unknown")


def _run_chat(req: ChatReq, context: UserContext) -> ChatResp:
    try:
        # 核心一步：转手给 agent 层。门童不关心 ReAct 循环怎么转，只关心拿到 (reply, toolCalls)
        # agent.run 统一按可信 userId 隔离历史，也保护绕过 HTTP 层的内部调用。
        reply, tool_calls = agent_module.run(req.userId, req.sessionId, req.message,
                                            request_id=context.request_id, context=context)
    except Exception as e:
        traceback.print_exc()   # 栈打在服务端控制台（= 服务端日志），客户端只收到友好文案
        # 写入成功后模型生成失败，仍可以依据真实工单号确认结果。
        if context.ticket_no and not context.write_result_unknown:
            return ChatResp(reply=f"工单已登记，工单号 {context.ticket_no}。请等待商家核实，登记不代表退款完成。",
                            toolCalls=["create_ticket"], outcome="completed")
        return ChatResp(reply="AI 服务暂时不可用，操作结果需核对，请保留请求 ID。",
                        toolCalls=[], outcome="unknown")
    if context.write_result_unknown:
        # 此时模型即使输出了“已创建”，也不能覆盖工具记录的未知状态。
        return ChatResp(reply="工单提交结果暂时无法确认。请保留请求 ID，先核对原工单结果再重试。",
                        toolCalls=tool_calls, outcome="unknown")
    if context.ticket_no:
        reply = f"工单已登记，工单号 {context.ticket_no}。请等待商家核实，登记不代表退款完成。"
    elif context.ticket_error or "create_ticket" in tool_calls:
        reply = context.ticket_error or "本次未能登记工单，请核对类别、诉求内容及关联订单后再试。"
    elif context.order_access_denied:
        reply = "无法查看该订单，请核对订单号并确认是当前账户的订单。"
    return ChatResp(reply=reply, toolCalls=tool_calls, outcome="completed")


@app.get("/health")
def health():
    # 存活探针：部署/联调时先打这个接口确认服务起来了（= Spring Actuator 的 /health）
    return {"status": "ok"}
