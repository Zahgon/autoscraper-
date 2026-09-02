package io.github.autoscraper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.autoscraper.match.TextTarget;
import io.github.autoscraper.model.Rule;
import io.github.autoscraper.model.ScrapeResult;
import io.github.autoscraper.model.StackContentNode;
import io.github.autoscraper.net.HtmlFetcher;
import io.github.autoscraper.options.BuildOptions;
import io.github.autoscraper.options.ExactOptions;
import io.github.autoscraper.options.ResultOptions;
import io.github.autoscraper.options.SimilarOptions;
import io.github.autoscraper.soup.AttrValue;
import io.github.autoscraper.util.PyRepr;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Replays the end-to-end scenarios recorded from the Python implementation.
 *
 * <p>This is the equivalence gate for the whole port: every scenario runs the Java API and compares
 * the outcome against the value the Python original produced for the same inputs.
 */
class ScenarioGoldenTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private static JsonNode golden;
    private static Map<String, String> fixtures;

    @BeforeAll
    static void loadGolden() throws IOException {
        try (InputStream in = ScenarioGoldenTest.class.getResourceAsStream("/golden/scenarios.json")) {
            assertThat(in).as("golden/scenarios.json must be on the test classpath").isNotNull();
            golden = MAPPER.readTree(in);
        }
        fixtures = new LinkedHashMap<>();
        golden.get("fixtures").fields().forEachRemaining(e -> fixtures.put(e.getKey(), e.getValue().asText()));
    }

    private static String f(String name) {
        String html = fixtures.get(name);
        assertThat(html).as("fixture " + name).isNotNull();
        return html;
    }

    /**
     * Builds the scenario table and pairs each entry with its recorded value.
     *
     * @return one dynamic test per recorded scenario
     */
    @TestFactory
    Iterable<DynamicTest> reproducesEveryPythonScenario() {
        Map<String, Supplier<JsonNode>> cases = scenarioTable();
        JsonNode recorded = golden.get("scenarios");

        List<DynamicTest> tests = new ArrayList<>();
        List<String> recordedNames = new ArrayList<>();
        for (JsonNode scenario : recorded) {
            String name = scenario.get("name").asText();
            recordedNames.add(name);
            JsonNode expected = scenario.get("value");
            Supplier<JsonNode> actual = cases.get(name);
            tests.add(DynamicTest.dynamicTest(name, () -> {
                assertThat(actual).as("no Java implementation for scenario " + name).isNotNull();
                assertThat(actual.get().toString()).isEqualTo(parseEmbeddedModels(expected).toString());
            }));
        }
        tests.add(DynamicTest.dynamicTest("scenario table covers every recorded scenario",
                () -> assertThat(cases.keySet()).containsExactlyInAnyOrderElementsOf(recordedNames)));
        return tests;
    }

    /**
     * Re-parses saved model files that the generator recorded as raw JSON text.
     *
     * <p>Python's {@code json.dump} writes {@code ", "} and {@code ": "} separators while Jackson
     * writes neither, so the two files differ in whitespace alone. Comparing parsed documents keeps
     * the assertion on the actual contract, which is that either writer's output loads into the
     * other's reader. Field order survives the round trip, so it stays part of the comparison.
     *
     * @param expected the recorded scenario value
     * @return the value with embedded model text replaced by parsed documents
     */
    private static JsonNode parseEmbeddedModels(JsonNode expected) {
        if (!expected.isObject()) {
            return expected;
        }
        ObjectNode out = MAPPER.createObjectNode();
        expected.fields().forEachRemaining(entry -> {
            String field = entry.getKey();
            JsonNode value = entry.getValue();
            if (value.isTextual() && ("json".equals(field) || "legacy_json".equals(field))) {
                try {
                    out.set(field, MAPPER.readTree(value.asText()));
                } catch (JsonProcessingException e) {
                    throw new IllegalStateException("recorded model for field " + field + " is not JSON", e);
                }
            } else {
                out.set(field, value);
            }
        });
        return out;
    }

    private Map<String, Supplier<JsonNode>> scenarioTable() {
        Map<String, Supplier<JsonNode>> cases = new LinkedHashMap<>();

        cases.put("build_requires_targets", () -> capture("ValueError",
                () -> new AutoScraper().build(BuildOptions.builder().html(f("SIMPLE")).build())));

        cases.put("build_and_get_result_similar", () -> {
            AutoScraper s = new AutoScraper();
            List<String> built = s.build(build(f("SIMPLE"), "Banana"));
            ObjectNode out = NODES.objectNode();
            out.set("built", strings(built));
            out.set("rules", rules(s));
            out.set("similar_csl", result(s.getResultSimilar(
                    SimilarOptions.builder().html(f("SIMPLE")).containSiblingLeaves(true).build())));
            out.set("similar_plain", result(s.getResultSimilar(
                    SimilarOptions.builder().html(f("SIMPLE")).build())));
            return out;
        });

        cases.put("get_result_exact_order", () -> {
            AutoScraper s = new AutoScraper();
            List<String> built = s.build(build(f("COMPLEX_ORDER"), "Banana", "$2"));
            ObjectNode out = NODES.objectNode();
            out.set("built", strings(built));
            out.set("rules", rules(s));
            out.set("exact", result(s.getResultExact(exact(f("COMPLEX_ORDER")))));
            return out;
        });

        cases.put("group_by_alias", () -> {
            AutoScraper s = new AutoScraper();
            s.build(BuildOptions.builder().html(f("SIMPLE"))
                    .wanted("fruit", List.of(new TextTarget.Literal("Banana"))).build());
            ObjectNode out = NODES.objectNode();
            out.set("rules", rules(s));
            out.set("similar", result(s.getResultSimilar(SimilarOptions.builder().html(f("SIMPLE"))
                    .groupByAlias(true).containSiblingLeaves(true).unique(true).build())));
            return out;
        });

        cases.put("save_and_load", () -> {
            try {
                AutoScraper s = new AutoScraper();
                s.build(build(f("SIMPLE"), "Banana"));
                Path p = Files.createTempDirectory("autoscraper").resolve("model.json");
                s.save(p);
                AutoScraper loaded = new AutoScraper();
                loaded.load(p);
                ObjectNode out = NODES.objectNode();
                out.set("json", MAPPER.readTree(Files.readString(p)));
                out.set("exact_orig", result(s.getResultExact(exact(f("SIMPLE")))));
                out.set("exact_loaded", result(loaded.getResultExact(exact(f("SIMPLE")))));
                return out;
            } catch (IOException e) {
                throw new IllegalStateException("scenario save_and_load could not use the filesystem", e);
            }
        });

        cases.put("keep_rules", () -> {
            AutoScraper s = new AutoScraper();
            s.build(build(f("SIMPLE"), "Banana"));
            String first = s.getStackList().get(0).stackId();
            s.build(BuildOptions.builder().html(f("SIMPLE")).wantedList("Apple").update(true).build());
            String second = s.getStackList().get(1).stackId();
            s.keepRules(List.of(second));
            ObjectNode out = NODES.objectNode();
            out.put("first", first);
            out.put("second", second);
            out.set("remaining", strings(ruleIds(s)));
            return out;
        });

        cases.put("get_result_combined", () -> {
            AutoScraper s = new AutoScraper();
            s.build(build(f("SIMPLE"), "Banana"));
            AutoScraper.ResultPair pair = s.getResult(ResultOptions.builder().html(f("SIMPLE")).build());
            ObjectNode out = NODES.objectNode();
            out.set("similar", result(pair.similar()));
            out.set("exact", result(pair.exact()));
            return out;
        });

        cases.put("text_fuzz_ratio_partial", () -> {
            AutoScraper s = new AutoScraper();
            s.build(BuildOptions.builder().html(f("ONE_LI")).wantedList("Banan").textFuzzRatio(0.8).build());
            ObjectNode out = NODES.objectNode();
            out.set("rules", rules(s));
            out.set("exact", result(s.getResultExact(exact(f("ONE_LI")))));
            return out;
        });

        cases.put("set_rule_aliases", () -> {
            AutoScraper s = new AutoScraper();
            s.build(build(f("SIMPLE"), "Banana"));
            String id = s.getStackList().get(0).stackId();
            s.setRuleAliases(Map.of(id, "fruit"));
            ObjectNode out = NODES.objectNode();
            out.put("rule_id", id);
            out.set("result", result(s.getResultSimilar(SimilarOptions.builder().html(f("SIMPLE"))
                    .groupByAlias(true).containSiblingLeaves(true).build())));
            return out;
        });

        cases.put("grouped_results_by_rule", () -> {
            AutoScraper s = new AutoScraper();
            s.build(build(f("SIMPLE"), "Banana"));
            ObjectNode out = NODES.objectNode();
            out.put("rule_id", s.getStackList().get(0).stackId());
            out.set("result", result(s.getResultSimilar(SimilarOptions.builder().html(f("SIMPLE"))
                    .grouped(true).containSiblingLeaves(true).build())));
            return out;
        });

        cases.put("similar_unique_false", () -> {
            AutoScraper s = new AutoScraper();
            s.build(build(f("DUP"), "Banana"));
            ObjectNode out = NODES.objectNode();
            out.set("rules", rules(s));
            out.set("unique_false", result(s.getResultSimilar(
                    SimilarOptions.builder().html(f("DUP")).unique(false).build())));
            out.set("unique_false_csl", result(s.getResultSimilar(SimilarOptions.builder().html(f("DUP"))
                    .unique(false).containSiblingLeaves(true).build())));
            out.set("unique_true", result(s.getResultSimilar(
                    SimilarOptions.builder().html(f("DUP")).unique(true).build())));
            out.set("default", result(s.getResultSimilar(SimilarOptions.builder().html(f("DUP")).build())));
            return out;
        });

        cases.put("similar_keep_order", () -> {
            AutoScraper s = new AutoScraper();
            s.build(build(f("SIMPLE"), "Banana"));
            return result(s.getResultSimilar(SimilarOptions.builder().html(f("SIMPLE"))
                    .containSiblingLeaves(true).keepOrder(true).build()));
        });

        cases.put("extract_relative_link", () -> {
            AutoScraper s = new AutoScraper();
            String url = "https://example.com/index.html";
            List<String> built = s.build(BuildOptions.builder().url(url).html(f("COMPLEX"))
                    .wantedList("https://example.com/apple").build());
            ObjectNode out = NODES.objectNode();
            out.set("built", strings(built));
            out.set("rules", rules(s));
            out.set("similar", result(s.getResultSimilar(SimilarOptions.builder().url(url).html(f("COMPLEX"))
                    .containSiblingLeaves(true).unique(true).build())));
            out.set("exact", result(s.getResultExact(
                    ExactOptions.builder().url(url).html(f("COMPLEX")).build())));
            return out;
        });

        cases.put("build_with_regex", () -> {
            AutoScraper s = new AutoScraper();
            s.build(BuildOptions.builder().html(f("COMPLEX"))
                    .wantedTargets(List.of(new TextTarget.Regex(Pattern.compile("Ban.*")))).build());
            ObjectNode out = NODES.objectNode();
            out.set("rules", rules(s));
            out.set("exact", result(s.getResultExact(exact(f("COMPLEX")))));
            return out;
        });

        cases.put("update_appends_rules", () -> {
            AutoScraper s = new AutoScraper();
            s.build(build(f("COMPLEX"), "Banana"));
            int first = s.getStackList().size();
            s.build(BuildOptions.builder().html(f("COMPLEX")).wantedList("Apple").update(true).build());
            ObjectNode out = NODES.objectNode();
            out.put("count_after_first", first);
            out.put("count_after_update", s.getStackList().size());
            return out;
        });

        cases.put("remove_rules", () -> {
            AutoScraper s = new AutoScraper();
            s.build(build(f("COMPLEX"), "Banana"));
            s.build(BuildOptions.builder().html(f("COMPLEX")).wantedList("Apple").update(true).build());
            List<String> before = ruleIds(s);
            s.removeRules(List.of(before.get(0)));
            ObjectNode out = NODES.objectNode();
            out.set("before", strings(before));
            out.set("after", strings(ruleIds(s)));
            return out;
        });

        cases.put("keep_blank_returns_empty", () -> {
            AutoScraper s = new AutoScraper();
            s.build(build(f("COMPLEX"), "/shop"));
            String blank = f("COMPLEX").replace("href=\"/shop\"", "href=\"\"");
            ObjectNode out = NODES.objectNode();
            out.set("rules", rules(s));
            out.set("exact_keep_blank", result(s.getResultExact(
                    ExactOptions.builder().html(blank).keepBlank(true).build())));
            out.set("exact_no_keep_blank", result(s.getResultExact(exact(blank))));
            return out;
        });

        cases.put("attr_fuzz_ratio", () -> {
            AutoScraper s = new AutoScraper();
            s.build(build(f("BTN_BASE"), "Buy"));
            ObjectNode out = NODES.objectNode();
            out.set("rules", rules(s));
            out.set("fuzz_08", result(s.getResultExact(
                    ExactOptions.builder().html(f("BTN_VARIANT")).attrFuzzRatio(0.8).build())));
            out.set("fuzz_10", result(s.getResultExact(
                    ExactOptions.builder().html(f("BTN_VARIANT")).attrFuzzRatio(1.0).build())));
            return out;
        });

        cases.put("grouping_and_rule_removal", () -> {
            AutoScraper s = new AutoScraper();
            s.build(build(f("PAGE_1"), "Sony PlayStation 4 PS4 Pro 1TB 4K Console - Black",
                    "US $349.99", "4.8", "See details"));
            Map<String, List<String>> grouped = s.getResultExact(
                    ExactOptions.builder().html(f("PAGE_2")).grouped(true).build()).asMap();
            List<String> unwanted = new ArrayList<>();
            grouped.forEach((rule, values) -> {
                if (values.equals(List.of("See details"))) {
                    unwanted.add(rule);
                }
            });
            s.removeRules(unwanted);
            ObjectNode out = NODES.objectNode();
            out.set("grouped", groups(grouped));
            out.set("unwanted", strings(unwanted));
            out.set("result", result(s.getResultExact(exact(f("PAGE_2")))));
            return out;
        });

        cases.put("incremental_learning_multiple_sites", () -> {
            AutoScraper s = new AutoScraper();
            s.build(BuildOptions.builder().html(f("PAGE_1")).wantedList("US $349.99").update(true).build());
            s.build(BuildOptions.builder().html(f("WALMART_1")).wantedList("$8.95").update(true).build());
            s.build(BuildOptions.builder().html(f("ETSY_1")).wantedList("$12.50+").update(true).build());
            ObjectNode out = NODES.objectNode();
            out.set("rules", rules(s));
            out.set("page2", result(s.getResultExact(exact(f("PAGE_2")))));
            out.set("walmart2", result(s.getResultExact(exact(f("WALMART_2")))));
            out.set("etsy2", result(s.getResultExact(exact(f("ETSY_2")))));
            return out;
        });

        cases.put("attr_fuzz_ratio_realistic", () -> {
            AutoScraper s = new AutoScraper();
            s.build(build(f("BTN_BASE2"), "Buy"));
            return result(s.getResultExact(
                    ExactOptions.builder().html(f("BTN_VARIANT2")).attrFuzzRatio(0.8).build()));
        });

        cases.put("regex_name_extraction", () -> {
            AutoScraper s = new AutoScraper();
            s.build(BuildOptions.builder().html(f("PAGE_1"))
                    .wantedTargets(List.of(new TextTarget.Regex(Pattern.compile(".*PlayStation.*Console.*"))))
                    .build());
            return result(s.getResultExact(exact(f("PAGE_1"))));
        });

        cases.put("keep_blank_for_missing_rating", () -> {
            AutoScraper s = new AutoScraper();
            s.build(build(f("PAGE_1"), "4.8"));
            String noRating = f("PAGE_2").replace("5.0", "");
            ObjectNode out = NODES.objectNode();
            out.set("rules", rules(s));
            out.set("result", result(s.getResultExact(
                    ExactOptions.builder().html(noRating).keepBlank(true).build())));
            return out;
        });

        cases.put("quirk_wanted_list_overrides_dict", () -> {
            AutoScraper s = new AutoScraper();
            List<String> built = s.build(BuildOptions.builder().html(f("SIMPLE")).wantedList("Banana")
                    .wanted("x", List.of(new TextTarget.Literal("Apple"))).build());
            ArrayNode aliases = NODES.arrayNode();
            s.getStackList().forEach(r -> aliases.add(r.alias()));
            ObjectNode out = NODES.objectNode();
            out.set("built", strings(built));
            out.set("aliases", aliases);
            return out;
        });

        cases.put("quirk_alias_not_in_hash", () -> {
            AutoScraper a = new AutoScraper();
            a.build(build(f("SIMPLE"), "Banana"));
            AutoScraper b = new AutoScraper();
            b.build(BuildOptions.builder().html(f("SIMPLE"))
                    .wanted("myalias", List.of(new TextTarget.Literal("Banana"))).build());
            String hashA = a.getStackList().get(0).hash();
            String hashB = b.getStackList().get(0).hash();
            ObjectNode out = NODES.objectNode();
            out.put("no_alias_hash", hashA);
            out.put("with_alias_hash", hashB);
            out.put("equal", hashA.equals(hashB));
            return out;
        });

        cases.put("quirk_unique_tristate", () -> {
            AutoScraper s = new AutoScraper();
            s.build(build(f("SIMPLE"), "Banana"));
            ObjectNode out = NODES.objectNode();
            out.set("grouped_unique_none", result(s.getResultSimilar(SimilarOptions.builder()
                    .html(f("SIMPLE")).grouped(true).containSiblingLeaves(true).unique(null).build())));
            out.set("grouped_unique_true", result(s.getResultSimilar(SimilarOptions.builder()
                    .html(f("SIMPLE")).grouped(true).containSiblingLeaves(true).unique(true).build())));
            out.set("ungrouped_unique_none", result(s.getResultSimilar(SimilarOptions.builder()
                    .html(f("SIMPLE")).containSiblingLeaves(true).unique(null).build())));
            return out;
        });

        cases.put("quirk_url_leak_across_rules", () -> {
            AutoScraper s = new AutoScraper();
            String url = "https://example.com/index.html";
            s.build(BuildOptions.builder().url(url).html(f("COMPLEX"))
                    .wantedList("https://example.com/apple").build());
            s.build(BuildOptions.builder().html(f("COMPLEX")).wantedList("Fresh fruits").update(true).build());
            ArrayNode urls = NODES.arrayNode();
            s.getStackList().forEach(r -> urls.add(r.url() == null ? "" : r.url()));
            ObjectNode out = NODES.objectNode();
            out.set("rule_urls", urls);
            out.set("exact_no_url", result(s.getResultExact(exact(f("COMPLEX")))));
            return out;
        });

        cases.put("quirk_non_rec_text", () -> {
            String html = "<div><span>outer<b>inner</b></span></div>";
            AutoScraper s = new AutoScraper();
            s.build(build(html, "outer"));
            ObjectNode out = NODES.objectNode();
            out.set("rules", rules(s));
            out.set("exact", result(s.getResultExact(exact(html))));
            return out;
        });

        cases.put("quirk_multi_valued_class", () -> quirkRoundTrip("MULTI_CLASS", "hit"));
        cases.put("quirk_style_attr", () -> quirkRoundTrip("STYLE_ATTR", "styled"));

        cases.put("quirk_src_full_url", () -> {
            AutoScraper s = new AutoScraper();
            List<String> built = s.build(BuildOptions.builder().url("https://example.com/page/index.html")
                    .html(f("SRC_ATTR")).wantedList("https://example.com/img/a.png").build());
            ObjectNode out = NODES.objectNode();
            out.set("built", strings(built));
            out.set("rules", rules(s));
            return out;
        });

        cases.put("quirk_entity_unescape", () -> quirkRoundTrip("ENTITY", "caf\u00e9 & bar"));

        cases.put("quirk_load_legacy_list", () -> {
            try {
                AutoScraper s = new AutoScraper();
                s.build(build(f("SIMPLE"), "Banana"));
                ArrayNode legacy = NODES.arrayNode();
                s.getStackList().forEach(r -> legacy.add(r.toJson(NODES)));
                Path p = Files.createTempDirectory("autoscraper").resolve("legacy.json");
                Files.writeString(p, MAPPER.writeValueAsString(legacy));
                AutoScraper loaded = new AutoScraper();
                loaded.load(p);
                ObjectNode out = NODES.objectNode();
                out.set("legacy_json", MAPPER.readTree(Files.readString(p)));
                out.set("exact", result(loaded.getResultExact(exact(f("SIMPLE")))));
                return out;
            } catch (IOException e) {
                throw new IllegalStateException("scenario quirk_load_legacy_list could not use the filesystem", e);
            }
        });

        cases.put("quirk_empty_html_falls_through", () -> capture("ValueError",
                () -> new AutoScraper().build(build("", "Banana"))));

        cases.put("quirk_set_rule_aliases_unknown", () -> {
            AutoScraper s = new AutoScraper();
            s.build(build(f("SIMPLE"), "Banana"));
            ObjectNode out = NODES.objectNode();
            try {
                s.setRuleAliases(Map.of("rule_deadbeef", "x"));
                return NODES.textNode("NO_ERROR");
            } catch (IllegalArgumentException e) {
                out.put("error", "KeyError");
                out.put("message", PyRepr.repr(e.getMessage()));
                return out;
            }
        });

        cases.put("request_headers_user_agent",
                () -> NODES.textNode(HtmlFetcher.REQUEST_HEADERS.get("User-Agent")));

        return cases;
    }

    private JsonNode quirkRoundTrip(String fixture, String wanted) {
        AutoScraper s = new AutoScraper();
        s.build(build(f(fixture), wanted));
        ObjectNode out = NODES.objectNode();
        out.set("rules", rules(s));
        out.set("exact", result(s.getResultExact(exact(f(fixture)))));
        return out;
    }

    private static BuildOptions build(String html, String... wanted) {
        return BuildOptions.builder().html(html).wantedList(wanted).build();
    }

    private static ExactOptions exact(String html) {
        return ExactOptions.builder().html(html).build();
    }

    /**
     * Runs a scenario that the Python original ended by raising, and records the failure the same
     * way the generator did.
     *
     * <p>Java's exception vocabulary differs from Python's, so the expected Python type name is
     * supplied by the caller rather than derived from the Java class.
     *
     * @param pythonType the exception type name Python reported
     * @param action     the failing call
     * @return the recorded error shape, or the literal {@code NO_ERROR} when nothing was raised
     */
    private static JsonNode capture(String pythonType, Runnable action) {
        try {
            action.run();
            return NODES.textNode("NO_ERROR");
        } catch (IllegalArgumentException e) {
            ObjectNode out = NODES.objectNode();
            out.put("error", pythonType);
            out.put("message", e.getMessage());
            return out;
        }
    }

    private static List<String> ruleIds(AutoScraper scraper) {
        List<String> ids = new ArrayList<>();
        scraper.getStackList().forEach(r -> ids.add(r.stackId()));
        return ids;
    }

    private static ArrayNode strings(List<String> values) {
        ArrayNode array = NODES.arrayNode();
        values.forEach(array::add);
        return array;
    }

    private static ObjectNode groups(Map<String, List<String>> grouped) {
        ObjectNode out = NODES.objectNode();
        grouped.forEach((key, values) -> out.set(key, strings(values)));
        return out;
    }

    private static JsonNode result(ScrapeResult scrapeResult) {
        if (scrapeResult instanceof ScrapeResult.Flat flat) {
            return strings(flat.values());
        }
        return groups(((ScrapeResult.Grouped) scrapeResult).groups());
    }

    /**
     * Renders the learned rules the way the generator's {@code norm_rules} did.
     *
     * @param scraper the scraper holding the rules
     * @return the rules as recorded JSON
     */
    private static ArrayNode rules(AutoScraper scraper) {
        ArrayNode array = NODES.arrayNode();
        for (Rule rule : scraper.getStackList()) {
            ObjectNode node = NODES.objectNode();
            node.put("stack_id", rule.stackId());
            node.put("hash", rule.hash());
            node.put("alias", rule.aliasOrEmpty());
            node.put("wanted_attr", rule.wantedAttr());
            if (rule.isFullUrl() == null) {
                node.putNull("is_full_url");
            } else {
                node.put("is_full_url", rule.isFullUrl());
            }
            if (rule.isNonRecText() == null) {
                node.putNull("is_non_rec_text");
            } else {
                node.put("is_non_rec_text", rule.isNonRecText());
            }
            node.put("url", rule.url() == null ? "" : rule.url());
            ArrayNode content = NODES.arrayNode();
            for (StackContentNode step : rule.content()) {
                ArrayNode entry = NODES.arrayNode();
                entry.add(step.name());
                ObjectNode attrs = NODES.objectNode();
                step.attrs().forEach((key, value) -> {
                    if (value instanceof AttrValue.Tokens tokens) {
                        attrs.set(key, strings(tokens.values()));
                    } else {
                        attrs.put(key, ((AttrValue.Text) value).value());
                    }
                });
                entry.add(attrs);
                if (step.index() != null) {
                    entry.add(step.index());
                }
                content.add(entry);
            }
            node.set("content", content);
            array.add(node);
        }
        return array;
    }
}
