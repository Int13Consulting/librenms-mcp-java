#!/usr/bin/env python3
"""Record upstream's behavioural tests as replayable contract scenarios.

Runs upstream's pytest suites with a plugin that wraps the scripted LibreNMS
transport and FastMCP's Client.call_tool. Every tool call a test makes becomes
one scenario: arguments, each LibreNMS request with the scripted response, and
the MCP result. The Java contract test replays them, so upstream's edge cases
(name/ID collisions, port-ID fallbacks, device checks) are enforced without
hand-porting each test.

Usage (from the repository root, in the upstream environment):
    python capture_tests.py --upstream /path/to/librenms-mcp --out contracts/upstream-tests
"""

from __future__ import annotations

import argparse
import base64
import json
import re
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
from capture import record_request, record_result  # noqa: E402

SUITES = ["tests/test_tool_paths.py", "tests/test_graphs.py", "tests/test_bill_graphs.py"]


class Recorder:
    def __init__(self) -> None:
        self.by_test: dict[str, list[dict]] = {}
        self.current: str | None = None
        self.requests: list[dict] | None = None

    # pytest hooks -----------------------------------------------------------------------
    def pytest_runtest_setup(self, item):
        self.current = item.nodeid

    def pytest_runtest_teardown(self, item):
        self.current = None

    def pytest_sessionstart(self, session):
        import httpx2
        from fastmcp import Client
        from fastmcp.exceptions import ToolError

        recorder = self
        original_handle = httpx2.MockTransport.handle_async_request

        async def handle(transport, request):
            response = await original_handle(transport, request)
            if recorder.requests is not None:
                await response.aread()
                content_type = response.headers.get("content-type", "")
                entry = {"status": response.status_code, "contentType": content_type}
                if response.headers.get("location"):
                    entry["location"] = response.headers["location"]
                try:
                    entry["body"] = json.loads(response.content) if response.content else None
                    if entry["body"] is None:
                        del entry["body"]
                        entry["bodyBase64"] = ""
                except ValueError:
                    entry["bodyBase64"] = base64.b64encode(response.content).decode()
                recorder.requests.append({**record_request(request), "response": entry})
            return response

        httpx2.MockTransport.handle_async_request = handle
        original_call = Client.call_tool

        async def call_tool(client, name, arguments=None, *args, raise_on_error=True, **kwargs):
            outer = recorder.requests
            recorder.requests = []
            try:
                result = await original_call(client, name, arguments, *args, raise_on_error=False, **kwargs)
            finally:
                requests, recorder.requests = recorder.requests, outer
            if recorder.current is not None:
                recorder.by_test.setdefault(recorder.current, []).append(
                    {"tool": name, "arguments": arguments or {}, "requests": requests, "result": record_result(result)}
                )
            if raise_on_error and result.is_error:
                text = result.content[0].text if result.content else ""
                raise ToolError(text)
            return result

        Client.call_tool = call_tool


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--upstream", type=Path, required=True)
    parser.add_argument("--out", type=Path, default=Path("contracts/upstream-tests"))
    args = parser.parse_args()

    recorder = Recorder()
    suites = [str(args.upstream / s) for s in SUITES]
    code = pytest.main(["-q", "-p", "no:cacheprovider", "--rootdir", str(args.upstream), *suites], plugins=[recorder])
    if code != 0:
        print("upstream tests failed; not writing scenarios", file=sys.stderr)
        return int(code)

    args.out.mkdir(parents=True, exist_ok=True)
    for old in args.out.glob("*.json"):
        old.unlink()
    total = 0
    used: set[str] = set()
    for nodeid, calls in sorted(recorder.by_test.items()):
        name = re.sub(r"[^A-Za-z0-9_.-]+", "_", nodeid.split("::", 1)[1]).strip("_")[:120]
        stem = f"{Path(nodeid.split('::', 1)[0]).stem}__{name}"
        unique, n = stem, 2
        # Parameter ids can collide once sanitized, or differ only in case (macOS file systems).
        while unique.lower() in used:
            unique, n = f"{stem}__{n}", n + 1
        used.add(unique.lower())
        doc = {"test": nodeid, "calls": calls}
        (args.out / f"{unique}.json").write_text(json.dumps(doc, indent=2, ensure_ascii=False) + "\n")
        total += len(calls)
    print(f"wrote {len(recorder.by_test)} tests ({total} tool calls) to {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
