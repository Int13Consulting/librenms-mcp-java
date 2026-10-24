# Architecture

Scope and phasing live in `docs/implementation-plan.md`; upstream behaviour being reproduced is
in `docs/upstream-baseline.md`. This document describes how the Java server is put together.

## Request path

```
MCP client ──HTTPS──▶ TLS ingress ──▶ Javalin (/mcp)
                                        │
                              AuthFilter: Entra JWT | API key ──▶ 401 + WWW-Authenticate
                                        │  Principal{subject, clientId, role}
                                        ▼
                              MCP SDK, stateless Streamable HTTP
                                        │ tools/list, tools/call, search_tools, call_tool
                                        ▼
                              ToolDispatcher
                                ├─ PolicyEngine   read-only → disabled tags → role ≥ access → destructive gate
                                ├─ RateLimiter    per principal
                                └─ AuditLog       mutations and denials
                                        ▼
                              ToolHandler (typed arguments)
                                        ▼
                              LibreNmsClient ──X-Auth-Token──▶ LibreNMS /api/v0
```

stdio mode uses the same server and dispatcher with no HTTP layer and a fixed local principal
whose role comes from configuration (still capped by read-only mode).

Javalin also serves `/health` (liveness, unauthenticated, no LibreNMS call), `/readyz`,
`/.well-known/oauth-protected-resource` and `/metrics`.

## Packages (`ca.int13.librenmsmcp`)

| Package | Responsibility | Depends on |
|---|---|---|
| `config` | Load env and `*_FILE` secrets, validate, refuse insecure combinations | — |
| `mcp` | SDK server setup, transports, list/search/call wiring | `tools`, `authz` |
| `auth` | Entra JWT validation, JWKS cache, API-key check, protected-resource metadata | `config` |
| `authz` | `Principal`, `Access`, `PolicyEngine`, rate limiter | — |
| `tools.*` | One class per upstream module; each binds handlers by upstream name | `librenms`, `tools.support` |
| `tools.support` | Upstream's shared helpers: device checks, name→ID routing, port-ID fallback, graph rendering | `librenms` |
| `search` | BM25 and regex tool search (FastMCP-identical ranking) | `tools` |
| `librenms` | HTTP client, path-segment encoding, error normalization, pagination | `config` |
| `observability` | JSON logging, audit events, metrics | — |

Policy never depends on tools, and tools never see the principal. Authorization happens once, in
the dispatcher, for every entry point (direct call or `call_tool`).

## Tool registry

Every tool is a `ToolSpec`: an upstream `ToolDefinition` (name, description, input/output schema,
annotations, tags), an approved `Classification` (`Access`, `destructive`, `retrySafe`) and a Java
`ToolHandler`. The first two are read from `contracts/upstream-tools.json` and
`contracts/tool-classification.json`, which are packaged into the jar, so tool metadata matches
upstream by construction. A tool module binds handlers by upstream name. Startup fails if a handler
names an unknown or unclassified tool, or binds a name twice.

Tests that tie the registry to the contracts:

- **`ToolRegistryTest`**: every upstream tool is classified, nothing else is, packaged contracts
  equal the repository files, and served tools carry their approved classification.
- **`HttpServerTest.listedToolsMatchUpstreamContracts`**: `tools/list` over HTTP returns upstream's
  description, input schema, output schema and annotations for every served tool.

## Contract tests

`contracts/transcripts/<tool>.json` (synthetic LibreNMS answers, recorded from upstream) and
`contracts/live/<tool>.json` (sanitized answers from the test LibreNMS) hold scenarios of the form
arguments → expected LibreNMS requests and their responses → expected MCP result.
`TranscriptContractTest` replays every scenario for every implemented tool:

1. Script `LibreNmsStub` (the JDK's built-in HTTP server) with the recorded responses, in order.
2. Call the tool through `ToolDispatcher` with a real `LibreNmsClient`.
3. Assert the requests (method, raw path, decoded query pairs, JSON body) and the result
   (`isError`, content, `structuredContent`). Text must match upstream byte for byte, key order
   included; only float formatting may differ.

The same test also replays `contracts/upstream-tests/*.json`: every tool call made by upstream's
own pytest suites (recorded by `capture_tests.py`) with its scripted LibreNMS answers.
`ToolSearchTest` checks search rankings against `contracts/tool-search.json`.

Python is needed only to regenerate the contracts, never to build or test Java.

## Security boundaries

- Entra roles decide what an MCP caller may do. The LibreNMS API token decides what the server
  may do. They are separate, and the server checks the first before using the second.
- Entra access tokens are never forwarded to LibreNMS. The LibreNMS token never leaves the server.
- Oxidized config bodies, tokens and request payloads are never logged. Audit events record
  identifiers and outcomes only.
