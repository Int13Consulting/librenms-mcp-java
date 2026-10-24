# Upstream Baseline

The Java port targets this exact upstream state. Changes upstream after this pin are tracked
separately (see `tools/upstream-extract/README.md`, "Updating the upstream pin").

| | |
|---|---|
| Repository | https://github.com/mhajder/librenms-mcp |
| Commit | `09ba2f6d393d199ff6bb0b0fdaa824da0f909274` |
| Release | 1.12.1 (2026-10-23) |
| Runtime | Python 3.11–3.15, FastMCP 4.1.2, mcp 2.3.0, pydantic 2.14.1, httpx2 |
| License | MIT, Copyright (c) 2025 Mateusz Hajder — preserved in `NOTICE` |
| Size | ~7.7k LOC (src), 171 tests |

## Contract artifacts

| File | Contents |
|---|---|
| `contracts/upstream-tools.json` | All 110 tools as served by FastMCP: name, description, tags, annotations, input schema, output schema, and statically extracted LibreNMS operations |
| `contracts/transcripts/<tool>.json` | Golden transcripts: arguments → HTTP requests sent → MCP result, for `required`, `full`, `error` and named path-safety scenarios (339 total) |
| `contracts/live/<tool>.json` | Sanitized live transcripts (`live` scenario) of 72 READ tools against the test LibreNMS (26.11.0-dev): real field names and types for output-shape tests |
| `contracts/upstream-tests/*.json` | Upstream's pytest suites recorded as scenarios (227 tool calls from 222 tests) |
| `contracts/tool-search.json` | Tool-search tool definitions and recorded BM25/regex rankings |
| `contracts/tool-classification.json` | Java access level, destructive flag, retry safety and port status per tool (hand-maintained) |
| `docs/parity-matrix.md` | Generated view of the above |

## Tool inventory

110 tools in 15 modules: devices 26, ports 15, alerts 13, network 12, bills 9, locations 6,
logs 5, services 5, graphs 4, health 4, oxidized 3, port_security 3, inventory 2, system 2,
pollers 1.

Every tool has a `title` (e.g. "Alerts Get") and `_meta: {"fastmcp": {"tags": [...]}}`, and
`tools/list` returns tools in registration order (alerts, bills, devices, graphs, …). Java serves
all three identically.

Upstream tags: 75 `read-only`, 29 `admin`, 6 untagged (`alert_template_create`,
`alert_template_edit`, `device_discover`, `device_eventlog_add`, `device_rename`,
`port_description_update`). Every tool also has tag `librenms` and one or more area tags, which
`DISABLED_TAGS` matches against. All tools carry MCP annotations (`readOnlyHint`,
`destructiveHint`, `idempotentHint`); 25 have `destructiveHint: true`.

Java classification (approved 2026-10-24): READ 73, OPERATE 7, ADMIN 30; 14 destructive. See
`docs/parity-matrix.md`.

## Behaviour the Java port must reproduce

### Transport and server
- `MCP_TRANSPORT`: `stdio` (default), `http` (alias `streamable-http`), `sse`. Unknown values stop
  startup. Default bind `127.0.0.1:8000`.
- `GET /health` returns `200 ok` as plain text, outside authentication, without calling
  LibreNMS. The container `HEALTHCHECK` runs `python -m librenms_mcp.healthcheck` against it.
- Server name `LibreNMS MCP Server`, with fixed `instructions` text (see `server.py`).
- Static bearer token (`MCP_HTTP_BEARER_TOKEN`) grants scopes `read`, `write` to client
  `authenticated-client`. No token on an HTTP transport logs a warning and runs unauthenticated.
- `READ_ONLY_MODE` enables only tools tagged `read-only`, so the six untagged tools are hidden in
  read-only mode. `DISABLED_TAGS` (comma-separated) then hides any tool with a matching tag.
- Tool search (`TOOL_SEARCH_ENABLED`) replaces the tool list with `search_tools` and `call_tool`;
  strategy `bm25` (default) or `regex`; `TOOL_SEARCH_MAX_RESULTS` default 5.
- Rate limiting: one global sliding window, `RATE_LIMIT_MAX_REQUESTS` per
  `RATE_LIMIT_WINDOW_MINUTES`. When enabled, values below 1 fail startup; when disabled they are
  ignored.
- Unknown or lowercase `LOG_LEVEL` falls back to INFO with a warning, without failing.

### LibreNMS client
- Base URL `${LIBRENMS_URL}/api/v0`, auth header `X-Auth-Token`, `LIBRENMS_VERIFY_SSL`,
  `LIBRENMS_TIMEOUT` (seconds, default 30).
- **Path segments**: each argument is percent-encoded as exactly one segment (`/`, `?`, `#`,
  spaces all encoded, value otherwise sent verbatim, surrounding spaces kept). Blank values and
  any `.` or `..` part (backslash counts as a separator) are rejected locally with
  `Invalid path segment: '<value>'` — no request is sent.
- **Redirects** are never followed; they surface as errors naming the target.
- **Error normalization** (non-2xx): LibreNMS-shaped bodies (`{"status":"error","message":…}`)
  pass through unchanged. Other JSON objects get `status: "error"`, keeping their fields; a
  missing `message` becomes `HTTP <code>`, and any other original `status` value moves to
  `upstream_status`. Non-object JSON becomes `{"status":"error","message":"HTTP <code>","body":…}`.
  Non-JSON bodies (proxy error pages) raise with the status code. Non-LibreNMS 400/404 answers on
  paths containing `%2F` add a `hint` about `AllowEncodedSlashes NoDecode`. Exact
  cases: upstream `tests/test_client_errors.py` (34 tests).
- **Retries**: none. The Java port adds retries only for tools marked `retrySafe` in the
  classification. Note that `device_discover` is a `GET` with side effects, so retry safety is
  per tool, not per HTTP method.

### Tool results
- **JSON tools (105)** declare output schema `{"type":"object","additionalProperties":true}` and
  return the LibreNMS body as both text and `structuredContent`.
- **Failures in JSON tools are not MCP errors.** LibreNMS errors, local validation errors and
  exceptions all return `{"status":"error","message":…}` with `isError: false`.
- **Graph tools (5)** — `device_graph`, `port_graph`, `port_group_graph`, `bill_graph`,
  `bill_history_graph` — have no output schema, return `ImageContent` (`image/png`, requested via
  `graph_type=png`), and **do** raise MCP errors (`isError: true`) on failure.
- **Pagination is client-side**: `paginate_list` fetches the full LibreNMS list and slices it,
  adding `count`, `total`, `limit`, `offset`. When no key is given it picks the longest list in the
  response. Defaults are usually `limit=100`, `offset=0`.
- Empty "ok" answers to get-by-ID lookups (alerts, rules, templates, bills, ports) are turned into
  not-found errors (`not_found_if_empty`).
- Name resolution: device-scoped tools check the device exists first (`lookup_device`); location,
  group and poller-group names containing `/` are resolved to IDs (`route_ref`); port names fall
  back to port ID when a proxy mangles encoded slashes (`resolve_port_id`,
  `with_port_id_fallback`).
- Tools call `ctx.info` / `ctx.error`, so MCP clients receive log notifications during calls.

## Compatibility gaps and deviations

| Item | Upstream | Java | Status |
|---|---|---|---|
| SSE transport | supported | dropped | Approved 2026-10-24 |
| Sentry | optional | dropped | Approved 2026-10-24 |
| Default transport | stdio | http | Deviation: deployment target is a container |
| `READ_ONLY_MODE` default | false | true | Security deviation |
| Unauthenticated HTTP | warning only | refused unless loopback and `AUTH_MODE=none` | Security deviation |
| Oxidized config access | read-only | ADMIN | Approved 2026-10-24 (Oxidized not in use; no live fixtures) |
| Rate limit scope | global | per principal | Security deviation |
| Health check probe | Python script | Java-native (no shell/curl in a distroless image) | Implementation detail |
| Live response coverage | — | 37 tools with data, 20 empty-but-valid, 15 LibreNMS error answers | Test instance has one switch and no alerts, rules, bills, groups, locations or routing; recapture after seeding (see below) |
| MCP logging notifications | `ctx.info` / `ctx.error` during calls | Not emitted | Logging capability deprecated in MCP spec 2026-07-28 (SEP-2577); proposed deviation |
| Input validation errors | pydantic message, `isError: true` | SDK JSON Schema validator message, `isError: true` | Wording differs (e.g. `Tool (devices_list) input validation failed: … /limit: must have a minimum value of 1`) |
| Transport error text | httpx exception text (e.g. `All connection attempts failed`) | `Could not reach LibreNMS for GET <path>: <JDK message>` | Wording differs; shape (`status: "error"`) is the same |
| Denied tool call | n/a (hidden tools cannot be called upstream) | `isError: true`, `Tool '<name>' is not permitted: <reason>` | New |

## Upstream test catalog

The transcripts cover each tool's request mapping and basic success/error shape. The behavioral
edge cases below are ported as Java tests module by module in Phase 3 (one Java test per upstream
test, same name in camelCase).

| File | Tests | Covers |
|---|---|---|
| `test_tool_paths.py` | 96 | Path encoding, dot-segment rejection, port-ID fallbacks, ARP search routing, log tools, name→ID resolution, device checks, alert rule add/edit IDs |
| `test_client_errors.py` | 34 | Error-body normalization, redirects, non-JSON bodies, encoded-slash hint |
| `test_utils.py` | 17 | Env parsing, pagination, `unique_match` |
| `test_graphs.py` | 11 | Image conversion, graph error reporting |
| `test_healthcheck.py` | 9 | Probe host selection (wildcard binds, IPv6) |
| `test_bill_graphs.py` | 2 | Bill graph types and parameters |
| `test_sentry_init.py` | 2 | Not ported (Sentry excluded) |

## Live capture coverage

`tools/upstream-extract/capture_live.py` ran the 72 READ, retry-safe, non-Oxidized tools
against the test instance on 2026-10-24. The instance had one polled Cisco C2960L, so tools for
data it does not have returned empty lists or LibreNMS "not found" errors:

- **Empty but valid:** alerts, alert rules, bills, BGP, OSPF, NAC, port security, transceivers,
  services, syslog, alert log, maintenance status.
- **LibreNMS errors (404/400/500):** device groups, port groups, locations, VRF, FDB/MAC lookups,
  BGP session, OSPF ports, bill history graphs.

To fill these in, add to the test instance an alert rule that fires, a bill, a location, a device
group, a port group and a device with BGP/OSPF, then rerun the capture.
