# LibreNMS MCP Server (Java)

A Java 25 port of [mhajder/librenms-mcp](https://github.com/mhajder/librenms-mcp) that exposes the
LibreNMS API to MCP clients over Streamable HTTP or stdio. Tool names, descriptions and schemas
match upstream exactly. Per-tool authorization comes from an approved classification, and
callers sign in with Microsoft Entra ID or an API key.

**Status:** all 110 upstream tools and tool search are ported, hardened and release-ready
(Phases 0–5 of `docs/implementation-plan.md`). Entra ID and API-key auth
work. See `docs/parity-matrix.md` for per-tool status and `docs/entra-setup.md` for Entra.

## Quick start

```sh
mvn verify                               # build, unit + contract tests, coverage (target/site/jacoco);
                                         # on JDK 25 also format check (Spotless) and Error Prone
docker compose up -d                     # see docs/deployment.md for the secrets it expects
```

Run from the jar (needs Java 25+):

```sh
LIBRENMS_URL=https://librenms.example LIBRENMS_TOKEN_FILE=./secrets/librenms_token \
AUTH_MODE=apikey MCP_API_KEY_FILE=./secrets/mcp_api_key \
java -Djdk.httpclient.redirects.retrylimit=1 -jar target/librenms-mcp-*.jar
```

Claude Code over stdio:

```sh
claude mcp add librenms -e MCP_TRANSPORT=stdio -e LIBRENMS_URL=https://librenms.example \
  -e LIBRENMS_TOKEN_FILE=$PWD/secrets/librenms_token -- java -jar $PWD/target/librenms-mcp-0.1.0-SNAPSHOT.jar
```

## Configuration

Any secret can be given as `<NAME>_FILE` (path to a file) instead of `<NAME>`.

| Variable | Default | |
|---|---|---|
| `LIBRENMS_URL` | required | Base URL; `/api/v0` is appended |
| `LIBRENMS_TOKEN` | required | LibreNMS API token. Use a least-privileged LibreNMS user |
| `LIBRENMS_VERIFY_SSL` | `true` | |
| `LIBRENMS_TIMEOUT` | `30` | Seconds |
| `LIBRENMS_ALLOW_HTTP` | `false` | Allow a plain-http `LIBRENMS_URL` (the token is then sent unencrypted) |
| `MCP_TRANSPORT` | `http` | `http` (alias `streamable-http`) or `stdio`. SSE is not supported |
| `MCP_HTTP_HOST` / `MCP_HTTP_PORT` | `127.0.0.1` / `8000` | The image sets `0.0.0.0` |
| `AUTH_MODE` | required for http | `entra`, `apikey`, `entra,apikey`, or `none` (loopback only) |
| `MCP_PUBLIC_URL` | | Public https base URL (no path); required for `entra` |
| `ENTRA_TENANT_ID` / `ENTRA_CLIENT_ID` | | Tenant and API app client ID; required for `entra` |
| `ENTRA_ALLOWED_CLIENT_IDS` | | Accept only tokens issued to these client apps |
| `ENTRA_REQUIRED_SCOPE` | `access_as_user` | Delegated scope user tokens must carry |
| `ENTRA_CLOCK_SKEW_SECONDS` | `60` | |
| `ENTRA_AUTHORITY` | `https://login.microsoftonline.com` | Sovereign clouds |
| `MCP_ALLOWED_ORIGINS` | | Browser origins allowed on `/mcp`; any other `Origin` gets 403 |
| `RATE_LIMIT_ENABLED` | `true` | Per authenticated caller |
| `RATE_LIMIT_MAX_REQUESTS` / `RATE_LIMIT_WINDOW_MINUTES` | `100` / `1` | Sliding window |
| `MCP_API_KEY` | | At least 32 characters, sent as `Authorization: Bearer <key>` |
| `MCP_API_KEY_ROLE` | `reader` | `reader`, `operator` or `admin` |
| `MCP_LOCAL_ROLE` | `reader` | Role for stdio and `AUTH_MODE=none` callers |
| `READ_ONLY_MODE` | `true` | Only READ tools, whatever the role |
| `ALLOW_DESTRUCTIVE` | `false` | Allow destructive tools (still admin-only) |
| `DISABLED_TAGS` | | Comma-separated upstream tags to hide (e.g. `alert,bills`) |
| `TOOL_SEARCH_ENABLED` | `false` | Replace the tool list with `search_tools` + `call_tool` |
| `TOOL_SEARCH_STRATEGY` | `bm25` | `bm25` (natural language) or `regex` |
| `TOOL_SEARCH_MAX_RESULTS` | `5` | |
| `MCP_MAX_REQUEST_BYTES` | `1048576` | |
| `LOG_LEVEL` | `INFO` | Unknown values fall back to INFO |
| `LOG_FORMAT` | `text` (`json` in the image) | `json` emits one JSON object per line, audit fields as key-values |
| `MCP_METRICS_PORT` | off | Prometheus `/metrics` on its own port (never behind `/mcp`) |

## Endpoints

| Path | Auth | |
|---|---|---|
| `POST /mcp` | yes | MCP Streamable HTTP (stateless) |
| `GET /health` | no | Liveness; never calls LibreNMS |
| `GET /readyz` | no | Readiness; pings LibreNMS |
| `GET /.well-known/oauth-protected-resource[/mcp]` | no | RFC 9728 metadata (Entra only) |
| `GET /metrics` on `MCP_METRICS_PORT` | no | Prometheus; bind internally |

## Layout

| Path | |
|---|---|
| `src/main/java/ca/int13/librenmsmcp` | `config`, `authz`, `librenms` (API client), `tools` (registry and handlers), `mcp` (SDK wiring and dispatcher), `http` |
| `contracts/` | Upstream tool contracts, approved classification, golden transcripts. The first two are packaged in the jar and drive tool metadata |
| `tools/upstream-extract/` | Dev-only Python that regenerates `contracts/` from upstream |
| `docs/` | `deployment.md`, `entra-setup.md`, `security.md`, `tool-reference.md`, `parity-matrix.md`, `architecture.md`, `upstream-baseline.md`, `implementation-plan.md`, `spikes/` |
| `tools/e2e/live_writes.py` | Live write test against a real LibreNMS through the server (disposable fixtures) |

## License

MIT. Derived from librenms-mcp by Mateusz Hajder; see `NOTICE`.
