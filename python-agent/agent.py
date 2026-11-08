# agent.py —— Agent 的"大脑"：用 LangChain 1.x 的 create_agent 装配 ReAct 循环 + LangGraph 多轮记忆
#
# 【一轮对话的完整时序】（ReAct = Reason 推理 + Act 行动，反复交替直到给出答案）
#   ① main.py 调 agent.run(userId, sessionId, message)
#   ② invoke 把「系统提示词 + 该会话的历史消息(checkpointer 按 thread_id 取) + 本轮用户消息」发给 DeepSeek
#   ③ 模型决策：直接回答，或"我要调 query_orders"（返回带 tool_calls 的 AIMessage）
#   ④ 命中工具 → 框架按 tool_calls 执行 tools.py 里对应函数（此时 runtime.context 已注入 userId）
#   ⑤ 工具返回的字符串包成 ToolMessage 追加进消息列表，再发给模型
#   ⑥ 模型看到工具结果 → 组织成人话，输出最终 AIMessage（循环结束）
#      （一次对话里 ③~⑥ 可能来回多轮，比如先查列表再查明细）
#
# 【与手写版的本质区别】手写版（AiAssistantServiceImpl）要自己拼消息列表、自己解析
#   tool_calls JSON、自己调度函数；create_agent 把这套循环全部内置，我们只声明"用什么模型、
#   有哪些工具、遵守什么规则"。（类比：手写 JDBC vs MyBatis）
from langchain.agents import create_agent        # LangChain 1.x 新 API；老教程的 AgentExecutor 已是过去式
from langchain_openai import ChatOpenAI          # OpenAI 协议客户端，配 base_url 即可连 DeepSeek
from langgraph.graph.state import CompiledStateGraph
from langgraph.graph.message import REMOVE_ALL_MESSAGES
from langchain_core.messages import AIMessage, RemoveMessage, ToolMessage
from contextlib import contextmanager, nullcontext
from threading import Lock
from weakref import WeakValueDictionary
import uuid

import config as c
from managed_memory import ManagedMemorySaver
from tools import UserContext, query_orders, query_order_detail, create_ticket, query_faq

# 2. 系统提示词：给模型定身份 + 防幻觉铁律（= 手写版里注入的 system prompt，作用完全相同）。
#    这 8 条是行为约束的"宪法"，尤其第 1、4、6、8 条：事实和能力只能来自工具返回，
#    模型自己"背"出来的订单号/金额/规则数字一律不可信 —— 这就是防幻觉设计。
SYSTEM_PROMPT = """你是"禾巷餐饮"的售后客服助手，用中文回复，简洁、有同理心。
工作规则：
1. 订单相关问题（状态/金额/明细/进度）必须先调用工具查询，禁止凭印象编造订单号、金额、时间；
2. 你只能查询当前登录用户自己的订单；工具返回"查不到/无权"时，如实转告并建议核对订单号；
3. 退款只能解释规则、登记工单，不能承诺"已经退款/一定能退"；用户坚持要人工处理时调用 create_ticket；
4. 回复中的金额与时间必须引用工具返回的原文，不要改写数字；
5. 用户问题与订单、售后无关时，礼貌说明你只负责外卖售后相关问题；
6. 退款规则、配送费、起送价、优惠券、支付方式、会员等【通用规则类】问题先调 query_faq 检索 FAQ，
   答案按原文引用，不要凭印象编造规则里的金额、时长、阈值；
7. 区分两类问题：问"我的订单到哪了"用 query_orders，问"退款多久到账"用 query_faq；
8. 只承诺已经由工具实际完成的动作。当前没有向已有工单追加文字、照片或附件的工具；不要承诺用户回复后你会补充工单或转达材料。用户要补充资料时，说明当前无法在对话中追加，建议等待人工客服联系后提供。
9. 建工单工具若提示“提交结果无法确认”，不得说已创建或创建失败；不要自行换一条诉求重建。FAQ 提示无法核实时，不得凭印象补编金额、时限或权益。
10. 当前订单工具只提供订单状态、总额、时间与菜品明细，不提供配送费拆分、优惠券抵扣或退款流水。不能承诺拿到订单号后就能核对这些字段；需要核实时说明当前工具做不到，可建议人工处理。
11. 后端存在接口或支付对接代码，不等于前端按钮存在、真实支付渠道可用或运营政策已获确认。特别不能把“代码有微信支付对接”改写成“目前确认支持微信支付”。"""

# 3. 装配 Agent —— 懒加载设计（Important）
#    ChatOpenAI 在构造时就校验 api_key：如果模块导入时（服务启动时）就装配，
#    没配 key 会让整个服务（连 /health）都起不来，运维没法先起服务再补配置。
#    所以改成"第一次真正对话时才创建"，之后一直复用同一个实例。
#    checkpointer 也在此时创建且只创建一次 —— 换新的存档器 = 历史清零，多轮记忆就断了。
_TOOLS = [query_orders, query_order_detail, create_ticket, query_faq]
_agent = None
_agent_init_lock = Lock()
# 仅串行化同一用户的同一会话；不同会话仍可并发。弱引用避免锁表随历史会话永久增长。
# 每次调用持有锁的强引用（包括等待获取锁的调用），最后一个调用结束后自动回收。
_session_locks = WeakValueDictionary()
_session_locks_guard = Lock()


@contextmanager
def _lock_session(thread_id: str):
    with _session_locks_guard:
        lock = _session_locks.get(thread_id)
        if lock is None:
            lock = Lock()
            _session_locks[thread_id] = lock
    with lock:
        yield


def _get_agent():
    global _agent          # 声明要修改模块级变量 _agent（Python 里不改则不用写 global）
    if _agent is None:
        with _agent_init_lock:
            # 等待构造的请求必须再检查一次，否则仍会创建多个独立 checkpointer。
            if _agent is None:
                _agent = create_agent(
                    model=ChatOpenAI(
                        model=c.DEEPSEEK_MODEL,
                        api_key=c.DEEPSEEK_API_KEY,
                        base_url=c.DEEPSEEK_BASE_URL,
                        temperature=0.3,
                        timeout=c.MODEL_TIMEOUT_SECONDS,
                        max_retries=c.MODEL_MAX_RETRIES,
                    ),
                    tools=_TOOLS,
                    system_prompt=SYSTEM_PROMPT,
                    checkpointer=ManagedMemorySaver(
                        max_sessions=c.AGENT_MEMORY_MAX_SESSIONS,
                        max_turns=c.AGENT_MEMORY_MAX_TURNS,
                        ttl_seconds=c.AGENT_MEMORY_TTL_SECONDS,
                    ),  # 有容量与闲置过期边界；进程重启仍会失忆。
                    context_schema=UserContext,
                )
    return _agent


def _reply_text(messages) -> str:
    """只接受完整的最终模型回复，并剔除结构化内容中的推理等非文本块。"""
    if not messages:
        raise RuntimeError("Agent returned no messages")
    final = messages[-1]
    if final.type != "ai" or getattr(final, "tool_calls", None):
        raise RuntimeError("Agent did not finish with an assistant response")
    content = final.content
    if isinstance(content, str):
        reply = content
    elif isinstance(content, list):
        reply = "".join(
            block if isinstance(block, str) else block.get("text", "")
            for block in content
            if isinstance(block, str) or (
                isinstance(block, dict) and block.get("type") == "text"
                and isinstance(block.get("text"), str)
            )
        )
    else:
        raise RuntimeError("Agent returned unsupported response content")
    if not reply.strip():
        raise RuntimeError("Agent returned an empty assistant response")
    return reply.strip()


def _repair_interrupted_tool_calls(graph, config):
    """新用户轮开始前补齐中断调用的协议回执，保留已有真实结果且不恢复旧工具。"""
    if not isinstance(graph, CompiledStateGraph):
        return
    state = graph.get_state(config)
    messages = state.values.get("messages", [])
    repaired = []
    pending = {}
    changed = False

    def close_pending():
        nonlocal changed
        for call_id, name in pending.items():
            repaired.append(ToolMessage(
                content="上轮工具调用已中断，执行结果无法确认；本次仅补齐对话记录，没有重新执行工具。"
                        "不得将此调用视为成功或自动重新提交；涉及工单时请保留原请求 ID 核对。",
                tool_call_id=call_id, name=name, status="error",
            ))
            changed = True
        pending.clear()

    for item in messages:
        if isinstance(item, ToolMessage):
            pending.pop(item.tool_call_id, None)
            repaired.append(item)
            continue
        # 已失败的后续请求也可能留下 HumanMessage；回执必须排在它们之前。
        close_pending()
        repaired.append(item)
        if isinstance(item, AIMessage):
            pending.update((call["id"], call["name"]) for call in item.tool_calls)
    close_pending()
    if changed:
        # 用公共接口替换完整消息顺序；普通 add_messages 只会把补偿回执追加到历史末尾。
        # as_node="tools" 表示补齐工具回执，不执行 checkpoint 中尚待调度的旧写动作。
        graph.update_state(config, {"messages": [RemoveMessage(id=REMOVE_ALL_MESSAGES), *repaired]},
                           as_node="tools")


def run(user_id: int, session_id: str, message: str, request_id: str | None = None,
        context: UserContext | None = None) -> tuple[str, list[str]]:
    """跑一轮对话。
    :param user_id:   真实登录用户 id（来自 Java 解析好的登录态，不是模型给的）
    :param session_id: 原始会话 id；与真实用户 id 一起组成记忆键
    :param message:   用户这句话
    :param context:   HTTP 层传入的本次执行上下文；工具在其中记录写入结果，不参与历史记忆
    :return: (最终回复文本, 本轮实际调用过的工具名列表)  —— 工具名单用于前端展示/日志观测
    """
    if context is not None and context.user_id != user_id:
        raise ValueError("context user_id must match the authenticated user")
    thread_id = f"user-{user_id}:{session_id}"
    with _lock_session(thread_id):
        graph = _get_agent()
        run_config = {"configurable": {"thread_id": thread_id},
                      "recursion_limit": c.AGENT_RECURSION_LIMIT}
        store = getattr(graph, "checkpointer", None)
        managed = isinstance(store, ManagedMemorySaver)
        with store.session(thread_id) if managed else nullcontext():
            try:
                _repair_interrupted_tool_calls(graph, run_config)
                result = graph.invoke(
                    {"messages": [{"role": "user", "content": message}]},
                    context=context if context is not None else UserContext(
                        user_id=user_id, request_id=request_id or uuid.uuid4().hex),
                    config=run_config,
                )
            finally:
                if managed:
                    store.compact(thread_id)
    messages = result["messages"]
    # ⑤⑥ 走完后 messages 里是完整轨迹，形如：
    #   [HumanMessage(用户), AIMessage(带tool_calls), ToolMessage(工具结果),
    #    AIMessage(带tool_calls), ToolMessage(工具结果), ..., AIMessage(最终回复)]
    # checkpointer 会把历史轮次也放在 messages 中；只看最后一条 HumanMessage 之后的工具结果。
    # 否则第二轮会把第一轮的 query_orders 错报为本轮调用，污染前端展示和调用次数统计。
    last_human = max((i for i, m in enumerate(messages) if m.type == "human"), default=-1)
    tool_calls = [m.name for m in messages[last_human + 1:]
                  if m.type == "tool" and isinstance(m.name, str) and m.name]
    reply = _reply_text(messages)
    return reply, tool_calls
