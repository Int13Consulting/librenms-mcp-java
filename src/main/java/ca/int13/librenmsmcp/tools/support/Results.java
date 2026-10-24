package ca.int13.librenmsmcp.tools.support;

import ca.int13.librenmsmcp.json.Json;
import ca.int13.librenmsmcp.librenms.PythonText;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Small result helpers from upstream {@code utils.py}. */
public final class Results {
    private Results() {}

    /** {@code {"status": "error", "message": message}}, LibreNMS's own error shape. */
    public static ObjectNode errorBody(String message) {
        ObjectNode body = Json.object();
        body.put("status", "error");
        body.put("message", message);
        return body;
    }

    public static boolean isError(JsonNode result) {
        return result != null && result.isObject() && "error".equals(text(result.get("status")));
    }

    static boolean isOk(JsonNode result) {
        return result != null && result.isObject() && "ok".equals(text(result.get("status")));
    }

    private static String text(JsonNode v) {
        return v != null && v.isString() ? v.stringValue() : null;
    }

    /** Upstream {@code not_found_if_empty}: an "ok" answer with an empty list under key becomes an error. */
    public static JsonNode notFoundIfEmpty(JsonNode result, String key, String message) {
        if (isOk(result)
                && result.get(key) != null
                && result.get(key).isArray()
                && result.get(key).isEmpty()) {
            return errorBody(message);
        }
        return result;
    }

    /** Upstream {@code error_if_no_alert}: LibreNMS's "No alert by that ID" success becomes an error. */
    public static JsonNode errorIfNoAlert(JsonNode result) {
        if (isOk(result)) {
            JsonNode message = result.get("message");
            String text = message == null ? "" : PythonText.str(message);
            if (PythonText.casefold(text).contains("no alert by that id")) {
                ObjectNode marked = ((ObjectNode) result).deepCopy();
                marked.put("status", "error");
                return marked;
            }
        }
        return result;
    }

    /** Upstream {@code blank_to_none}: the value stripped, or null when unset or blank. */
    public static String blankToNone(String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        return stripped.isEmpty() ? null : stripped;
    }

    /**
     * Upstream {@code optional_segment}: a trailing path segment only when the value is set (not
     * blank). A set value is sent unstripped.
     */
    public static List<Object> optionalSegment(String value) {
        return value != null && blankToNone(value) != null ? List.of(value) : List.of();
    }

    /** Path parts followed by an optional trailing segment, for {@code client.get(..., segments(...))}. */
    public static Object[] segments(List<Object> optionalTail, Object... head) {
        List<Object> all = new ArrayList<>(List.of(head));
        all.addAll(optionalTail);
        return all.toArray();
    }

    /** The entry found, or the matches when ambiguous. */
    public record Match(JsonNode entry, List<JsonNode> ambiguous) {}

    /**
     * Upstream {@code unique_match}: the one entry whose field equals name, exactly and then
     * ignoring case. A stage matching several entries reports them rather than picking one.
     */
    public static Match uniqueMatch(List<JsonNode> entries, String field, String name) {
        for (UnaryOperator<String> normalize : List.<UnaryOperator<String>>of(s -> s, PythonText::casefold)) {
            String wanted = normalize.apply(name);
            List<JsonNode> matches = new ArrayList<>();
            for (JsonNode entry : entries) {
                JsonNode v = entry.get(field);
                if (v != null
                        && !v.isNull()
                        && normalize.apply(PythonText.str(v)).equals(wanted)) {
                    matches.add(entry);
                }
            }
            if (matches.size() == 1) {
                return new Match(matches.getFirst(), List.of());
            }
            if (!matches.isEmpty()) {
                return new Match(null, matches);
            }
        }
        return new Match(null, List.of());
    }

    /** The object entries of the list under key (missing or non-list counts as empty). */
    public static List<JsonNode> objects(JsonNode result, String key) {
        List<JsonNode> out = new ArrayList<>();
        JsonNode list = result == null ? null : result.get(key);
        if (list != null && list.isArray()) {
            list.forEach(e -> {
                if (e.isObject()) {
                    out.add(e);
                }
            });
        }
        return out;
    }

    /** Python {@code str(value)[:200]}. */
    public static String truncatedRepr(JsonNode value) {
        String s = PythonText.str(value);
        return s.codePointCount(0, s.length()) > 200 ? s.substring(0, s.offsetByCodePoints(0, 200)) : s;
    }
}
