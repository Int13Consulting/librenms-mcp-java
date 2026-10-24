#!/usr/bin/env python3
"""Live end-to-end test of the write tools through the Java MCP server.

Drives a running server (read-write, admin API key, ALLOW_DESTRUCTIVE=true) over Streamable HTTP
against a real LibreNMS. Every object created is named mcp-e2e-<run id> and removed again in a
finally block; the few upstream tools without a delete counterpart are listed as residue.

Usage:
    MCP_URL=http://localhost:18090/mcp MCP_API_KEY_FILE=... python3 live_writes.py [--groups clean,residue,device]
Standard library only.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
import urllib.request
import uuid

RUN = "mcp-e2e-" + uuid.uuid4().hex[:6]
URL = os.environ.get("MCP_URL", "http://localhost:18090/mcp")
KEY = open(os.environ["MCP_API_KEY_FILE"]).read().strip()
results: list[tuple[str, bool, str]] = []
residue: list[str] = []
skipped: list[str] = []


def call(tool: str, args: dict) -> dict:
    body = {"jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": {"name": tool, "arguments": args}}
    req = urllib.request.Request(URL, json.dumps(body).encode(), {
        "Content-Type": "application/json", "Accept": "application/json, text/event-stream",
        "Authorization": "Bearer " + KEY})
    with urllib.request.urlopen(req, timeout=120) as resp:
        return json.load(resp)["result"]


def step(label: str, tool: str, args: dict, expect_ok: bool = True) -> dict:
    r = call(tool, args)
    s = r.get("structuredContent") or {}
    ok = not r.get("isError") and (s.get("status") == "ok" or not expect_ok)
    detail = s.get("message") or (r["content"][0].get("text", "")[:120] if r.get("content") else "")
    results.append((f"{label} ({tool})", ok, str(detail)[:140]))
    return s


def find(listing: dict, key: str, field: str, value: str) -> dict | None:
    for item in listing.get(key) or []:
        if isinstance(item, dict) and item.get(field) == value:
            return item
    return None


def group_clean(host: str, device_id: int) -> None:
    # Location
    try:
        step("create location", "location_add", {"payload": {"location": RUN, "lat": 45.42, "lng": -75.69}})
        step("read location", "location_get", {"location": RUN})
        step("edit location", "location_edit", {"location": RUN, "payload": {"lat": 45.5}})
        step("maintenance on empty location", "location_set_maintenance",
             {"location": RUN, "payload": {"title": RUN, "notes": "mcp e2e", "duration": "0:01"}})
    finally:
        step("delete location", "location_delete", {"location": RUN})

    # Alert rule (created disabled)
    # LibreNMS 26.x: alert_operation_id null suppresses notifications (no transports needed).
    rule = {"name": RUN, "devices": [device_id], "severity": "warning", "invert": False, "disabled": 1,
            "alert_operation_id": None,
            "builder": {"condition": "AND", "valid": True, "rules": [
                {"id": "devices.status", "field": "devices.status", "type": "string", "input": "text",
                 "operator": "equal", "value": "0"}]}}
    rule_id = None
    try:
        step("create disabled alert rule", "alert_rule_add", {"payload": rule})
        found = find(step("list alert rules", "alert_rules_list", {"limit": 1000}), "rules", "name", RUN)
        rule_id = found and found.get("id")
        if rule_id:
            step("edit alert rule", "alert_rule_edit", {"payload": {**rule, "rule_id": rule_id, "severity": "critical"}})
            step("read alert rule", "alert_rule_get", {"rule_id": rule_id})
    finally:
        if rule_id:
            step("delete alert rule", "alert_rule_delete", {"rule_id": rule_id})

    # Static device group with the switch
    created = False
    try:
        created = bool(step("create device group", "devicegroup_add",
                            {"payload": {"name": RUN, "type": "static", "devices": [], "desc": "mcp e2e"}}).get("status") == "ok")
        if created:
            step("update device group", "devicegroup_update", {"name": RUN, "payload": {"desc": "mcp e2e edited"}})
            step("add device to group", "devicegroup_add_devices", {"name": RUN, "payload": {"devices": [device_id]}})
            step("list group devices", "devicegroup_devices", {"name": RUN})
            step("remove device from group", "devicegroup_remove_devices", {"name": RUN, "payload": {"devices": [device_id]}})
    finally:
        if created:
            step("delete device group", "devicegroup_delete", {"name": RUN})

    # ICMP service on the switch
    service_id = None
    try:
        r = call("service_add", {"hostname": host, "payload": {"type": "icmp", "desc": RUN}})
        message = str((r.get("structuredContent") or {}).get("message", ""))
        if "Available service types: \n" in message or message.rstrip().endswith("Available service types:"):
            skipped.append("services: no Nagios service plugins installed on this LibreNMS")
        else:
            results.append(("add ICMP service (service_add)", (r.get("structuredContent") or {}).get("status") == "ok", message[:140]))
            found = find(step("list device services", "services_for_device", {"hostname": host}), "services", "service_desc", RUN)
            service_id = found and found.get("service_id")
            if service_id:
                step("edit service", "service_edit", {"service_id": service_id, "payload": {"service_desc": RUN + "-edited"}})
    finally:
        if service_id:
            step("delete service", "service_delete", {"service_id": service_id})

    # Bill
    bill_id = None
    try:
        step("create bill", "bill_create_or_update", {"payload": {
            "bill_name": RUN, "bill_type": "cdr", "bill_cdr": 1000000, "bill_day": 1, "bill_custid": "mcp-e2e",
            "bill_ref": RUN, "bill_notes": "mcp e2e", "dir_95th": "in"}})
        found = find(step("list bills", "bills_list", {"limit": 1000}), "bills", "bill_name", RUN)
        bill_id = found and found.get("bill_id")
        if bill_id:
            step("read bill", "bill_get", {"bill_id": bill_id})
    finally:
        if bill_id:
            step("delete bill", "bill_delete", {"bill_id": bill_id})


def group_residue(host: str, port_id: int) -> None:
    template = {"name": RUN, "template": "{{ $alert->title }}", "title": RUN, "title_rec": RUN}
    step("create alert template", "alert_template_create", {"payload": template})
    found = find(step("list alert templates", "alert_templates_list", {"limit": 1000}), "alert_templates", "name", RUN)
    if found:
        residue.append(f"alert template '{RUN}' (id {found['id']})")
        step("edit alert template", "alert_template_edit",
             {"payload": {**template, "template_id": found["id"], "title": RUN + "-edited"}})

    step("create port group", "port_group_add", {"payload": {"name": RUN, "desc": "mcp e2e"}})
    found = find(step("list port groups", "port_groups_list", {}), "groups", "name", RUN)
    if found:
        residue.append(f"port group '{RUN}' (id {found['id']})")
        step("assign port to group", "port_group_assign", {"port_group_id": found["id"], "payload": {"port_ids": [port_id]}})
        step("list group ports", "port_group_list_ports", {"name": RUN})
        step("remove port from group", "port_group_remove", {"port_group_id": found["id"], "payload": {"port_ids": [port_id]}})

    step("add eventlog entry", "device_eventlog_add", {"hostname": host, "payload": {"text": RUN + " test entry", "type": "system"}})
    residue.append(f"eventlog entry '{RUN} test entry' on {host}")


def group_device(host: str, port_id: int) -> None:
    step("1-minute device maintenance", "device_set_maintenance",
         {"hostname": host, "payload": {"title": RUN, "notes": "mcp e2e", "duration": "0:01"}})
    step("read maintenance status", "device_maintenance_status", {"hostname": host})
    original = step("read port description", "port_description_get", {"port_id": port_id}).get("port_description", "")
    try:
        step("update port description", "port_description_update", {"port_id": port_id, "payload": {"description": RUN}})
        after = step("re-read port description", "port_description_get", {"port_id": port_id})
        results.append(("description changed", after.get("port_description") == RUN, str(after.get("port_description"))))
    finally:
        step("restore port description", "port_description_update", {"port_id": port_id, "payload": {"description": original}})
    step("trigger discovery", "device_discover", {"hostname": host})


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--groups", default="clean,residue,device")
    groups = parser.parse_args().groups.split(",")
    device = call("devices_list", {"limit": 1})["structuredContent"]["devices"][0]
    host, device_id = device["hostname"], device["device_id"]
    ports = call("device_ports", {"hostname": host, "columns": "port_id,ifName", "limit": 1})["structuredContent"]["ports"]
    port_id = ports[0]["port_id"]
    print(f"run {RUN} on device_id {device_id}, port_id {port_id}")
    start = time.time()
    if "clean" in groups:
        group_clean(host, device_id)
    if "residue" in groups:
        group_residue(host, port_id)
    if "device" in groups:
        group_device(host, port_id)
    for label, ok, detail in results:
        print(f"{'PASS' if ok else 'FAIL'}  {label:58s} {detail if not ok else ''}")
    failed = sum(1 for _, ok, _ in results if not ok)
    print(f"\n{len(results) - failed}/{len(results)} passed in {time.time() - start:.1f}s")
    if skipped:
        print("Skipped:\n  - " + "\n  - ".join(skipped))
    if residue:
        print("Left behind (no upstream delete tool):\n  - " + "\n  - ".join(residue))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
