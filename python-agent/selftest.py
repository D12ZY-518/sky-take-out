# selftest.py —— 不依赖真实 LLM 的本地自检：agent 能否装配 + 三工具 mock 全分支 + FastAPI 接口
# 跑法：python selftest.py
#
# 【它和 accept_e2e.py 的分工】（两层测试，面试常问"你的 AI 功能怎么测"）
#   selftest.py  = 单元/集成自检：不调真实大模型、不花钱、不网络依赖，秒级跑完，改动后随手跑；
#   accept_e2e.py = 端到端验收：真实 LLM + 真实 Java + 真实落库，验证"模型决策是否正确"这层。
#   为什么 selftest 不碰 LLM？—— 模型输出不确定，同一句话今天回"订单号 xxx"明天换个说法，
#   断言会抖动（flaky）；把【确定性部分】（工具逻辑、分支、接口契约）和【不确定性部分】
#   （模型话术）分层测，是 AI 工程的基本功。Java 对照：JUnit 单测不连真实第三方服务。
# 自检只验证工具逻辑与 HTTP 契约，不应受开发机 .env / Compose 的 MOCK_JAVA=0 影响。
# 在导入 config/tools 前固定为 mock，避免测试误连未启动的 Java 服务。
import os
import sys
os.environ["MOCK_JAVA"] = "1"
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

import tools
from tools import query_orders, query_order_detail, create_ticket, UserContext


class R:
    """模拟 LangChain 注入的 ToolRuntime（只需要 context 字段）
    【为什么需要它】工具函数签名里有 runtime: ToolRuntime[UserContext] 参数，
    直接调用必须传。但 selftest 只想测工具的【纯业务逻辑】，不想搭一套真的
    LangChain 执行环境 —— 于是用鸭子类型（duck typing）塞个"长得像"的对象：
    只要它有 .context.user_id，工具代码就照常工作。
    Java 对照：Mockito.mock(ToolRuntime.class) + when(...) 打桩，思路一模一样。
    """
    def __init__(self, uid):
        self.context = UserContext(user_id=uid, request_id=f"selftest-{uid}")


def check(name, got, expect_kw):
    """断言 helper：got 里必须包含 expect_kw 列表中的【每一个】关键词。
    【为什么用"包含关键词"而不是全文比对】工具返回的是给人/模型看的文本，
    措辞可能微调；断言只锁定业务要点（订单号、状态词、拒绝话术），
    格式变化不会误报。all() = Java 的 list.stream().allMatch(...)。
    assert 失败会抛 AssertionError 立刻中断 —— 同 JUnit 的 assertEquals 失败即停。
    """
    ok = all(k in got for k in expect_kw)
    print(("PASS" if ok else "FAIL"), name, "=>", got.replace("\n", " | ")[:120])
    assert ok, f"{name} 期望含 {expect_kw}"


print("== 1. agent 模块可导入（空 key 时懒加载，不构造模型） ==")
# 【验证什么】import agent 不炸 = 模块顶层没有"启动即需要 API Key"的副作用；
# 真正构造 ChatOpenAI 被推迟到第一次 run()（agent.py 的 _get_agent 懒加载）。
# 收益：没配 key 也能 import 工具来测，且 FastAPI 服务能先起、接口能返回兜底文案。
import agent as ag
assert callable(ag.run) and len(ag._TOOLS) == 4
print("PASS agent 导入成功，已注册工具 =", [t.name for t in ag._TOOLS])

print("\n== 2. query_orders 分支 ==")
# 【语法点】@tool 把函数包成了 StructuredTool 对象（不再是普通函数），
# .func 拿回【原始函数】直接调 —— 对应 Java 里"取被代理对象的原生方法"来单测。
f1 = query_orders.func
check("A user1 全部(4单)", f1(runtime=R(1)), ["1788231207410", "test123"])          # 不筛状态 → 4 单全出（倒序）
check("B user1 status=5(2单)", f1(status=5, runtime=R(1)), ["1788231207410", "1787893545201"])  # 筛已完成
assert "cancel" not in f1(status=5, runtime=R(1))    # 反向断言：已完成里绝不能混进已取消单（过滤真的生效）
check("C user1 status=1 无单", f1(status=1, runtime=R(1)), ["没有符合条件"])        # 筛出空集要走空集话术，不是崩
check("D user2 身份隔离", f1(runtime=R(2)), ["你当前没有任何订单"])                 # 换个 uid 世界就不同（防串数据）
r_limited = f1(limit=1, runtime=R(1))
assert r_limited.count("订单号") == 1                # 数出现次数：limit 截断真的只留 1 条
check("E limit=1", r_limited, ["1788231207410"])

print("\n== 3. query_order_detail 分支 ==")
# G/G2/G3 对应 Java 内部接口的 404/403 语义（mock 分支用同样两句话术模拟）
f2 = query_order_detail.func
check("F 本人订单明细", f2("1788231207410", runtime=R(1)), ["王老吉", "北冰洋"])    # 正路：本人单 → 出明细
check("G 不存在单号", f2("not_exist", runtime=R(1)), ["没有这张订单"])              # 404 语义
# G2 关键用例：user2 查 user1 的单。单【存在】但不是你的 → "无权查看"（= Java 403）
# 这正是"归属校验"防越权的主场景：模型被用户忽悠塞别人的单号，工具这里拦下。
check("G2 user2 查 user1 的单=越权", f2("1788231207410", runtime=R(2)), ["无权查看"])
# G3：mock 库【故意没放】user4 的单（见 tools.py _MOCK_ORDERS 注释），
# 所以 user1 查 user4 的单号 → 查无此单 → "没有这张订单"（= Java 404）
check("G3 user1 查 user4 的单=查不到", f2("1776845110793", runtime=R(1)), ["没有这张订单"])

print("\n== 4. create_ticket 分支 ==")
f3 = create_ticket.func
# mock 分支只造号不落库；验收"TK + 诉求被复述"两个要点（真实落库在 accept_e2e 场景 3 + 查库）
check("H 建工单", f3("退款", "想取消订单退款", runtime=R(1)), ["TK", "退款"])

print("\n== 5. FastAPI /health 与 /agent/chat 入参校验（不真正调模型）==")
# TestClient：FastAPI 官方的内存级测试客户端，不发真实网络请求，直接在进程内走完整
# 路由/校验/异常链 —— Java 对照：spring-boot-starter-test 的 MockMvc。
from fastapi.testclient import TestClient
from main import app
client = TestClient(app)
r = client.get("/health")
assert r.status_code == 200 and r.json() == {"status": "ok"}
print("PASS /health =", r.json())
# 缺字段应 422（pydantic 校验），证明门童的契约生效
# 422 = Unprocessable Entity，FastAPI/pydantic 对"字段缺失/类型不对"的标准响应
# （Java 对照：@Valid 校验失败时 Spring 抛 MethodArgumentNotValidException → 400）
r2 = client.post("/agent/chat", json={"userId": 1})
assert r2.status_code == 422
print("PASS 缺 message 时被拦在门外 status =", r2.status_code)
# 没配 key 时接口不 500，回兜底文案 —— 验证 main.py 的"配置缺失降级"分支：
# 服务起得来、请求打得通、回复说得清怎么修，而不是甩 500 白屏。
# 【注意】不能依赖"环境里恰好没配 key"（配了 key 的机器上这条会真实调模型而误报），
# 所以在进程内把 config 模块的 key 临时清空强制走兜底分支，测完恢复。
# 模块是单例：main.py 的 c 和这里的 c 是同一个对象，改一处全局生效（= Java 静态字段语义）。
import config as cfg
_orig_key = cfg.DEEPSEEK_API_KEY
cfg.DEEPSEEK_API_KEY = ""
try:
    r_unauthorized = client.post("/agent/chat", json={"userId": 1, "message": "你好"})
    assert r_unauthorized.status_code == 401
    print("PASS 缺少内部令牌被拒绝 status =", r_unauthorized.status_code)
    r3 = client.post("/agent/chat", headers={"X-Internal-Token": cfg.INTERNAL_TOKEN},
                     json={"userId": 1, "message": "你好"})
    assert r3.status_code == 200 and "DEEPSEEK_API_KEY" in r3.json()["reply"]
    print("PASS 无 key 兜底回复 =", r3.json()["reply"][:40], "...")
finally:
    cfg.DEEPSEEK_API_KEY = _orig_key   # finally 保证恢复：测试不改坏现场（= JUnit @After 的自觉）

print("\n全部通过 ✅")
