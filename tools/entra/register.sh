#!/usr/bin/env bash
# Register LibreNMS MCP in Microsoft Entra ID.
#
# Creates:
#   API app      "$NAME": identifier URIs api://<appId> and $PUBLIC_URL/mcp, v2 access tokens,
#                delegated scope access_as_user, app roles LibreNMS.Reader/.Operator/.Admin,
#                optional claim idtyp, role assignment required, VS Code pre-authorized
#   clients      per $CLIENTS (comma-separated, default all):
#                  claude-code  public client, redirect http://localhost:$CALLBACK_PORT/callback
#                  claude-ai    web client + secret, redirect https://claude.ai/api/mcp/auth_callback
#                  service      confidential client + secret for client credentials, assigned $SERVICE_ROLE
#
# Optionally assigns the signed-in user a role ($ASSIGN_SELF=reader|operator|admin).
# Writes IDs and secrets to $OUT (default secrets/entra.env, mode 600); prints only IDs.
#
# Requires: az login into the target tenant with rights to create app registrations and grant
# admin consent. $PUBLIC_URL must be https on a domain verified in that tenant.
#
# Usage:
#   PUBLIC_URL=https://mcp.example.com tools/entra/register.sh
set -euo pipefail

: "${PUBLIC_URL:?set PUBLIC_URL, e.g. https://mcp.example.com (no /mcp)}"
PUBLIC_URL="${PUBLIC_URL%/}"
NAME="${NAME:-LibreNMS MCP}"
CLIENTS="${CLIENTS:-claude-code,claude-ai,service}"
CALLBACK_PORT="${CALLBACK_PORT:-8765}"
SERVICE_ROLE="${SERVICE_ROLE:-reader}"
ASSIGN_SELF="${ASSIGN_SELF:-}"
OUT="${OUT:-secrets/entra.env}"
VSCODE_CLIENT_ID="aebc6443-996d-45c2-90f0-388ff96faa56"
GRAPH="https://graph.microsoft.com/v1.0"
RESOURCE="$PUBLIC_URL/mcp"

case "$PUBLIC_URL" in https://*) ;; *) echo "PUBLIC_URL must be https" >&2; exit 1 ;; esac
HOST="${PUBLIC_URL#https://}"; HOST="${HOST%%/*}"; HOST="${HOST%%:*}"

uuid() { uuidgen | tr '[:upper:]' '[:lower:]'; }
graph() { az rest --only-show-errors --method "$1" --url "$GRAPH$2" ${3:+--headers Content-Type=application/json --body "$3"}; }
has() { [[ ",$CLIENTS," == *",$1,"* ]]; }
role_id() { case "$1" in reader) echo "$READER_ID" ;; operator) echo "$OPERATOR_ID" ;; admin) echo "$ADMIN_ID" ;; *) echo "unknown role $1" >&2; exit 1 ;; esac; }

TENANT_ID=$(az account show --query tenantId -o tsv)
echo "tenant: $TENANT_ID"

# Entra only accepts an https identifier URI on a verified domain; check before creating anything.
VERIFIED=$(az rest --only-show-errors --method GET --url "$GRAPH/organization?\$select=verifiedDomains" \
  --query 'value[0].verifiedDomains[].name' -o tsv)
ok=""
for d in $VERIFIED; do [[ "$HOST" == "$d" || "$HOST" == *".$d" ]] && ok=1; done
[[ -n "$ok" ]] || { echo "$HOST is not on a verified domain of this tenant; MCP clients will fail with AADSTS9010010" >&2; exit 1; }

if [[ -n "$(az ad app list --filter "displayName eq '$NAME'" --query '[].appId' -o tsv)" ]]; then
  echo "an app registration named '$NAME' already exists; set NAME or delete it first" >&2
  exit 1
fi

# --- API app -------------------------------------------------------------------------------------
SCOPE_ID=$(uuid); READER_ID=$(uuid); OPERATOR_ID=$(uuid); ADMIN_ID=$(uuid)
API_APP_ID=$(az ad app create --only-show-errors --display-name "$NAME" --sign-in-audience AzureADMyOrg --query appId -o tsv)
API_OBJ=$(az ad app show --id "$API_APP_ID" --query id -o tsv)
app_role() { # id value displayName description
  printf '{"id":"%s","value":"%s","displayName":"%s","description":"%s","allowedMemberTypes":["User","Application"],"isEnabled":true}' "$@"
}
graph PATCH "/applications/$API_OBJ" "$(cat <<JSON
{
  "identifierUris": ["api://$API_APP_ID", "$RESOURCE"],
  "api": {
    "requestedAccessTokenVersion": 2,
    "oauth2PermissionScopes": [{
      "id": "$SCOPE_ID", "value": "access_as_user", "type": "User", "isEnabled": true,
      "adminConsentDisplayName": "Use LibreNMS MCP", "adminConsentDescription": "Call LibreNMS MCP tools as the signed-in user.",
      "userConsentDisplayName": "Use LibreNMS MCP", "userConsentDescription": "Call LibreNMS MCP tools as you."
    }]
  },
  "appRoles": [
    $(app_role "$READER_ID" LibreNMS.Reader "LibreNMS Reader" "Read-only LibreNMS MCP tools"),
    $(app_role "$OPERATOR_ID" LibreNMS.Operator "LibreNMS Operator" "Read tools plus approved operational writes"),
    $(app_role "$ADMIN_ID" LibreNMS.Admin "LibreNMS Admin" "All approved LibreNMS MCP tools")
  ],
  "optionalClaims": {"accessToken": [{"name": "idtyp", "essential": false}]}
}
JSON
)"
# Pre-authorizing needs the scope to exist first.
graph PATCH "/applications/$API_OBJ" \
  "{\"api\":{\"preAuthorizedApplications\":[{\"appId\":\"$VSCODE_CLIENT_ID\",\"delegatedPermissionIds\":[\"$SCOPE_ID\"]}]}}"
API_SP=$(az ad sp create --only-show-errors --id "$API_APP_ID" --query id -o tsv)
graph PATCH "/servicePrincipals/$API_SP" '{"appRoleAssignmentRequired":true}'
echo "api app: $API_APP_ID ($RESOURCE)"

if [[ -n "$ASSIGN_SELF" ]]; then
  USER_OID=$(az ad signed-in-user show --query id -o tsv)
  graph POST "/servicePrincipals/$API_SP/appRoleAssignedTo" \
    "{\"principalId\":\"$USER_OID\",\"resourceId\":\"$API_SP\",\"appRoleId\":\"$(role_id "$ASSIGN_SELF")\"}" >/dev/null
  echo "signed-in user assigned $ASSIGN_SELF"
fi

# --- clients -------------------------------------------------------------------------------------
delegated_client() { # displayName -> appId
  local id; id=$(az ad app create --only-show-errors --display-name "$1" --sign-in-audience AzureADMyOrg --query appId -o tsv)
  az ad app permission add --only-show-errors --id "$id" --api "$API_APP_ID" --api-permissions "$SCOPE_ID=Scope"
  az ad sp create --only-show-errors --id "$id" >/dev/null
  az ad app permission grant --only-show-errors --id "$id" --api "$API_APP_ID" --scope access_as_user >/dev/null
  echo "$id"
}

CLAUDE_CODE_ID=""; CLAUDE_AI_ID=""; CLAUDE_AI_SECRET=""; SERVICE_ID=""; SERVICE_SECRET=""
if has claude-code; then
  CLAUDE_CODE_ID=$(delegated_client "$NAME - Claude Code")
  az ad app update --only-show-errors --id "$CLAUDE_CODE_ID" --is-fallback-public-client true \
    --public-client-redirect-uris "http://localhost:$CALLBACK_PORT/callback"
  echo "claude code client: $CLAUDE_CODE_ID (callback port $CALLBACK_PORT)"
fi
if has claude-ai; then
  CLAUDE_AI_ID=$(delegated_client "$NAME - claude.ai")
  az ad app update --only-show-errors --id "$CLAUDE_AI_ID" --web-redirect-uris "https://claude.ai/api/mcp/auth_callback"
  CLAUDE_AI_SECRET=$(az ad app credential reset --only-show-errors --id "$CLAUDE_AI_ID" --display-name claude-ai --years 1 --query password -o tsv)
  echo "claude.ai client: $CLAUDE_AI_ID"
fi
if has service; then
  SERVICE_ID=$(az ad app create --only-show-errors --display-name "$NAME - Service" --sign-in-audience AzureADMyOrg --query appId -o tsv)
  SERVICE_SP=$(az ad sp create --only-show-errors --id "$SERVICE_ID" --query id -o tsv)
  SERVICE_SECRET=$(az ad app credential reset --only-show-errors --id "$SERVICE_ID" --display-name service --years 1 --query password -o tsv)
  graph POST "/servicePrincipals/$API_SP/appRoleAssignedTo" \
    "{\"principalId\":\"$SERVICE_SP\",\"resourceId\":\"$API_SP\",\"appRoleId\":\"$(role_id "$SERVICE_ROLE")\"}" >/dev/null
  echo "service client: $SERVICE_ID (assigned $SERVICE_ROLE)"
fi

mkdir -p "$(dirname "$OUT")"
umask 077
cat > "$OUT" <<ENV
# Server settings
AUTH_MODE=entra
MCP_PUBLIC_URL=$PUBLIC_URL
ENTRA_TENANT_ID=$TENANT_ID
ENTRA_CLIENT_ID=$API_APP_ID
# Registration details (not server settings)
ENTRA_SCOPE=$RESOURCE/access_as_user
ENTRA_ROLE_READER_ID=$READER_ID
ENTRA_ROLE_OPERATOR_ID=$OPERATOR_ID
ENTRA_ROLE_ADMIN_ID=$ADMIN_ID
CLAUDE_CODE_CLIENT_ID=$CLAUDE_CODE_ID
CLAUDE_CODE_CALLBACK_PORT=$CALLBACK_PORT
CLAUDE_AI_CLIENT_ID=$CLAUDE_AI_ID
CLAUDE_AI_CLIENT_SECRET=$CLAUDE_AI_SECRET
SERVICE_CLIENT_ID=$SERVICE_ID
SERVICE_CLIENT_SECRET=$SERVICE_SECRET
ENV
echo "wrote $OUT"
