package ca.int13.librenmsmcp.tools.oxidized;

import ca.int13.librenmsmcp.json.Json;
import ca.int13.librenmsmcp.librenms.LibreNmsClient;
import ca.int13.librenmsmcp.librenms.Pagination;
import ca.int13.librenmsmcp.librenms.PythonText;
import ca.int13.librenmsmcp.tools.ToolBinder;
import ca.int13.librenmsmcp.tools.ToolModule;
import ca.int13.librenmsmcp.tools.support.DeviceCheck;
import ca.int13.librenmsmcp.tools.support.Results;
import java.util.List;
import tools.jackson.databind.JsonNode;

/** Upstream {@code tools/oxidized.py}. */
public final class OxidizedTools implements ToolModule {
    private final LibreNmsClient client;

    public OxidizedTools(LibreNmsClient client) {
        this.client = client;
    }

    @Override
    public void register(ToolBinder binder) {
        binder.json("oxidized_list", args -> {
            List<Object> host =
                    Results.optionalSegment(args.optionalString("hostname").orElse(null));
            JsonNode result = client.get(Results.segments(host, "oxidized"));
            // Oxidized filters on the stored hostname: an empty answer may mean an unknown device, or a
            // device ID / alias that must be translated to the stored hostname.
            if (!host.isEmpty() && result.isArray() && result.isEmpty()) {
                String given = (String) host.getFirst();
                DeviceCheck.Lookup lookup = DeviceCheck.lookupDevice(client, given);
                if (lookup.error() != null) {
                    return lookup.error();
                }
                JsonNode storedName = lookup.device().get("hostname");
                String stored = DeviceCheck.truthy(storedName) ? PythonText.str(storedName) : given;
                if (!stored.equals(given)) {
                    result = client.get("oxidized", stored);
                }
            }
            if (result.isArray()) {
                result = Json.object().set("devices", result);
            }
            return Pagination.paginate(result, args.integer("limit"), args.integer("offset"), "devices");
        });
        // Config bodies may hold secrets: these tools are ADMIN (approved deviation) and their
        // results are never logged.
        binder.json("oxidized_config_get", args -> {
            JsonNode result = client.get("oxidized", "config", args.string("hostname"));
            return result.isArray() ? Json.object().set("configs", result) : result;
        });
        binder.json("oxidized_config_search", args -> {
            String search = args.string("search");
            if (search.contains("/")) {
                // LibreNMS decodes %2F before routing, so the term would split the route.
                String hint = java.util.Arrays.stream(search.split("/", -1))
                        .map(String::strip)
                        .filter(p -> !p.isEmpty())
                        .findFirst()
                        .map(p -> ", e.g. " + PythonText.repr(p))
                        .orElse("");
                return Results.errorBody("LibreNMS cannot search Oxidized configs for a term containing '/' ("
                        + PythonText.repr(search) + "); search for a part without it" + hint + ".");
            }
            JsonNode result = client.get("oxidized", "config", "search", search);
            if (result.isArray()) {
                result = Json.object().set("nodes", result);
            }
            return Pagination.paginate(result, args.integer("limit"), args.integer("offset"), "nodes");
        });
    }
}
