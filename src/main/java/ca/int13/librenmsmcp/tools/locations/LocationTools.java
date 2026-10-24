package ca.int13.librenmsmcp.tools.locations;

import ca.int13.librenmsmcp.librenms.LibreNmsClient;
import ca.int13.librenmsmcp.librenms.Pagination;
import ca.int13.librenmsmcp.tools.ToolBinder;
import ca.int13.librenmsmcp.tools.ToolModule;
import ca.int13.librenmsmcp.tools.support.NameRefs;

/** Upstream {@code tools/locations.py}. */
public final class LocationTools implements ToolModule {
    private final LibreNmsClient client;

    public LocationTools(LibreNmsClient client) {
        this.client = client;
    }

    @Override
    public void register(ToolBinder binder) {
        binder.json(
                "locations_list",
                args -> Pagination.paginate(
                        client.get("resources", "locations"),
                        args.integer("limit"),
                        args.integer("offset"),
                        "locations"));
        binder.json(
                "location_get",
                args -> client.get("location", NameRefs.routeRef(client, NameRefs.LOCATION, args.string("location"))));
        binder.json("location_add", args -> client.post(args.raw("payload"), "locations"));
        binder.json(
                "location_delete",
                args -> client.delete(
                        null,
                        null,
                        "locations",
                        NameRefs.routeRef(client, NameRefs.LOCATION, args.string("location"))));
        binder.json(
                "location_edit",
                args -> client.patch(
                        args.raw("payload"),
                        "locations",
                        NameRefs.routeRef(client, NameRefs.LOCATION, args.string("location"))));
        binder.json(
                "location_set_maintenance",
                args -> client.post(
                        args.raw("payload"),
                        "locations",
                        NameRefs.routeRef(client, NameRefs.LOCATION, args.string("location")),
                        "maintenance"));
    }
}
