"""真实 Java/MySQL 联调的只读预检；不调用模型，也不创建工单。"""

from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

import httpx
from dotenv import dotenv_values


def read_internal_token() -> str:
    """优先使用当前进程环境变量，回退到本机未入库的 .env。"""
    token = os.environ.get("INTERNAL_TOKEN")
    if token:
        return token
    env_path = Path(__file__).resolve().parents[1] / ".env"
    if env_path.exists():
        return dotenv_values(env_path).get("INTERNAL_TOKEN") or ""
    return ""


def probe(
    client: httpx.Client,
    python_url: str,
    java_url: str,
    user_id: int,
    token: str,
) -> list[tuple[str, bool, str]]:
    """依次检查 Python 存活与 Java 内部查单，保留可归因的状态。"""
    outcomes: list[tuple[str, bool, str]] = []
    try:
        response = client.get(f"{python_url.rstrip('/')}/health", timeout=5)
        if response.status_code != 200:
            outcomes.append(("Python /health", False, f"HTTP {response.status_code}"))
        else:
            body = response.json()
            ok = isinstance(body, dict) and body.get("status") == "ok"
            detail = "HTTP 200"
            if not isinstance(body, dict):
                detail += "，响应格式异常"
            outcomes.append(("Python /health", ok, detail))
    except (httpx.RequestError, ValueError) as exc:
        outcomes.append(("Python /health", False, type(exc).__name__))

    if not token:
        outcomes.append(("Java /internal/orders", False, "未提供 INTERNAL_TOKEN"))
        return outcomes

    try:
        response = client.get(
            f"{java_url.rstrip('/')}/internal/orders",
            params={"userId": user_id, "limit": 20},
            headers={"X-Internal-Token": token},
            timeout=5,
        )
        if response.status_code != 200:
            outcomes.append(("Java /internal/orders", False, f"HTTP {response.status_code}"))
        else:
            body = response.json()
            if not isinstance(body, dict):
                outcomes.append(("Java /internal/orders", False, "HTTP 200，响应格式异常"))
                return outcomes
            rows = body.get("data")
            ok = body.get("code") == 1 and isinstance(rows, list)
            detail = f"业务 code={body.get('code')}"
            if ok:
                detail += f"，用户 {user_id} 订单数={len(rows)}"
                if not rows:
                    detail += "；此身份不能直接用于正向订单验收"
            outcomes.append(("Java /internal/orders", ok, detail))
    except (httpx.RequestError, ValueError) as exc:
        outcomes.append(("Java /internal/orders", False, type(exc).__name__))
    return outcomes


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--python-url", default="http://127.0.0.1:8000")
    parser.add_argument("--java-url", default="http://127.0.0.1:8080")
    parser.add_argument("--user-id", type=int, default=1)
    args = parser.parse_args()
    if args.user_id <= 0:
        parser.error("--user-id 必须为正整数")

    token = read_internal_token()
    # 本机预检不走系统 HTTP 代理；否则服务未启动时可能收到代理的 502，误判故障层。
    with httpx.Client(trust_env=False) as client:
        results = probe(client, args.python_url, args.java_url, args.user_id, token)
    for name, ok, detail in results:
        print(f"[{'OK' if ok else 'FAIL'}] {name}: {detail}")
    print("Scope: read-only connectivity; MOCK_JAVA, model key, and chat E2E not checked.")
    return 0 if all(ok for _, ok, _ in results) else 1


if __name__ == "__main__":
    sys.exit(main())
