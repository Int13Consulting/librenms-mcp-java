package ca.int13.librenmsmcp.tools.network;

import static ca.int13.librenmsmcp.tools.support.Results.errorBody;

import ca.int13.librenmsmcp.json.Json;
import ca.int13.librenmsmcp.librenms.LibreNmsClient;
import ca.int13.librenmsmcp.librenms.Pagination;
import ca.int13.librenmsmcp.librenms.PythonText;
import ca.int13.librenmsmcp.tools.ToolArgs;
import ca.int13.librenmsmcp.tools.ToolBinder;
import ca.int13.librenmsmcp.tools.ToolModule;
import ca.int13.librenmsmcp.tools.support.DeviceCheck;
import ca.int13.librenmsmcp.tools.support.IpNetworks;
import ca.int13.librenmsmcp.tools.support.Results;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Upstream {@code tools/network.py}. */
public final class NetworkTools implements ToolModule {
    static final String IPV6_ARP_ERROR = "LibreNMS ARP lookups are IPv4-only; IPv6 queries are not supported";

    /** Paginated list endpoints with no arguments: tool name → path. */
    private static final Map<String, List<String>> LISTS = Map.of(
            "routing_ip_addresses", List.of("resources", "ip", "addresses"),
            "switching_vlans", List.of("resources", "vlans"),
            "switching_links", List.of("resources", "links"),
            "ospf_list", List.of("ospf"),
            "ospf_ports", List.of("ospf_ports"),
            "vrf_list", List.of("routing", "vrf"));

    private static final List<String> BGP_FILTERS = List.of(
            "asn",
            "remote_asn",
            "remote_address",
            "local_address",
            "bgp_descr",
            "bgp_state",
            "bgp_adminstate",
            "bgp_family");

    private final LibreNmsClient client;

    public NetworkTools(LibreNmsClient client) {
        this.client = client;
    }

    @Override
    public void register(ToolBinder binder) {
        binder.json("arp_search", this::arpSearch);
        binder.json("bgp_sessions", args -> {
            ObjectNode params = Json.object();
            String hostname =
                    Results.blankToNone(args.optionalString("hostname").orElse(null));
            if (hostname != null) {
                params.put("hostname", hostname);
            }
            for (String filter : BGP_FILTERS) {
                JsonNode v = args.raw(filter);
                if (v != null) {
                    params.set(filter, v);
                }
            }
            // LibreNMS drops an unknown hostname filter and returns every device's sessions.
            if (hostname != null) {
                DeviceCheck.Lookup lookup = DeviceCheck.lookupDevice(client, hostname);
                if (lookup.error() != null && DeviceCheck.truthy(lookup.error())) {
                    return lookup.error();
                }
            }
            return paginate(client.get(params, "bgp"), args);
        });
        binder.json("bgp_session_get", args -> client.get("bgp", args.integer("bgp_id")));
        binder.json("bgp_session_edit", args -> client.post(args.raw("payload"), "bgp", args.integer("bgp_id")));
        LISTS.forEach((tool, path) -> binder.json(tool, args -> paginate(client.get(path.toArray()), args)));
        binder.json("fdb_lookup", args -> client.get("resources", "fdb", args.string("mac")));
        binder.json(
                "nac_list",
                args -> paginate(
                        client.get(Results.segments(
                                Results.optionalSegment(
                                        args.optionalString("mac").orElse(null)),
                                "resources",
                                "nac")),
                        args));
    }

    private JsonNode arpSearch(ToolArgs args) {
        String query = args.string("query").strip();
        String device = Results.blankToNone(args.optionalString("device").orElse(null));
        boolean isAll = PythonText.casefold(query).equals("all");
        if (device != null && !isAll) {
            return errorBody("The device parameter can only be used with query \"all\"");
        }
        if (isAll && device == null) {
            return errorBody("Query \"all\" requires the device parameter");
        }
        List<Object> segments = new ArrayList<>(List.of("resources", "ip", "arp", isAll ? "all" : query));
        Optional<IpNetworks.Network> network = IpNetworks.parse(query);
        if (network.isEmpty() && query.contains("/")) {
            return errorBody("Invalid CIDR notation: " + PythonText.repr(query));
        }
        if (network.isPresent()) {
            if (network.get().version() != 4) {
                return errorBody(IPV6_ARP_ERROR);
            }
            if (query.contains("/")) {
                segments.removeLast();
                segments.add(network.get().networkAddress());
                segments.add(Integer.toString(network.get().prefixLength()));
            }
        }
        JsonNode params = device != null ? Json.object().put("device", device) : null;
        JsonNode result = client.get(params, segments.toArray());
        if (device != null) {
            result = DeviceCheck.errorIfEmpty(client, device, result, "arp");
        }
        return Pagination.paginate(result, args.integer("limit"), args.integer("offset"), null);
    }

    private static JsonNode paginate(JsonNode result, ToolArgs args) {
        return Pagination.paginate(result, args.integer("limit"), args.integer("offset"), null);
    }
}
