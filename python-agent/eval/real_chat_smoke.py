"""Opt-in, model-billed smoke test through the Java user chat entry.

Run only against a disposable demo environment: the Agent still owns its tool
choices, so a read-only prompt is not a hard write-prevention boundary.
"""

import argparse
import json
import sys
import uuid
from urllib.error import HTTPError, URLError
from urllib.request import Request, ProxyHandler, build_opener


DIRECT_HTTP = build_opener(ProxyHandler({}))


def chat(base_url: str, session_id: str, message: str, timeout: int) -> dict:
    body = json.dumps({"sessionId": session_id, "message": message}).encode("utf-8")
    request = Request(
        f"{base_url.rstrip('/')}/user/service/chat-python",
        data=body,
        headers={"Content-Type": "application/json; charset=utf-8"},
        method="POST",
    )
    with DIRECT_HTTP.open(request, timeout=timeout) as response:
        if response.status != 200:
            raise AssertionError(f"Java HTTP status {response.status}")
        result = json.load(response)
    if result.get("code") != 1 or not isinstance(result.get("data"), dict):
        raise AssertionError(f"Java business result is not successful: {result.get('code')}")
    data = result["data"]
    if not isinstance(data.get("reply"), str) or not isinstance(data.get("toolCalls"), list):
        raise AssertionError("Unexpected chat response contract")
    if "create_ticket" in data["toolCalls"]:
        raise AssertionError("Unexpected write tool in intended read-only smoke test")
    return data


def run(base_url: str, expected_order: str, expected_dish: str, timeout: int) -> None:
    session = f"read-smoke-{uuid.uuid4().hex}"
    first = chat(base_url, session, "请查询我最近的订单号和状态，不要创建工单。", timeout)
    if "query_orders" not in first["toolCalls"] or expected_order not in first["reply"]:
        raise AssertionError(f"First turn missing expected order/tool; tools={first['toolCalls']}")
    second = chat(base_url, session, "请查看刚才最新那单的菜品明细，不要创建工单。", timeout)
    if "query_order_detail" not in second["toolCalls"]:
        raise AssertionError(f"Second turn did not query details; tools={second['toolCalls']}")
    if "query_orders" in second["toolCalls"]:
        raise AssertionError("Second turn toolCalls contains a historical query_orders")
    if expected_order not in second["reply"] or expected_dish not in second["reply"]:
        raise AssertionError("Second turn did not mention the expected order and dish")
    print(json.dumps({"result": "pass", "turns": 2, "firstTools": first["toolCalls"],
                      "secondTools": second["toolCalls"]}, ensure_ascii=False))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--expected-order", required=True)
    parser.add_argument("--expected-dish", required=True)
    parser.add_argument("--timeout", type=int, default=90)
    args = parser.parse_args()
    try:
        run(args.base_url, args.expected_order, args.expected_dish, args.timeout)
    except (AssertionError, HTTPError, URLError, OSError, ValueError) as exc:
        print(f"FAIL: {type(exc).__name__}: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
