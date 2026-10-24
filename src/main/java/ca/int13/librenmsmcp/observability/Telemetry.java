package ca.int13.librenmsmcp.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.micrometer.core.instrument.binder.system.UptimeMetrics;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.time.Duration;

/**
 * Application metrics on Micrometer's global registry, which is a no-op until {@link #prometheus()}
 * adds a Prometheus registry. Labels are bounded (tool names, outcomes, status classes): callers'
 * identities are never labels.
 */
public final class Telemetry {
    private Telemetry() {}

    /** Creates the Prometheus registry, registers it globally with JVM metrics, and returns it. */
    public static PrometheusMeterRegistry prometheus() {
        var registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        new JvmMemoryMetrics().bindTo(registry);
        new JvmGcMetrics().bindTo(registry);
        new JvmThreadMetrics().bindTo(registry);
        new ProcessorMetrics().bindTo(registry);
        new UptimeMetrics().bindTo(registry);
        Metrics.addRegistry(registry);
        return registry;
    }

    private static MeterRegistry registry() {
        return Metrics.globalRegistry;
    }

    /** One MCP tool call: {@code decision} allowed/denied, {@code outcome} ok/error/librenms_error/denied. */
    public static void toolCall(String tool, String decision, String outcome, Duration took) {
        Timer.builder("mcp_tool_calls")
                .description("MCP tool calls")
                .tags("tool", tool, "decision", decision, "outcome", outcome)
                .register(registry())
                .record(took);
    }

    /** A request refused before reaching MCP: invalid_token, insufficient_scope, no_credentials, forbidden_origin, rate_limited. */
    public static void rejected(String reason) {
        Counter.builder("mcp_http_rejections")
                .description("MCP HTTP requests refused before dispatch")
                .tag("reason", reason)
                .register(registry())
                .increment();
    }

    /** One LibreNMS API request; {@code status} is the HTTP status class (2xx…5xx) or "error". */
    public static void libreNmsRequest(String method, String status, Duration took) {
        Timer.builder("librenms_requests")
                .description("LibreNMS API requests")
                .tags("method", method, "status", status)
                .register(registry())
                .record(took);
    }
}
