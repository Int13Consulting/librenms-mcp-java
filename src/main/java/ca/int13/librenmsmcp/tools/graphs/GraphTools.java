package ca.int13.librenmsmcp.tools.graphs;

import ca.int13.librenmsmcp.librenms.LibreNmsClient;
import ca.int13.librenmsmcp.tools.ToolBinder;
import ca.int13.librenmsmcp.tools.ToolFailure;
import ca.int13.librenmsmcp.tools.ToolModule;
import ca.int13.librenmsmcp.tools.support.DeviceCheck;
import ca.int13.librenmsmcp.tools.support.Graphs;
import ca.int13.librenmsmcp.tools.support.PortLookup;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Upstream {@code tools/graphs.py}. */
public final class GraphTools implements ToolModule {
    /** Graphs LibreNMS lists for every device, so their presence does not prove the device exists. */
    static final Set<String> ALWAYS_LISTED_GRAPHS =
            Set.of("device_poller_perf", "device_icmp_perf", "device_ping_perf");

    private final LibreNmsClient client;

    public GraphTools(LibreNmsClient client) {
        this.client = client;
    }

    @Override
    public void register(ToolBinder binder) {
        binder.json("device_graphs_list", args -> {
            String hostname = args.string("hostname");
            return DeviceCheck.errorIfEmpty(
                    client,
                    hostname,
                    client.get("devices", hostname, "graphs"),
                    "graphs",
                    GraphTools::hasDeviceSpecificGraph);
        });
        binder.bind("device_graph", args -> {
            String hostname = args.string("hostname");
            String type = args.string("graph_type");
            ObjectNode params = Graphs.graphParams(args);
            return Graphs.render(
                    type + " graph for " + hostname, () -> client.getRaw(params, "devices", hostname, type));
        });
        binder.bind("port_graph", args -> {
            String hostname = args.string("hostname");
            String ifname = args.string("ifname");
            String graphType = args.string("graph_type");
            ObjectNode params = Graphs.graphParams(args);
            String librenmsType = portGraphType(graphType);
            return Graphs.render(graphType + " graph for " + hostname + " " + ifname, () -> {
                if (!librenmsType.equals("port_bits")) {
                    return client.getRaw(params, "devices", hostname, "ports", ifname, librenmsType);
                }
                // Only the traffic graph has a port-ID route (the multiport graph of one port).
                return PortLookup.withPortIdFallback(
                        client,
                        hostname,
                        ifname,
                        () -> client.getRaw(params, "devices", hostname, "ports", ifname, librenmsType),
                        portId -> client.getRaw(params, "portgroups", "multiport", "bits", portId));
            });
        });
        binder.bind("port_group_graph", args -> {
            String ids = StreamSupport.stream(args.raw("port_ids").spliterator(), false)
                    .map(JsonNode::asString)
                    .collect(Collectors.joining(","));
            ObjectNode params = Graphs.graphParams(args);
            return Graphs.render(
                    "traffic graph for port(s) " + ids,
                    () -> client.getRaw(params, "portgroups", "multiport", "bits", ids));
        });
    }

    static boolean hasDeviceSpecificGraph(JsonNode graphs) {
        if (graphs == null || !graphs.isArray()) {
            return false;
        }
        for (JsonNode g : graphs) {
            if (!g.isObject()
                    || g.get("name") == null
                    || !g.get("name").isString()
                    || !ALWAYS_LISTED_GRAPHS.contains(g.get("name").stringValue())) {
                return true;
            }
        }
        return false;
    }

    /** LibreNMS port graph names are {@code port_<type>}; accept either form, case-insensitively. */
    static String portGraphType(String graphType) {
        String name = graphType.strip().toLowerCase(Locale.ROOT);
        if (name.isEmpty() || name.equals("port_")) {
            throw new ToolFailure("graph_type is required, e.g. 'bits' or 'errors'.");
        }
        return name.startsWith("port_") ? name : "port_" + name;
    }
}
