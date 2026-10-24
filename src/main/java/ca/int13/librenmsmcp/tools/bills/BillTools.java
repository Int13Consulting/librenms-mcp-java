package ca.int13.librenmsmcp.tools.bills;

import ca.int13.librenmsmcp.json.Json;
import ca.int13.librenmsmcp.librenms.LibreNmsClient;
import ca.int13.librenmsmcp.librenms.Pagination;
import ca.int13.librenmsmcp.tools.ToolBinder;
import ca.int13.librenmsmcp.tools.ToolModule;
import ca.int13.librenmsmcp.tools.support.Graphs;
import ca.int13.librenmsmcp.tools.support.Results;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Upstream {@code tools/bills.py}. */
public final class BillTools implements ToolModule {
    private final LibreNmsClient client;

    public BillTools(LibreNmsClient client) {
        this.client = client;
    }

    // Identity comparisons below mirror upstream's Python `is` checks on the same objects.
    @SuppressWarnings("ReferenceEquality")
    @Override
    public void register(ToolBinder binder) {
        binder.json("bills_list", args -> {
            ObjectNode params = Json.object();
            for (String filter : List.of("period", "ref", "custid")) {
                args.optionalString(filter).ifPresent(v -> params.put(filter, v));
            }
            return Pagination.paginate(
                    client.get(params, "bills"), args.integer("limit"), args.integer("offset"), "bills");
        });
        binder.json("bill_get", args -> {
            int id = args.integer("bill_id");
            String period = args.optionalString("period").orElse(null);
            ObjectNode params = Json.object();
            if (period != null) {
                params.put("period", period);
            }
            String missing = "Bill " + id + " does not exist";
            JsonNode result = client.get(params, "bills", id);
            JsonNode checked = Results.notFoundIfEmpty(result, "bills", missing);
            // A bill with no previous period answers "previous" with an empty list; only call it
            // missing if the bill itself does not exist.
            if (checked != result && "previous".equals(Results.blankToNone(period))) {
                JsonNode plain = client.get("bills", id);
                checked = Results.notFoundIfEmpty(plain, "bills", missing);
                if (plain.isObject() && "ok".equals(plain.path("status").stringValue(null)) && checked == plain) {
                    return result;
                }
            }
            return checked;
        });
        binder.bind("bill_graph", args -> {
            int id = args.integer("bill_id");
            String type = args.string("graph_type");
            return Graphs.render(
                    type + " graph for bill " + id,
                    () -> client.getRaw(Graphs.imageParams(), "bills", id, "graphs", type));
        });
        binder.json(
                "bill_graph_data",
                args -> client.get("bills", args.integer("bill_id"), "graphdata", args.string("graph_type")));
        binder.json(
                "bill_history",
                args -> Pagination.paginate(
                        client.get("bills", args.integer("bill_id"), "history"),
                        args.integer("limit"),
                        args.integer("offset"),
                        null));
        binder.bind("bill_history_graph", args -> {
            int id = args.integer("bill_id");
            int history = args.integer("history_id");
            String type = args.string("graph_type");
            return Graphs.render(
                    type + " graph for bill " + id + " history " + history,
                    () -> client.getRaw(Graphs.imageParams(), "bills", id, "history", history, "graphs", type));
        });
        binder.json("bill_create_or_update", args -> client.post(args.raw("payload"), "bills"));
        binder.json("bill_delete", args -> client.delete(null, null, "bills", args.integer("bill_id")));
        binder.json(
                "bill_history_graph_data",
                args -> client.get(
                        "bills",
                        args.integer("bill_id"),
                        "history",
                        args.integer("history_id"),
                        "graphdata",
                        args.string("graph_type")));
    }
}
