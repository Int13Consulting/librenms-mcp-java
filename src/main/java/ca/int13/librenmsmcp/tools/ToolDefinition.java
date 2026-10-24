package ca.int13.librenmsmcp.tools;

import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * A tool's public contract exactly as upstream serves it: name, title, description, schemas,
 * annotations, {@code _meta} and tags. Loaded from {@code contracts/upstream-tools.json}, never hand-copied.
 *
 * @param outputSchema null for tools that return images
 */
public record ToolDefinition(
        String name,
        String module,
        String title,
        String description,
        JsonNode inputSchema,
        JsonNode outputSchema,
        JsonNode annotations,
        JsonNode meta,
        Set<String> tags) {

    public boolean returnsJson() {
        return outputSchema != null && !outputSchema.isNull();
    }
}
