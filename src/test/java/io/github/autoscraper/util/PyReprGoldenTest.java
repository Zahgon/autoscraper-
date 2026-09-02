package io.github.autoscraper.util;

import io.github.autoscraper.testutil.GoldenTsv;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins {@link PyRepr} to {@code repr()} output captured from CPython by
 * the oracle fixture generator.
 */
class PyReprGoldenTest {

    /**
     * The four {@code stack_*} rows encode whole rule dictionaries and are exercised end to end by
     * the core scraper suite; they are only presence-checked here so the fixture cannot silently
     * shrink.
     */
    private static final List<String> DEFERRED_CASES =
            List.of("stack_simple_cs", "stack_url_cs", "stack_simple_sc", "stack_url_sc");

    @Test
    @DisplayName("repr() and its sha256 match CPython for every golden case")
    void matchesCpythonRepr() {
        Map<String, String[]> rows = new LinkedHashMap<>();
        for (String[] row : GoldenTsv.rows("/golden/pyrepr.tsv")) {
            rows.put(row[0], row);
        }
        assertThat(rows.keySet()).containsAll(DEFERRED_CASES);

        Map<String, Object> cases = cases();
        assertThat(rows.keySet())
                .as("every non-deferred golden case must be covered by this test")
                .containsAll(cases.keySet());

        List<String> uncovered = new ArrayList<>(rows.keySet());
        uncovered.removeAll(cases.keySet());
        uncovered.removeAll(DEFERRED_CASES);
        assertThat(uncovered).as("golden fixture gained cases this test does not check").isEmpty();

        cases.forEach((id, value) -> {
            String[] row = rows.get(id);
            String expectedRepr = row[1];
            String expectedSha = row[2];
            String expectedStackId = row[3];

            String actual = PyRepr.repr(value);
            assertThat(actual).as("repr for case '%s'", id).isEqualTo(expectedRepr);
            assertThat(PyRepr.sha256Hex(actual)).as("sha256 for case '%s'", id)
                    .isEqualTo(expectedSha);
            assertThat("rule_" + expectedSha.substring(0, 8)).isEqualTo(expectedStackId);
        });
    }

    @Test
    @DisplayName("unsupported types are rejected rather than silently rendered")
    void rejectsUnsupportedTypes() {
        assertThatThrownBy(() -> PyRepr.repr(1.5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Double");
    }

    /** Java equivalents of the values the fixture generator reprs on the Python side. */
    private static Map<String, Object> cases() {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("str_plain", "hello");
        c.put("str_squote", "it's");
        c.put("str_dquote", "say \"hi\"");
        c.put("str_both", "it's a \"test\"");
        c.put("str_backslash", "a\\b");
        c.put("str_newline", "a\nb");
        c.put("str_tab", "a\tb");
        c.put("str_cr", "a\rb");
        c.put("str_nul", "a\u0000b");
        c.put("str_bell", "a\u0007b");
        c.put("str_esc1b", "a\u001bb");
        c.put("str_del", "a\u007fb");
        c.put("str_nbsp", "a\u00a0b");
        c.put("str_softhyphen", "a\u00adb");
        c.put("str_accent", "caf\u00e9");
        c.put("str_cjk", "\u4f60\u597d");
        c.put("str_emoji", new String(Character.toChars(0x1F600)));
        c.put("str_combining", "e\u0301");
        c.put("str_zwsp", "a\u200bb");
        c.put("str_linesep", "a\u2028b");
        c.put("str_empty", "");
        c.put("none", null);
        c.put("true", Boolean.TRUE);
        c.put("false", Boolean.FALSE);
        c.put("int0", 0);
        c.put("int_neg", -12);
        c.put("list_empty", List.of());
        c.put("list_mixed", Arrays.asList("a", 1, null, Boolean.TRUE));
        c.put("tuple_empty", PyRepr.tuple());
        c.put("tuple_one", PyRepr.tuple("a"));
        c.put("tuple_one_int", PyRepr.tuple(1));
        c.put("tuple_two", PyRepr.tuple("a", new LinkedHashMap<String, Object>()));
        c.put("tuple_three", PyRepr.tuple("li", attrs("", ""), 0));
        c.put("dict_empty", new LinkedHashMap<String, Object>());
        c.put("dict_order_cs", attrs("", ""));
        c.put("dict_order_sc", styleFirst());
        c.put("dict_class_list", attrs(List.of("item", "x"), ""));

        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("content", List.of(
                PyRepr.tuple("div", attrs(List.of("a"), ""), 2),
                PyRepr.tuple("p", attrs("", ""))));
        c.put("nested", nested);
        return c;
    }

    private static Map<String, Object> attrs(Object clazz, Object style) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("class", clazz);
        m.put("style", style);
        return m;
    }

    private static Map<String, Object> styleFirst() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("style", "");
        m.put("class", "");
        return m;
    }
}
