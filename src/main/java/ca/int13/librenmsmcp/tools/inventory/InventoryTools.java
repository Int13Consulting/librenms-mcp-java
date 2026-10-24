package ca.int13.librenmsmcp.tools.inventory;

import ca.int13.librenmsmcp.json.Json;
import ca.int13.librenmsmcp.librenms.LibreNmsClient;
import ca.int13.librenmsmcp.librenms.Pagination;
import ca.int13.librenmsmcp.tools.ToolBinder;
import ca.int13.librenmsmcp.tools.ToolModule;
import ca.int13.librenmsmcp.tools.support.DeviceCheck;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Upstream {@code tools/inventory.py}. */
public final class InventoryTools implements ToolModule {
    private final LibreNmsClient client;

    public InventoryTools(LibreNmsClient client) {
        this.client = client;
    }

    @Override
    public void register(ToolBinder binder) {
        binder.json("inventory_device", args -> {
            String hostname = args.string("hostname");
            ObjectNode params = Json.object();
            args.optionalString("ent_physical_class").ifPresent(v -> params.put("entPhysicalClass", v));
            JsonNode containedIn = args.raw("ent_physical_contained_in");
            if (containedIn != null) {
                params.set("entPhysicalContainedIn", containedIn);
            }
            JsonNode result =
                    DeviceCheck.errorIfEmpty(client, hostname, client.get(params, "inventory", hostname), "inventory");
            return Pagination.paginate(result, args.integer("limit"), args.integer("offset"), "inventory");
        });
        binder.json("inventory_device_flat", args -> {
            String hostname = args.string("hostname");
            JsonNode result =
                    DeviceCheck.errorIfEmpty(client, hostname, client.get("inventory", hostname, "all"), "inventory");
            return Pagination.paginate(result, args.integer("limit"), args.integer("offset"), "inventory");
        });
    }
}
