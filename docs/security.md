# Security

How LibreNMS MCP is protected, what was reviewed, and what remains the operator's
responsibility.

## Threat model

| Actor | Can | Concern |
|---|---|---|
| Unauthenticated network client | Reach `/mcp`, `/health`, `/readyz`, the OAuth metadata | Probing, DoS, information disclosure |
| Authenticated MCP caller (user or service) | Call tools its role allows | Privilege escalation, tool misuse, reaching other LibreNMS objects |
| The model driving the caller | Choose any tool arguments, possibly prompt-injected | Path traversal into other API routes, mass changes, secret exfiltration |
| A compromised or misbehaving LibreNMS / proxy | Answer with anything | Parser DoS, misleading results, redirects |

Two authorization boundaries are deliberately separate. **Entra roles** decide what a caller may
ask for. **The LibreNMS API token** decides what the server can do. Use a least-privileged
LibreNMS user for the token: the MCP roles cannot exceed it, and it is the backstop if the MCP
layer were bypassed.

## Controls

**Authentication** (`auth`)
- Entra v2 access tokens only: RS256, tenant issuer, `tid`, `ver` 2.0, `aud` = the API client ID,
  `exp`/`nbf` with bounded skew, required `iat`/`sub`/`oid`, `typ` JWT. `alg: none`, HS256 and
  foreign keys fail signature selection. ID tokens and tokens for other APIs (Graph) are rejected.
- Delegated tokens need the configured scope; app-only tokens need an app role and no `scp`;
  `idtyp` must agree when present. Groups grant nothing. `ENTRA_ALLOWED_CLIENT_IDS` can restrict
  client apps. `appRoleAssignmentRequired` stops unassigned users before a token is issued.
- JWKS is cached, refreshed on unknown `kid`, and refreshes are rate limited.
- API keys: at least 32 characters, compared in constant time, assigned one role.
- `AUTH_MODE=none` is refused unless bound to loopback. There is no unauthenticated remote mode.

**Authorization** (`authz`, `mcp.ToolDispatcher`)
- Every call goes through one dispatcher, whatever the entry point (direct `tools/call`,
  `call_tool` under tool search). The SDK's list filter hides tools but does not stop calls, so
  the dispatcher re-checks every call.
- Order: read-only mode (default **on**) → disabled tags → role ≥ tool access → destructive
  gate (`ALLOW_DESTRUCTIVE`, default **off**). The classification of all 110 tools is approved
  data (`contracts/tool-classification.json`); a tool without one cannot be served.
- Hidden tools are invisible to `tools/list` and `search_tools`, and `call_tool` reports them as unknown.

**Input handling** (`librenms`)
- Arguments are validated against upstream's JSON Schema before any request.
- Every path argument is percent-encoded as exactly one segment; blank values and `.`/`..` parts
  (backslash counts as `/`) are refused, so a model cannot steer a request to another route.
- The LibreNMS base URL is fixed by configuration (no SSRF from arguments). Redirects are never followed.
- Writes and side-effecting GETs (`device_discover`) are never retried. The JDK client's hidden
  GET retries are disabled (`jdk.httpclient.redirects.retrylimit=1`).
- Regex tool search has a 1 s budget and a deadline-checking input, against catastrophic backtracking.

**HTTP surface** (`http`)
- `Origin` checked on `/mcp` (DNS rebinding); only `MCP_ALLOWED_ORIGINS` may send one.
- 1 MiB request limit (413), per-principal rate limit (429 + `Retry-After`).
- SDK error bodies are rewritten to plain JSON-RPC errors: the SDK otherwise returns serialized
  exceptions with stack traces for malformed messages.
- `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Cache-Control: no-store`,
  `Referrer-Policy: no-referrer`; no `Server` header.
- `/readyz` and `/health` reveal only status; failure detail is logged, not returned.
- Metrics are on a separate port (off by default) so they never sit behind the public endpoint.

**Secrets and logging** (`config`, `observability`)
- Secrets from env or `*_FILE`; `Config` `toString()` masks them. The LibreNMS token is sent only
  to LibreNMS; Entra tokens are never forwarded.
- Audit events for every write and denial record subject, client, target identifiers and
  payload **field names only**, never values. Oxidized config reads (ADMIN) are audited and never logged.
- Model-supplied values in log lines are bounded and stripped of control characters; JSON logs escape them.
- Metrics labels are bounded (tool, outcome, status class), never identities.

**Runtime**
- Distroless base, jlink runtime, uid 65532, read-only root filesystem, all capabilities dropped,
  `no-new-privileges`. No shell in the image.
- CI: Error Prone, Trivy (dependencies, secrets, misconfiguration, image), CycloneDX SBOM; releases
  carry SBOM and provenance attestations. Dependabot for Maven, Docker and Actions.

## Review findings (2026-10-24)

| Finding | Severity | Status |
|---|---|---|
| JDK HTTP client silently re-sent GETs up to 5×, so `device_discover` could run repeatedly | High | Fixed (Phase 1), tested |
| Jackson 3.1.4 (via MCP SDK): five DoS CVEs (CVE-2026-89407, -89425, -68497, -91776, -91777) | High | Fixed: pinned 3.1.8; image scan clean |
| SDK returned stack traces in 400 bodies for malformed JSON-RPC | Medium | Fixed: response sanitizing, tested |
| `/readyz` (unauthenticated) echoed LibreNMS error text, e.g. redirect targets | Low | Fixed: generic reason, tested |
| Missing security headers | Low | Fixed |
| SDK's transport security validator defaults to no-op (no Origin check) | Medium | Mitigated: our own Origin check |
| SDK's tool filter does not block calls to hidden tools | High if relied on | Mitigated: dispatcher checks every call; tested for all 110 tools |
| Expected failures (unroutable names, port fallbacks) logged as unexpected | Low (log noise) | Fixed |

## Residual risks and operator responsibilities

- **TLS** terminates at your reverse proxy or tunnel; keep the container port private.
- **Pre-auth requests are not rate limited** per IP (the rate limit is per principal). Put your
  proxy's or WAF's IP limits in front for internet exposure. JWT validation is cheap and JWKS
  refresh is rate limited, so the server itself degrades gracefully.
- **Write payloads are passed through to LibreNMS** as upstream does; LibreNMS validates them.
  Keep `READ_ONLY_MODE=true` unless writes are needed, and grant Operator/Admin sparingly.
- **Oxidized configs** can contain device secrets; the tools are Admin-only. Disable them with
  `DISABLED_TAGS=oxidized` if not needed.
- **`LIBRENMS_ALLOW_HTTP` / `LIBRENMS_VERIFY_SSL=false`** expose the LibreNMS token on the network; labs only.
- The API key has one role for everyone who holds it; prefer Entra for people.

## Reporting

Report vulnerabilities privately to the repository owner (GitHub security advisories) rather than
in public issues.
