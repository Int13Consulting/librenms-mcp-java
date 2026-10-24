package ca.int13.librenmsmcp.http;

import static org.assertj.core.api.Assertions.assertThat;

import ca.int13.librenmsmcp.authz.PolicyEngine;
import ca.int13.librenmsmcp.authz.Role;
import ca.int13.librenmsmcp.config.Config;
import ca.int13.librenmsmcp.config.Config.AuthMode;
import ca.int13.librenmsmcp.json.Json;
import ca.int13.librenmsmcp.librenms.LibreNmsClient;
import ca.int13.librenmsmcp.mcp.ToolDispatcher;
import ca.int13.librenmsmcp.testing.LibreNmsStub;
import ca.int13.librenmsmcp.testing.TestEntra;
import ca.int13.librenmsmcp.tools.ToolRegistry;
import ca.int13.librenmsmcp.tools.ToolResult;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Entra-protected Streamable HTTP, end to end. */
class EntraHttpTest {
    private static final String PUBLIC = "https://mcp.example.test";
    private static final String KEY = "test-api-key-0123456789abcdefghijklmnop";
    private static final String LIST = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}";
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private TestEntra entra;
    private LibreNmsStub stub;
    private HttpServer server;
    private URI base;

    @BeforeEach
    void start() {
        entra = new TestEntra();
        stub = new LibreNmsStub();
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.close();
        }
        stub.close();
        entra.close();
    }

    /** Read-write policy, one READ, one OPERATE and one ADMIN tool, so role filtering is visible. */
    private void serve(Set<AuthMode> modes, Config.RateLimit rateLimit, Set<String> origins) {
        var http = new Config.Http(
                "127.0.0.1",
                0,
                modes,
                Optional.of(KEY),
                Role.READER,
                1024 * 1024,
                Optional.of(URI.create(PUBLIC)),
                Optional.of(entra.config()),
                origins,
                rateLimit,
                0);
        var config = new Config(
                stub.config(),
                Config.Transport.HTTP,
                http,
                new Config.Policy(false, Set.of(), false, Role.READER),
                Config.Search.DISABLED,
                "INFO");
        ToolRegistry registry = ToolRegistry.build(List.of(b -> {
            for (String tool : List.of("ping", "alert_acknowledge", "device_update")) {
                b.bind(tool, args -> new ToolResult.JsonResult(Json.object().put("tool", tool)));
            }
        }));
        var dispatcher = new ToolDispatcher(registry, new PolicyEngine(config.policy()));
        server = new HttpServer(config, dispatcher, new LibreNmsClient(stub.config()), "test").start();
        base = URI.create("http://127.0.0.1:" + server.port());
    }

    private void serve() {
        serve(Set.of(AuthMode.ENTRA), Config.RateLimit.DISABLED, Set.of());
    }

    private static HttpResponse<String> post(URI base, String token, String body, String... headers) throws Exception {
        var b = HttpRequest.newBuilder(base.resolve("/mcp"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        for (int i = 0; i < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static List<String> tools(HttpResponse<String> resp) {
        assertThat(resp.statusCode()).as(resp.body()).isEqualTo(200);
        List<String> out = new ArrayList<>();
        Json.parse(resp.body())
                .at("/result/tools")
                .forEach(t -> out.add(t.get("name").stringValue()));
        return out;
    }

    private static String challenge(HttpResponse<String> resp) {
        return resp.headers().firstValue("WWW-Authenticate").orElse("");
    }

    @Test
    void protectedResourceMetadataIsServedAtBothWellKnownPaths() throws Exception {
        serve();
        for (String path :
                List.of("/.well-known/oauth-protected-resource/mcp", "/.well-known/oauth-protected-resource")) {
            HttpResponse<String> resp =
                    HTTP.send(HttpRequest.newBuilder(base.resolve(path)).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(resp.statusCode()).as(path).isEqualTo(200);
            JsonNode m = Json.parse(resp.body());
            assertThat(m.get("resource").stringValue()).isEqualTo(PUBLIC + "/mcp");
            assertThat(m.at("/authorization_servers/0").stringValue()).isEqualTo(entra.issuer());
            assertThat(m.at("/scopes_supported/0").stringValue()).isEqualTo(PUBLIC + "/mcp/access_as_user");
            assertThat(m.at("/bearer_methods_supported/0").stringValue()).isEqualTo("header");
        }
    }

    @Test
    void missingTokenGetsADiscoveryChallengeWithoutAnErrorCode() throws Exception {
        serve();
        HttpResponse<String> resp = post(base, null, LIST);
        assertThat(resp.statusCode()).isEqualTo(401);
        assertThat(challenge(resp))
                .isEqualTo("Bearer resource_metadata=\"" + PUBLIC
                        + "/.well-known/oauth-protected-resource/mcp\", scope=\"" + PUBLIC + "/mcp/access_as_user\"");
    }

    @Test
    void invalidTokenIs401InvalidToken() throws Exception {
        serve();
        for (String token : List.of(
                entra.user().expiresIn(Duration.ofMinutes(-10)).sign(),
                "not-a-jwt",
                entra.user().unsigned())) {
            HttpResponse<String> resp = post(base, token, LIST);
            assertThat(resp.statusCode()).isEqualTo(401);
            assertThat(challenge(resp)).endsWith(", error=\"invalid_token\"");
        }
    }

    @Test
    void validTokenWithoutRoleIs403InsufficientScope() throws Exception {
        serve();
        HttpResponse<String> resp = post(base, entra.user().without("roles").sign(), LIST);
        assertThat(resp.statusCode()).isEqualTo(403);
        assertThat(challenge(resp)).endsWith(", error=\"insufficient_scope\"");
    }

    @Test
    void toolsAreFilteredByEntraRole() throws Exception {
        serve();
        assertThat(tools(post(base, entra.user().sign(), LIST))).containsExactly("ping");
        assertThat(tools(post(
                        base,
                        entra.user()
                                .claim("roles", List.of("LibreNMS.Operator"))
                                .sign(),
                        LIST)))
                .containsExactlyInAnyOrder("ping", "alert_acknowledge");
        assertThat(tools(post(
                        base,
                        entra.app().claim("roles", List.of("LibreNMS.Admin")).sign(),
                        LIST)))
                .containsExactlyInAnyOrder("ping", "alert_acknowledge", "device_update");
    }

    @Test
    void readerCannotCallOperatorToolByName() throws Exception {
        serve();
        String call =
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"alert_acknowledge\",\"arguments\":{\"alert_id\":1}}}";
        JsonNode result =
                Json.parse(post(base, entra.user().sign(), call).body()).get("result");
        assertThat(result.get("isError").asBoolean()).isTrue();
        assertThat(result.at("/content/0/text").stringValue()).contains("requires the operate permission");
        JsonNode ok = Json.parse(post(
                                base,
                                entra.user()
                                        .claim("roles", List.of("LibreNMS.Operator"))
                                        .sign(),
                                call)
                        .body())
                .get("result");
        assertThat(ok.get("isError").asBoolean()).isFalse();
    }

    @Test
    void apiKeyAndEntraCanBeEnabledTogether() throws Exception {
        serve(Set.of(AuthMode.ENTRA, AuthMode.APIKEY), Config.RateLimit.DISABLED, Set.of());
        assertThat(tools(post(base, KEY, LIST))).containsExactly("ping");
        assertThat(tools(post(
                        base,
                        entra.app().claim("roles", List.of("LibreNMS.Admin")).sign(),
                        LIST)))
                .hasSize(3);
        assertThat(post(base, "wrong-key", LIST).statusCode()).isEqualTo(401);
    }

    @Test
    void foreignOriginsAreRefusedEvenWithAValidToken() throws Exception {
        serve(Set.of(AuthMode.ENTRA), Config.RateLimit.DISABLED, Set.of("https://inspector.example"));
        assertThat(post(base, entra.user().sign(), LIST, "Origin", "https://evil.example")
                        .statusCode())
                .isEqualTo(403);
        assertThat(post(base, entra.user().sign(), LIST, "Origin", "null").statusCode())
                .isEqualTo(403);
        assertThat(tools(post(base, entra.user().sign(), LIST, "Origin", "https://inspector.example")))
                .containsExactly("ping");
        assertThat(tools(post(base, entra.user().sign(), LIST)))
                .as("no Origin: non-browser client")
                .containsExactly("ping");
    }

    @Test
    void rateLimitIsPerPrincipal() throws Exception {
        serve(Set.of(AuthMode.ENTRA), new Config.RateLimit(3, Duration.ofMinutes(1)), Set.of());
        String alice = entra.user().sign();
        for (int i = 0; i < 3; i++) {
            assertThat(post(base, alice, LIST).statusCode()).isEqualTo(200);
        }
        HttpResponse<String> limited = post(base, alice, LIST);
        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(limited.headers().firstValue("Retry-After"))
                .hasValueSatisfying(v -> assertThat(Integer.parseInt(v)).isBetween(1, 61));
        String bob = entra.user()
                .claim("oid", "66666666-6666-4666-8666-666666666666")
                .sign();
        assertThat(post(base, bob, LIST).statusCode())
                .as("another principal is unaffected")
                .isEqualTo(200);
        assertThat(post(base, null, LIST).statusCode())
                .as("unauthenticated requests are not counted")
                .isEqualTo(401);
    }
}
