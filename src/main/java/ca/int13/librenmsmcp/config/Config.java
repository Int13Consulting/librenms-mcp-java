package ca.int13.librenmsmcp.config;

import ca.int13.librenmsmcp.authz.Role;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;

/**
 * Validated server configuration. Built only by {@link ConfigLoader}, so every instance has passed
 * the startup checks.
 */
public record Config(LibreNms librenms, Transport transport, Http http, Policy policy, Search search, String logLevel) {

    public enum Transport {
        HTTP,
        STDIO
    }

    public enum AuthMode {
        ENTRA,
        APIKEY,
        NONE
    }

    /** Connection to LibreNMS. The token is never logged; see {@link #toString()}. */
    public record LibreNms(URI url, String token, boolean verifySsl, Duration timeout) {
        @Override
        public String toString() {
            return "LibreNms[url=" + url + ", verifySsl=" + verifySsl + ", timeout=" + timeout + "]";
        }
    }

    /**
     * HTTP transport settings; ignored for stdio.
     *
     * @param publicUrl the externally visible base URL (no path); required with Entra, where
     *     {@code <publicUrl>/mcp} must be registered as an identifier URI of the API app
     * @param allowedOrigins browser origins allowed to call {@code /mcp}; requests carrying any other
     *     {@code Origin} header are refused (DNS-rebinding protection)
     * @param metricsPort Prometheus port on the same host, or 0 for no metrics
     */
    public record Http(
            String host,
            int port,
            Set<AuthMode> authModes,
            Optional<String> apiKey,
            Role apiKeyRole,
            int maxRequestBytes,
            Optional<URI> publicUrl,
            Optional<Entra> entra,
            Set<String> allowedOrigins,
            RateLimit rateLimit,
            int metricsPort) {

        /** Settings without Entra, origins or rate limiting; for tests and local use. */
        public static Http basic(String host, int port, Set<AuthMode> modes, Optional<String> apiKey, Role apiKeyRole) {
            return new Http(
                    host,
                    port,
                    modes,
                    apiKey,
                    apiKeyRole,
                    1024 * 1024,
                    Optional.empty(),
                    Optional.empty(),
                    Set.of(),
                    RateLimit.DISABLED,
                    0);
        }

        @Override
        public String toString() {
            return "Http[host=" + host + ", port=" + port + ", authModes=" + authModes
                    + ", apiKey=" + (apiKey.isPresent() ? "<set>" : "<unset>") + ", apiKeyRole=" + apiKeyRole
                    + ", publicUrl=" + publicUrl.map(URI::toString).orElse("<unset>") + ", entra=" + entra.orElse(null)
                    + ", allowedOrigins=" + allowedOrigins + ", rateLimit=" + rateLimit
                    + ", metricsPort=" + (metricsPort == 0 ? "off" : metricsPort) + "]";
        }
    }

    /**
     * Microsoft Entra ID resource-server settings.
     *
     * @param authority the login host, {@code https://login.microsoftonline.com} unless a sovereign cloud
     * @param clientId the API app registration's client ID (the {@code aud} of v2 access tokens)
     * @param requiredScope the delegated scope a user token must carry
     * @param allowedClientIds when non-empty, only tokens issued to these client apps ({@code azp}) are accepted
     */
    public record Entra(
            URI authority,
            String tenantId,
            String clientId,
            String requiredScope,
            Duration clockSkew,
            Set<String> allowedClientIds) {

        public String issuer() {
            return authority.toString().replaceAll("/+$", "") + "/" + tenantId + "/v2.0";
        }

        public URI jwksUri() {
            return URI.create(authority.toString().replaceAll("/+$", "") + "/" + tenantId + "/discovery/v2.0/keys");
        }
    }

    /** Per-principal sliding window; {@code maxRequests} of 0 disables limiting. */
    public record RateLimit(int maxRequests, Duration window) {
        public static final RateLimit DISABLED = new RateLimit(0, Duration.ofMinutes(1));

        public boolean enabled() {
            return maxRequests > 0;
        }
    }

    /**
     * Tool search (upstream {@code TOOL_SEARCH_*}): when enabled, the tool list is replaced by
     * {@code search_tools} and {@code call_tool}.
     */
    public record Search(boolean enabled, ca.int13.librenmsmcp.search.ToolSearch.Strategy strategy, int maxResults) {
        public static final Search DISABLED =
                new Search(false, ca.int13.librenmsmcp.search.ToolSearch.Strategy.BM25, 5);
    }

    /**
     * Tool policy. {@code localRole} is the role of callers that are not authenticated per request:
     * the stdio client, and HTTP callers when {@code AUTH_MODE=none}.
     */
    public record Policy(boolean readOnly, Set<String> disabledTags, boolean allowDestructive, Role localRole) {}
}
