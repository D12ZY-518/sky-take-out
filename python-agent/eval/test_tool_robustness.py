"""Offline regressions for invalid model arguments and repeated ticket calls."""

import unittest
from concurrent.futures import ThreadPoolExecutor
from threading import Event
from types import SimpleNamespace
from unittest.mock import Mock, patch

import httpx

with patch("dotenv.load_dotenv", return_value=False):
    import tools


def runtime_for(user_id=1):
    return SimpleNamespace(context=tools.UserContext(user_id, "robust-test-001"))


def response(body):
    result = Mock()
    result.json.return_value = body
    return result


class ToolArgumentsTest(unittest.TestCase):
    def test_mock_orders_are_sorted_before_limit(self):
        older = {"number": "older", "user_id": 1, "status": 5,
                 "amount": "1.00", "order_time": "2026-08-01 10:00:00"}
        newer = {**older, "number": "newer", "order_time": "2026-09-01 10:00:00"}
        with (patch.object(tools.c, "MOCK_JAVA", True),
              patch.object(tools, "_MOCK_ORDERS", [older, newer])):
            answer = tools.query_orders.func(limit=1, runtime=runtime_for())
        self.assertIn("newer", answer)
        self.assertNotIn("older", answer)

    def test_invalid_order_filters_are_rejected_before_mock_or_http(self):
        for mock_java in (True, False):
            for args in ({"limit": -1}, {"limit": 0}, {"limit": 21},
                         {"limit": True}, {"limit": "2"}, {"status": 7},
                         {"status": False}, {"status": "5"}):
                with self.subTest(mock=mock_java, args=args):
                    with (patch.object(tools.c, "MOCK_JAVA", mock_java),
                          patch.object(tools.httpx, "get") as get):
                        answer = tools.query_orders.func(runtime=runtime_for(), **args)
                    self.assertIn("参数", answer)
                    get.assert_not_called()

    def test_invalid_order_number_cannot_change_url_path_or_query(self):
        for number in ("../tickets", "id?userId=2", "id#fragment", "id/sub", "", "x" * 51):
            with self.subTest(number=number):
                with (patch.object(tools.c, "MOCK_JAVA", False),
                      patch.object(tools.httpx, "get") as get):
                    answer = tools.query_order_detail.func(number, runtime=runtime_for())
                self.assertIn("订单号", answer)
                get.assert_not_called()

    def test_ticket_arguments_are_validated_before_any_write(self):
        for kwargs in ({"category": " "}, {"content": ""}, {"category": "x" * 33},
                       {"content": "x" * 501}, {"order_number": "../other"}):
            args = {"category": "配送", "content": "少送菜", **kwargs}
            with self.subTest(args=args):
                runtime = runtime_for()
                with (patch.object(tools.c, "MOCK_JAVA", False),
                      patch.object(tools.httpx, "post") as post):
                    answer = tools.create_ticket.func(runtime=runtime, **args)
                self.assertNotIn("已为你创建", answer)
                post.assert_not_called()
                self.assertFalse(runtime.context.write_result_unknown)

    def test_malformed_request_id_is_rejected_before_mock_or_http(self):
        for mock_java in (True, False):
            for request_id in ("short", "x" * 65, "bad/path-id", "has space id"):
                with self.subTest(mock=mock_java, request_id=request_id):
                    runtime = runtime_for()
                    runtime.context.request_id = request_id
                    with (patch.object(tools.c, "MOCK_JAVA", mock_java),
                          patch.object(tools.httpx, "post") as post):
                        answer = tools.create_ticket.func("配送", "少送菜", runtime=runtime)
                    self.assertIn("请求标识", answer)
                    post.assert_not_called()

    def test_tools_fail_closed_without_injected_identity(self):
        with (patch.object(tools.c, "MOCK_JAVA", False),
              patch.object(tools.httpx, "get") as get,
              patch.object(tools.httpx, "post") as post):
            for runtime in (None, runtime_for(0), runtime_for(True)):
                for function, args in ((tools.query_orders.func, ()),
                                       (tools.query_order_detail.func, ("test123",)),
                                       (tools.create_ticket.func, ("配送", "少送菜"))):
                    with self.subTest(runtime=runtime, function=function):
                        self.assertIn("身份", function(*args, runtime=runtime))
            get.assert_not_called()
            post.assert_not_called()

    def test_empty_faq_question_does_not_load_retriever(self):
        with patch.object(tools.faq_rag, "search") as search:
            self.assertIn("问题", tools.query_faq.func("  "))
            search.assert_not_called()

    def test_malformed_faq_result_is_a_tool_error_not_an_exception(self):
        with patch.object(tools.faq_rag, "search", return_value=[{"question": "退款"}]):
            self.assertIn("暂时不可用", tools.query_faq.func("退款规则"))


class TicketRepeatTest(unittest.TestCase):
    def test_malformed_write_response_keeps_outcome_unknown(self):
        for body in ({}, [], {"code": True}, {"code": "0"},
                     {"code": 1, "data": {"ticketNo": " "}}):
            with self.subTest(body=body):
                runtime = runtime_for()
                with (patch.object(tools.c, "MOCK_JAVA", False),
                      patch.object(tools.httpx, "post", return_value=response(body))):
                    answer = tools.create_ticket.func("配送", "原问题", runtime=runtime)
                self.assertTrue(runtime.context.write_result_unknown)
                self.assertIsNone(runtime.context.ticket_no)
                self.assertNotIn("已为你创建", answer)

    def test_mock_repeat_reuses_receipt_and_checks_order_ownership(self):
        runtime = runtime_for()
        with patch.object(tools.c, "MOCK_JAVA", True):
            first = tools.create_ticket.func("配送", "少送菜", runtime=runtime)
            second = tools.create_ticket.func("配送", "少送菜", runtime=runtime)
            other = tools.create_ticket.func("退款", "另一个问题", runtime=runtime)
            invalid = tools.create_ticket.func("配送", "少送菜", "test123", runtime=runtime_for(2))
        self.assertEqual(first, second)
        self.assertNotIn("已为你创建", other)
        self.assertIn("未找到可关联的订单", invalid)

    def test_success_is_reused_without_a_second_post(self):
        runtime = runtime_for()
        with (patch.object(tools.c, "MOCK_JAVA", False),
              patch.object(tools.httpx, "post", return_value=response(
                  {"code": 1, "data": {"ticketNo": "TKoriginal"}})) as post):
            first = tools.create_ticket.func("配送", "少送菜", runtime=runtime)
            second = tools.create_ticket.func("配送", "少送菜", runtime=runtime)
        self.assertEqual(first, second)
        self.assertEqual(post.call_count, 1)

    def test_timeout_payload_cannot_be_changed_and_unknown_stays_set(self):
        runtime = runtime_for()
        with (patch.object(tools.c, "MOCK_JAVA", False),
              patch.object(tools.httpx, "post", side_effect=[
                  httpx.ReadTimeout("lost receipt"),
                  response({"code": 0, "msg": "本次明确拒绝"})]) as post):
            tools.create_ticket.func("配送", "原问题", runtime=runtime)
            changed = tools.create_ticket.func("配送", "模型重写后的问题", runtime=runtime)
        self.assertIn("同一请求", changed)
        self.assertEqual(post.call_count, 1)
        self.assertTrue(runtime.context.write_result_unknown)

    def test_success_then_changed_payload_preserves_confirmed_receipt(self):
        runtime = runtime_for()
        with (patch.object(tools.c, "MOCK_JAVA", False),
              patch.object(tools.httpx, "post", return_value=response(
                  {"code": 1, "data": {"ticketNo": "TKoriginal"}})) as post):
            tools.create_ticket.func("配送", "原问题", runtime=runtime)
            changed = tools.create_ticket.func("退款", "改写后的问题", runtime=runtime)
        self.assertIn("同一请求", changed)
        self.assertEqual(post.call_count, 1)
        self.assertEqual(runtime.context.ticket_no, "TKoriginal")
        self.assertFalse(runtime.context.write_result_unknown)

    def test_timeout_then_same_payload_receipt_resolves_unknown(self):
        runtime = runtime_for()
        with (patch.object(tools.c, "MOCK_JAVA", False),
              patch.object(tools.httpx, "post", side_effect=[
                  httpx.ReadTimeout("lost receipt"),
                  response({"code": 1, "data": {"ticketNo": "TKrestored"}})]) as post):
            tools.create_ticket.func("配送", "原问题", runtime=runtime)
            answer = tools.create_ticket.func("配送", "原问题", runtime=runtime)
        self.assertIn("TKrestored", answer)
        self.assertFalse(runtime.context.write_result_unknown)
        self.assertEqual(post.call_args_list[0].kwargs["json"],
                         post.call_args_list[1].kwargs["json"])

    def test_unknown_is_not_cleared_by_a_later_business_rejection(self):
        runtime = runtime_for()
        with (patch.object(tools.c, "MOCK_JAVA", False),
              patch.object(tools.httpx, "post", side_effect=[
                  httpx.ReadTimeout("lost receipt"),
                  response({"code": 0, "msg": "本次拒绝"})])):
            tools.create_ticket.func("配送", "原问题", runtime=runtime)
            tools.create_ticket.func("配送", "原问题", runtime=runtime)
        self.assertTrue(runtime.context.write_result_unknown)

    def test_concurrent_identical_calls_send_one_post_and_share_receipt(self):
        runtime = runtime_for()
        entered, release = Event(), Event()

        def slow_post(*args, **kwargs):
            entered.set()
            if not release.wait(3):
                raise AssertionError("test did not release pending write")
            return response({"code": 1, "data": {"ticketNo": "TKshared"}})

        with (patch.object(tools.c, "MOCK_JAVA", False),
              patch.object(tools.httpx, "post", side_effect=slow_post) as post,
              ThreadPoolExecutor(max_workers=2) as executor):
            first = executor.submit(tools.create_ticket.func, "配送", "原问题", runtime=runtime)
            try:
                self.assertTrue(entered.wait(3))
                second = executor.submit(tools.create_ticket.func, "配送", "原问题", runtime=runtime)
            finally:
                release.set()
            self.assertEqual(first.result(timeout=3), second.result(timeout=3))
        self.assertEqual(post.call_count, 1)
        self.assertFalse(runtime.context.write_result_unknown)

    def test_concurrent_changed_payload_preserves_first_write_outcome(self):
        for timeout in (False, True):
            with self.subTest(timeout=timeout):
                runtime = runtime_for()
                entered, release = Event(), Event()

                def slow_post(*args, **kwargs):
                    entered.set()
                    if not release.wait(3):
                        raise AssertionError("test did not release pending write")
                    if timeout:
                        raise httpx.ReadTimeout("lost receipt")
                    return response({"code": 1, "data": {"ticketNo": "TKfirst"}})

                with (patch.object(tools.c, "MOCK_JAVA", False),
                      patch.object(tools.httpx, "post", side_effect=slow_post) as post,
                      ThreadPoolExecutor(max_workers=2) as executor):
                    first = executor.submit(tools.create_ticket.func, "配送", "原问题", runtime=runtime)
                    try:
                        self.assertTrue(entered.wait(3))
                        second = executor.submit(tools.create_ticket.func, "退款", "新问题", runtime=runtime)
                    finally:
                        release.set()
                    first.result(timeout=3)
                    self.assertIn("同一请求", second.result(timeout=3))
                self.assertEqual(post.call_count, 1)
                self.assertEqual(runtime.context.write_result_unknown, timeout)
                self.assertEqual(runtime.context.ticket_no, None if timeout else "TKfirst")


if __name__ == "__main__":
    unittest.main()
