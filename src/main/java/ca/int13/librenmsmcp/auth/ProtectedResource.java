package ca.int13.librenmsmcp.auth;

import ca.int13.librenmsmcp.config.Config;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OAuth 2.0 protected-resource metadata (RFC 9728) and {@code WWW-Authenticate} challenges, which
 * is how MCP clients discover that they must sign in with Entra and which scope to request.
 *
 * <p>The resource is {@code <publicUrl>/mcp}. It must be registered as an identifier URI of the API
 * app: clients send it as the RFC 8707 {@code resource} parameter, and Entra refuses a resource that
 * is not an identifier URI of the app owning the scope (AADSTS9010010).
 */
public final class ProtectedResource {
    public static final String METADATA_PATH = "/.well-known/oauth-protected-resource";

    private final String resource;
    private final String scope;
    private final String metadataUrl;
    private final String issuer;

    public ProtectedResource(URI publicUrl, String mcpPath, Config.Entra entra) {
        String base = publicUrl.toString().replaceAll("/+$", "");
        this.resource = base + mcpPath;
        this.scope = resource + "/" + entra.requiredScope();
        this.metadataUrl = base + METADATA_PATH + mcpPath;
        this.issuer = entra.issuer();
    }

    public String resource() {
        return resource;
    }

    public String scope() {
        return scope;
    }

    public Map<String, Object> metadata() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("resource", resource);
        m.put("authorization_servers", List.of(issuer));
        m.put("scopes_supported", List.of(scope));
        m.put("bearer_methods_supported", List.of("header"));
        m.put("resource_name", "LibreNMS MCP Server");
        return m;
    }

    /** The challenge for a 401/403; {@code error} is null when the request carried no credentials. */
    public String challenge(AuthResult.Error error) {
        String value = "Bearer resource_metadata=\"" + metadataUrl + "\", scope=\"" + scope + "\"";
        return error == null ? value : value + ", error=\"" + error.code + "\"";
    }
}
