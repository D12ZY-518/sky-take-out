"""No-network checks that the served FAQ differs from unverified legacy demo text."""

import json
import unittest
from pathlib import Path
from unittest.mock import patch

import config
import tools


HERE = Path(__file__).resolve().parents[1]


class FaqGroundingTest(unittest.TestCase):
    def test_default_index_uses_reviewed_faq_file(self):
        self.assertEqual(config.FAQ_DATA_PATH, "faq_grounded.json")
        old = json.loads((HERE / "faq_data.json").read_text(encoding="utf-8"))
        served = json.loads((HERE / "faq_grounded.json").read_text(encoding="utf-8"))
        self.assertEqual(len(old), 30)
        self.assertEqual(len(served), 13)
        self.assertEqual(len({item["id"] for item in served}), len(served))
        self.assertTrue(all(item.get("answer") and item.get("question") for item in served))
        self.assertNotIn("支持微信支付、支付宝、银行卡、 Apple Pay",
                         " ".join(item["answer"] for item in served))
        # 索引更新行为由 test_faq_rag 的假 Chroma 集成测试验证，
        # 不依赖源码中 collection 名称的具体拼写方式。

    def test_faq_failure_does_not_invite_guessing_policy_numbers(self):
        with patch.object(tools.faq_rag, "search", side_effect=RuntimeError("unavailable")):
            answer = tools.query_faq.func("退款多久到账")
        self.assertIn("无法核实规则", answer)
        self.assertIn("请勿猜测", answer)


if __name__ == "__main__":
    unittest.main()
