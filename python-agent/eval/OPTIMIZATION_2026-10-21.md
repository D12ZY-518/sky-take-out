# 客服 Agent 排查与优化 · 2026-10-21

这是首轮记录。后续已加入会话 TTL、历史压缩及跨 HTTP 重试状态保护，当前行为与最终 106 项测试结果见[第二轮记录](FOLLOWUP_2026-10-21.md)。

本轮在已有未提交工作区上修改 Python 客服 Agent，保留原有改动。未修改 Java、部署配置或业务数据库，未调用付费模型。以下结论来自确定性本地回归，不代表真实模型全量评测成绩。

## 修复记录

| 问题与复现 | 修复后行为 |
| --- | --- |
| 6 个首次并发请求构造了 6 个 Agent，记忆实例各自独立 | 初始化加锁，共用一个 Agent 与记忆实例 |
| 同会话调用重叠；直接调用 run 时用户隔离依赖 HTTP 层 | run 统一按用户和会话隔离，同会话串行，其他会话可并发；拒绝上下文身份不一致 |
| 未显式设模型等待预算，反复调用工具可能长时间执行 | 单次模型请求默认超时 30 秒、自动重试 0 次，图执行最多 12 步，均可配置 |
| 回复内容块、空答复或未结束的工具调用可被当作最终答案 | 只提取最终助手的可见文本，异常结果进入既有兜底路径 |
| 非法身份、空白消息等会进入模型；非 ASCII 令牌触发 500 | HTTP 层拒绝非法入参，异常令牌返回 401；未配模型保留 unknown，避免重试 ID 被误丢弃 |
| 负数 limit、异常订单号等参数使 Mock 与真实路径表现不一 | 工具 Schema 和函数内部均校验，拒绝能改变 URL 路径或查询的订单号，缺失身份不执行 |
| 同轮重复或并行建单多次 POST，改写内容与写入状态互相覆盖 | 当前 Context 固定首次提交载荷并串行建单，成功后复用回执；未知后改参或收到畸形响应仍保持 unknown |
| FAQ 更新、删除后继续使用非空旧索引，首次并发重复初始化 | 按题库内容与模型指纹切换 collection，完成标记在写入成功后保存，未变题库复用索引 |
| 环境模板指向旧 30 条 FAQ，旧测试也假定 30 条 | 模板改为 faq_grounded.json；FAQ 默认测试使用假向量库，不下载模型 |

Mock 查单增加时间排序；Mock 工单关联订单时也核对归属。FAQ 空问题、无效题库及畸形检索返回均有回归覆盖。

复核真实 Chroma 时发现，modify(metadata=...) 不能再次带入 hnsw:space，即使值不变。完成标记更新已排除该字段；新增真实 Chroma 临时索引测试，使用固定本地向量验证建库、检索和题库切换，不加载 bge-m3。

## 验证

- eval 目录：62 项通过，含真实 LangChain 图的本地假模型测试，以及真实 Chroma 的临时索引测试。
- test_faq_rag.py：12 项通过，覆盖并发初始化、更新与删除、模型变更、空题库、重复 ID、失败重试与 top-k。
- selftest.py：通过。
- eval/run_eval.py --validate：36 例 / 40 轮、7 张 Mock 订单格式校验通过；没有执行真实模型问答。
- git diff --check -- python-agent：通过。

在 python-agent 目录复跑。本机 PATH 的 python 命中失效的 ServBay current 包装器，本轮使用已存在的解释器：

```powershell
$agentPython = 'D:/AAAAAAAAAA——developor/ServBay/packages/python/3.14/python.exe'
$env:PYTHONUTF8 = '1'
& $agentPython -m unittest discover -s eval -p 'test_*.py' -v
& $agentPython test_faq_rag.py
& $agentPython selftest.py
& $agentPython eval/run_eval.py --validate
```

真实 Chroma 契约测试在未安装可选 chromadb 时会跳过，本机本轮没有跳过。Dockerfile 另行安装 RAG 依赖；实际向量检索仍需 RAG 依赖与模型文件。

## 配置与限制

新增配置为 MODEL_TIMEOUT_SECONDS=30、MODEL_MAX_RETRIES=0、AGENT_RECURSION_LIMIT=12。30 秒作用于单次模型 HTTP 请求，12 步包含模型和工具节点，不能当作整轮总耗时承诺。评测报告也记录这些配置。

HTTP 请求约束：userId 为正整数；sessionId 去除首尾空白后 1～128 字；message 去除首尾空白后 1～2000 字；可选 requestId 为 8～64 位字母、数字、下划线或短横线。

会话锁、当前执行的工单回执缓存均在进程内。跨 HTTP 请求的最终写入去重仍依赖现有 Java/MySQL 唯一约束，不能用 Mock 回执复用证明数据库幂等。历史仍使用 InMemorySaver，无 TTL，进程重启会丢失；本轮未实施持久化、历史裁剪或多进程会话调度。

新 FAQ 索引保留旧 collection，首次使用新指纹时需要重新计算向量；历史 collection 未自动清理。指纹包含模型名称，不包含同名远端模型权重的版本号。题库过期规则本身仍需业务审核。

本轮未重建运行镜像，未做真实 DeepSeek、bge-m3 召回质量或 Java/MySQL 全链路验收。
