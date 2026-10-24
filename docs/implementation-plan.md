# LibreNMS MCP Java — Implementation Plan

**Status:** Draft for approval
**Derived from:** `project_plan.md` (review notes in §1)
**Upstream baseline:** `mhajder/librenms-mcp` @ `09ba2f6d393d199ff6bb0b0fdaa824da0f909274` (release 1.12.1, 2026-10-23)

## 0. Decisions captured

| Topic | Decision |
|---|---|
| Target clients (V1) | Claude (Desktop / claude.ai connector), Claude Code / CLI agents, VS Code / Copilot, app-only service agents |
| Transports | Streamable HTTP (primary) + stdio. SSE dropped (deprecated in MCP spec) — documented deviation |
| HTTP stack | Javalin, hosting the official Java MCP SDK transport (compatibility spike in Phase 1) |
| Roles | Reader / Operator / Admin. Operator list proposed by dev in the parity matrix, approved by owner |
| Sprint order | Follows plan §17: Entra + Reader auth before any write tool ships |
| Session model | SDK stateless Streamable HTTP mode — no in-memory session affinity, horizontally scalable, no DB |

## 1. Review findings on `project_plan.md`

1. **Upstream surface is 110 tools** in 17 modules (~7.7k LOC Python, FastMCP 3.x). Tool-count risk is real but bounded.
2. **Classification is tag-only upstream** (`read-only` / `admin`). Six mutating tools carry neither tag: `device_discover`, `device_rename`, `device_eventlog_add`, `port_description_update`, `alert_template_create`, `alert_template_edit`. In upstream read-only mode they are hidden (only `read-only` is enabled), but they would bypass any admin-tag policy. Java will classify every tool explicitly; an unclassified tool fails the build.
3. **Graph tools return PNG `ImageContent`** (`device_graph`, `port_graph`, `port_group_graph`, `bill_graph`, `bill_history_graph`, …). Contract tests must cover binary content, not just JSON.
4. **Tool search** (`TOOL_SEARCH_ENABLED`) replaces `tools/list` with `search_tools` + `call_tool` (BM25 or regex). `call_tool` is the indirect dispatch path — it must re-run authorization. BM25 needs a small in-process implementation (no Lucene needed for ~110 docs).
5. **Sprint order contradiction**: sprints put writes (S3) before Entra (S4); §17 says the reverse. This plan follows §17.
6. **Entra ↔ MCP OAuth interop is the top risk and was scheduled last.** Entra has no Dynamic Client Registration and no Client ID Metadata Document support, and v2 uses `scope=api://<app>/.default` rather than RFC 8707 `resource`. Each interactive client likely needs a pre-registered public client ID; behaviour differs per client. Moved to Phase 1 as a spike.
7. **Per-principal tool visibility vs SDK**: the SDK's tool registry is server-global. Hiding tools per caller in `tools/list` requires either a custom list handler reading the transport context, or a thin JSON-RPC dispatch layer of our own. Spike in Phase 1.
8. **Defaults differ from upstream** (`READ_ONLY_MODE=true`, HTTP transport, auth required). Keep, but list as security deviations in the parity matrix.
9. **Unspecified in plan**: role of static API-key fallback, Sentry, upstream drift process, real Maven coordinates. Resolved in §9.

## 2. Architecture

```
MCP client ──HTTPS──▶ reverse proxy / ingress (TLS)
                          │
                          ▼
   Javalin ─ /mcp ─▶ AuthFilter ─▶ MCP SDK (stateless Streamable HTTP)
     │                 │ Entra JWT | static key → Principal{roles, clientId}
     │                 ▼
     │           ToolDispatcher ── PolicyEngine (read-only, disabled tags, role ≥ tool level)
     │                 │          └─ RateLimiter (per principal)
     │                 │          └─ AuditLog (mutations, denials)
     │                 ▼
     │           ToolHandler (typed args → LibreNmsClient)
     │                 ▼
     │           LibreNmsClient (java.net.http, Jackson, timeouts, GET-only retry)
     │
     ├─ /health   (liveness, no LibreNMS call — matches upstream)
     ├─ /readyz   (config valid + optional LibreNMS reachability)
     ├─ /.well-known/oauth-protected-resource  (RFC 9728)
     └─ /metrics  (Micrometer Prometheus)
stdio mode: same McpServer + dispatcher, no HTTP, principal = local Admin-capped-by-config
```

### Tool model
One declarative registry is the single source of truth for listing, search, policy, audit and the parity matrix:

```java
record ToolSpec(
    String name, String description, JsonNode inputSchema,
    Set<String> tags,            // upstream tags preserved for DISABLED_TAGS
    Access access,               // READ | OPERATE | ADMIN — required, no default
    boolean destructive,         // delete / discovery / config-changing
    ToolHandler handler) {}
```

- Tool metadata (description, input/output schema, annotations, tags) is loaded from the packaged upstream contracts, so it matches upstream by construction; an HTTP test also checks `tools/list` against the contracts. (Changed in Phase 1 from generating schemas from Java records.)
- `Access` is mandatory; a registry test asserts all 110 upstream names are present and classified.

### Package layout
`ca.int13.librenmsmcp`:
`config/ mcp/ auth/ authz/ librenms/ tools/{devices,ports,alerts,inventory,network,health,logs,services,bills,graphs,oxidized,system}/ search/ observability/`

## 3. Phases

Each phase ends deployable. Estimates assume one developer.

### Phase 0 — Baseline capture (3 days) — done
- ✅ `tools/upstream-extract/extract.py` → `contracts/upstream-tools.json` (110 tools, exact FastMCP schemas + statically mapped LibreNMS operations).
- ✅ `tools/upstream-extract/capture.py` → `contracts/transcripts/` (339 golden request/result scenarios, deterministic). These replace hand-built fixtures for request-mapping and error-semantics contract tests.
- ✅ `contracts/tool-classification.json` + `gen_matrix.py` → `docs/parity-matrix.md` (Operator list approved 2026-10-24).
- ✅ `docs/upstream-baseline.md`, `docs/architecture.md`, `NOTICE`.
- ✅ `capture_live.py` → `contracts/live/` (72 sanitized live transcripts; 37 with data). Recapture after seeding the test instance.
- ✅ Oxidized config tools confirmed ADMIN; Operator classification kept despite upstream `destructiveHint` on two tools.

### Phase 1 — Foundation + risk spikes (1 week) — done 2026-10-24
Spikes:
1. ✅ **Javalin × SDK transport** and 2. ✅ **per-principal `tools/list`**: `docs/spikes/01-transport-and-tool-filtering.md`. No fallback needed.
3. ✅ **Entra × clients** (claude.ai and app-only pass; Claude Code and VS Code still to test): `docs/spikes/02-entra-client-interop.md`. Key constraint: the public MCP URL must be a tenant-verified-domain identifier URI on the API app.

Built (113 tests, 85% line coverage, authz 95%):
- ✅ Maven project, Java 25 release, MCP SDK 2.1.0, Javalin 7.2.3, Jackson 3, JaCoCo, JUnit 6, AssertJ. Enforcer with dependency convergence. Spotless/Error Prone moved to Phase 5.
- ✅ Config loader (env + `*_FILE`), every problem reported at once, refuses `AUTH_MODE=none` off loopback, plain-http LibreNMS without `LIBRENMS_ALLOW_HTTP`, and upstream's `MCP_HTTP_BEARER_TOKEN` (with guidance).
- ✅ `LibreNmsClient` reproducing upstream path encoding, error normalization and exception messages; retries only for retry-safe tools via a `ScopedValue`.
- ✅ Tools `ping`, `system_info`, `devices_list`, `device_get`, passing all 19 transcript scenarios byte for byte (15 upstream, 4 live).
- ✅ Policy engine and dispatcher (read-only, disabled tags, role, destructive gate, audit log lines).
- ✅ HTTP (`/mcp`, `/health`, `/readyz`, API-key auth) and stdio transports, verified against the live LibreNMS.
- ✅ Distroless non-root image with Java health probe; `compose.yaml` with file secrets, read-only rootfs, dropped capabilities; verified healthy against the live LibreNMS.
- ✅ CI workflow (`.github/workflows/ci.yml`), ready for when the repository exists.

Decisions and findings during the build:
- **Tool metadata is data, not code.** Descriptions, schemas, annotations and tags load from `contracts/upstream-tools.json` and access levels from `contracts/tool-classification.json` (both packaged in the jar). Java supplies only handlers. Schemas match upstream by construction, so the planned jsonschema-generator step is dropped.
- **No WireMock.** WireMock's Jetty 12.0 conflicts with Javalin's Jetty 12.1, and the JDK's built-in HTTP server is enough for transcript replay (`LibreNmsStub`).
- **The JDK HTTP client silently retries GETs** (up to 5 attempts) when a connection drops before a response, which would repeat side-effecting GETs such as `device_discover`. Disabled with `-Djdk.httpclient.redirects.retrylimit=1`, set in `Main`, the image entrypoint and Surefire, and covered by a test.
- **stdio needs explicit shutdown**: the SDK transport does not end the process on stdin EOF; `Main` detects EOF and exits.
- **Input validation** is the SDK's JSON Schema validator; its messages differ from pydantic's (deviation, see baseline).

### Phase 2 — Entra auth + Reader policy (1–1.5 weeks) — done 2026-10-24, except Claude Code / VS Code client checks
- ✅ `EntraAuthenticator` (Nimbus): RS256 only, tenant v2 issuer, `tid`, `ver` 2.0, `aud` = API client ID (or `api://`), `exp`/`nbf` with configurable skew, required `iat`/`sub`/`oid`, `typ` JWT. JWKS is cached, refreshes on an unknown `kid` (rate limited) and retries once.
- ✅ Authorization from claims: delegated tokens need `scp` with `ENTRA_REQUIRED_SCOPE`; app-only tokens need `roles` and no `scp`; `idtyp`, when present, must agree. The highest `LibreNMS.*` role wins. Groups are ignored. Optional `ENTRA_ALLOWED_CLIENT_IDS`.
- ✅ `AUTH_MODE=entra`, `apikey`, `entra,apikey`, `none` (loopback). API key is checked first; anything else falls through to Entra.
- ✅ RFC 9728 metadata at `/.well-known/oauth-protected-resource[/mcp]`. RFC 6750 challenges: 401 with no error code when no credentials were sent, 401 `invalid_token`, 403 `insufficient_scope`. Reasons are logged server-side only.
- ✅ Origin check on `/mcp` (`MCP_ALLOWED_ORIGINS`; the SDK's own validator defaults to no-op).
- ✅ Per-principal sliding-window rate limit (default 100/min), 429 with `Retry-After`; unauthenticated requests are not counted.
- ✅ `tools/entra/register.sh` (production registration: verified-domain check, https identifier URI, `idtyp` optional claim, assignment required, per-client apps) and `docs/entra-setup.md`.
- ✅ Negative security suite: 30 token tests (`EntraAuthenticatorTest`, every case in §12) plus 9 HTTP-level tests (`EntraHttpTest`). Mutation-checked.
- ✅ Live: the production container behind `https://librenms-mcp-spike.int13.ca` validated real Entra app-only tokens, rejected Graph tokens, and served live LibreNMS data.
- ✅ claude.ai against the production server (delegated Admin, `devices_list` with live data), confirmed in the new per-request access log.
- ⏳ Claude Code and VS Code sign-in.

### Phase 3 — Read-only parity (1.5–2 weeks) — done 2026-10-24
- ✅ All 73 READ tools across 16 modules, plus upstream's shared helpers ported line by line: device checks, name→ID routing (`route_ref`), port-ID fallback, `unique_match`, client-side pagination, Python `str`/`repr`/`casefold` text, and `ipaddress.ip_network` parsing for ARP.
- ✅ Graph tools return PNG `ImageContent`; their failures are MCP errors with upstream's messages.
- ✅ **Upstream's behavioural tests are now contracts**: `capture_tests.py` records each tool call in upstream's pytest suites (241 tests → 227 calls, 222 files) with its scripted LibreNMS answers. Java replays them, so name/ID collisions, slash routing, fallbacks and device checks are enforced without hand-porting each test.
- ✅ Tool search (`TOOL_SEARCH_*`): `search_tools` + `call_tool`, BM25 and regex, reproducing FastMCP's rankings exactly (41 recorded queries/patterns). Search only sees tools the caller may use, `call_tool` goes through the dispatcher, and regex matching has a time budget against catastrophic backtracking.
- ✅ Arguments are validated against the upstream schema in the dispatcher too, so stdio and HTTP reject the same input before any request.
- ✅ Found and fixed while porting: upstream serves a `title` and `_meta` (`fastmcp.tags`) for every tool, and lists tools in registration order. Both were missing from the contracts and are now served identically.
- ✅ 669 tests, 89% line coverage; every tool module at 97–100%. Live smoke test against the test LibreNMS, including real PNG graphs.

### Phase 4 — Write tools + safety (1 week) — done 2026-10-24
- ✅ All 37 OPERATE/ADMIN tools ported (110/110), passing every transcript scenario and every recorded upstream test call, including payload ID checks for rule/template add and edit, device-group routing for writes, `service_add` IP defaulting and `device_update`'s field/data form.
- ✅ Audit events for every write and every denial: tool, subject, display name, client, auth method, access level, **target identifiers**, **payload field names (never values)**, outcome; one line, control characters neutralized, request ID from the log context. Oxidized config reads are ADMIN, so they are audited too.
- ✅ Destructive tools require Admin **and** `ALLOW_DESTRUCTIVE=true`; read-only mode overrides every role.
- ✅ `WriteToolPolicyTest`: for each of the 37 tools, with its recorded arguments, denial in read-only mode, at every lower role and without `ALLOW_DESTRUCTIVE`, with no request reaching LibreNMS; allowed at the required role. Mutation-checked (removing the destructive gate fails exactly the 14 destructive tools, removing read-only fails all 37).
- ✅ Retries: no write is ever retried (only GET tools classified retry-safe; `device_discover` excluded), and the JDK's hidden GET retries are disabled.
- ✅ Live run against the test LibreNMS 26.11 (approved 2026-10-24), via a local read-write instance and `tools/e2e/live_writes.py`: 37/37 steps pass. Location, alert rule, device group and bill lifecycles clean up after themselves. Alert template, port group and eventlog writes leave residue (upstream has no delete tools). Device maintenance, port description update + restore, and discovery were run on the lab switch. Service tools were skipped because the lab has no service check plugins; they are covered by contracts.
- Finding: upstream's `alert_rule_add`/`alert_rule_edit` descriptions document the legacy `delay`/`interval`/`mute` fields. LibreNMS 26.x needs `alert_operation_id` (or `operations` with transports), so models following the description will get "Each operation must include at least one transport". Kept verbatim for parity; worth reporting upstream.

### Phase 5 — Operations + release (1 week) — done 2026-10-24
- ✅ JSON logs (`LOG_FORMAT=json`, image default) with structured audit key-values; per-request access log; logback status kept off stderr.
- ✅ Prometheus metrics on a separate port (`MCP_METRICS_PORT`, off by default): tool calls by tool/decision/outcome, HTTP rejections by reason, LibreNMS request latency by status class, JVM. No identities in labels.
- ✅ Image: jlink runtime on `distroless/base-debian13:nonroot`, 342 → 191 MB; verified health, Entra (JWKS over TLS), graphs, stdio and metrics on the trimmed runtime. `java.instrument` kept for an optional OpenTelemetry agent.
- ✅ Quality profile on JDK 25 (CI and image toolchain): Spotless (palantir-java-format) check and Error Prone; no Error Prone errors, 5 cosmetic warnings.
- ✅ CI: build/test/format/static analysis, generated-docs check, CycloneDX SBOM, Trivy (dependencies, secrets, misconfiguration, image). Release on `v*.*.*` tags: GHCR with semver tags, SBOM + provenance, build attestation, GitHub release. Dependabot. CI-friendly `${revision}` versioning.
- ✅ Security review (`docs/security.md`): Jackson DoS CVEs fixed by pinning 3.1.8 (image scan now clean), SDK stack traces in error bodies sanitized, security headers, `/readyz` made generic, expected failures no longer logged as unexpected.
- ✅ Docs: `security.md`, generated `tool-reference.md`, deployment (logs, metrics, tracing, releases, rollback, troubleshooting).
- ⏳ Needs the GitHub repository: first CI run, first tagged release.

## 3a. Definition of done (plan §16)

| Criterion | Status |
|---|---|
| All baseline tools implemented or approved as excluded | ✅ 110/110 |
| Java contracts pass automated parity tests | ✅ 638 replayed scenarios (synthetic, live, upstream tests) + schema/title/_meta/order checks + search rankings |
| No Python runtime required | ✅ (Python only regenerates contracts) |
| Entra works for approved interactive and service clients | ✅ claude.ai and client credentials; ⏳ Claude Code and VS Code sign-in not yet run |
| Reader / Operator / Admin enforced | ✅ per tool, tested for all 37 write tools |
| Read-only mode and disabled tags preserved | ✅ |
| Production HTTP authenticated and behind TLS | ✅ auth enforced; TLS at proxy/tunnel |
| Repeatable Docker Compose deployment | ✅ |
| Secrets externalized | ✅ env or `*_FILE` |
| Logs, audit, metrics, health | ✅ |
| CI publishes tested, scanned images | ✅ workflows written; ⏳ first run needs the repository |
| Docs: Entra, configuration, deployment, testing, troubleshooting, rollback | ✅ |
| No unresolved critical/high findings | ✅ (`docs/security.md`) |

## 4. Proposed Operator set (for approval in Phase 0)
Operational, reversible, non-destructive:
`alert_acknowledge`, `alert_unmute`, `device_set_maintenance`, `devicegroup_set_maintenance`, `location_set_maintenance`, `port_description_update`, `device_eventlog_add`.
Everything else that writes → Admin; deletes, `device_discover`, `device_add`, `bgp_session_edit`, alert rule/template changes → Admin + destructive flag.

## 5. Configuration (additions to plan §11)
```dotenv
MCP_TRANSPORT=http            # http | stdio
AUTH_MODE=entra               # entra | apikey | entra,apikey | none (loopback only)
MCP_API_KEY_FILE=/run/secrets/mcp_api_key
MCP_API_KEY_ROLE=Reader
ENTRA_REQUIRED_SCOPE=access_as_user
ALLOW_DESTRUCTIVE=false
TOOL_SEARCH_ENABLED=false
TOOL_SEARCH_STRATEGY=bm25
TOOL_SEARCH_MAX_RESULTS=5
TRUSTED_PROXIES=              # CIDRs allowed to set X-Forwarded-*
```
Upstream names (`LIBRENMS_URL`, `LIBRENMS_TOKEN`, `READ_ONLY_MODE`, `DISABLED_TAGS`, `RATE_LIMIT_*`, `TOOL_SEARCH_*`) are kept identical where they exist.

## 6. Key dependencies
Java MCP SDK 2.1.0 (`io.modelcontextprotocol.sdk:mcp`, Jackson 3), Javalin 7.2.3, Nimbus JOSE+JWT 10.10 (Phase 2), SLF4J + Logback, Micrometer (Phase 5); test: JUnit 6, AssertJ, JDK HTTP server as LibreNMS stub.

## 7. Risks (delta from plan §15)
| Risk | Mitigation |
|---|---|
| A target client can't complete Entra OAuth | Phase 1 spike; document per-client config; API-key fallback for that client if needed |
| Javalin servlet API mismatch with SDK | Spike 1 fallback handler |
| SDK can't filter `tools/list` per caller | Spike 2 fallback JSON-RPC layer |
| Schema drift from Python (pydantic vs Java generator) | Contract diff test; approved deviations only |
| Upstream releases during port | Baseline pinned; monthly diff of `upstream-tools.json` tracked as issues |

## 8. Definition of done
As plan §16, plus: registry test proves 110/110 tools present-or-excluded and classified; per-client Entra flows recorded in `docs/entra-setup.md`.

## 9. Resolved items
- Maven coordinates / package: `ca.int13:librenms-mcp`, `ca.int13.librenmsmcp`.
- GitHub repo / GHCR org: owner creates the repo; CI/GHCR config targets it once it exists.
- Test LibreNMS instance + test Entra tenant: available (owner to supply URLs/credentials for Phase 1 spike 3 and Phase 3 fixtures).
- Sentry: not needed — excluded from the port and listed as an approved exclusion in the parity matrix.
