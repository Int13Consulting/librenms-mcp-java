package ca.int13.librenmsmcp.tools.logs;

import ca.int13.librenmsmcp.json.Json;
import ca.int13.librenmsmcp.librenms.LibreNmsClient;
import ca.int13.librenmsmcp.tools.ToolArgs;
import ca.int13.librenmsmcp.tools.ToolBinder;
import ca.int13.librenmsmcp.tools.ToolModule;
import ca.int13.librenmsmcp.tools.support.DeviceCheck;
import ca.int13.librenmsmcp.tools.support.Results;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Upstream {@code tools/logs.py}. */
public final class LogTools implements ToolModule {
    private final LibreNmsClient client;

    public LogTools(LibreNmsClient client) {
        this.client = client;
    }

    @Override
    public void register(ToolBinder binder) {
        for (String type : List.of("eventlog", "syslog", "alertlog")) {
            binder.json(
                    "logs_" + type,
                    args -> listLogs(type, args.optionalString("hostname").orElse(null), args));
        }
        binder.json("logs_authlog", args -> listLogs("authlog", null, args));
        binder.json("logs_syslogsink", args -> client.post(args.raw("payload"), "syslogsink"));
    }

    /**
     * LibreNMS drops an unknown hostname filter and answers with every device's logs, so the device
     * is confirmed first. {@code count} is the number of entries returned, not the total.
     */
    private JsonNode listLogs(String type, String hostname, ToolArgs args) {
        int start = args.integer("start");
        int limit = args.integer("limit");
        ObjectNode params = Json.object().put("start", start).put("limit", limit);
        args.optionalString("from_ts").ifPresent(v -> params.put("from", v));
        args.optionalString("to_ts").ifPresent(v -> params.put("to", v));
        args.optionalString("sortorder").ifPresent(v -> params.put("sortorder", v));
        List<Object> host = Results.optionalSegment(hostname);
        if (!host.isEmpty()) {
            DeviceCheck.Lookup lookup = DeviceCheck.lookupDevice(client, (String) host.getFirst());
            if (lookup.error() != null && DeviceCheck.truthy(lookup.error())) {
                return lookup.error();
            }
        }
        JsonNode result = client.get(params, Results.segments(host, "logs", type));
        if (result.isObject() && "ok".equals(result.path("status").stringValue(null))) {
            JsonNode logs = result.get("logs");
            ObjectNode out = (ObjectNode) result;
            out.put("count", logs != null && logs.isArray() ? logs.size() : 0);
            out.put("limit", limit);
            out.put("start", start);
        }
        return result;
    }
}
