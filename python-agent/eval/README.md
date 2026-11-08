# Agent 评测与 Bad Case 记录

本轮已完成[收尾](CLOSEOUT_2026-10-27.md)：唯一验收测试工单备份后精确清理，原有两条保留；服务核对正常，源码快照与原始证据已归档。

最新验收见 [2026-10-27 本地真实链路验收](ACCEPTANCE_2026-10-27.md)：实际模型 + Java/MySQL + 网页操作、客户端超时重试与停机恢复已做样本验证，当前 118 项本地回归通过；文中记录测试工单和验收范围。

当前源码的跨请求重试、异常恢复与记忆容量优化见 [2026-10-21 第二轮记录](FOLLOWUP_2026-10-21.md)，最终 106 项本地测试通过；[首轮记录](OPTIMIZATION_2026-10-21.md)保留当时 74 项测试结果与复跑命令。下文历史统计保留原日期和环境含义。

真实 Java/MySQL 联调前，先运行**只读预检**：

```powershell
# 在 python-agent 目录；使用已安装的 Python 解释器，普通 python 命令若指向失效的 ServBay current 则换成完整路径
python eval/real_preflight.py --user-id 1
```

脚本仅发 `GET /health` 与 `GET /internal/orders?userId=1&limit=20`，不调模型、不写工单。服务间令牌从环境变量 `INTERNAL_TOKEN` 读取，缺失时回退本机 `python-agent/.env`，不会打印令牌。预检会绕过系统 HTTP 代理直连本机服务：服务未启动时应报告 `ConnectError`，避免把代理给出的 502 误判为 Java/Python 错误。它能确认 Python 存活、Java 内部接口可达并返回成功业务码；**不能**证明 Python 正在以 `MOCK_JAVA=0` 运行、模型密钥已配、指定订单归属正确或聊天 E2E 已通过。若查到 0 张订单，读链路可达，但当前用户不适合拿来验收正向查单。

旧 `accept_e2e.py` 写死历史订单号，且会建真实工单；现已加显式写入门槛，默认运行会立即退出。它仍不是当前 Docker 种子环境的一键验收。不要为了让旧脚本通过而重置有数据的 MySQL 卷。

## 目标与边界

`cases.json` 是版本化的合成问句，目前覆盖工具路由、参数、身份隔离、多轮状态、FAQ 和工单。`mock_fixture.json` 是评测专用的 7 条合成订单：两个用户、不同金额、待付款/派送中/已完成/已取消状态。评测启动时只把它装入 Python 进程的 Mock 数据，不修改业务数据库或普通演示种子。它不是生产用户数据，也不是已经通过的成绩单。设计采用“有参考工具调用的离线数据集 + 可重复的规则检查”；答复质量和安全边界仍需人工复核。这个分层与 [LangChain 官方评测分类](https://docs.langchain.com/langsmith/evaluation-types)中的离线数据集、代码评估器和人工/模型评估思路一致；当前实现不引入 LangSmith 依赖。

当前脚本有两种模式：

```powershell
python python-agent/eval/run_eval.py --validate
python python-agent/eval/run_eval.py --live --group routing --limit 3
python python-agent/eval/run_eval.py --live --case-id TICKET-02 --limit 1
# 在 python-agent 目录内做无网络的评测器和工具契约测试：
python -m unittest discover -s eval -p "test_*.py" -v
```

`--validate` 只检查案例和 Mock 数据格式，不调用模型。`--live` 需要本地 Python 已安装 Agent 依赖、配置好 `python-agent/.env` 里的 `DEEPSEEK_API_KEY`，会产生模型费用；必须显式填写 `--limit`。脚本在导入 Agent 前强制 `MOCK_JAVA=1`，所以不会访问 Java/MySQL，也不会真正创建工单。FAQ 组仍会加载本地 bge-m3/Chroma，初次使用可能需要下载模型；没有模型资源时请先跑其他组。

报告写入 `runs/<UTC时间>-<随机码>.json`，该目录被 Git 忽略。每条记录包含输入、预期工具/参数、实际工具/参数、工具返回文本、最终回复、时间与各维度是否匹配。评测脚本与现版 `agent.py run()` 均使用最后一条 `HumanMessage` 之后的消息判断**本轮**工具调用；旧版 API 曾混入历史工具。原始报告含完整合成问答和工具输出，仅限本地审阅；未来如评真实用户数据必须先脱敏。

`pass` 仅表示当前规则检查通过；`needs_review` 表示规则通过但仍要看答复；`fail` 是规则不符；`error` 是调用异常。不要把四者混算成“Agent 准确率”。也不要把 Mock 模式的成绩写成真实 Java/MySQL 全链路成功率。汇总里的 `parameter_checks_run` / `reply_checks_run` 是**真正设了断言的轮数**，不能把未设置的断言当作正确。当前未记录 token/费用，因为这个脚本没有可靠的统一计量来源。

评测集允许对确实有多种合理路径的题设置 `tool_options`，并可用 `tool_policy=contains_in_order` 表示必须调用某工具但允许前置辅助查询。写操作还设置 `max_calls` 防重复创建、`ticket_no_from_tool` 核对最终工单号确实来自工具结果；`reply_all` 要求多个关键事实都出现。这些字符串断言仍不能证明语义完全正确，需要人工检查是否有冗余调用、越权泄露或事实过期。报告会记录数据集与夹具 SHA-256、Git HEAD 和工作区是否有未提交改动，方便追溯版本。

### 历史：2026-09-26 v1 首次探索性烟雾测试

在真实模型、Mock Java 下，每组只抽首例，共 6 个案例、7 轮；这不是全量基线，更不是生产准确率。路由和参数首例自动检查通过；身份、FAQ 首例需要人工复核。多轮 `MEM-01` 与转人工 `TICKET-01` 起初被过严的工具序列规则误报为失败，规则修订并重跑后为 `needs_review`（多轮第一轮为 `pass`）。因此按修订后的各组最新报告统计为 3 个自动 `pass`、4 个 `needs_review`、0 个 `fail/error`，但这些结果来自多个独立运行，不能当作同一批次通过率。FAQ 首次加载本地模型耗时约 68 秒；后续延迟应单独测冷启动与热启动。

人工复核：`AUTH-01` 未按用户自称的“用户2”更改身份；`FAQ-01` 回答中的退款时限能在当前 `faq_data.json` 中找到，但仍要审查知识本身是否过期；`TICKET-01` 确实创建了 Mock 工单，但前置查单可能多余。这些仅是本次样本观察。已确认的评测器误报见 [Bad Case 台账](BAD_CASES.md)。

v2 变更了 Mock 数据和评分规则，**不能与上面的 v1 结果混算**。修改期间的小样本烟雾报告有 `working_tree_dirty=true`，不算冻结版本基线。

### 2026-09-26 v2 固定提交全量运行与人工复核

固定提交 `acb1dc4`、工作区干净时运行 `--live --limit 36`：36 案例/40 轮，原始自动统计为 19 `pass`、18 `needs_review`、3 `fail`、0 `error`；14 轮有参数断言且 14 轮匹配，14 轮有回复关键词断言且 14 轮匹配。平均延迟约 3.6 秒，最大约 30.3 秒（该轮首次 FAQ 模型加载）；这只是这一环境的样本，不是服务 SLO。原始报告 `runs/20260925T181309Z-880275.json` 在本机，因可能含对话数据而被 Git 忽略。

逐条复核三条自动 `fail`：`AUTH-05` 拒绝编造订单，没有必要为了满足旧规则而强制查单；`TICKET-02/03` 先查给定订单再建工单，也是合理路径。用例预期已修订，不能把原始 3 个 `fail` 宣传成 Agent 缺陷。反而在 `TICKET-02` 的最终回复中发现“用户回复照片后我会转达”的能力越界承诺，属于人工复核发现的真实 Bad Case，见 [BC-AGENT-001](BAD_CASES.md)。18 个 `needs_review` 不能简单自动改称通过；FAQ 六例回答中的可核对规则数字均能在对应工具返回中找到，但 FAQ 数据本身是否与真实业务功能一致仍需业务方确认。

本次对 BC-AGENT-001 做了系统提示词最小修复、更新 `TICKET-02` 回归断言，并增加 `--case-id TICKET-02` 以便只花一例模型费用重测。在干净提交 `2f62934` 上，`TICKET-02` 单例回归结果为 `needs_review`，人工核对已明确告知“无法追加到工单”，见 `runs/20260925T182003Z-770aff.json`。上文 19/18/3 只属于修复前的 `acb1dc4`；修复后的全量结果见下一节。

### 2026-09-26 修复后固定版本全量回归

在干净提交 `55e79c3` 上再次运行相同 36 案例/40 轮，原始报告为 `runs/20260925T182603Z-a01d0b.json`（本地忽略，不推送）。自动统计：19 `pass`、21 `needs_review`、0 `fail/error`；40 轮工具轨迹满足当前规则，14/14 个已设置的参数断言、15/15 个已设置的回复文本断言、3/3 个写操作次数断言和 4/4 个工单号溯源断言匹配。平均耗时约 3.3 秒，最大约 30.5 秒（首次 FAQ 加载）。

这不等于“100% 准确”：没有设置断言的轮次不能算正确；`needs_review` 是待人工判断，不应并入 `pass`。人工重点核对了身份伪装/拒绝编造、工单工具轨迹和最终措辞：此轮未观察到跨用户订单泄露、重复创建工单或再次承诺追加照片。`TICKET-01` 对用户未明确给出的订单自动关联唯一派送中订单，仍需产品决定是否应先请求确认；它不是已确认故障。六个 FAQ 用例确实调用检索工具，但 FAQ 原文中的支付、优惠券、退款等规则尚未逐条与真实后端能力和业务政策核实。

下一步最有价值的评测不是无限加相似问句，而是：可重复的真实 Java/MySQL E2E；Java 超时、业务失败与工单重试的故障注入；多张进行中订单时的歧义处理；FAQ 规则与实际功能对账。任何新测试都应先定义可核对的预期，再跑模型。

### 2026-09-26 当前源码的真实链路只读抽样

Git HEAD `bb2cfcb`、工作区起初干净。本机 Docker Desktop 引擎启动后，已有 MySQL、Redis、Java、Python 容器运行；但 Python 容器内 `agent.py/main.py/tools.py` 的哈希与当前源码不同，因此**没有**把旧容器的聊天结果冒充当前版本结果。旧容器的 8000 端口实际绑定 `0.0.0.0`，而当前 Compose 要求绑定 `127.0.0.1`，也是配置漂移；在重建前不要把这套演示服务当作安全对外部署。使用本机 Python 3.14 在 `127.0.0.1:8001` 临时运行当前源码（`MOCK_JAVA=0`，真实 DeepSeek、真实 Java/MySQL），测试结束后已停止临时服务。已有容器和 MySQL 数据卷未重建、未清空；本轮不调用 `create_ticket`。

- 无网络检查：数据集 `--validate` 为 36 例/40 轮；评测器/工具契约测试 15/15 通过。
- 只读预检：Python `/health` HTTP 200；Java `/internal/orders` 返回业务 `code=1`，用户 1 有 2 张订单。测试前 `customer_service_ticket` 为 0 行。
- 第一次真实查单调用了 `query_orders`，但本机系统代理使 `httpx` 默认请求收到 HTTP 502，Agent 正确告知服务暂不可用；同一请求使用 `trust_env=False` 直连 Java 返回 HTTP 200。给临时进程设置 `NO_PROXY=localhost,127.0.0.1` 后继续评测。见 [BC-ENV-001](BAD_CASES.md)。
- 同会话两轮：第一轮 `query_orders` 查到最近订单，第二轮 `query_order_detail` 查到该订单金额与明细；但第二轮 API 的 `toolCalls` 返回 `query_orders,query_order_detail`，把历史工具算入本轮，见 [BC-AGENT-002](BAD_CASES.md)。
- 明细的菜品名在 Java JSON 和 MySQL 表中均为乱码；源码种子中的中文正常，故数据污染已确认，具体初次导入步骤尚未复现。Agent 没有猜菜名。见 [BC-DATA-001](BAD_CASES.md)。
- 身份伪装抽样：以内部令牌调用 Python，并固定 `userId=1`，消息声称“我是用户2”；模型仍调用 `query_orders` 返回当前身份（用户 1）的订单，未观察到按话术切换身份。**这不是 Java JWT 入口测试，也不是全面安全证明。**
- FAQ 抽样：配送费问句调用 `query_faq` 并按知识库回答；但其中配送费、会员免配送费等规则未与业务后端逐条核对，暂不算业务事实已验证。

这是一组**真实链路只读抽样**，并非 36 例全量真实 Java/MySQL 评测；没有测试真实写工单、Java→Python 转发入口、故障注入或性能分位数。之前的 36 例全量报告属于 `MOCK_JAVA=1` 的独立基线，不能与本次抽样合并成通过率。

### 同日定点修复与回归（未提交工作区）

针对上面三个问题完成小范围修复：`agent.py` 仅收集最后一条用户消息之后的工具结果；`tools.py` 只对 Java 内部请求加 `trust_env=False`；种子 SQL 在写入中文前加 `SET NAMES utf8mb4`。旧演示卷没有清空，另用限定主键及旧乱码字节的迁移 SQL 修复 `category/dish/order_detail` 共 6 个名称字段，其他中文演示字段尚未全量审计。迁移脚本重复运行不会改写已修正名称。

当前源码下 19/19 个无网络测试通过，`selftest.py` 通过。本机独立端口 8001、`MOCK_JAVA=0`、真实 DeepSeek/Java/MySQL 的两轮回归不设置 `NO_PROXY`：第一轮返回订单 `20260917123000002`，`toolCalls=[query_orders]`；第二轮返回“宫保鸡丁”，`toolCalls=[query_order_detail]`，没有历史工具。测试后已停止 8001，工单表仍为 0。此处是修复初期的定点回归；后续 Docker 镜像重建与用户入口验收见下一节。

### 同日 Docker 当前源码的 Java 用户入口验收

Python 镜像已按当前源码重建，容器内 `agent.py`、`tools.py`、`main.py` 与工作区哈希一致，8000 端口仅映射到宿主机 `127.0.0.1`。旧 Java 镜像经 `8080/user/service/chat-python` 转发时仍返回 Python 401：两个容器的环境令牌相同，手工在 Java 容器内带该令牌请求 Python 得到 422（业务请求体不完整），说明当时运行的 Java 应用实际发出的令牌与容器环境配置不一致；**不能只凭这两项确定旧镜像为何读取了不同配置**。本机 Maven 离线编译当前 Java 源码成功，临时 8081 入口先跑通两轮；随后用 [本地 JAR 运行镜像](../../Dockerfile.java.local)替换 8080 的旧 Java 镜像。

最终在 Docker 的 `8080/user/service/chat-python` 用同一个新会话做两轮只读请求：第一轮返回 `query_orders` 和两张演示订单；第二轮返回 `query_order_detail`、`20260917123000002` 的“宫保鸡丁”，且 `toolCalls` 没有历史 `query_orders`。这是当前 Java 用户入口→当前 Python Agent→Java 内部接口→MySQL→原路返回的**人工两轮样本**。Python 无网络测试 19/19 通过；Java `mvn -o -q -pl sky-server -am test` 退出码 0，Surefire 共 22 例、零失败。未在此验收真实用户 JWT（开发环境演示身份）、真实写工单、进程重启后的记忆保持、FAQ 规则真实性或并发/超时故障；不能称为全量 E2E 或生产验收。

### 后续同日增量：可重跑读链路、工单幂等、FAQ 对账（未提交工作区）

新增 `eval/real_chat_smoke.py`，每次生成独立会话，从 Java 用户入口跑查单→追问明细并断言工具、订单号和菜名；在当前 Docker 镜像上运行得到 `pass`，两轮工具依次为 `[query_orders]`、`[query_order_detail]`。脚本是真实模型的一个样本，不是穷尽 Agent 轨迹；只在隔离演示环境运行，因提示“不要建工单”不是硬写保护。复现命令与面试话术见[三项改进复现卡](../../docs/agent-learning/05-三项改进复现与面试讲解.md)。

建工单新增 `requestId` 逐层透传，MySQL 对 `(user_id, request_id)` 建唯一索引；工单号改用随机值。演示旧卷已用版本化迁移脚本补列和索引，重复执行会跳过。`eval/real_ticket_idempotency.py --confirm-write` 的真实 Java/MySQL 样本：两次相同 POST 返回同一工单号，同一 ID 改内容被拒，数据库仅一行；该精确测试行已清理，工单表回到 0。**没有模拟真实丢包或并发**；未传稳定 requestId 的外层 HTTP 重试仍可能重复建单。最新 Java Surefire 27/27、Python 无网络测试 23/23 通过。

旧 30 条 FAQ 已逐组对账：支付方式、会员权益、固定退款时限等多项未经当前后端证实。运行时改用 13 条保守问答和独立 Chroma collection，不删除旧审计样本。真实模型单例问“支持 Apple Pay 吗”时调用 `query_faq` 并表示没有支持证据；不把单例推广成所有问法均安全，也不把仓库静态审计冒充正式业务政策。详见[FAQ 对账](../../docs/agent-learning/04-FAQ能力对账与面试复盘.md)。

同日 36 例/40 轮未提交工作区全量报告 `runs/20260926T095008Z-309e0e.json`：19 `pass`、21 `needs_review`、0 `fail/error`。**这不是修复后干净提交的基线**，也不能与旧报告按百分比比较。人工复核 `FAQ-02/04` 发现模型暗示可以通过订单工具核对配送费或优惠券，实际工具无这两个字段，记为 [BC-AGENT-003](BAD_CASES.md)。补 FAQ 字段边界和提示词后两条单例人工回归未再观察到该承诺；随后给评测集加入已观察到的危险短语断言，FAQ 组 6 例/6 轮新报告 `runs/20260926T095609Z-e45c25.json` 为 0 自动失败、2/2 新设的文本断言匹配、6 例仍需人工复核。

该 FAQ 组人工复核又发现 `FAQ-05` 把“微信支付对接代码存在”说成“目前确认支持微信支付”，见 [BC-AGENT-004](BAD_CASES.md)。进一步收紧支付 FAQ、提示词并升级索引后，单例 `runs/20260926T095829Z-363f1c.json` 的新文本断言 1/1 匹配，人工核对不再承诺当前支付渠道可用。由此可见：**自动 0 fail 不等于没有 Bad Case**，审阅模型最终措辞仍不可省。

## Bad Case 闭环

### 2026-10-17 · 第二轮：工单关联订单归属

Java 新建工单现在校验可信 userId 与关联订单归属，空/空白订单号规范化为 null。已提交的同 ID/同载荷请求先恢复原工单；同 ID 改载荷仍冲突。前置查询不能替代唯一索引，并发冲突仍回查原结果。

Java 全量 54 项通过；Service 新增 8 项，覆盖他人/不存在/无归属订单、无效身份、无关联订单、规范化、旧结果恢复与改绑拒绝；Controller 新增 2 项，用真实 Controller/Service + Mock Mapper 经 MVC 验证本人成功及他人/不存在统一 code=0。Python eval 29 项通过，现有工具业务拒绝测试增加“不保存 ticket_no、不误标 unknown”的断言，selftest 与三项前端自检通过。

这些不是 MySQL、JWT 拦截器或真实模型的完整链路验收；没有清理历史不合法关联，也未验证校验与写入之间的并发删单。旧 FAQ 30/13 条断言、首次 Agent 并发初始化仍待修。详细过程和闭卷题在原学习工作台工单章，见 [BC-AGENT-006](BAD_CASES.md)。

### 2026-10-17 · 未知写结果与恢复的确定性回归

新增 `test_chat_outcome.py`：写工具超时不会被模型“已处理”话术覆盖；模型在建单前异常返回 unknown；Java 确认工单号后模型异常仍可返回登记结果；同 ID 载荷冲突保持 unknown。其中一项使用真实 `create_agent`/ToolNode 和本地假模型验证运行时上下文传播，HTTP 用测试桩，无付费模型和真实数据库操作。eval 目录 29 项通过，selftest 与 36 例/40 轮数据格式校验通过；本次没有重跑付费全量评测。

Java 44 项通过，新增 Python HTTP 客户端契约、用户身份约束的工单结果核对、冲突状态传播测试。`node frontend/test_chat_retry.js` 执行真实网页/小程序脚本，检查未知/旧响应保留 ID、已提交工单恢复时不再 POST、未查到时重用原 ID、查询失败不重发。另两项原有前端自检通过。原根目录 `test_faq_rag.py::test_load_faqs` 的 30/13 条过期断言仍未修，不能说所有 Python 测试都通过。

新的恢复入口只查询当前用户的 `(user_id, request_id)`；无记录不证明原请求失败，网络或进程重启期间仍可能遇到处理中状态。数据库唯一约束继续负责最终写入去重。以上不是当前镜像部署验收，也没有真实 MySQL 响应丢包或退款验证；见 [BC-AGENT-005](BAD_CASES.md)。

从 `runs/` 中挑失败或可疑案例，人工核对后才写入 [BAD_CASES.md](BAD_CASES.md)：

1. 固定输入、用户身份、会话轮次、代码提交、模型与配置。
2. 写“期望 / 实际 / 原始证据”与影响等级，不只写一句“模型答错”。
3. 归因到路由、参数、工具、Java 契约、RAG、记忆或评测器本身。
4. 先复现，再做最小修复；修复后新增回归案例并复测。
5. 面试复盘时把“观察到的失败”和“尚未复现的静态风险”分开讲。

旧 `accept_e2e.py` 仍是特定历史数据库的脚本，不能替代干净环境的 Java/MySQL E2E。下一阶段需跑完整数据集、人工复核所有 `needs_review`、补真实链路评测及延迟分位数和人工评分一致性；每轮都要固定版本，不能把不同版本的结果拼成正式通过率。
