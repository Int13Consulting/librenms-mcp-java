package ca.int13.librenmsmcp.tools.pollers;

import static ca.int13.librenmsmcp.tools.support.Results.errorBody;
import static ca.int13.librenmsmcp.tools.support.Results.optionalSegment;

import ca.int13.librenmsmcp.librenms.LibreNmsClient;
import ca.int13.librenmsmcp.librenms.PythonText;
import ca.int13.librenmsmcp.tools.ToolBinder;
import ca.int13.librenmsmcp.tools.ToolModule;
import ca.int13.librenmsmcp.tools.support.NameRefs;
import java.math.BigInteger;
import java.util.List;

/** Upstream {@code tools/pollers.py}. */
public final class PollerTools implements ToolModule {
    private final LibreNmsClient client;

    public PollerTools(LibreNmsClient client) {
        this.client = client;
    }

    @Override
    public void register(ToolBinder binder) {
        binder.json("poller_group_get", args -> {
            String group = args.optionalString("poller_group").orElse(null);
            if (group != null && PythonText.casefold(group.strip()).equals("all")) {
                group = null;
            }
            List<Object> tail = optionalSegment(group);
            if (!tail.isEmpty()) {
                String ref = NameRefs.routeRef(client, NameRefs.POLLER_GROUP, (String) tail.getFirst());
                if (NameRefs.isLibreNmsId(ref) && new BigInteger(ref).signum() == 0) {
                    return errorBody("Poller group 0 (the default group) cannot be looked up by ID;"
                            + " omit poller_group to list every group");
                }
                return client.get("poller_group", ref);
            }
            return client.get("poller_group");
        });
    }
}
