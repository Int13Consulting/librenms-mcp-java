package ca.int13.librenmsmcp.tools.support;

import ca.int13.librenmsmcp.librenms.LibreNmsClient;
import ca.int13.librenmsmcp.librenms.PythonText;
import java.math.BigInteger;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;

/**
 * Route-safe references to objects addressed by name or ID (upstream {@code name_refs.py}).
 *
 * <p>LibreNMS decodes {@code %2F} before routing, so a name containing '/' splits the route; such
 * names are translated to their ID. An all-digit reference is read by LibreNMS as an ID, so it is
 * checked against the list to catch a name that collides with another object's ID.
 */
public final class NameRefs {
    private NameRefs() {}

    /** Where to list one kind of named object, and how to read its entries. */
    public record NamedKind(String label, List<String> listPath, String listKey, String nameField) {
        String lower() {
            return label.toLowerCase(Locale.ROOT);
        }
    }

    public static final NamedKind LOCATION =
            new NamedKind("Location", List.of("resources", "locations"), "locations", "location");
    public static final NamedKind DEVICE_GROUP =
            new NamedKind("Device group", List.of("devicegroups"), "groups", "name");
    public static final NamedKind PORT_GROUP = new NamedKind("Port group", List.of("port_groups"), "groups", "name");
    public static final NamedKind POLLER_GROUP =
            new NamedKind("Poller group", List.of("poller_group"), "get_poller_group", "group_name");

    /** A reference that cannot be routed safely; returned to the caller as an error body. */
    public static final class UnroutableRefException extends RuntimeException {
        UnroutableRefException(String message) {
            super(message);
        }
    }

    /** PHP {@code ctype_digit}: ASCII digits only. */
    public static boolean isLibreNmsId(String ref) {
        return !ref.isEmpty() && ref.chars().allMatch(c -> c >= '0' && c <= '9');
    }

    /** The reference unchanged, or the ID of the object it names. */
    // Identity comparisons below mirror upstream's Python `is` checks on the same objects.
    @SuppressWarnings("ReferenceEquality")
    public static String routeRef(LibreNmsClient client, NamedKind kind, String name) {
        boolean isId = isLibreNmsId(name);
        if (!name.contains("/") && !isId) {
            return name;
        }
        JsonNode result = client.get(kind.listPath().toArray());
        if (!result.isObject() || Results.isError(result)) {
            if (isId) {
                // A token may reach an ID without being allowed to list them all.
                return name;
            }
            JsonNode detail = result.isObject() ? result.get("message") : null;
            String why = detail != null && DeviceCheck.truthy(detail)
                    ? PythonText.str(detail)
                    : Results.truncatedRepr(result);
            throw new UnroutableRefException(
                    "Could not look up " + kind.lower() + " " + PythonText.repr(name) + ": " + why);
        }
        List<JsonNode> entries = Results.objects(result, kind.listKey()).stream()
                .filter(e -> e.get("id") != null && !e.get("id").isNull())
                .toList();
        Results.Match match = Results.uniqueMatch(entries, kind.nameField(), name);
        String idRef = isId ? new BigInteger(name).toString() : null;
        JsonNode byId = entries.stream()
                .filter(e -> PythonText.str(e.get("id")).equals(idRef))
                .findFirst()
                .orElse(null);
        if (!match.ambiguous().isEmpty()) {
            String ids = match.ambiguous().stream()
                    .map(e -> PythonText.str(e.get("id")))
                    .collect(Collectors.joining(", "));
            if (byId != null && !match.ambiguous().contains(byId)) {
                throw new UnroutableRefException(PythonText.repr(name) + " is both the ID of " + kind.lower() + " "
                        + PythonText.repr(byId.get(kind.nameField())) + " and the name of " + kind.lower()
                        + "s with IDs " + ids + "; use the name of the first or the ID of one of the others");
            }
            if (byId != null) {
                JsonNode self = byId;
                String others = match.ambiguous().stream()
                        .filter(e -> e != self)
                        .map(e -> PythonText.str(e.get("id")))
                        .collect(Collectors.joining(", "));
                throw new UnroutableRefException(
                        kind.label() + " name " + PythonText.repr(name) + " is shared by IDs " + ids
                                + ", and " + PythonText.repr(name) + " is also the ID of one of them; use ID " + others
                                + " for the others, or rename one so the name is unique");
            }
            throw new UnroutableRefException(
                    kind.label() + " name " + PythonText.repr(name) + " is ambiguous (IDs " + ids + "); use the ID");
        }
        JsonNode entry = match.entry();
        if (isId) {
            if (entry == null || entry == byId) {
                return name;
            }
            if (byId != null) {
                throw new UnroutableRefException(PythonText.repr(name) + " is both the ID of " + kind.lower() + " "
                        + PythonText.repr(byId.get(kind.nameField())) + " and the name of " + kind.lower() + " ID "
                        + PythonText.str(entry.get("id")) + "; use the name of the first or the ID of the second");
            }
        }
        if (entry != null) {
            return PythonText.str(entry.get("id"));
        }
        throw new UnroutableRefException(kind.label() + " " + PythonText.repr(name) + " does not exist");
    }
}
