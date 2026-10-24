package ca.int13.librenmsmcp.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import ca.int13.librenmsmcp.authz.Access;
import ca.int13.librenmsmcp.authz.PolicyEngine;
import ca.int13.librenmsmcp.authz.Principal;
import ca.int13.librenmsmcp.authz.Role;
import ca.int13.librenmsmcp.config.Config;
import ca.int13.librenmsmcp.json.Json;
import ca.int13.librenmsmcp.librenms.LibreNmsClient;
import ca.int13.librenmsmcp.testing.LibreNmsStub;
import ca.int13.librenmsmcp.testing.TestTools;
import ca.int13.librenmsmcp.tools.ToolRegistry;
import ca.int13.librenmsmcp.tools.ToolSpec;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.slf4j.LoggerFactory;

/**
 * Every operator/admin tool, with its recorded arguments: denied before any LibreNMS request in
 * read-only mode, for every lower role, and (destructive tools) without ALLOW_DESTRUCTIVE; allowed
 * with the required role.
 */
class WriteToolPolicyTest {
    private static LibreNmsStub stub;
    private static ToolRegistry registry;

    @BeforeAll
    static void start() {
        stub = new LibreNmsStub();
        stub.answer(r -> LibreNmsStub.Answer.json(200, "{\"status\":\"ok\",\"devices\":[{\"device_id\":1}]}"));
        registry = TestTools.registry(new LibreNmsClient(stub.config()));
    }

    @AfterAll
    static void stop() {
        stub.close();
    }

    private static Map<String, Object> recordedArgs(String tool) throws IOException {
        var doc = Json.parse(Files.readAllBytes(Path.of("contracts/transcripts", tool + ".json")));
        return Json.toMap(doc.at("/scenarios/required/arguments"));
    }

    private static McpSchema.CallToolResult call(Config.Policy policy, Role role, String tool) throws IOException {
        stub.reset();
        return new ToolDispatcher(registry, new PolicyEngine(policy))
                .call(new Principal("tester", "client", role, "test"), tool, recordedArgs(tool));
    }

    private static String text(McpSchema.CallToolResult r) {
        return ((McpSchema.TextContent) r.content().getFirst()).text();
    }

    private static void assertDenied(McpSchema.CallToolResult r, String tool, String reason) {
        assertThat(r.isError()).as(tool).isTrue();
        assertThat(text(r)).as(tool).isEqualTo("Tool '" + tool + "' is not permitted: " + reason);
        assertThat(stub.requests()).as(tool + " reached LibreNMS").isEmpty();
    }

    @TestFactory
    Stream<DynamicTest> everyWriteToolIsGated() {
        List<ToolSpec> writes = registry.tools().values().stream()
                .filter(t -> t.access() != Access.READ)
                .toList();
        assertThat(writes).hasSize(37);
        List<DynamicTest> tests = new ArrayList<>();
        for (ToolSpec tool : writes) {
            String name = tool.name();
            Role required = tool.access() == Access.OPERATE ? Role.OPERATOR : Role.ADMIN;
            tests.add(DynamicTest.dynamicTest(
                    name + " read-only",
                    () -> assertDenied(
                            call(new Config.Policy(true, Set.of(), true, Role.READER), Role.ADMIN, name),
                            name,
                            "the server is in read-only mode")));
            for (Role lower : Role.values()) {
                if (!lower.atLeast(required)) {
                    tests.add(DynamicTest.dynamicTest(
                            name + " as " + lower,
                            () -> assertDenied(
                                    call(new Config.Policy(false, Set.of(), true, Role.READER), lower, name),
                                    name,
                                    "requires the " + tool.access().name().toLowerCase(java.util.Locale.ROOT)
                                            + " permission")));
                }
            }
            if (tool.destructive()) {
                tests.add(DynamicTest.dynamicTest(
                        name + " without ALLOW_DESTRUCTIVE",
                        () -> assertDenied(
                                call(new Config.Policy(false, Set.of(), false, Role.READER), Role.ADMIN, name),
                                name,
                                "destructive tools are disabled on this server")));
            }
            tests.add(DynamicTest.dynamicTest(name + " as " + required, () -> {
                McpSchema.CallToolResult r =
                        call(new Config.Policy(false, Set.of(), true, Role.READER), required, name);
                assertThat(text(r)).as(name).doesNotContain("is not permitted");
            }));
        }
        return tests.stream();
    }

    @Test
    void operatorToolsAreTheApprovedSet() {
        assertThat(registry.tools().values().stream()
                        .filter(t -> t.access() == Access.OPERATE)
                        .map(ToolSpec::name))
                .containsExactlyInAnyOrder(
                        "alert_acknowledge",
                        "alert_unmute",
                        "device_set_maintenance",
                        "devicegroup_set_maintenance",
                        "location_set_maintenance",
                        "port_description_update",
                        "device_eventlog_add");
    }

    @Test
    void auditRecordsTargetAndPayloadKeysButNeverPayloadValues() {
        var audit = (Logger) LoggerFactory.getLogger("audit");
        var events = new ListAppender<ILoggingEvent>();
        events.start();
        audit.addAppender(events);
        try {
            var d = new ToolDispatcher(
                    registry, new PolicyEngine(new Config.Policy(false, Set.of(), true, Role.READER)));
            var admin = new Principal("oid-1", "client-1", Role.ADMIN, "entra-user", "alice@example.com");
            d.call(
                    admin,
                    "device_update",
                    Map.of("hostname", "core1", "payload", Map.of("community", "s3cret-community")));
            d.call(
                    new Principal("oid-2", null, Role.READER, "apikey"),
                    "device_delete",
                    Map.of("hostname", "core1\nforged line"));
        } finally {
            audit.detachAppender(events);
        }
        List<String> lines =
                events.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0))
                .startsWith("tool_call allowed tool=device_update subject=oid-1 name=alice@example.com")
                .contains("target=[hostname=core1]", "payload_keys=[community]", "access=ADMIN")
                .doesNotContain("s3cret-community");
        assertThat(lines.get(1))
                .startsWith("tool_call denied tool=device_delete subject=oid-2")
                .contains("target=[hostname=core1_forged_line]")
                .doesNotContain("\n");
        // Structured fields for JSON logging.
        var kv = new java.util.HashMap<String, Object>();
        events.list.getFirst().getKeyValuePairs().forEach(p -> kv.put(p.key, p.value));
        assertThat(kv)
                .containsEntry("event", "tool_call")
                .containsEntry("decision", "allowed")
                .containsEntry("tool", "device_update")
                .containsEntry("subject", "oid-1")
                .containsEntry("target", "hostname=core1")
                .containsEntry("payload_keys", "[community]")
                .containsEntry("outcome", "ok");
        assertThat(kv.values()).noneMatch(v -> String.valueOf(v).contains("s3cret"));
    }
}
