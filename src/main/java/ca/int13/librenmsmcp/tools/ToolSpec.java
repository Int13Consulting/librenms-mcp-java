package ca.int13.librenmsmcp.tools;

import ca.int13.librenmsmcp.authz.Access;
import ca.int13.librenmsmcp.authz.PolicyEngine;
import ca.int13.librenmsmcp.json.Json;
import java.util.Map;
import java.util.Set;

/** A registered tool: upstream contract, approved classification and Java handler. */
public record ToolSpec(
        ToolDefinition definition,
        Classification classification,
        ToolHandler handler,
        Map<String, Object> inputSchemaMap)
        implements PolicyEngine.Subject {

    public ToolSpec(ToolDefinition definition, Classification classification, ToolHandler handler) {
        this(definition, classification, handler, Json.toMap(definition.inputSchema()));
    }

    @Override
    public String name() {
        return definition.name();
    }

    @Override
    public Access access() {
        return classification.access();
    }

    @Override
    public boolean destructive() {
        return classification.destructive();
    }

    @Override
    public Set<String> tags() {
        return definition.tags();
    }
}
