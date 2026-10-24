package ca.int13.librenmsmcp.mcp;

import ca.int13.librenmsmcp.authz.Principal;
import ca.int13.librenmsmcp.json.Json;
import ca.int13.librenmsmcp.librenms.PythonText;
import ca.int13.librenmsmcp.search.ToolSearch;
import ca.int13.librenmsmcp.tools.ToolDefinition;
import ca.int13.librenmsmcp.tools.ToolSpec;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code search_tools} and {@code call_tool}, which replace the tool list when tool search is on
 * (upstream {@code TOOL_SEARCH_ENABLED}). Their definitions come from {@code contracts/tool-search.json}.
 *
 * <p>Search only sees tools the caller may use, and {@code call_tool} goes through the dispatcher,
 * so it is authorized exactly like a direct call. A tool the caller cannot see is reported as
 * unknown, as upstream reports tools hidden by its visibility rules.
 */
final class SearchTools {
    static final String SEARCH = "search_tools";
    static final String CALL = "call_tool";

    private final ToolDispatcher dispatcher;
    private final ToolSearch search;
    private final JsonNode definitions;

    SearchTools(ToolDispatcher dispatcher, ToolSearch search) {
        this.dispatcher = dispatcher;
        this.search = search;
        this.definitions = read().get(search.strategy() == ToolSearch.Strategy.BM25 ? "bm25" : "regex")
                .get("tools");
    }

    McpSchema.Tool tool(String name, McpJsonMapper json) {
        JsonNode d = definitions.get(name);
        var builder = McpSchema.Tool.builder(name, json, Json.write(d.get("inputSchema")))
                .title(d.path("title").stringValue(null))
                .description(d.get("description").stringValue());
        if (d.has("outputSchema")) {
            builder.outputSchema(Json.toMap(d.get("outputSchema")));
        }
        if (d.has("_meta")) {
            builder.meta(Json.toMap(d.get("_meta")));
        }
        return builder.build();
    }

    McpSchema.CallToolResult call(Principal principal, String name, Map<String, Object> arguments) {
        return name.equals(SEARCH) ? search(principal, arguments) : callTool(principal, arguments);
    }

    private McpSchema.CallToolResult search(Principal principal, Map<String, Object> arguments) {
        String input =
                String.valueOf(arguments.get(search.strategy() == ToolSearch.Strategy.BM25 ? "query" : "pattern"));
        List<ToolSpec> found = search.search(dispatcher.visibleTools(principal), ToolSpec::definition, input);
        ArrayNode results = Json.MAPPER.createArrayNode();
        found.forEach(t -> results.add(describe(t.definition())));
        return McpSchema.CallToolResult.builder()
                .addTextContent(Json.write(results))
                .structuredContent(Map.of("result", Json.MAPPER.convertValue(results, List.class)))
                .isError(false)
                .build();
    }

    private McpSchema.CallToolResult callTool(Principal principal, Map<String, Object> arguments) {
        Object name = arguments.get("name");
        String tool = name == null ? "" : name.toString();
        if (!dispatcher.canSee(principal, tool)) {
            return McpSchema.CallToolResult.builder()
                    .addTextContent("Error calling tool '" + CALL + "': Unknown tool: " + PythonText.repr(tool))
                    .isError(true)
                    .build();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> toolArgs =
                arguments.get("arguments") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        return dispatcher.call(principal, tool, toolArgs);
    }

    /** A tool as {@code tools/list} shows it, in FastMCP's key order. */
    static ObjectNode describe(ToolDefinition d) {
        ObjectNode out = Json.object();
        out.put("name", d.name());
        if (d.title() != null) {
            out.put("title", d.title());
        }
        out.put("description", d.description());
        out.set("inputSchema", d.inputSchema());
        if (d.returnsJson()) {
            out.set("outputSchema", d.outputSchema());
        }
        if (d.annotations() != null && !d.annotations().isNull()) {
            out.set("annotations", d.annotations());
        }
        if (d.meta() != null) {
            out.set("_meta", d.meta());
        }
        return out;
    }

    private static JsonNode read() {
        try (InputStream in = SearchTools.class.getResourceAsStream("/contracts/tool-search.json")) {
            if (in == null) {
                throw new IllegalStateException("missing classpath resource /contracts/tool-search.json");
            }
            return Json.parse(in.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
