package ca.int13.librenmsmcp.tools.portsecurity;

import ca.int13.librenmsmcp.librenms.LibreNmsClient;
import ca.int13.librenmsmcp.librenms.Pagination;
import ca.int13.librenmsmcp.tools.ToolBinder;
import ca.int13.librenmsmcp.tools.ToolModule;
import ca.int13.librenmsmcp.tools.support.DeviceCheck;

/** Upstream {@code tools/port_security.py}. */
public final class PortSecurityTools implements ToolModule {
    private final LibreNmsClient client;

    public PortSecurityTools(LibreNmsClient client) {
        this.client = client;
    }

    @Override
    public void register(ToolBinder binder) {
        binder.json(
                "port_security_list",
                args -> Pagination.paginate(
                        client.get("port_security"), args.integer("limit"), args.integer("offset"), "port"));
        binder.json("port_security_device", args -> {
            String hostname = args.string("hostname");
            return Pagination.paginate(
                    DeviceCheck.errorIfEmpty(client, hostname, client.get("port_security", "device", hostname), "port"),
                    args.integer("limit"),
                    args.integer("offset"),
                    "port");
        });
        binder.json("port_security_port", args -> client.get("port_security", "port", args.integer("port_id")));
    }
}
