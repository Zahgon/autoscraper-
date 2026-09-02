package io.github.autoscraper;

import io.github.autoscraper.model.Rule;
import io.github.autoscraper.model.StackContentNode;
import io.github.autoscraper.options.BuildOptions;
import io.github.autoscraper.options.ExactOptions;
import io.github.autoscraper.options.SimilarOptions;
import io.github.autoscraper.soup.AttrValue;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers approximate attribute matching against values recorded from the Python implementation.
 *
 * <p>The upstream {@code test_attr_fuzz_ratio} tests pass a ratio below 1.0 but never reach this
 * code, because their markup makes every candidate below {@code <html>} fail the parent-text guard
 * in {@code _child_has_text}. The resulting rule identifies elements by empty class and style
 * strings, and an empty value is deliberately left exact rather than made approximate. The markup
 * here gives the target a sibling so the anchor itself carries the rule's identifying class.
 *
 * <p>Expected values were produced by running the Python package against the same markup:
 * <pre>
 * built     = ['Buy']            rules  = ['rule_cff222a2']
 * fuzz_1.0  = []                 fuzz_0.8 = ['Buy']         fuzz_0.5 = ['Buy']
 * exact_0.8 = ['Buy']            same page at 1.0 = ['Buy']
 * </pre>
 */
class FuzzyAttributeMatchingTest {

    private static final String BASE =
            "<div><a class=\"btn-primary\" href=\"/item\">Buy</a><span>Other</span></div>";
    private static final String VARIANT =
            "<div><a class=\"btn-prime\" href=\"/item\">Buy</a><span>Other</span></div>";

    private static AutoScraper trained() {
        AutoScraper scraper = new AutoScraper();
        List<String> built = scraper.build(BuildOptions.builder().html(BASE).wantedList("Buy").build());
        assertThat(built).containsExactly("Buy");
        return scraper;
    }

    private static List<String> similar(AutoScraper scraper, String html, double attrFuzzRatio) {
        return scraper.getResultSimilar(
                SimilarOptions.builder().html(html).attrFuzzRatio(attrFuzzRatio).build()).asList();
    }

    @Test
    void ruleIdentifiesTheAnchorByItsClass() {
        List<Rule> rules = trained().getStackList();
        assertThat(rules).hasSize(1);
        assertThat(rules.get(0).stackId()).isEqualTo("rule_cff222a2");

        List<StackContentNode> content = rules.get(0).content();
        assertThat(content).hasSize(5);
        StackContentNode leaf = content.get(4);
        assertThat(leaf.name()).isEqualTo("a");
        assertThat(leaf.attrs().get("class")).isEqualTo(new AttrValue.Tokens(List.of("btn-primary")));
    }

    @Test
    void exactMatchingRejectsTheChangedClass() {
        assertThat(similar(trained(), VARIANT, 1.0)).isEmpty();
    }

    @Test
    void approximateMatchingAcceptsTheChangedClass() {
        AutoScraper scraper = trained();
        assertThat(similar(scraper, VARIANT, 0.8)).containsExactly("Buy");
        assertThat(similar(scraper, VARIANT, 0.5)).containsExactly("Buy");
    }

    @Test
    void approximateMatchingAlsoAppliesToExactExtraction() {
        List<String> result = trained().getResultExact(
                ExactOptions.builder().html(VARIANT).attrFuzzRatio(0.8).build()).asList();
        assertThat(result).containsExactly("Buy");
    }

    @Test
    void unchangedMarkupStillMatchesExactly() {
        assertThat(similar(trained(), BASE, 1.0)).containsExactly("Buy");
    }
}
