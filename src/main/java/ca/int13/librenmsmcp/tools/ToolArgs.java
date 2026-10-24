package ca.int13.librenmsmcp.tools;

import ca.int13.librenmsmcp.json.Json;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Typed access to a call's arguments. Missing optional arguments take the default declared in the
 * tool's upstream input schema, so defaults cannot drift from what clients are told. Types are
 * already checked against the schema by the MCP SDK before the handler runs.
 */
public final class ToolArgs {
    private final ObjectNode values;
    private final JsonNode properties;

    public ToolArgs(Map<String, Object> arguments, JsonNode inputSchema) {
        this.values = arguments == null ? Json.object() : (ObjectNode) Json.toNode(arguments);
        JsonNode props = inputSchema == null ? null : inputSchema.get("properties");
        this.properties = props == null ? Json.object() : props;
    }

    /** The argument, its schema default, or null. JSON null counts as absent. */
    public JsonNode raw(String name) {
        JsonNode v = values.get(name);
        if (v != null && !v.isNull()) {
            return v;
        }
        JsonNode schema = properties.get(name);
        JsonNode dflt = schema == null ? null : schema.get("default");
        return dflt == null || dflt.isNull() ? null : dflt;
    }

    public String string(String name) {
        return optionalString(name).orElseThrow(() -> missing(name));
    }

    public Optional<String> optionalString(String name) {
        JsonNode v = raw(name);
        return v == null ? Optional.empty() : Optional.of(v.isString() ? v.stringValue() : v.toString());
    }

    public int integer(String name) {
        return optionalInteger(name).orElseThrow(() -> missing(name));
    }

    public Optional<Integer> optionalInteger(String name) {
        JsonNode v = raw(name);
        return v == null ? Optional.empty() : Optional.of(v.asInt());
    }

    public Optional<Boolean> optionalBoolean(String name) {
        JsonNode v = raw(name);
        return v == null ? Optional.empty() : Optional.of(v.asBoolean());
    }

    /** An object argument, or null when absent. */
    public ObjectNode object(String name) {
        JsonNode v = raw(name);
        return v != null && v.isObject() ? (ObjectNode) v : null;
    }

    private static IllegalArgumentException missing(String name) {
        return new IllegalArgumentException("Missing required argument: " + name);
    }
}
