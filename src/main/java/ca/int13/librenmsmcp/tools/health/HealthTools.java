package ca.int13.librenmsmcp.tools.health;

import ca.int13.librenmsmcp.json.Json;
import ca.int13.librenmsmcp.librenms.LibreNmsClient;
import ca.int13.librenmsmcp.librenms.Pagination;
import ca.int13.librenmsmcp.librenms.PythonText;
import ca.int13.librenmsmcp.tools.ToolBinder;
import ca.int13.librenmsmcp.tools.ToolModule;
import ca.int13.librenmsmcp.tools.support.DeviceCheck;
import ca.int13.librenmsmcp.tools.support.Results;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Upstream {@code tools/health.py}. */
public final class HealthTools implements ToolModule {
    private final LibreNmsClient client;

    public HealthTools(LibreNmsClient client) {
        this.client = client;
    }

    @Override
    public void register(ToolBinder binder) {
        binder.json("health_list", args -> {
            String hostname = args.string("hostname");
            JsonNode result =
                    DeviceCheck.errorIfEmpty(client, hostname, client.get("devices", hostname, "health"), "graphs");
            return Pagination.paginate(result, args.integer("limit"), args.integer("offset"), null);
        });
        binder.json("health_by_type", args -> {
            String hostname = args.string("hostname");
            JsonNode result = DeviceCheck.errorIfEmpty(
                    client, hostname, client.get("devices", hostname, "health", args.string("type")), "graphs");
            return Pagination.paginate(result, args.integer("limit"), args.integer("offset"), null);
        });
        binder.json("health_sensor_get", args -> {
            String hostname = args.string("hostname");
            String type = args.string("type");
            int sensorId = args.integer("sensor_id");
            JsonNode result = client.get("devices", hostname, "health", type, sensorId);
            JsonNode sensors = result.isObject() ? result.get("graphs") : null;
            if (result.isObject()
                    && !Results.isError(result)
                    && sensors != null
                    && sensors.isArray()
                    && !sensors.isEmpty()) {
                DeviceCheck.Lookup lookup = DeviceCheck.lookupDevice(client, hostname);
                if (lookup.error() != null) {
                    return lookup.error();
                }
                // The route ignores the hostname and type for some sensor IDs; keep only this device's sensors of this
                // class.
                String deviceId = PythonText.str(lookup.device().get("device_id"));
                String sensorClass = PythonText.casefold(type.startsWith("device_") ? type.substring(7) : type);
                ArrayNode kept = Json.MAPPER.createArrayNode();
                for (JsonNode sensor : sensors) {
                    if (!sensor.isObject()
                            || (str(sensor, "device_id", deviceId).equals(deviceId)
                                    && PythonText.casefold(str(sensor, "sensor_class", sensorClass))
                                            .equals(sensorClass))) {
                        kept.add(sensor);
                    }
                }
                ObjectNode filtered = ((ObjectNode) result).deepCopy();
                filtered.set("graphs", kept);
                result = filtered;
            } else {
                result = DeviceCheck.errorIfEmpty(client, hostname, result, "graphs");
            }
            return Results.notFoundIfEmpty(
                    result, "graphs", "Sensor " + sensorId + " (" + type + ") does not exist on " + hostname);
        });
        binder.json(
                "sensors_list",
                args -> Pagination.paginate(
                        client.get("resources", "sensors"), args.integer("limit"), args.integer("offset"), "sensors"));
    }

    /** Python {@code str(sensor.get(field, default))}. */
    private static String str(JsonNode sensor, String field, String dflt) {
        JsonNode v = sensor.get(field);
        return v == null ? dflt : PythonText.str(v);
    }
}
