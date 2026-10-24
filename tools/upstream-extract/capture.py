#!/usr/bin/env python3
"""Record golden contract transcripts from the upstream tools.

Every tool is called through a real FastMCP client against a scripted LibreNMS.
Each transcript records the tool arguments, every HTTP request the tool sent
(method, raw path, query, JSON body) with the scripted LibreNMS response, and
the MCP result. Java contract tests
replay these with WireMock, so Java CI needs no Python.

Scenarios per tool:
  required  - only required arguments, LibreNMS answers successfully
  full      - every argument set, LibreNMS answers successfully
  error     - required arguments, LibreNMS answers 404 with an error body

Arguments are generated from the input schema; scenarios.json overrides them
where a tool needs a realistic payload, and adds named "extra" scenarios
(success responses) such as upstream's path-safety cases.

Usage:
    python capture.py --out contracts/transcripts [--overrides scenarios.json]
"""

from __future__ import annotations

import argparse
import asyncio
import base64
import json
from pathlib import Path
from urllib.parse import parse_qsl, unquote

import httpx2
from fastmcp import Client, FastMCP

from librenms_mcp.librenms_client import LibreNMSClient
from librenms_mcp.models import LibreNMSConfig
from librenms_mcp.tools import register_tools

CONFIG = LibreNMSConfig(librenms_url="https://nms.invalid", token="t")

# 1x1 transparent PNG.
PNG = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII="
)

STRING_SAMPLES = {
    "hostname": "core1",
    "new_hostname": "core1-new",
    "ifname": "Gi0/1",
    "mac": "00:11:22:33:44:55",
    "ip": "192.0.2.1",
    "query": "192.0.2.1",
    "graph_type": "device_processor",
    "type": "temperature",
    "field": "ifAlias",
    "search": "uplink",
    "from_time": "-1d",
    "to_time": "now",
    "period": "current",
    "name": "sample",
}


def sample(name: str, schema: dict, full: bool):
    """Build a value that satisfies a (pydantic-generated) JSON schema."""
    if "anyOf" in schema:
        options = [s for s in schema["anyOf"] if s.get("type") != "null"]
        return sample(name, {**options[0], **{k: v for k, v in schema.items() if k != "anyOf"}}, full)
    if "enum" in schema:
        return schema["enum"][0]
    match schema.get("type"):
        case "string":
            return STRING_SAMPLES.get(name, f"{name}-1")
        case "integer":
            return max(1, schema.get("minimum", 1))
        case "boolean":
            return True
        case "array":
            return [sample(name, schema.get("items", {"type": "string"}), full)]
        case "object":
            props = schema.get("properties", {})
            return {k: sample(k, v, full) for k, v in props.items()}
    return f"{name}-1"


def build_args(tool: dict, full: bool) -> dict:
    schema = tool["inputSchema"]
    required = set(schema.get("required", []))
    return {
        name: sample(name, prop, full)
        for name, prop in schema.get("properties", {}).items()
        if name in required or full
    }


def success_handler(binary: bool):
    def handle(request: httpx2.Request) -> httpx2.Response:
        parts = request.url.path.split("/")
        # Device lookups (GET /api/v0/devices/{name}) answer with a device record,
        # as in upstream's own path tests.
        if request.method == "GET" and len(parts) == 5 and parts[3] == "devices":
            name = unquote(parts[4])
            return httpx2.Response(
                200, json={"status": "ok", "count": 1, "devices": [{"device_id": 1, "hostname": name}]}
            )
        if binary and request.method == "GET" and "graphs" not in parts[-1:]:
            return httpx2.Response(200, content=PNG, headers={"content-type": "image/png"})
        return httpx2.Response(200, json={"status": "ok", "count": 0})

    return handle


def error_handler(_request: httpx2.Request) -> httpx2.Response:
    return httpx2.Response(404, json={"status": "error", "message": "Not found"})


def record_request(request: httpx2.Request) -> dict:
    raw_path, _, raw_query = request.url.raw_path.decode().partition("?")
    entry = {
        "method": request.method,
        "path": raw_path,
        "query": parse_qsl(raw_query, keep_blank_values=True),
    }
    if request.content:
        try:
            entry["body"] = json.loads(request.content)
        except ValueError:
            entry["bodyRaw"] = request.content.decode(errors="replace")
    return entry


def record_response(response: httpx2.Response) -> dict:
    """The scripted LibreNMS answer, so replays need not re-implement the handlers."""
    content_type = response.headers.get("content-type", "")
    entry = {"status": response.status_code, "contentType": content_type}
    if content_type.startswith("application/json"):
        entry["body"] = json.loads(response.content)
    else:
        entry["bodyBase64"] = base64.b64encode(response.content).decode()
    return entry


def record_result(result) -> dict:
    content = []
    for block in result.content:
        dumped = block.model_dump(mode="json", by_alias=True, exclude_none=True)
        dumped.pop("annotations", None)
        content.append(dumped)
    out = {"isError": result.is_error, "content": content}
    if result.structured_content is not None:
        out["structuredContent"] = result.structured_content
    return out


async def run_scenario(mcp: FastMCP, tool: dict, args: dict, handler) -> dict:
    LibreNMSClient._instance = None
    LibreNMSClient._initialized = False
    requests: list[dict] = []

    def transport(request: httpx2.Request) -> httpx2.Response:
        response = handler(request)
        requests.append({**record_request(request), "response": record_response(response)})
        return response

    client = LibreNMSClient(CONFIG)
    client.client = httpx2.AsyncClient(base_url=client.base_url, transport=httpx2.MockTransport(transport))
    try:
        async with Client(mcp) as mcp_client:
            result = await mcp_client.call_tool(tool["name"], args, raise_on_error=False)
    finally:
        LibreNMSClient._instance = None
        LibreNMSClient._initialized = False
    return {"arguments": args, "requests": requests, "result": record_result(result)}


async def capture(out: Path, overrides: dict, contracts: dict) -> None:
    mcp = FastMCP("capture")
    register_tools(mcp, CONFIG)
    out.mkdir(parents=True, exist_ok=True)
    for tool in contracts["tools"]:
        binary = any(op.get("binary") for op in tool["httpOperations"])
        tool_overrides = overrides.get(tool["name"], {})
        required_args = tool_overrides.get("required", build_args(tool, full=False))
        full_args = tool_overrides.get("full", {**build_args(tool, full=True), **required_args})
        scenarios = {
            "required": await run_scenario(mcp, tool, required_args, success_handler(binary)),
            "full": await run_scenario(mcp, tool, full_args, success_handler(binary)),
            "error": await run_scenario(mcp, tool, required_args, error_handler),
        }
        for name, extra_args in tool_overrides.get("extra", {}).items():
            scenarios[name] = await run_scenario(mcp, tool, extra_args, success_handler(binary))
        doc = {"tool": tool["name"], "scenarios": scenarios}
        (out / f"{tool['name']}.json").write_text(json.dumps(doc, indent=2) + "\n")
    print(f"wrote {len(contracts['tools'])} transcripts to {out}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--contracts", type=Path, default=Path("contracts/upstream-tools.json"))
    parser.add_argument("--out", type=Path, default=Path("contracts/transcripts"))
    parser.add_argument("--overrides", type=Path, default=Path(__file__).with_name("scenarios.json"))
    args = parser.parse_args()
    overrides = json.loads(args.overrides.read_text()) if args.overrides.exists() else {}
    overrides.pop("$comment", None)
    asyncio.run(capture(args.out, overrides, json.loads(args.contracts.read_text())))


if __name__ == "__main__":
    main()
