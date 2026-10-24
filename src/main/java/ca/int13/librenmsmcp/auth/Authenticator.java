package ca.int13.librenmsmcp.auth;

/** Checks a request's bearer credential. Implementations must be thread-safe. */
public interface Authenticator {
    /** @param bearerToken the {@code Authorization: Bearer} value, or null when there is none */
    AuthResult authenticate(String bearerToken);
}
