package io.github.autoscraper.util;

import io.github.autoscraper.testutil.GoldenTsv;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins {@link PyUrl} to {@code urllib.parse.urljoin} and {@code html.unescape} output captured
 * from CPython by the oracle fixture generator.
 */
class PyUrlGoldenTest {

    @Test
    @DisplayName("urljoin matches urllib.parse.urljoin for every golden pair")
    void matchesCpythonUrljoin() {
        List<String[]> rows = GoldenTsv.rows("/golden/urljoin.tsv");
        assertThat(rows).hasSizeGreaterThanOrEqualTo(200);
        for (int i = 0; i < rows.size(); i++) {
            String[] row = rows.get(i);
            assertThat(PyUrl.urljoin(row[0], row[1]))
                    .as("row %d: urljoin('%s', '%s')", i, row[0], row[1])
                    .isEqualTo(row[2]);
        }
    }

    @Test
    @DisplayName("unescape matches html.unescape for every golden input")
    void matchesCpythonUnescape() {
        List<String[]> rows = GoldenTsv.rows("/golden/unescape.tsv");
        assertThat(rows).isNotEmpty();
        for (int i = 0; i < rows.size(); i++) {
            String[] row = rows.get(i);
            assertThat(PyUrl.unescape(row[0]))
                    .as("row %d: unescape('%s')", i, row[0])
                    .isEqualTo(row[1]);
        }
    }

    @Test
    @DisplayName("urljoin keeps CPython's empty-operand short circuits")
    void emptyOperandShortCircuits() {
        assertThat(PyUrl.urljoin("", "/shop")).isEqualTo("/shop");
        assertThat(PyUrl.urljoin("https://a.example/x", "")).isEqualTo("https://a.example/x");
    }

    @Test
    @DisplayName("a non-relative scheme is returned untouched instead of being resolved")
    void foreignSchemeIsPassedThrough() {
        assertThat(PyUrl.urljoin("https://a.example/x", "mailto:me@example.com"))
                .isEqualTo("mailto:me@example.com");
        assertThat(PyUrl.urljoin("https://a.example/x", "javascript:void(0)"))
                .isEqualTo("javascript:void(0)");
    }
}
