package ca.int13.librenmsmcp.search;

import static org.assertj.core.api.Assertions.assertThat;

import ca.int13.librenmsmcp.json.Json;
import ca.int13.librenmsmcp.tools.ToolDefinition;
import ca.int13.librenmsmcp.tools.ToolRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;

/** Rankings must equal FastMCP's (contracts/tool-search.json) over the same 110 tools. */
class ToolSearchTest {
    private static List<ToolDefinition> all;
    private static JsonNode contract;

    @BeforeAll
    static void load() throws IOException {
        all = List.copyOf(ToolRegistry.build(List.of()).definitions().values());
        contract = Json.parse(Files.readAllBytes(Path.of("contracts/tool-search.json")));
    }

    @TestFactory
    Stream<DynamicTest> rankingsMatchFastMcp() {
        List<DynamicTest> tests = new ArrayList<>();
        for (String strategy : List.of("bm25", "regex")) {
            var search =
                    new ToolSearch(strategy.equals("bm25") ? ToolSearch.Strategy.BM25 : ToolSearch.Strategy.REGEX, 5);
            String arg = strategy.equals("bm25") ? "query" : "pattern";
            for (JsonNode r : contract.get(strategy).get("results")) {
                String input = r.get(arg).stringValue();
                List<String> expected = new ArrayList<>();
                r.get("names").forEach(n -> expected.add(n.stringValue()));
                tests.add(DynamicTest.dynamicTest(
                        strategy + ": " + (input.length() > 40 ? input.substring(0, 40) + "…" : input),
                        () -> assertThat(search.search(all, Function.identity(), input))
                                .extracting(ToolDefinition::name)
                                .containsExactlyElementsOf(expected)));
            }
        }
        return tests.stream();
    }

    @Test
    void catastrophicPatternIsCutOffByTheTimeBudget() {
        var search = new ToolSearch(ToolSearch.Strategy.REGEX, 5);
        long start = System.nanoTime();
        search.search(all, Function.identity(), "(a|aa)+(b|bb)+(c|cc)+(x|xx)+$");
        assertThat(System.nanoTime() - start).isLessThan(ToolSearch.REGEX_BUDGET_NANOS * 3);
    }

    @Test
    void unsupportedRegexConstructsMatchNothing() {
        for (String p : List.of("(?=x)", "(?<!x)y", "(a)\\1", "a++", "(?>a)", "(unclosed", "x".repeat(1001))) {
            assertThat(ToolSearch.compileLikeRust(p)).as(p).isNull();
        }
        assertThat(ToolSearch.compileLikeRust("(?P<name>port)")).isNotNull();
    }
}
