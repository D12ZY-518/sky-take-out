"""真实本地图验证历史与检查点容量；无模型网络请求。"""
from unittest.mock import patch
import unittest

from langchain.agents import create_agent
from langchain_core.language_models.fake_chat_models import FakeMessagesListChatModel
from langchain_core.messages import AIMessage, HumanMessage, ToolMessage
from langgraph.errors import GraphRecursionError

import agent
from managed_memory import ManagedMemorySaver, MemoryCapacityError
from tools import UserContext


class LocalModel(FakeMessagesListChatModel):
    def bind_tools(self, tools, **kwargs):
        return self


class ManagedMemoryTest(unittest.TestCase):
    def store(self, **kwargs):
        return ManagedMemorySaver(max_sessions=kwargs.get("max_sessions", 2),
                                  max_turns=kwargs.get("max_turns", 3),
                                  ttl_seconds=10, clock=kwargs.get("clock", lambda: 0))

    def graph(self, store, responses):
        return create_agent(model=LocalModel(responses=responses), tools=agent._TOOLS,
                            checkpointer=store, context_schema=UserContext)

    def step(self, graph, store, thread, text):
        with store.session(thread):
            try:
                return graph.invoke({"messages": [HumanMessage(text)]},
                                    context=UserContext(1, "memory-test-001"),
                                    config={"configurable": {"thread_id": thread}})
            finally:
                store.compact(thread)

    def test_long_session_retains_complete_turns_and_one_checkpoint(self):
        store = self.store()
        graph = self.graph(store, [AIMessage(content=f"reply {i}") for i in range(40)])
        for index in range(40):
            self.step(graph, store, "growth", f"question {index}")
        state = graph.get_state({"configurable": {"thread_id": "growth"}})
        self.assertEqual([m.content for m in state.values["messages"] if m.type == "human"],
                         ["question 37", "question 38", "question 39"])
        self.assertEqual(len(state.values["messages"]), 6)
        self.assertEqual(sum(len(ns) for ns in store.storage["growth"].values()), 1)
        self.assertLessEqual(len(store.blobs), 10)

    def test_trim_keeps_tool_call_and_receipt_together(self):
        store = self.store(max_turns=1)
        graph = self.graph(store, [
            AIMessage(content="first"),
            AIMessage(content="", tool_calls=[{"name": "query_orders", "id": "orders",
                                               "args": {}}]),
            AIMessage(content="orders replied"), AIMessage(content="next"),
        ])
        with patch.object(agent.c, "MOCK_JAVA", True):
            self.step(graph, store, "pairing", "hello")
            self.step(graph, store, "pairing", "orders")
            state = graph.get_state({"configurable": {"thread_id": "pairing"}})
            self.assertEqual([m.type for m in state.values["messages"]],
                             ["human", "ai", "tool", "ai"])
            self.assertEqual(state.values["messages"][2].tool_call_id, "orders")
            self.step(graph, store, "pairing", "thanks")

    def test_production_run_uses_managed_store_and_compacts_after_each_turn(self):
        model = LocalModel(responses=[AIMessage(content=f"reply {i}") for i in range(40)])
        with (patch.object(agent, "_agent", None),
              patch.object(agent, "ChatOpenAI", return_value=model),
              patch.object(agent.c, "AGENT_MEMORY_MAX_TURNS", 3)):
            for index in range(40):
                self.assertEqual(agent.run(1, "managed-run", f"question {index}")[0],
                                 f"reply {index}")
            graph = agent._get_agent()
            self.assertIsInstance(graph.checkpointer, ManagedMemorySaver)
            state = graph.get_state({"configurable": {"thread_id": "user-1:managed-run"}})
            self.assertEqual(len(state.values["messages"]), 6)
            self.assertEqual(len(list(graph.get_state_history(
                {"configurable": {"thread_id": "user-1:managed-run"}}))), 1)

    def test_expiry_removes_all_thread_storage(self):
        now = [0]
        store = self.store(clock=lambda: now[0])
        graph = self.graph(store, [AIMessage(content="first"), AIMessage(content="second")])
        self.step(graph, store, "expired", "hello")
        now[0] = 11
        self.step(graph, store, "new", "hello")
        self.assertNotIn("expired", store.storage)
        self.assertFalse(any(key[0] == "expired" for key in store.blobs))
        self.assertFalse(any(key[0] == "expired" for key in store.writes))

    def test_capacity_evicts_idle_lru_and_protects_active_session(self):
        store = self.store()
        with store.session("active"):
            with store.session("idle"):
                pass
            with store.session("replacement"):
                self.assertIn("active", store._sessions)
                self.assertNotIn("idle", store._sessions)
        self.assertEqual(len(store._sessions), 2)

    def test_capacity_rejects_when_every_session_is_active(self):
        store = self.store(max_sessions=1)
        with store.session("active"):
            with self.assertRaises(MemoryCapacityError):
                with store.session("another"):
                    self.fail("capacity must reject new session")
        with store.session("another"):
            self.assertNotIn("active", store._sessions)

    def test_expiry_does_not_remove_active_session(self):
        now = [0]
        store = self.store(clock=lambda: now[0])
        with store.session("active"):
            now[0] = 100
            with store.session("other"):
                self.assertIn("active", store._sessions)

    def test_failed_invoke_preserves_pending_tool_state_without_execution(self):
        store = self.store()
        graph = self.graph(store, [AIMessage(content="", tool_calls=[{
            "name": "create_ticket", "id": "not-run", "args": {
                "category": "配送", "content": "少送菜"}}])])
        with store.session("failed"):
            with self.assertRaises(GraphRecursionError):
                graph.invoke({"messages": [HumanMessage("help")]},
                             context=UserContext(1, "memory-test-002"),
                             config={"configurable": {"thread_id": "failed"},
                                     "recursion_limit": 1})
            original = graph.get_state({"configurable": {"thread_id": "failed"}})
            store.compact("failed")
            restored = graph.get_state({"configurable": {"thread_id": "failed"}})
        self.assertEqual(original.next, restored.next)
        self.assertEqual(original.values["messages"], restored.values["messages"])
        self.assertFalse(any(isinstance(m, ToolMessage) for m in restored.values["messages"]))


if __name__ == "__main__":
    unittest.main()
