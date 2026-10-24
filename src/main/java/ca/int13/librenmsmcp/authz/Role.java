package ca.int13.librenmsmcp.authz;

import java.util.Locale;
import java.util.Optional;

/** A caller's role. Entra app roles {@code LibreNMS.Reader/Operator/Admin} map onto these. */
public enum Role {
    READER(1),
    OPERATOR(2),
    ADMIN(3);

    private final int level;

    Role(int level) {
        this.level = level;
    }

    /** Roles are cumulative: each permits its own access level and every lower one. */
    public boolean permits(Access access) {
        return level >= required(access);
    }

    /** True when this role permits everything {@code other} does. */
    public boolean atLeast(Role other) {
        return level >= other.level;
    }

    private static int required(Access access) {
        return switch (access) {
            case READ -> 1;
            case OPERATE -> 2;
            case ADMIN -> 3;
        };
    }

    public static Optional<Role> parse(String value) {
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "reader" -> Optional.of(READER);
            case "operator" -> Optional.of(OPERATOR);
            case "admin" -> Optional.of(ADMIN);
            default -> Optional.empty();
        };
    }
}
