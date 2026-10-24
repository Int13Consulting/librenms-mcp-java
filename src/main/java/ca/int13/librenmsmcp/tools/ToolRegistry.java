package ca.int13.librenmsmcp.tools;

import ca.int13.librenmsmcp.authz.Access;
import ca.int13.librenmsmcp.json.Json;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;

/**
 * Joins the upstream contracts, the approved classification and the Java handlers into the set of
 * served tools. Registration fails closed: a handler for a tool that upstream does not have, or
 * that has no classification, stops startup.
 */
public final class ToolRegistry {
    static final String UPSTREAM_TOOLS = "/contracts/upstream-tools.json";
    static final String CLASSIFICATION = "/contracts/tool-classification.json";

    private final Map<String, ToolDefinition> definitions;
    private final Map<String, Classification> classifications;
    private final Map<String, ToolSpec> tools;

    private ToolRegistry(
            Map<String, ToolDefinition> definitions,
            Map<String, Classification> classifications,
            Map<String, ToolSpec> tools) {
        this.definitions = definitions;
        this.classifications = classifications;
        this.tools = tools;
    }

    public static ToolRegistry build(List<ToolModule> modules) {
        return build(read(UPSTREAM_TOOLS), read(CLASSIFICATION), modules);
    }

    static ToolRegistry build(JsonNode upstream, JsonNode classification, List<ToolModule> modules) {
        Map<String, ToolDefinition> definitions = parseDefinitions(upstream);
        Map<String, Classification> classifications = parseClassifications(classification);
        List<String> problems = new ArrayList<>();
        for (String name : new TreeSet<>(classifications.keySet())) {
            if (!definitions.containsKey(name)) {
                problems.add("classified tool is not an upstream tool: " + name);
            }
        }
        Map<String, ToolSpec> tools = new LinkedHashMap<>();
        ToolBinder binder = (name, handler) -> {
            ToolDefinition definition = definitions.get(name);
            Classification c = classifications.get(name);
            if (definition == null) {
                problems.add("handler bound for unknown tool: " + name);
            } else if (c == null) {
                problems.add("handler bound for unclassified tool: " + name);
            } else if (tools.putIfAbsent(name, new ToolSpec(definition, c, handler)) != null) {
                problems.add("handler bound twice: " + name);
            }
        };
        modules.forEach(m -> m.register(binder));
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Tool registry is inconsistent:\n  - " + String.join("\n  - ", problems));
        }
        // Serve in upstream's order, which tools/list and tool-search ties follow.
        Map<String, ToolSpec> ordered = new LinkedHashMap<>();
        definitions.keySet().forEach(name -> {
            if (tools.containsKey(name)) {
                ordered.put(name, tools.get(name));
            }
        });
        return new ToolRegistry(definitions, classifications, Collections.unmodifiableMap(ordered));
    }

    /** Served tools, in upstream's order. */
    public Map<String, ToolSpec> tools() {
        return tools;
    }

    public Optional<ToolSpec> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    /** Upstream tools that have no Java handler yet. */
    public Set<String> unimplemented() {
        Set<String> missing = new TreeSet<>(definitions.keySet());
        missing.removeAll(tools.keySet());
        return missing;
    }

    /** Every upstream tool definition, in upstream order, whether served or not. */
    public Map<String, ToolDefinition> definitions() {
        return definitions;
    }

    Map<String, Classification> classifications() {
        return classifications;
    }

    private static Map<String, ToolDefinition> parseDefinitions(JsonNode upstream) {
        Map<String, ToolDefinition> out = new LinkedHashMap<>();
        for (JsonNode t : upstream.get("tools")) {
            Set<String> tags = new TreeSet<>();
            t.get("tags").forEach(tag -> tags.add(tag.stringValue()));
            JsonNode output = t.get("outputSchema");
            JsonNode title = t.get("title");
            JsonNode meta = t.get("meta");
            out.put(
                    t.get("name").stringValue(),
                    new ToolDefinition(
                            t.get("name").stringValue(),
                            t.get("module").stringValue(),
                            title == null || title.isNull() ? null : title.stringValue(),
                            t.get("description").stringValue(),
                            t.get("inputSchema"),
                            output == null || output.isNull() ? null : output,
                            t.get("annotations"),
                            meta == null || meta.isNull() ? null : meta,
                            Set.copyOf(tags)));
        }
        return out;
    }

    private static Map<String, Classification> parseClassifications(JsonNode classification) {
        Map<String, Classification> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> e : classification.get("tools").properties()) {
            JsonNode c = e.getValue();
            out.put(
                    e.getKey(),
                    new Classification(
                            Access.valueOf(c.get("access").stringValue()),
                            c.get("destructive").asBoolean(),
                            c.get("retrySafe").asBoolean()));
        }
        return out;
    }

    private static JsonNode read(String resource) {
        try (InputStream in = ToolRegistry.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("missing classpath resource " + resource);
            }
            return Json.parse(in.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
