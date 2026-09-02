package io.github.autoscraper;

import io.github.autoscraper.match.TextTarget;
import io.github.autoscraper.model.Rule;
import io.github.autoscraper.options.BuildOptions;
import io.github.autoscraper.options.ResultOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers {@link AutoScraper#getResult}, which runs both extraction strategies over a single parse
 * and returns their results together.
 *
 * <p>The upstream suite only calls this method once, without grouping, so the grouping and
 * deduplication options reachable through it are otherwise unverified. Every expectation here was
 * taken from the Python package driven with the same markup and the same options.
 */
class CombinedResultTest {

    private static final String PAGE =
            "<div class=\"p\"><h1>Widget</h1><span class=\"price\">$9.99</span>"
                    + "<span class=\"rate\">4.5</span></div>";

    private AutoScraper scraper;

    @BeforeEach
    void train() {
        scraper = new AutoScraper();
        scraper.build(BuildOptions.builder()
                .html(PAGE)
                .wanted("name", List.of(new TextTarget.Literal("Widget")))
                .wanted("price", List.of(new TextTarget.Literal("$9.99")))
                .build());

        assertThat(scraper.getRuleAliases()).containsExactly("name", "price");
        assertThat(scraper.getStackList())
                .extracting(Rule::stackId)
                .containsExactly("rule_2cb28308", "rule_e124701a");
    }

    private AutoScraper.ResultPair resultFor(ResultOptions.Builder options) {
        return scraper.getResult(options.html(PAGE).build());
    }

    @Test
    void bothStrategiesAgreeOnThisPage() {
        AutoScraper.ResultPair pair = resultFor(ResultOptions.builder());

        assertThat(pair.similar().asList()).containsExactly("Widget", "$9.99");
        assertThat(pair.exact().asList()).containsExactly("Widget", "$9.99");
    }

    @Test
    void groupingKeysResultsByRuleIdentifier() {
        AutoScraper.ResultPair pair = resultFor(ResultOptions.builder().grouped(true));

        Map<String, List<String>> expected = Map.of(
                "rule_2cb28308", List.of("Widget"),
                "rule_e124701a", List.of("$9.99"));

        assertThat(pair.similar().asMap()).isEqualTo(expected);
        assertThat(pair.exact().asMap()).isEqualTo(expected);
    }

    @Test
    void groupingByAliasKeysResultsByTheNamesGivenAtBuildTime() {
        AutoScraper.ResultPair pair = resultFor(ResultOptions.builder().groupByAlias(true));

        Map<String, List<String>> expected = Map.of(
                "name", List.of("Widget"),
                "price", List.of("$9.99"));

        assertThat(pair.similar().asMap()).isEqualTo(expected);
        assertThat(pair.exact().asMap()).isEqualTo(expected);
    }

    @Test
    void disablingDeduplicationLeavesTheseGroupsUnchanged() {
        AutoScraper.ResultPair pair =
                resultFor(ResultOptions.builder().groupByAlias(true).unique(false));

        assertThat(pair.similar().asMap()).isEqualTo(Map.of(
                "name", List.of("Widget"),
                "price", List.of("$9.99")));
    }

    @Test
    void approximateAttributeMatchingStillFindsTheSameValues() {
        AutoScraper.ResultPair pair = resultFor(ResultOptions.builder().attrFuzzRatio(0.8));

        assertThat(pair.similar().asList()).containsExactly("Widget", "$9.99");
        assertThat(pair.exact().asList()).containsExactly("Widget", "$9.99");
    }
}
