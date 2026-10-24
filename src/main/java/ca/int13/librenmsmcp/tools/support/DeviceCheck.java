package ca.int13.librenmsmcp.tools.support;

import ca.int13.librenmsmcp.librenms.LibreNmsClient;
import ca.int13.librenmsmcp.librenms.PythonText;
import java.util.function.Predicate;
import tools.jackson.databind.JsonNode;

/**
 * Device checks for tools that filter LibreNMS data by hostname (upstream {@code device_check.py}).
 *
 * <p>Several LibreNMS endpoints cannot tell "no such device" from "no data": some drop an unknown
 * hostname filter and answer with every device's data, others answer with an empty list. Tools
 * confirm the device exists first where LibreNMS would leak other devices' data, and look it up
 * only after an empty answer otherwise.
 */
public final class DeviceCheck {
    private DeviceCheck() {}

    /** The device record, or the error body explaining why there is none. Exactly one is set. */
    public record Lookup(JsonNode device, JsonNode error) {}

    public static Lookup lookupDevice(LibreNmsClient client, String hostname) {
        JsonNode found = client.get("devices", hostname);
        if (Results.isError(found)) {
            return new Lookup(null, found);
        }
        JsonNode devices = found.isObject() ? found.get("devices") : null;
        if (Results.isOk(found)
                && devices != null
                && devices.isArray()
                && !devices.isEmpty()
                && devices.get(0).isObject()) {
            return new Lookup(devices.get(0), null);
        }
        // Not what LibreNMS answers (an SSO login page, a gateway list, "ok" without a record):
        // fail closed, because the filter this check guards cannot be trusted.
        return new Lookup(
                null,
                Results.errorBody("Unexpected response when looking up device " + PythonText.repr(hostname) + ": "
                        + Results.truncatedRepr(found)));
    }

    /** Upstream {@code device_error_if_empty} with the default "non-empty value" proof. */
    public static JsonNode errorIfEmpty(LibreNmsClient client, String hostname, JsonNode result, String key) {
        return errorIfEmpty(client, hostname, result, key, DeviceCheck::truthy);
    }

    /**
     * The result, unless it is an error or its {@code key} value does not prove the device exists,
     * in which case the device is looked up and its error (if any) returned instead.
     */
    public static JsonNode errorIfEmpty(
            LibreNmsClient client, String hostname, JsonNode result, String key, Predicate<JsonNode> provesDevice) {
        if (result.isObject() && !Results.isError(result) && provesDevice.test(result.get(key))) {
            return result;
        }
        Lookup lookup = lookupDevice(client, hostname);
        return lookup.error() != null ? lookup.error() : result;
    }

    /** Python truthiness of a decoded JSON value. */
    public static boolean truthy(JsonNode v) {
        if (v == null || v.isNull() || v.isMissingNode()) {
            return false;
        }
        if (v.isBoolean()) {
            return v.booleanValue();
        }
        if (v.isNumber()) {
            return v.doubleValue() != 0;
        }
        if (v.isString()) {
            return !v.stringValue().isEmpty();
        }
        return !v.isEmpty();
    }
}
