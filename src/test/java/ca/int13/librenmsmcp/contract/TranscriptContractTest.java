package ca.int13.librenmsmcp.contract;

import static org.assertj.core.api.Assertions.assertThat;

import ca.int13.librenmsmcp.authz.PolicyEngine;
import ca.int13.librenmsmcp.authz.Principal;
import ca.int13.librenmsmcp.authz.Role;
import ca.int13.librenmsmcp.config.Config;
import ca.int13.librenmsmcp.json.Json;
import ca.int13.librenmsmcp.librenms.LibreNmsClient;
import ca.int13.librenmsmcp.mcp.ToolDispatcher;
import ca.int13.librenmsmcp.testing.LibreNmsStub;
import ca.int13.librenmsmcp.testing.LibreNmsStub.Answer;
import ca.int13.librenmsmcp.testing.TestTools;
import ca.int13.librenmsmcp.tools.ToolRegistry;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;

/**
 * Replays the golden transcripts captured from upstream ({@code contracts/transcripts}, synthetic
 * LibreNMS answers) and from the test LibreNMS ({@code contracts/live}) against every implemented
 * tool. Each scenario must send exactly the recorded requests and produce the recorded MCP result.
 */
class TranscriptContractTest {
    private static final List<Path> SOURCES = List.of(Path.of("contracts/transcripts"), Path.of("contracts/live"));

    private static LibreNmsStub stub;
    private static ToolDispatcher dispatcher;

    @BeforeAll
    static void start() {
        stub = new LibreNmsStub();
        var client = new LibreNmsClient(stub.config());
        ToolRegistry registry = TestTools.registry(client);
        // Contracts describe tool behaviour, so the policy here allows everything.
        dispatcher =
                new ToolDispatcher(registry, new PolicyEngine(new Config.Policy(false, Set.of(), true, Role.ADMIN)));
    }

    @AfterAll
    static void stop() {
        stub.close();
    }

    @TestFactory
    Stream<DynamicTest> transcripts() throws IOException {
        List<DynamicTest> tests = new ArrayList<>();
        for (String tool : dispatcher.registry().tools().keySet()) {
            for (Path dir : SOURCES) {
                Path file = dir.resolve(tool + ".json");
                if (!Files.exists(file)) {
                    continue;
                }
                JsonNode doc = Json.parse(Files.readAllBytes(file));
                for (Map.Entry<String, JsonNode> scenario : doc.get("scenarios").properties()) {
                    String name = dir.getFileName() + "/" + tool + "#" + scenario.getKey();
                    tests.add(DynamicTest.dynamicTest(name, () -> replay(tool, scenario.getValue())));
                }
            }
        }
        assertThat(tests).as("every implemented tool has contract scenarios").isNotEmpty();
        return tests.stream();
    }

    /**
     * Upstream's own behavioural tests, recorded by {@code capture_tests.py}: each tool call a test
     * made, with its scripted LibreNMS answers. Calls to tools not ported yet are skipped.
     */
    @TestFactory
    Stream<DynamicTest> upstreamTests() throws IOException {
        List<DynamicTest> tests = new ArrayList<>();
        try (Stream<Path> files = Files.list(Path.of("contracts/upstream-tests"))) {
            for (Path file : files.sorted().toList()) {
                JsonNode doc = Json.parse(Files.readAllBytes(file));
                int i = 0;
                for (JsonNode call : doc.get("calls")) {
                    String tool = call.get("tool").stringValue();
                    String name = doc.get("test").stringValue() + " #" + (i++) + " " + tool;
                    if (dispatcher.registry().find(tool).isPresent()) {
                        tests.add(DynamicTest.dynamicTest(name, () -> replay(tool, call)));
                    }
                }
            }
        }
        assertThat(tests).isNotEmpty();
        return tests.stream();
    }

    private static void replay(String tool, JsonNode scenario) {
        List<JsonNode> expected = new ArrayList<>();
        scenario.get("requests").forEach(expected::add);
        stub.reset();
        stub.script(expected.stream().map(r -> answer(r.get("response"))).toList());

        McpSchema.CallToolResult result = dispatcher.call(
                new Principal("contract", null, Role.ADMIN, "test"), tool, Json.toMap(scenario.get("arguments")));

        List<LibreNmsStub.Recorded> actual = stub.requests();
        assertThat(actual).as("number of LibreNMS requests").hasSize(expected.size());
        for (int i = 0; i < expected.size(); i++) {
            JsonNode want = expected.get(i);
            LibreNmsStub.Recorded got = actual.get(i);
            assertThat(got.method())
                    .as("request %d method", i)
                    .isEqualTo(want.get("method").stringValue());
            assertThat(got.path())
                    .as("request %d path", i)
                    .isEqualTo(want.get("path").stringValue());
            assertThat(Json.toNode(got.query())).as("request %d query", i).isEqualTo(want.get("query"));
            assertThat(got.body()).as("request %d body", i).isEqualTo(want.get("body"));
        }
        assertResult(result, scenario.get("result"));
    }

    private static Answer answer(JsonNode response) {
        int status = response.get("status").asInt();
        String type = response.get("contentType").stringValue();
        byte[] body;
        if (response.has("body")) {
            body = Json.write(response.get("body")).getBytes(StandardCharsets.UTF_8);
        } else if (response.has("bodyBase64")) {
            body = Base64.getDecoder().decode(response.get("bodyBase64").stringValue());
        } else {
            body = response.get("bodyText").stringValue().getBytes(StandardCharsets.UTF_8);
        }
        JsonNode location = response.get("location");
        return new Answer(status, type, body, location == null ? Map.of() : Map.of("Location", location.stringValue()));
    }

    private static void assertResult(McpSchema.CallToolResult actual, JsonNode expected) {
        assertThat(actual.isError())
                .as("isError")
                .isEqualTo(expected.get("isError").asBoolean());
        String expectedText = expected.at("/content/0/text").stringValue(null);
        if (expectedText != null && expectedText.matches("(?s)\\d+ validation errors? for call\\[.*")) {
            // Argument validation: pydantic's wording is not reproduced (documented deviation);
            // what matters is that the call fails before reaching LibreNMS, which replay() checks.
            assertThat(((McpSchema.TextContent) actual.content().getFirst()).text())
                    .startsWith("Input validation error for ");
            return;
        }
        JsonNode content = expected.get("content");
        assertThat(actual.content()).as("content blocks").hasSize(content.size());
        for (int i = 0; i < content.size(); i++) {
            JsonNode want = content.get(i);
            switch (actual.content().get(i)) {
                case McpSchema.TextContent text -> {
                    assertThat(want.get("type").stringValue()).isEqualTo("text");
                    assertText(text.text(), want.get("text").stringValue());
                }
                case McpSchema.ImageContent image -> {
                    assertThat(want.get("type").stringValue()).isEqualTo("image");
                    assertThat(image.mimeType()).isEqualTo(want.get("mimeType").stringValue());
                    assertThat(image.data()).isEqualTo(want.get("data").stringValue());
                }
                default ->
                    throw new AssertionError(
                            "unexpected content " + actual.content().get(i));
            }
        }
        JsonNode structured = expected.get("structuredContent");
        if (structured == null) {
            assertThat(actual.structuredContent()).as("structuredContent").isNull();
        } else {
            assertThat(Json.toNode(actual.structuredContent()))
                    .as("structuredContent")
                    .isEqualTo(structured);
        }
    }

    /**
     * Text must match upstream byte for byte, key order included. The one tolerated difference is
     * float formatting (Python writes 1e-05 where Jackson writes 1.0E-5): if the strings differ but
     * the JSON is equal and only number tokens differ, the text still passes.
     */
    private static void assertText(String actual, String expected) {
        if (actual.equals(expected)) {
            return;
        }
        JsonNode want = tryParse(expected);
        assertThat(want)
                .as("text content")
                .withFailMessage("text differs:%n  actual:   %s%n  expected: %s", actual, expected)
                .isNotNull();
        assertThat(tryParse(actual)).as("text content (as JSON)").isEqualTo(want);
        assertThat(actual.replaceAll(NUMBER, "#"))
                .as("text content apart from number formatting")
                .isEqualTo(expected.replaceAll(NUMBER, "#"));
    }

    private static final String NUMBER = "-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?";

    private static JsonNode tryParse(String text) {
        try {
            return Json.parse(text);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
