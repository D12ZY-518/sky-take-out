"""Small deterministic checks for the evaluator itself; no model calls."""

import unittest
from types import SimpleNamespace

from run_eval import load_cases, load_mock_fixture, observe_turn, score_turn


class EvalRunnerTest(unittest.TestCase):
    def test_dataset_has_expected_coverage(self):
        cases = load_cases()
        self.assertGreaterEqual(len(cases), 30)
        self.assertLessEqual(len(cases), 50)
        self.assertEqual(len({case["id"] for case in cases}), len(cases))
        self.assertTrue({"routing", "parameters", "identity", "memory", "faq", "ticket"}
                        <= {case["group"] for case in cases})
        fixture = load_mock_fixture()
        self.assertGreaterEqual(len({o["user_id"] for o in fixture["orders"]}), 2)
        self.assertGreaterEqual(len({o["amount"] for o in fixture["orders"]}), 3)
        self.assertTrue({1, 4, 5, 6} <= {o["status"] for o in fixture["orders"]})

    def test_history_tool_is_not_counted_in_current_turn(self):
        messages = [
            SimpleNamespace(type="human", content="first"),
            SimpleNamespace(type="tool", name="query_orders", content="first result"),
            SimpleNamespace(type="human", content="second"),
            SimpleNamespace(type="ai", tool_calls=[{"name": "query_faq", "args": {"question": "退款"}}]),
            SimpleNamespace(type="tool", name="query_faq", content="FAQ result"),
            SimpleNamespace(type="ai", tool_calls=[], content="FAQ answer"),
        ]
        observed = observe_turn(messages)
        self.assertEqual(observed["tools"], ["query_faq"])
        self.assertEqual(observed["calls"][0]["args"]["question"], "退款")
        self.assertEqual(observed["tool_results"][0]["name"], "query_faq")

    def test_parameter_and_reply_checks_are_independent(self):
        expected = {"tools": ["query_orders"], "args": [
            {"name": "query_orders", "contains": {"status": 5}}],
            "reply_none": ["已经退款"], "review": False}
        observed = {"tools": ["query_orders"], "calls": [
            {"name": "query_orders", "args": {"status": 6}}], "reply": "已经退款"}
        scored = score_turn(expected, observed)
        self.assertTrue(scored["tool_ok"])
        self.assertFalse(scored["args_ok"])
        self.assertFalse(scored["reply_ok"])
        self.assertEqual(scored["status"], "fail")

    def test_valid_alternative_and_supporting_reads_require_review(self):
        memory = {"tools": [], "tool_options": [[], ["query_order_detail"]],
                  "reply_any": ["10.00"], "review": True}
        self.assertEqual(score_turn(memory, {"tools": [], "calls": [],
                                             "reply": "金额 10.00 元"})["status"],
                         "needs_review")
        ticket = {"tools": ["create_ticket"], "tool_policy": "contains_in_order",
                  "review": True}
        observed = {"tools": ["query_orders", "create_ticket"], "calls": [],
                    "reply": "已登记"}
        self.assertEqual(score_turn(ticket, observed)["status"], "needs_review")
        observed["tools"] = ["query_orders"]
        self.assertEqual(score_turn(ticket, observed)["status"], "fail")

    def test_unchecked_dimensions_are_identified(self):
        scored = score_turn({"tools": [], "review": False},
                            {"tools": [], "calls": [], "reply": ""})
        self.assertTrue(scored["args_ok"])
        self.assertFalse(scored["args_checked"])
        self.assertFalse(scored["reply_checked"])

    def test_all_required_words_and_duplicate_ticket_guard(self):
        expected = {"tools": ["create_ticket"], "tool_policy": "contains_in_order",
                    "max_calls": {"create_ticket": 1}, "ticket_no_from_tool": True,
                    "reply_all": ["已创建", "TK123"], "review": True}
        observed = {"tools": ["query_orders", "create_ticket"], "calls": [],
                    "tool_results": [{"name": "create_ticket",
                                      "content": "已为你创建人工工单 TK123"}],
                    "reply": "已创建 TK123"}
        self.assertEqual(score_turn(expected, observed)["status"], "needs_review")
        observed["tools"] = ["create_ticket", "create_ticket"]
        self.assertFalse(score_turn(expected, observed)["call_count_ok"])
        observed["tools"] = ["create_ticket"]
        observed["reply"] = "已创建 TK999"
        self.assertFalse(score_turn(expected, observed)["ticket_no_ok"])


if __name__ == "__main__":
    unittest.main()
