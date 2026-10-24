package ca.int13.librenmsmcp.http;

import ca.int13.librenmsmcp.auth.ApiKeyAuthenticator;
import ca.int13.librenmsmcp.auth.AuthResult;
import ca.int13.librenmsmcp.auth.Authenticator;
import ca.int13.librenmsmcp.auth.EntraAuthenticator;
import ca.int13.librenmsmcp.auth.ProtectedResource;
import ca.int13.librenmsmcp.authz.Principal;
import ca.int13.librenmsmcp.config.Config;
import ca.int13.librenmsmcp.config.Config.AuthMode;
import ca.int13.librenmsmcp.librenms.LibreNmsClient;
import ca.int13.librenmsmcp.mcp.McpServers;
import ca.int13.librenmsmcp.mcp.ToolDispatcher;
import ca.int13.librenmsmcp.observability.Telemetry;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.json.JavalinJackson3;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import tools.jackson.databind.JsonNode;

/**
 * Streamable HTTP endpoint ({@code /mcp}, stateless) plus health and OAuth metadata endpoints, on
 * Javalin.
 *
 * <p>The SDK servlet is not mounted in Jetty: Javalin routes call it directly, after these checks
 * on every MCP request, in order:
 *
 * <ol>
 *   <li>{@code Origin}, if present, must be allowed (DNS-rebinding protection) → else 403;
 *   <li>credentials must be valid → else 401 with a {@code WWW-Authenticate} challenge;
 *   <li>the caller must hold a LibreNMS role / required scope → else 403 {@code insufficient_scope};
 *   <li>the caller must be within its rate limit → else 429 with {@code Retry-After}.
 * </ol>
 */
public final class HttpServer implements AutoCloseable {
    public static final String MCP_PATH = "/mcp";
    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final String STARTED = "librenms-mcp.started";
    private static final Logger log = LoggerFactory.getLogger(HttpServer.class);

    private final Javalin app;
    private final McpStatelessSyncServer mcp;

    public HttpServer(Config config, ToolDispatcher dispatcher, LibreNmsClient client, String version) {
        this(config, dispatcher, client, version, authenticators(config), Clock.systemUTC());
    }

    HttpServer(
            Config config,
            ToolDispatcher dispatcher,
            LibreNmsClient client,
            String version,
            List<Authenticator> authenticators,
            Clock clock) {
        Config.Http http = config.http();
        Optional<ProtectedResource> resource =
                http.entra().map(entra -> new ProtectedResource(http.publicUrl().orElseThrow(), MCP_PATH, entra));
        Optional<RateLimiter> limiter =
                http.rateLimit().enabled() ? Optional.of(new RateLimiter(http.rateLimit(), clock)) : Optional.empty();

        var transport = HttpServletStatelessServerTransport.builder()
                .jsonMapper(McpServers.jsonMapper())
                .messageEndpoint(MCP_PATH)
                .maxRequestSize(http.maxRequestBytes())
                .contextExtractor(req -> McpTransportContext.create(
                        Map.of(McpServers.PRINCIPAL, req.getAttribute(McpServers.PRINCIPAL))))
                .build();
        this.mcp = McpServers.stateless(transport, dispatcher, config.search(), version);

        this.app = Javalin.create(c -> {
            c.concurrency.useVirtualThreads = true;
            c.jsonMapper(new JavalinJackson3());
            c.jetty.host = http.host();
            c.jetty.port = http.port();
            c.startup.showJavalinBanner = false;
            c.startup.showOldJavalinVersionWarning = false;

            c.routes.before(ctx -> {
                String id = ctx.header("X-Request-Id");
                id = id != null && REQUEST_ID.matcher(id).matches()
                        ? id
                        : UUID.randomUUID().toString();
                MDC.put("requestId", id);
                ctx.header("X-Request-Id", id);
                // An API, never a page: no sniffing, framing, caching or referrers.
                ctx.header("X-Content-Type-Options", "nosniff");
                ctx.header("X-Frame-Options", "DENY");
                ctx.header("Cache-Control", "no-store");
                ctx.header("Referrer-Policy", "no-referrer");
                ctx.attribute(STARTED, System.nanoTime());
            });
            c.routes.after(MCP_PATH, ctx -> {
                Object p = ctx.req().getAttribute(McpServers.PRINCIPAL);
                if (p instanceof Principal principal) {
                    long ms = (System.nanoTime() - (long) ctx.attribute(STARTED)) / 1_000_000;
                    log.info(
                            "{} {} {} {}ms subject={} name={} client={} via={}",
                            ctx.method(),
                            ctx.path(),
                            ctx.statusCode(),
                            ms,
                            principal.subject(),
                            principal.name() == null ? "-" : principal.name(),
                            principal.clientId() == null ? "-" : principal.clientId(),
                            principal.method());
                }
            });
            c.routes.after(ctx -> MDC.clear());

            // Liveness: proves the server handles requests; never calls LibreNMS (as upstream).
            c.routes.get("/health", ctx -> ctx.contentType("text/plain").result("ok"));
            c.routes.get("/readyz", ctx -> ready(ctx, client));

            resource.ifPresent(r -> {
                // RFC 9728: the metadata for resource <base>/mcp lives at /.well-known/...-resource/mcp;
                // the root form is served too for clients that look there.
                c.routes.get(ProtectedResource.METADATA_PATH + MCP_PATH, ctx -> ctx.json(r.metadata()));
                c.routes.get(ProtectedResource.METADATA_PATH, ctx -> ctx.json(r.metadata()));
            });

            c.routes.before(MCP_PATH, ctx -> guard(ctx, http.allowedOrigins(), authenticators, resource, limiter));
            c.routes.post(MCP_PATH, ctx -> serve(transport, ctx));
            c.routes.get(MCP_PATH, ctx -> serve(transport, ctx));
            c.routes.delete(MCP_PATH, ctx -> serve(transport, ctx));
        });
    }

    public HttpServer start() {
        app.start();
        log.info(
                "Listening on http://{}:{}{}",
                app.jettyServer().server().getURI().getHost(),
                port(),
                MCP_PATH);
        return this;
    }

    public int port() {
        return app.port();
    }

    @Override
    public void close() {
        app.stop();
        mcp.close();
    }

    static List<Authenticator> authenticators(Config config) {
        List<Authenticator> out = new ArrayList<>();
        Config.Http http = config.http();
        // API key first: a cheap exact comparison. Anything else falls through to Entra.
        if (http.authModes().contains(AuthMode.APIKEY)) {
            out.add(new ApiKeyAuthenticator(http.apiKey().orElseThrow(), http.apiKeyRole()));
        }
        if (http.authModes().contains(AuthMode.ENTRA)) {
            out.add(new EntraAuthenticator(http.entra().orElseThrow()));
        }
        if (http.authModes().contains(AuthMode.NONE)) {
            Principal local = Principal.local(config.policy().localRole());
            out.add(token -> new AuthResult.Authenticated(local));
            log.warn("AUTH_MODE=none: MCP requests are not authenticated (loopback only, role {})", local.role());
        }
        if (out.isEmpty()) {
            throw new IllegalStateException("no authentication mode configured");
        }
        return List.copyOf(out);
    }

    private static void guard(
            Context ctx,
            Set<String> allowedOrigins,
            List<Authenticator> authenticators,
            Optional<ProtectedResource> resource,
            Optional<RateLimiter> limiter) {
        String origin = ctx.header("Origin");
        if (origin != null && !allowedOrigins.contains(origin)) {
            log.info("Refused {} {} from origin {}", ctx.method(), ctx.path(), origin);
            Telemetry.rejected("forbidden_origin");
            ctx.status(403).json(Map.of("error", "forbidden_origin"));
            ctx.skipRemainingHandlers();
            return;
        }

        String token = bearer(ctx.header("Authorization"));
        AuthResult.Rejected rejected = null;
        for (Authenticator a : authenticators) {
            AuthResult result = a.authenticate(token);
            if (result instanceof AuthResult.Authenticated(Principal principal)) {
                OptionalLong retryAfter =
                        limiter.map(l -> l.acquire(key(principal))).orElse(OptionalLong.empty());
                if (retryAfter.isPresent()) {
                    log.info(
                            "Rate limited {} ({}), retry after {}s",
                            principal.subject(),
                            principal.method(),
                            retryAfter.getAsLong());
                    Telemetry.rejected("rate_limited");
                    ctx.header("Retry-After", Long.toString(retryAfter.getAsLong()));
                    ctx.status(429).json(Map.of("error", "rate_limited"));
                    ctx.skipRemainingHandlers();
                    return;
                }
                MDC.put("subject", principal.subject());
                ctx.req().setAttribute(McpServers.PRINCIPAL, principal);
                return;
            }
            if (result instanceof AuthResult.Rejected r && rejected == null) {
                rejected = r;
            }
        }

        AuthResult.Error error =
                token == null ? null : rejected == null ? AuthResult.Error.INVALID_TOKEN : rejected.error();
        Telemetry.rejected(error == null ? "no_credentials" : error.code);
        log.info(
                "Rejected {} {}: {}",
                ctx.method(),
                ctx.path(),
                token == null ? "no credentials" : rejected == null ? "unrecognized credentials" : rejected.reason());
        ctx.header(
                "WWW-Authenticate",
                resource.map(r -> r.challenge(error))
                        .orElse("Bearer realm=\"librenms-mcp\""
                                + (error == null ? "" : ", error=\"" + error.code + "\"")));
        int status = error == null ? 401 : error.status;
        ctx.status(status).json(error == null ? Map.of("error", "unauthorized") : Map.of("error", error.code));
        ctx.skipRemainingHandlers();
    }

    private static String bearer(String header) {
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String token = header.substring(7).strip();
        return token.isEmpty() ? null : token;
    }

    private static String key(Principal p) {
        return p.method() + ":" + p.subject() + ":" + p.clientId();
    }

    /** Runs the SDK servlet into a buffer so its error bodies can be cleaned (see {@link SanitizingResponse}). */
    private static void serve(HttpServletStatelessServerTransport transport, Context ctx) throws Exception {
        var response = new SanitizingResponse(ctx.res());
        transport.service(ctx.req(), response);
        response.finish();
    }

    /** Unauthenticated, so it says only whether LibreNMS answers; the detail goes to the log. */
    private static void ready(Context ctx, LibreNmsClient client) {
        try {
            JsonNode pong = client.get("ping");
            JsonNode message = pong.get("message");
            if (message != null && "pong".equals(message.stringValue())) {
                ctx.json(Map.of("status", "ready"));
                return;
            }
            log.warn(
                    "Not ready: LibreNMS ping answered {}",
                    ca.int13.librenmsmcp.tools.support.Results.truncatedRepr(pong));
            ctx.status(503).json(Map.of("status", "not_ready", "reason", "librenms_error"));
        } catch (RuntimeException e) {
            log.warn("Not ready: {}", e.getMessage());
            ctx.status(503).json(Map.of("status", "not_ready", "reason", "librenms_unreachable"));
        }
    }
}
