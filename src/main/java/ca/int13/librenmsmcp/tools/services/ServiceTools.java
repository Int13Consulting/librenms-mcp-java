package ca.int13.librenmsmcp.tools.services;

import ca.int13.librenmsmcp.json.Json;
import ca.int13.librenmsmcp.librenms.LibreNmsClient;
import ca.int13.librenmsmcp.librenms.Pagination;
import ca.int13.librenmsmcp.tools.ToolArgs;
import ca.int13.librenmsmcp.tools.ToolBinder;
import ca.int13.librenmsmcp.tools.ToolModule;
import ca.int13.librenmsmcp.tools.support.DeviceCheck;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Upstream {@code tools/services.py}. */
public final class ServiceTools implements ToolModule {
    private final LibreNmsClient client;

    public ServiceTools(LibreNmsClient client) {
        this.client = client;
    }

    @Override
    public void register(ToolBinder binder) {
        binder.json(
                "services_list",
                args -> Pagination.paginate(
                        flatten(client.get(filters(args), "services")),
                        args.integer("limit"),
                        args.integer("offset"),
                        "services"));
        binder.json("services_for_device", args -> {
            String hostname = args.string("hostname");
            JsonNode result = DeviceCheck.errorIfEmpty(
                    client, hostname, flatten(client.get(filters(args), "services", hostname)), "services");
            return Pagination.paginate(result, args.integer("limit"), args.integer("offset"), "services");
        });
        binder.json("service_add", args -> {
            String hostname = args.string("hostname");
            DeviceCheck.Lookup lookup = DeviceCheck.lookupDevice(client, hostname);
            if (lookup.error() != null && DeviceCheck.truthy(lookup.error())) {
                return lookup.error();
            }
            // LibreNMS needs the ip key; an empty value makes it use the device's hostname.
            ObjectNode payload = ((ObjectNode) args.raw("payload")).deepCopy();
            if (!DeviceCheck.truthy(payload.get("ip"))) {
                payload.put("ip", "");
            }
            return client.post(payload, "services", hostname);
        });
        binder.json("service_edit", args -> client.patch(args.raw("payload"), "services", args.integer("service_id")));
        binder.json("service_delete", args -> client.delete(null, null, "services", args.integer("service_id")));
    }

    private static ObjectNode filters(ToolArgs args) {
        ObjectNode params = Json.object();
        JsonNode state = args.raw("state");
        if (state != null) {
            params.set("state", state);
        }
        args.optionalString("service_type").ifPresent(v -> params.put("type", v));
        return params;
    }

    /** LibreNMS groups services per device ({@code [[...], [...]]}); flatten so pagination counts services. */
    static JsonNode flatten(JsonNode result) {
        if (!result.isObject()) {
            return result;
        }
        JsonNode services = result.get("services");
        if (services == null || !services.isArray()) {
            return result;
        }
        for (JsonNode group : services) {
            if (!group.isArray()) {
                return result;
            }
        }
        ArrayNode flat = Json.MAPPER.createArrayNode();
        services.forEach(group -> group.forEach(flat::add));
        ObjectNode out = ((ObjectNode) result).deepCopy();
        out.set("services", flat);
        return out;
    }
}
