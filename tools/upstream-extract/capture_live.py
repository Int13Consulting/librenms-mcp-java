#!/usr/bin/env python3
"""Record sanitized live transcripts of the upstream read tools against a real LibreNMS.

Same transcript shape as capture.py, but LibreNMS answers come from a real
instance, so responses show real field names and types. Only tools classified
READ and retrySafe are run. A guard transport refuses anything other than GET
and any path containing "discover", so nothing on the instance can change.

Everything written is pseudonymized: IP and MAC addresses, host and domain
names, identity fields (hostnames, locations, contacts, serials, descriptions)
and free text are replaced consistently across arguments, requests, responses
and results; secrets are redacted; graph images are replaced by a placeholder.
Review the output before committing it.

Usage:
    python capture_live.py --url http://librenms.example --env-file .env [--out contracts/live]
The token is read from LIBRENMS_TOKEN or LIBRENMS_API_KEY in the environment or env file.
"""

from __future__ import annotations

import argparse
import asyncio
import base64
import ipaddress
import json
import os
import re
from pathlib import Path
from urllib.parse import quote

import sys

import httpx2
from fastmcp import Client, FastMCP

sys.path.insert(0, str(Path(__file__).resolve().parent))
from capture import PNG, build_args, record_request, record_result, sample
from librenms_mcp.librenms_client import LibreNMSClient
from librenms_mcp.models import LibreNMSConfig
from librenms_mcp.tools import register_tools

SKIP_MODULES = {"oxidized"}  # not in use on the target deployment


class GuardTransport(httpx2.AsyncBaseTransport):
    """Read-only pass-through: refuse every request that could change LibreNMS."""

    def __init__(self) -> None:
        self.inner = httpx2.AsyncHTTPTransport()
        self.log: list[tuple[httpx2.Request, httpx2.Response]] | None = None

    async def handle_async_request(self, request: httpx2.Request) -> httpx2.Response:
        if request.method != "GET" or "discover" in request.url.path.lower():
            raise RuntimeError(f"refused non-read request: {request.method} {request.url.path}")
        response = await self.inner.handle_async_request(request)
        await response.aread()
        if self.log is not None:
            self.log.append((request, response))
        return response


# --- seeding -----------------------------------------------------------------


async def seed(client: httpx2.AsyncClient) -> dict:
    """Pick real identifiers to call the tools with."""

    async def get(*path: str | int, **params) -> dict:
        resp = await client.get("/".join(quote(str(p), safe="") for p in path), params=params or None)
        try:
            body = resp.json()
        except ValueError:
            return {}
        return body if isinstance(body, dict) else {}

    def first(body: dict, key: str) -> dict:
        items = body.get(key)
        return items[0] if isinstance(items, list) and items and isinstance(items[0], dict) else {}

    s: dict = {}
    device = first(await get("devices", type="all"), "devices")
    if device:
        s["hostname"] = device["hostname"]
        ports = (await get("devices", device["hostname"], "ports", columns="port_id,ifName,ifPhysAddress")).get("ports") or []
        port = next((p for p in ports if p.get("ifName") and p.get("ifPhysAddress")), ports[0] if ports else {})
        if port:
            s["port_id"] = port["port_id"]
            s["port_ids"] = [port["port_id"]]
            s["ifname"] = port.get("ifName")
            s["search"] = port.get("ifName")
            mac = port.get("ifPhysAddress") or ""
            if len(mac) == 12:
                s["mac"] = ":".join(mac[i : i + 2] for i in range(0, 12, 2))
        health = (await get("devices", device["hostname"], "health")).get("graphs") or []
        if health:
            s["type"] = health[0].get("name", "").removeprefix("device_")
            sensor = first(await get("devices", device["hostname"], "health", health[0].get("name", "")), "graphs")
            if sensor.get("sensor_id"):
                s["sensor_id"] = sensor["sensor_id"]
        graphs = (await get("devices", device["hostname"], "graphs")).get("graphs") or []
        if graphs:
            s["device_graph_type"] = graphs[0].get("name")
        s["query"] = "all"
        s["device"] = device["hostname"]
    for path, key, field, seed_key in [
        (("alerts",), "alerts", "id", "alert_id"),
        (("rules",), "rules", "id", "rule_id"),
        (("alert_templates",), "templates", "id", "template_id"),
        (("bills",), "bills", "bill_id", "bill_id"),
        (("routing", "bgp"), "bgp_sessions", "bgpPeer_id", "bgp_id"),
        (("resources", "locations"), "locations", "location", "location"),
        (("devicegroups",), "groups", "name", "devicegroup_name"),
        (("port_groups",), "groups", "name", "port_group_name"),
    ]:
        body = await get(*path)
        item = first(body, key) or next(
            (v[0] for v in body.values() if isinstance(v, list) and v and isinstance(v[0], dict)), {}
        )
        if field in item:
            s[seed_key] = item[field]
    if "bill_id" in s:
        history = first(await get("bills", s["bill_id"], "history"), "bill_history")
        if history.get("bill_hist_id"):
            s["history_id"] = history["bill_hist_id"]
    return s


def args_for(tool: dict, seeds: dict) -> dict:
    args = build_args(tool, full=False)
    props = tool["inputSchema"].get("properties", {})
    for name in args:
        if name == "graph_type" and tool["name"] == "device_graph" and "device_graph_type" in seeds:
            args[name] = seeds["device_graph_type"]
        elif name == "graph_type" and tool["name"].startswith("bill"):
            args[name] = "bits"
        elif name == "field":
            args[name] = "ifName"
        elif name == "name" and "devicegroup" in tool["name"] and "devicegroup_name" in seeds:
            args[name] = seeds["devicegroup_name"]
        elif name == "name" and "port_group" in tool["name"] and "port_group_name" in seeds:
            args[name] = seeds["port_group_name"]
        elif name in seeds:
            args[name] = seeds[name]
    if tool["name"] == "arp_search" and "device" in seeds:
        args["device"] = seeds["device"]
    # Keep schema-valid types for anything still sampled.
    return {k: (v if v is not None else sample(k, props.get(k, {}), False)) for k, v in args.items()}


# --- sanitizing --------------------------------------------------------------

SECRET_KEY = re.compile(r"pass|secret|token|community|auth_?name|api_?key|snmp_?user|cryptoalgo|authalgo", re.I)
IDENTITY_KEYS = {
    "hostname": "host", "sysname": "host", "display": "host", "device_hostname": "host",
    "remote_hostname": "host", "overwrite_ip": "host", "ip": "ip", "service_ip": "ip",
    "syscontact": "contact", "location": "location", "syslocation": "location",
    "serial": "serial", "entphysicalserialnum": "serial", "asset_tag": "serial",
    "ifalias": "descr", "port_descr_descr": "descr", "port_descr_circuit": "descr",
    "service_desc": "descr", "service_message": "text", "service_param": "text",
    "notes": "text", "purpose": "text", "user": "user", "username": "user", "name_full": "user",
}
FREE_TEXT_KEYS = {"message", "msg", "comment", "note", "details"}
ZERO_KEYS = {"lat", "lng"}
# Lookarounds keep OIDs such as .1.3.6.1.4.1.9.1.2365 from matching.
IPV4 = re.compile(r"(?<![\d.])(?:(?:25[0-5]|2[0-4]\d|1?\d?\d)\.){3}(?:25[0-5]|2[0-4]\d|1?\d?\d)(?!\.?\d)")
# Candidates only; each match is validated with ipaddress before replacing.
IPV6 = re.compile(r"(?<![\w:])[0-9a-fA-F]{0,4}(?::[0-9a-fA-F]{0,4}){2,7}(?![\w:])")
MAC = re.compile(r"\b[0-9a-fA-F]{2}(?::[0-9a-fA-F]{2}){5}\b")
FQDN = re.compile(r"\b(?:[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\.)+[a-zA-Z]{2,24}\b")
BARE_MAC_KEY = re.compile(r"mac|physaddress", re.I)
SAFE_FQDNS = {"example.net", "example.com", "librenms.org"}


def _is_ip(value: str) -> bool:
    try:
        ipaddress.ip_address(value)
    except ValueError:
        return False
    return True


class Pseudonymizer:
    def __init__(self) -> None:
        self.map: dict[str, str] = {}
        self.fakes: set[str] = set()
        self.counters: dict[str, int] = {}

    def _next(self, category: str) -> int:
        self.counters[category] = self.counters.get(category, 0) + 1
        return self.counters[category]

    def alias(self, value: str, category: str) -> str:
        if value in self.map:
            return self.map[value]
        if value in self.fakes:
            return value
        n = self._next(category)
        if category == "ip4":
            fake = f"{('192.0.2', '198.51.100', '203.0.113')[(n - 1) // 250 % 3]}.{(n - 1) % 250 + 1}"
        elif category == "ip6":
            fake = f"2001:db8::{n:x}"
        elif category == "mac":
            fake = f"00:00:5e:00:{n // 256:02x}:{n % 256:02x}"
        elif category == "mac12":
            fake = f"00005e00{n // 256:02x}{n % 256:02x}"
        elif category == "fqdn":
            fake = f"domain-{n}.example.net"
        else:
            fake = f"{category}-{n}"
        self.map[value] = fake
        self.fakes.add(fake)
        return fake

    def ip(self, value: str) -> str:
        try:
            addr = ipaddress.ip_address(value)
        except ValueError:
            return self.alias(value, "host")
        return self.alias(value, "ip4" if addr.version == 4 else "ip6")

    # Pass 1: learn identity values from the responses.
    def learn(self, node, key: str = "") -> None:
        if isinstance(node, dict):
            for k, v in node.items():
                self.learn(v, k)
        elif isinstance(node, list):
            for v in node:
                self.learn(v, key)
        elif isinstance(node, str) and node.strip():
            category = IDENTITY_KEYS.get(key.lower())
            if category == "ip" or (category == "host" and IPV4.fullmatch(node)):
                self.ip(node)
            elif category and len(node) >= 3:
                self.alias(node, category)

    def text(self, value: str) -> str:
        # Literal pass for names; addresses are left to the regexes below so a
        # replaced address is never matched (and replaced) a second time.
        for original in sorted(self.map, key=len, reverse=True):
            if len(original) >= 3 and not _is_ip(original):
                value = value.replace(original, self.map[original])
                value = value.replace(quote(original, safe=""), quote(self.map[original], safe=""))
        value = MAC.sub(lambda m: self.alias(m.group(0).lower(), "mac"), value)
        value = IPV4.sub(lambda m: self.ip(m.group(0)), value)

        def v6(m: re.Match) -> str:
            try:
                ipaddress.IPv6Address(m.group(0))
            except ValueError:
                return m.group(0)
            return self.ip(m.group(0))

        value = IPV6.sub(v6, value)
        return FQDN.sub(
            lambda m: m.group(0)
            if m.group(0).lower() in SAFE_FQDNS or m.group(0).lower().endswith(".example.net")
            or re.fullmatch(r"[\w-]+\.(php|py|js|json|html|rrd|png|svg|txt|conf|log)", m.group(0), re.I)
            else self.alias(m.group(0).lower(), "fqdn"),
            value,
        )

    # Pass 2: rewrite.
    def scrub(self, node, key: str = "", depth: int = 0):
        lk = key.lower()
        if isinstance(node, dict):
            return {k: self.scrub(v, k, depth + 1) for k, v in node.items()}
        if isinstance(node, list):
            return [self.scrub(v, key, depth) for v in node]
        if lk in ZERO_KEYS and node not in (None, ""):
            return 0 if isinstance(node, (int, float)) else "0"
        if not isinstance(node, str) or not node:
            return node
        if SECRET_KEY.search(lk):
            return "REDACTED"
        if lk in FREE_TEXT_KEYS and depth > 1:
            return self.alias(node, "text")
        if BARE_MAC_KEY.search(lk) and re.fullmatch(r"[0-9a-fA-F]{12}", node):
            return self.alias(node.lower(), "mac12")
        category = IDENTITY_KEYS.get(lk)
        if category and node in self.map:
            return self.map[node]
        return self.text(node)


def sanitize_scenario(scenario: dict, p: Pseudonymizer) -> dict:
    out = {"arguments": p.scrub(scenario["arguments"]), "requests": [], "result": None}
    for req in scenario["requests"]:
        entry = {
            "method": req["method"],
            "path": p.text(req["path"]),
            "query": [[k, p.text(v)] for k, v in req["query"]],
            "response": dict(req["response"]),
        }
        resp = entry["response"]
        if "body" in resp:
            resp["body"] = p.scrub(resp["body"])
        elif "bodyBase64" in resp:
            if resp["contentType"].startswith("image/"):
                resp["originalBytes"] = len(base64.b64decode(resp["bodyBase64"]))
                resp["bodyBase64"] = base64.b64encode(PNG).decode()
                resp["contentType"] = "image/png"
            else:
                resp["bodyText"] = p.text(base64.b64decode(resp.pop("bodyBase64")).decode(errors="replace"))
        out["requests"].append(entry)
    result = scenario["result"]
    content = []
    for block in result["content"]:
        block = dict(block)
        if block.get("type") == "text":
            try:
                block["text"] = json.dumps(p.scrub(json.loads(block["text"])), separators=(",", ":"), ensure_ascii=False)
            except ValueError:
                block["text"] = p.text(block["text"])
        elif block.get("type") == "image":
            block["data"] = base64.b64encode(PNG).decode()
        content.append(block)
    out["result"] = {"isError": result["isError"], "content": content}
    if "structuredContent" in result:
        out["result"]["structuredContent"] = p.scrub(result["structuredContent"])
    return out


# --- capture -----------------------------------------------------------------


def record_live_response(response: httpx2.Response) -> dict:
    content_type = response.headers.get("content-type", "").split(";")[0].strip()
    entry = {"status": response.status_code, "contentType": content_type}
    try:
        entry["body"] = json.loads(response.content)
    except ValueError:
        entry["bodyBase64"] = base64.b64encode(response.content).decode()
    return entry


async def run(url: str, token: str, out: Path) -> None:
    contracts = json.loads(Path("contracts/upstream-tools.json").read_text())
    classification = json.loads(Path("contracts/tool-classification.json").read_text())["tools"]
    config = LibreNMSConfig(librenms_url=url, token=token, timeout=60)
    guard = GuardTransport()
    http = httpx2.AsyncClient(
        base_url=f"{url.rstrip('/')}/api/v0", transport=guard,
        headers={"X-Auth-Token": token}, timeout=60, follow_redirects=False,
    )
    seeds = await seed(http)
    print("seeded:", ", ".join(sorted(seeds)))

    mcp = FastMCP("capture-live")
    register_tools(mcp, config)
    raw: dict[str, dict] = {}
    for tool in contracts["tools"]:
        c = classification[tool["name"]]
        if c["access"] != "READ" or not c["retrySafe"] or tool["module"] in SKIP_MODULES:
            continue
        args = args_for(tool, seeds)
        LibreNMSClient._instance = None
        LibreNMSClient._initialized = False
        client = LibreNMSClient(config)
        client.client = http
        guard.log = []
        async with Client(mcp) as mcp_client:
            result = await mcp_client.call_tool(tool["name"], args, raise_on_error=False)
        requests = [{**record_request(rq), "response": record_live_response(rs)} for rq, rs in guard.log]
        raw[tool["name"]] = {"arguments": args, "requests": requests, "result": record_result(result)}
    await http.aclose()

    system = next((s["requests"][0]["response"]["body"] for n, s in raw.items() if n == "system_info"), {})
    version = (system.get("system") or [{}])[0].get("local_ver")

    p = Pseudonymizer()
    for scenario in raw.values():
        for req in scenario["requests"]:
            p.learn(req["response"].get("body"))
    out.mkdir(parents=True, exist_ok=True)
    for name, scenario in raw.items():
        doc = {"tool": name, "librenmsVersion": version, "scenarios": {"live": sanitize_scenario(scenario, p)}}
        (out / f"{name}.json").write_text(json.dumps(doc, indent=2, ensure_ascii=False) + "\n")
    print(f"wrote {len(raw)} live transcripts to {out}")


def read_token(env_file: Path | None) -> str:
    for key in ("LIBRENMS_TOKEN", "LIBRENMS_API_KEY"):
        if os.environ.get(key):
            return os.environ[key]
    if env_file and env_file.exists():
        for line in env_file.read_text().splitlines():
            key, _, value = line.partition("=")
            if key.strip() in ("LIBRENMS_TOKEN", "LIBRENMS_API_KEY") and value.strip():
                return value.strip().strip("'\"")
    raise SystemExit("no LIBRENMS_TOKEN or LIBRENMS_API_KEY found")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--url", required=True)
    parser.add_argument("--env-file", type=Path, default=Path(".env"))
    parser.add_argument("--out", type=Path, default=Path("contracts/live"))
    args = parser.parse_args()
    asyncio.run(run(args.url, read_token(args.env_file), args.out))


if __name__ == "__main__":
    main()
