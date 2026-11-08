# 禾巷餐饮 · AI 售后客服 Agent 面试导读（手机版）

> 项目来源说明：禾巷餐饮是演示品牌名；基础外卖业务来自《苍穹外卖》课程，商家旧前端也使用了课程资料的编译产物。面试时请把课程基础与自己新增的 Python Agent、工具接口、工单幂等、评测和前端适配明确分开，不要称整个系统从零自研。

> 版本提示（2026-09-26）：本文是较早阶段的架构导读，不能直接当作现行评测成绩或业务政策。工单现已加入 `requestId` + MySQL 唯一约束，默认 FAQ 从旧 30 条演示文本改成 13 条保守问答；可重跑命令、已确认 Bad Case 和面试表述以 [三项改进复现与面试讲解](agent-learning/05-三项改进复现与面试讲解.md)及[FAQ 能力对账](agent-learning/04-FAQ能力对账与面试复盘.md)为准。下文若写“安全”“可用”，仍需区分代码设计与真实生产验收。

> 用法：本文按**一次请求的真实调用顺序**写成 8 站。每站包含：在架构里的位置 → 关键真实代码 → 业务逻辑 → Java/Python 对照 → 面试问答。
> 手机上配合 Gitee 仓库看：每个文件名都能在仓库里搜到原文件。建议第一遍顺读，第二遍合上文档自己画调用环。

---

## 一、三档自我介绍话术（先背这个）

### 30 秒版

> 我的“禾巷餐饮”项目以课程外卖后端为基础，我重点扩展了 **AI 售后客服 Agent**：用 LangChain 1.x 装配 ReAct 循环，Python 做模型决策与工具编排，Java 做数据与权限的事实源，两边通过 HTTP 通信、用共享密钥做服务间鉴权。Agent 能查订单、查详情、登记售后工单，还接了 bge-m3 + Chroma 的 FAQ 向量检索，并用 Docker Compose 编排 MySQL、Redis、Java、Python 四个服务。

### 2 分钟版

在 30 秒版基础上加三个亮点：

1. **安全**：用户身份全程不信任客户端——Java 从 JWT 解析 userId，通过 LangChain 的 ToolRuntime 上下文"暗道"注入工具，模型看不见也改不了；查订单详情时 Java 再做一次归属校验（不存在 404、非本人 403），形成双闸防水平越权。
2. **防幻觉**：订单号、金额、退款规则等事实只能来自工具返回，系统提示词立法 + 后端以库为准，模型不允许自己"背"数字。
3. **工程化**：重依赖全部懒加载（没配 API Key 服务也能起）、Python 故障时 Java 优雅降级不 500、RAG 后台预热不阻塞启动；Docker Compose 健康检查控制启动顺序，一条命令起全套。

### 5 分钟版

= 2 分钟版 + 后文"第四部分四大主题"里挑两个展开讲 + 一个 STAR 故事（推荐讲 h2c 422 或双闸越权）。

---

## 二、全局架构（必须能手画）

```
                          浏览器/小程序
                              │ POST /user/service/chat-python
                              ▼
            ┌─────────────────────────────────────┐
            │  Java（Spring Boot，8080）            │
            │  ① ServiceAgentController            │  从 JWT 解析 userId
            │  ② PythonAgentClient ───HTTP/1.1──┐  │  超时兜底，Python挂了回降级文案
            └─────────────────────────────────┼──┘
                                              ▼
            ┌─────────────────────────────────────┐
            │  Python Agent（FastAPI，8000）       │
            │  ③ main.py   收 JSON/异常兜底        │
            │  ④ agent.py  ReAct循环 + 多轮记忆    │
            │  ⑤ tools.py  4件工具（决策后执行）   │
            │  ⑥ faq_rag.py bge-m3+Chroma 检索    │
            └──────────────┬──────────────────────┘
                           │ 回调 HTTP，带 X-Internal-Token
                           ▼
            ┌─────────────────────────────────────┐
            │  Java 内部接口 /internal/**          │
            │  ⑦ InternalTokenInterceptor 鉴服务   │
            │     InternalApiController 契约/状态码│
            │  ⑧ InternalApiServiceImpl 归属校验  │
            │     → MyBatis → MySQL               │
            └─────────────────────────────────────┘
```

**一句话职责切分（面试必说）**：Java 是数据与权限的"事实源"，Python 是大模型决策编排层；数据不长期过 Python 的手，Python 挂了 Java 有兜底。

**为什么分两种语言？** DeepSeek 是 OpenAI 协议，Java 也能调（项目里就有一个 Java 手写版 AI 点餐助手作对照）。引入 Python 版的价值：① 对比"手写消息循环 vs 框架编排"两条路线；② LangChain/LangGraph 的 Agent、工具调用、向量检索生态在 Python 侧最成熟；③ 体现跨语言微服务协作与故障隔离能力。

---

## 三、调用链 8 站逐站导读

### 站 1　ServiceAgentController：入口与"我是谁"的裁定

文件：`sky-server/.../controller/user/ServiceAgentController.java`

**它干什么**：极薄的 Controller，两个动作——从登录态解析 userId、把消息转发给 Python。

请求 DTO（`ServiceChatDTO`）**只有 message 和 sessionId，故意没有 userId**：

```java
// userId 绝不从请求体取！请求体是用户可控输入
private Long resolveUserId(HttpServletRequest request) {
    String token = request.getHeader(jwtProperties.getUserTokenName());
    if (token != null && !token.isEmpty()) {
        try {
            Claims claims = JwtUtil.parseJWT(jwtProperties.getUserSecretKey(), token);
            return Long.valueOf(claims.get(JwtClaimsConstant.USER_ID).toString());
        } catch (Exception e) {
            // token 无效：降级为演示用户
        }
    }
    return DEMO_USER_ID;   // = 1L，仅演示用；生产必须 resolve 失败即 401
}
```

转发时缺省 sessionId 按用户隔离：

```java
String sessionId = (空) ? "user-" + userId : dto.getSessionId();
return Result.success(pythonAgentClient.chat(userId, sessionId, dto.getMessage()));
```

**业务逻辑要点**：
- 身份只信 JWT，不信请求字段——对照 Java 后台管理端 JWT 拦截器把 empId 塞 ThreadLocal，是同一思想。
- 多轮记忆的"抽屉号"缺省值也绑 userId，防止匿名用户共享一段记忆。
- **演示取舍要主动交代**：token 无效降级为用户 1 是为了免登录演示，生产环境应直接 401。主动说 = 分得清演示便利与安全边界。

**面试问答**
- Q：userId 放请求体里传不行吗？
  A：那任何人改个数字就能冒充别人，等于把鉴权交给调用方。身份必须从服务端签发、自己验签的令牌里取。

---

### 站 2　PythonAgentClient：出站 HTTP 与故障兜底

文件：`sky-server/.../client/PythonAgentClient.java`

**它干什么**：用 JDK 原生 HttpClient 向 Python 发一次 POST，Python 异常时不抛 500，回友好文案。

```java
private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)          // 关键：锁 HTTP/1.1
        .connectTimeout(Duration.ofSeconds(10)).build();

// 请求超时 60s：Agent 一轮内部可能 查列表→查明细→建工单 多次往返
HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(pythonAgentBaseUrl + "/agent/chat"))
        .timeout(Duration.ofSeconds(60))
        .POST(BodyPublishers.ofString(body.toJSONString(), UTF_8)).build();
```

响应解析（Python 回 `{reply, toolCalls}`）：

```java
if (response.statusCode() != 200) return fallback("...响应异常...");
// toolCalls 做 null 判空——跨进程契约里可选字段，消费方必须防御
return ServiceChatVO.builder().reply(data.getString("reply")).toolCalls(toolCalls).build();
// catch 所有 Exception → fallback("Python Agent 服务暂时不可用...")
```

**三个设计决策**：
1. **选型**：RestTemplate 老化、WebClient 拖 webflux、OpenFeign 通常配注册中心；一次 POST 的诉求，JDK 原生零依赖最合适。
2. **两档超时**：connectTimeout 10s 管建连（Python 没起快速失败），request 60s 管 Agent 多跳等待。
3. **故障隔离**：客服对话可用性优先，Python 挂/超时/返回怪 → Java 吃掉异常回"稍后再试"，用户不白屏，其他业务不受影响。

**面试问答（招牌坑）**
- Q：为什么锁 HTTP/1.1？
  A：JDK HttpClient 默认尝试 h2c（HTTP/2 明文升级）先发 Upgrade，uvicorn 不认，回 422 Unsupported upgrade。排查方法比答案值钱：**跨语言联调出文不对题的错，先怀疑两边对协议的默认假设是否一致**，抓包/看服务端日志定位。

---

### 站 3　main.py：Python 侧的"门童"（= Spring 的 Controller 层）

文件：`python-agent/main.py`

**Java ↔ Python 对照**：

| Spring MVC | FastAPI |
|---|---|
| `@RestController` + `@PostMapping` | `@app.post("/agent/chat")` |
| `@RequestBody XxxDTO` | 参数声明 `req: ChatReq`（Pydantic 自动反序列化+校验） |
| return DTO 自动转 JSON | 直接 return Pydantic 模型 |
| `@PostConstruct` | lifespan 的 yield 之前 |
| Actuator /health | 手写 `@app.get("/health")` |

核心代码：

```python
class ChatReq(BaseModel):
    userId: int
    sessionId: str = "default"
    message: str
    # Pydantic：类型注解即校验，userId 缺失/不是 int 自动 422

@app.post("/agent/chat")
def chat(req: ChatReq):
    if not c.DEEPSEEK_API_KEY:        # 没配 key 不 500，回可展示文案
        return ChatResp(reply="AI 服务未配置 DEEPSEEK_API_KEY...", toolCalls=[])
    try:
        reply, tool_calls = agent_module.run(req.userId, req.sessionId, req.message)
    except Exception as e:
        traceback.print_exc()        # 栈只打服务端日志
        return ChatResp(reply=f"AI 服务暂时开小差了（{type(e).__name__}）...", toolCalls=[])
    return ChatResp(reply=reply, toolCalls=tool_calls)
```

启动预热（不阻塞起服务）：

```python
@asynccontextmanager
async def lifespan(app):
    threading.Thread(target=faq_rag_module.warm_up, daemon=True).start()
    yield
```

**业务逻辑要点**：门童不含任何业务规则，只做收 JSON、调 agent.run、异常兜底。RAG 模型 2.2GB，放后台守护线程预热，服务秒级起来；预热失败只打日志，RAG 退化不可用不影响其他工具。

---

### 站 4　agent.py：Agent 的大脑（ReAct 循环 + 多轮记忆）

文件：`python-agent/agent.py`

**ReAct = Reason + Act 交替**，一轮对话时序：

```
① run(userId, sessionId, message)
② 系统提示词 + checkpointer 按 thread_id 取出的历史 + 本轮消息 → DeepSeek
③ 模型决策：直接答 或 返回 tool_calls（"我要调 query_orders"）
④ 框架执行 tools.py 对应函数（此时 runtime.context 里已注入 userId）
⑤ 工具结果包成 ToolMessage 喂回模型
⑥ 模型看到结果组织成人话（③~⑥ 可能多轮：先查列表再查明细）
```

与 Java 手写版的关系：手写版（AiAssistantServiceImpl）自己拼消息、自己解析 tool_calls JSON、自己调度函数；create_agent 把这套循环内置——**类比手写 JDBC vs MyBatis**。

关键代码：

```python
_tools = [query_orders, query_order_detail, create_ticket, query_faq]
_agent = None

def _get_agent():          # 懒加载单例
    global _agent
    if _agent is None:
        _agent = create_agent(
            model=ChatOpenAI(model=c.DEEPSEEK_MODEL, api_key=..., base_url=..., temperature=0.3),
            tools=_TOOLS,
            system_prompt=SYSTEM_PROMPT,
            checkpointer=InMemorySaver(),       # 多轮记忆本体，按 thread_id 存取
            context_schema=UserContext,         # userId 的注入"暗道"
        )
    return _agent

def run(user_id, session_id, message):
    result = _get_agent().invoke(
        {"messages": [{"role": "user", "content": message}]},
        context=UserContext(user_id=user_id),                        # 身份注入
        config={"configurable": {"thread_id": session_id}},          # 同号=同历史
    )
    messages = result["messages"]
    tool_calls = [m.name for m in messages if m.type == "tool"]      # 轨迹里筛工具名
    return messages[-1].content, tool_calls                          # 最后一条=最终回复
```

**系统提示词 7 条"宪法"（防幻觉核心，背第 1/4/6 条）**：
1. 订单问题必须先调工具，禁止编造订单号、金额、时间；
2. 只能查自己的订单，查不到/无权如实转告；
3. 退款只解释规则、登记工单，不承诺结果；
4. **金额与时间必须引用工具返回原文，不改写数字**；
6. 规则类问题先调 query_faq，按原文引用；
7. 区分两类问题：查我的订单→query_orders，问退款时效→query_faq。

**懒加载原因**：ChatOpenAI 构造时就校验 api_key，模块导入即装配的话，没配 key 连 /health 都起不来，运维没法先起服务再补配置。
**checkpointer 只建一次**：换新存档器 = 历史清零。InMemorySaver 进程重启失忆，生产可换 RedisSaver/PostgresSaver。
**temperature=0.3**：客服场景要准不要发散。

---

### 站 5　tools.py：Agent 的手脚（4 件工具 + 防越权第一闸）

文件：`python-agent/tools.py`

**@tool 装饰器干了什么（≈ Java 注解 + APT，运行期生效）**：
1. 读函数 docstring → 变成给**模型**看的工具说明书（没有 docstring 直接报错）；
2. 读参数名/类型注解 → 生成 JSON Schema 让模型选参；
3. **runtime 参数被排除在 Schema 外，模型根本不知道它存在**。

身份上下文：

```python
@dataclass                       # ≈ Lombok @Data
class UserContext:
    user_id: int                 # 代码注入，模型不可见

@tool
def query_orders(status: int | None = None, limit: int = 5,
                 runtime: ToolRuntime[UserContext] = None) -> str:
    """查询【当前登录用户】自己的订单列表...
    Args:
        status: 1待付款 2待接单 3已接单 4派送中 5已完成 6已取消
    """
    uid = runtime.context.user_id      # 真实身份只从上下文拿，绝不来自模型参数
    ...
    resp = httpx.get(f"{c.JAVA_BASE_URL}/internal/orders",
                     params={"userId": uid, "limit": limit, ...},
                     headers={"X-Internal-Token": c.INTERNAL_TOKEN}, timeout=10)
```

**为什么 userId 不能写进工具参数？** 工具参数是模型填的，模型会被用户话术影响——用户说"帮我查 user 2 的订单"，模型可能真填 2。参数里不出现 userId，就从根上断了这条路。

查详情的第二闸（模型给订单号，uid 是注入的，必须匹配）：

```python
# Python mock 分支
if order["user_id"] != uid:
    return "无权查看该订单（订单不属于当前用户）。"   # 话术不泄露订单是否存在
# 真实分支看 Java 的状态码：404=不存在 403=非本人，话术分开
```

四件工具分工：

| 工具 | 干什么 | 数据性质 | 要 userId？ |
|---|---|---|---|
| query_orders | 我的订单列表（可按状态/limit） | 用户私有数据 | 要 |
| query_order_detail | 单张订单+菜品明细（归属校验） | 用户私有数据 | 要 |
| create_ticket | 登记售后工单（唯一写操作，落库） | 用户私有数据 | 要 |
| query_faq | 检索通用规则知识库 | 公共知识，对所有人一致 | **不要** |

**容错纪律**：每个工具调 Java 全部 try/except，网络抖动返回"订单服务暂时不可用（ConnectError）"这类**可转述的话**给模型，绝不让异常炸穿成 500 白屏。
**MOCK_JAVA 开关**：=1 用本地假数据离线联调（selftest 15/15），=0 真实回调 Java（accept_e2e 7/7），一套代码两副面孔。

---

### 站 6　faq_rag.py：FAQ 向量检索（RAG）

文件：`python-agent/faq_rag.py`

**链路**：query_faq 工具 → `faq_rag.search(问题)` → bge-m3 把问题向量化 → Chroma 余弦相似度召回 top-K → 原文拼给模型引用。

```python
# 懒加载：import chromadb / sentence_transformers 都在函数体内
_client = _collection = None

def _get_collection():
    if _collection is None:
        import chromadb
        from chromadb.utils.embedding_functions import SentenceTransformerEmbeddingFunction
        _client = chromadb.PersistentClient(path=...)          # 落盘，重启不丢
        ef = SentenceTransformerEmbeddingFunction(model_name=c.EMBED_MODEL)  # bge-m3
        _collection = _client.get_or_create_collection(
            name="faq", embedding_function=ef,
            metadata={"hnsw:space": "cosine"})                # 余弦距离
    return _collection

def build_index():                       # upsert 幂等：FAQ 更新可热重载
    coll.upsert(ids=[...], documents=[q+" "+a...], metadatas=[{question,answer,category}...])

def search(query, top_k=None):
    coll = _get_collection()
    if coll.count() == 0: build_index()  # 空库自愈
    res = coll.query(query_texts=[query], n_results=top_k)
    # 注意：返回的是"距离"，score = 1 - distance 才是相似度
```

**选型论证（高频题：用向量库是不是过度设计？）**
- numpy 30×1024 矩阵做余弦其实零依赖、毫秒级，初版评估过；
- 最终选 Chroma 的两个真实理由：**持久化**（numpy 方案每次重启要重新编码）+ **未来增长**（FAQ 上千、要按分类/时间做元数据过滤时直接支持）；
- faiss 纯库无持久化、概念陡；Milvus 要独立部署，对 30 条数据运维过重。Chroma 嵌入式 pip 即用，规模刚好。

**为什么独立成工具，不把 30 条 FAQ 全塞进 system prompt？** 占 token + 每轮被无关上下文稀释注意力；按需召回更省更准。
**为什么 question+answer 一起向量化？** 答案里同义词多，单题召回率更稳。

---

### 站 7　回调入口：InternalTokenInterceptor + InternalApiController

文件：`sky-server/.../interceptor/InternalTokenInterceptor.java`、`controller/internal/InternalApiController.java`

**两种鉴权的分界线（跨语言服务高频考点）**：

| | 鉴的对象 | 凭证 | userId 来源 |
|---|---|---|---|
| `/user/**` | 鉴"人" | JWT 登录态 | 从 token 解析 |
| `/internal/**` | 鉴"服务" | X-Internal-Token 共享密钥 | 授信服务显式传参 |

为什么 /internal 里 userId 敢用参数传？信任主体变了——调用方不是不可信的浏览器，而是密钥已对上的内部服务；且那个 userId 来自 Python 侧模型碰不到的 UserContext，链条闭环。

拦截器核心：

```java
String token = request.getHeader("X-Internal-Token");
if (internalToken.equals(token)) return true;     // 放行
log.warn("令牌校验失败：path={}, 来自={}", ...);    // 内部接口被扫=安全事件，留痕
response.setStatus(401);                          // 手写响应，不抛异常（抛了被兜成500）
writer.write(JSON.toJSONString(Result.error("内部接口令牌无效")));
return false;
```

- 为什么用拦截器不用 Filter：能拿到 handler、在 Spring 体系内统一管路径；Filter 适合编码/CORS 这类与业务无关的事。
- 密钥：`@Value` 读 yml 的 `sky.ai.internal-token`，Python 的 .env 必须同值（对称授信）。加分话题：生产可用 `MessageDigest.isEqual` 常量时间比较 + 密钥轮换。

Controller 的三个端点：

```java
GET  /internal/orders?userId=&status=&limit=5     // 列表，limit 在 Service 再夹逼一次
GET  /internal/orders/{orderNumber}?userId=       // 不存在404 / 非本人403
POST /internal/tickets                            // 唯一写操作
```

两个值得讲的点：
1. **详情端点为什么手动 catch 不用全局异常处理器**：项目全局处理器把业务异常统一折成 200+Result.error，但 Python 需要用 HTTP 状态码区分 404/403 生成不同话术——契约优先于风格统一。
2. **授信不等于数据合法**：内部服务密钥对上了，createTicket 仍在服务端再校验 userId/category/content 非空——授信看信道，校验看数据。

---

### 站 8　InternalApiServiceImpl：归属校验、落库与隐私裁剪

文件：`sky-server/.../service/impl/InternalApiServiceImpl.java`

**越权最后一道闸（先 404 后 403，顺序有讲究）**：

```java
Orders order = internalApiMapper.getByNumber(orderNumber);
if (order == null) throw new OrderNotFoundException(...);        // 先判存在
if (!order.getUserId().equals(userId))                           // 再判归属
    throw new OrderAccessDeniedException(...);
```

顺序第一原因是 null 安全（查不到单就没有 userId 可比）。权衡：区分 404/403 会暴露单号是否存在（枚举探测），对持密钥的内部服务可接受；面向不可信调用方应统一模糊话术。

**隐私裁剪（toOrderMap）**：

```java
private Map<String, Object> toOrderMap(Orders o) {
    Map<String,Object> m = new LinkedHashMap<>();
    m.put("number", o.getNumber());
    m.put("status", o.getStatus());
    m.put("amount", o.getAmount()==null ? "" : o.getAmount().toPlainString());
    m.put("orderTime", o.getOrderTime()==null ? "" : FMT.format(o.getOrderTime()));
    return m;
}
```

- 只导出客服需要的 4 个字段，不把地址、手机号等隐私暴露给 Python；
- 时间统一格式化成字符串，避免跨语言序列化差异（Jackson 默认时间是数组）；
- BigDecimal 用 `toPlainString()` 不用 `toString()`（后者科学计数法风险）；
- LinkedHashMap 保证 JSON 字段顺序稳定。
- 已知技术债（面试可主动认领）：这里用 Map 未用 VO，字段契约不显式；工单号 `TK+currentTimeMillis` 高并发同毫秒有撞号风险，应改序列/UUID。

**参数防御**：`safeLimit = (limit==null || <=0 || >20) ? 5 : limit`——Controller 默认值 + Service 夹逼双层防御。

**工单落库**：insert customer_service_ticket，初始状态 PENDING，builder 装配 + createTime。

---

## 四、四大主题深挖（5 分钟版的弹药）

### 主题 A：防水平越权的完整闭环

```
浏览器（不可信）
  → Java：userId 只从 JWT 取          （站1，身份诞生）
  → Python：userId 走 context 暗道    （站4/5，模型不可见不可改）
  → Java：/internal 先过服务密钥      （站7，鉴服务）
  → Java：订单归属再校验 404/403      （站8，最后一道闸）
```
每一层只解决自己的问题，单层失守不导致越权。金句：**"用户嘴里说的订单号 + 系统注入的 userId" 必须在服务端做匹配，信任永远不下放到客户端或模型。**

### 主题 B：防幻觉四道闸

1. 系统提示词立法（事实只能来自工具，数字引用原文）；
2. 结构化工具：模型只决策"调谁、传什么业务参数"，身份参数碰不到；
3. 后端以库为准：查不到/不属于如实说，金额时间引用工具原文；
4. 知识库没有的规则走 query_faq，再没有就转人工，不许编。

### 主题 C：可用性与懒加载哲学

- ChatOpenAI、Chroma、sentence_transformers 全部懒加载：任何重依赖缺失都不拖垮进程，/health 照常；
- RAG warm_up 后台线程 + try-except：模型 2.2GB 不阻塞启动、加载失败退化为其余工具；
- Java→Python 两级超时 + fallback；Python→Java 每个工具 try/except 回可转述话术；
- 故障面被限制在单个功能内，而不是整链熔断。

### 主题 D：Docker Compose 一键部署

- 四服务：mysql / redis / java-app / python-agent，自定义 bridge 网络 sky-net；
- Java `depends_on: condition: service_healthy`——等 MySQL/Redis 健康才启动，解决启动时序竞态；
- yml 用 `${MYSQL_HOST:localhost}` 占位符带默认值：**容器注入服务名、本地裸跑用 localhost，一份配置两种跑法**；
- MySQL 数据卷仅首次初始化执行建表+种子 SQL（改种子要 `down -v` 重建）；
- 多阶段构建：Maven 构建层 → JRE 运行层；2.2GB 模型走 volume 不进镜像；
- 端口：8080 Java / 8000 Python / 3307 容器 MySQL（宿主 3306 被占）/ 6379 Redis。

---

## 五、高频面试题自测（先自己答，再对照）

1. **画一次完整调用环，每跳说协议和鉴权。** → 见第二部分图。
2. **为什么不用 Java 直接调大模型，要拆 Python 服务？** → 见第二部分"为什么分两种语言"。
3. **模型输出你敢直接用吗？** → 不敢。身份不经过模型、事实以库为准、数字引用原文、写操作服务端再校验。
4. **怎么防用户查别人订单？** → 主题 A 的四环节。
5. **404 和 403 为什么要区分？有什么代价？** → Python 按状态码分化术；代价是泄露单号存在性，内部授信可接受。
6. **Python 服务挂了会怎样？** → connectTimeout 快速失败/异常被 catch，Java 返回降级文案，其他业务不受影响。
7. **RAG 为什么选 Chroma？numpy 不行吗？** → 站 6 选型论证。
8. **多轮记忆存哪？重启怎么办？** → InMemorySaver 按 thread_id，重启失忆；生产换 RedisSaver/PostgresSaver。
9. **共享密钥鉴权有什么不足？怎么升级？** → 对称密钥无法精细授权、有泄露面；升级常量时间比较、密钥轮换、mTLS/服务网格。
10. **一次 Agent 对话为什么超时给 60 秒？** → 一次 HTTP 内套多跳工具往返（列表→明细→工单），每跳还回调 Java。
11. **拦截器和 Filter 区别？** → 站 7。
12. **为什么金额用 toPlainString？** → 避免 BigDecimal toString 科学计数法，跨语言传输最稳。
13. **你项目里最能体现排查能力的 bug？** → 讲 h2c 422（TLS/协议假设）或 GCM 双账号推送挂起（curl 对比+verbose+二分）。

---

## 六、文件地图（在 Gitee 里照着点开原文件）

**Java（sky-server/src/main/java/com/sky/）**
- `controller/user/ServiceAgentController.java` —— 站1
- `client/PythonAgentClient.java` —— 站2
- `interceptor/InternalTokenInterceptor.java` —— 站7
- `controller/internal/InternalApiController.java` —— 站7
- `service/impl/InternalApiServiceImpl.java` —— 站8
- `service/impl/AiAssistantServiceImpl.java` —— Java 手写版对照
- `controller/user/AiAssistantController.java`、`resources/static/ai-chat.html` —— 手写版演示页

**Python（python-agent/）**
- `main.py` 站3 ｜ `agent.py` 站4 ｜ `tools.py` 站5 ｜ `faq_rag.py` 站6
- `config.py` 配置（API Key/MOCK_JAVA/JAVA_BASE_URL/INTERNAL_TOKEN/RAG 参数）
- `selftest.py` 离线测试 15 项 ｜ `accept_e2e.py` 真实 LLM 验收 7 场景 ｜ `faq_data.json` 30 条题库

**部署**：`docker-compose.yml`、`Dockerfile.java`、`python-agent/Dockerfile`、`docker/seed/02-sample-data.sql`

**复习档案**：仓库外的 `练习记录.md`（第 14/15/16 次日志有完整踩坑 STAR 素材）、`AI记忆.md`。

---

## 七、现场演示检查清单

1. `docker compose up -d` 四容器 healthy；
2. 浏览器开 `http://localhost:8080/doc.html`，调 `/user/service/chat-python`；
3. 准备三句台词：①"我的订单到哪了"（query_orders）②"退款多久能到账"（query_faq）③"我要投诉，帮我转人工"（create_ticket）；
4. 补刀问明细："那单里面有什么菜"（验证多轮+query_order_detail）；
5. 收尾看 toolCalls 字段与 Java 日志里的真实 SQL user_id，证明"真查库、非模型编造"。
