package io.github.autoscraper.soup;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link Soup} reproduces {@code BeautifulSoup(html, "lxml")} exactly.
 *
 * <p>The fixtures were dumped from the Python oracle by the oracle fixture generator. Every
 * assertion here guards a rule identity: rule IDs hash the element stack, so a single extra
 * {@code <tbody>} or a differently-typed text node would change every hash the port produces.
 */
class SoupParseTreeGoldenTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode loadGolden() throws Exception {
        try (InputStream in = SoupParseTreeGoldenTest.class.getResourceAsStream("/golden/parse_trees.json")) {
            assertThat(in).as("golden/parse_trees.json on the test classpath").isNotNull();
            return MAPPER.readTree(in);
        }
    }

    @Test
    void reproducesLxmlTreeShapeForEveryFixture() throws Exception {
        JsonNode golden = loadGolden();
        assertThat(golden.size()).as("fixture count").isEqualTo(25);

        Iterator<Map.Entry<String, JsonNode>> fixtures = golden.fields();
        while (fixtures.hasNext()) {
            Map.Entry<String, JsonNode> fixture = fixtures.next();
            String name = fixture.getKey();
            JsonNode expected = fixture.getValue();

            SoupElement root = Soup.parse(expected.get("normalized").asText());

            List<String> actualOrder = new ArrayList<>();
            for (SoupElement descendant : root.findChildren()) {
                actualOrder.add(descendant.name());
            }
            List<String> expectedOrder = new ArrayList<>();
            for (JsonNode entry : expected.get("descendant_order")) {
                expectedOrder.add(entry.asText());
            }
            assertThat(actualOrder).as("descendant order for %s", name).isEqualTo(expectedOrder);

            assertNode(name, expected.get("tree"), root);
        }
    }

    private static void assertNode(String fixture, JsonNode expected, SoupElement actual) {
        String path = fixture + " <" + actual.name() + ">";
        assertThat(actual.name()).as("tag name in %s", fixture).isEqualTo(expected.get("name").asText());

        JsonNode expectedAttrs = expected.get("attrs");
        assertThat(actual.attributes().keySet())
                .as("attribute names of %s", path)
                .containsExactlyElementsOf(fieldNames(expectedAttrs));
        for (Map.Entry<String, AttrValue> attribute : actual.attributes().entrySet()) {
            JsonNode expectedValue = expectedAttrs.get(attribute.getKey());
            if (expectedValue.isArray()) {
                List<String> tokens = new ArrayList<>();
                for (JsonNode token : expectedValue) {
                    tokens.add(token.asText());
                }
                assertThat(attribute.getValue())
                        .as("multi-valued attribute %s of %s", attribute.getKey(), path)
                        .isEqualTo(new AttrValue.Tokens(tokens));
            } else {
                assertThat(attribute.getValue())
                        .as("attribute %s of %s", attribute.getKey(), path)
                        .isEqualTo(new AttrValue.Text(expectedValue.asText()));
            }
        }

        assertThat(actual.directText()).as("direct text of %s", path).isEqualTo(expected.get("text_direct").asText());
        assertThat(actual.getText()).as("get_text of %s", path).isEqualTo(expected.get("get_text").asText());

        List<SoupElement> actualChildren = new ArrayList<>();
        for (SoupNode child : actual.children()) {
            if (child instanceof SoupElement element) {
                actualChildren.add(element);
            }
        }
        JsonNode expectedChildren = expected.get("children");
        assertThat(actualChildren).as("child element count of %s", path).hasSize(expectedChildren.size());
        for (int i = 0; i < expectedChildren.size(); i++) {
            assertNode(fixture, expectedChildren.get(i), actualChildren.get(i));
        }
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
