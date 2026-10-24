package ca.int13.librenmsmcp.tools.alerts;

import ca.int13.librenmsmcp.json.Json;
import ca.int13.librenmsmcp.librenms.LibreNmsClient;
import ca.int13.librenmsmcp.librenms.Pagination;
import ca.int13.librenmsmcp.librenms.PythonText;
import ca.int13.librenmsmcp.tools.ToolBinder;
import ca.int13.librenmsmcp.tools.ToolModule;
import ca.int13.librenmsmcp.tools.support.NameRefs;
import ca.int13.librenmsmcp.tools.support.Results;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Upstream {@code tools/alerts.py}. */
public final class AlertTools implements ToolModule {
    /** Every alert state, so a lookup by ID finds the alert whatever its state. */
    static final String ALL_ALERT_STATES =
            IntStream.range(0, 16).mapToObj(Integer::toString).collect(Collectors.joining(","));

    private final LibreNmsClient client;

    public AlertTools(LibreNmsClient client) {
        this.client = client;
    }

    @Override
    public void register(ToolBinder binder) {
        binder.json("alerts_get", args -> {
            ObjectNode params = Json.object();
            for (String filter : List.of("state", "severity", "alert_rule", "order")) {
                JsonNode v = args.raw(filter);
                if (v != null) {
                    params.set(filter, v);
                }
            }
            return Pagination.paginate(
                    client.get(params, "alerts"), args.integer("limit"), args.integer("offset"), "alerts");
        });
        binder.json("alert_get_by_id", args -> {
            int id = args.integer("alert_id");
            return Results.notFoundIfEmpty(
                    client.get(Json.object().put("state", ALL_ALERT_STATES), "alerts", id),
                    "alerts",
                    "Alert " + id + " does not exist");
        });
        binder.json(
                "alert_rules_list",
                args -> Pagination.paginate(client.get("rules"), args.integer("limit"), args.integer("offset"), null));
        binder.json("alert_rule_get", args -> {
            int id = args.integer("rule_id");
            return Results.notFoundIfEmpty(client.get("rules", id), "rules", "Alert rule " + id + " does not exist");
        });
        binder.json(
                "alert_templates_list",
                args -> Pagination.paginate(
                        client.get("alert_templates"),
                        args.integer("limit"),
                        args.integer("offset"),
                        "alert_templates"));
        binder.json("alert_acknowledge", args -> {
            ObjectNode data = Json.object();
            args.optionalString("note").ifPresent(v -> data.put("note", v));
            args.optionalBoolean("until_clear").ifPresent(v -> data.put("until_clear", v));
            return Results.errorIfNoAlert(client.put(data.isEmpty() ? null : data, "alerts", args.integer("alert_id")));
        });
        binder.json(
                "alert_unmute",
                args -> Results.errorIfNoAlert(client.put(null, "alerts", "unmute", args.integer("alert_id"))));
        binder.json("alert_rule_add", args -> {
            JsonNode payload = args.raw("payload");
            JsonNode error = addIdError(payload, "rule_id", "alert rule", "alert_rule_edit");
            return error != null ? error : client.post(payload, "rules");
        });
        binder.json("alert_rule_edit", args -> {
            JsonNode payload = args.raw("payload");
            JsonNode error = editIdError(payload, "rule_id", "alert rule");
            return error != null ? error : client.put(payload, "rules");
        });
        binder.json("alert_rule_delete", args -> client.delete(null, null, "rules", args.integer("rule_id")));
        binder.json("alert_template_create", args -> {
            JsonNode payload = args.raw("payload");
            JsonNode error = addIdError(payload, "template_id", "alert template", "alert_template_edit");
            return error != null ? error : client.post(payload, "alert_templates");
        });
        binder.json("alert_template_edit", args -> {
            JsonNode payload = args.raw("payload");
            JsonNode error = editIdError(payload, "template_id", "alert template");
            return error != null ? error : client.put(payload, "alert_templates");
        });
        binder.json("alert_template_get", args -> {
            int id = args.integer("template_id");
            return Results.notFoundIfEmpty(
                    client.get("alert_templates", id), "alert_templates", "Alert template " + id + " does not exist");
        });
    }

    /**
     * Edits address the object by an ID inside the payload, so a missing or invalid one would make
     * LibreNMS create a new object or fail obscurely: require a positive integer (or digit string).
     */
    static JsonNode editIdError(JsonNode payload, String key, String kind) {
        JsonNode value = payload.get(key);
        if (value != null
                && value.isString()
                && NameRefs.isLibreNmsId(value.stringValue())
                && new java.math.BigInteger(value.stringValue()).signum() > 0) {
            return null;
        }
        if (value != null && value.isIntegralNumber() && value.bigIntegerValue().signum() > 0) {
            return null;
        }
        return Results.errorBody(
                "payload must include " + key + ", the ID of the " + kind + " to edit, got " + PythonText.repr(value));
    }

    /** Adding with an ID would edit (or collide with) an existing object instead. */
    static JsonNode addIdError(JsonNode payload, String key, String kind, String editTool) {
        JsonNode value = payload.get(key);
        if (value == null || value.isNull()) {
            return null;
        }
        return Results.errorBody("payload must not include " + key + " when adding an " + kind + "; use " + editTool
                + " to change an existing one");
    }
}
