package ca.int13.librenmsmcp.tools;

/**
 * A failure reported to the client as an MCP error ({@code isError: true}) with exactly this
 * message. Upstream image tools fail this way; JSON tools instead return an error body.
 */
public final class ToolFailure extends RuntimeException {
    public ToolFailure(String message) {
        super(message);
    }

    public ToolFailure(String message, Throwable cause) {
        super(message, cause);
    }
}
