package ca.int13.librenmsmcp.tools.support;

import ca.int13.librenmsmcp.json.Json;
import ca.int13.librenmsmcp.librenms.LibreNmsClient;
import ca.int13.librenmsmcp.librenms.LibreNmsHttpException;
import ca.int13.librenmsmcp.librenms.PythonText;
import ca.int13.librenmsmcp.tools.ToolFailure;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;

/**
 * Port lookups for tools that address a port by interface name (upstream {@code port_lookup.py}).
 * Names like {@code Te2/7} carry an encoded slash that some proxies reject, so a failed by-name
 * request is retried through the port-ID route.
 */
public final class PortLookup {
    private PortLookup() {}

    /** A request that may fail with {@link LibreNmsHttpException}. */
    @FunctionalInterface
    public interface Request<T> {
        T run();
    }

    /** A request by port ID. */
    @FunctionalInterface
    public interface ByIdRequest<T> {
        T run(int portId);
    }

    /** Both the by-name request and the port-ID fallback failed; the message keeps both. */
    public static final class PortFallbackException extends RuntimeException {
        PortFallbackException(RuntimeException byName, RuntimeException fallback) {
            super(byName.getMessage() + "; the lookup by port ID also failed: " + fallback.getMessage(), fallback);
        }
    }

    /**
     * Whether a by-name failure is one the port-ID route avoids: 400/404/500 when the name has a
     * slash or the error did not come from LibreNMS. Auth errors are never retried.
     */
    public static boolean shouldRetryByPortId(LibreNmsHttpException error, String ifname) {
        int status = error.statusCode();
        if (status != 400 && status != 404 && status != 500) {
            return false;
        }
        return ifname.contains("/") || !error.fromLibreNms();
    }

    /** The port ID behind a hostname and interface name, matched exactly and then ignoring case. */
    public static int resolvePortId(LibreNmsClient client, String hostname, String ifname) {
        JsonNode result = client.get(Json.object().put("columns", "port_id,ifName"), "devices", hostname, "ports");
        if (Results.isError(result)) {
            JsonNode message = result.get("message");
            throw new ToolFailure("Could not list ports on " + hostname + ": "
                    + (message != null ? PythonText.str(message) : PythonText.repr(result)));
        }
        Results.Match match = Results.uniqueMatch(Results.objects(result, "ports"), "ifName", ifname);
        if (match.entry() != null) {
            return match.entry().get("port_id").asInt();
        }
        if (!match.ambiguous().isEmpty()) {
            throw new ToolFailure(ambiguous(ifname, hostname, match.ambiguous()));
        }
        throw new ToolFailure("No interface named '" + ifname + "' found on " + hostname + ".");
    }

    private static String ambiguous(String ifname, String hostname, List<JsonNode> matches) {
        String listed = matches.stream()
                .sorted(Comparator.comparing((JsonNode p) -> PythonText.str(p.get("ifName"))))
                .map(p -> PythonText.repr(p.get("ifName")) + " (port_id " + PythonText.str(p.get("port_id")) + ")")
                .collect(Collectors.joining(", "));
        return "Interface name '" + ifname + "' is ambiguous on " + hostname + ": " + listed
                + ". Use port_group_graph or the port tools with a port ID instead.";
    }

    /** Runs the by-name request, retrying through the port-ID route when that can help. */
    public static <T> T withPortIdFallback(
            LibreNmsClient client, String hostname, String ifname, Request<T> byName, ByIdRequest<T> byId) {
        try {
            return byName.run();
        } catch (LibreNmsHttpException byNameError) {
            if (!shouldRetryByPortId(byNameError, ifname)) {
                throw byNameError;
            }
            try {
                return byId.run(resolvePortId(client, hostname, ifname));
            } catch (RuntimeException fallbackError) {
                throw new PortFallbackException(byNameError, fallbackError);
            }
        }
    }
}
