package ca.int13.librenmsmcp.tools.support;

import ca.int13.librenmsmcp.json.Json;
import ca.int13.librenmsmcp.librenms.LibreNmsClient;
import ca.int13.librenmsmcp.tools.ToolArgs;
import ca.int13.librenmsmcp.tools.ToolFailure;
import ca.int13.librenmsmcp.tools.ToolResult;
import java.util.function.Supplier;
import tools.jackson.databind.node.ObjectNode;

/** Graph helpers shared by the graph and bill tools (upstream {@code tools/graphs.py}). */
public final class Graphs {
    private Graphs() {}

    /** LibreNMS renders PNG when asked; otherwise it uses webui.graph_type, which may be SVG. */
    public static ObjectNode imageParams() {
        return Json.object().put("graph_type", "png");
    }

    /** Upstream {@code _graph_params}: time range, size and legend from the standard arguments. */
    public static ObjectNode graphParams(ToolArgs args) {
        ObjectNode params = imageParams();
        args.optionalString("from_time").ifPresent(v -> params.put("from", v));
        args.optionalString("to_time").ifPresent(v -> params.put("to", v));
        args.optionalInteger("width").ifPresent(v -> params.put("width", v));
        args.optionalInteger("height").ifPresent(v -> params.put("height", v));
        args.optionalBoolean("legend").ifPresent(v -> params.put("legend", v ? "yes" : "no"));
        return params;
    }

    /** Upstream {@code _to_image}: anything but an image means LibreNMS answered with an error. */
    public static ToolResult.ImageResult toImage(LibreNmsClient.Raw raw) {
        if (!raw.contentType().startsWith("image/")) {
            throw new ToolFailure("Expected an image from the LibreNMS graph endpoint, got '" + raw.contentType()
                    + "'. This usually means LibreNMS returned a JSON error instead of rendering the graph.");
        }
        return new ToolResult.ImageResult(raw.content(), raw.contentType());
    }

    /**
     * Runs a graph request; a {@link ToolFailure} passes through, any other failure becomes one
     * prefixed with what was being rendered, as upstream wraps exceptions in {@code ToolError}.
     */
    public static ToolResult render(String what, Supplier<LibreNmsClient.Raw> request) {
        try {
            return toImage(request.get());
        } catch (ToolFailure e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ToolFailure("Could not render the " + what + ": " + e.getMessage(), e);
        }
    }
}
