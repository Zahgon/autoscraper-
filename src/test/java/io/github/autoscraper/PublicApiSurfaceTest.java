package io.github.autoscraper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.autoscraper.match.SequenceMatcher;
import io.github.autoscraper.model.Rule;
import io.github.autoscraper.model.ScrapeResult;
import io.github.autoscraper.options.BuildOptions;
import io.github.autoscraper.options.ExactOptions;
import io.github.autoscraper.options.RequestArgs;
import io.github.autoscraper.options.ResultOptions;
import io.github.autoscraper.options.SimilarOptions;
import io.github.autoscraper.soup.AttrValue;
import io.github.autoscraper.soup.Soup;
import io.github.autoscraper.soup.SoupElement;
import io.github.autoscraper.testutil.Fixtures;
import io.github.autoscraper.util.PyRepr;
import io.github.autoscraper.util.PyUrl;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises published entry points that the ported upstream suite never reaches.
 *
 * <p>Upstream's tests drive the scraper through one happy path, so identity methods, the
 * deprecated code generator, the cheap ratio bounds and several option setters were never
 * executed even though they are part of the surface callers can use. Each test here states the
 * contract the method is supposed to honour rather than merely calling it.
 */
class PublicApiSurfaceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static AutoScraper trained() {
        AutoScraper scraper = new AutoScraper();
        scraper.build(BuildOptions.builder()
                .html(Fixtures.html("SIMPLE"))
                .wantedList("Banana")
                .build());
        return scraper;
    }

    @Nested
    class RuleIdentity {

        @Test
        void rulesLearnedFromTheSamePageAreEqualAndShareAHashCode() {
            Rule first = trained().getStackList().get(0);
            Rule second = trained().getStackList().get(0);

            assertThat(first).isEqualTo(second);
            assertThat(first).hasSameHashCodeAs(second);
        }

        @Test
        void rulesLearnedFromDifferentPagesDifferAndAreNotEqualToOtherTypes() {
            AutoScraper other = new AutoScraper();
            other.build(BuildOptions.builder()
                    .html(Fixtures.html("COMPLEX_ORDER"))
                    .wantedList("$2")
                    .build());

            Rule banana = trained().getStackList().get(0);
            Rule price = other.getStackList().get(0);

            assertThat(banana).isNotEqualTo(price);
            assertThat(banana).isNotEqualTo("rule_eddd3f53");
            assertThat(banana).isEqualTo(banana);
        }

        @Test
        void anAliasParticipatesInEqualityEvenThoughItIsExcludedFromTheHash() {
            Rule plain = trained().getStackList().get(0);
            Rule aliased = trained().getStackList().get(0);
            aliased.setAlias("fruit");

            assertThat(aliased.hash()).isEqualTo(plain.hash());
            assertThat(aliased).isNotEqualTo(plain);
        }

        @Test
        void aRulePrintsAsItsIdentifier() {
            Rule rule = trained().getStackList().get(0);

            assertThat(rule).hasToString(rule.stackId());
            assertThat(rule.stackId()).isEqualTo("rule_eddd3f53");
        }

        @Test
        void aRuleWithMultiValuedAttributesSurvivesAJsonRoundTrip() throws Exception {
            AutoScraper scraper = new AutoScraper();
            scraper.build(BuildOptions.builder()
                    .url("https://example.com/index.html")
                    .html(Fixtures.html("COMPLEX"))
                    .wantedList("https://example.com/apple")
                    .build());
            Rule original = scraper.getStackList().get(0);

            assertThat(original.stackId()).isEqualTo("rule_a6600eca");
            assertThat(original.content()).hasSize(7);

            JsonNode encoded = MAPPER.readTree(MAPPER.writeValueAsString(
                    original.toJson(MAPPER.getNodeFactory())));
            Rule restored = Rule.fromJson(encoded);

            assertThat(restored.stackId()).isEqualTo(original.stackId());
            assertThat(restored.hash()).isEqualTo(original.hash());
            assertThat(restored.content()).isEqualTo(original.content());
            assertThat(restored.wantedAttr()).isEqualTo("href");
            assertThat(restored.isFullUrl()).isTrue();
            assertThat(restored.url()).isEqualTo("https://example.com/index.html");

            assertThat(restored.content().get(4).attrs().get("class"))
                    .isEqualTo(new AttrValue.Tokens(List.of("fruits")));
            assertThat(restored.content().get(6).attrs().get("class"))
                    .isEqualTo(new AttrValue.Tokens(List.of("link")));
        }
    }

    @Nested
    class ScraperFacade {

        @Test
        void rulesCanBeTransplantedIntoAnotherScraper() {
            AutoScraper source = trained();
            AutoScraper target = new AutoScraper();

            assertThat(target.getStackList()).isEmpty();
            target.setStackList(source.getStackList());

            ScrapeResult extracted = target.getResultExact(ExactOptions.builder()
                    .html(Fixtures.html("SIMPLE"))
                    .build());

            assertThat(target.getStackList()).hasSize(1);
            assertThat(extracted.asList()).containsExactly("Banana");
        }

        @Test
        void aScraperCanBeConstructedDirectlyFromLearnedRules() {
            List<Rule> learned = trained().getStackList();

            AutoScraper restored = new AutoScraper(learned);

            ScrapeResult extracted = restored.getResultExact(ExactOptions.builder()
                    .html(Fixtures.html("SIMPLE"))
                    .build());

            assertThat(restored.getStackList()).hasSize(1);
            assertThat(extracted.asList()).containsExactly("Banana");
        }

        @Test
        @SuppressWarnings("deprecation")
        void theDeprecatedCodeGeneratorStillRunsWithoutFailing() {
            AutoScraper scraper = trained();

            assertThatThrownBy(() -> {
                scraper.generatePythonCode();
                throw new IllegalStateException("sentinel");
            }).isInstanceOf(IllegalStateException.class).hasMessage("sentinel");
        }
    }

    @Nested
    class OptionBuilders {

        @Test
        void requestSettingsReachEveryOperationThatCanFetchAPage() {
            RequestArgs build = new RequestArgs().header("X-Trace", "build");
            RequestArgs similar = new RequestArgs().header("X-Trace", "similar");
            RequestArgs exact = new RequestArgs().header("X-Trace", "exact");
            RequestArgs combined = new RequestArgs().header("X-Trace", "combined");

            assertThat(BuildOptions.builder().requestArgs(build).build().requestArgs())
                    .isSameAs(build);
            assertThat(SimilarOptions.builder().requestArgs(similar).build().requestArgs())
                    .isSameAs(similar);
            assertThat(ExactOptions.builder().requestArgs(exact).build().requestArgs())
                    .isSameAs(exact);
            assertThat(ResultOptions.builder().requestArgs(combined).build().requestArgs())
                    .isSameAs(combined);
        }

        @Test
        void blankValuesCanBeKeptThroughTheSimilarBuilder() {
            AutoScraper scraper = new AutoScraper();
            scraper.build(BuildOptions.builder()
                    .html(Fixtures.html("WALMART_1"))
                    .wantedList("$8.95")
                    .build());

            ScrapeResult kept = scraper.getResultSimilar(SimilarOptions.builder()
                    .html(Fixtures.html("WALMART_2"))
                    .keepBlank(true)
                    .build());

            assertThat(kept.asList()).containsExactly("$7.00");
        }

        @Test
        void theCombinedBuilderCarriesTheUrlToBothStrategies() {
            AutoScraper scraper = new AutoScraper();
            scraper.build(BuildOptions.builder()
                    .url("https://example.com/index.html")
                    .html(Fixtures.html("COMPLEX"))
                    .wantedList("https://example.com/apple")
                    .build());

            AutoScraper.ResultPair pair = scraper.getResult(ResultOptions.builder()
                    .url("https://example.com/index.html")
                    .html(Fixtures.html("COMPLEX"))
                    .build());

            assertThat(pair.similar().asList()).containsExactly(
                    "https://example.com/banana",
                    "https://example.com/apple",
                    "https://example.com/orange");
            assertThat(pair.exact().asList()).containsExactly("https://example.com/apple");
        }
    }

    @Nested
    class RatioShortcuts {

        @Test
        void theCheapBoundsNeverUnderstateTheRealRatio() {
            SequenceMatcher matcher = new SequenceMatcher("btn-primary", "btn-prime");

            double real = matcher.ratio();

            assertThat(matcher.quickRatio()).isGreaterThanOrEqualTo(real);
            assertThat(matcher.realQuickRatio()).isGreaterThanOrEqualTo(matcher.quickRatio());
            assertThat(matcher.realQuickRatio()).isLessThanOrEqualTo(1.0);
        }

        @Test
        void identicalAndDisjointInputsPinBothEndsOfTheRange() {
            assertThat(new SequenceMatcher("abc", "abc").quickRatio()).isEqualTo(1.0);
            assertThat(new SequenceMatcher("abc", "xyz").quickRatio()).isEqualTo(0.0);
            assertThat(new SequenceMatcher("", "").realQuickRatio()).isEqualTo(1.0);
        }
    }

    @Nested
    class PythonHelpers {

        @Test
        void aMappingIsRenderedWithPythonPunctuationAndInsertionOrder() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("class", "");
            map.put("style", List.of("a", "b"));
            map.put("index", 0);
            map.put("flag", null);

            assertThat(PyRepr.reprMap(map))
                    .isEqualTo("{'class': '', 'style': ['a', 'b'], 'index': 0, 'flag': None}");
            assertThat(PyRepr.reprMap(Map.of())).isEqualTo("{}");
        }

        @Test
        void bracketedHostsAreAccepted() {
            PyUrl.Split split = PyUrl.urlsplit("http://[::1]:8080/path?q=1", null);

            assertThat(split.scheme()).isEqualTo("http");
            assertThat(split.netloc()).isEqualTo("[::1]:8080");
            assertThat(split.path()).isEqualTo("/path");
            assertThat(split.query()).isEqualTo("q=1");
        }

        @Test
        void anUnbalancedBracketIsRejected() {
            assertThatThrownBy(() -> PyUrl.urlsplit("http://[::1/path", null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> PyUrl.urlsplit("http://::1]/path", null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void aSuppliedDefaultSchemeIsCleanedBeforeUse() {
            PyUrl.Split split = PyUrl.urlsplit("//example.com/path", "  https\t\n ");

            assertThat(split.scheme()).isEqualTo("https");
            assertThat(split.netloc()).isEqualTo("example.com");
            assertThat(split.path()).isEqualTo("/path");
        }
    }

    @Nested
    class SoupPrinting {

        @Test
        void anElementPrintsAsItsTagName() {
            SoupElement document = Soup.parse(Fixtures.html("SIMPLE"));

            assertThat(document).hasToString("<[document]>");
            assertThat(document.findChildren().get(0)).hasToString("<html>");
        }
    }
}
