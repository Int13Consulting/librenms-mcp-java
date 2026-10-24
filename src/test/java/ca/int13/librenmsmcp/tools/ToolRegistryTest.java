package ca.int13.librenmsmcp.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.int13.librenmsmcp.authz.Access;
import ca.int13.librenmsmcp.json.Json;
import ca.int13.librenmsmcp.librenms.LibreNmsClient;
import ca.int13.librenmsmcp.testing.TestTools;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

class ToolRegistryTest {
    private static final ToolHandler NOOP = args -> new ToolResult.JsonResult(Json.object());

    private static LibreNmsClient client() {
        return new LibreNmsClient(new ca.int13.librenmsmcp.config.Config.LibreNms(
                java.net.URI.create("http://127.0.0.1:9"), "t", true, Duration.ofSeconds(1)));
    }

    @Test
    void packagedContractsMatchTheRepositoryFiles() throws IOException {
        for (String file : List.of("upstream-tools.json", "tool-classification.json")) {
            JsonNode packaged = Json.parse(
                    ToolRegistry.class.getResourceAsStream("/contracts/" + file).readAllBytes());
            assertThat(packaged).as(file).isEqualTo(Json.parse(Files.readAllBytes(Path.of("contracts", file))));
        }
    }

    @Test
    void everyUpstreamToolIsClassifiedAndNothingElse() {
        ToolRegistry registry = TestTools.registry(client());
        assertThat(registry.definitions()).hasSize(110);
        assertThat(registry.classifications().keySet())
                .isEqualTo(registry.definitions().keySet());
    }

    @Test
    void servedToolsCarryTheirApprovedClassification() {
        ToolRegistry registry = TestTools.registry(client());
        assertThat(registry.tools()).isNotEmpty();
        registry.tools().forEach((name, spec) -> {
            assertThat(spec.classification())
                    .as(name)
                    .isEqualTo(registry.classifications().get(name));
            assertThat(spec.definition())
                    .as(name)
                    .isSameAs(registry.definitions().get(name));
        });
        assertThat(registry.unimplemented()).hasSize(110 - registry.tools().size());
    }

    @Test
    void sideEffectingGetIsNotRetrySafe() {
        Classification discover = TestTools.registry(client()).classifications().get("device_discover");
        assertThat(discover.retrySafe()).isFalse();
        assertThat(discover.destructive()).isTrue();
        assertThat(discover.access()).isEqualTo(Access.ADMIN);
    }

    @Test
    void handlerForUnknownToolFailsStartup() {
        assertThatThrownBy(() -> ToolRegistry.build(List.of(b -> b.bind("not_a_tool", NOOP))))
                .hasMessageContaining("handler bound for unknown tool: not_a_tool");
    }

    @Test
    void handlerBoundTwiceFailsStartup() {
        assertThatThrownBy(() -> ToolRegistry.build(List.of(b -> b.bind("ping", NOOP), b -> b.bind("ping", NOOP))))
                .hasMessageContaining("handler bound twice: ping");
    }

    @Test
    void unclassifiedToolFailsClosed() throws IOException {
        JsonNode upstream = Json.parse(Files.readAllBytes(Path.of("contracts/upstream-tools.json")));
        ObjectNode classification =
                (ObjectNode) Json.parse(Files.readAllBytes(Path.of("contracts/tool-classification.json")));
        ((ObjectNode) classification.get("tools")).remove("ping");
        assertThatThrownBy(() -> ToolRegistry.build(upstream, classification, List.of(b -> b.bind("ping", NOOP))))
                .hasMessageContaining("handler bound for unclassified tool: ping");
    }

    @Test
    void classificationOfUnknownToolFailsClosed() throws IOException {
        JsonNode upstream = Json.parse(Files.readAllBytes(Path.of("contracts/upstream-tools.json")));
        ObjectNode classification =
                (ObjectNode) Json.parse(Files.readAllBytes(Path.of("contracts/tool-classification.json")));
        ((ObjectNode) classification.get("tools"))
                .set("ghost", Json.toNode(Map.of("access", "READ", "destructive", false, "retrySafe", true)));
        assertThatThrownBy(() -> ToolRegistry.build(upstream, classification, List.of()))
                .hasMessageContaining("classified tool is not an upstream tool: ghost");
    }
}
