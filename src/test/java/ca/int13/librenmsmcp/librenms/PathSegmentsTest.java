package ca.int13.librenmsmcp.librenms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class PathSegmentsTest {
    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            core1/discover|core1%2Fdiscover
            sw#1|sw%231
            a?b=c&d|a%3Fb%3Dc%26d
            Gi0/1.100|Gi0%2F1.100
            ~user_name-1.x|~user_name-1.x
            café|caf%C3%A9
            100%|100%25
            """)
    void encodesEachValueAsOneSegmentLikePythonQuote(String value, String expected) {
        assertThat(PathSegments.encode(value)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {".", "..", "../rules/5", "a/../b", "a/.", "..\\x", "x\\..", " ", ""})
    void rejectsBlankAndDotSegments(String value) {
        assertThatThrownBy(() -> PathSegments.encode(value))
                .isInstanceOf(PathSegments.InvalidPathSegmentException.class)
                .hasMessageStartingWith("Invalid path segment: ");
    }

    @ParameterizedTest
    @ValueSource(strings = {"...", ".hidden", "a.b", "..a"})
    void allowsDotsThatAreNotWholeSegments(String value) {
        assertThat(PathSegments.encode(value)).isEqualTo(value);
    }

    @org.junit.jupiter.api.Test
    void keepsSurroundingSpaces() {
        assertThat(PathSegments.encode(" sw1 ")).isEqualTo("%20sw1%20");
    }

    @org.junit.jupiter.api.Test
    void joinsSegmentsAndStringifiesNumbers() {
        assertThat(PathSegments.join(List.of("devices", 42, "ports"))).isEqualTo("devices/42/ports");
        assertThatThrownBy(() -> PathSegments.join(List.of())).isInstanceOf(IllegalArgumentException.class);
    }
}
