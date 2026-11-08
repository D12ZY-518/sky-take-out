# tools.py —— Agent 的"手脚"：模型只能动嘴决策，真正干活的是这里的 4 个工具
#
# 【它在架构里的位置】
#   agent.py 装配 create_agent(tools=[这里的函数]) → 模型看中某个工具 →
#   LangChain 框架自动调用对应函数 → 函数返回的字符串被喂回模型 → 模型组织成人话。
#   分工铁律：Python 只做"模型决策 + 编排"，真正查库/落库在 Java /internal/**，
#   这里用 httpx 回调 Java（MOCK_JAVA=1 时走本地假数据，不依赖 Java 即可联调）。
#
# 【@tool 装饰器干了什么】（装饰器 = Java 注解 + APT 生成器的合体，运行期生效）
#   1. 读函数的 docstring → 变成给【模型】看的工具说明书（所以 docstring 必须写清
#      "做什么 + 参数含义 + 枚举值"，模型全靠它决定什么时候用、参数怎么填）；
#      没有 docstring 直接报 ValueError，框架强制你写。
#   2. 读函数签名（参数名/类型注解）→ 生成 JSON Schema 发给模型选参；
#      ⚠ runtime 参数会被框架排除在 Schema 外，模型根本不知道它的存在。
#   3. 原函数被包进 StructuredTool 对象（query_orders 变成"工具"不再是普通函数），
#      想在单元测试里直接调原始函数，用 query_orders.func(...)。
import re
from dataclasses import dataclass   # dataclass = 自动生成构造器/getter 的 Lombok @Data 平替
from threading import Lock
from typing import Annotated
from uuid import uuid4

import httpx                        # Python 界最常用的 HTTP 客户端（类比 Java 的 HttpClient/OkHttp）
from langchain.tools import tool, ToolRuntime
from pydantic import Field

import config as c                  # 导入配置模块（注意：c 是别名，本文件统一用 c.XXX 取值）
import faq_rag                       # RAG 检索模块（lazy import 已在 faq_rag 内部做了，这里只引模块名）

# ==================== 运行时上下文：防越权的核心机制 ====================
# 【为什么 userId 不能写进工具参数】
#   工具参数是模型填的，而模型会被用户消息影响——用户说"帮我查 user 2 的订单"，
#   模型就可能真填 user_id=2，越权。解法：参数里根本不出现 userId，
#   真实身份由我们自己的代码经 agent.invoke(context=UserContext(...)) 注入，
#   模型看不到也改不了（Java 对照：不能信前端传的 userId，要从登录态 token 里取）。
#
# 【数据流】main.py 收到请求里的 userId → agent.run() 包成 UserContext →
#   → LangChain 执行工具时把它塞进 runtime.context → 工具函数里 runtime.context.user_id 取出。
@dataclass
class UserContext:
    user_id: int   # 当前登录用户 id，由代码注入，模型不可见。必填字段（不写默认值=构造时必须传）
    request_id: str = ""  # 同一用户发送动作的幂等键，模型不可见；仅写工具使用
    # 仅属于本次请求的执行事实；工具写入、HTTP 层读取，不能由模型文案决定。
    write_result_unknown: bool = False
    ticket_no: str | None = None
    ticket_error: str | None = None  # 明确拒绝的可信结果，不能被模型改写成创建成功
    order_access_denied: bool = False

    def __post_init__(self):
        # LangChain 可并行执行同轮工具；锁与首次提交内容只属于当前上下文，
        # 不进入模型参数或会话检查点。锁覆盖 HTTP 请求，避免两个调用互相覆盖写入事实。
        self._ticket_lock = Lock()
        self._ticket_payload: dict | None = None

# 订单状态码 → 中文（字典 = Java 的 Map.of(...)；含义与 Java Orders 实体常量保持一致）
STATUS_TEXT = {1: "待付款", 2: "待接单", 3: "已接单", 4: "派送中", 5: "已完成", 6: "已取消"}

# ==================== mock 假数据（来源：本地 sky_take_out 库 user_id=1 的真实订单） ====================
# 列表套字典：Python 里最常用的"轻量 DTO"写法，o["number"] 取值 = Java 的 map.get("number")
_MOCK_ORDERS = [
    {"number": "1788231207410", "user_id": 1, "status": 5, "amount": "10.00", "order_time": "2026-09-01 10:53:27"},
    {"number": "cancel_test_1", "user_id": 1, "status": 6, "amount": "10.00", "order_time": "2026-08-30 22:51:33"},
    {"number": "1787893545201", "user_id": 1, "status": 5, "amount": "10.00", "order_time": "2026-08-28 13:05:45"},
    {"number": "test123", "user_id": 1, "status": 6, "amount": "10.00", "order_time": "2026-08-28 13:02:34"},
    # user_id=4 的两单（1776845110793 / 1775410154429）【故意】不放进来：
    # user 1 去查它们时应当走"查不到/无权"分支，用于验收越权防护（Java 侧 404/403 同理）。
]

# 订单明细：key=订单号，value=该单的菜品行列表（字典 = Java 的 Map<String, List<Detail>>）
_MOCK_DETAILS = {
    "1788231207410": [{"name": "王老吉", "number": 1, "amount": "6.00"},
                      {"name": "北冰洋", "number": 1, "amount": "4.00"}],
    "1787893545201": [{"name": "王老吉", "number": 1, "amount": "6.00"},
                      {"name": "北冰洋", "number": 1, "amount": "4.00"}],
}

# ==================== 调 Java 内部接口的公共零件 ====================
_HEADERS = {"X-Internal-Token": c.INTERNAL_TOKEN}   # 服务间鉴权头，token 必须与 Java yml 一致，否则 401
_TIMEOUT = httpx.Timeout(10.0)                       # 连接+读取总超时：Agent 一轮可能多次回调 Java，不设超时会被慢请求拖死
# Java 内部服务地址应直连；开发机的系统代理曾把 localhost 请求转走并返回 502。
# 仅作用于这三个 Java 工具请求，不改变 DeepSeek 客户端的代理配置。
_JAVA_HTTP_OPTIONS = {"headers": _HEADERS, "timeout": _TIMEOUT, "trust_env": False}
_ORDER_NUMBER_PATTERN = r"[A-Za-z0-9_-]{1,50}"


def _user_context(runtime) -> UserContext | None:
    context = getattr(runtime, "context", None)
    if (not isinstance(context, UserContext)
            or type(context.user_id) is not int or context.user_id <= 0):
        return None
    return context


def _valid_order_number(order_number) -> bool:
    # 订单号只能占一个 URL 路径段，拒绝 /、?、#、.. 等改变请求目标的输入。
    return (isinstance(order_number, str)
            and re.fullmatch(_ORDER_NUMBER_PATTERN, order_number) is not None)


def _business_message(body: dict, fallback: str) -> str:
    message = body.get("msg")
    return message if isinstance(message, str) and message.strip() else fallback


def _response_body(response) -> dict:
    body = response.json()
    if (not isinstance(body, dict) or type(body.get("code")) is not int
            or body["code"] not in (0, 1)):
        raise ValueError("invalid Java response")
    return body


def _find_mock_order(order_number: str) -> dict | None:
    """按订单号在 mock 列表里找单。
    语法点：生成器表达式 + next(…, None) ≈ Java 的 stream().filter(...).findFirst().orElse(null)；
    返回类型 `dict | None` 表示"找到返回字典，找不到返回 None"（= Java 的 Optional<Order>）。
    """
    return next((o for o in _MOCK_ORDERS if o["number"] == order_number), None)


def _format_order_line(o: dict) -> str:
    """把一条订单 dict 拼成一行文本。
    mock 分支和真实 Java 分支都会先把数据归一化成同一组 key，再走这里 ——
    这样格式化逻辑只写一份（等价 Java 里抽取的公共 render 方法）。
    f"..." 是 f 字符串：{o['number']} 会在运行时替换成变量值（= Java 的 String.format/文本块）。
    """
    return f"订单号：{o['number']}  状态：{STATUS_TEXT[o['status']]}  金额：¥{o['amount']}  下单时间：{o['order_time']}"


def _format_detail(order: dict, details: list[dict]) -> str:
    """拼订单主信息 + 明细列表。lines 是列表，最后 '\\n'.join(lines) = Java 的 String.join。
    函数名前的下划线 _ 是 Python 惯例：表示"内部私有"（语言不强制，约定俗成）。
    """
    lines = [_format_order_line(order), "明细："]
    for d in details:
        lines.append(f"· {d['name']} ×{d['number']}  ¥{d['amount']}")
    return "\n".join(lines)


# ==================== 工具 1：查订单列表 ====================
# 签名解读：
#   status: int | None = None   → 可空参数（= Java 的 Integer status），用户没指定状态就传 null
#   limit: int = 5              → 带默认值的参数
#   runtime: ToolRuntime[UserContext] = None → 框架注入的上下文，模型不可见（见文件头 @tool 说明）
#   -> str                      → 返回类型注解（Python 注解不强制，但 @tool 会用它生成参数 Schema）
@tool
def query_orders(status: Annotated[int, Field(strict=True, ge=1, le=6)] | None = None,
                 limit: Annotated[int, Field(strict=True, ge=1, le=20)] = 5,
                 runtime: ToolRuntime[UserContext] = None) -> str:
    """查询【当前登录用户】自己的订单列表，按下单时间倒序。用于回答"我的订单到哪了/有哪些订单/订单状态"。
    Args:
        status: 订单状态筛选，不填=全部。1待付款 2待接单 3已接单 4派送中 5已完成 6已取消
        limit: 最多返回条数，1 到 20，默认 5
    """
    context = _user_context(runtime)
    if context is None:
        return "当前用户身份信息不可用，请重新登录后再试。"
    if type(limit) is not int or not 1 <= limit <= 20:
        return "订单查询参数不合法：limit 必须是 1 到 20 的整数。"
    if status is not None and (type(status) is not int or status not in STATUS_TEXT):
        return "订单查询参数不合法：status 必须是 1 到 6 的整数或不填。"
    uid = context.user_id   # 真实用户身份只从注入的上下文拿，绝不来自模型参数（防越权第一闸）

    # ---------- 分支 A：mock 假数据，本地过滤/截断（MOCK_JAVA=1 时联调用） ----------
    if c.MOCK_JAVA:
        rows = [o for o in _MOCK_ORDERS if o["user_id"] == uid]
        if not rows:
            # 按注入身份过滤；默认演示数据只有 user 1，评测可装载多用户假数据。
            return "你当前没有任何订单。"
        if status is not None:
            rows = [o for o in rows if o["status"] == status]   # 列表推导 = stream().filter()，注意缩进层级
        rows.sort(key=lambda o: o["order_time"], reverse=True)
        rows = rows[:limit]                                      # 切片取前 limit 条（= subList(0, limit)）
        if not rows:
            return "没有符合条件的订单。"
        return "\n".join(_format_order_line(o) for o in rows)    # join(生成器) 逐行拼文本

    # ---------- 分支 B：真实调 Java /internal/orders（过滤/截断由 Java SQL 完成，Python 不做业务） ----------
    try:
        params = {"userId": uid, "limit": limit}
        if status is not None:
            params["status"] = status
        resp = httpx.get(f"{c.JAVA_BASE_URL}/internal/orders",
                         params=params, **_JAVA_HTTP_OPTIONS)
        resp.raise_for_status()          # 4xx/5xx 抛异常（= Java HttpClient 的响应码检查）
        body = _response_body(resp)      # 检查统一响应体，畸形响应不能被当作明确的业务拒绝
        if body.get("code") != 1:        # Java 统一返回体 code=1 才是业务成功
            return _business_message(body, "订单查询失败，请稍后再试。")
        # 把 Java 的驼峰字段（orderTime）归一化成 mock 同款 key（order_time），
        # 之后复用同一套格式化 —— 两个数据源，一个出口
        rows = [{"number": x["number"], "status": x["status"],
                 "amount": x["amount"], "order_time": x["orderTime"]}
                for x in (body.get("data") or [])]
        if not rows:
            return "你当前没有符合条件的订单。"
        return "\n".join(_format_order_line(o) for o in rows)
    except Exception as e:
        # 网络抖动/Java 没起/超时：绝不让异常炸穿 Agent（炸穿=整个接口 500，用户看到白屏报错），
        # 而是给模型一句可转述的话，由模型礼貌转告用户。type(e).__name__ 取异常类名，如 ConnectError
        return f"订单服务暂时不可用（{type(e).__name__}），请稍后再试。"


# ==================== 工具 2：查单张订单明细（含归属校验） ====================
@tool
def query_order_detail(order_number: Annotated[str, Field(pattern=r"^[A-Za-z0-9_-]{1,50}$")],
                       runtime: ToolRuntime[UserContext] = None) -> str:
    """查询【当前登录用户】某一张订单的状态、金额与菜品明细。用户问"某单多少钱/里面有什么/具体情况"时使用。
    Args:
        order_number: 订单号，由用户消息或上文对话提供，1 到 50 位字母、数字、下划线或短横线
    """
    context = _user_context(runtime)
    if context is None:
        return "当前用户身份信息不可用，请重新登录后再试。"
    if not _valid_order_number(order_number):
        return "订单号格式不合法，请提供 1 到 50 位字母、数字、下划线或短横线。"
    uid = context.user_id

    # ---------- 分支 A：mock ----------
    if c.MOCK_JAVA:
        order = _find_mock_order(order_number)
        if order is None:
            return "没有这张订单，请核对订单号。"
        if order["user_id"] != uid:
            # 越权第二闸：模型给的订单号 vs 注入的真实 uid 对不上 → 拒绝。
            # 注意话术里【不泄露】该订单是否存在/属于谁，防探测（Java 侧 403 同思路）
            context.order_access_denied = True
            return "无权查看该订单（订单不属于当前用户）。"
        # _MOCK_DETAILS.get(key, []) = map.getOrDefault(key, List.of())：没明细就给空列表，避免 KeyError
        return _format_detail(order, _MOCK_DETAILS.get(order_number, []))

    # ---------- 分支 B：真实调 Java，归属校验在 Java 侧完成 ----------
    # Java 端约定：404=订单不存在 / 403=不是本人的单，Python 按状态码区分话术（两道闸语义一致）
    try:
        resp = httpx.get(f"{c.JAVA_BASE_URL}/internal/orders/{order_number}",
                         params={"userId": uid}, **_JAVA_HTTP_OPTIONS)
        if resp.status_code == 404:
            return "没有这张订单，请核对订单号。"
        if resp.status_code == 403:
            context.order_access_denied = True
            return "无权查看该订单（订单不属于当前用户）。"
        resp.raise_for_status()
        body = _response_body(resp)
        if body.get("code") != 1:
            return _business_message(body, "订单查询失败，请稍后再试。")
        data = body["data"]
        order = {"number": data["order"]["number"], "status": data["order"]["status"],
                 "amount": data["order"]["amount"], "order_time": data["order"]["orderTime"]}
        return _format_detail(order, data.get("details") or [])
    except Exception as e:
        return f"订单服务暂时不可用（{type(e).__name__}），请稍后再试。"


# ==================== 工具 3：转人工 / 建工单 ====================
@tool
def create_ticket(category: Annotated[str, Field(min_length=1, max_length=32)],
                  content: Annotated[str, Field(min_length=1, max_length=500)],
                  order_number: Annotated[str, Field(pattern=r"^[A-Za-z0-9_-]{1,50}$")] | None = None,
                  runtime: ToolRuntime[UserContext] = None) -> str:
    """用户要求转人工、投诉、退款协商或反馈问题时，创建一张售后工单。不要承诺退款结果，只负责登记。
    Args:
        category: 工单类别，如 退款/催单/投诉/配送/其他，最多 32 字
        content: 用户诉求的简要描述，最多 500 字（用客服口吻概括，不要原样堆叠语气词）
        order_number: 关联订单号，没有关联订单可不填
    """
    context = _user_context(runtime)
    if context is None:
        return "当前用户身份信息不可用，请重新登录后再试。"
    request_id = context.request_id
    if not request_id:
        return _ticket_rejection(context, "缺少工单请求标识，无法安全创建工单；请重新发起请求。")
    if not isinstance(request_id, str) or re.fullmatch(r"[A-Za-z0-9_-]{8,64}", request_id) is None:
        return _ticket_rejection(context, "工单请求标识格式不合法，无法安全创建工单；请重新发起请求。")
    if not isinstance(category, str) or not category.strip() or len(category) > 32:
        return _ticket_rejection(context, "工单参数不合法：类别不能为空，且最多 32 字。")
    if not isinstance(content, str) or not content.strip() or len(content) > 500:
        return _ticket_rejection(context, "工单参数不合法：诉求内容不能为空，且最多 500 字。")
    if order_number is not None and not _valid_order_number(order_number):
        return _ticket_rejection(context, "关联订单号格式不合法，请核对订单号；不关联订单时请不填。")
    payload = {"userId": context.user_id, "category": category, "content": content,
               "orderNumber": order_number, "requestId": request_id}   # Java DTO 驼峰命名

    with context._ticket_lock:
        if context._ticket_payload is not None and context._ticket_payload != payload:
            return _ticket_rejection(context, "同一请求只能登记同一条工单诉求，请勿更改已提交的类别、内容或订单号；如需重试请使用原参数。")
        if context.ticket_no:
            return _ticket_confirmation(context.ticket_no, category)
        context._ticket_payload = payload.copy()
        return _submit_ticket(payload, context)


def _ticket_confirmation(ticket_no: str, category: str) -> str:
    return f"已为你创建人工工单 {ticket_no}（类别：{category}），客服会尽快与你联系。"


def _ticket_rejection(context: UserContext, message: str) -> str:
    context.ticket_error = message
    return message


def _submit_ticket(payload: dict, context: UserContext) -> str:
    """必须在当前上下文的工单锁内调用；仅确认的同 ID 回执可以解除 unknown。"""
    # ---------- 分支 A：mock，本地造工单号（不真正落库） ----------
    if c.MOCK_JAVA:
        # Mock 同样校验关联订单归属；当前请求内复用回执，跨请求幂等仍由 Java/MySQL 保证。
        if payload["orderNumber"] is not None:
            order = _find_mock_order(payload["orderNumber"])
            if order is None or order["user_id"] != context.user_id:
                return _ticket_rejection(context, "未找到可关联的订单，请核对订单号。")
        ticket_no = f"TK{uuid4().hex[:30]}"
        context.ticket_no = ticket_no
        context.write_result_unknown = False
        context.ticket_error = None
        return _ticket_confirmation(ticket_no, payload["category"])

    # ---------- 分支 B：真实调 Java /internal/tickets，落库 customer_service_ticket 表 ----------
    previously_unknown = context.write_result_unknown
    context.write_result_unknown = True  # 发出写请求后，在收到明确结果前均视为未知
    try:
        resp = httpx.post(f"{c.JAVA_BASE_URL}/internal/tickets",
                          json=payload, **_JAVA_HTTP_OPTIONS)   # json= 自动序列化并设 Content-Type
        resp.raise_for_status()
        body = _response_body(resp)
        if body.get("code") != 1:
            # 本次明确被拒绝，不覆盖此前同 ID 写入结果未知的事实。
            data = body.get("data")
            if data is not None and not isinstance(data, dict):
                raise ValueError("invalid ticket response")
            context.write_result_unknown = (
                previously_unknown or (data or {}).get("outcome") == "unknown")
            return _ticket_rejection(context, _business_message(body, "工单创建失败，请稍后再试。"))
        ticket_no = body["data"]["ticketNo"]   # 字典逐层取值 = JSON 反序列化后 getObject().getTicketNo()
        if not isinstance(ticket_no, str) or not ticket_no.strip():
            raise ValueError("missing ticket number")
        context.ticket_no = ticket_no
        context.write_result_unknown = False
        context.ticket_error = None
        return _ticket_confirmation(ticket_no, payload["category"])
    except httpx.TimeoutException:
        return "工单提交结果暂时无法确认。请勿重复创建；系统可用同一请求标识重试并取回原工单号。"
    except Exception as e:
        return f"工单服务暂时不可用（{type(e).__name__}），请稍后再试。"


# ==================== 工具 4：FAQ 知识库检索（RAG，阶段 8 新增） ====================
# 【与前三件工具的本质区别】前三件都查"用户自己的数据"（订单/工单），需注入 userId 防越权；
#   FAQ 是【公共知识库】，对所有用户一致，所以本工具不要 runtime —— 少一份耦合、多一份明确。
# 【为什么独立成工具而不是直接拼进 SYSTEM_PROMPT】30 条 FAQ 全塞进系统提示词，
#   既占 token 又让模型在每轮决策时被无关上下文稀释注意力；用工具按需召回，更省也更准。
@tool
def query_faq(question: str) -> str:
    """从 FAQ 知识库中检索与用户问题最匹配的常见问答。当用户问到退款规则、配送费、起送价、
    优惠券使用、支付方式、会员积分、订单状态含义等【通用规则】类问题时调用此工具，
    返回的答案必须按原文引用，不要改写金额、时长、阈值等数字。
    不要用此工具查询【当前用户】的具体订单（那是 query_orders 的职责）。
    Args:
        question: 用户问题原文（如"退款多久到账""配送费怎么算"），尽量保留原意不要改写
    """
    if not isinstance(question, str) or not question.strip():
        return "请提供需要查询的具体问题。"
    try:
        hits = faq_rag.search(question)   # top_k 默认走 config.RAG_TOP_K
        if not hits:
            return "FAQ 知识库暂无相关条目；当前无法核实这项规则，请勿凭印象回答具体金额或时限。"
        # 拼成"序号 · Q/A"格式；格式异常同样应返回工具错误，不能炸穿 Agent。
        lines = []
        for i, h in enumerate(hits, 1):
            if not isinstance(h["question"], str) or not isinstance(h["answer"], str):
                raise ValueError("invalid FAQ result")
            lines.append(f"{i}. Q: {h['question']}\n   A: {h['answer']}")
        return "\n".join(lines)
    except Exception as e:
        # 模型没下完 / faq_data 缺失 / numpy 报错：兜底一句话，绝不抛穿 Agent
        return f"FAQ 知识库暂时不可用（{type(e).__name__}）；当前无法核实规则，请勿猜测金额或时限，可建议转人工。"
