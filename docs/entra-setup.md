# Microsoft Entra ID setup

The MCP server is an OAuth 2.0 **protected resource**. MCP clients sign users in with Entra and
send the resulting access token. The server validates it and maps its app roles to Reader,
Operator or Admin. The server never sees passwords and never forwards Entra tokens to LibreNMS.

## 1. Before you start

- **A public HTTPS URL on a verified domain of your tenant**, e.g. `https://mcp.example.com`.
  Clients send `<URL>/mcp` to Entra as the OAuth `resource`, and Entra only accepts it if it is
  an identifier URI of the API app. Identifier URIs must be on a verified domain. A random hostname
  (e.g. `*.trycloudflare.com`) fails with **AADSTS9010010**. See
  `docs/spikes/02-entra-client-interop.md`.
- `az login --tenant <tenant>` as someone who can create app registrations and grant admin consent
  (Application Administrator or higher).

## 2. Register

```sh
PUBLIC_URL=https://mcp.example.com ASSIGN_SELF=admin tools/entra/register.sh
```

This creates the API app ("LibreNMS MCP") and one client app per MCP client:

| Registration | What it is |
|---|---|
| **LibreNMS MCP** | The API. Identifier URIs `api://<appId>` and `<PUBLIC_URL>/mcp`. v2 tokens, delegated scope `access_as_user`, app roles `LibreNMS.Reader`/`Operator`/`Admin`, optional claim `idtyp`. **Assignment required**: only users, groups and apps given a role can get a token. VS Code's Microsoft client is pre-authorized for the scope |
| **… - Claude Code** | Public client; redirect `http://localhost:8765/callback` (`CALLBACK_PORT`) |
| **… - claude.ai** | Web client with a secret; redirect `https://claude.ai/api/mcp/auth_callback` |
| **… - Service** | Confidential client for app-only (client-credentials) access; assigned `SERVICE_ROLE` (default reader) |

Admin consent is granted for the delegated clients. IDs and secrets are written to
`secrets/entra.env` (mode 600, git-ignored). Choose clients with `CLIENTS=claude-ai,service`, etc.

### Doing it in the portal instead

1. **App registrations → New registration** "LibreNMS MCP", single tenant.
2. **Manifest**: set `"requestedAccessTokenVersion": 2`.
3. **Expose an API**: set the Application ID URI to `<PUBLIC_URL>/mcp`. Also keep or add
   `api://<appId>`, which the service client uses. Add scope `access_as_user` (admins and users).
   Add authorized client `aebc6443-996d-45c2-90f0-388ff96faa56` (VS Code) for that scope.
4. **App roles**: `LibreNMS.Reader`, `LibreNMS.Operator`, `LibreNMS.Admin`, each with allowed member types *Users/Groups + Applications*.
5. **Token configuration → Add optional claim → Access → `idtyp`**.
6. **Enterprise applications → LibreNMS MCP → Properties → Assignment required = Yes**.
7. Client apps: register each as above, add the `access_as_user` permission, and **Grant admin consent**.

## 3. Assign roles

The highest LibreNMS role in a token wins. Group membership alone grants nothing: assign the group
to an app role.

```sh
API_SP=$(az ad sp show --id <API client id> --query id -o tsv)
az rest --method POST --url "https://graph.microsoft.com/v1.0/servicePrincipals/$API_SP/appRoleAssignedTo" \
  --body "{\"principalId\":\"<user, group or service principal object id>\",\"resourceId\":\"$API_SP\",\"appRoleId\":\"<ENTRA_ROLE_*_ID>\"}"
```

Or use the portal: **Enterprise applications → LibreNMS MCP → Users and groups → Add**.

## 4. Configure the server

```dotenv
AUTH_MODE=entra                 # or entra,apikey to keep an API key for automation
MCP_PUBLIC_URL=https://mcp.example.com
ENTRA_TENANT_ID=<tenant id>
ENTRA_CLIENT_ID=<API app client id>
# Optional
ENTRA_ALLOWED_CLIENT_IDS=<claude.ai id>,<claude code id>   # refuse tokens issued to any other client app
ENTRA_REQUIRED_SCOPE=access_as_user
ENTRA_CLOCK_SKEW_SECONDS=60
ENTRA_AUTHORITY=https://login.microsoftonline.com          # e.g. https://login.microsoftonline.us for US Gov
```

What the server accepts: RS256 v2 access tokens from
`<authority>/<tenant>/v2.0` with `tid` = tenant, `aud` = API client ID, valid `exp`/`nbf`. User
tokens must have `scp` containing `access_as_user`. App tokens must have `roles` and no `scp`. Both
need a `LibreNMS.*` role. Everything else gets 401 `invalid_token` or 403 `insufficient_scope`.
The reason is logged server-side only.

The server publishes RFC 9728 metadata at
`<PUBLIC_URL>/.well-known/oauth-protected-resource/mcp` and points to it from every 401, which is
how clients discover Entra.

## 5. Connect clients

| Client | Setup | Verified |
|---|---|---|
| **claude.ai / Claude Desktop** | Settings → Connectors → Add custom connector → `<PUBLIC_URL>/mcp`; Advanced settings: claude.ai client ID and secret | ✅ 2026-10-24 (production server) |
| **Claude Code** | `claude mcp add --transport http librenms <PUBLIC_URL>/mcp --client-id <Claude Code client id> --callback-port 8765`, then `/mcp` → Authenticate | ⏳ |
| **VS Code** | MCP: Add Server… → HTTP → `<PUBLIC_URL>/mcp`; sign in with Microsoft when prompted | ⏳ |
| **Service / automation** | Client credentials: `scope=api://<API client id>/.default` | ✅ 2026-10-24 |

Example app-only call:

```sh
TOKEN=$(curl -s https://login.microsoftonline.com/$ENTRA_TENANT_ID/oauth2/v2.0/token \
  -d grant_type=client_credentials -d client_id=$SERVICE_CLIENT_ID \
  --data-urlencode client_secret=$SERVICE_CLIENT_SECRET \
  --data-urlencode scope=api://$ENTRA_CLIENT_ID/.default | jq -r .access_token)
curl -s $PUBLIC_URL/mcp -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

## Troubleshooting

| Error | Meaning |
|---|---|
| AADSTS9010010 (resource doesn't match scopes) | `<PUBLIC_URL>/mcp` is not an identifier URI of the API app, or `MCP_PUBLIC_URL` differs from the URL the client uses |
| AADSTS50011 (redirect URI mismatch) | Wrong client ID for this client (e.g. the Claude Code ID in claude.ai). The error names the redirect URI the client really sent |
| AADSTS50105 (user not assigned) | The user, or their group, has no app role on LibreNMS MCP |
| AADSTS65001 (consent required) | Admin consent not granted for the client app |
| 401 `invalid_token` | Expired, wrong tenant or audience, v1 token, or bad signature. The server log has the reason |
| 403 `insufficient_scope` | Valid token without `access_as_user` or without a LibreNMS role, or a client not in `ENTRA_ALLOWED_CLIENT_IDS` |
| Tools missing from the list | Role too low, `READ_ONLY_MODE=true`, `DISABLED_TAGS`, or `ALLOW_DESTRUCTIVE=false` |
| claude.ai shows an old tool list | claude.ai caches the list per connection. Disconnect and reconnect the connector, or start a new chat. The server's access log shows whether it fetched again |
