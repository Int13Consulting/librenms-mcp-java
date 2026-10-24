#!/usr/bin/env python3
"""Record FastMCP tool-search behaviour (TOOL_SEARCH_ENABLED) as a contract.

For both strategies: the search_tools / call_tool definitions, and the ranked
results of a fixed set of queries over all upstream tools. The Java search
must reproduce the rankings exactly.

Usage:
    python capture_search.py --out contracts/tool-search.json
"""

from __future__ import annotations

import argparse
import asyncio
import json
from pathlib import Path

from fastmcp import Client, FastMCP
from fastmcp.server.transforms.search import BM25SearchTransform, RegexSearchTransform

from librenms_mcp.models import LibreNMSConfig
from librenms_mcp.tools import register_tools

BM25_QUERIES = [
    "port traffic graph", "list devices", "device", "acknowledge an alert", "alert rules",
    "billing history", "BGP sessions for a router", "OSPF neighbours", "ARP table lookup by IP",
    "find a MAC address", "syslog messages", "event log for a device", "sensor temperature health",
    "oxidized configuration backup", "port security", "VLANs", "inventory hardware serial",
    "maintenance mode", "location", "poller group", "services nagios checks", "ping", "system info version",
    "Te2/7 interface", "delete", "ÉVÉNEMENT", "a", "", "zzzz nothing matches",
]
REGEX_PATTERNS = [
    "graph", "^device_", "port.*graph", "bgp|ospf", "MAC", "hostname", "(unclosed", "a{2,}",
    "(?=lookahead)", "x" * 1001, "_list$", "alert_(rule|template)",
]


async def collect() -> dict:
    out: dict = {}
    for name, transform, queries, arg in (
        ("bm25", BM25SearchTransform, BM25_QUERIES, "query"),
        ("regex", RegexSearchTransform, REGEX_PATTERNS, "pattern"),
    ):
        mcp = FastMCP("search")
        register_tools(mcp, LibreNMSConfig(librenms_url="https://librenms.invalid", token="x"))
        mcp.add_transform(transform(max_results=5))
        async with Client(mcp) as client:
            tools = {
                t.name: t.model_dump(mode="json", by_alias=True, exclude_none=True) for t in await client.list_tools()
            }
            results = []
            for q in queries:
                r = await client.call_tool("search_tools", {arg: q}, raise_on_error=False)
                names = [t["name"] for t in (r.structured_content or {}).get("result", [])]
                results.append({arg: q, "names": names})
            full = await client.call_tool("search_tools", {arg: queries[0]}, raise_on_error=False)
            out[name] = {
                "tools": tools,
                "results": results,
                "exampleResult": {
                    "text": full.content[0].text,
                    "structuredContent": full.structured_content,
                },
            }
    return out


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--out", type=Path, default=Path("contracts/tool-search.json"))
    args = parser.parse_args()
    args.out.write_text(json.dumps(asyncio.run(collect()), indent=2, ensure_ascii=False) + "\n")
    print(f"wrote {args.out}")


if __name__ == "__main__":
    main()
