package ca.int13.librenmsmcp.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import ca.int13.librenmsmcp.authz.PolicyEngine;
import ca.int13.librenmsmcp.authz.Principal;
import ca.int13.librenmsmcp.authz.Role;
import ca.int13.librenmsmcp.config.Config;
import ca.int13.librenmsmcp.json.Json;
import ca.int13.librenmsmcp.librenms.LibreNmsClient;
import ca.int13.librenmsmcp.testing.LibreNmsStub;
import ca.int13.librenmsmcp.testing.TestTools;
import ca.int13.librenmsmcp.tools.ToolFailure;
import ca.int13.librenmsmcp.tools.ToolRegistry;
import ca.int13.librenmsmcp.tools.ToolResult;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ToolDispatcherTest {
    private static final Principal ADMIN = new Principal("u", null, Role.ADMIN, "test");
    private static final Config.Policy OPEN = new Config.Policy(false, Set.of(), true, Role.READER);

    private LibreNmsStub stub;
    private LibreNmsClient client;

    @BeforeEach
    void start() {
        stub = new LibreNmsStub();
        client = new LibreNmsClient(stub.config());
    }

    @AfterEach
    void stop() {
        stub.close();
    }

    private ToolDispatcher production(Config.Policy policy) {
        return new ToolDispatcher(TestTools.registry(client), new PolicyEngine(policy));
    }

    private static String text(McpSchema.CallToolResult r) {
        return ((McpSchema.TextContent) r.content().getFirst()).text();
    }

    @Test
    void deniedCallsNeverReachLibreNms() {
        ToolDispatcher d = production(new Config.Policy(false, Set.of("librenms"), true, Role.READER));
        for (String tool : d.registry().tools().keySet()) {
            McpSchema.CallToolResult r = d.call(ADMIN, tool, Map.of("hostname", "core1"));
            assertThat(r.isError()).as(tool).isTrue();
            assertThat(text(r))
                    .as(tool)
                    .isEqualTo("Tool '" + tool + "' is not permitted: the tool is disabled on this server");
            assertThat(d.canSee(ADMIN, tool)).as(tool).isFalse();
        }
        assertThat(stub.requests()).isEmpty();
    }

    @Test
    void unauthenticatedCallsAreDeniedForEveryTool() {
        ToolDispatcher d = production(OPEN);
        for (String tool : d.registry().tools().keySet()) {
            assertThat(d.call(null, tool, Map.of()).isError()).as(tool).isTrue();
        }
        assertThat(d.visibleTools(null)).isEmpty();
        assertThat(stub.requests()).isEmpty();
    }

    @Test
    void unknownToolIsAnError() {
        McpSchema.CallToolResult r = production(OPEN).call(ADMIN, "nope", Map.of());
        assertThat(r.isError()).isTrue();
        assertThat(text(r)).isEqualTo("Unknown tool: nope");
    }

    @Test
    void jsonToolFailuresUseLibreNmsErrorShapeNotMcpErrors() {
        McpSchema.CallToolResult r = production(OPEN).call(ADMIN, "device_get", Map.of("hostname", ".."));
        assertThat(r.isError()).isFalse();
        assertThat(text(r)).isEqualTo("{\"status\":\"error\",\"message\":\"Invalid path segment: '..'\"}");
        assertThat(r.structuredContent()).isEqualTo(Map.of("status", "error", "message", "Invalid path segment: '..'"));
    }

    @Test
    void retrySafetyIsBoundPerTool() {
        Map<String, Object> seen = new ConcurrentHashMap<>();
        ToolRegistry registry = ToolRegistry.build(List.of(b -> {
            for (String tool : List.of("ping", "device_discover")) {
                b.bind(tool, args -> {
                    seen.put(tool, LibreNmsClient.RETRY_SAFE.isBound() ? LibreNmsClient.RETRY_SAFE.get() : "unbound");
                    return new ToolResult.JsonResult(Json.object());
                });
            }
        }));
        var d = new ToolDispatcher(registry, new PolicyEngine(OPEN));
        d.call(ADMIN, "ping", Map.of());
        d.call(ADMIN, "device_discover", Map.of("hostname", "x"));
        assertThat(seen).containsEntry("ping", true).containsEntry("device_discover", false);
    }

    @Test
    void imageToolFailuresAreMcpErrors() {
        ToolRegistry registry = ToolRegistry.build(List.of(b -> b.bind("device_graph", args -> {
            throw new ToolFailure("Could not render the graph");
        })));
        McpSchema.CallToolResult r = new ToolDispatcher(registry, new PolicyEngine(OPEN))
                .call(ADMIN, "device_graph", Map.of("hostname", "x", "graph_type", "y"));
        assertThat(r.isError()).isTrue();
        assertThat(text(r)).isEqualTo("Could not render the graph");
    }

    @Test
    void readOnlyModeHidesWriteToolsEvenFromAdmins() {
        ToolRegistry registry = ToolRegistry.build(List.of(b -> {
            b.bind("ping", args -> new ToolResult.JsonResult(Json.object()));
            b.bind("alert_acknowledge", args -> new ToolResult.JsonResult(Json.object()));
        }));
        var d = new ToolDispatcher(registry, new PolicyEngine(new Config.Policy(true, Set.of(), true, Role.READER)));
        assertThat(d.visibleTools(ADMIN)).extracting(t -> t.name()).containsExactly("ping");
        assertThat(text(d.call(ADMIN, "alert_acknowledge", Map.of("alert_id", 1))))
                .isEqualTo("Tool 'alert_acknowledge' is not permitted: the server is in read-only mode");
    }
}
