# Deployment

A single container on a VM, authenticating with Entra ID (`docs/entra-setup.md`), an API key, or
both.

## Secrets

```sh
install -d -m 700 secrets
printf '%s' "<librenms api token>" > secrets/librenms_token
openssl rand -hex 32 | tr -d '\n' > secrets/mcp_api_key
chmod 644 secrets/librenms_token secrets/mcp_api_key
```

Compose mounts file secrets with their host permissions, and the container runs as uid 65532, so
the files must be world-readable. The `700` directory is what keeps other host users out. Never
commit `secrets/` (it is git-ignored).

Use a LibreNMS API token belonging to a user with the least privilege that covers the tools you
enable. The MCP roles limit what callers may ask for, and the LibreNMS token limits what the server
can actually do. They are separate boundaries.

## Run

```sh
export LIBRENMS_URL=https://librenms.example
docker compose up -d
docker compose ps        # STATUS shows (healthy) once /health answers
curl -s localhost:8000/readyz
```

The compose file publishes port 8000 on `127.0.0.1` only. Put a TLS-terminating reverse proxy
(nginx, Caddy, Traefik, Cloudflare Tunnel) in front of it for remote clients. With Entra (Phase 2)
the public URL must be on a tenant-verified domain (see `docs/spikes/02-entra-client-interop.md`).

The container runs as non-root with a read-only root filesystem, all capabilities dropped and
`no-new-privileges`.

## Settings that need care

- `LIBRENMS_ALLOW_HTTP=true` sends the LibreNMS token unencrypted. Use it for labs only.
- `READ_ONLY_MODE=false` exposes write tools to callers whose role permits them.
  `ALLOW_DESTRUCTIVE=true` also exposes deletes, discovery and rule/template changes to admins.
- `AUTH_MODE=none` is refused unless `MCP_HTTP_HOST` is loopback.

## Logs

The image logs JSON to stderr (`LOG_FORMAT=json`): one object per line with `timestamp`,
`level`, `loggerName`, `mdc.requestId` and `formattedMessage`. Audit events come from logger
`audit` with key-values `event`, `decision`, `tool`, `subject`, `name`, `client`, `via`, `access`,
`target`, `payload_keys` and `outcome`. Route them with `loggerName == "audit"`. Every `/mcp`
request is logged with status, duration, subject and client. `X-Request-Id` is accepted from the
proxy or generated, and echoed.

## Metrics

Set `MCP_METRICS_PORT=9464` and scrape `http://<container>:9464/metrics` from inside the Docker
network (do not publish it). Series:

| Metric | Labels |
|---|---|
| `mcp_tool_calls_seconds` (count/sum/max) | `tool`, `decision` (allowed/denied), `outcome` (ok/error/librenms_error/denied) |
| `mcp_http_rejections_total` | `reason` (no_credentials, invalid_token, insufficient_scope, forbidden_origin, rate_limited) |
| `librenms_requests_seconds` | `method`, `status` (2xx…5xx, error) |
| `jvm_*`, `process_*`, `system_*` | standard |

## Tracing (optional)

The runtime includes `java.instrument`, so the OpenTelemetry Java agent can be attached without
rebuilding: mount the agent jar and set
`JAVA_TOOL_OPTIONS=-javaagent:/otel/opentelemetry-javaagent.jar` with the usual `OTEL_*`
variables. It traces Jetty requests and the JDK HTTP client calls to LibreNMS.

## Releases, upgrade and rollback

Pushing a tag `vX.Y.Z` runs `.github/workflows/release.yml`: tests, then publishes
`ghcr.io/<owner>/<repo>:X.Y.Z` (plus `X.Y` and `X`) with SBOM and provenance attestations, and a
GitHub release. Deployment stays manual. Pin `image:` in `compose.yaml` to a full version:

```yaml
    image: ghcr.io/<owner>/librenms-mcp:1.0.0
```

Upgrade: change the tag, then `docker compose pull && docker compose up -d`. Roll back the same way
with the previous tag. The server keeps no state, so nothing needs migrating. Verify a release
image before deploying with `gh attestation verify oci://ghcr.io/<owner>/librenms-mcp:1.0.0 --owner <owner>`.

## Troubleshooting

| Symptom | Cause |
|---|---|
| Container exits with code 2 | Configuration error. `docker compose logs` lists every problem |
| Log `Not ready: … HTTP 401` | Wrong or revoked LibreNMS token |
| Log `Not ready: … redirected to https://…` | `LIBRENMS_URL` uses http but LibreNMS redirects to https; use the https URL |
| Tool errors mention `AllowEncodedSlashes` | Apache in front of LibreNMS rejects `%2F` in names like `Gi0/1`; set `AllowEncodedSlashes NoDecode` |
| 401 from `/mcp` | Missing or invalid credentials; the server log line `Rejected POST /mcp: …` gives the reason |
| 403 `forbidden_origin` | A browser-based client sent an `Origin` not in `MCP_ALLOWED_ORIGINS` |
| 429 | The caller exceeded `RATE_LIMIT_MAX_REQUESTS` per window; honour `Retry-After` |
| `/readyz` 503 `librenms_unreachable` / `librenms_error` | See the `Not ready:` log line for the cause |
| 403 from Cloudflare before reaching the server | Cloudflare bot protection blocks some default user agents (e.g. Python `urllib`); set a user agent or add a WAF skip rule for `/mcp` |
