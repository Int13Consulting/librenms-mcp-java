package ca.int13.librenmsmcp.tools.devices;

import ca.int13.librenmsmcp.json.Json;
import ca.int13.librenmsmcp.librenms.LibreNmsClient;
import ca.int13.librenmsmcp.librenms.LibreNmsHttpException;
import ca.int13.librenmsmcp.librenms.Pagination;
import ca.int13.librenmsmcp.librenms.PythonText;
import ca.int13.librenmsmcp.tools.ToolBinder;
import ca.int13.librenmsmcp.tools.ToolModule;
import ca.int13.librenmsmcp.tools.support.DeviceCheck;
import ca.int13.librenmsmcp.tools.support.NameRefs;
import ca.int13.librenmsmcp.tools.support.PortLookup;
import ca.int13.librenmsmcp.tools.support.Results;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.NullNode;
import tools.jackson.databind.node.ObjectNode;

/** Upstream {@code tools/devices.py}. */
public final class DeviceTools implements ToolModule {
    private final LibreNmsClient client;

    public DeviceTools(LibreNmsClient client) {
        this.client = client;
    }

    @Override
    public void register(ToolBinder binder) {
        binder.json(
                "devices_list",
                args -> Pagination.paginate(
                        client.get(args.object("query"), "devices"),
                        args.integer("limit"),
                        args.integer("offset"),
                        "devices"));
        binder.json("device_get", args -> client.get("devices", args.string("hostname")));
        binder.json("device_ports", args -> {
            String hostname = args.string("hostname");
            ObjectNode params = Json.object();
            args.optionalString("columns").ifPresent(v -> params.put("columns", v));
            JsonNode result = DeviceCheck.errorIfEmpty(
                    client, hostname, client.get(params, "devices", hostname, "ports"), "ports");
            return Pagination.paginate(result, args.integer("limit"), args.integer("offset"), "ports");
        });
        binder.json("device_ports_get", args -> devicePortsGet(args.string("hostname"), args.string("ifname")));
        binder.json("device_availability", args -> {
            String hostname = args.string("hostname");
            return DeviceCheck.errorIfEmpty(
                    client, hostname, client.get("devices", hostname, "availability"), "availability");
        });
        binder.json("device_outages", args -> {
            String hostname = args.string("hostname");
            JsonNode result =
                    DeviceCheck.errorIfEmpty(client, hostname, client.get("devices", hostname, "outages"), "outages");
            return Pagination.paginate(result, args.integer("limit"), args.integer("offset"), "outages");
        });
        binder.json(
                "devicegroups_list",
                args -> Pagination.paginate(
                        client.get("devicegroups"), args.integer("limit"), args.integer("offset"), null));
        binder.json("devicegroup_devices", args -> {
            ObjectNode params = Json.object();
            if (args.optionalBoolean("full").orElse(false)) {
                params.put("full", 1);
            }
            String ref = NameRefs.routeRef(client, NameRefs.DEVICE_GROUP, args.string("name"));
            return Pagination.paginate(
                    client.get(params, "devicegroups", ref), args.integer("limit"), args.integer("offset"), null);
        });
        binder.json("device_maintenance_status", args -> client.get("devices", args.string("hostname"), "maintenance"));
        binder.json(
                "device_vlans",
                args -> Pagination.paginate(
                        client.get("devices", args.string("hostname"), "vlans"),
                        args.integer("limit"),
                        args.integer("offset"),
                        null));
        binder.json("device_links", args -> {
            String hostname = args.string("hostname");
            // LibreNMS drops an unknown hostname and returns every device's links.
            DeviceCheck.Lookup lookup = DeviceCheck.lookupDevice(client, hostname);
            if (lookup.error() != null && DeviceCheck.truthy(lookup.error())) {
                return lookup.error();
            }
            return Pagination.paginate(
                    client.get("devices", hostname, "links"), args.integer("limit"), args.integer("offset"), null);
        });
        binder.json(
                "device_fdb",
                args -> Pagination.paginate(
                        client.get("devices", args.string("hostname"), "fdb"),
                        args.integer("limit"),
                        args.integer("offset"),
                        "ports_fdb"));
        binder.json("device_add", args -> client.post(args.raw("payload"), "devices"));
        binder.json("device_delete", args -> client.delete(null, null, "devices", args.string("hostname")));
        binder.json(
                "device_update",
                args -> client.patch(updatePayload(args.raw("payload")), "devices", args.string("hostname")));
        binder.json(
                "device_set_maintenance",
                args -> client.post(args.raw("payload"), "devices", args.string("hostname"), "maintenance"));
        binder.json("devicegroup_add", args -> client.post(args.raw("payload"), "devicegroups"));
        binder.json(
                "devicegroup_update",
                args -> client.patch(args.raw("payload"), "devicegroups", group(args.string("name"))));
        binder.json(
                "devicegroup_delete", args -> client.delete(null, null, "devicegroups", group(args.string("name"))));
        binder.json(
                "devicegroup_set_maintenance",
                args -> client.post(args.raw("payload"), "devicegroups", group(args.string("name")), "maintenance"));
        binder.json(
                "devicegroup_add_devices",
                args -> client.post(args.raw("payload"), "devicegroups", group(args.string("name")), "devices"));
        binder.json(
                "devicegroup_remove_devices",
                args -> client.delete(
                        null, args.raw("payload"), "devicegroups", group(args.string("name")), "devices"));
        // A GET with side effects: classified non-retry-safe, so it is never re-sent.
        binder.json("device_discover", args -> client.get("devices", args.string("hostname"), "discover"));
        binder.json(
                "device_rename",
                args -> client.patch(null, "devices", args.string("hostname"), "rename", args.string("new_hostname")));
        binder.json(
                "device_eventlog_add",
                args -> client.post(args.raw("payload"), "devices", args.string("hostname"), "eventlog"));
        binder.json(
                "device_nac",
                args -> Pagination.paginate(
                        client.get("devices", args.string("hostname"), "nac"),
                        args.integer("limit"),
                        args.integer("offset"),
                        "ports_nac"));
    }

    private String group(String name) {
        return NameRefs.routeRef(client, NameRefs.DEVICE_GROUP, name);
    }

    /** LibreNMS's update API takes {@code field}/{@code data}: a single pair, or parallel lists. */
    static ObjectNode updatePayload(JsonNode payload) {
        ObjectNode api = Json.object();
        if (payload.size() == 1) {
            var only = payload.properties().iterator().next();
            api.put("field", only.getKey());
            api.set("data", only.getValue());
        } else {
            var fields = Json.MAPPER.createArrayNode();
            var data = Json.MAPPER.createArrayNode();
            payload.properties().forEach(e -> {
                fields.add(e.getKey());
                data.add(e.getValue());
            });
            api.set("field", fields);
            api.set("data", data);
        }
        return api;
    }

    /**
     * A port by interface name. Names like {@code Te2/7} are retried by port ID when a proxy rejects
     * the encoded slash. LibreNMS answers an unknown name with "ok" and no port, so that is turned
     * into an error naming the device or the port.
     */
    private JsonNode devicePortsGet(String hostname, String ifname) {
        JsonNode result;
        try {
            result = PortLookup.withPortIdFallback(
                    client,
                    hostname,
                    ifname,
                    () -> client.request("GET", List.of("devices", hostname, "ports", ifname), null, null, true),
                    portId -> {
                        JsonNode byId = client.request("GET", List.of("ports", portId), null, null, true);
                        JsonNode ports = byId.isObject() ? byId.get("port") : null;
                        if (ports != null && ports.isArray()) {
                            ((ObjectNode) byId).set("port", ports.isEmpty() ? NullNode.getInstance() : ports.get(0));
                        }
                        return byId;
                    });
        } catch (LibreNmsHttpException e) {
            if (e.body() != null) {
                return e.body();
            }
            throw e;
        }
        if (!result.isObject()) {
            return result;
        }
        JsonNode port = result.get("port");
        boolean hasPort = port != null
                && port.isObject()
                && port.get("port_id") != null
                && !port.get("port_id").isNull();
        if ("ok".equals(result.path("status").stringValue(null)) && !hasPort) {
            DeviceCheck.Lookup lookup = DeviceCheck.lookupDevice(client, hostname);
            if (lookup.error() != null && DeviceCheck.truthy(lookup.error())) {
                return lookup.error();
            }
            return Results.errorBody(
                    "No port named " + PythonText.repr(ifname) + " on device " + PythonText.repr(hostname));
        }
        return result;
    }
}
