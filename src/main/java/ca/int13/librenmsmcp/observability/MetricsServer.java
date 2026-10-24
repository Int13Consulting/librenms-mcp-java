package ca.int13.librenmsmcp.observability;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * Prometheus {@code /metrics} on its own port, so it is never reachable through the public MCP
 * endpoint (reverse proxy or tunnel). Unauthenticated: bind it to an internal interface.
 */
public final class MetricsServer implements AutoCloseable {
    private final HttpServer server;

    public MetricsServer(String host, int port, PrometheusMeterRegistry registry) {
        try {
            server = HttpServer.create(new InetSocketAddress(host, port), 0);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot bind metrics port " + host + ":" + port, e);
        }
        server.createContext("/metrics", exchange -> {
            byte[] body = registry.scrape().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
