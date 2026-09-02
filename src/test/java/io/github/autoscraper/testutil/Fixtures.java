package io.github.autoscraper.testutil;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Serves the HTML documents the Python fixture generator recorded.
 *
 * <p>Tests read markup from here rather than restating it so that the Java and Python suites are
 * provably running against the same bytes.
 */
public final class Fixtures {

    private static final Map<String, String> HTML = load();

    private Fixtures() {
    }

    private static Map<String, String> load() {
        try (InputStream in = Fixtures.class.getResourceAsStream("/golden/scenarios.json")) {
            if (in == null) {
                throw new IllegalStateException(
                        "golden/scenarios.json is missing; regenerate it with the oracle fixture generator");
            }
            JsonNode fixtures = new ObjectMapper().readTree(in).get("fixtures");
            Map<String, String> out = new LinkedHashMap<>();
            fixtures.fields().forEachRemaining(entry -> out.put(entry.getKey(), entry.getValue().asText()));
            return Map.copyOf(out);
        } catch (IOException e) {
            throw new IllegalStateException("could not read golden/scenarios.json", e);
        }
    }

    /**
     * Returns the recorded HTML for a fixture.
     *
     * @param name the fixture name
     * @return the HTML exactly as the Python suite declares it
     */
    public static String html(String name) {
        String html = HTML.get(name);
        if (html == null) {
            throw new IllegalArgumentException("no fixture named " + name + "; known: " + HTML.keySet());
        }
        return html;
    }
}
