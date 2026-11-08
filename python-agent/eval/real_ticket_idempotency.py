"""Opt-in Java/MySQL integration check: two identical POSTs must return one ticket.

Creates one real ticket in the target demo database. Run only on an isolated
demo database; inspect and remove the exact test row afterwards if desired.
"""

import argparse
import json
import uuid

import httpx

import config as c


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--confirm-write", action="store_true")
    parser.add_argument("--user-id", type=int, default=1)
    args = parser.parse_args()
    if not args.confirm_write:
        parser.error("This creates a real demo ticket; pass --confirm-write deliberately")
    request_id = "it-" + uuid.uuid4().hex
    payload = {"userId": args.user_id, "requestId": request_id,
               "category": "其他", "content": "idempotency integration test",
               "orderNumber": None}
    endpoint = f"{c.JAVA_BASE_URL}/internal/tickets"
    options = {"headers": {"X-Internal-Token": c.INTERNAL_TOKEN},
               "timeout": 10, "trust_env": False}
    first = httpx.post(endpoint, json=payload, **options)
    first.raise_for_status()
    second = httpx.post(endpoint, json=payload, **options)
    second.raise_for_status()
    changed = httpx.post(endpoint, json={**payload, "content": "different request"}, **options)
    changed.raise_for_status()
    a, b, conflict = first.json(), second.json(), changed.json()
    if a.get("code") != 1 or b.get("code") != 1:
        raise AssertionError("First insert or same-payload retry failed")
    ticket = a["data"]["ticketNo"]
    if b["data"]["ticketNo"] != ticket:
        raise AssertionError("Retry returned a different ticket number")
    if conflict.get("code") != 0:
        raise AssertionError("Same key with changed payload did not conflict")
    print(json.dumps({"result": "pass", "requestId": request_id,
                      "ticketNo": ticket, "sameTicketOnRetry": True,
                      "changedPayloadRejected": True}, ensure_ascii=False))


if __name__ == "__main__":
    main()
