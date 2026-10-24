package ca.int13.librenmsmcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HealthCheckTest {
    private static HttpServer serve(int status, String body) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", ex -> {
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, b.length);
            try (var out = ex.getResponseBody()) {
                out.write(b);
            }
        });
        server.start();
        return server;
    }

    @Test
    void healthyWhenHealthAnswersOk() throws Exception {
        HttpServer s = serve(200, "ok");
        try {
            String port = Integer.toString(s.getAddress().getPort());
            assertThat(HealthCheck.probe(Map.of("MCP_HTTP_PORT", port))).isTrue();
            assertThat(HealthCheck.probe(Map.of("MCP_HTTP_HOST", "0.0.0.0", "MCP_HTTP_PORT", port)))
                    .as("wildcard probes loopback")
                    .isTrue();
        } finally {
            s.stop(0);
        }
    }

    @Test
    void unhealthyOnErrorOrUnexpectedBody() throws Exception {
        for (HttpServer s : new HttpServer[] {serve(503, "ok"), serve(200, "nope")}) {
            try {
                assertThat(HealthCheck.probe(Map.of(
                                "MCP_HTTP_PORT", Integer.toString(s.getAddress().getPort()))))
                        .isFalse();
            } finally {
                s.stop(0);
            }
        }
    }

    @Test
    void unhealthyWhenNothingListensAndHealthyForStdio() {
        assertThat(HealthCheck.probe(Map.of("MCP_HTTP_PORT", "9"))).isFalse();
        assertThat(HealthCheck.probe(Map.of("MCP_TRANSPORT", "stdio"))).isTrue();
    }
}
