package ca.int13.librenmsmcp.authz;

import java.util.Objects;

/**
 * An authenticated caller.
 *
 * @param subject stable identifier of the user or service (Entra object ID, for audit)
 * @param clientId the client application, when known
 * @param role the effective role
 * @param method how the caller authenticated: {@code entra-user}, {@code entra-app}, {@code apikey}, {@code local}
 * @param name a human-readable name for logs (e.g. the user's UPN), when known
 */
public record Principal(String subject, String clientId, Role role, String method, String name) {
    public Principal {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(method, "method");
    }

    public Principal(String subject, String clientId, Role role, String method) {
        this(subject, clientId, role, method, null);
    }

    public static Principal local(Role role) {
        return new Principal("local", null, role, "local");
    }
}
