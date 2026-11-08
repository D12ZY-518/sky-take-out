"""Deterministic tool-boundary checks; no model, Java, MySQL, or network calls."""

import unittest
from types import SimpleNamespace
from unittest.mock import Mock, patch

import httpx
import tools
from run_eval import install_mock_fixture, load_mock_fixture


def runtime_for(user_id: int) -> SimpleNamespace:
    return SimpleNamespace(context=tools.UserContext(user_id=user_id,
                                                     request_id="test-request-001"))


class ToolContractTest(unittest.TestCase):
    def test_evaluation_fixture_has_positive_and_negative_identity_paths(self):
        fixture = load_mock_fixture()
        with (patch.object(tools.c, "MOCK_JAVA", True),
              patch.object(tools, "_MOCK_ORDERS", []),
              patch.object(tools, "_MOCK_DETAILS", {})):
            install_mock_fixture(tools, fixture)
            user_1 = tools.query_orders.func(limit=20, runtime=runtime_for(1))
            user_2 = tools.query_orders.func(limit=20, runtime=runtime_for(2))
            self.assertIn("eval_u1_pending", user_1)
            self.assertNotIn("eval_u2_done", user_1)
            self.assertIn("eval_u2_done", user_2)
            self.assertNotIn("1788231207410", user_2)

    def test_java_request_uses_injected_identity(self):
        response = Mock()
        response.json.return_value = {"code": 1, "data": [{
            "number": "eval_u2_done", "status": 5, "amount": "31.50",
            "orderTime": "2026-09-02 12:00:00"}]}
        with (patch.object(tools.c, "MOCK_JAVA", False),
              patch.object(tools.httpx, "get", return_value=response) as get):
            answer = tools.query_orders.func(status=5, limit=2, runtime=runtime_for(2))
        self.assertEqual(get.call_args.kwargs["params"],
                         {"userId": 2, "limit": 2, "status": 5})
        self.assertIs(get.call_args.kwargs["trust_env"], False)
        self.assertIn("eval_u2_done", answer)

    def test_order_detail_bypasses_system_proxy(self):
        response = Mock(status_code=404)
        with (patch.object(tools.c, "MOCK_JAVA", False),
              patch.object(tools.httpx, "get", return_value=response) as get):
            answer = tools.query_order_detail.func("missing", runtime=runtime_for(1))
        self.assertIn("没有这张订单", answer)
        self.assertIs(get.call_args.kwargs["trust_env"], False)

    def test_ticket_timeout_does_not_claim_creation(self):
        with (patch.object(tools.c, "MOCK_JAVA", False),
              patch.object(tools.httpx, "post", side_effect=httpx.ReadTimeout("timeout")) as post):
            answer = tools.create_ticket.func("配送", "一直没到", runtime=runtime_for(1))
        self.assertIn("结果暂时无法确认", answer)
        self.assertNotIn("已为你创建", answer)
        self.assertIs(post.call_args.kwargs["trust_env"], False)
        self.assertEqual(post.call_args.kwargs["json"]["requestId"], "test-request-001")

    def test_ticket_business_failure_does_not_claim_creation(self):
        response = Mock()
        response.json.return_value = {"code": 0, "msg": "未找到可关联的订单，请核对订单号。"}
        runtime = runtime_for(1)
        with (patch.object(tools.c, "MOCK_JAVA", False),
              patch.object(tools.httpx, "post", return_value=response)):
            answer = tools.create_ticket.func("配送", "一直没到", "other-order", runtime=runtime)
        self.assertEqual(answer, "未找到可关联的订单，请核对订单号。")
        self.assertIsNone(runtime.context.ticket_no)
        self.assertFalse(runtime.context.write_result_unknown)

    def test_ticket_retry_keeps_same_runtime_request_id(self):
        response = Mock()
        response.json.return_value = {"code": 1, "data": {"ticketNo": "TKoriginal"}}
        runtime = runtime_for(1)
        with (patch.object(tools.c, "MOCK_JAVA", False),
              patch.object(tools.httpx, "post",
                           side_effect=[httpx.ReadTimeout("lost response"), response]) as post):
            first = tools.create_ticket.func("配送", "一直没到", runtime=runtime)
            second = tools.create_ticket.func("配送", "一直没到", runtime=runtime)
        self.assertIn("结果暂时无法确认", first)
        self.assertIn("TKoriginal", second)
        self.assertEqual(post.call_args_list[0].kwargs["json"]["requestId"],
                         post.call_args_list[1].kwargs["json"]["requestId"])

    def test_ticket_without_request_id_does_not_call_java(self):
        runtime = SimpleNamespace(context=tools.UserContext(user_id=1))
        with (patch.object(tools.c, "MOCK_JAVA", False),
              patch.object(tools.httpx, "post") as post):
            answer = tools.create_ticket.func("配送", "一直没到", runtime=runtime)
        self.assertIn("缺少工单请求标识", answer)
        post.assert_not_called()


if __name__ == "__main__":
    unittest.main()
