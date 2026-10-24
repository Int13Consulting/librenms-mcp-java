package ca.int13.librenmsmcp.mcp;

import ca.int13.librenmsmcp.authz.Access;
import ca.int13.librenmsmcp.authz.Decision;
import ca.int13.librenmsmcp.authz.PolicyEngine;
import ca.int13.librenmsmcp.authz.Principal;
import ca.int13.librenmsmcp.json.Json;
import ca.int13.librenmsmcp.librenms.LibreNmsClient;
import ca.int13.librenmsmcp.librenms.LibreNmsHttpException;
import ca.int13.librenmsmcp.librenms.LibreNmsTransportException;
import ca.int13.librenmsmcp.observability.Telemetry;
import ca.int13.librenmsmcp.tools.ToolArgs;
import ca.int13.librenmsmcp.tools.ToolFailure;
import ca.int13.librenmsmcp.tools.ToolRegistry;
import ca.int13.librenmsmcp.tools.ToolResult;
import ca.int13.librenmsmcp.tools.ToolSpec;
import ca.int13.librenmsmcp.tools.support.NameRefs;
import ca.int13.librenmsmcp.tools.support.PortLookup;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * The only path from an MCP request to a tool handler. Every call is authorized here, whatever
 * entry point it came through (the SDK tool filter only hides tools from listings; it does not stop
 * a call naming a hidden tool).
 *
 * <p>Result semantics follow upstream: a JSON tool that fails returns {@code {"status":"error",
 * "message":...}} with {@code isError: false}; an image tool that fails, and any denied call,
 * returns {@code isError: true}.
 */
public final class ToolDispatcher {
    private static final Logger log = LoggerFactory.getLogger(ToolDispatcher.class);
    private static final Logger audit = LoggerFactory.getLogger("audit");
    private static final JsonSchemaValidator VALIDATOR = McpJsonDefaults.getSchemaValidator();

    private final ToolRegistry registry;
    private final PolicyEngine policy;

    public ToolDispatcher(ToolRegistry registry, PolicyEngine policy) {
        this.registry = registry;
        this.policy = policy;
    }

    public ToolRegistry registry() {
        return registry;
    }

    public boolean canSee(Principal principal, String toolName) {
        return registry.find(toolName)
                .map(t -> policy.decide(principal, t).allowed())
                .orElse(false);
    }

    public List<ToolSpec> visibleTools(Principal principal) {
        return registry.tools().values().stream()
                .filter(t -> policy.decide(principal, t).allowed())
                .toList();
    }

    public McpSchema.CallToolResult call(Principal principal, String toolName, Map<String, Object> arguments) {
        Optional<ToolSpec> found = registry.find(toolName);
        if (found.isEmpty()) {
            return error("Unknown tool: " + toolName);
        }
        ToolSpec tool = found.get();
        Decision decision = policy.decide(principal, tool);
        long started = System.nanoTime();
        if (!decision.allowed()) {
            Telemetry.toolCall(toolName, "denied", "denied", Duration.ZERO);
            audit.atInfo()
                    .setMessage(
                            "tool_call denied tool={} subject={} name={} client={} access={} target=[{}] reason=\"{}\"")
                    .addArgument(toolName)
                    .addArgument(subject(principal))
                    .addArgument(name(principal))
                    .addArgument(client(principal))
                    .addArgument(tool.access())
                    .addArgument(target(arguments))
                    .addArgument(decision.reason())
                    .addKeyValue("event", "tool_call")
                    .addKeyValue("decision", "denied")
                    .addKeyValue("tool", toolName)
                    .addKeyValue("subject", subject(principal))
                    .addKeyValue("client", client(principal))
                    .addKeyValue("access", tool.access().name())
                    .addKeyValue("target", target(arguments))
                    .addKeyValue("reason", decision.reason())
                    .log();
            return error("Tool '" + toolName + "' is not permitted: " + decision.reason());
        }
        // Validated here as well as by the HTTP transport, so stdio and every other entry point
        // reject the same input before any LibreNMS request (upstream: pydantic validation).
        JsonSchemaValidator.ValidationResponse valid =
                VALIDATOR.validate(tool.inputSchemaMap(), arguments == null ? Map.of() : arguments);
        if (!valid.valid()) {
            return error("Input validation error for " + toolName + ": " + valid.errorMessage());
        }
        McpSchema.CallToolResult result = ScopedValue.where(
                        LibreNmsClient.RETRY_SAFE, tool.classification().retrySafe())
                .call(() -> invoke(tool, arguments));
        Telemetry.toolCall(toolName, "allowed", outcome(result), Duration.ofNanos(System.nanoTime() - started));
        if (tool.access() != Access.READ) {
            String outcome = outcome(result);
            audit.atInfo()
                    .setMessage(
                            "tool_call allowed tool={} subject={} name={} client={} via={} access={} target=[{}] payload_keys={} outcome={}")
                    .addArgument(toolName)
                    .addArgument(subject(principal))
                    .addArgument(name(principal))
                    .addArgument(client(principal))
                    .addArgument(principal.method())
                    .addArgument(tool.access())
                    .addArgument(target(arguments))
                    .addArgument(payloadKeys(arguments))
                    .addArgument(outcome)
                    .addKeyValue("event", "tool_call")
                    .addKeyValue("decision", "allowed")
                    .addKeyValue("tool", toolName)
                    .addKeyValue("subject", subject(principal))
                    .addKeyValue("name", name(principal))
                    .addKeyValue("client", client(principal))
                    .addKeyValue("via", principal.method())
                    .addKeyValue("access", tool.access().name())
                    .addKeyValue("target", target(arguments))
                    .addKeyValue("payload_keys", payloadKeys(arguments))
                    .addKeyValue("outcome", outcome)
                    .log();
        }
        return result;
    }

    private McpSchema.CallToolResult invoke(ToolSpec tool, Map<String, Object> arguments) {
        ToolArgs args = new ToolArgs(arguments, tool.definition().inputSchema());
        try {
            return switch (tool.handler().call(args)) {
                case ToolResult.JsonResult(JsonNode body) -> jsonResult(body);
                case ToolResult.ImageResult(byte[] data, String mimeType) ->
                    McpSchema.CallToolResult.builder()
                            .addContent(McpSchema.ImageContent.builder(
                                            Base64.getEncoder().encodeToString(data), mimeType)
                                    .build())
                            .isError(false)
                            .build();
            };
        } catch (ToolFailure e) {
            if (!tool.definition().returnsJson()) {
                return error(e.getMessage());
            }
            // JSON tools catch every failure upstream, ToolError included.
            return jsonResult(errorBody(e.getMessage()));
        } catch (Exception e) {
            if (expected(e)) {
                log.debug("tool {} failed: {}", tool.name(), e.getMessage());
            } else {
                log.warn("tool {} failed unexpectedly", tool.name(), e);
            }
            if (!tool.definition().returnsJson()) {
                return error("Error calling tool '" + tool.name() + "': " + e.getMessage());
            }
            // Upstream error_body(str(e)): failures travel in LibreNMS's own error shape.
            return jsonResult(errorBody(e.getMessage()));
        }
    }

    /**
     * LibreNMS answers, unreachable LibreNMS, bad input, unroutable names and failed port fallbacks
     * are normal outcomes (upstream raises ToolError for them); anything else is a bug.
     */
    private static boolean expected(Exception e) {
        return e instanceof LibreNmsHttpException
                || e instanceof LibreNmsTransportException
                || e instanceof IllegalArgumentException
                || e instanceof NameRefs.UnroutableRefException
                || e instanceof PortLookup.PortFallbackException;
    }

    private static JsonNode errorBody(String message) {
        var body = Json.object();
        body.put("status", "error");
        body.put("message", message == null ? "" : message);
        return body;
    }

    private static McpSchema.CallToolResult jsonResult(JsonNode body) {
        var builder = McpSchema.CallToolResult.builder()
                .addTextContent(Json.write(body))
                .isError(false);
        if (body.isObject()) {
            builder.structuredContent(Json.toMap(body));
        }
        return builder.build();
    }

    private static McpSchema.CallToolResult error(String message) {
        return McpSchema.CallToolResult.builder()
                .addTextContent(message)
                .isError(true)
                .build();
    }

    private static String outcome(McpSchema.CallToolResult result) {
        if (Boolean.TRUE.equals(result.isError())) {
            return "error";
        }
        if (result.structuredContent() instanceof Map<?, ?> m && "error".equals(m.get("status"))) {
            return "librenms_error";
        }
        return "ok";
    }

    /** Arguments that identify what a call acts on. Payload values are never logged (they can hold secrets). */
    static final List<String> TARGET_ARGS = List.of(
            "hostname",
            "new_hostname",
            "name",
            "location",
            "port_id",
            "port_group_id",
            "alert_id",
            "rule_id",
            "template_id",
            "bill_id",
            "history_id",
            "service_id",
            "bgp_id");

    static String target(Map<String, Object> arguments) {
        if (arguments == null) {
            return "";
        }
        var parts = new java.util.ArrayList<String>();
        for (String key : TARGET_ARGS) {
            Object v = arguments.get(key);
            if (v != null) {
                parts.add(key + "=" + sanitize(String.valueOf(v)));
            }
        }
        return String.join(" ", parts);
    }

    /** The payload's field names only, e.g. [hostname, community, version]. */
    static String payloadKeys(Map<String, Object> arguments) {
        Object payload = arguments == null ? null : arguments.get("payload");
        if (payload instanceof Map<?, ?> m) {
            return m.keySet().stream()
                    .map(k -> sanitize(String.valueOf(k)))
                    .sorted()
                    .toList()
                    .toString();
        }
        return payload instanceof List<?> l ? "[list of " + l.size() + "]" : "-";
    }

    /** Keeps log lines single-line and bounded; values come from the model. */
    private static String sanitize(String v) {
        String clean = v.replaceAll("[\\p{Cntrl}\\s]+", "_");
        return clean.length() > 100 ? clean.substring(0, 100) + "…" : clean;
    }

    private static String name(Principal p) {
        return p == null || p.name() == null ? "-" : p.name();
    }

    private static String subject(Principal p) {
        return p == null ? "-" : p.subject();
    }

    private static String client(Principal p) {
        return p == null || p.clientId() == null ? "-" : p.clientId();
    }
}
