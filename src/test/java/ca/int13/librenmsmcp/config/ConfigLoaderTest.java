package ca.int13.librenmsmcp.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.int13.librenmsmcp.authz.Role;
import ca.int13.librenmsmcp.config.Config.AuthMode;
import ca.int13.librenmsmcp.config.Config.Transport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigLoaderTest {
    private static final String KEY = "k".repeat(ConfigLoader.MIN_API_KEY_LENGTH);

    private static Map<String, String> valid() {
        Map<String, String> env = new HashMap<>();
        env.put("LIBRENMS_URL", "https://nms.example/");
        env.put("LIBRENMS_TOKEN", "secret-token");
        env.put("AUTH_MODE", "apikey");
        env.put("MCP_API_KEY", KEY);
        return env;
    }

    private static Map<String, String> with(String... kv) {
        Map<String, String> env = valid();
        for (int i = 0; i < kv.length; i += 2) {
            if (kv[i + 1] == null) {
                env.remove(kv[i]);
            } else {
                env.put(kv[i], kv[i + 1]);
            }
        }
        return env;
    }

    @Test
    void secureDefaults() {
        Config c = ConfigLoader.load(valid());
        assertThat(c.transport()).isEqualTo(Transport.HTTP);
        assertThat(c.librenms().url()).hasToString("https://nms.example");
        assertThat(c.librenms().verifySsl()).isTrue();
        assertThat(c.librenms().timeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(c.http().host()).isEqualTo("127.0.0.1");
        assertThat(c.http().port()).isEqualTo(8000);
        assertThat(c.http().authModes()).containsExactly(AuthMode.APIKEY);
        assertThat(c.http().apiKeyRole()).isEqualTo(Role.READER);
        assertThat(c.policy().readOnly()).isTrue();
        assertThat(c.policy().allowDestructive()).isFalse();
        assertThat(c.policy().localRole()).isEqualTo(Role.READER);
        assertThat(c.logLevel()).isEqualTo("INFO");
    }

    @Test
    void reportsEveryProblemAtOnce() {
        assertThatThrownBy(() -> ConfigLoader.load(Map.of()))
                .isInstanceOfSatisfying(
                        ConfigException.class,
                        e -> assertThat(e.problems())
                                .containsExactlyInAnyOrder(
                                        "LIBRENMS_URL is required",
                                        "LIBRENMS_TOKEN (or LIBRENMS_TOKEN_FILE) is required",
                                        "AUTH_MODE is required for the http transport (entra, apikey, or none for loopback-only development)"));
    }

    @Test
    void plainHttpLibreNmsNeedsExplicitOptIn() {
        assertThatThrownBy(() -> ConfigLoader.load(with("LIBRENMS_URL", "http://nms.lab")))
                .hasMessageContaining("LIBRENMS_ALLOW_HTTP=true");
        assertThat(ConfigLoader.load(with("LIBRENMS_URL", "http://nms.lab", "LIBRENMS_ALLOW_HTTP", "true"))
                        .librenms()
                        .url())
                .hasToString("http://nms.lab");
    }

    @Test
    void noAuthOnlyOnLoopback() {
        assertThat(ConfigLoader.load(with("AUTH_MODE", "none", "MCP_API_KEY", null))
                        .http()
                        .authModes())
                .containsExactly(AuthMode.NONE);
        assertThatThrownBy(() -> ConfigLoader.load(with("AUTH_MODE", "none", "MCP_HTTP_HOST", "0.0.0.0")))
                .hasMessageContaining("only allowed when MCP_HTTP_HOST is a loopback address");
        assertThatThrownBy(() -> ConfigLoader.load(with("AUTH_MODE", "none,apikey")))
                .hasMessageContaining("cannot be combined");
    }

    @Test
    void apiKeyMustBeLongEnough() {
        assertThatThrownBy(() -> ConfigLoader.load(with("MCP_API_KEY", "short")))
                .hasMessageContaining("at least " + ConfigLoader.MIN_API_KEY_LENGTH);
        assertThatThrownBy(() -> ConfigLoader.load(with("MCP_API_KEY", null)))
                .hasMessageContaining("AUTH_MODE=apikey requires MCP_API_KEY");
    }

    @Test
    void secretsFromFiles(@TempDir Path dir) throws IOException {
        Path token = Files.writeString(dir.resolve("token"), "file-token\n");
        Path key = Files.writeString(dir.resolve("key"), KEY + "\n");
        Config c = ConfigLoader.load(with(
                "LIBRENMS_TOKEN",
                null,
                "LIBRENMS_TOKEN_FILE",
                token.toString(),
                "MCP_API_KEY",
                null,
                "MCP_API_KEY_FILE",
                key.toString()));
        assertThat(c.librenms().token()).isEqualTo("file-token");
        assertThat(c.http().apiKey()).contains(KEY);

        assertThatThrownBy(() -> ConfigLoader.load(with("LIBRENMS_TOKEN_FILE", token.toString())))
                .hasMessageContaining("set only one of LIBRENMS_TOKEN and LIBRENMS_TOKEN_FILE");
        assertThatThrownBy(() -> ConfigLoader.load(with(
                        "LIBRENMS_TOKEN",
                        null,
                        "LIBRENMS_TOKEN_FILE",
                        dir.resolve("missing").toString())))
                .hasMessageContaining("LIBRENMS_TOKEN_FILE cannot be read");
    }

    @Test
    void transports() {
        assertThat(ConfigLoader.load(with("MCP_TRANSPORT", "streamable-http")).transport())
                .isEqualTo(Transport.HTTP);
        Config stdio = ConfigLoader.load(with("MCP_TRANSPORT", "stdio", "AUTH_MODE", null, "MCP_API_KEY", null));
        assertThat(stdio.transport()).isEqualTo(Transport.STDIO);
        assertThat(stdio.http().authModes()).isEmpty();
        assertThatThrownBy(() -> ConfigLoader.load(with("MCP_TRANSPORT", "sse")))
                .hasMessageContaining("sse is not supported");
        assertThatThrownBy(() -> ConfigLoader.load(with("MCP_TRANSPORT", "carrier-pigeon")))
                .hasMessageContaining("MCP_TRANSPORT must be");
    }

    @Test
    void upstreamBearerTokenVariableIsRejectedWithGuidance() {
        assertThatThrownBy(() -> ConfigLoader.load(with("MCP_HTTP_BEARER_TOKEN", "x")))
                .hasMessageContaining("set AUTH_MODE=apikey and MCP_API_KEY instead");
    }

    private static final String TENANT = "28fcbfbd-c451-4133-9785-7684129851a5";
    private static final String CLIENT = "19ead6e2-7572-45a3-8d5c-d322144a5d46";

    private static Map<String, String> entra(String... kv) {
        Map<String, String> env = with(
                "AUTH_MODE",
                "entra",
                "MCP_API_KEY",
                null,
                "ENTRA_TENANT_ID",
                TENANT,
                "ENTRA_CLIENT_ID",
                CLIENT.toUpperCase(java.util.Locale.ROOT),
                "MCP_PUBLIC_URL",
                "https://mcp.example.ca/");
        for (int i = 0; i < kv.length; i += 2) {
            if (kv[i + 1] == null) {
                env.remove(kv[i]);
            } else {
                env.put(kv[i], kv[i + 1]);
            }
        }
        return env;
    }

    @Test
    void entraSettings() {
        Config c = ConfigLoader.load(entra());
        Config.Entra e = c.http().entra().orElseThrow();
        assertThat(c.http().authModes()).containsExactly(AuthMode.ENTRA);
        assertThat(c.http().publicUrl()).hasValueSatisfying(u -> assertThat(u).hasToString("https://mcp.example.ca"));
        assertThat(e.clientId()).as("normalized to lower case").isEqualTo(CLIENT);
        assertThat(e.issuer()).isEqualTo("https://login.microsoftonline.com/" + TENANT + "/v2.0");
        assertThat(e.jwksUri()).hasToString("https://login.microsoftonline.com/" + TENANT + "/discovery/v2.0/keys");
        assertThat(e.requiredScope()).isEqualTo("access_as_user");
        assertThat(e.clockSkew()).isEqualTo(Duration.ofSeconds(60));
        assertThat(e.allowedClientIds()).isEmpty();

        Config sovereign = ConfigLoader.load(entra(
                "ENTRA_AUTHORITY",
                "https://login.microsoftonline.us/",
                "ENTRA_ALLOWED_CLIENT_IDS",
                "1fc6ab81-a4ce-4b65-b45c-b40e4e3c486f",
                "ENTRA_REQUIRED_SCOPE",
                "mcp.use"));
        assertThat(sovereign.http().entra().orElseThrow().issuer())
                .isEqualTo("https://login.microsoftonline.us/" + TENANT + "/v2.0");
        assertThat(sovereign.http().entra().orElseThrow().allowedClientIds())
                .containsExactly("1fc6ab81-a4ce-4b65-b45c-b40e4e3c486f");
        assertThat(ConfigLoader.load(entra("AUTH_MODE", "entra,apikey", "MCP_API_KEY", KEY))
                        .http()
                        .authModes())
                .containsExactlyInAnyOrder(AuthMode.ENTRA, AuthMode.APIKEY);
    }

    @Test
    void entraRequiresTenantClientAndPublicUrl() {
        assertThatThrownBy(() -> ConfigLoader.load(
                        entra("ENTRA_TENANT_ID", null, "ENTRA_CLIENT_ID", "not-a-guid", "MCP_PUBLIC_URL", null)))
                .isInstanceOfSatisfying(
                        ConfigException.class,
                        ex -> assertThat(ex.problems())
                                .containsExactlyInAnyOrder(
                                        "AUTH_MODE=entra requires MCP_PUBLIC_URL (the https URL clients use, without /mcp)",
                                        "AUTH_MODE=entra requires ENTRA_TENANT_ID",
                                        "ENTRA_CLIENT_ID must be a GUID, got: not-a-guid"));
    }

    @Test
    void publicUrlMustBeHttpsWithoutPath() {
        assertThatThrownBy(() -> ConfigLoader.load(entra("MCP_PUBLIC_URL", "http://mcp.example.ca")))
                .hasMessageContaining("must be an https URL");
        assertThatThrownBy(() -> ConfigLoader.load(entra("MCP_PUBLIC_URL", "https://mcp.example.ca/mcp")))
                .hasMessageContaining("must not have a path");
        assertThat(ConfigLoader.load(entra("MCP_PUBLIC_URL", "http://localhost:8000"))
                        .http()
                        .publicUrl())
                .isPresent();
    }

    @Test
    void entraAuthorityAndClientAllowListAreValidated() {
        assertThatThrownBy(() -> ConfigLoader.load(entra("ENTRA_AUTHORITY", "http://login.example")))
                .hasMessageContaining("ENTRA_AUTHORITY must be an https URL");
        assertThatThrownBy(() -> ConfigLoader.load(entra("ENTRA_ALLOWED_CLIENT_IDS", "claude")))
                .hasMessageContaining("not a GUID: claude");
    }

    @Test
    void rateLimitIsOnByDefault() {
        assertThat(ConfigLoader.load(valid()).http().rateLimit())
                .isEqualTo(new Config.RateLimit(100, Duration.ofMinutes(1)));
        assertThat(ConfigLoader.load(with("RATE_LIMIT_ENABLED", "false", "RATE_LIMIT_MAX_REQUESTS", "0"))
                        .http()
                        .rateLimit()
                        .enabled())
                .as("leftover values of a disabled feature are ignored, as upstream")
                .isFalse();
        assertThat(ConfigLoader.load(with("RATE_LIMIT_MAX_REQUESTS", "10", "RATE_LIMIT_WINDOW_MINUTES", "5"))
                        .http()
                        .rateLimit())
                .isEqualTo(new Config.RateLimit(10, Duration.ofMinutes(5)));
        assertThatThrownBy(() -> ConfigLoader.load(with("RATE_LIMIT_MAX_REQUESTS", "0")))
                .hasMessageContaining("RATE_LIMIT_MAX_REQUESTS must be at least 1");
    }

    @Test
    void toolSearch() {
        assertThat(ConfigLoader.load(valid()).search().enabled()).isFalse();
        assertThat(ConfigLoader.load(with("TOOL_SEARCH_MAX_RESULTS", "0"))
                        .search()
                        .enabled())
                .as("ignored while disabled, as upstream")
                .isFalse();
        Config.Search s = ConfigLoader.load(with(
                        "TOOL_SEARCH_ENABLED", "true", "TOOL_SEARCH_STRATEGY", "REGEX", "TOOL_SEARCH_MAX_RESULTS", "8"))
                .search();
        assertThat(s).isEqualTo(new Config.Search(true, ca.int13.librenmsmcp.search.ToolSearch.Strategy.REGEX, 8));
        assertThat(ConfigLoader.load(with("TOOL_SEARCH_ENABLED", "true")).search())
                .isEqualTo(new Config.Search(true, ca.int13.librenmsmcp.search.ToolSearch.Strategy.BM25, 5));
        assertThatThrownBy(
                        () -> ConfigLoader.load(with("TOOL_SEARCH_ENABLED", "true", "TOOL_SEARCH_STRATEGY", "fuzzy")))
                .hasMessageContaining("TOOL_SEARCH_STRATEGY must be bm25 or regex");
        assertThatThrownBy(() -> ConfigLoader.load(with("TOOL_SEARCH_ENABLED", "true", "TOOL_SEARCH_MAX_RESULTS", "0")))
                .hasMessageContaining("TOOL_SEARCH_MAX_RESULTS must be at least 1");
    }

    @Test
    void metricsAndLogFormat() {
        assertThat(ConfigLoader.load(valid()).http().metricsPort()).isZero();
        assertThat(ConfigLoader.load(with("MCP_METRICS_PORT", "9464")).http().metricsPort())
                .isEqualTo(9464);
        assertThatThrownBy(() -> ConfigLoader.load(with("MCP_METRICS_PORT", "8000")))
                .hasMessageContaining("must differ from MCP_HTTP_PORT");
        assertThat(ConfigLoader.load(with("LOG_FORMAT", "JSON"))).isNotNull();
        assertThatThrownBy(() -> ConfigLoader.load(with("LOG_FORMAT", "xml")))
                .hasMessageContaining("LOG_FORMAT must be text or json");
    }

    @Test
    void allowedOrigins() {
        assertThat(ConfigLoader.load(with("MCP_ALLOWED_ORIGINS", "https://a.example, https://b.example"))
                        .http()
                        .allowedOrigins())
                .containsExactlyInAnyOrder("https://a.example", "https://b.example");
    }

    @Test
    void policySettings() {
        Config c = ConfigLoader.load(with(
                "READ_ONLY_MODE",
                "false",
                "ALLOW_DESTRUCTIVE",
                "yes",
                "DISABLED_TAGS",
                "alert, bills ,,",
                "MCP_LOCAL_ROLE",
                "Admin",
                "MCP_API_KEY_ROLE",
                "operator"));
        assertThat(c.policy().readOnly()).isFalse();
        assertThat(c.policy().allowDestructive()).isTrue();
        assertThat(c.policy().disabledTags()).isEqualTo(Set.of("alert", "bills"));
        assertThat(c.policy().localRole()).isEqualTo(Role.ADMIN);
        assertThat(c.http().apiKeyRole()).isEqualTo(Role.OPERATOR);
    }

    @Test
    void invalidValuesAreReported() {
        assertThatThrownBy(() -> ConfigLoader.load(with(
                        "READ_ONLY_MODE",
                        "maybe",
                        "MCP_HTTP_PORT",
                        "99999",
                        "LIBRENMS_TIMEOUT",
                        "0",
                        "MCP_API_KEY_ROLE",
                        "root")))
                .isInstanceOfSatisfying(
                        ConfigException.class, e -> assertThat(e.problems()).hasSize(4));
    }

    @Test
    void unknownLogLevelFallsBackToInfo() {
        assertThat(ConfigLoader.load(with("LOG_LEVEL", "chatty")).logLevel()).isEqualTo("INFO");
        assertThat(ConfigLoader.load(with("LOG_LEVEL", "warning")).logLevel()).isEqualTo("WARN");
    }

    @Test
    void toStringNeverContainsSecrets() {
        Config c = ConfigLoader.load(valid());
        assertThat(c.toString()).doesNotContain("secret-token").doesNotContain(KEY);
    }
}
