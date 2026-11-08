"""HTTP 请求在调用模型前校验；所有请求均使用本地测试客户端。"""
import unittest
from unittest.mock import patch

from fastapi.testclient import TestClient

import main


class ChatValidationTest(unittest.TestCase):
    def setUp(self):
        cache = patch.object(main, "_request_states", main.RequestStateCache(600, 10))
        cache.start()
        self.addCleanup(cache.stop)
        self.client = TestClient(main.app, raise_server_exceptions=False)
        self.headers = {"X-Internal-Token": "validation-token"}
        self.payload = {"userId": 1, "sessionId": "test", "message": "查订单",
                        "requestId": "send-0001"}

    def test_invalid_inputs_never_reach_agent(self):
        invalid = [
            {"userId": 0}, {"userId": -1}, {"userId": True}, {"userId": "1"},
            {"message": "   "}, {"message": "字" * 2001},
            {"sessionId": " "}, {"sessionId": "s" * 129},
            {"requestId": "short"}, {"requestId": "bad request id"},
            {"requestId": "r" * 65},
        ]
        with (patch.object(main.c, "INTERNAL_TOKEN", "validation-token"),
              patch.object(main.c, "DEEPSEEK_API_KEY", "test-key"),
              patch.object(main.agent_module, "run", return_value=("回复", [])) as run):
            for changes in invalid:
                with self.subTest(changes=changes):
                    response = self.client.post("/agent/chat", headers=self.headers,
                                                json=self.payload | changes)
                    self.assertEqual(response.status_code, 422)
            run.assert_not_called()

    def test_non_ascii_token_is_rejected_without_server_error(self):
        with patch.object(main.c, "INTERNAL_TOKEN", "validation-token"):
            response = self.client.post("/agent/chat", json=self.payload,
                                        headers={"X-Internal-Token": b"caf\xe9"})
        self.assertEqual(response.status_code, 401)

    def test_valid_request_passes_raw_session_id_and_preserves_request_id(self):
        with (patch.object(main.c, "INTERNAL_TOKEN", "validation-token"),
              patch.object(main.c, "DEEPSEEK_API_KEY", "test-key"),
              patch.object(main.agent_module, "run", return_value=("回复", [])) as run):
            response = self.client.post("/agent/chat", headers=self.headers, json=self.payload)
        self.assertEqual(response.status_code, 200)
        self.assertEqual(run.call_args.args, (1, "test", "查订单"))
        self.assertEqual(run.call_args.kwargs["context"].request_id, "send-0001")

    def test_unconfigured_model_keeps_outcome_unknown_for_retry(self):
        with (patch.object(main.c, "INTERNAL_TOKEN", "validation-token"),
              patch.object(main.c, "DEEPSEEK_API_KEY", ""),
              patch.object(main.agent_module, "run") as run):
            response = self.client.post("/agent/chat", headers=self.headers, json=self.payload)
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["outcome"], "unknown")
        run.assert_not_called()


if __name__ == "__main__":
    unittest.main()
