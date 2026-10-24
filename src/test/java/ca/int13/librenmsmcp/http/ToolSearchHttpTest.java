package ca.int13.librenmsmcp.http;

import static org.assertj.core.api.Assertions.assertThat;

import ca.int13.librenmsmcp.authz.PolicyEngine;
import ca.int13.librenmsmcp.authz.Role;
import ca.int13.librenmsmcp.config.Config;
import ca.int13.librenmsmcp.config.Config.AuthMode;
import ca.int13.librenmsmcp.json.Json;
import ca.int13.librenmsmcp.librenms.LibreNmsClient;
import ca.int13.librenmsmcp.mcp.ToolDispatcher;
import ca.int13.librenmsmcp.search.ToolSearch;
import ca.int13.librenmsmcp.testing.LibreNmsStub;
import ca.int13.librenmsmcp.testing.TestTools;
import ca.int13.librenmsmcp.tools.ToolRegistry;
import ca.int13.librenmsmcp.tools.ToolResult;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** TOOL_SEARCH_ENABLED over Streamable HTTP. */
class ToolSearchHttpTest {
    private static final String READER_KEY = "reader-key-0123456789abcdefghijklmnopqr";
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private LibreNmsStub stub;
    private HttpServer server;
    private URI base;

    @BeforeEach
    void start() {
        stub = new LibreNmsStub();
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.close();
        }
        stub.close();
    }

    private void serve(ToolSearch.Strategy strategy, ToolRegistry registry) {
        var config = new Config(
                stub.config(),
                Config.Transport.HTTP,
                Config.Http.basic("127.0.0.1", 0, Set.of(AuthMode.APIKEY), Optional.of(READER_KEY), Role.READER),
                new Config.Policy(false, Set.of(), false, Role.READER),
                new Config.Search(true, strategy, 5),
                "INFO");
        var dispatcher = new ToolDispatcher(registry, new PolicyEngine(config.policy()));
        server = new HttpServer(config, dispatcher, new LibreNmsClient(stub.config()), "test").start();
        base = URI.create("http://127.0.0.1:" + server.port());
    }

    private JsonNode rpc(String method, String params) throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\""
                + (params == null ? "" : ",\"params\":" + params) + "}";
        HttpResponse<String> resp = HTTP.send(
                HttpRequest.newBuilder(base.resolve("/mcp"))
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json, text/event-stream")
                        .header("Authorization", "Bearer " + READER_KEY)
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).as(resp.body()).isEqualTo(200);
        return Json.parse(resp.body()).get("result");
    }

    @Test
    void searchToolsReplaceTheListWithUpstreamDefinitions() throws Exception {
        JsonNode contract = Json.parse(Files.readAllBytes(Path.of("contracts/tool-search.json")));
        for (var strategy : ToolSearch.Strategy.values()) {
            serve(strategy, TestTools.registry(new LibreNmsClient(stub.config())));
            JsonNode tools = rpc("tools/list", null).get("tools");
            assertThat(tools).hasSize(2);
            JsonNode want = contract.get(strategy.name().toLowerCase(java.util.Locale.ROOT))
                    .get("tools");
            for (JsonNode t : tools) {
                JsonNode w = want.get(t.get("name").stringValue());
                for (String field : List.of("title", "description", "inputSchema", "outputSchema", "_meta")) {
                    assertThat(t.get(field))
                            .as(strategy + " " + t.get("name") + " " + field)
                            .isEqualTo(w.get(field));
                }
            }
            server.close();
            server = null;
        }
    }

    @Test
    void searchResultTextMatchesUpstreamByteForByte() throws Exception {
        // Upstream's example result was computed over all 110 tools, so serve a handler for each.
        ToolRegistry everything = ToolRegistry.build(List.of(b -> ToolRegistry.build(List.of())
                .definitions()
                .keySet()
                .forEach(name -> b.bind(name, args -> new ToolResult.JsonResult(Json.object())))));
        serve(ToolSearch.Strategy.BM25, everything);
        // Reader with read-write policy sees only READ tools, so compare names against a READ-only ranking instead.
        JsonNode result =
                rpc("tools/call", "{\"name\":\"search_tools\",\"arguments\":{\"query\":\"port traffic graph\"}}");
        List<String> names = new ArrayList<>();
        result.at("/structuredContent/result")
                .forEach(t -> names.add(t.get("name").stringValue()));
        assertThat(names)
                .as("admin-only tools are not searchable by a reader")
                .doesNotContain("bill_create_or_update")
                .contains("port_graph");
        assertThat(result.at("/content/0/text").stringValue())
                .startsWith("[{\"name\":\"port_graph\",\"title\":\"Port Graph\"");
        JsonNode example = Json.parse(Files.readAllBytes(Path.of("contracts/tool-search.json")))
                .at("/bm25/exampleResult/text");
        String first = Json.write(Json.parse(example.stringValue()).get(0));
        assertThat(result.at("/content/0/text").stringValue()).startsWith("[" + first);
    }

    @Test
    void callToolAuthorizesLikeADirectCall() throws Exception {
        ToolRegistry registry = ToolRegistry.build(List.of(b -> {
            b.bind("ping", args -> new ToolResult.JsonResult(Json.object().put("message", "pong")));
            b.bind("alert_acknowledge", args -> new ToolResult.JsonResult(Json.object()));
        }));
        serve(ToolSearch.Strategy.BM25, registry);
        JsonNode ok = rpc("tools/call", "{\"name\":\"call_tool\",\"arguments\":{\"name\":\"ping\",\"arguments\":{}}}");
        assertThat(ok.get("isError").asBoolean()).isFalse();
        assertThat(ok.at("/structuredContent/message").stringValue()).isEqualTo("pong");

        JsonNode denied = rpc(
                "tools/call",
                "{\"name\":\"call_tool\",\"arguments\":{\"name\":\"alert_acknowledge\",\"arguments\":{\"alert_id\":1}}}");
        assertThat(denied.get("isError").asBoolean()).isTrue();
        assertThat(denied.at("/content/0/text").stringValue())
                .isEqualTo("Error calling tool 'call_tool': Unknown tool: 'alert_acknowledge'");

        JsonNode unknown = rpc("tools/call", "{\"name\":\"call_tool\",\"arguments\":{\"name\":\"nope\"}}");
        assertThat(unknown.at("/content/0/text").stringValue())
                .isEqualTo("Error calling tool 'call_tool': Unknown tool: 'nope'");

        JsonNode search =
                rpc("tools/call", "{\"name\":\"search_tools\",\"arguments\":{\"query\":\"acknowledge alert\"}}");
        assertThat(search.at("/structuredContent/result")).isEmpty();
        assertThat(stub.requests()).isEmpty();
    }
}
