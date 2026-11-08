"""Validate or run the versioned Agent evaluation set.

Validation uses only the Python standard library. Live mode calls the configured
DeepSeek model and real FAQ retrieval, but forces MOCK_JAVA=1 before imports.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import statistics
import subprocess
import sys
import time
import uuid
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
DATASET = HERE / "cases.json"
FIXTURE = HERE / "mock_fixture.json"
ALLOWED_TOOLS = {"query_orders", "query_order_detail", "create_ticket", "query_faq"}


def load_cases() -> list[dict]:
    data = json.loads(DATASET.read_text(encoding="utf-8"))
    if data.get("version") != 1 or not isinstance(data.get("cases"), list):
        raise ValueError("cases.json must contain version=1 and a cases array")
    seen = set()
    for case in data["cases"]:
        case_id = case.get("id")
        if not isinstance(case_id, str) or not case_id or case_id in seen:
            raise ValueError(f"duplicate or invalid case id: {case_id!r}")
        seen.add(case_id)
        if not isinstance(case.get("group"), str) or not case["group"]:
            raise ValueError(f"{case_id}: group is required")
        if type(case.get("user_id")) is not int or case["user_id"] <= 0:
            raise ValueError(f"{case_id}: user_id must be a positive integer")
        turns = case.get("turns")
        if not isinstance(turns, list) or not turns:
            raise ValueError(f"{case_id}: at least one turn is required")
        for index, turn in enumerate(turns, 1):
            label = f"{case_id} turn {index}"
            if not isinstance(turn.get("message"), str) or not turn["message"].strip():
                raise ValueError(f"{label}: message is required")
            expected = turn.get("tools")
            if not isinstance(expected, list) or any(t not in ALLOWED_TOOLS for t in expected):
                raise ValueError(f"{label}: tools must list registered tool names")
            if "tool_options" in turn and (not isinstance(turn["tool_options"], list)
                                           or not turn["tool_options"]
                                           or any(not isinstance(option, list)
                                                  or any(t not in ALLOWED_TOOLS for t in option)
                                                  for option in turn["tool_options"])):
                raise ValueError(f"{label}: tool_options must be valid tool sequences")
            if turn.get("tool_policy", "exact") not in ("exact", "contains_in_order"):
                raise ValueError(f"{label}: unsupported tool_policy")
            if not isinstance(turn.get("review"), bool):
                raise ValueError(f"{label}: review must be true or false")
            for key in ("reply_any", "reply_all", "reply_none"):
                if key in turn and (not isinstance(turn[key], list)
                                    or any(not isinstance(x, str) or not x for x in turn[key])):
                    raise ValueError(f"{label}: {key} must be a list of nonempty strings")
            if "max_calls" in turn and (not isinstance(turn["max_calls"], dict)
                                         or any(tool not in ALLOWED_TOOLS
                                                or type(count) is not int or count < 0
                                                for tool, count in turn["max_calls"].items())):
                raise ValueError(f"{label}: max_calls must map tools to nonnegative integers")
            if "ticket_no_from_tool" in turn and not isinstance(turn["ticket_no_from_tool"], bool):
                raise ValueError(f"{label}: ticket_no_from_tool must be boolean")
            if not isinstance(turn.get("args", []), list):
                raise ValueError(f"{label}: args must be a list")
            for expected_call in turn.get("args", []):
                if (not isinstance(expected_call, dict)
                        or expected_call.get("name") not in ALLOWED_TOOLS
                        or not isinstance(expected_call.get("contains"), dict)):
                    raise ValueError(f"{label}: invalid args expectation")
    return data["cases"]


def load_mock_fixture() -> dict:
    fixture = json.loads(FIXTURE.read_text(encoding="utf-8"))
    orders = fixture.get("orders")
    details = fixture.get("details")
    if fixture.get("version") != 1 or not isinstance(orders, list) or not isinstance(details, dict):
        raise ValueError("mock_fixture.json must contain version=1, orders, and details")
    numbers = set()
    for order in orders:
        if (not isinstance(order, dict) or not isinstance(order.get("number"), str)
                or not order["number"] or order["number"] in numbers
                or type(order.get("user_id")) is not int or order["user_id"] <= 0
                or type(order.get("status")) is not int or order["status"] not in range(1, 7)
                or not isinstance(order.get("amount"), str)
                or not isinstance(order.get("order_time"), str)):
            raise ValueError(f"invalid or duplicate mock order: {order!r}")
        numbers.add(order["number"])
    if any(number not in numbers or not isinstance(rows, list)
           for number, rows in details.items()):
        raise ValueError("mock details must refer to orders and contain lists")
    return fixture


def install_mock_fixture(tools_module: object, fixture: dict) -> None:
    """Replace only in-process demo data; the evaluator never writes MySQL."""
    tools_module._MOCK_ORDERS[:] = fixture["orders"]
    tools_module._MOCK_DETAILS.clear()
    tools_module._MOCK_DETAILS.update(fixture["details"])


def reply_text(content: object) -> str:
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        return "".join(part.get("text", "") if isinstance(part, dict) else str(part)
                       for part in content)
    return str(content)


def observe_turn(messages: list) -> dict:
    """Use only messages after the latest HumanMessage, not the whole checkpoint."""
    last_human = max((i for i, message in enumerate(messages)
                      if getattr(message, "type", None) == "human"), default=-1)
    current = messages[last_human + 1:] if last_human >= 0 else messages
    tool_results = [{"name": message.name, "content": reply_text(message.content)}
                    for message in current if getattr(message, "type", None) == "tool"]
    actual_tools = [item["name"] for item in tool_results]
    calls = []
    for message in current:
        if getattr(message, "type", None) == "ai":
            for call in getattr(message, "tool_calls", []) or []:
                calls.append({"name": call.get("name"), "args": call.get("args", {})})
    return {"tools": actual_tools, "calls": calls, "tool_results": tool_results,
            "reply": reply_text(messages[-1].content) if messages else ""}


def score_turn(expected: dict, observed: dict) -> dict:
    alternatives = expected.get("tool_options", [expected["tools"]])
    if expected.get("tool_policy") == "contains_in_order":
        tool_ok = any(_is_subsequence(option, observed["tools"]) for option in alternatives)
    else:
        tool_ok = observed["tools"] in alternatives
    args_ok = all(any(call["name"] == wanted["name"]
                      and isinstance(call["args"], dict)
                      and all(call["args"].get(k) == v for k, v in wanted["contains"].items())
                      for call in observed["calls"])
                  for wanted in expected.get("args", []))
    args_checked = bool(expected.get("args"))
    any_words = expected.get("reply_any", [])
    all_words = expected.get("reply_all", [])
    forbidden = expected.get("reply_none", [])
    reply_ok = (not any_words or any(word in observed["reply"] for word in any_words))
    reply_ok = reply_ok and all(word in observed["reply"] for word in all_words)
    reply_ok = reply_ok and not any(word in observed["reply"] for word in forbidden)
    reply_checked = bool(any_words or all_words or forbidden)
    counts = Counter(observed["tools"])
    call_count_ok = all(counts[name] <= maximum
                        for name, maximum in expected.get("max_calls", {}).items())
    ticket_no_ok = True
    if expected.get("ticket_no_from_tool"):
        ticket_results = [item["content"] for item in observed.get("tool_results", [])
                          if item["name"] == "create_ticket"]
        numbers = [match.group() for content in ticket_results
                   for match in re.finditer(r"TK\d+", content)]
        ticket_no_ok = len(numbers) == 1 and numbers[0] in observed["reply"]
    automated_pass = tool_ok and args_ok and reply_ok and call_count_ok and ticket_no_ok
    status = "fail" if not automated_pass else ("needs_review" if expected["review"] else "pass")
    return {"status": status, "tool_ok": tool_ok, "args_ok": args_ok,
            "args_checked": args_checked, "reply_ok": reply_ok,
            "reply_checked": reply_checked, "call_count_ok": call_count_ok,
            "ticket_no_ok": ticket_no_ok, "automated_pass": automated_pass}


def _is_subsequence(wanted: list[str], actual: list[str]) -> bool:
    """Allow supporting reads but keep the required tool order."""
    iterator = iter(actual)
    return all(any(name == item for item in iterator) for name in wanted)


def run_live(cases: list[dict], limit: int) -> int:
    # This assignment must precede the config/tools/agent imports.
    os.environ["MOCK_JAVA"] = "1"
    sys.path.insert(0, str(HERE.parent))
    import config as config_module
    if not config_module.DEEPSEEK_API_KEY:
        print("DEEPSEEK_API_KEY is missing; no model calls were made.", file=sys.stderr)
        return 2
    import agent as agent_module
    import tools as tools_module
    from tools import UserContext

    install_mock_fixture(tools_module, load_mock_fixture())
    graph = agent_module._get_agent()
    run_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:6]
    commit = subprocess.run(["git", "rev-parse", "HEAD"], cwd=HERE.parent.parent,
                            capture_output=True, text=True, check=False)
    worktree = subprocess.run(["git", "status", "--porcelain"], cwd=HERE.parent.parent,
                              capture_output=True, text=True, check=False)
    report = {"dataset": "sky-service-agent-mock-v3", "run_id": run_id,
              "mode": "live-model-mock-java", "model": config_module.DEEPSEEK_MODEL,
              "model_timeout_seconds": config_module.MODEL_TIMEOUT_SECONDS,
              "model_max_retries": config_module.MODEL_MAX_RETRIES,
              "agent_recursion_limit": config_module.AGENT_RECURSION_LIMIT,
              "memory_max_turns": config_module.AGENT_MEMORY_MAX_TURNS,
              "memory_max_sessions": config_module.AGENT_MEMORY_MAX_SESSIONS,
              "memory_ttl_seconds": config_module.AGENT_MEMORY_TTL_SECONDS,
              "dataset_sha256": hashlib.sha256(DATASET.read_bytes()).hexdigest(),
              "fixture_sha256": hashlib.sha256(FIXTURE.read_bytes()).hexdigest(),
              "git_head": commit.stdout.strip() if commit.returncode == 0 else None,
              "working_tree_dirty": bool(worktree.stdout.strip()) if worktree.returncode == 0 else None,
              "created_at": datetime.now(timezone.utc).isoformat(), "cases": []}
    for case in cases[:limit]:
        thread_id = f"eval:{run_id}:{case['id']}"
        config = {"configurable": {"thread_id": thread_id},
                  "recursion_limit": config_module.AGENT_RECURSION_LIMIT}
        case_result = {"id": case["id"], "group": case["group"], "user_id": case["user_id"],
                       "turns": []}
        for index, turn in enumerate(case["turns"], 1):
            started = time.perf_counter()
            try:
                # 同生产 run 一样应用会话容量和压缩；保留本轮原始轨迹用于评分。
                store = graph.checkpointer
                with store.session(thread_id):
                    try:
                        result = graph.invoke(
                            {"messages": [{"role": "user", "content": turn["message"]}]},
                            context=UserContext(user_id=case["user_id"],
                                                request_id=f"eval-{run_id}-{case['id']}-{index}"),
                            config=config,
                        )
                    finally:
                        store.compact(thread_id)
                observed = observe_turn(result["messages"])
                scored = score_turn(turn, observed)
                error = None
            except Exception as exc:  # Keep one bad run from hiding the rest of the dataset.
                observed = {"tools": [], "calls": [], "tool_results": [], "reply": ""}
                scored = {"status": "error", "tool_ok": False, "args_ok": False,
                          "args_checked": bool(turn.get("args")), "reply_ok": False,
                          "reply_checked": bool(turn.get("reply_any") or turn.get("reply_all")
                                                or turn.get("reply_none")),
                          "call_count_ok": False, "ticket_no_ok": False,
                          "automated_pass": False}
                error = f"{type(exc).__name__}: {str(exc)[:240]}".replace(
                    config_module.DEEPSEEK_API_KEY, "[REDACTED]")
            elapsed_ms = round((time.perf_counter() - started) * 1000)
            item = {"turn": index, "prompt": turn["message"], "expected_tools": turn["tools"],
                    "expected_args": turn.get("args", []),
                    "expected_max_calls": turn.get("max_calls", {}),
                    "ticket_no_from_tool": turn.get("ticket_no_from_tool", False),
                    "observed": observed,
                    "score": scored, "latency_ms": elapsed_ms, "error": error}
            case_result["turns"].append(item)
            print(f"{case['id']}#{index}: {scored['status']}  "
                  f"tools={observed['tools']}  {elapsed_ms}ms", flush=True)
            if error:
                # A failed turn leaves uncertain checkpoint state; do not continue that case.
                break
        report["cases"].append(case_result)

    turns = [turn for case in report["cases"] for turn in case["turns"]]
    counts = Counter(turn["score"]["status"] for turn in turns)
    latencies = [turn["latency_ms"] for turn in turns]
    report["summary"] = {
        "cases_run": len(report["cases"]), "turns_run": len(turns),
        "pass": counts["pass"], "needs_review": counts["needs_review"],
        "fail": counts["fail"], "error": counts["error"],
        "tool_selection_correct": sum(t["score"]["tool_ok"] for t in turns),
        "parameter_checks_run": sum(t["score"]["args_checked"] for t in turns),
        "parameter_checks_correct": sum(t["score"]["args_checked"]
                                        and t["score"]["args_ok"] for t in turns),
        "reply_checks_run": sum(t["score"]["reply_checked"] for t in turns),
        "reply_checks_correct": sum(t["score"]["reply_checked"]
                                    and t["score"]["reply_ok"] for t in turns),
        "call_count_correct": sum(t["score"]["call_count_ok"] for t in turns
                                  if t["expected_max_calls"]),
        "call_count_checks_run": sum(bool(t["expected_max_calls"]) for t in turns),
        "ticket_no_checks_run": sum(t["ticket_no_from_tool"] for t in turns),
        "ticket_no_checks_correct": sum(t["ticket_no_from_tool"]
                                        and t["score"]["ticket_no_ok"] for t in turns),
        "mean_latency_ms": round(statistics.mean(latencies)) if latencies else None,
        "max_latency_ms": max(latencies) if latencies else None,
    }
    runs = HERE / "runs"
    runs.mkdir(exist_ok=True)
    report_path = runs / f"{run_id}.json"
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"Report: {report_path}")
    print(json.dumps(report["summary"], ensure_ascii=False, indent=2))
    return 1 if counts["fail"] or counts["error"] else 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    action = parser.add_mutually_exclusive_group(required=True)
    action.add_argument("--validate", action="store_true", help="validate dataset, no model calls")
    action.add_argument("--live", action="store_true", help="call the configured model with MOCK_JAVA=1")
    selection = parser.add_mutually_exclusive_group()
    selection.add_argument("--group", help="run only one dataset group")
    selection.add_argument("--case-id", help="run one exact case, useful for Bad Case regression")
    parser.add_argument("--limit", type=int, help="maximum cases to run; required with --live")
    args = parser.parse_args()
    try:
        cases = load_cases()
        fixture = load_mock_fixture()
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print(f"Dataset invalid: {exc}", file=sys.stderr)
        return 2
    groups = Counter(case["group"] for case in cases)
    if args.validate:
        print(f"Dataset valid: {len(cases)} cases, {sum(len(c['turns']) for c in cases)} turns; "
              f"mock fixture: {len(fixture['orders'])} orders")
        print(json.dumps(groups, ensure_ascii=False, indent=2))
        return 0
    if args.limit is None or args.limit <= 0:
        parser.error("--live requires an explicit positive --limit to control model calls")
    if args.group:
        cases = [case for case in cases if case["group"] == args.group]
        if not cases:
            parser.error(f"unknown or empty group: {args.group}")
    if args.case_id:
        cases = [case for case in cases if case["id"] == args.case_id]
        if not cases:
            parser.error(f"unknown case id: {args.case_id}")
    return run_live(cases, min(args.limit, len(cases)))


if __name__ == "__main__":
    raise SystemExit(main())
