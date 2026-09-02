package io.github.autoscraper;

import io.github.autoscraper.match.TextTarget;
import io.github.autoscraper.model.Rule;
import io.github.autoscraper.options.BuildOptions;
import io.github.autoscraper.options.ExactOptions;
import io.github.autoscraper.options.ResultOptions;
import io.github.autoscraper.options.SimilarOptions;
import io.github.autoscraper.testutil.Fixtures;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The upstream Python suite, translated case for case.
 *
 * <p>Each nested class corresponds to one Python test module and each method to one test function,
 * so a reviewer can diff the two suites by name.
 */
class PortedPythonSuiteTest {

    private static final String HTML = Fixtures.html("SIMPLE");
    private static final String HTML_DUP = Fixtures.html("DUP");
    private static final String HTML_COMPLEX_ORDER = Fixtures.html("COMPLEX_ORDER");
    private static final String HTML_COMPLEX = Fixtures.html("COMPLEX");
    private static final String HTML_PAGE_1 = Fixtures.html("PAGE_1");
    private static final String HTML_PAGE_2 = Fixtures.html("PAGE_2");

    private static AutoScraper trained(String html, String... wanted) {
        AutoScraper scraper = new AutoScraper();
        scraper.build(BuildOptions.builder().html(html).wantedList(wanted).build());
        return scraper;
    }

    private static List<String> exact(AutoScraper scraper, String html) {
        return scraper.getResultExact(ExactOptions.builder().html(html).build()).asList();
    }

    private static List<String> ruleIds(AutoScraper scraper) {
        return scraper.getStackList().stream().map(Rule::stackId).toList();
    }

    @Nested
    class Build {

        @Test
        void buildRequiresTargets() {
            AutoScraper scraper = new AutoScraper();
            assertThatThrownBy(() -> scraper.build(BuildOptions.builder().html(HTML).build()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("No targets were supplied");
        }

        @Test
        void buildAndGetResultSimilar() {
            AutoScraper scraper = new AutoScraper();
            List<String> result =
                    scraper.build(BuildOptions.builder().html(HTML).wantedList("Banana").build());
            assertThat(result).containsExactly("Banana");

            List<String> similar = scraper.getResultSimilar(
                            SimilarOptions.builder().html(HTML).containSiblingLeaves(true).build())
                    .asList();
            assertThat(similar).containsExactly("Banana", "Apple", "Orange");
        }
    }

    @Nested
    class Features {

        @Test
        void getResultExactOrder() {
            AutoScraper scraper = trained(HTML_COMPLEX_ORDER, "Banana", "$2");
            assertThat(exact(scraper, HTML_COMPLEX_ORDER)).containsExactly("Banana", "$2");
        }

        @Test
        void groupByAlias() {
            AutoScraper scraper = new AutoScraper();
            scraper.build(BuildOptions.builder()
                    .html(HTML)
                    .wanted("fruit", List.of(new TextTarget.Literal("Banana")))
                    .build());

            Map<String, List<String>> similar = scraper.getResultSimilar(SimilarOptions.builder()
                            .html(HTML)
                            .groupByAlias(true)
                            .containSiblingLeaves(true)
                            .unique(true)
                            .build())
                    .asMap();
            assertThat(similar).containsExactly(Map.entry("fruit", List.of("Banana", "Apple", "Orange")));
        }

        @Test
        void saveAndLoad(@TempDir Path tempDir) {
            AutoScraper scraper = trained(HTML, "Banana");
            Path modelPath = tempDir.resolve("model.json");
            scraper.save(modelPath);

            AutoScraper reloaded = new AutoScraper();
            reloaded.load(modelPath);
            assertThat(exact(reloaded, HTML)).isEqualTo(exact(scraper, HTML));
        }

        @Test
        void keepRules() {
            AutoScraper scraper = trained(HTML, "Banana");
            scraper.build(BuildOptions.builder().html(HTML).wantedList("Apple").update(true).build());

            String secondRule = scraper.getStackList().get(1).stackId();
            scraper.keepRules(List.of(secondRule));

            assertThat(ruleIds(scraper)).containsExactly(secondRule);
        }

        @Test
        void getResultCombined() {
            AutoScraper scraper = trained(HTML, "Banana");
            AutoScraper.ResultPair pair = scraper.getResult(ResultOptions.builder().html(HTML).build());

            assertThat(pair.exact().asList()).containsExactly("Banana");
            assertThat(pair.similar().asList()).containsExactly("Banana");
        }
    }

    @Nested
    class AdditionalFeatures {

        @Test
        void textFuzzRatioPartial() {
            String oneLi = Fixtures.html("ONE_LI");
            AutoScraper scraper = new AutoScraper();
            scraper.build(BuildOptions.builder()
                    .html(oneLi)
                    .wantedList("Banan")
                    .textFuzzRatio(0.8)
                    .build());

            assertThat(exact(scraper, oneLi)).containsExactly("Banana");
        }

        @Test
        void setRuleAliases() {
            AutoScraper scraper = trained(HTML, "Banana");
            scraper.setRuleAliases(Map.of(scraper.getStackList().get(0).stackId(), "fruit"));

            Map<String, List<String>> result = scraper.getResultSimilar(SimilarOptions.builder()
                            .html(HTML)
                            .groupByAlias(true)
                            .containSiblingLeaves(true)
                            .build())
                    .asMap();
            assertThat(result).containsExactly(Map.entry("fruit", List.of("Banana", "Apple", "Orange")));
        }

        @Test
        void groupedResultsByRule() {
            AutoScraper scraper = trained(HTML, "Banana");
            String ruleId = scraper.getStackList().get(0).stackId();

            Map<String, List<String>> result = scraper.getResultSimilar(SimilarOptions.builder()
                            .html(HTML)
                            .grouped(true)
                            .containSiblingLeaves(true)
                            .build())
                    .asMap();
            assertThat(result).containsExactly(Map.entry(ruleId, List.of("Banana", "Apple", "Orange")));
        }

        /**
         * The upstream assertion of {@code ["Banana", "Banana"]} only holds under the stub parser
         * that {@code tests/conftest.py} installs. Against real BeautifulSoup the two identical
         * {@code <li>} elements hash alike, so learning keeps a single rule and the ungrouped result
         * is one value. PORTING-NOTES.md records the divergence; the sibling-leaf variant below is
         * what actually yields the repeated value.
         */
        @Test
        void similarUniqueFalse() {
            AutoScraper scraper = trained(HTML_DUP, "Banana");
            assertThat(scraper.getStackList()).hasSize(1);

            assertThat(scraper.getResultSimilar(
                            SimilarOptions.builder().html(HTML_DUP).unique(false).build())
                    .asList()).containsExactly("Banana");

            assertThat(scraper.getResultSimilar(SimilarOptions.builder()
                            .html(HTML_DUP)
                            .unique(false)
                            .containSiblingLeaves(true)
                            .build())
                    .asList()).containsExactly("Banana", "Banana");
        }

        @Test
        void similarKeepOrder() {
            AutoScraper scraper = trained(HTML, "Banana");
            List<String> result = scraper.getResultSimilar(SimilarOptions.builder()
                            .html(HTML)
                            .containSiblingLeaves(true)
                            .keepOrder(true)
                            .build())
                    .asList();
            assertThat(result).containsExactly("Banana", "Apple", "Orange");
        }
    }

    @Nested
    class ComplexFeatures {

        @Test
        void extractRelativeLink() {
            String url = "https://example.com/index.html";
            AutoScraper scraper = new AutoScraper();
            List<String> result = scraper.build(BuildOptions.builder()
                    .url(url)
                    .html(HTML_COMPLEX)
                    .wantedList("https://example.com/apple")
                    .build());
            assertThat(result).contains("https://example.com/apple");

            List<String> similar = scraper.getResultSimilar(SimilarOptions.builder()
                            .url(url)
                            .html(HTML_COMPLEX)
                            .containSiblingLeaves(true)
                            .unique(true)
                            .build())
                    .asList();
            assertThat(similar).containsExactlyInAnyOrder(
                    "https://example.com/banana",
                    "https://example.com/apple",
                    "https://example.com/orange");

            List<String> exact = scraper.getResultExact(
                            ExactOptions.builder().url(url).html(HTML_COMPLEX).build())
                    .asList();
            assertThat(exact).containsExactly("https://example.com/apple");
        }

        @Test
        void buildWithRegex() {
            AutoScraper scraper = new AutoScraper();
            scraper.build(BuildOptions.builder()
                    .html(HTML_COMPLEX)
                    .wantedTargets(List.of(new TextTarget.Regex(Pattern.compile("Ban.*"))))
                    .build());

            assertThat(exact(scraper, HTML_COMPLEX).get(0)).contains("Banana");
        }

        @Test
        void updateAppendsRules() {
            AutoScraper scraper = trained(HTML_COMPLEX, "Banana");
            int count = scraper.getStackList().size();

            scraper.build(BuildOptions.builder()
                    .html(HTML_COMPLEX)
                    .wantedList("Apple")
                    .update(true)
                    .build());

            assertThat(scraper.getStackList()).hasSize(count + 1);
        }

        @Test
        void removeRules() {
            AutoScraper scraper = trained(HTML_COMPLEX, "Banana");
            scraper.build(BuildOptions.builder()
                    .html(HTML_COMPLEX)
                    .wantedList("Apple")
                    .update(true)
                    .build());

            List<String> ruleIds = ruleIds(scraper);
            String toRemove = ruleIds.get(0);
            scraper.removeRules(List.of(toRemove));

            assertThat(ruleIds(scraper)).doesNotContain(toRemove).hasSize(ruleIds.size() - 1);
        }

        @Test
        void keepBlankReturnsEmpty() {
            AutoScraper scraper = trained(HTML_COMPLEX, "/shop");
            String blank = HTML_COMPLEX.replace("href=\"/shop\"", "href=\"\"");

            List<String> result = scraper.getResultExact(
                            ExactOptions.builder().html(blank).keepBlank(true).build())
                    .asList();
            assertThat(result).containsExactly("");
        }

        @Test
        void attrFuzzRatio() {
            AutoScraper scraper = trained(Fixtures.html("BTN_BASE"), "Buy");

            List<String> result = scraper.getResultExact(ExactOptions.builder()
                            .html(Fixtures.html("BTN_VARIANT"))
                            .attrFuzzRatio(0.8)
                            .build())
                    .asList();
            assertThat(result).containsExactly("Buy");
        }
    }

    @Nested
    class RealWorld {

        @Test
        void groupingAndRuleRemoval() {
            AutoScraper scraper = trained(HTML_PAGE_1,
                    "Sony PlayStation 4 PS4 Pro 1TB 4K Console - Black",
                    "US $349.99",
                    "4.8",
                    "See details");

            Map<String, List<String>> grouped = scraper.getResultExact(
                            ExactOptions.builder().html(HTML_PAGE_2).grouped(true).build())
                    .asMap();
            List<String> unwanted = grouped.entrySet().stream()
                    .filter(entry -> entry.getValue().equals(List.of("See details")))
                    .map(Map.Entry::getKey)
                    .toList();
            scraper.removeRules(unwanted);

            assertThat(exact(scraper, HTML_PAGE_2)).containsExactly(
                    "Acer Predator Helios 300 15.6'' 144Hz FHD Laptop i7-9750H 16GB 512GB GTX 1660 Ti",
                    "US $1,229.49",
                    "5.0");
        }

        @Test
        void incrementalLearningMultipleSites() {
            AutoScraper scraper = new AutoScraper();
            record Lesson(String html, String wanted) {
            }
            List<Lesson> lessons = List.of(
                    new Lesson(HTML_PAGE_1, "US $349.99"),
                    new Lesson(Fixtures.html("WALMART_1"), "$8.95"),
                    new Lesson(Fixtures.html("ETSY_1"), "$12.50+"));

            for (Lesson lesson : lessons) {
                scraper.build(BuildOptions.builder()
                        .html(lesson.html())
                        .wantedList(lesson.wanted())
                        .update(true)
                        .build());
            }

            assertThat(exact(scraper, HTML_PAGE_2)).contains("US $1,229.49");
            assertThat(exact(scraper, Fixtures.html("WALMART_2"))).contains("$7.00");
            assertThat(exact(scraper, Fixtures.html("ETSY_2"))).contains("$60.00");
        }

        @Test
        void attrFuzzRatioRealistic() {
            AutoScraper scraper = trained(Fixtures.html("BTN_BASE2"), "Buy");

            List<String> result = scraper.getResultExact(ExactOptions.builder()
                            .html(Fixtures.html("BTN_VARIANT2"))
                            .attrFuzzRatio(0.8)
                            .build())
                    .asList();
            assertThat(result).containsExactly("Buy");
        }

        @Test
        void regexNameExtraction() {
            AutoScraper scraper = new AutoScraper();
            scraper.build(BuildOptions.builder()
                    .html(HTML_PAGE_1)
                    .wantedTargets(List.of(new TextTarget.Regex(Pattern.compile(".*PlayStation.*Console.*"))))
                    .build());

            assertThat(exact(scraper, HTML_PAGE_1)).anyMatch(value -> value.contains("PlayStation"));
        }

        @Test
        void keepBlankForMissingRating() {
            AutoScraper scraper = trained(HTML_PAGE_1, "4.8");
            String noRating = HTML_PAGE_2.replace("5.0", "");

            List<String> result = scraper.getResultExact(
                            ExactOptions.builder().html(noRating).keepBlank(true).build())
                    .asList();
            assertThat(result).containsExactly("");
        }
    }
}
