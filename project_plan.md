# LibreNMS MCP Java Port
## Project Plan and Sprint Backlog

**Status:** Proposed
**Language:** Java 25
**Build:** Maven
**Deployment:** Docker / Docker Compose
**Identity:** Microsoft Entra ID
**Source:** https://github.com/mhajder/librenms-mcp
**License:** MIT, subject to preservation of required notices

## 1. Executive Summary

Reimplement the existing Python LibreNMS MCP server in Java, preserving its tool contracts and LibreNMS API capabilities while adding enterprise-grade authentication and authorization through Microsoft Entra ID.

The system will expose LibreNMS monitoring and management capabilities to MCP-compatible AI assistants using Streamable HTTP. The initial deployment target is a single Docker container running on a VM, with no database dependency.

### Objectives
- Preserve the upstream MCP tool names, schemas, behavior, and error semantics.
- Replace the Python runtime with Java 25 and Maven.
- Support authenticated MCP clients through Microsoft Entra ID.
- Enforce per-tool read, write, and administrative permissions.
- Package the application as a minimal, non-root OCI container.
- Provide operational logs, health checks, metrics, documentation, and CI/CD.
- Remain compatible with existing LibreNMS API token authentication.

### Non-goals
- Replacing LibreNMS itself.
- Building a new LibreNMS web interface.
- Implementing direct Entra authentication inside LibreNMS.
- Automatically granting management permissions to AI agents.

## 2. Baseline and Feature Inventory

The upstream project already supports:
- Device, inventory, location, and device-group operations.
- Ports, port groups, MAC and ARP searches.
- Alerts, alert rules, alert templates, and logs.
- Billing and graph queries.
- Oxidized configuration retrieval and search.
- BGP, OSPF, VRF, routing, switching, sensors, and services.
- Read-only mode and disabled tool tags.
- Rate limiting, tool search, pagination, and optional Sentry.
- stdio, SSE, and Streamable HTTP transports.
- Static HTTP bearer-token authentication and Docker images.

### Mandatory baseline work
1. Pin the upstream commit SHA.
2. Inventory all registered MCP tools, including names, descriptions, tags, input schemas, output structures, and underlying HTTP operations.
3. Identify tools with multiple possible write methods or side effects.
4. Capture representative LibreNMS API responses.
5. Build a parity matrix mapping each Python tool to its Java implementation.
6. Record the upstream MIT license and preserve applicable notices.

**Acceptance:** Every upstream tool is accounted for in a versioned feature-parity document. No tool is silently omitted.

## 3. Technical Architecture

### Components
1. MCP HTTP server: Java MCP SDK with a compatible HTTP transport.
2. HTTP hosting: Javalin where compatible, otherwise a supported SDK transport adapter.
3. Authentication middleware: Entra JWT validation and OAuth resource-server metadata.  Fall back to static API key.
4. Authorization engine: policy evaluation before any tool execution.
5. Tool registry: typed definitions and schemas.
6. LibreNMS API client: Java HttpClient with Jackson.
7. Configuration and secrets provider: environment variables and mounted secrets.
8. Observability: structured logging, health endpoints, metrics, and audit events.

### Request flow
MCP client -> HTTPS ingress -> OAuth token or API Key validation -> authorization -> MCP tool dispatcher -> LibreNMS API client -> LibreNMS

### Design principles
- Stateless to start, caching added later to increase performance.
- No application database in V1 (it will come in v2)
- Fail closed for missing or invalid authentication.
- Fail closed for unclassified tools.
- Separate transport, identity, policy, tools, and API client packages.
- No Python dependencies in the production image.
- Preserve upstream behavior unless a documented security improvement requires a change.

### Proposed package structure
```text
src/main/java/com/example/librenmsmcp/
  Main.java
  config/
  mcp/
  auth/
  authorization/
  librenms/
  tools/
    devices/
    ports/
    alerts/
    inventory/
    routing/
    monitoring/
    billing/
    oxidized/
  observability/
  exceptions/
src/test/java/
docs/
  parity-matrix.md
  architecture.md
  entra-setup.md
  deployment.md
  security.md
  tool-reference.md
Dockerfile
compose.yaml
pom.xml
```

## 4. Authentication and Authorization

### Entra application model
Register the MCP server as an Entra-protected API with an Application ID URI. Register interactive MCP clients separately where needed.

Support:
- OAuth 2.0 Authorization Code with PKCE for interactive clients.
- OAuth 2.0 client credentials for approved machine-to-machine clients.
- Microsoft Entra OpenID Connect discovery and JWKS validation.
- MCP OAuth protected-resource metadata and compatible authorization-server discovery.
- Client-specific authorization and explicit administrator consent where required.

The MCP server is a protected resource, not a password-based login service. It validates Entra-issued access tokens intended for this API.

### Token validation
Validate signature, issuer, tenant, audience, expiry, not-before, permitted algorithms, and expected token version. Use tenant-specific issuer configuration and rotate signing keys through JWKS refresh.

Do not accept ID tokens as API access tokens. Do not accept Microsoft Graph tokens whose audience is not this MCP server.

### Roles and permissions
Define three authorization levels:
- Reader: read-only tools.
- Operator: read tools plus explicitly approved operational writes.
- Administrator: all approved tools, including destructive actions.

Suggested Entra application roles:
- LibreNMS.Reader
- LibreNMS.Operator
- LibreNMS.Admin

For delegated access, map configured delegated scopes and approved user roles/groups into the same internal permission model. For app-only access, use explicitly assigned application roles.

### Security controls
- Read-only mode globally overrides every identity permission.
- Tool-level authorization is enforced at execution time.
- Tool listing and search must hide unauthorized tools.
- Indirect tool dispatch must repeat authorization checks.
- Destructive operations require an explicit administrator policy.
- Validate tool inputs and reject unsafe identifiers and URLs.
- Rate-limit per authenticated principal and client identity.
- Protect against cross-tenant tokens and token replay where applicable.
- Do not log tokens, API secrets, or full sensitive configuration responses.
- Reject insecure production startup configurations.
- Use HTTPS and restrict trusted proxy headers.

### LibreNMS credentials
Use a server-side LibreNMS API token with the least privilege available. Do not forward Entra access tokens to LibreNMS.

Entra roles govern MCP access, while the LibreNMS token independently limits downstream permissions. Document that these are separate authorization boundaries.

## 5. Sprint 0: Discovery and Contract Capture

**Estimated duration:** 3–5 days

Tasks:
- Clone and pin upstream repository.
- Review source layout, licensing, dependencies, tests, and releases.
- Extract complete tool inventory and JSON schemas.
- Identify read/write/destructive operations.
- Record error and pagination behavior.
- Create a fixture library of sanitized API responses.
- Document compatibility and version assumptions.
- Establish repository, branch protections, and issue templates.

Deliverables:
- docs/parity-matrix.md
- docs/upstream-baseline.md
- docs/architecture.md
- Initial test fixtures

Acceptance criteria:
- Every upstream tool has a migration status and permission classification.
- Upstream commit is recorded.
- Known compatibility gaps are documented.

## 6. Sprint 1: Java Foundation and MCP Server

**Estimated duration:** 1 week

Tasks:
- Scaffold Java 25 Maven project.
- Integrate official Java MCP SDK.
- Select and validate Streamable HTTP transport.
- Add Jackson, SLF4J, Logback, JUnit 5, and test dependencies.
- Implement configuration validation.
- Implement LibreNMS HTTP client and API-token handling.
- Add connection timeouts, bounded retries for safe/idempotent operations, and error translation.
- Add basic MCP tools: ping, system_info, devices_list, device_get.
- Add health and readiness endpoints.
- Build first multi-stage Dockerfile.

Acceptance criteria:
- Server starts and responds to MCP initialization.
- MCP tools/list and tools/call operate successfully.
- Initial tools work against fixture and test LibreNMS instances.
- Build and tests run with Maven.
- Container runs as a non-root user.

## 7. Sprint 2: Read-Only Feature Parity

**Estimated duration:** 1–2 weeks

Tasks:
- Port device and inventory queries.
- Port locations and device-group queries.
- Port port, port-group, MAC, and ARP queries.
- Port alert and log retrieval.
- Port routing, BGP, OSPF, VRF, and switching queries.
- Port sensors, health, and services queries.
- Port billing graphs and historical data retrieval.
- Port Oxidized configuration retrieval and search.
- Preserve pagination, optional filters, and error semantics.
- Add tests for all read-only tools.

Acceptance criteria:
- All upstream read-only tools have Java implementations.
- Schemas match the documented baseline or have approved deviations.
- Sensitive Oxidized output is not included in application logs.
- Test coverage meets project thresholds.

## 8. Sprint 3: Write Operations and Safety

**Estimated duration:** 1 week

Tasks:
- Port device, location, and device-group management.
- Port port descriptions and port-group assignments.
- Port alert acknowledgement and alert-rule management.
- Port service and billing management.
- Port maintenance, discovery, and other mutation tools.
- Implement read-only mode.
- Implement tool-level permission annotations.
- Add policy checks at dispatch and execution.
- Implement audit logging for all mutations.
- Add input validation and destructive-operation controls.
- Ensure retry logic never duplicates unsafe mutations.

Acceptance criteria:
- Every write tool is permission-classified.
- Read-only mode blocks all mutations.
- Unauthorized calls cannot reach LibreNMS.
- Audit events record principal, tool, target, outcome, and correlation ID.
- Mutations are tested using disposable fixtures.

## 9. Sprint 4: Microsoft Entra Integration

**Estimated duration:** 1–2 weeks

Tasks:
- Document Entra app registration.
- Implement JWT access-token validation.
- Support Entra signing-key rotation.
- Validate tenant, issuer, and audience.
- Publish OAuth protected-resource metadata.
- Implement OAuth authorization-server discovery compatibility.
- Implement Reader, Operator, and Administrator policies.
- Support delegated and app-only token claims.
- Integrate authentication into Streamable HTTP.
- Apply tool visibility and invocation filtering.
- Add negative security tests.
- Document compatible MCP client authentication flows.

Acceptance criteria:
- Missing and invalid tokens are rejected.
- Wrong-audience and wrong-tenant tokens are rejected.
- Expired tokens are rejected.
- Reader identities cannot execute write tools.
- Operator identities cannot execute admin-only tools.
- Approved service principals can use assigned permissions.
- At least one interactive client and one service client complete end-to-end tests.

## 10. Sprint 5: Containerization, Operations, and Release

**Estimated duration:** 1 week

Tasks:
- Finalize multi-stage Docker build.
- Use a minimal supported Java runtime image.
- Run as a non-root user with a read-only filesystem where practical.
- Add Docker Compose configuration.
- Add secrets-file support.
- Add liveness and readiness probes.
- Add structured JSON logging and audit events.
- Add Micrometer metrics or equivalent.
- Add optional OpenTelemetry integration.
- Add dependency and container vulnerability scanning.
- Add GitHub Actions for build, test, image publishing, and release tagging.
- Generate SBOM and image provenance where supported.
- Publish deployment and troubleshooting documentation.
- Run a complete parity and security regression suite.

Acceptance criteria:
- `docker compose up -d` starts the service with valid configuration.
- Container image is published with immutable version tags.
- No credentials appear in the image or build logs.
- Entra-protected remote MCP access works over HTTPS.
- CI checks are green.
- Deployment and rollback are documented.

## 11. Configuration

Example configuration names:

```dotenv
LIBRENMS_URL=https://librenms.example.com
LIBRENMS_TOKEN_FILE=/run/secrets/librenms_token
LIBRENMS_VERIFY_SSL=true
LIBRENMS_TIMEOUT=30

MCP_TRANSPORT=http
MCP_HTTP_HOST=0.0.0.0
MCP_HTTP_PORT=8000

AUTH_MODE=entra
ENTRA_TENANT_ID=<tenant-id>
ENTRA_CLIENT_ID=<api-application-client-id>
ENTRA_AUDIENCE=api://<api-application-client-id>
MCP_PUBLIC_URL=https://mcp.example.com

READ_ONLY_MODE=true
DISABLED_TAGS=
RATE_LIMIT_ENABLED=true
RATE_LIMIT_MAX_REQUESTS=100
RATE_LIMIT_WINDOW_MINUTES=1

LOG_LEVEL=INFO
AUDIT_ENABLED=true
```

These names define the proposed Java implementation and are not guaranteed to match the upstream project. Never commit real secrets.

Production defaults should require Entra authentication for remote access. Any optional unauthenticated local development mode must bind only to loopback and must be explicitly enabled.

## 12. Testing Strategy

### Unit tests
- Configuration parsing and validation.
- LibreNMS request construction.
- JSON serialization and schema validation.
- Token claim mapping and policy decisions.
- Input validation and error translation.

### Integration tests
- Mock LibreNMS HTTP API with WireMock.
- MCP initialize/list/call workflows.
- Pagination and rate-limit behavior.
- Docker startup and health probes.
- JWKS rotation and token validation using test keys.
- Permission enforcement for all tool categories.

### Contract tests
Compare Python baseline and Java implementations using sanitized fixtures:
- Tool name and description.
- Input schema and required parameters.
- LibreNMS method, path, and query.
- Output shape.
- Error handling.
- Read-only behavior.

### Security tests
- Missing token.
- Invalid signature.
- Wrong issuer.
- Wrong tenant.
- Wrong audience.
- Expired or not-yet-valid token.
- ID token supplied as an access token.
- Insufficient scope or role.
- Indirect unauthorized tool invocation.
- Unsafe write attempts.
- Rate-limit bypass attempts.
- Secret leakage through logs and exceptions.

### Coverage targets
- Minimum 80% overall line coverage.
- Minimum 90% coverage for authentication and authorization.
- Every MCP tool covered by at least one contract test.
- Every mutation covered by a denial test.

## 13. CI/CD

GitHub Actions workflow:
1. Maven compile and unit tests.
2. Static analysis and formatting checks.
3. Dependency vulnerability scan.
4. Contract and integration tests.
5. Container build.
6. Container security scan.
7. SBOM generation.
8. Publish image to GHCR on approved tags.
9. Generate release notes and deployment artifacts.

Use immutable image tags and avoid automatic production deployment in V1.

## 14. Recommended Milestones

| Milestone | Target |
|---|---|
| M0: Baseline captured | End of Sprint 0 |
| M1: Java MCP server operational | End of Sprint 1 |
| M2: Read-only tool parity | End of Sprint 2 |
| M3: Full tool parity and safety | End of Sprint 3 |
| M4: Entra authentication and RBAC | End of Sprint 4 |
| M5: Production-ready container | End of Sprint 5 |

Estimated total: approximately 6–8 weeks for one developer, subject to upstream tool count, client compatibility, and testing access.

## 15. Risks and Mitigations

**SDK/HTTP integration:** Javalin may not support the selected SDK transport directly. Validate in Sprint 1 and use a supported transport implementation if necessary.

**Large upstream tool surface:** Automate inventory and contract extraction before porting. Avoid undocumented omissions.

**Entra/MCP interoperability:** Validate an actual target MCP client early. Not every client supports the same OAuth discovery and authorization flows.

**Role mapping:** Do not equate arbitrary group claims with application permissions. Configure explicit mappings and test both delegated and app-only tokens.

**LibreNMS API permissions:** A shared LibreNMS API token may have broader privileges than an MCP identity. Enforce authorization before dispatch and use least-privileged downstream credentials.

**Sensitive configuration disclosure:** Oxidized output may contain secrets. Restrict access, redact logs, and document exposure risks.

**Destructive actions:** Disable or tightly restrict delete, discovery, and configuration-modifying tools by default.

**Upstream drift:** Pin a baseline and track upstream changes separately. Do not change feature scope mid-sprint without review.

## 16. Definition of Done

The project is complete when:
- All baseline MCP tools are implemented or explicitly approved as excluded.
- Java tool contracts pass automated parity tests.
- No Python runtime is required.
- Entra authentication works for approved interactive and service clients.
- Reader, Operator, and Administrator permissions are enforced.
- Read-only mode and disabled tags are preserved.
- Production HTTP access is authenticated and protected by TLS.
- Docker Compose deployment is repeatable.
- Secrets are externalized.
- Logs, audit events, metrics, and health endpoints work.
- CI/CD publishes tested, scanned images.
- Documentation includes Entra setup, configuration, deployment, testing, troubleshooting, and rollback.
- No unresolved critical or high-severity security findings remain without explicit risk acceptance.

## 17. Recommended Implementation Order

Prioritize the first useful, secure release rather than waiting for every management feature:

1. Establish MCP transport and Java LibreNMS client.
2. Port core read-only monitoring tools.
3. Implement Entra authentication and Reader authorization.
4. Deploy a read-only container for early testing.
5. Complete remaining read-only parity.
6. Implement management tools and stronger authorization.
7. Harden, test, and publish the full release.

This order allows an AI assistant to safely query monitoring data before potentially destructive functionality is introduced.

## 18. Reference Links

- Upstream project: https://github.com/mhajder/librenms-mcp
- LibreNMS API: https://docs.librenms.org/API/
- Model Context Protocol: https://modelcontextprotocol.io/
- Java MCP SDK: https://github.com/modelcontextprotocol/java-sdk
- Microsoft identity platform: https://learn.microsoft.com/en-us/entra/identity-platform/

