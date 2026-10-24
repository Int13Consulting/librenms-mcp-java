package ca.int13.librenmsmcp;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * Container health probe: {@code java -cp app.jar ca.int13.librenmsmcp.HealthCheck}. Exits 0 when
 * {@code /health} answers {@code ok}, 1 otherwise. The image has no shell or curl, so the probe is
 * Java. It reads the same {@code MCP_*} variables as the server, so the two agree on the address.
 */
public final class HealthCheck {
    private static final Set<String> WILDCARDS = Set.of("0.0.0.0", "::", "[::]");

    private HealthCheck() {}

    public static void main(String[] args) {
        System.exit(probe(System.getenv()) ? 0 : 1);
    }

    static boolean probe(Map<String, String> env) {
        if ("stdio".equalsIgnoreCase(env.getOrDefault("MCP_TRANSPORT", "http").strip())) {
            return true; // no HTTP listener to probe
        }
        String host = env.getOrDefault("MCP_HTTP_HOST", "127.0.0.1").strip();
        // A wildcard bind listens on loopback too.
        if (WILDCARDS.contains(host)) {
            host = "127.0.0.1";
        } else if (host.contains(":") && !host.startsWith("[")) {
            host = "[" + host + "]";
        }
        String port = env.getOrDefault("MCP_HTTP_PORT", "8000").strip();
        try {
            HttpResponse<String> resp = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(2))
                    .build()
                    .send(
                            HttpRequest.newBuilder(URI.create("http://" + host + ":" + port + "/health"))
                                    .timeout(Duration.ofSeconds(3))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200 && "ok".equals(resp.body());
        } catch (Exception e) {
            System.err.println("health check failed: " + e);
            return false;
        }
    }
}
