package ca.int13.librenmsmcp.auth;

import ca.int13.librenmsmcp.authz.Principal;
import ca.int13.librenmsmcp.authz.Role;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * A static API key sent as {@code Authorization: Bearer <key>}, compared in constant time. Any other
 * token is left to the next authenticator (an Entra JWT, for instance).
 */
public final class ApiKeyAuthenticator implements Authenticator {
    private final byte[] key;
    private final Role role;

    public ApiKeyAuthenticator(String key, Role role) {
        this.key = key.getBytes(StandardCharsets.UTF_8);
        this.role = role;
    }

    @Override
    public AuthResult authenticate(String bearerToken) {
        if (bearerToken == null || !MessageDigest.isEqual(key, bearerToken.getBytes(StandardCharsets.UTF_8))) {
            return AuthResult.NOT_APPLICABLE;
        }
        return new AuthResult.Authenticated(new Principal("apikey", null, role, "apikey"));
    }
}
