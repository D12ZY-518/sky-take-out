"""Request retries and trustworthy tool outcomes; real dispatch, local fake replies."""

import unittest
from concurrent.futures import ThreadPoolExecutor
from threading import Event
from unittest.mock import Mock, patch

import httpx
from fastapi.testclient import TestClient
from langchain_core.language_models.fake_chat_models import FakeMessagesListChatModel
from langchain_core.messages import AIMessage
from langgraph.checkpoint.memory import InMemorySaver

with patch("dotenv.load_dotenv", return_value=False):
    import agent
    import main
    import tools
    from request_state import RequestCapacityError, RequestConflict, RequestStateCache


class LocalToolChatModel(FakeMessagesListChatModel):
    def bind_tools(self, tool_list, **kwargs):
        return self


def tool_call(name, arguments, call_id):
    return AIMessage(content="", tool_calls=[{
        "name": name, "args": arguments, "id": call_id,
    }])


def response(body, status_code=200):
    result = Mock(status_code=status_code)
    result.json.return_value = body
    return result


def request_replies(replies, *, message="转人工登记少送菜", count=1):
    graph = agent.create_agent(
        model=LocalToolChatModel(responses=replies), tools=agent._TOOLS,
        checkpointer=InMemorySaver(), context_schema=tools.UserContext,
    )
    with (patch.object(main.c, "DEEPSEEK_API_KEY", "offline-test-key"),
          patch.object(main.c, "INTERNAL_TOKEN", "offline-test-token"),
          patch.object(tools.c, "MOCK_JAVA", False),
          patch.object(main, "_request_states", RequestStateCache(600, 100)),
          patch.object(agent, "_get_agent", return_value=graph)):
        client = TestClient(main.app)
        bodies = []
        for _ in range(count):
            result = client.post(
                "/agent/chat", headers={"X-Internal-Token": "offline-test-token"},
                json={"userId": 1, "sessionId": "followup-proof",
                      "message": message, "requestId": "same-send-0001"},
            )
            if result.status_code != 200:
                raise AssertionError(f"unexpected HTTP status: {result.status_code}")
            bodies.append(result.json())
    return bodies


class RetryFollowupTest(unittest.TestCase):
    def test_same_request_cannot_clear_unknown_without_a_receipt(self):
        # The write was sent; losing its response makes the first outcome unknown.
        # The next HTTP request creates a fresh UserContext and can lose that fact.
        with patch.object(tools.httpx, "post", side_effect=httpx.ReadTimeout("lost receipt")) as post:
            first, retried = request_replies([
                tool_call("create_ticket", {"category": "配送", "content": "少送菜"}, "first-write"),
                AIMessage(content="结果尚未确认"),
                AIMessage(content="已为你登记，等待客服联系即可。"),
            ], count=2)
        self.assertEqual(first["outcome"], "unknown")
        self.assertEqual(post.call_count, 1)
        self.assertEqual(retried["toolCalls"], [])
        self.assertEqual(retried["outcome"], "unknown")

    def test_same_message_retry_blocks_rewording_and_recovers_with_original_payload(self):
        stored_payload = None
        sent_payloads = []

        def fake_java(*args, json, **kwargs):
            nonlocal stored_payload
            sent_payloads.append(json.copy())
            if stored_payload is None:
                stored_payload = json.copy()
                raise httpx.ReadTimeout("database committed; response lost")
            if json != stored_payload:
                return response({"code": 0, "msg": "requestId 已用于另一条工单诉求",
                                 "data": {"outcome": "unknown"}})
            return response({"code": 1, "data": {"ticketNo": "TKpersisted"}})

        with patch.object(tools.httpx, "post", side_effect=fake_java):
            first, changed, retried = request_replies([
                tool_call("create_ticket", {"category": "配送", "content": "少送菜"}, "original"),
                AIMessage(content="结果尚未确认"),
                tool_call("create_ticket", {"category": "配送", "content": "用户反映缺少菜品"}, "reworded"),
                AIMessage(content="无法确认原工单"),
                tool_call("create_ticket", {"category": "配送", "content": "少送菜"}, "retry-original"),
                AIMessage(content="已登记"),
            ], count=3)
        self.assertEqual(first["outcome"], "unknown")
        self.assertEqual(len(sent_payloads), 2)
        self.assertEqual(sent_payloads[0]["requestId"], sent_payloads[1]["requestId"])
        self.assertEqual(sent_payloads[0], sent_payloads[1])
        self.assertEqual(changed["outcome"], "unknown")
        self.assertIn("TKpersisted", retried["reply"])
        self.assertEqual(retried["outcome"], "completed")

    def test_confirmed_ticket_rejection_cannot_be_described_as_success(self):
        with patch.object(tools.httpx, "post", return_value=response({
            "code": 0, "msg": "未找到可关联的订单，请核对订单号。",
        })) as post:
            body, = request_replies([
                tool_call("create_ticket", {
                    "category": "配送", "content": "少送菜", "order_number": "other-order",
                }, "rejected-ticket"),
                AIMessage(content="已为你创建人工工单 TKfabricated，客服会尽快联系你。"),
            ])
        self.assertEqual(post.call_count, 1)
        self.assertEqual(body["toolCalls"], ["create_ticket"])
        # completed only means this turn ended; the unsupported receipt is the bug.
        self.assertNotIn("TKfabricated", body["reply"])

    def test_denied_order_lookup_cannot_return_fabricated_order_details(self):
        with patch.object(tools.httpx, "get", return_value=response({}, status_code=403)) as get:
            body, = request_replies([
                tool_call("query_order_detail", {"order_number": "other-order"}, "denied-order"),
                AIMessage(content="订单 other-order 已送达，金额 ¥99.99，包含两份牛肉饭。"),
            ], message="帮我查 other-order 的订单明细")
        self.assertEqual(get.call_args.kwargs["params"], {"userId": 1})
        self.assertEqual(body["toolCalls"], ["query_order_detail"])
        self.assertNotIn("99.99", body["reply"])

    def test_schema_rejected_ticket_arguments_cannot_fabricate_a_receipt(self):
        with patch.object(tools.httpx, "post") as post:
            body, = request_replies([
                tool_call("create_ticket", {"category": "配送", "content": ""}, "invalid-ticket"),
                AIMessage(content="已创建 TKfabricated 工单"),
            ])
        post.assert_not_called()
        self.assertEqual(body["toolCalls"], ["create_ticket"])
        self.assertNotIn("TKfabricated", body["reply"])

    def test_ticket_receipt_has_priority_over_a_later_denied_query(self):
        with (patch.object(tools.httpx, "post", return_value=response({
                  "code": 1, "data": {"ticketNo": "TKconfirmed"}})),
              patch.object(tools.httpx, "get", return_value=response({}, status_code=403))):
            body, = request_replies([
                tool_call("create_ticket", {"category": "配送", "content": "少送菜"}, "created"),
                tool_call("query_order_detail", {"order_number": "other-order"}, "denied-after-write"),
                AIMessage(content="订单金额 ¥99.99"),
            ])
        self.assertIn("TKconfirmed", body["reply"])
        self.assertNotIn("99.99", body["reply"])
        self.assertEqual(body["outcome"], "completed")

    def test_normal_order_query_does_not_claim_a_ticket(self):
        with patch.object(tools.httpx, "get", return_value=response({
            "code": 1, "data": [{"number": "own-order", "status": 5,
                                    "amount": "10.00", "orderTime": "2026-09-01 10:00:00"}],
        })):
            body, = request_replies([
                tool_call("query_orders", {}, "own-orders"),
                AIMessage(content="你的订单 own-order 已完成，金额 ¥10.00。"),
            ], message="查我的订单")
        self.assertEqual(body["reply"], "你的订单 own-order 已完成，金额 ¥10.00。")
        self.assertEqual(body["toolCalls"], ["query_orders"])


class RequestStateCacheTest(unittest.TestCase):
    def test_requests_without_reusable_id_do_not_consume_retry_cache(self):
        request = main.ChatReq(userId=1, sessionId="uncached", message="你好")
        cache = RequestStateCache(600, 1)
        with (patch.object(main.c, "DEEPSEEK_API_KEY", "offline-key"),
              patch.object(main.c, "INTERNAL_TOKEN", "offline-token"),
              patch.object(main, "_request_states", cache),
              patch.object(main.agent_module, "run", return_value=("回复", [])) as run):
            self.assertEqual(main.chat(request, "offline-token").outcome, "completed")
            self.assertEqual(main.chat(request, "offline-token").outcome, "completed")
        self.assertEqual(run.call_count, 2)
        self.assertNotEqual(run.call_args_list[0].kwargs["context"].request_id,
                            run.call_args_list[1].kwargs["context"].request_id)
        with cache.lease(1, "slot-still-free", "session", "hello"):
            pass

    def test_completed_response_is_replayed_without_model_or_history_updates(self):
        request = main.ChatReq(userId=1, sessionId="cached", message="你好", requestId="cache-send-001")
        with (patch.object(main.c, "DEEPSEEK_API_KEY", "offline-key"),
              patch.object(main.c, "INTERNAL_TOKEN", "offline-token"),
              patch.object(main, "_request_states", RequestStateCache(600, 10)),
              patch.object(main.agent_module, "run", return_value=("原回复", [])) as run):
            first = main.chat(request, "offline-token")
            duplicate = main.chat(request, "offline-token")
        self.assertEqual(first, duplicate)
        self.assertEqual(run.call_count, 1)

    def test_read_only_failure_can_recover_on_same_id_without_ticket_receipt(self):
        request = main.ChatReq(userId=1, sessionId="read-only", message="查订单", requestId="read-send-001")
        with (patch.object(main.c, "DEEPSEEK_API_KEY", "offline-key"),
              patch.object(main.c, "INTERNAL_TOKEN", "offline-token"),
              patch.object(main, "_request_states", RequestStateCache(600, 10)),
              patch.object(main.traceback, "print_exc"),
              patch.object(main.agent_module, "run", side_effect=[
                  RuntimeError("model unavailable before any write"),
                  ("你的订单已完成。", ["query_orders"]),
              ]) as run):
            first = main.chat(request, "offline-token")
            retried = main.chat(request, "offline-token")
        self.assertEqual(first.outcome, "unknown")
        self.assertEqual(retried.outcome, "completed")
        self.assertEqual(retried.reply, "你的订单已完成。")
        self.assertEqual(run.call_count, 2)

    def test_changed_input_cannot_replace_unknown_original_state(self):
        request = main.ChatReq(userId=1, sessionId="original", message="转人工", requestId="cache-send-001")
        contexts = []

        def invoke(*args, context, **kwargs):
            contexts.append(context)
            context.write_result_unknown = True
            return "模型声称已登记", ["create_ticket"]

        with (patch.object(main.c, "DEEPSEEK_API_KEY", "offline-key"),
              patch.object(main.c, "INTERNAL_TOKEN", "offline-token"),
              patch.object(main, "_request_states", RequestStateCache(600, 10)),
              patch.object(main.agent_module, "run", side_effect=invoke) as run):
            self.assertEqual(main.chat(request, "offline-token").outcome, "unknown")
            for changed in (request.model_copy(update={"message": "修改诉求"}),
                            request.model_copy(update={"sessionId": "different"})):
                self.assertIn("已绑定", main.chat(changed, "offline-token").reply)
            self.assertEqual(run.call_count, 1)
            self.assertEqual(main.chat(request, "offline-token").outcome, "unknown")
        self.assertIs(contexts[0], contexts[1])

    def test_different_users_do_not_share_request_state(self):
        cache = RequestStateCache(600, 10)
        with cache.lease(1, "same-id-001", "same", "hello") as first:
            first.context.write_result_unknown = True
        with cache.lease(2, "same-id-001", "same", "hello") as second:
            self.assertFalse(second.context.write_result_unknown)
            self.assertIsNot(first.context, second.context)

    def test_concurrent_duplicates_run_once(self):
        request = main.ChatReq(userId=1, sessionId="cached", message="你好", requestId="cache-send-001")
        entered, release = Event(), Event()

        def invoke(*args, **kwargs):
            entered.set()
            if not release.wait(3):
                raise AssertionError("test did not release pending request")
            return "原回复", []

        with (patch.object(main.c, "DEEPSEEK_API_KEY", "offline-key"),
              patch.object(main.c, "INTERNAL_TOKEN", "offline-token"),
              patch.object(main, "_request_states", RequestStateCache(600, 10)),
              patch.object(main.agent_module, "run", side_effect=invoke) as run,
              ThreadPoolExecutor(max_workers=2) as executor):
            first = executor.submit(main.chat, request, "offline-token")
            try:
                self.assertTrue(entered.wait(3))
                duplicate = executor.submit(main.chat, request, "offline-token")
            finally:
                release.set()
            self.assertEqual(first.result(timeout=3), duplicate.result(timeout=3))
        self.assertEqual(run.call_count, 1)

    def test_capacity_preserves_unexpired_unknown_state(self):
        cache = RequestStateCache(600, 1)
        with cache.lease(1, "first-id-001", "same", "hello") as original:
            original.context.write_result_unknown = True
        with self.assertRaises(RequestCapacityError):
            with cache.lease(1, "second-id-001", "same", "hello"):
                self.fail("capacity limit was bypassed")
        with cache.lease(1, "first-id-001", "same", "hello") as retried:
            self.assertIs(original, retried)
            self.assertTrue(retried.context.write_result_unknown)

    def test_active_request_is_never_expired_and_idle_expired_request_is_removed(self):
        now = [0.0]
        cache = RequestStateCache(10, 1, clock=lambda: now[0])
        with cache.lease(1, "first-id-001", "same", "hello") as original:
            now[0] = 20
            with self.assertRaises(RequestCapacityError):
                with cache.lease(1, "second-id-001", "same", "hello"):
                    self.fail("active request was evicted")
        now[0] = 31
        with cache.lease(1, "second-id-001", "same", "hello") as replacement:
            self.assertIsNot(original, replacement)

    def test_conflict_does_not_overwrite_original_input(self):
        cache = RequestStateCache(600, 2)
        with cache.lease(1, "first-id-001", "original", "hello") as original:
            pass
        with self.assertRaises(RequestConflict):
            with cache.lease(1, "first-id-001", "changed", "hello"):
                self.fail("request input was changed")
        with cache.lease(1, "first-id-001", "original", "hello") as retried:
            self.assertIs(original, retried)


if __name__ == "__main__":
    unittest.main()
