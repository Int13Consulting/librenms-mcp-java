package ca.int13.librenmsmcp.tools;

/** Implements one tool. Runs only after the dispatcher's policy check has allowed the call. */
@FunctionalInterface
public interface ToolHandler {
    ToolResult call(ToolArgs args) throws Exception;
}
