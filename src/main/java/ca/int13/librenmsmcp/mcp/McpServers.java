package ca.int13.librenmsmcp.mcp;

import ca.int13.librenmsmcp.authz.Principal;
import ca.int13.librenmsmcp.config.Config;
import ca.int13.librenmsmcp.json.Json;
import ca.int13.librenmsmcp.search.ToolSearch;
import ca.int13.librenmsmcp.tools.ToolDefinition;
import ca.int13.librenmsmcp.tools.ToolSpec;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStatelessServerTransport;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import tools.jackson.databind.JsonNode;

/** Builds MCP servers over the SDK's transports, all dispatching through {@link ToolDispatcher}. */
public final class McpServers {
    public static final String SERVER_NAME = "LibreNMS MCP Server";
    /** Upstream's server instructions, verbatim. */
    public static final String INSTRUCTIONS = "This MCP server exposes tools for interacting with the LibreNMS API,"
            + " supporting both read and write operations if not in read-only mode.";
    /** Transport-context key under which the HTTP layer stores the authenticated {@link Principal}. */
    public static final String PRINCIPAL = "principal";

    private McpServers() {}

    public static McpJsonMapper jsonMapper() {
        return McpJsonDefaults.getMapper();
    }

    /**
     * A stateless server for Streamable HTTP. The principal comes from the transport context on every
     * request, so one server instance serves every caller.
     */
    public static McpStatelessSyncServer stateless(
            McpStatelessServerTransport transport, ToolDispatcher dispatcher, Config.Search search, String version) {
        McpJsonMapper json = jsonMapper();
        List<McpStatelessServerFeatures.SyncToolSpecification> tools;
        if (search.enabled()) {
            var searchTools = new SearchTools(dispatcher, new ToolSearch(search.strategy(), search.maxResults()));
            tools = List.of(SearchTools.SEARCH, SearchTools.CALL).stream()
                    .map(name -> McpStatelessServerFeatures.SyncToolSpecification.builder()
                            .tool(searchTools.tool(name, json))
                            .callHandler((ctx, request) -> searchTools.call(principal(ctx), name, args(request)))
                            .build())
                    .toList();
        } else {
            tools = dispatcher.registry().tools().values().stream()
                    .map(spec -> McpStatelessServerFeatures.SyncToolSpecification.builder()
                            .tool(toMcpTool(spec, json))
                            .callHandler((ctx, request) ->
                                    dispatcher.call(principal(ctx), request.name(), request.arguments()))
                            .build())
                    .toList();
        }
        var builder = McpServer.sync(transport)
                .serverInfo(SERVER_NAME, version)
                .instructions(INSTRUCTIONS)
                .capabilities(
                        McpSchema.ServerCapabilities.builder().tools(false).build())
                .validateToolInputs(true)
                .tools(tools);
        if (!search.enabled()) {
            builder.addToolFilter((ctx, tool) -> dispatcher.canSee(principal(ctx), tool.name()));
        }
        return builder.build();
    }

    private static java.util.Map<String, Object> args(McpSchema.CallToolRequest request) {
        return request.arguments() == null ? java.util.Map.of() : request.arguments();
    }

    /**
     * A single-client stdio server. Only tools visible to the local principal are registered.
     * {@code onEndOfInput} runs when the client closes stdin, which is how MCP clients end a stdio
     * session; the SDK transport does not stop the process by itself.
     */
    public static McpSyncServer stdio(
            ToolDispatcher dispatcher, Config.Search search, Principal local, String version, Runnable onEndOfInput) {
        McpJsonMapper json = jsonMapper();
        List<McpServerFeatures.SyncToolSpecification> tools;
        if (search.enabled()) {
            var searchTools = new SearchTools(dispatcher, new ToolSearch(search.strategy(), search.maxResults()));
            tools = List.of(SearchTools.SEARCH, SearchTools.CALL).stream()
                    .map(name -> McpServerFeatures.SyncToolSpecification.builder()
                            .tool(searchTools.tool(name, json))
                            .callHandler((exchange, request) -> searchTools.call(local, name, args(request)))
                            .build())
                    .toList();
        } else {
            tools = dispatcher.visibleTools(local).stream()
                    .map(spec -> McpServerFeatures.SyncToolSpecification.builder()
                            .tool(toMcpTool(spec, json))
                            .callHandler(
                                    (exchange, request) -> dispatcher.call(local, request.name(), request.arguments()))
                            .build())
                    .toList();
        }
        InputStream in = new FilterInputStream(System.in) {
            @Override
            public int read() throws IOException {
                return eof(super.read());
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                return eof(super.read(b, off, len));
            }

            private int eof(int n) {
                if (n < 0) {
                    onEndOfInput.run();
                }
                return n;
            }
        };
        return McpServer.sync(new StdioServerTransportProvider(json, in, System.out))
                .serverInfo(SERVER_NAME, version)
                .instructions(INSTRUCTIONS)
                .capabilities(
                        McpSchema.ServerCapabilities.builder().tools(false).build())
                .tools(tools)
                .build();
    }

    static McpSchema.Tool toMcpTool(ToolSpec spec, McpJsonMapper json) {
        ToolDefinition d = spec.definition();
        var builder = McpSchema.Tool.builder(d.name(), json, Json.write(d.inputSchema()))
                .title(d.title())
                .description(d.description())
                .annotations(annotations(d.annotations()));
        if (d.returnsJson()) {
            builder.outputSchema(Json.toMap(d.outputSchema()));
        }
        if (d.meta() != null) {
            builder.meta(Json.toMap(d.meta()));
        }
        return builder.build();
    }

    private static McpSchema.ToolAnnotations annotations(JsonNode a) {
        if (a == null || a.isNull()) {
            return null;
        }
        return new McpSchema.ToolAnnotations(
                text(a, "title"),
                flag(a, "readOnlyHint"),
                flag(a, "destructiveHint"),
                flag(a, "idempotentHint"),
                flag(a, "openWorldHint"),
                null);
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.stringValue();
    }

    private static Boolean flag(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asBoolean();
    }

    private static Principal principal(McpTransportContext ctx) {
        return ctx == null ? null : (Principal) ctx.get(PRINCIPAL);
    }
}
