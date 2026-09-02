package io.github.autoscraper.match;

import io.github.autoscraper.testutil.GoldenTsv;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Pins {@link SequenceMatcher} to ratios captured from CPython's {@code difflib} by
 * the oracle fixture generator.
 */
class SequenceMatcherGoldenTest {

    @Test
    @DisplayName("ratio() matches CPython difflib for every golden pair")
    void matchesCpythonRatios() {
        List<String[]> rows = GoldenTsv.rows("/golden/sequence_matcher.tsv");
        assertThat(rows).as("golden fixture must not shrink").hasSizeGreaterThanOrEqualTo(500);

        for (int i = 0; i < rows.size(); i++) {
            String[] row = rows.get(i);
            String a = row[0];
            String b = row[1];
            double expected = Double.parseDouble(row[2]);
            double actual = SequenceMatcher.ratio(a, b);
            assertThat(actual)
                    .as("row %d: ratio(%s, %s)", i, abbreviate(a), abbreviate(b))
                    .isCloseTo(expected, within(1e-12));
        }
    }

    @Test
    @DisplayName("both operands empty yields 1.0, matching _calculate_ratio's length==0 branch")
    void bothEmptyIsOne() {
        assertThat(SequenceMatcher.ratio("", "")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("one empty operand yields 0.0")
    void oneEmptyIsZero() {
        assertThat(SequenceMatcher.ratio("", "abc")).isEqualTo(0.0);
        assertThat(SequenceMatcher.ratio("abc", "")).isEqualTo(0.0);
    }

    @Test
    @DisplayName("identical operands yield 1.0")
    void identicalIsOne() {
        assertThat(SequenceMatcher.ratio("Banana", "Banana")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("autojunk changes the ratio once b reaches 200 elements")
    void autojunkKicksInAt200() {
        // Without a junk predicate the "extend over junk" loops can silently rebuild a purged match,
        // so a
        // discriminating case needs an alphabet where every element exceeds ntest = 200/100 + 1
        // and no match starts at index 0. These exact values were captured from CPython.
        String a = "aaaababa";
        String b = "babaaabaaaabbaaabaaaabaaaabbaabaaabaaaabbbbbbbaaaabbbbbaababab"
                + "baabbbbbaabbaabbbbbabbaabaabaabbbaabbbabbbbbaaaaaaaababbaabbb"
                + "aabbbbbabbaaaabaabaaaaabaaababbbbaabbbbbaaabbbaaabaababbabab"
                + "aaabaabbaabbbabbb";
        assertThat(b).hasSize(200);
        assertThat(new SequenceMatcher(a, b, true).ratio()).isEqualTo(0.0);
        assertThat(new SequenceMatcher(a, b, false).ratio())
                .isCloseTo(0.07692307692307693, within(1e-15));

        // One element short of the threshold the heuristic never runs, so both settings agree.
        String justUnder = b.substring(1);
        assertThat(justUnder).hasSize(199);
        assertThat(new SequenceMatcher(a, justUnder, true).ratio())
                .isEqualTo(new SequenceMatcher(a, justUnder, false).ratio());
    }

    private static String abbreviate(String s) {
        String escaped = s.replace("\n", "\\n").replace("\t", "\\t").replace("\r", "\\r");
        return escaped.length() <= 60 ? "'" + escaped + "'"
                : "'" + escaped.substring(0, 57) + "...' (len " + s.length() + ")";
    }
}
