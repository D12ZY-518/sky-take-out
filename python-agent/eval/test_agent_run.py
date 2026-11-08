"""Agent 返回契约的确定性回归：不调用模型或外部服务。"""

import unittest
from types import SimpleNamespace
from unittest.mock import Mock, patch

import agent


def message(kind: str, content: str = "", name: str | None = None):
    return SimpleNamespace(type=kind, content=content, name=name)


class AgentRunTest(unittest.TestCase):
    def test_only_current_turn_tools_are_reported(self):
        messages = [
            message("human", "查订单"),
            message("tool", name="query_orders"),
            message("ai", "第一轮答复"),
            message("human", "刚才那单有什么菜"),
            message("tool", name="query_order_detail"),
            message("ai", "第二轮答复"),
        ]
        fake = Mock()
        fake.invoke.return_value = {"messages": messages}
        with patch.object(agent, "_get_agent", return_value=fake):
            reply, calls = agent.run(1, "test", "刚才那单有什么菜")

        self.assertEqual(reply, "第二轮答复")
        self.assertEqual(calls, ["query_order_detail"])
        self.assertEqual(fake.invoke.call_args.kwargs["context"].user_id, 1)
        self.assertTrue(fake.invoke.call_args.kwargs["context"].request_id)
        self.assertEqual(fake.invoke.call_args.kwargs["config"]["configurable"]["thread_id"],
                         "user-1:test")

    def test_no_new_tool_does_not_report_history(self):
        messages = [
            message("human", "查订单"),
            message("tool", name="query_orders"),
            message("ai", "第一轮答复"),
            message("human", "谢谢"),
            message("ai", "不客气"),
        ]
        fake = Mock()
        fake.invoke.return_value = {"messages": messages}
        with patch.object(agent, "_get_agent", return_value=fake):
            reply, calls = agent.run(1, "test", "谢谢")
        self.assertEqual((reply, calls), ("不客气", []))


if __name__ == "__main__":
    unittest.main()
