package ca.int13.librenmsmcp.authz;

/** The outcome of a policy check; {@code reason} explains a denial and is safe to show the caller. */
public record Decision(boolean allowed, String reason) {
    static final Decision ALLOW = new Decision(true, null);

    static Decision deny(String reason) {
        return new Decision(false, reason);
    }
}
