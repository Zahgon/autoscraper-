package io.github.autoscraper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.autoscraper.model.Rule;
import io.github.autoscraper.options.BuildOptions;
import io.github.autoscraper.testutil.Fixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the consequences of a Python implementation detail that the port had to freeze.
 *
 * <p>Upstream fills in missing identifying attributes by iterating the set literal
 * {@code {"class", "style"}}, so the order depends on {@code PYTHONHASHSEED} and rule identifiers
 * change between interpreter processes. Two golden corpora were captured, one per ordering. These
 * tests establish that the choice is safe: it moves identifiers only, never scraped values.
 */
class AttributeOrderInvarianceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern HASH = Pattern.compile("\\b[0-9a-f]{64}\\b");
    private static final Pattern RULE_ID = Pattern.compile("rule_[0-9a-f]{8}");

    private static final String CLASS_THEN_STYLE = "/golden/scenarios.json";
    private static final String STYLE_THEN_CLASS = "/golden/scenarios_style_class.json";

    private static Map<String, JsonNode> scenarios(String resource) {
        try (InputStream in = AttributeOrderInvarianceTest.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing golden corpus " + resource
                        + "; regenerate with the oracle fixture generator");
            }
            Map<String, JsonNode> out = new LinkedHashMap<>();
            for (JsonNode scenario : MAPPER.readTree(in).get("scenarios")) {
                out.put(scenario.get("name").asText(), scenario.get("value"));
            }
            return out;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read golden corpus " + resource, e);
        }
    }

    /**
     * Rewrites a recorded value so that only order-independent, identity-independent content
     * remains: object keys are sorted, embedded model documents are parsed so their key order is
     * normalised too, and every rule identifier and hash is renamed to a positional alias.
     *
     * <p>Identifiers are renamed rather than blanked because they also appear as grouping keys,
     * and collapsing them to a single constant would merge distinct groups and hide differences.
     * First-encounter order is stable across both corpora, so the aliases line up.
     */
    private static JsonNode scrub(JsonNode node) {
        Map<String, String> aliases = new LinkedHashMap<>();
        collectIdentifiers(node, aliases);
        return rewrite(node, aliases);
    }

    private static void collectIdentifiers(JsonNode node, Map<String, String> aliases) {
        if (node.isObject()) {
            for (String field : fieldNames(node)) {
                registerAll(field, aliases);
                collectIdentifiers(node.get(field), aliases);
            }
        } else if (node.isArray()) {
            node.forEach(child -> collectIdentifiers(child, aliases));
        } else if (node.isTextual()) {
            JsonNode embedded = parseEmbedded(node.textValue());
            if (embedded != null) {
                collectIdentifiers(embedded, aliases);
            } else {
                registerAll(node.textValue(), aliases);
            }
        }
    }

    private static void registerAll(String value, Map<String, String> aliases) {
        HASH.matcher(value).results()
                .forEach(m -> aliases.computeIfAbsent(m.group(), k -> "HASH_" + aliases.size()));
        RULE_ID.matcher(value).results()
                .forEach(m -> aliases.computeIfAbsent(m.group(), k -> "RULE_" + aliases.size()));
    }

    private static JsonNode rewrite(JsonNode node, Map<String, String> aliases) {
        if (node.isObject()) {
            ObjectNode out = MAPPER.createObjectNode();
            Map<String, JsonNode> renamed = new LinkedHashMap<>();
            for (String field : fieldNames(node)) {
                renamed.put(apply(field, aliases), rewrite(node.get(field), aliases));
            }
            new TreeSet<>(renamed.keySet()).forEach(field -> out.set(field, renamed.get(field)));
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = MAPPER.createArrayNode();
            node.forEach(child -> out.add(rewrite(child, aliases)));
            return out;
        }
        if (node.isTextual()) {
            JsonNode embedded = parseEmbedded(node.textValue());
            return MAPPER.getNodeFactory().textNode(embedded != null
                    ? rewrite(embedded, aliases).toString()
                    : apply(node.textValue(), aliases));
        }
        return node;
    }

    private static JsonNode parseEmbedded(String text) {
        String trimmed = text.trim();
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            return null;
        }
        try {
            return MAPPER.readTree(trimmed);
        } catch (JsonProcessingException notAModelDocument) {
            return null;
        }
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
            names.add(it.next());
        }
        return names;
    }

    private static String apply(String value, Map<String, String> aliases) {
        String out = value;
        for (Map.Entry<String, String> alias : aliases.entrySet()) {
            out = out.replace(alias.getKey(), alias.getValue());
        }
        return out;
    }

    @Test
    @DisplayName("the two attribute orderings really do produce different rule identifiers")
    void theTwoOrderingsDisagree() {
        Map<String, JsonNode> classThenStyle = scenarios(CLASS_THEN_STYLE);
        Map<String, JsonNode> styleThenClass = scenarios(STYLE_THEN_CLASS);

        assertThat(styleThenClass.keySet()).isEqualTo(classThenStyle.keySet());

        List<String> differing = classThenStyle.keySet().stream()
                .filter(name -> !classThenStyle.get(name).equals(styleThenClass.get(name)))
                .toList();

        assertThat(differing)
                .as("the alternate corpus must not silently become a copy of the primary one")
                .hasSize(25)
                .contains("quirk_style_attr", "quirk_multi_valued_class", "build_and_get_result_similar");
    }

    @Test
    @DisplayName("attribute ordering moves rule identifiers only, never scraped values")
    void orderingChangesIdentifiersNotResults() {
        Map<String, JsonNode> classThenStyle = scenarios(CLASS_THEN_STYLE);
        Map<String, JsonNode> styleThenClass = scenarios(STYLE_THEN_CLASS);

        for (Map.Entry<String, JsonNode> entry : classThenStyle.entrySet()) {
            assertThat(scrub(entry.getValue()))
                    .as("scenario %s must agree once rule identifiers are masked", entry.getKey())
                    .isEqualTo(scrub(styleThenClass.get(entry.getKey())));
        }
    }

    @Test
    @DisplayName("this port pins the class-then-style ordering")
    void portPinsClassThenStyleOrdering() {
        AutoScraper scraper = new AutoScraper();
        scraper.build(BuildOptions.builder()
                .html(Fixtures.html("STYLE_ATTR"))
                .wantedList("styled")
                .build());

        List<Rule> rules = scraper.getStackList();
        assertThat(rules).hasSize(1);

        String expected = scenarios(CLASS_THEN_STYLE)
                .get("quirk_style_attr").get("rules").get(0).get("stack_id").asText();
        String alternate = scenarios(STYLE_THEN_CLASS)
                .get("quirk_style_attr").get("rules").get(0).get("stack_id").asText();

        assertThat(expected).isNotEqualTo(alternate);
        assertThat(rules.get(0).stackId()).isEqualTo(expected);
    }
}
