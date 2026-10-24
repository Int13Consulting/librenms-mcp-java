package ca.int13.librenmsmcp.librenms;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Expected values are Python 3 {@code repr()} output. */
class PythonTextTest {
    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '`', textBlock = """
            ../x        | '../x'
            it's        | "it's"
            `it's "q"`  | `'it\\'s "q"'`
            `a\\b`      | `'a\\\\b'`
            """)
    void reprMatchesPython(String value, String expected) {
        assertThat(PythonText.repr(value)).isEqualTo(expected);
    }

    @org.junit.jupiter.api.Test
    void escapesControlCharacters() {
        assertThat(PythonText.repr("a\tb\nc\u0001 ")).isEqualTo("'a\\tb\\nc\\x01\\xa0'");
    }
}
