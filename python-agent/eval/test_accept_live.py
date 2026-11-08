"""真实验收入口的确定性测试；仅 MockTransport，不访问模型或数据库。"""

import contextlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import httpx

import accept_live


class DemoBackend:
    def __init__(self, *, legacy=False, lose_response=False, invalid_business=False, foreign_empty=False):
        self.legacy = legacy
        self.lose_response = lose_response
        self.invalid_business = invalid_business
        self.foreign_empty = foreign_empty
        self.requests = []
        self.cache = {}
        self.tickets = []
        self.messages = {}

    def envelope(self, data):
        return httpx.Response(200, json={"code": 1, "data": data})

    def __call__(self, request):
        self.requests.append(request)
        path = request.url.path
        if path == "/health":
            return httpx.Response(200, json={"status": "ok"})
        if path == "/internal/orders":
            user = request.url.params["userId"]
            rows = ([{"number": "own-order", "status": 4, "amount": "22.00"}] if user == "1"
                    else [{"number": "foreign-order", "status": 5, "amount": "31.50"}]
                    if user == "4" and not self.foreign_empty else [])
            return self.envelope(rows)
        if path == "/internal/orders/own-order":
            return self.envelope({"order": {"number": "own-order", "amount": "22.00"},
                                  "details": [{"name": "宫保鸡丁", "amount": "22.00", "number": 1}]})
        if path == "/user/service/ticket-result":
            if self.legacy:
                return httpx.Response(404, json={"error": "not found"})
            request_id = request.url.params["requestId"]
            row = next((row for row in self.tickets if row["requestId"] == request_id), None)
            return self.envelope({"reply": "原工单号 " + row["ticketNo"] if row else "暂未查到，结果未知",
                                  "toolCalls": [], "outcome": "completed" if row else "unknown"})
        if path == "/admin/tickets":
            return self.envelope({"records": self.tickets, "total": len(self.tickets)})
        if path not in ("/user/service/chat-python", "/agent/chat"):
            raise AssertionError("unexpected endpoint " + path)
        payload = json.loads(request.content)
        request_id = payload["requestId"]
        cache_key = (payload.get("userId", 1), request_id)
        if cache_key in self.cache:
            if self.messages[cache_key] != payload["message"]:
                data = {"reply": "该请求 ID 已绑定原消息，不能修改诉求", "toolCalls": [], "outcome": "unknown"}
            else:
                data = self.cache[cache_key]
        else:
            if request_id.endswith("-orders"):
                data = {"reply": "订单 own-order，派送中", "toolCalls": ["query_orders"]}
            elif request_id.endswith("-details"):
                data = {"reply": "订单 own-order，宫保鸡丁，总额22.00", "toolCalls": ["query_order_detail"]}
            elif "-faq-" in request_id:
                data = {"reply": "当前没有可核实规则，不能承诺", "toolCalls": ["query_faq"]}
            elif request_id.endswith("-foreign"):
                data = {"reply": "无法查看该订单，请核对订单号", "toolCalls": ["query_order_detail"]}
            elif request_id.endswith("-identity"):
                data = {"reply": "当前没有任何订单", "toolCalls": ["query_orders"]}
            elif request_id.endswith("-ticket"):
                marker = request_id.removesuffix("-ticket")
                ticket = {"ticketNo": "TKacceptance123456", "requestId": request_id, "userId": 1,
                          "content": marker + " 验收测试"}
                self.tickets.append(ticket)
                data = {"reply": "工单已登记，工单号 TKacceptance123456", "toolCalls": ["create_ticket"]}
            else:
                raise AssertionError("unexpected scenario request ID")
            if not self.legacy:
                data["outcome"] = "completed"
            self.cache[cache_key] = data
            self.messages[cache_key] = payload["message"]
            if request_id.endswith("-ticket") and self.lose_response:
                self.lose_response = False
                raise httpx.ReadTimeout("simulated lost response", request=request)
        if self.invalid_business:
            return httpx.Response(200, json={"code": 0, "msg": "business failed"})
        return httpx.Response(200, json=data) if path == "/agent/chat" else self.envelope(data)


class AcceptLiveTest(unittest.TestCase):
    def args(self, *extra):
        return accept_live.parse_args(["--live", "--limit", "12", "--confirm-write", "--identity-via-python",
                                       "--result-wait", "0", *extra])

    def credentials(self, admin=True):
        return accept_live.Credentials("internal-secret-value", "primary-user-secret",
                                       "secondary-user-secret", "admin-secret-value" if admin else "")

    def execute(self, backend, args=None, credentials=None):
        with httpx.Client(transport=httpx.MockTransport(backend)) as client:
            runner = accept_live.Acceptance(args or self.args(), credentials or self.credentials(),
                                           client, run_id="offline123456")
            return runner.run()

    def test_live_requires_both_explicit_budget_and_write_confirmation(self):
        for argv in (["--live"], ["--live", "--limit", "2"],
                     ["--live", "--confirm-write"], ["--live", "--limit", "0", "--confirm-write"]):
            with self.subTest(argv=argv), contextlib.redirect_stderr(io.StringIO()):
                with self.assertRaises(SystemExit):
                    accept_live.parse_args(argv)

    def test_default_plan_does_not_submit_any_http(self):
        backend = DemoBackend()
        report = self.execute(backend, accept_live.parse_args([]))
        self.assertEqual(backend.requests, [])
        self.assertEqual(report["summary"]["automated"], "not_run")
        self.assertEqual(report["budget"]["used"], 0)
        self.assertTrue(all(ids["requestId"].startswith(report["marker"] + "-")
                            for ids in report["ids"].values()))

    def test_complete_run_checks_real_contracts_and_keeps_manual_review_pending(self):
        backend = DemoBackend()
        report = self.execute(backend)
        self.assertEqual(report["summary"], {"automated": "pass", "manual": "pending", "overall": "requires_review"})
        self.assertEqual(report["budget"]["used"], 12)
        self.assertEqual(len(backend.tickets), 1)
        self.assertIn(report["marker"], backend.tickets[0]["content"])
        self.assertTrue(all("elapsed_seconds" in entry for entry in report["steps"]))
        self.assertTrue(any(item["name"] == "faq-payment" for item in report["manual_review"]))
        chats = [json.loads(request.content) for request in backend.requests if request.method == "POST"]
        self.assertTrue(all(row["requestId"].startswith(report["marker"] + "-") for row in chats))
        self.assertTrue(all(len(row["requestId"]) <= 64 and len(row["sessionId"]) <= 128 for row in chats))
        orders = [row for row in chats if row["requestId"].endswith("-orders")]
        self.assertEqual(orders[0], orders[1])
        identities = [row for row in chats if row["requestId"].endswith("-identity")]
        self.assertEqual(identities[0]["userId"], 2)
        self.assertEqual(identities[0]["sessionId"], orders[0]["sessionId"])

    def test_budget_prevents_unfinished_write_scenario(self):
        backend = DemoBackend()
        report = self.execute(backend, self.args("--limit", "5"))
        self.assertEqual(report["budget"]["used"], 5)
        self.assertEqual(len([request for request in backend.requests if request.method == "POST"]), 5)
        self.assertEqual(backend.tickets, [])
        self.assertEqual(report["summary"]["automated"], "incomplete")

    def test_legacy_missing_outcome_and_receipt_are_incompatible_not_success(self):
        backend = DemoBackend(legacy=True)
        report = self.execute(backend)
        self.assertEqual(backend.tickets, [])
        self.assertEqual(report["summary"]["automated"], "incomplete")
        self.assertIn("java:outcome", {item["name"] for item in report["compatibility"]})
        self.assertIn("java:ticket-result", {item["name"] for item in report["compatibility"]})

    def test_lost_first_write_response_reuses_exact_request_and_receipt(self):
        backend = DemoBackend(lose_response=True)
        report = self.execute(backend, self.args("--simulate-response-loss"))
        self.assertEqual(report["summary"]["automated"], "pass")
        self.assertEqual(len(backend.tickets), 1)
        first = next(step for step in report["steps"] if step["name"] == "ticket")
        retry = next(step for step in report["steps"] if step["name"] == "ticket-retry")
        self.assertEqual(first["request"], retry["request"])
        self.assertTrue(first["fault_injection"]["observed"])
        self.assertEqual(first["error"], "ReadTimeout")
        self.assertEqual(report["confirmed_ticket"]["ticketNo"], "TKacceptance123456")

    def test_business_failure_cannot_pass_even_with_http_200(self):
        report = self.execute(DemoBackend(invalid_business=True))
        self.assertEqual(report["summary"]["automated"], "fail")

    def test_no_admin_credential_leaves_row_count_and_marker_unverified(self):
        backend = DemoBackend()
        report = self.execute(backend, credentials=self.credentials(admin=False))
        self.assertEqual(report["summary"]["automated"], "pass")
        self.assertTrue(report["confirmed_ticket"])
        self.assertEqual(report["external_verification"]["expected_ticket"], report["confirmed_ticket"])
        self.assertIn("external_verification_needed", {check["status"] for check in report["checks"]})
        self.assertFalse(any(request.url.path == "/admin/tickets" for request in backend.requests))

    def test_missing_foreign_fixture_uses_real_primary_order_with_second_identity(self):
        backend = DemoBackend(foreign_empty=True)
        report = self.execute(backend)
        self.assertEqual(report["summary"]["automated"], "pass")
        step = next(step for step in report["steps"] if step["name"] == "foreign")
        self.assertTrue(step["url"].endswith("/agent/chat"))
        self.assertEqual(step["request"]["userId"], 2)
        self.assertIn("own-order", step["request"]["message"])
        self.assertEqual(step["request"]["sessionId"], report["ids"]["orders"]["sessionId"])

    def test_report_redacts_credentials_even_when_echoed_by_server(self):
        credentials = self.credentials()

        def handler(request):
            if request.url.path == "/health":
                return httpx.Response(200, json={"status": "ok", "token": credentials.internal,
                                                 "echo": "secret " + credentials.admin})
            return DemoBackend()(request)

        report = self.execute(handler, credentials=credentials)
        encoded = json.dumps(report)
        self.assertTrue(all(secret not in encoded for secret in credentials.values()))
        self.assertIn("[REDACTED]", encoded)

    def test_plan_main_writes_json_without_loading_dotenv(self):
        with tempfile.TemporaryDirectory() as output, patch.dict("os.environ", {}, clear=True), \
                contextlib.redirect_stdout(io.StringIO()) as stdout:
            self.assertEqual(accept_live.main(["--output-dir", output]), 0)
            files = list(Path(output).glob("acceptance-test-*.json"))
            self.assertEqual(len(files), 1)
            report = json.loads(files[0].read_text(encoding="utf-8"))
            self.assertEqual(report["steps"], [])
            self.assertEqual(json.loads(stdout.getvalue())["report"], str(files[0].resolve()))

    def test_rejects_credential_bearing_base_url(self):
        with contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit):
                accept_live.parse_args(["--java-url", "http://user:secret@localhost:8080"])


if __name__ == "__main__":
    unittest.main()
