package ca.int13.librenmsmcp.observability;

import static org.assertj.core.api.Assertions.assertThat;

import ca.int13.librenmsmcp.authz.PolicyEngine;
import ca.int13.librenmsmcp.authz.Principal;
import ca.int13.librenmsmcp.authz.Role;
import ca.int13.librenmsmcp.config.Config;
import ca.int13.librenmsmcp.librenms.LibreNmsClient;
import ca.int13.librenmsmcp.mcp.ToolDispatcher;
import ca.int13.librenmsmcp.testing.LibreNmsStub;
import ca.int13.librenmsmcp.testing.TestTools;
import io.micrometer.core.instrument.Metrics;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MetricsTest {
    @Test
    void toolCallsAndLibreNmsRequestsAreScrapedWithoutIdentities() throws Exception {
        var registry = Telemetry.prometheus();
        try (var stub = new LibreNmsStub();
                var server = new MetricsServer("127.0.0.1", 0, registry)) {
            stub.answer(r -> LibreNmsStub.Answer.json(200, "{\"message\":\"pong\"}"));
            var dispatcher = new ToolDispatcher(
                    TestTools.registry(new LibreNmsClient(stub.config())),
                    new PolicyEngine(new Config.Policy(true, Set.of(), false, Role.READER)));
            var alice = new Principal("secret-subject-oid", "client-x", Role.READER, "entra-user", "alice@example.com");
            dispatcher.call(alice, "ping", Map.of());
            dispatcher.call(alice, "device_delete", Map.of("hostname", "core1"));
            Telemetry.rejected("invalid_token");

            String scrape = HttpClient.newHttpClient()
                    .send(
                            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/metrics"))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString())
                    .body();
            assertThat(scrape)
                    .contains("mcp_tool_calls_seconds_count{decision=\"allowed\",outcome=\"ok\",tool=\"ping\"} 1")
                    .contains(
                            "mcp_tool_calls_seconds_count{decision=\"denied\",outcome=\"denied\",tool=\"device_delete\"} 1")
                    .contains("librenms_requests_seconds_count{method=\"GET\",status=\"2xx\"}")
                    .contains("mcp_http_rejections_total{reason=\"invalid_token\"} 1")
                    .contains("jvm_memory_used_bytes")
                    .doesNotContain("secret-subject-oid")
                    .doesNotContain("alice@example.com")
                    .doesNotContain("client-x");
        } finally {
            Metrics.removeRegistry(registry);
            registry.close();
        }
    }
}
