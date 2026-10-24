# Spike 3: Microsoft Entra ID × MCP clients

**Date:** 2026-10-24  **Verdict:** ✅ Works with a verified-domain identifier URI. claude.ai and
app-only clients pass. Claude Code and VS Code not yet tested.

## Setup

- Tenant `28fcbfbd-c451-4133-9785-7684129851a5` (Int13 Consulting). Registrations come from
  `tools/entra/register-spike.sh`; IDs and secrets are in `secrets/entra-spike.env` (git-ignored).
- Server: Javalin + SDK stateless transport (spike 1), Nimbus JOSE+JWT 10.10, published through a
  named Cloudflare tunnel at `https://librenms-mcp-spike.int13.ca/mcp`.

## The key finding: Entra and the RFC 8707 `resource` parameter

MCP clients send `resource=<MCP server URL>` along with the scope from the protected-resource
metadata. Entra v2 rejects any request whose `resource` is not an identifier URI of the app that
owns the scope:

> AADSTS9010010: The resource parameter provided in the request doesn't match with the requested scopes.

This is what claude.ai hit with `api://<appId>` scopes and a `*.trycloudflare.com` URL.

**Fix:** add the public MCP URL itself as an Application ID URI on the API app, e.g.
`https://librenms-mcp-spike.int13.ca/mcp`. Entra only allows `https` identifier URIs on a domain
verified in the tenant, and paths are allowed. Probing the authorize endpoint showed:

| `scope` | `resource` | Result |
|---|---|---|
| `api://<appId>/access_as_user` | tunnel URL (not an identifier URI) | AADSTS9010010 |
| `https://…int13.ca/mcp/access_as_user` | `https://…int13.ca/mcp` | ✅ sign-in page |
| `api://<appId>/access_as_user` | `https://…int13.ca/mcp` | ✅ sign-in page |

Consequences for the product:
- **The public MCP URL must be on a tenant-verified domain and registered as an identifier URI.**
  `docs/entra-setup.md` and startup validation must spell this out. A random hostname will not work.
- Protected-resource metadata advertises `scopes_supported: ["<MCP URL>/access_as_user"]`.
- No OAuth proxy is needed, so the server stays a pure resource server.

## Results

| Client | Flow | Result | Token as seen by the server |
|---|---|---|---|
| Service (client credentials) | app-only, secret | ✅ Reader sees only `whoami`; `admin_tool` denied | `aud`=API client ID, `ver` 2.0, `roles:[LibreNMS.Reader]`, no `scp`, `azp`=client, `azpacr` 1 |
| claude.ai custom connector | auth code + PKCE, confidential client | ✅ Connected, both tools listed and called as Admin | `aud`=API client ID, `ver` 2.0, `scp: access_as_user`, `roles:[LibreNMS.Admin]`, `azp`=claude.ai client, `azpacr` 1, `preferred_username` |
| Claude Code 2.1.310 | auth code + PKCE, public client, `--client-id`, `--callback-port 8765` | ⏳ Not yet tested | |
| VS Code | Microsoft auth provider, pre-authorized first-party client | ⏳ Not yet tested | |
| Microsoft Graph token (same app) | — | ✅ Rejected (signature/audience) | |
| Tampered signature | — | ✅ Rejected | |

claude.ai setup: Settings → Connectors → Add custom connector → URL, plus Advanced settings with
the claude.ai client ID and secret. Its redirect URI is `https://claude.ai/api/mcp/auth_callback`.
Using a client ID registered without that redirect gives AADSTS50011, which is the useful clue
when someone pastes the wrong ID.

## Validation rules confirmed (carry into Phase 2)

- Issuer `https://login.microsoftonline.com/<tenant>/v2.0`, `tid` = tenant, `ver` = `2.0`, RS256
  only, `typ` JWT, keys from `…/discovery/v2.0/keys`. Required claims: `exp`, `iat`, `nbf`, `oid`, `sub`.
- `aud` is the API's **client ID GUID** for v2 tokens, whichever identifier URI the client used.
  Accept the GUID (and `api://<GUID>` defensively). Do not accept the https identifier URI as
  `aud` unless it is ever observed.
- Delegated vs app-only: delegated tokens carry `scp`; app-only carry `roles` without `scp`. App
  tokens do not include `idtyp` by default. Add the `idtyp` optional claim in the registration so
  the distinction is explicit rather than inferred.
- Roles arrive in the access token for delegated **and** app-only callers, so one role-mapping
  path covers both.
- `appRoleAssignmentRequired=true` on the API service principal means unassigned users cannot get a
  token at all, which gives defence in depth before our own policy runs.
- Every MCP message is a separate HTTP request in stateless mode, so tokens are validated on every
  request (Nimbus caches JWKS). That's fine at this scale.

## Implementation notes

- Javalin 7 needs `config.jsonMapper(new JavalinJackson3())` for `ctx.json` when only Jackson 3 is
  on the classpath.
- `WWW-Authenticate: Bearer resource_metadata="<url>/.well-known/oauth-protected-resource/mcp", scope="<scope>"`
  is sufficient for claude.ai discovery. Serve the metadata at both the root and `/mcp`-suffixed
  well-known paths.

## Cleanup (when the spike apps are no longer needed)

```sh
for id in $(az ad app list --display-name "LibreNMS MCP (spike)" --query '[].appId' -o tsv); do az ad app delete --id "$id"; done
cloudflared tunnel delete librenms-mcp-spike
# then delete the librenms-mcp-spike.int13.ca CNAME in the Cloudflare dashboard
```
