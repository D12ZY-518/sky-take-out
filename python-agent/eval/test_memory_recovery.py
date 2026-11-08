"""容量压缩与失败恢复联合回归：真实图、真实 pending_writes，模型和 HTTP 离线。"""

from threading import Event
import unittest
from unittest.mock import patch

from langchain.agents import create_agent
from langchain.tools import tool
from langchain_core.messages import AIMessage, HumanMessage, ToolMessage

import agent
from managed_memory import ManagedMemorySaver
from test_agent_recovery import ProtocolCheckingModel, ticket_response, validate_tool_protocol
import tools
from tools import UserContext


class MemoryRecoveryTest(unittest.TestCase):
    def exercise_partial_failure(self, *, old_turn=False, compactions=1):
        written = Event()
        failure_calls = []

        @tool
        def failing_tool() -> str:
            """离线模拟并行工具已完成写入后的另一工具异常。"""
            failure_calls.append("called")
            if not written.wait(timeout=2):
                raise AssertionError("ticket write did not start")
            raise RuntimeError("offline partial tool failure")

        scripted = [AIMessage(content="", tool_calls=[
            {"name": "create_ticket", "args": {"category": "配送", "content": "配送一直未到"},
             "id": "completed-ticket"},
            {"name": "failing_tool", "args": {}, "id": "interrupted-tool"},
        ]), AIMessage(content="工单已登记，请等待人工处理")]
        if old_turn:
            scripted.insert(0, AIMessage(content="欢迎咨询"))
        model = ProtocolCheckingModel(responses=scripted)
        memory = ManagedMemorySaver(max_sessions=2, max_turns=1, ttl_seconds=60)
        graph = create_agent(model=model, tools=[tools.create_ticket, failing_tool],
                             checkpointer=memory, context_schema=UserContext)
        thread = "user-1:compact-partial-failure"
        graph_config = {"configurable": {"thread_id": thread}}
        first_context = UserContext(1, "first-request-001")
        next_context = UserContext(1, "second-request-002")

        def post_response(*args, **kwargs):
            written.set()
            return ticket_response()

        with (patch.object(agent, "_get_agent", return_value=graph),
              patch.object(tools.c, "MOCK_JAVA", False),
              patch.object(tools.httpx, "post", side_effect=post_response) as post):
            with memory.session(thread):
                if old_turn:
                    graph.invoke({"messages": [HumanMessage("较早的咨询")]},
                                 context=UserContext(1, "older-request-000"), config=graph_config)
                    memory.compact(thread)
                with self.assertRaisesRegex(RuntimeError, "offline partial tool failure"):
                    graph.invoke({"messages": [HumanMessage("帮我转人工")]},
                                 context=first_context, config=graph_config)
                self.assertEqual(first_context.ticket_no, "TKconfirmed")
                self.assertTrue(memory.get_tuple(graph_config).pending_writes)
                before = graph.get_state(graph_config)
                for _ in range(compactions):
                    memory.compact(thread)
                after = graph.get_state(graph_config)
                self.assertEqual(before.next, after.next)
                # max_turns=1 时只删除较早完整轮，失败轮与 pending 的成功回执应保留。
                last_human = max(index for index, message in enumerate(before.values["messages"])
                                 if message.type == "human")
                self.assertEqual(before.values["messages"][last_human:], after.values["messages"])
                self.assertTrue(any(isinstance(message, ToolMessage)
                                    and message.tool_call_id == "completed-ticket"
                                    and "TKconfirmed" in message.content
                                    for message in after.values["messages"]))
            self.assertEqual(agent.run(1, "compact-partial-failure", "工单登记了吗", context=next_context),
                             ("工单已登记，请等待人工处理", []))

        self.assertEqual(post.call_count, 1)
        self.assertEqual(failure_calls, ["called"])
        self.assertIsNone(next_context.ticket_no)
        validate_tool_protocol(model.seen[-1])
        receipts = [message for message in model.seen[-1] if isinstance(message, ToolMessage)]
        self.assertEqual(len(receipts), 2)
        by_call = {message.tool_call_id: message for message in receipts}
        self.assertEqual(by_call["completed-ticket"].status, "success")
        self.assertIn("TKconfirmed", by_call["completed-ticket"].content)
        self.assertEqual(by_call["interrupted-tool"].status, "error")
        self.assertIn("无法确认", by_call["interrupted-tool"].content)

    def test_compaction_preserves_partial_success_without_replaying_tools(self):
        self.exercise_partial_failure()

    def test_repeated_compaction_trims_old_turn_but_preserves_pending_receipt(self):
        self.exercise_partial_failure(old_turn=True, compactions=3)


if __name__ == "__main__":
    unittest.main()
