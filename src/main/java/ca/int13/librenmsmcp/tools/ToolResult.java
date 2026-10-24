package ca.int13.librenmsmcp.tools;

import tools.jackson.databind.JsonNode;

/** What a handler produces. Failures are exceptions; see {@link ToolFailure}. */
public sealed interface ToolResult {
    record JsonResult(JsonNode body) implements ToolResult {}

    record ImageResult(byte[] data, String mimeType) implements ToolResult {}
}
