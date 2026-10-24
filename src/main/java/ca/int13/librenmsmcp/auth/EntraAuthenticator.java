package ca.int13.librenmsmcp.auth;

import ca.int13.librenmsmcp.auth.AuthResult.Error;
import ca.int13.librenmsmcp.authz.Principal;
import ca.int13.librenmsmcp.authz.Role;
import ca.int13.librenmsmcp.config.Config;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.DefaultJOSEObjectTypeVerifier;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import java.net.MalformedURLException;
import java.text.ParseException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Validates Microsoft Entra ID v2 access tokens issued for this API.
 *
 * <p>Accepted: RS256-signed JWTs from {@code <authority>/<tenant>/v2.0} with {@code tid} = tenant,
 * {@code ver} = 2.0, {@code aud} = the API client ID (or {@code api://} form), within
 * {@code exp}/{@code nbf} plus the configured skew. Signing keys come from the tenant JWKS, cached
 * and refreshed when an unknown key ID appears.
 *
 * <p>Then the token must say who may do what:
 *
 * <ul>
 *   <li>delegated (user) tokens carry {@code scp}, which must include the required scope;
 *   <li>app-only tokens carry {@code roles} and no {@code scp};
 *   <li>either way, {@code roles} must include a LibreNMS app role; the highest one wins.
 * </ul>
 *
 * Tokens with neither {@code scp} nor {@code roles} (ID tokens, tokens for other APIs) are refused.
 * Group claims are ignored.
 */
public final class EntraAuthenticator implements Authenticator {
    static final Map<String, Role> APP_ROLES =
            Map.of("LibreNMS.Reader", Role.READER, "LibreNMS.Operator", Role.OPERATOR, "LibreNMS.Admin", Role.ADMIN);

    private final Config.Entra config;
    private final DefaultJWTProcessor<SecurityContext> processor;

    public EntraAuthenticator(Config.Entra config) {
        this(config, keySource(config, true));
    }

    EntraAuthenticator(Config.Entra config, JWKSource<SecurityContext> keys) {
        this.config = config;
        var p = new DefaultJWTProcessor<SecurityContext>();
        p.setJWSTypeVerifier(new DefaultJOSEObjectTypeVerifier<>(JOSEObjectType.JWT, null));
        p.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, keys));
        var verifier = new DefaultJWTClaimsVerifier<SecurityContext>(
                Set.of(config.clientId(), "api://" + config.clientId()),
                new JWTClaimsSet.Builder()
                        .issuer(config.issuer())
                        .claim("tid", config.tenantId())
                        .claim("ver", "2.0")
                        .build(),
                Set.of("exp", "iat", "nbf", "sub", "oid"),
                null);
        verifier.setMaxClockSkew((int) config.clockSkew().toSeconds());
        p.setJWTClaimsSetVerifier(verifier);
        this.processor = p;
    }

    @Override
    public AuthResult authenticate(String bearerToken) {
        if (bearerToken == null || bearerToken.chars().filter(c -> c == '.').count() != 2) {
            return AuthResult.NOT_APPLICABLE; // not a JWS compact serialization
        }
        JWTClaimsSet claims;
        try {
            claims = processor.process(bearerToken, null);
        } catch (Exception e) {
            return new AuthResult.Rejected(Error.INVALID_TOKEN, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        try {
            return authorize(claims);
        } catch (ParseException e) {
            return new AuthResult.Rejected(Error.INVALID_TOKEN, "malformed claim: " + e.getMessage());
        }
    }

    private AuthResult authorize(JWTClaimsSet claims) throws ParseException {
        String scp = claims.getStringClaim("scp");
        List<String> roles = claims.getStringListClaim("roles");
        String idtyp = claims.getStringClaim("idtyp");

        boolean delegated;
        if (idtyp == null) {
            delegated = scp != null;
        } else if (idtyp.equals("user") || idtyp.equals("app")) {
            delegated = idtyp.equals("user");
            if (delegated != (scp != null)) {
                return new AuthResult.Rejected(Error.INVALID_TOKEN, "idtyp=" + idtyp + " inconsistent with scp");
            }
        } else {
            return new AuthResult.Rejected(Error.INVALID_TOKEN, "unexpected idtyp " + idtyp);
        }
        if (!delegated && roles == null) {
            return new AuthResult.Rejected(
                    Error.INVALID_TOKEN, "token has neither scp nor roles; not an access token for this API");
        }
        if (delegated && !Arrays.asList(scp.split(" ")).contains(config.requiredScope())) {
            return new AuthResult.Rejected(
                    Error.INSUFFICIENT_SCOPE, "scope " + config.requiredScope() + " missing (scp=" + scp + ")");
        }

        String client = Optional.ofNullable(claims.getStringClaim("azp")).orElse(claims.getStringClaim("appid"));
        if (!config.allowedClientIds().isEmpty()
                && (client == null || !config.allowedClientIds().contains(client.toLowerCase(java.util.Locale.ROOT)))) {
            return new AuthResult.Rejected(
                    Error.INSUFFICIENT_SCOPE, "client " + client + " is not in ENTRA_ALLOWED_CLIENT_IDS");
        }

        Optional<Role> role = roles == null
                ? Optional.empty()
                : roles.stream().map(APP_ROLES::get).filter(Objects::nonNull).max(Enum::compareTo);
        if (role.isEmpty()) {
            return new AuthResult.Rejected(
                    Error.INSUFFICIENT_SCOPE, "no LibreNMS app role assigned (roles=" + roles + ")");
        }
        String name = delegated ? claims.getStringClaim("preferred_username") : null;
        return new AuthResult.Authenticated(new Principal(
                claims.getStringClaim("oid"), client, role.get(), delegated ? "entra-user" : "entra-app", name));
    }

    /**
     * The tenant key set. Nimbus caches it, refreshes it when a token names an unknown key ID, and
     * retries a failed fetch once. Refreshes are rate limited (at most one per 30 s) so a stream of
     * forged key IDs cannot make the server hammer Entra; tests turn that off to rotate keys quickly.
     */
    static JWKSource<SecurityContext> keySource(Config.Entra config, boolean rateLimited) {
        try {
            return JWKSourceBuilder.create(
                            config.jwksUri().toURL(), new DefaultResourceRetriever(5_000, 5_000, 512 * 1024))
                    .retrying(true)
                    .rateLimited(rateLimited)
                    .build();
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("invalid JWKS URL " + config.jwksUri(), e);
        }
    }
}
