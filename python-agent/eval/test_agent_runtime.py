"""Agent 运行时回归：假模型与内存调用，不访问模型、Java 或向量库。"""

import threading
import time
import unittest
from concurrent.futures import ThreadPoolExecutor
from types import SimpleNamespace
from unittest.mock import Mock, patch

from langchain_core.language_models.fake_chat_models import FakeMessagesListChatModel
from langchain_core.messages import AIMessage
from langgraph.checkpoint.memory import InMemorySaver
from langgraph.errors import GraphRecursionError

import agent
from tools import UserContext


def msg(kind, content="", name=None, tool_calls=None):
    return SimpleNamespace(type=kind, content=content, name=name,
                           tool_calls=tool_calls or [])


class LocalToolChatModel(FakeMessagesListChatModel):
    def bind_tools(self, tools, **kwargs):
        return self


class AgentRuntimeTest(unittest.TestCase):
    def test_concurrent_first_requests_share_one_agent(self):
        ready = threading.Barrier(6)
        created = []

        def build(**kwargs):
            # 保持构造窗口开启，让没有初始化锁时的多次构造稳定复现。
            time.sleep(0.04)
            instance = object()
            created.append(instance)
            return instance

        def get():
            ready.wait(timeout=2)
            return agent._get_agent()

        with patch.object(agent, "_agent", None), \
                patch.object(agent, "ChatOpenAI"), \
                patch.object(agent, "create_agent", side_effect=build):
            with ThreadPoolExecutor(max_workers=6) as pool:
                results = list(pool.map(lambda _: get(), range(6)))

        self.assertEqual(len(created), 1)
        self.assertTrue(all(result is results[0] for result in results))

    def test_model_has_explicit_timeout_retry_and_graph_limit(self):
        fake = Mock()
        fake.invoke.return_value = {"messages": [msg("human", "你好"), msg("ai", "你好")]}
        with patch.object(agent, "_agent", None), \
                patch.object(agent, "ChatOpenAI") as model, \
                patch.object(agent, "create_agent", return_value=fake):
            agent.run(1, "limits", "你好")

        self.assertEqual(model.call_args.kwargs["timeout"], agent.c.MODEL_TIMEOUT_SECONDS)
        self.assertEqual(model.call_args.kwargs["max_retries"], agent.c.MODEL_MAX_RETRIES)
        self.assertEqual(fake.invoke.call_args.kwargs["config"]["recursion_limit"],
                         agent.c.AGENT_RECURSION_LIMIT)

    def test_same_session_calls_do_not_overlap(self):
        gate = threading.Barrier(2)
        active = 0
        peak = 0
        guard = threading.Lock()

        def invoke(data, **kwargs):
            nonlocal active, peak
            with guard:
                active += 1
                peak = max(peak, active)
            time.sleep(0.04)
            with guard:
                active -= 1
            return {"messages": [msg("human", "查订单"), msg("ai", "查询完成")]}

        def run():
            gate.wait(timeout=2)
            return agent.run(1, "shared", "查订单")

        with patch.object(agent, "_get_agent", return_value=Mock(invoke=invoke)):
            with ThreadPoolExecutor(max_workers=2) as pool:
                results = list(pool.map(lambda _: run(), range(2)))

        self.assertEqual(peak, 1)
        self.assertEqual(results, [("查询完成", []), ("查询完成", [])])

    def test_different_sessions_can_run_in_parallel(self):
        entered = threading.Barrier(2)

        def invoke(data, **kwargs):
            entered.wait(timeout=2)
            return {"messages": [msg("human", "你好"), msg("ai", "你好")]}

        with patch.object(agent, "_get_agent", return_value=Mock(invoke=invoke)):
            with ThreadPoolExecutor(max_workers=2) as pool:
                results = list(pool.map(lambda session: agent.run(1, session, "你好"),
                                        ["first", "second"]))
        self.assertEqual(results, [("你好", []), ("你好", [])])

    def test_user_identity_is_part_of_checkpoint_key(self):
        fake = Mock()
        fake.invoke.return_value = {"messages": [msg("human", "你好"), msg("ai", "你好")]}
        with patch.object(agent, "_get_agent", return_value=fake):
            agent.run(1, "same-browser-id", "你好")
            agent.run(2, "same-browser-id", "你好")
        keys = [call.kwargs["config"]["configurable"]["thread_id"]
                for call in fake.invoke.call_args_list]
        self.assertEqual(keys, ["user-1:same-browser-id", "user-2:same-browser-id"])

    def test_context_identity_must_match_requested_user(self):
        fake = Mock()
        with patch.object(agent, "_get_agent", return_value=fake):
            with self.assertRaises(ValueError):
                agent.run(1, "session", "查订单", context=UserContext(user_id=2))
        fake.invoke.assert_not_called()

    def test_structured_content_returns_only_visible_text(self):
        content = [{"type": "reasoning", "reasoning": "内部推理不能返回"},
                   {"type": "text", "text": "订单"}, "已完成"]
        fake = Mock()
        fake.invoke.return_value = {"messages": [msg("human", "查订单"), msg("ai", content)]}
        with patch.object(agent, "_get_agent", return_value=fake):
            self.assertEqual(agent.run(1, "session", "查订单"), ("订单已完成", []))

    def test_incomplete_or_empty_model_results_are_rejected(self):
        results = [
            {"messages": []},
            {"messages": [msg("human", "查订单"), msg("tool", "订单结果", "query_orders")]},
            {"messages": [msg("human", "查订单"), msg("ai", "", tool_calls=[{"name": "query_orders"}])]},
            {"messages": [msg("human", "查订单"), msg("ai", "   ")]},
            {"messages": [msg("human", "查订单"), msg("ai", [{"type": "reasoning", "reasoning": "思考"}])]},
        ]
        for result in results:
            with self.subTest(result=result):
                fake = Mock()
                fake.invoke.return_value = result
                with patch.object(agent, "_get_agent", return_value=fake):
                    with self.assertRaises(RuntimeError):
                        agent.run(1, "session", "查订单")

    def test_failed_request_releases_session_lock(self):
        fake = Mock()
        fake.invoke.side_effect = [TimeoutError("model timeout"),
                                   {"messages": [msg("human", "你好"), msg("ai", "你好")]}]
        with patch.object(agent, "_get_agent", return_value=fake):
            with self.assertRaises(TimeoutError):
                agent.run(1, "session", "你好")
            self.assertEqual(agent.run(1, "session", "你好"), ("你好", []))

    def test_real_graph_stops_repeating_tool_loop(self):
        model = LocalToolChatModel(responses=[
            AIMessage(content="", tool_calls=[{
                "name": "query_orders", "args": {}, "id": f"loop-call-{index}",
            }]) for index in range(6)
        ])
        graph = agent.create_agent(model=model, tools=agent._TOOLS,
                                   checkpointer=InMemorySaver(), context_schema=UserContext)
        with patch.object(agent, "_get_agent", return_value=graph), \
                patch.object(agent.c, "MOCK_JAVA", True), \
                patch.object(agent.c, "AGENT_RECURSION_LIMIT", 4):
            with self.assertRaises(GraphRecursionError):
                agent.run(1, "loop", "查订单")

    def test_real_graph_retains_history_and_reports_only_new_tools(self):
        model = LocalToolChatModel(responses=[
            AIMessage(content="", tool_calls=[{
                "name": "query_orders", "args": {}, "id": "orders-call",
            }]),
            AIMessage(content="已查询订单"),
            AIMessage(content="不客气"),
        ])
        graph = agent.create_agent(model=model, tools=agent._TOOLS,
                                   checkpointer=InMemorySaver(), context_schema=UserContext)
        with patch.object(agent, "_get_agent", return_value=graph), \
                patch.object(agent.c, "MOCK_JAVA", True):
            self.assertEqual(agent.run(1, "history", "查订单"),
                             ("已查询订单", ["query_orders"]))
            self.assertEqual(agent.run(1, "history", "谢谢"), ("不客气", []))
        history = graph.get_state({"configurable": {"thread_id": "user-1:history"}})
        self.assertEqual([m.content for m in history.values["messages"] if m.type == "human"],
                         ["查订单", "谢谢"])


if __name__ == "__main__":
    unittest.main()
