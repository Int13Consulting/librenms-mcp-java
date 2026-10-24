package ca.int13.librenmsmcp.testing;

import ca.int13.librenmsmcp.Main;
import ca.int13.librenmsmcp.librenms.LibreNmsClient;
import ca.int13.librenmsmcp.tools.ToolRegistry;

/** Builds the production tool registry for tests. */
public final class TestTools {
    private TestTools() {}

    public static ToolRegistry registry(LibreNmsClient client) {
        return ToolRegistry.build(Main.modules(client));
    }
}
