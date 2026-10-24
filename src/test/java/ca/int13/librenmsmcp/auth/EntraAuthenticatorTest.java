package ca.int13.librenmsmcp.auth;

import static org.assertj.core.api.Assertions.assertThat;

import ca.int13.librenmsmcp.auth.AuthResult.Error;
import ca.int13.librenmsmcp.authz.Principal;
import ca.int13.librenmsmcp.authz.Role;
import ca.int13.librenmsmcp.testing.TestEntra;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.jwk.RSAKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Token validation, including every negative case in the plan's security test list. */
class EntraAuthenticatorTest {
    private TestEntra entra;
    private EntraAuthenticator auth;

    @BeforeEach
    void start() {
        entra = new TestEntra();
        auth = new EntraAuthenticator(entra.config(), EntraAuthenticator.keySource(entra.config(), false));
    }

    @AfterEach
    void stop() {
        entra.close();
    }

    private Principal accepted(String token) {
        AuthResult r = auth.authenticate(token);
        assertThat(r).isInstanceOf(AuthResult.Authenticated.class);
        return ((AuthResult.Authenticated) r).principal();
    }

    private void rejected(String token, Error error, String reasonFragment) {
        AuthResult r = auth.authenticate(token);
        assertThat(r).isInstanceOfSatisfying(AuthResult.Rejected.class, rej -> {
            assertThat(rej.error()).isEqualTo(error);
            assertThat(rej.reason()).containsIgnoringCase(reasonFragment);
        });
    }

    // --- accepted ---------------------------------------------------------------------------------

    @Test
    void delegatedUserToken() {
        Principal p = accepted(entra.user().sign());
        assertThat(p.method()).isEqualTo("entra-user");
        assertThat(p.subject()).isEqualTo(TestEntra.USER_OID);
        assertThat(p.clientId()).isEqualTo(TestEntra.CLIENT);
        assertThat(p.role()).isEqualTo(Role.READER);
        assertThat(p.name()).isEqualTo("alice@example.com");
    }

    @Test
    void appOnlyToken() {
        Principal p = accepted(entra.app().sign());
        assertThat(p.method()).isEqualTo("entra-app");
        assertThat(p.role()).isEqualTo(Role.READER);
        assertThat(p.name()).isNull();
    }

    @Test
    void highestLibreNmsRoleWinsAndOtherRolesAreIgnored() {
        assertThat(accepted(entra.user()
                                .claim("roles", List.of("Other.Role", "LibreNMS.Admin", "LibreNMS.Reader"))
                                .sign())
                        .role())
                .isEqualTo(Role.ADMIN);
        assertThat(accepted(entra.app()
                                .claim("roles", List.of("LibreNMS.Operator"))
                                .sign())
                        .role())
                .isEqualTo(Role.OPERATOR);
    }

    @Test
    void apiUriAudienceAndExtraScopesAreAccepted() {
        accepted(entra.user().claim("aud", "api://" + TestEntra.API).sign());
        accepted(entra.user().claim("scp", "openid access_as_user profile").sign());
    }

    @Test
    void explicitTokenTypeClaimIsHonoured() {
        assertThat(accepted(entra.user().claim("idtyp", "user").sign()).method())
                .isEqualTo("entra-user");
        assertThat(accepted(entra.app().claim("idtyp", "app").sign()).method()).isEqualTo("entra-app");
    }

    @Test
    void clockSkewIsTolerated() {
        accepted(entra.user().expiresIn(Duration.ofSeconds(-30)).sign());
        accepted(entra.user().notBefore(Duration.ofSeconds(30)).sign());
    }

    @Test
    void appIdClaimIsUsedWhenAzpIsAbsent() {
        assertThat(accepted(entra.app()
                                .without("azp")
                                .claim("appid", TestEntra.CLIENT)
                                .sign())
                        .clientId())
                .isEqualTo(TestEntra.CLIENT);
    }

    // --- signature and format ---------------------------------------------------------------------

    @Test
    void notAJwtIsLeftToOtherAuthenticators() {
        assertThat(auth.authenticate(null)).isInstanceOf(AuthResult.NotApplicable.class);
        assertThat(auth.authenticate("an-api-key")).isInstanceOf(AuthResult.NotApplicable.class);
    }

    @Test
    void garbageJwtIsRejected() {
        rejected("aaa.bbb.ccc", Error.INVALID_TOKEN, "");
    }

    @Test
    void signatureFromAnotherKeyWithTheSameKidIsRejected() {
        RSAKey forged = TestEntra.newKey("key-1");
        rejected(entra.user().signedWith(forged).sign(), Error.INVALID_TOKEN, "signature");
    }

    @Test
    void tamperedPayloadIsRejected() {
        String[] parts = entra.user().sign().split("\\.");
        String admin =
                entra.user().claim("roles", List.of("LibreNMS.Admin")).sign().split("\\.")[1];
        rejected(parts[0] + "." + admin + "." + parts[2], Error.INVALID_TOKEN, "signature");
    }

    @Test
    void hs256WithPublicKeyMaterialIsRejected() {
        byte[] secret = "an attacker's 256-bit shared secret!!".getBytes(StandardCharsets.UTF_8);
        rejected(entra.user().signHs256(secret), Error.INVALID_TOKEN, "");
    }

    @Test
    void unsignedTokenIsRejected() {
        rejected(entra.user().unsigned(), Error.INVALID_TOKEN, "");
    }

    @Test
    void unexpectedTokenTypeHeaderIsRejected() {
        rejected(entra.user().type(new JOSEObjectType("at+jwt+other")).sign(), Error.INVALID_TOKEN, "type");
    }

    @Test
    void unknownKeyIdIsRejected() {
        rejected(entra.user().signedWith(TestEntra.newKey("never-published")).sign(), Error.INVALID_TOKEN, "");
    }

    @Test
    void rotatedSigningKeyIsPickedUpFromJwks() {
        accepted(entra.user().sign());
        int fetches = entra.jwksFetches();
        RSAKey next = TestEntra.newKey("key-2");
        entra.rotate(next);
        accepted(entra.user().sign());
        assertThat(entra.jwksFetches()).as("refetched for the unknown kid").isGreaterThan(fetches);
    }

    // --- claims -----------------------------------------------------------------------------------

    @Test
    void wrongIssuerIsRejected() {
        rejected(
                entra.user()
                        .claim("iss", "https://login.microsoftonline.com/" + TestEntra.TENANT + "/v2.0")
                        .sign(),
                Error.INVALID_TOKEN,
                "iss");
        rejected(
                entra.user()
                        .claim("iss", "https://sts.windows.net/" + TestEntra.TENANT + "/")
                        .sign(),
                Error.INVALID_TOKEN,
                "iss");
    }

    @Test
    void otherTenantIsRejected() {
        rejected(
                entra.user()
                        .claim("tid", "99999999-9999-4999-8999-999999999999")
                        .sign(),
                Error.INVALID_TOKEN,
                "tid");
    }

    @Test
    void wrongAudienceIsRejected() {
        rejected(
                entra.user()
                        .claim("aud", "00000003-0000-0000-c000-000000000000")
                        .sign(),
                Error.INVALID_TOKEN,
                "aud");
        rejected(entra.user().claim("aud", "https://librenms-mcp.example/mcp").sign(), Error.INVALID_TOKEN, "aud");
    }

    @Test
    void v1TokenIsRejected() {
        rejected(entra.user().claim("ver", "1.0").sign(), Error.INVALID_TOKEN, "ver");
    }

    @Test
    void expiredTokenIsRejected() {
        rejected(entra.user().expiresIn(Duration.ofMinutes(-5)).sign(), Error.INVALID_TOKEN, "expired");
    }

    @Test
    void notYetValidTokenIsRejected() {
        rejected(entra.user().notBefore(Duration.ofMinutes(5)).sign(), Error.INVALID_TOKEN, "before");
    }

    @Test
    void missingRequiredClaimsAreRejected() {
        for (String claim : List.of("exp", "iat", "nbf", "sub", "oid")) {
            rejected(entra.user().without(claim).sign(), Error.INVALID_TOKEN, claim);
        }
    }

    @Test
    void idTokenIsRejected() {
        // An ID token: audience is the client app, no scp, no roles.
        rejected(
                entra.user()
                        .without("scp")
                        .without("roles")
                        .claim("aud", TestEntra.CLIENT)
                        .sign(),
                Error.INVALID_TOKEN,
                "aud");
        // Even if presented with our audience, a token without scp or roles is not an access token.
        rejected(entra.user().without("scp").without("roles").sign(), Error.INVALID_TOKEN, "neither scp nor roles");
    }

    @Test
    void inconsistentTokenTypeIsRejected() {
        rejected(entra.user().claim("idtyp", "app").sign(), Error.INVALID_TOKEN, "idtyp");
        rejected(entra.app().claim("idtyp", "user").sign(), Error.INVALID_TOKEN, "idtyp");
        rejected(entra.app().claim("idtyp", "device").sign(), Error.INVALID_TOKEN, "idtyp");
    }

    // --- authorization ----------------------------------------------------------------------------

    @Test
    void delegatedTokenWithoutTheScopeIsInsufficient() {
        rejected(entra.user().claim("scp", "User.Read").sign(), Error.INSUFFICIENT_SCOPE, "access_as_user");
    }

    @Test
    void tokenWithoutALibreNmsRoleIsInsufficient() {
        rejected(entra.user().without("roles").sign(), Error.INSUFFICIENT_SCOPE, "no LibreNMS app role");
        rejected(
                entra.app().claim("roles", List.of("Something.Else")).sign(),
                Error.INSUFFICIENT_SCOPE,
                "no LibreNMS app role");
    }

    @Test
    void groupsDoNotGrantAccess() {
        rejected(
                entra.user()
                        .without("roles")
                        .claim("groups", List.of("LibreNMS.Admin"))
                        .sign(),
                Error.INSUFFICIENT_SCOPE,
                "no LibreNMS app role");
    }

    @Test
    void clientAllowListIsEnforced() {
        var restricted = new EntraAuthenticator(
                entra.config(Set.of("55555555-5555-4555-8555-555555555555")),
                EntraAuthenticator.keySource(entra.config(), false));
        AuthResult r = restricted.authenticate(entra.user().sign());
        assertThat(r).isInstanceOfSatisfying(AuthResult.Rejected.class, rej -> {
            assertThat(rej.error()).isEqualTo(Error.INSUFFICIENT_SCOPE);
            assertThat(rej.reason()).contains("ENTRA_ALLOWED_CLIENT_IDS");
        });
        var allowed = new EntraAuthenticator(
                entra.config(Set.of(TestEntra.CLIENT)), EntraAuthenticator.keySource(entra.config(), false));
        assertThat(allowed.authenticate(entra.user().sign())).isInstanceOf(AuthResult.Authenticated.class);
    }

    @Test
    void malformedRolesClaimIsRejected() {
        rejected(entra.user().claim("roles", "LibreNMS.Admin").sign(), Error.INVALID_TOKEN, "malformed claim");
    }
}
