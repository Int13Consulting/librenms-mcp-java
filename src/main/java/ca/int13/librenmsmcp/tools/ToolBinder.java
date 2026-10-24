package ca.int13.librenmsmcp.tools;

import tools.jackson.databind.JsonNode;

/** Binds handlers to upstream tool names during registration. */
public interface ToolBinder {
    /** A tool returning a JSON body (most tools). */
    interface JsonHandler {
        JsonNode call(ToolArgs args) throws Exception;
    }

    void bind(String name, ToolHandler handler);

    default void json(String name, JsonHandler handler) {
        bind(name, args -> new ToolResult.JsonResult(handler.call(args)));
    }
}
