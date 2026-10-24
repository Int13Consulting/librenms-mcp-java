package ca.int13.librenmsmcp.tools.ports;

import ca.int13.librenmsmcp.json.Json;
import ca.int13.librenmsmcp.librenms.LibreNmsClient;
import ca.int13.librenmsmcp.librenms.Pagination;
import ca.int13.librenmsmcp.librenms.PythonText;
import ca.int13.librenmsmcp.tools.ToolArgs;
import ca.int13.librenmsmcp.tools.ToolBinder;
import ca.int13.librenmsmcp.tools.ToolModule;
import ca.int13.librenmsmcp.tools.support.DeviceCheck;
import ca.int13.librenmsmcp.tools.support.NameRefs;
import ca.int13.librenmsmcp.tools.support.Results;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Upstream {@code tools/ports.py}. */
public final class PortTools implements ToolModule {
    /** Fields searched when a term contains '/', which LibreNMS would otherwise read as a field list separator. */
    static final String PORT_SEARCH_DEFAULT_FIELDS = "ifAlias,ifDescr,ifName";

    private final LibreNmsClient client;

    public PortTools(LibreNmsClient client) {
        this.client = client;
    }

    @Override
    public void register(ToolBinder binder) {
        binder.json("ports_list", args -> {
            ObjectNode params = args.object("query") == null
                    ? Json.object()
                    : args.object("query").deepCopy();
            JsonNode deviceId = params.remove("device_id");
            String device =
                    Results.blankToNone(deviceId == null || deviceId.isNull() ? null : PythonText.str(deviceId));
            JsonNode result;
            if (device != null) {
                // /ports ignores device_id; the per-device route filters for real.
                if (!params.has("columns")) {
                    params.put("columns", "port_id,ifName");
                }
                result = DeviceCheck.errorIfEmpty(
                        client, device, client.get(params, "devices", device, "ports"), "ports");
            } else {
                result = client.get(params, "ports");
            }
            return page(result, args, "ports");
        });
        binder.json("ports_search", args -> {
            String search = args.string("search");
            JsonNode result = search.contains("/")
                    ? client.get("ports", "search", PORT_SEARCH_DEFAULT_FIELDS, search)
                    : client.get("ports", "search", search);
            return page(result, args, "ports");
        });
        binder.json("ports_search_field", args -> {
            String search = args.string("search");
            if (search.equals("0")) {
                return Results.errorBody("LibreNMS cannot search ports for \"0\": it treats the term as empty"
                        + " and would search for the field name instead");
            }
            return page(client.get("ports", "search", args.string("field"), search), args, "ports");
        });
        binder.json("ports_search_mac", args -> page(client.get("ports", "mac", args.string("mac")), args, "ports"));
        binder.json("port_get", args -> {
            int id = args.integer("port_id");
            return Results.notFoundIfEmpty(client.get("ports", id), "port", "Port " + id + " does not exist");
        });
        binder.json("port_ip_info", args -> client.get("ports", args.integer("port_id"), "ip"));
        binder.json("port_transceiver", args -> client.get("ports", args.integer("port_id"), "transceiver"));
        binder.json("port_description_get", args -> client.get("ports", args.integer("port_id"), "description"));
        binder.json("port_groups_list", args -> page(client.get("port_groups"), args, null));
        binder.json(
                "port_group_list_ports",
                args -> page(
                        client.get("port_groups", NameRefs.routeRef(client, NameRefs.PORT_GROUP, args.string("name"))),
                        args,
                        null));
        binder.json("port_fdb", args -> page(client.get("ports", args.integer("port_id"), "fdb"), args, "macs"));
        binder.json(
                "port_description_update",
                args -> client.patch(args.raw("payload"), "ports", args.integer("port_id"), "description"));
        binder.json("port_group_add", args -> client.post(args.raw("payload"), "port_groups"));
        binder.json(
                "port_group_assign",
                args -> client.post(args.raw("payload"), "port_groups", args.integer("port_group_id"), "assign"));
        binder.json(
                "port_group_remove",
                args -> client.post(args.raw("payload"), "port_groups", args.integer("port_group_id"), "remove"));
    }

    private static JsonNode page(JsonNode result, ToolArgs args, String key) {
        return Pagination.paginate(result, args.integer("limit"), args.integer("offset"), key);
    }
}
