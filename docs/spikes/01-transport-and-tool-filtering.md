# Spike 1+2: Javalin × MCP SDK transport, per-principal tool filtering

**Date:** 2026-10-24  **Verdict:** ✅ Both pass. No fallback needed.

## Versions

| Component | Version | Notes |
|---|---|---|
| MCP Java SDK (`io.modelcontextprotocol.sdk:mcp`) | 2.1.0 | Latest stable; 2.2.0 is still a milestone. Bundles `mcp-json-jackson3` |
| Javalin | 7.2.3 | Jetty 12.1.12, ee10 (Servlet 6.0) |
| Jackson | 3.x (`tools.jackson`) | Shared by SDK and Javalin |
| JDK | 27 locally, `--release 25` | |

## Design proven

```java
var transport = HttpServletStatelessServerTransport.builder()
        .jsonMapper(json)
        .messageEndpoint("/mcp")
        .contextExtractor(req -> McpTransportContext.create(Map.of("principal", req.getAttribute("principal"))))
        .build();

McpServer.sync(transport)
        .addToolFilter((ctx, tool) -> policy.canSee(principal(ctx), tool.name()))   // tools/list
        .tools(specs)   // each callHandler(ctx, req) re-checks policy.canCall(...)
        .build();

Javalin.create(config -> {
    config.concurrency.useVirtualThreads = true;
    config.routes.before("/mcp", authFilter);                       // 401 + WWW-Authenticate
    config.routes.post("/mcp", ctx -> transport.service(ctx.req(), ctx.res()));
    config.routes.get("/mcp", ctx -> transport.service(ctx.req(), ctx.res()));
});
```

The SDK servlet is not mounted in Jetty; a Javalin route calls `HttpServlet.service` directly.
So Javalin `before` handlers (auth, rate limiting, correlation IDs) apply to MCP traffic with no
servlet filter.

## Results

| Check | Result |
|---|---|
| SDK servlet (built for Servlet 6.1) runs on Javalin's Jetty ee10 (Servlet 6.0) | ✅ No linkage errors |
| No / bad token | ✅ 401 with `WWW-Authenticate: Bearer resource_metadata=…`, before the SDK runs |
| `initialize` | ✅ Protocol 2025-11-25 |
| `tools/list` filtered per principal via `McpTransportContext` | ✅ Reader sees `read_tool`; admin sees both |
| Call-time authorization | ✅ Reader calling `admin_tool` gets `isError: true` |
| Virtual threads | ✅ Handlers run on `JettyServerThreadPool-Virtual-*` |
| `GET /mcp` (stateless, no SSE stream) | ✅ 405 |
| Real client (FastMCP 4.1.2 Streamable HTTP client) | ✅ Same results as curl |

## Findings that affect the build

1. **The SDK's tool filter only affects listing.** A `tools/call` naming a hidden tool still reaches
   that tool's handler. Every handler must go through the dispatcher's policy check. A test must
   prove that for every tool.
2. Stateless mode has no server-to-client notifications, which fits dropping the deprecated MCP
   logging notifications (see `docs/upstream-baseline.md`).
3. The transport also takes a `ServerTransportSecurityValidator` and `ServerHttpHeaderValidator`.
   Use them for Origin checking (DNS-rebinding protection) and a max request size.
