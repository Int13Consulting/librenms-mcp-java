package ca.int13.librenmsmcp.http;

import static org.assertj.core.api.Assertions.assertThat;

import ca.int13.librenmsmcp.authz.PolicyEngine;
import ca.int13.librenmsmcp.authz.Role;
import ca.int13.librenmsmcp.config.Config;
import ca.int13.librenmsmcp.config.Config.AuthMode;
import ca.int13.librenmsmcp.json.Json;
import ca.int13.librenmsmcp.librenms.LibreNmsClient;
import ca.int13.librenmsmcp.mcp.McpServers;
import ca.int13.librenmsmcp.mcp.ToolDispatcher;
import ca.int13.librenmsmcp.testing.LibreNmsStub;
import ca.int13.librenmsmcp.testing.LibreNmsStub.Answer;
import ca.int13.librenmsmcp.testing.TestTools;
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

/** End to end over Streamable HTTP: Javalin, authentication, the MCP SDK and the dispatcher. */
class HttpServerTest {
    private static final String KEY = "test-api-key-0123456789abcdefghijklmnop";
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private LibreNmsStub stub;
    private final List<HttpServer> servers = new ArrayList<>();

    @BeforeEach
    void start() {
        stub = new LibreNmsStub();
    }

    @AfterEach
    void stop() {
        servers.forEach(HttpServer::close);
        stub.close();
    }

    private URI serve(Set<String> disabledTags) {
        var config = new Config(
                stub.config(),
                Config.Transport.HTTP,
                Config.Http.basic("127.0.0.1", 0, Set.of(AuthMode.APIKEY), Optional.of(KEY), Role.READER),
                new Config.Policy(true, disabledTags, false, Role.READER),
                Config.Search.DISABLED,
                "INFO");
        var client = new LibreNmsClient(config.librenms());
        var dispatcher = new ToolDispatcher(TestTools.registry(client), new PolicyEngine(config.policy()));
        HttpServer server = new HttpServer(config, dispatcher, client, "test").start();
        servers.add(server);
        return URI.create("http://127.0.0.1:" + server.port());
    }

    private static HttpResponse<String> post(URI base, String auth, String body, String... headers) throws Exception {
        var builder = HttpRequest.newBuilder(base.resolve("/mcp"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (auth != null) {
            builder.header("Authorization", auth);
        }
        for (int i = 0; i < headers.length; i += 2) {
            builder.header(headers[i], headers[i + 1]);
        }
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode rpc(URI base, String method, String params) throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\""
                + (params == null ? "" : ",\"params\":" + params) + "}";
        HttpResponse<String> resp = post(base, "Bearer " + KEY, body);
        assertThat(resp.statusCode()).as(resp.body()).isEqualTo(200);
        return Json.parse(resp.body());
    }

    @Test
    void healthNeedsNoAuthAndNeverCallsLibreNms() throws Exception {
        URI base = serve(Set.of());
        HttpResponse<String> resp = HTTP.send(
                HttpRequest.newBuilder(base.resolve("/health")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).isEqualTo("ok");
        assertThat(stub.requests()).isEmpty();
    }

    @Test
    void readinessReflectsLibreNms() throws Exception {
        URI base = serve(Set.of());
        stub.answer(r -> Answer.json(200, "{\"message\":\"pong\"}"));
        assertThat(HTTP.send(
                                HttpRequest.newBuilder(base.resolve("/readyz")).build(),
                                HttpResponse.BodyHandlers.ofString())
                        .statusCode())
                .isEqualTo(200);
        stub.answer(r -> Answer.json(401, "{\"message\":\"Unauthenticated.\"}"));
        assertThat(HTTP.send(
                                HttpRequest.newBuilder(base.resolve("/readyz")).build(),
                                HttpResponse.BodyHandlers.ofString())
                        .statusCode())
                .isEqualTo(503);
    }

    @Test
    void mcpRequiresAValidKey() throws Exception {
        URI base = serve(Set.of());
        String init = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}";
        // RFC 6750: no error code when no bearer credentials were sent, invalid_token when they were wrong.
        for (String auth : new String[] {null, "Basic " + KEY, "Bearer"}) {
            HttpResponse<String> resp = post(base, auth, init);
            assertThat(resp.statusCode()).as(String.valueOf(auth)).isEqualTo(401);
            assertThat(resp.headers().firstValue("WWW-Authenticate")).hasValue("Bearer realm=\"librenms-mcp\"");
        }
        HttpResponse<String> wrong = post(base, "Bearer wrong", init);
        assertThat(wrong.statusCode()).isEqualTo(401);
        assertThat(wrong.headers().firstValue("WWW-Authenticate"))
                .hasValue("Bearer realm=\"librenms-mcp\", error=\"invalid_token\"");
        assertThat(post(base, "bearer " + KEY, init).statusCode()).isEqualTo(200);
    }

    @Test
    void initializeAdvertisesUpstreamIdentity() throws Exception {
        JsonNode result = rpc(
                        serve(Set.of()),
                        "initialize",
                        "{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}")
                .get("result");
        assertThat(result.at("/serverInfo/name").stringValue()).isEqualTo(McpServers.SERVER_NAME);
        assertThat(result.get("instructions").stringValue()).isEqualTo(McpServers.INSTRUCTIONS);
        assertThat(result.at("/capabilities/tools").isObject()).isTrue();
    }

    @Test
    void toolsListIsFilteredByPolicy() throws Exception {
        // Read-only mode: every served tool is a READ tool.
        List<String> all = names(rpc(serve(Set.of()), "tools/list", null));
        assertThat(all).hasSize(73).contains("ping", "system_info", "devices_list", "device_get", "port_graph");
        List<String> filtered = names(rpc(serve(Set.of("devices")), "tools/list", null));
        assertThat(filtered)
                .contains("ping", "system_info")
                .doesNotContain("devices_list", "device_get")
                .hasSizeLessThan(all.size());
    }

    @Test
    void listedToolsMatchUpstreamContracts() throws Exception {
        JsonNode upstream =
                Json.parse(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("contracts/upstream-tools.json")));
        for (JsonNode tool : rpc(serve(Set.of()), "tools/list", null).at("/result/tools")) {
            JsonNode want = null;
            for (JsonNode u : upstream.get("tools")) {
                if (u.get("name").equals(tool.get("name"))) {
                    want = u;
                }
            }
            assertThat(want).as(tool.get("name").stringValue()).isNotNull();
            assertThat(tool.get("title")).isEqualTo(want.get("title"));
            assertThat(tool.get("_meta")).isEqualTo(want.get("meta"));
            assertThat(tool.get("description")).isEqualTo(want.get("description"));
            assertThat(tool.get("inputSchema")).isEqualTo(want.get("inputSchema"));
            // Image tools have no output schema: null upstream, omitted on the wire.
            assertThat(absentIfNull(tool.get("outputSchema"))).isEqualTo(absentIfNull(want.get("outputSchema")));
            assertThat(tool.get("annotations")).isEqualTo(want.get("annotations"));
        }
    }

    @Test
    void callReachesLibreNms() throws Exception {
        URI base = serve(Set.of());
        stub.answer(r -> Answer.json(200, "{\"status\":\"ok\",\"devices\":[{\"device_id\":1}]}"));
        JsonNode result = rpc(base, "tools/call", "{\"name\":\"device_get\",\"arguments\":{\"hostname\":\"core1\"}}")
                .get("result");
        assertThat(result.get("isError").asBoolean()).isFalse();
        assertThat(result.at("/structuredContent/devices/0/device_id").asInt()).isEqualTo(1);
        assertThat(stub.requests()).extracting(LibreNmsStub.Recorded::path).containsExactly("/api/v0/devices/core1");
    }

    @Test
    void callingAHiddenToolIsDenied() throws Exception {
        URI base = serve(Set.of("devices"));
        JsonNode result = rpc(base, "tools/call", "{\"name\":\"device_get\",\"arguments\":{\"hostname\":\"core1\"}}")
                .get("result");
        assertThat(result.get("isError").asBoolean()).isTrue();
        assertThat(stub.requests()).isEmpty();
    }

    @Test
    void invalidArgumentsAreRejectedBeforeTheHandler() throws Exception {
        JsonNode result = rpc(serve(Set.of()), "tools/call", "{\"name\":\"devices_list\",\"arguments\":{\"limit\":0}}")
                .get("result");
        assertThat(result.get("isError").asBoolean()).isTrue();
        assertThat(stub.requests()).isEmpty();
    }

    @Test
    void requestIdIsEchoedOrGenerated() throws Exception {
        URI base = serve(Set.of());
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}";
        assertThat(post(base, "Bearer " + KEY, body, "X-Request-Id", "abc-123")
                        .headers()
                        .firstValue("X-Request-Id"))
                .hasValue("abc-123");
        assertThat(post(base, "Bearer " + KEY, body, "X-Request-Id", "bad id!")
                        .headers()
                        .firstValue("X-Request-Id"))
                .hasValueSatisfying(id -> assertThat(id).hasSize(36));
    }

    @Test
    void malformedMessagesGetCleanJsonRpcErrorsWithoutStackTraces() throws Exception {
        URI base = serve(Set.of());
        for (String body : new String[] {
            "{not json",
            "",
            "[]",
            "\"x\"",
            "{}",
            "{\"jsonrpc\":\"2.0\",\"id\":1}",
            "[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}]"
        }) {
            HttpResponse<String> resp = post(base, "Bearer " + KEY, body);
            assertThat(resp.statusCode()).as(body).isEqualTo(400);
            assertThat(resp.body())
                    .as(body)
                    .doesNotContain("stackTrace")
                    .doesNotContain("io.modelcontextprotocol")
                    .doesNotContain("java.");
            JsonNode error = Json.parse(resp.body());
            assertThat(error.get("jsonrpc").stringValue()).isEqualTo("2.0");
            assertThat(error.at("/error/code").asInt()).isNegative();
            assertThat(error.at("/error/message").stringValue()).isNotBlank();
        }
        // Successful responses are untouched.
        assertThat(Json.parse(post(base, "Bearer " + KEY, "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"ping\"}")
                                .body())
                        .get("id")
                        .asInt())
                .isEqualTo(7);
    }

    @Test
    void securityHeadersOnEveryResponse() throws Exception {
        URI base = serve(Set.of());
        for (HttpResponse<String> resp : List.of(
                post(base, null, "{}"),
                post(base, "Bearer " + KEY, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"),
                HTTP.send(
                        HttpRequest.newBuilder(base.resolve("/health")).build(),
                        HttpResponse.BodyHandlers.ofString()))) {
            assertThat(resp.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff");
            assertThat(resp.headers().firstValue("Cache-Control")).hasValue("no-store");
            assertThat(resp.headers().firstValue("X-Frame-Options")).hasValue("DENY");
            assertThat(resp.headers().firstValue("Server")).isEmpty();
        }
    }

    @Test
    void readinessDoesNotExposeErrorDetails() throws Exception {
        URI base = serve(Set.of());
        stub.answer(r -> Answer.redirect(302, "https://internal-sso.example/login"));
        HttpResponse<String> resp = HTTP.send(
                HttpRequest.newBuilder(base.resolve("/readyz")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(503);
        assertThat(resp.body()).doesNotContain("internal-sso").contains("librenms_unreachable");
    }

    private static JsonNode absentIfNull(JsonNode node) {
        return node == null || node.isNull() ? null : node;
    }

    private static List<String> names(JsonNode listResult) {
        List<String> out = new ArrayList<>();
        listResult.at("/result/tools").forEach(t -> out.add(t.get("name").stringValue()));
        return out;
    }
}
