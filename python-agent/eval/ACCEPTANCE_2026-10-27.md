# 客服 Agent 本地真实链路验收 · 2026-10-27

本轮样本验收通过。当前运行服务已更新到工作区的 Agent 核心源码，完成真实模型、Java 订单接口、MySQL 工单、FAQ 向量检索和客服网页的联合验证。结论限于本地演示环境与本轮样本。

收尾更新（2026-10-27）：测试工单已完整备份并精确删除，原有两条工单保留，服务正常运行。当前数据状态和代码归档见[收尾记录](CLOSEOUT_2026-10-27.md)；下表保留验收当时的事实。

运行模型为 deepseek-v4-flash，Python 使用 MOCK_JAVA=0，FAQ 为 faq_grounded.json。容器内 8 个核心文件 SHA-256 与本地一致，Java 容器 JAR 与本次现有源码构建产物一致；本次没有修改 Java 源码。

## 实际验证结果

| 场景 | 证据与结论 |
| --- | --- |
| 查单与同会话追问 | 查到最新订单 20260917123000002、派送中、¥22.00；第二轮返回宫保鸡丁 ×1、¥22.00，仅报告本轮 query_order_detail |
| 相同 ID 重复读请求 | 返回原始 completed 回复，内容一致；本轮缓存返回约 9 毫秒 |
| FAQ 四组 | 退款、支付、配送费、会员问题均调用 query_faq；逐条复核未把未经核实的固定时限、支付可用性或会员权益说成已确认 |
| FAQ 索引 | 当前题库 13 条，Chroma 当前指纹 collection 为 13 条，完成标记与题库指纹一致 |
| 跨用户历史与订单归属 | 相同原始 sessionId 下，第二身份无法读取用户 1 订单，未得到菜品与金额；第二身份查单返回无订单。该项通过 Python 内部入口注入身份，不代表生产 JWT 已验收 |
| 客户端超时后重试 | 建单第一次请求将客户端读取超时设为 0.1 秒，实际观察到 ReadTimeout；随后原 sessionId、requestId、message 重试取回 completed 工单回执 |
| 相同 ID 修改输入 | 拒绝修改后的诉求，返回 unknown；不把原请求 ID 用作另一条工单 |
| 数据库去重 | 该验收 run 只新增 1 行；requestId、userId、工单号及内容中的验收标记均匹配。原有工单 id 4、5 保持原值，总数 2 → 3 |
| Python 停机 | Java 聊天入口友好降级并返回 unknown；原工单结果核对仍返回 completed，查询不依赖模型 |
| Python 重启与网页恢复 | 重启后注入原请求的浏览器待重试记录，点击“重试上一条”只发出 GET ticket-result，约 17 毫秒取回原工单，没有再次 POST 建单 |
| 网页正常流程 | 实际发送查单、追问明细，两轮分别约 2.386 秒、2.061 秒；成功后待重试记录清空。新会话切换 sessionId，并清空页面历史 |

脚本共提交 12 次 chat HTTP 请求，包含缓存重试、冲突请求和一次客户端超时注入；另通过网页实际发送两次查询。不能将 HTTP 请求次数直接当作模型调用次数、token 数或费用。

8 个新读场景样本平均 3.3345 秒，中位数 3.0902 秒，范围 1.9613～6.3905 秒。样本量较小，且未单独做冷启动基准，不构成性能 SLO。

## 工单与数据处理

验收时创建的测试工单为 id **6**，工单号 **TK9702eae28d1d432a8baa511dd6fdea**，requestId 为 `acceptance-test-20261027-8d91f2857f5c-ticket`。内容包含唯一验收标记，并明确“请忽略此测试记录，不进行任何业务处理”。收尾时经用户授权，先备份完整行，再核对所有字段后仅删除此测试行，工单总数从 3 恢复到 2。原有 id 4、5 的完整记录经前后比对一致。没有执行真实退款、支付或催单，原始验收证据继续保留。

## 回归与证据

- eval 目录 106 项、FAQ 离线测试 12 项，合计 **118 项通过**，没有跳过或 expectedFailure。
- selftest.py 通过；36 例 / 40 轮评测数据格式校验通过。本次没有把该格式校验冒充 36 例真实模型全量评测。
- 新增 accept_live.py 和 12 项测试；默认只输出计划，真实对话必须同时显式提供 --live、--limit 和 --confirm-write。

原始脚本报告保留自动检查和待人工复核状态；单独的复核文件记录数据库与网页证据，避免改写原始运行事实：

- [真实请求、回复及耗时](runs/acceptance-test-20261027-8d91f2857f5c.json)
- [人工语义复核与外部证据结论](runs/acceptance-test-20261027-8d91f2857f5c-review.json)
- [数据库只读核对](runs/acceptance-test-20261027-8d91f2857f5c-database.txt)
- [停机降级与原工单核对](runs/acceptance-test-20261027-8d91f2857f5c-restart.json)
- [源码、JAR 与镜像核对](runs/acceptance-test-20261027-8d91f2857f5c-deployment.json)
- [网页操作及网络证据](../../output/playwright/acceptance-20261027/ui-evidence.json)
- [网页两轮查询截图](../../output/playwright/acceptance-20261027/01-order-detail.png)
- [重启后取回原工单截图](../../output/playwright/acceptance-20261027/03-ticket-recovered.png)

原始 runs 文件与截图保存在本机，不随 Git 提交。

## 复跑入口

在 python-agent 目录，使用已安装的 Python。直接运行默认只产计划：

```powershell
$agentPython = 'D:/AAAAAAAAAA——developor/ServBay/packages/python/3.14/python.exe'
& $agentPython eval/accept_live.py
```

真实验收会产生模型费用并创建新测试工单。内部令牌通过现有只读配置 helper 注入进程环境，不打印令牌：

```powershell
@'
import os, sys, runpy
from eval.real_preflight import read_internal_token
os.environ['INTERNAL_TOKEN'] = read_internal_token()
sys.argv = ['eval/accept_live.py', '--live', '--limit', '16', '--confirm-write',
            '--identity-via-python', '--simulate-response-loss']
runpy.run_path('eval/accept_live.py', run_name='__main__')
'@ | & $agentPython -
```

## 验收范围与当前限制

Java 用户入口使用演示用户 1；第二身份通过内部 Python 入口验证，因此没有证明生产 JWT 登录、完整权限链或多用户生产部署通过。

本次客户端超时不等于“已在数据库提交之后丢失写响应”。验证成立的是同 ID 重试、权威回执和数据库唯一一行；尚未对 MySQL 事务提交边界进行故障注入。网页恢复通过注入浏览器待重试记录复现，未在浏览器层真实断网。

会话记忆与请求缓存仍为进程内状态；重启后通过现有工单查询恢复写入结果，不代表聊天历史能够持久恢复。首次网页控制台出现 favicon.ico 404，没有聊天脚本异常。

Python 本次使用 Dockerfile.python.local 复用已有且版本一致的依赖镜像更新源码；Java 用现有源码打包并通过 Dockerfile.java.local 更新运行产物。没有把本次结果称作空缓存完整镜像构建验收。
