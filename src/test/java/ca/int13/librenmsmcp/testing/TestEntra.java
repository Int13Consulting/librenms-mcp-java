package ca.int13.librenmsmcp.testing;

import ca.int13.librenmsmcp.config.Config;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A stand-in for Entra: signing keys, a JWKS endpoint at the real path, and a builder for access
 * tokens that are valid by default and can be broken one claim at a time.
 */
public final class TestEntra implements AutoCloseable {
    public static final String TENANT = "11111111-1111-4111-8111-111111111111";
    public static final String API = "22222222-2222-4222-8222-222222222222";
    public static final String CLIENT = "33333333-3333-4333-8333-333333333333";
    public static final String USER_OID = "44444444-4444-4444-8444-444444444444";

    private final HttpServer server;
    private final AtomicInteger jwksFetches = new AtomicInteger();
    private volatile JWKSet published;
    private volatile RSAKey signingKey;

    public TestEntra() {
        signingKey = newKey("key-1");
        published = new JWKSet(signingKey.toPublicJWK());
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/" + TENANT + "/discovery/v2.0/keys", exchange -> {
            jwksFetches.incrementAndGet();
            byte[] body = published.toString(true).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    public static RSAKey newKey(String kid) {
        try {
            return new RSAKeyGenerator(2048).keyID(kid).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    public URI authority() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    public Config.Entra config() {
        return config(Set.of());
    }

    public Config.Entra config(Set<String> allowedClients) {
        return new Config.Entra(authority(), TENANT, API, "access_as_user", Duration.ofSeconds(60), allowedClients);
    }

    public String issuer() {
        return config().issuer();
    }

    /** Publishes a new key set and signs subsequent tokens with {@code key}. */
    public void rotate(RSAKey key, RSAKey... alsoPublished) {
        var keys = new java.util.ArrayList<com.nimbusds.jose.jwk.JWK>();
        keys.add(key.toPublicJWK());
        for (RSAKey k : alsoPublished) {
            keys.add(k.toPublicJWK());
        }
        published = new JWKSet(keys);
        signingKey = key;
    }

    public int jwksFetches() {
        return jwksFetches.get();
    }

    /** A delegated (user) token: scope access_as_user, role LibreNMS.Reader. */
    public Token user() {
        return new Token(this)
                .claim("scp", "access_as_user")
                .claim("roles", List.of("LibreNMS.Reader"))
                .claim("preferred_username", "alice@example.com");
    }

    /** An app-only token with role LibreNMS.Reader. */
    public Token app() {
        return new Token(this).claim("roles", List.of("LibreNMS.Reader"));
    }

    @Override
    public void close() {
        server.stop(0);
    }

    public static final class Token {
        private final Map<String, Object> claims = new LinkedHashMap<>();
        private RSAKey key;
        private JOSEObjectType type = JOSEObjectType.JWT;

        Token(TestEntra entra) {
            this.key = entra.signingKey;
            Instant now = Instant.now();
            claims.put("aud", API);
            claims.put("iss", entra.issuer());
            claims.put("iat", Date.from(now));
            claims.put("nbf", Date.from(now));
            claims.put("exp", Date.from(now.plusSeconds(3600)));
            claims.put("tid", TENANT);
            claims.put("ver", "2.0");
            claims.put("oid", USER_OID);
            claims.put("sub", "subject-1");
            claims.put("azp", CLIENT);
            claims.put("azpacr", "1");
        }

        public Token claim(String name, Object value) {
            claims.put(name, value);
            return this;
        }

        public Token without(String name) {
            claims.remove(name);
            return this;
        }

        public Token expiresIn(Duration d) {
            return claim("exp", Date.from(Instant.now().plus(d)));
        }

        public Token notBefore(Duration fromNow) {
            return claim("nbf", Date.from(Instant.now().plus(fromNow)));
        }

        public Token signedWith(RSAKey key) {
            this.key = key;
            return this;
        }

        public Token type(JOSEObjectType type) {
            this.type = type;
            return this;
        }

        private JWTClaimsSet claimsSet() {
            var b = new JWTClaimsSet.Builder();
            claims.forEach(b::claim);
            return b.build();
        }

        public String sign() {
            try {
                var jwt = new SignedJWT(
                        new JWSHeader.Builder(JWSAlgorithm.RS256)
                                .keyID(key.getKeyID())
                                .type(type)
                                .build(),
                        claimsSet());
                jwt.sign(new RSASSASigner(key));
                return jwt.serialize();
            } catch (JOSEException e) {
                throw new IllegalStateException(e);
            }
        }

        /** HS256 with a shared secret, as an attacker who knows only public data might try. */
        public String signHs256(byte[] secret) {
            try {
                var jwt = new SignedJWT(
                        new JWSHeader.Builder(JWSAlgorithm.HS256)
                                .keyID(key.getKeyID())
                                .build(),
                        claimsSet());
                jwt.sign(new MACSigner(secret));
                return jwt.serialize();
            } catch (JOSEException e) {
                throw new IllegalStateException(e);
            }
        }

        /** {@code alg: none}. */
        public String unsigned() {
            return new PlainJWT(claimsSet()).serialize();
        }
    }
}
