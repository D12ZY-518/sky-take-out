"""预检器只读契约：不发 POST，错误状态不能被误写成全链路通过。"""

import unittest

import httpx

from real_preflight import probe


class RealPreflightTest(unittest.TestCase):
    def test_happy_path_only_uses_get(self):
        requests = []

        def respond(request):
            requests.append(request)
            if request.url.path == "/health":
                return httpx.Response(200, json={"status": "ok"})
            return httpx.Response(200, json={"code": 1, "data": [{"number": "demo"}]})

        with httpx.Client(transport=httpx.MockTransport(respond)) as client:
            results = probe(client, "http://python", "http://java", 1, "test-token")
        self.assertTrue(all(ok for _, ok, _ in results))
        self.assertEqual([r.method for r in requests], ["GET", "GET"])
        self.assertEqual(requests[1].url.params["userId"], "1")
        self.assertEqual(requests[1].headers["X-Internal-Token"], "test-token")

    def test_unauthorized_java_is_not_success(self):
        def respond(request):
            if request.url.path == "/health":
                return httpx.Response(200, json={"status": "ok"})
            return httpx.Response(401, json={"code": 0})

        with httpx.Client(transport=httpx.MockTransport(respond)) as client:
            results = probe(client, "http://python", "http://java", 1, "wrong-token")
        self.assertEqual(results[0][1], True)
        self.assertEqual(results[1], ("Java /internal/orders", False, "HTTP 401"))

    def test_missing_token_does_not_call_java(self):
        requests = []

        def respond(request):
            requests.append(request)
            return httpx.Response(200, json={"status": "ok"})

        with httpx.Client(transport=httpx.MockTransport(respond)) as client:
            results = probe(client, "http://python", "http://java", 1, "")
        self.assertEqual(len(requests), 1)
        self.assertFalse(results[1][1])

    def test_http_200_business_failure_is_not_success(self):
        def respond(request):
            if request.url.path == "/health":
                return httpx.Response(200, json={"status": "ok"})
            return httpx.Response(200, json={"code": 0, "msg": "数据库不可用"})

        with httpx.Client(transport=httpx.MockTransport(respond)) as client:
            results = probe(client, "http://python", "http://java", 1, "test-token")
        self.assertFalse(results[1][1])
        self.assertIn("code=0", results[1][2])

    def test_malformed_response_is_reported(self):
        def respond(request):
            if request.url.path == "/health":
                return httpx.Response(200, json={"status": "ok"})
            return httpx.Response(200, json=["unexpected"])

        with httpx.Client(transport=httpx.MockTransport(respond)) as client:
            results = probe(client, "http://python", "http://java", 1, "test-token")
        self.assertFalse(results[1][1])
        self.assertIn("响应格式异常", results[1][2])


if __name__ == "__main__":
    unittest.main()
