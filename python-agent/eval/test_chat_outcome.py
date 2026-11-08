"""写操作结果通过结构化字段传播；模型文案不能把 unknown 变成 completed。"""
import unittest
from types import SimpleNamespace
from unittest.mock import Mock, patch

import httpx
from fastapi.testclient import TestClient

import agent
import main
import tools


class ChatOutcomeTest(unittest.TestCase):
    def request(self, invoke):
        fake = Mock()
        fake.invoke.side_effect = invoke
        with (patch.object(main.c, "DEEPSEEK_API_KEY", "test-key"),
              patch.object(main, "_request_states", main.RequestStateCache(600, 10)),
              patch.object(tools.c, "MOCK_JAVA", False),
              patch.object(main.traceback, "print_exc"),
              patch.object(agent, "_get_agent", return_value=fake)):
            return TestClient(main.app).post(
                "/agent/chat", headers={"X-Internal-Token": main.c.INTERNAL_TOKEN},
                json={"userId": 1, "sessionId": "outcome-test", "message": "转人工",
                      "requestId": "send-0001"}).json()

    def test_write_timeout_stays_unknown_even_when_model_claims_success(self):
        def invoke(inputs, *, context, config):
            with patch.object(tools.httpx, "post", side_effect=httpx.ReadTimeout("lost response")):
                tools.create_ticket.func("配送", "少送菜", runtime=SimpleNamespace(context=context))
            return {"messages": [SimpleNamespace(type="ai", content="已处理", name=None)]}
        body = self.request(invoke)
        self.assertEqual(body.get("outcome"), "unknown")
        self.assertIn("无法确认", body["reply"])

    def test_model_error_after_tool_call_is_unknown(self):
        def invoke(*args, **kwargs):
            raise RuntimeError("model unavailable")
        self.assertEqual(self.request(invoke).get("outcome"), "unknown")

    def test_read_only_reply_is_completed(self):
        body = self.request(lambda *args, **kwargs: {
            "messages": [SimpleNamespace(type="ai", content="你好", name=None)]})
        self.assertEqual(body.get("outcome"), "completed")

    def test_confirmed_ticket_records_receipt_even_when_model_reply_fails(self):
        def invoke(inputs, *, context, config):
            response = Mock()
            response.json.return_value = {"code": 1, "data": {"ticketNo": "TKconfirmed"}}
            with patch.object(tools.httpx, "post", return_value=response):
                tools.create_ticket.func("配送", "少送菜", runtime=SimpleNamespace(context=context))
            raise RuntimeError("model failed after insert")
        body = self.request(invoke)
        self.assertEqual(body.get("outcome"), "completed")
        self.assertIn("TKconfirmed", body["reply"])

    def test_request_conflict_requires_receipt_lookup_instead_of_discarding_id(self):
        def invoke(inputs, *, context, config):
            response = Mock()
            response.json.return_value = {"code": 0, "msg": "requestId 已用于另一条工单诉求",
                                          "data": {"outcome": "unknown"}}
            with patch.object(tools.httpx, "post", return_value=response):
                tools.create_ticket.func("配送", "模型改写的内容", runtime=SimpleNamespace(context=context))
            return {"messages": [SimpleNamespace(type="ai", content="本次被拒绝", name=None)]}
        self.assertEqual(self.request(invoke).get("outcome"), "unknown")

    def test_real_langchain_tool_dispatch_propagates_unknown_context(self):
        # 使用真实 create_agent/ToolNode 调度，仅模型输出和外部 HTTP 替换为本地桩。
        from langchain.agents import create_agent
        from langchain_core.language_models.fake_chat_models import FakeMessagesListChatModel
        from langchain_core.messages import AIMessage

        class LocalModel(FakeMessagesListChatModel):
            def bind_tools(self, tool_list, **kwargs):
                return self

        graph = create_agent(model=LocalModel(responses=[
            AIMessage(content="", tool_calls=[{"name": "create_ticket", "id": "call-1",
                      "args": {"category": "配送", "content": "少送菜"}}]),
            AIMessage(content="模型仍然说已经创建"),
        ]), tools=[tools.create_ticket], context_schema=tools.UserContext)
        with patch.object(tools.httpx, "post", side_effect=httpx.ReadTimeout("lost response")):
            body = self.request(graph.invoke)
        self.assertEqual(body["outcome"], "unknown")
        self.assertEqual(body["toolCalls"], ["create_ticket"])
        self.assertIn("无法确认", body["reply"])


if __name__ == "__main__":
    unittest.main()
