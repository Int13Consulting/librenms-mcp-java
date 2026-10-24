package ca.int13.librenmsmcp.tools.system;

import ca.int13.librenmsmcp.librenms.LibreNmsClient;
import ca.int13.librenmsmcp.tools.ToolBinder;
import ca.int13.librenmsmcp.tools.ToolModule;

/** Upstream {@code tools/system.py}. */
public final class SystemTools implements ToolModule {
    private final LibreNmsClient client;

    public SystemTools(LibreNmsClient client) {
        this.client = client;
    }

    @Override
    public void register(ToolBinder binder) {
        binder.json("system_info", args -> client.get("system"));
        binder.json("ping", args -> client.get("ping"));
    }
}
