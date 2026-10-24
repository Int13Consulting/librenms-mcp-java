#!/usr/bin/env bash
# Create the Entra app registrations for the Phase 1 client-interop spike.
#
#   API app            "LibreNMS MCP (spike)": api://<appId>, v2 tokens, scope access_as_user,
#                      app roles LibreNMS.Reader / .Operator / .Admin
#   Claude Code        public client, redirect http://localhost:${CALLBACK_PORT}/callback
#   claude.ai          confidential web client, redirect https://claude.ai/api/mcp/auth_callback
#   Service            confidential client for client-credentials, assigned LibreNMS.Reader
#   VS Code            pre-authorized on the API (first-party client ID, nothing created)
#
# The signed-in user is assigned LibreNMS.Admin. IDs and secrets are written to
# secrets/entra-spike.env (git-ignored); only IDs are printed.
#
# Requires: az login with rights to create app registrations and grant admin consent.
set -euo pipefail

PREFIX="${PREFIX:-LibreNMS MCP (spike)}"
CALLBACK_PORT="${CALLBACK_PORT:-8765}"
VSCODE_CLIENT_ID="aebc6443-996d-45c2-90f0-388ff96faa56"
OUT="${OUT:-secrets/entra-spike.env}"
GRAPH="https://graph.microsoft.com/v1.0"

uuid() { uuidgen | tr '[:upper:]' '[:lower:]'; }
graph() { az rest --only-show-errors --method "$1" --url "$GRAPH$2" ${3:+--headers Content-Type=application/json --body "$3"}; }

TENANT_ID=$(az account show --query tenantId -o tsv)
USER_OID=$(az ad signed-in-user show --query id -o tsv)
echo "tenant: $TENANT_ID"

# --- API app -------------------------------------------------------------------
SCOPE_ID=$(uuid); READER_ID=$(uuid); OPERATOR_ID=$(uuid); ADMIN_ID=$(uuid)
API_APP_ID=$(az ad app create --only-show-errors --display-name "$PREFIX" \
  --sign-in-audience AzureADMyOrg --query appId -o tsv)
API_OBJ=$(az ad app show --id "$API_APP_ID" --query id -o tsv)
role() { # id value displayName description
  printf '{"id":"%s","value":"%s","displayName":"%s","description":"%s","allowedMemberTypes":["User","Application"],"isEnabled":true}' "$@"
}
graph PATCH "/applications/$API_OBJ" "$(cat <<JSON
{
  "identifierUris": ["api://$API_APP_ID"],
  "api": {
    "requestedAccessTokenVersion": 2,
    "oauth2PermissionScopes": [{
      "id": "$SCOPE_ID", "value": "access_as_user", "type": "User", "isEnabled": true,
      "adminConsentDisplayName": "Use LibreNMS MCP", "adminConsentDescription": "Call LibreNMS MCP tools as the signed-in user.",
      "userConsentDisplayName": "Use LibreNMS MCP", "userConsentDescription": "Call LibreNMS MCP tools as you."
    }]
  },
  "appRoles": [
    $(role "$READER_ID" LibreNMS.Reader "LibreNMS Reader" "Read-only LibreNMS MCP tools"),
    $(role "$OPERATOR_ID" LibreNMS.Operator "LibreNMS Operator" "Read tools plus approved operational writes"),
    $(role "$ADMIN_ID" LibreNMS.Admin "LibreNMS Admin" "All approved LibreNMS MCP tools")
  ]
}
JSON
)"
# Pre-authorizing needs the scope to exist first, so it is a second update.
graph PATCH "/applications/$API_OBJ" \
  "{\"api\":{\"preAuthorizedApplications\":[{\"appId\":\"$VSCODE_CLIENT_ID\",\"delegatedPermissionIds\":[\"$SCOPE_ID\"]}]}}"
API_SP=$(az ad sp create --only-show-errors --id "$API_APP_ID" --query id -o tsv)
# Require assignment so only users/apps given a LibreNMS role can get tokens.
graph PATCH "/servicePrincipals/$API_SP" '{"appRoleAssignmentRequired":true}'
graph POST "/servicePrincipals/$API_SP/appRoleAssignedTo" \
  "{\"principalId\":\"$USER_OID\",\"resourceId\":\"$API_SP\",\"appRoleId\":\"$ADMIN_ID\"}" >/dev/null
echo "api app: $API_APP_ID (signed-in user assigned LibreNMS.Admin)"

# --- delegated clients ---------------------------------------------------------
delegated_client() { # displayName -> appId; caller adds redirect URIs
  local id; id=$(az ad app create --only-show-errors --display-name "$1" --sign-in-audience AzureADMyOrg --query appId -o tsv)
  az ad app permission add --only-show-errors --id "$id" --api "$API_APP_ID" --api-permissions "$SCOPE_ID=Scope"
  az ad sp create --only-show-errors --id "$id" >/dev/null
  az ad app permission grant --only-show-errors --id "$id" --api "$API_APP_ID" --scope access_as_user >/dev/null
  echo "$id"
}

CLAUDE_CODE_ID=$(delegated_client "$PREFIX - Claude Code")
az ad app update --only-show-errors --id "$CLAUDE_CODE_ID" --is-fallback-public-client true \
  --public-client-redirect-uris "http://localhost:$CALLBACK_PORT/callback"
echo "claude code client: $CLAUDE_CODE_ID (callback port $CALLBACK_PORT)"

CLAUDE_AI_ID=$(delegated_client "$PREFIX - claude.ai")
az ad app update --only-show-errors --id "$CLAUDE_AI_ID" --web-redirect-uris "https://claude.ai/api/mcp/auth_callback"
CLAUDE_AI_SECRET=$(az ad app credential reset --only-show-errors --id "$CLAUDE_AI_ID" --display-name spike --years 1 --query password -o tsv)
echo "claude.ai client: $CLAUDE_AI_ID"

# --- app-only client -----------------------------------------------------------
SERVICE_ID=$(az ad app create --only-show-errors --display-name "$PREFIX - Service" --sign-in-audience AzureADMyOrg --query appId -o tsv)
SERVICE_SP=$(az ad sp create --only-show-errors --id "$SERVICE_ID" --query id -o tsv)
SERVICE_SECRET=$(az ad app credential reset --only-show-errors --id "$SERVICE_ID" --display-name spike --years 1 --query password -o tsv)
graph POST "/servicePrincipals/$API_SP/appRoleAssignedTo" \
  "{\"principalId\":\"$SERVICE_SP\",\"resourceId\":\"$API_SP\",\"appRoleId\":\"$READER_ID\"}" >/dev/null
echo "service client: $SERVICE_ID (assigned LibreNMS.Reader)"

mkdir -p "$(dirname "$OUT")"
umask 077
cat > "$OUT" <<ENV
ENTRA_TENANT_ID=$TENANT_ID
ENTRA_CLIENT_ID=$API_APP_ID
ENTRA_SCOPE_ID=$SCOPE_ID
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
