"""真实 checkpoint 恢复协议回归；模型与 HTTP 均离线，不访问外部服务。"""

from typing import Any
from threading import Event
import unittest
from unittest.mock import Mock, patch

from langchain_core.language_models.chat_models import BaseChatModel
from langchain_core.messages import AIMessage, ToolMessage
from langchain_core.outputs import ChatGeneration, ChatResult
from langchain.tools import tool
from langgraph.checkpoint.memory import InMemorySaver
from langgraph.errors import GraphRecursionError

import agent
import tools
from tools import UserContext


class InvalidToolProtocol(RuntimeError):
    pass


def validate_tool_protocol(messages):
    """模拟模型接口对 assistant.tool_calls 必须立即配对工具回执的校验。"""
    pending = set()
    for message in messages:
        if isinstance(message, ToolMessage):
            if message.tool_call_id not in pending:
                raise InvalidToolProtocol("orphan tool response")
            pending.remove(message.tool_call_id)
            continue
        if pending:
            raise InvalidToolProtocol("unanswered tool calls before next message")
        if isinstance(message, AIMessage):
            pending.update(call["id"] for call in message.tool_calls)
    if pending:
        raise InvalidToolProtocol("unanswered tool calls at model invocation")


class ProtocolCheckingModel(BaseChatModel):
    responses: list[Any]
    seen: list[Any] = []
    index: int = 0

    @property
    def _llm_type(self):
        return "offline-protocol-checking-model"

    def bind_tools(self, tools, **kwargs):
        return self

    def _generate(self, messages, **kwargs):
        self.seen.append(list(messages))
        validate_tool_protocol(messages)
        if self.index >= len(self.responses):
            raise AssertionError("unexpected model invocation")
        response = self.responses[self.index]
        self.index += 1
        if isinstance(response, Exception):
            raise response
        return ChatResult(generations=[ChatGeneration(message=response)])


def call_tool(name, call_id, **args):
    return AIMessage(content="", tool_calls=[{"name": name, "args": args, "id": call_id}])


def ticket_call(call_id="ticket-call"):
    return call_tool("create_ticket", call_id, category="配送", content="配送一直未到")


def graph_for(model):
    return agent.create_agent(model=model, tools=agent._TOOLS,
                              checkpointer=InMemorySaver(), context_schema=UserContext)


def ticket_response():
    response = Mock()
    response.json.return_value = {"code": 1, "data": {"ticketNo": "TKconfirmed"}}
    return response


class AgentRecoveryTest(unittest.TestCase):
    def test_next_turn_recovers_after_first_model_timeout(self):
        model = ProtocolCheckingModel(responses=[TimeoutError("offline timeout"),
                                                 AIMessage(content="服务已恢复")])
        graph = graph_for(model)
        with patch.object(agent, "_get_agent", return_value=graph):
            with self.assertRaises(TimeoutError):
                agent.run(1, "first-model-failure", "查订单")
            self.assertEqual(agent.run(1, "first-model-failure", "再试一下"),
                             ("服务已恢复", []))
        validate_tool_protocol(model.seen[-1])

    def test_model_timeout_after_ticket_does_not_replay_old_write(self):
        model = ProtocolCheckingModel(responses=[ticket_call(), TimeoutError("offline timeout"),
                                                 AIMessage(content="不客气")])
        graph = graph_for(model)
        first_context = UserContext(user_id=1, request_id="first-request-001")
        next_context = UserContext(user_id=1, request_id="second-request-002")
        with (patch.object(agent, "_get_agent", return_value=graph),
              patch.object(tools.c, "MOCK_JAVA", False),
              patch.object(tools.httpx, "post", return_value=ticket_response()) as post):
            with self.assertRaises(TimeoutError):
                agent.run(1, "ticket-model-failure", "帮我转人工", context=first_context)
            self.assertEqual(first_context.ticket_no, "TKconfirmed")
            self.assertEqual(agent.run(1, "ticket-model-failure", "谢谢", context=next_context),
                             ("不客气", []))
        self.assertEqual(post.call_count, 1)
        self.assertEqual(post.call_args.kwargs["json"]["requestId"], "first-request-001")
        self.assertIsNone(next_context.ticket_no)
        self.assertTrue(any(isinstance(message, ToolMessage) and "TKconfirmed" in message.content
                            for message in model.seen[-1]))
        validate_tool_protocol(model.seen[-1])

    def test_recursion_after_completed_ticket_does_not_replay_write(self):
        model = ProtocolCheckingModel(responses=[ticket_call(), AIMessage(content="不客气")])
        graph = graph_for(model)
        first_context = UserContext(user_id=1, request_id="first-request-001")
        with (patch.object(agent, "_get_agent", return_value=graph),
              patch.object(agent.c, "AGENT_RECURSION_LIMIT", 2),
              patch.object(tools.c, "MOCK_JAVA", False),
              patch.object(tools.httpx, "post", return_value=ticket_response()) as post):
            with self.assertRaises(GraphRecursionError):
                agent.run(1, "ticket-recursion", "帮我转人工", context=first_context)
            self.assertEqual(first_context.ticket_no, "TKconfirmed")
            self.assertEqual(agent.run(1, "ticket-recursion", "谢谢",
                                       context=UserContext(1, "second-request-002")),
                             ("不客气", []))
        self.assertEqual(post.call_count, 1)
        validate_tool_protocol(model.seen[-1])

    def test_recursion_before_ticket_can_recover_without_executing_old_write(self):
        model = ProtocolCheckingModel(responses=[
            call_tool("query_orders", "orders-call"),
            ticket_call("unexecuted-ticket"),
            AIMessage(content="好的，不再登记工单"),
        ])
        graph = graph_for(model)
        order_response = Mock()
        order_response.json.return_value = {"code": 1, "data": []}
        next_context = UserContext(1, "second-request-002")
        with (patch.object(agent, "_get_agent", return_value=graph),
              patch.object(agent.c, "AGENT_RECURSION_LIMIT", 3),
              patch.object(tools.c, "MOCK_JAVA", False),
              patch.object(tools.httpx, "get", return_value=order_response),
              patch.object(tools.httpx, "post") as post):
            with self.assertRaises(GraphRecursionError):
                agent.run(1, "pending-ticket", "查订单后转人工",
                          context=UserContext(1, "first-request-001"))
            failed = graph.get_state({"configurable": {"thread_id": "user-1:pending-ticket"}})
            self.assertTrue(failed.next)
            self.assertIsInstance(failed.values["messages"][-1], AIMessage)
            self.assertEqual(failed.values["messages"][-1].tool_calls[0]["id"], "unexecuted-ticket")
            self.assertEqual(agent.run(1, "pending-ticket", "不要登记了",
                                       context=next_context),
                             ("好的，不再登记工单", []))
        post.assert_not_called()
        self.assertIsNone(next_context.ticket_no)
        validate_tool_protocol(model.seen[-1])
        repaired = [message for message in model.seen[-1]
                    if isinstance(message, ToolMessage) and message.tool_call_id == "unexecuted-ticket"]
        self.assertEqual(len(repaired), 1)
        self.assertEqual(repaired[0].status, "error")
        self.assertIn("无法确认", repaired[0].content)

    def test_old_failed_retry_messages_do_not_prevent_protocol_repair(self):
        model = ProtocolCheckingModel(responses=[
            call_tool("query_orders", "orders-call"), ticket_call("unexecuted-ticket"),
            AIMessage(content="好的，不再登记工单"),
        ])
        graph = graph_for(model)
        with (patch.object(agent, "_get_agent", return_value=graph),
              patch.object(agent.c, "AGENT_RECURSION_LIMIT", 3),
              patch.object(tools.c, "MOCK_JAVA", True)):
            with self.assertRaises(GraphRecursionError):
                agent.run(1, "old-failed-retry", "查订单后转人工")
            # 模拟修复发布前的一次失败重试，checkpoint 已留下新的 HumanMessage。
            with patch.object(agent, "_repair_interrupted_tool_calls"):
                with self.assertRaises(InvalidToolProtocol):
                    agent.run(1, "old-failed-retry", "再试一次")
            self.assertEqual(agent.run(1, "old-failed-retry", "不要登记了"),
                             ("好的，不再登记工单", []))
        validate_tool_protocol(model.seen[-1])

    def test_partial_tool_failure_preserves_completed_write_receipt(self):
        written = Event()

        @tool
        def fail_after_ticket() -> str:
            """用于离线回归，在另一工具写入结束后模拟运行时异常。"""
            if not written.wait(timeout=2):
                raise AssertionError("ticket was not written")
            raise RuntimeError("offline tool failure")

        model = ProtocolCheckingModel(responses=[
            AIMessage(content="", tool_calls=[
                {"name": "create_ticket", "args": {"category": "配送", "content": "配送一直未到"},
                 "id": "completed-ticket"},
                {"name": "fail_after_ticket", "args": {}, "id": "failed-tool"},
            ]),
            AIMessage(content="工单已登记，请等待人工处理"),
        ])
        graph = agent.create_agent(model=model, tools=[tools.create_ticket, fail_after_ticket],
                                   checkpointer=InMemorySaver(), context_schema=UserContext)

        def post_response(*args, **kwargs):
            written.set()
            return ticket_response()

        first_context = UserContext(1, "first-request-001")
        next_context = UserContext(1, "second-request-002")
        with (patch.object(agent, "_get_agent", return_value=graph),
              patch.object(tools.c, "MOCK_JAVA", False),
              patch.object(tools.httpx, "post", side_effect=post_response) as post):
            with self.assertRaisesRegex(RuntimeError, "offline tool failure"):
                agent.run(1, "partial-tool-failure", "帮我转人工", context=first_context)
            self.assertEqual(first_context.ticket_no, "TKconfirmed")
            self.assertEqual(agent.run(1, "partial-tool-failure", "工单登记了吗", context=next_context),
                             ("工单已登记，请等待人工处理", []))
        self.assertEqual(post.call_count, 1)
        self.assertIsNone(next_context.ticket_no)
        validate_tool_protocol(model.seen[-1])
        receipts = {message.tool_call_id: message for message in model.seen[-1]
                    if isinstance(message, ToolMessage)}
        self.assertIn("TKconfirmed", receipts["completed-ticket"].content)
        self.assertEqual(receipts["completed-ticket"].status, "success")
        self.assertEqual(receipts["failed-tool"].status, "error")


if __name__ == "__main__":
    unittest.main()
