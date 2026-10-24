#!/usr/bin/env python3
"""Dump the upstream librenms-mcp tool contracts to JSON.

Run with a Python environment that has the upstream package installed at the
pinned commit (see README.md). The tool list comes from FastMCP itself, so names,
descriptions, input/output schemas, tags and annotations are exactly what an MCP
client sees. The LibreNMS HTTP operations each tool performs are recovered
statically from the tool's source.

Usage:
    python extract.py --upstream /path/to/librenms-mcp --out contracts/upstream-tools.json
"""

from __future__ import annotations

import argparse
import ast
import asyncio
import inspect
import json
import subprocess
from importlib.metadata import version
from pathlib import Path

CLIENT_METHODS = {"get", "get_raw", "post", "put", "delete", "patch", "request"}

# Shared helpers that issue LibreNMS requests on a tool's behalf.
HELPERS = {
    "route_ref",
    "lookup_device",
    "device_error_if_empty",
    "resolve_port_id",
    "with_port_id_fallback",
    "paginate_list",
}


def http_operations(fn) -> tuple[list[dict], list[str]]:
    """Return the client calls and shared helpers used by a tool function."""
    # Parse the whole module and locate the function by line: decorators carry
    # multi-line strings that defeat dedenting the function's own source.
    module_tree = ast.parse(Path(inspect.getsourcefile(fn)).read_text())
    first_line = fn.__code__.co_firstlineno
    tree = next(
        node
        for node in ast.walk(module_tree)
        if isinstance(node, ast.AsyncFunctionDef | ast.FunctionDef)
        and node.name == fn.__name__
        and (node.lineno == first_line or any(d.lineno == first_line for d in node.decorator_list))
    )
    # Module-level functions, so calls into private helpers such as
    # logs._list_logs can be followed.
    local_fns = {
        node.name: node
        for node in module_tree.body
        if isinstance(node, ast.AsyncFunctionDef | ast.FunctionDef)
    }
    ops: list[dict] = []
    helpers: set[str] = set()
    pending, seen = [(tree, None)], set()
    while pending:
        current, via = pending.pop()
        _collect_calls(current, via, local_fns, ops, helpers, pending, seen)
    return ops, sorted(helpers)


def _collect_calls(tree, via, local_fns, ops, helpers, pending, seen) -> None:
    for node in ast.walk(tree):
        if not isinstance(node, ast.Call):
            continue
        func = node.func
        if isinstance(func, ast.Name) and func.id in local_fns and func.id not in seen:
            seen.add(func.id)
            pending.append((local_fns[func.id], func.id))
        if (
            isinstance(func, ast.Attribute)
            and func.attr in CLIENT_METHODS
            and isinstance(func.value, ast.Name)
            and func.value.id == "client"
        ):
            args = [ast.unparse(a) for a in node.args]
            method = "GET" if func.attr == "get_raw" else func.attr.upper()
            if func.attr == "request" and args:
                method, args = args[0].strip("'\""), args[1:]
            op = {"method": method, "segments": args}
            for kw in node.keywords:
                if kw.arg in {"params", "data"}:
                    op[kw.arg] = ast.unparse(kw.value)
            if func.attr == "get_raw":
                op["binary"] = True
            if via:
                op["via"] = via
            if op not in ops:
                ops.append(op)
        elif isinstance(func, ast.Name) and func.id in HELPERS:
            helpers.add(func.id)


def upstream_commit(path: Path) -> str | None:
    try:
        return subprocess.run(
            ["git", "-C", str(path), "rev-parse", "HEAD"],
            capture_output=True,
            text=True,
            check=True,
        ).stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        return None


async def collect() -> list[dict]:
    from fastmcp import FastMCP

    from librenms_mcp.models import LibreNMSConfig
    from librenms_mcp.tools import register_tools

    # Register on a bare server: no read-only/disabled-tag transforms, no tool
    # search, so every tool is listed.
    mcp = FastMCP("extract")
    register_tools(mcp, LibreNMSConfig(librenms_url="https://librenms.invalid", token="x"))

    tools = []
    for tool in await mcp.list_tools():
        mcp_tool = tool.to_mcp_tool().model_dump(
            mode="json", by_alias=True, exclude_none=True
        )
        ops, helpers = http_operations(tool.fn)
        tools.append(
            {
                "name": tool.name,
                "module": tool.fn.__module__.rsplit(".", 1)[-1],
                "tags": sorted(tool.tags),
                "title": mcp_tool.get("title"),
                "description": mcp_tool.get("description"),
                "annotations": mcp_tool.get("annotations", {}),
                "inputSchema": mcp_tool["inputSchema"],
                "outputSchema": mcp_tool.get("outputSchema"),
                "meta": mcp_tool.get("_meta"),
                "httpOperations": ops,
                "helpers": helpers,
            }
        )
    # FastMCP's own order (registration order): tools/list and tool-search ties follow it.
    return tools


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--upstream", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()

    tools = asyncio.run(collect())
    doc = {
        "upstream": {
            "repository": "https://github.com/mhajder/librenms-mcp",
            "commit": upstream_commit(args.upstream),
            "packageVersion": version("librenms-mcp"),
            "fastmcpVersion": version("fastmcp"),
        },
        "toolCount": len(tools),
        "tools": tools,
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(doc, indent=2, sort_keys=False) + "\n")
    print(f"wrote {len(tools)} tools to {args.out}")


if __name__ == "__main__":
    main()
