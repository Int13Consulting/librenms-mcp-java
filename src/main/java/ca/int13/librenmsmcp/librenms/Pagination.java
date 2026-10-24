package ca.int13.librenmsmcp.librenms;

import ca.int13.librenmsmcp.json.Json;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Client-side pagination, as upstream {@code paginate_list}: LibreNMS returns the whole list and the
 * tool slices it, adding {@code count}, {@code total}, {@code limit} and {@code offset}.
 *
 * <p>Key order matches upstream so the tool text is identical: the paginated list moves to the end,
 * then the metadata keys are set (keeping their position if LibreNMS already sent them).
 */
public final class Pagination {
    private Pagination() {}

    /**
     * @param key the response key holding the list, or null to pick the longest list in the response
     */
    public static JsonNode paginate(JsonNode result, int limit, int offset, String key) {
        if (result.isArray()) {
            ArrayNode items = slice((ArrayNode) result, limit, offset);
            ObjectNode out = Json.object();
            out.set("items", items);
            return withMetadata(out, items.size(), result.size(), limit, offset);
        }
        if (!result.isObject() || isError(result)) {
            return result;
        }
        if (key == null) {
            List<Map.Entry<String, JsonNode>> lists = new ArrayList<>();
            for (Map.Entry<String, JsonNode> e : result.properties()) {
                if (e.getValue().isArray()) {
                    lists.add(e);
                }
            }
            if (lists.isEmpty()) {
                return result;
            }
            // Stable sort, like Python's: ties keep response order.
            lists.sort(Comparator.comparingInt(
                            (Map.Entry<String, JsonNode> e) -> e.getValue().size())
                    .reversed());
            key = lists.getFirst().getKey();
        }
        JsonNode items = result.get(key);
        if (items == null || !items.isArray()) {
            return result;
        }
        ObjectNode out = Json.object();
        for (Map.Entry<String, JsonNode> e : result.properties()) {
            if (!e.getKey().equals(key)) {
                out.set(e.getKey(), e.getValue());
            }
        }
        ArrayNode page = slice((ArrayNode) items, limit, offset);
        out.set(key, page);
        return withMetadata(out, page.size(), items.size(), limit, offset);
    }

    private static boolean isError(JsonNode result) {
        JsonNode status = result.get("status");
        return status != null && status.isString() && status.stringValue().equals("error");
    }

    private static ArrayNode slice(ArrayNode items, int limit, int offset) {
        ArrayNode page = Json.MAPPER.createArrayNode();
        int end = (int) Math.min((long) offset + limit, items.size());
        for (int i = offset; i < end; i++) {
            page.add(items.get(i));
        }
        return page;
    }

    private static ObjectNode withMetadata(ObjectNode out, int count, int total, int limit, int offset) {
        out.put("count", count);
        out.put("total", total);
        out.put("limit", limit);
        out.put("offset", offset);
        return out;
    }
}
