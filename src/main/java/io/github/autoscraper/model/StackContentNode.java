package io.github.autoscraper.model;

import io.github.autoscraper.soup.AttrValue;
import io.github.autoscraper.util.PyRepr;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One step of a learned rule's path from the document root down to the wanted element.
 *
 * <p>Ancestor steps carry the sibling {@code index} at which the path continues; the final step
 * (the wanted element itself) carries none. Python builds these as tuples of mixed arity, and both
 * the JSON form and the hashed {@code repr} preserve that difference, so {@code index} is nullable
 * rather than defaulted.
 *
 * @param name  the tag name, or {@code [document]} for the root step
 * @param attrs the class/style attributes kept by {@code _get_valid_attrs}
 * @param index the sibling position, or {@code null} for the final step
 */
public record StackContentNode(String name, Map<String, AttrValue> attrs, Integer index) {

    public StackContentNode {
        Objects.requireNonNull(name, "name");
        attrs = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(attrs, "attrs")));
    }

    /**
     * Creates the final step, matching Python's two-element tuple.
     *
     * @param name  the tag name
     * @param attrs the kept attributes
     * @return a step without a sibling index
     */
    public static StackContentNode leaf(String name, Map<String, AttrValue> attrs) {
        return new StackContentNode(name, attrs, null);
    }

    /**
     * Creates an ancestor step, matching Python's three-element tuple.
     *
     * @param name  the tag name
     * @param attrs the kept attributes
     * @param index the sibling position at which the path continues
     * @return a step carrying a sibling index
     */
    public static StackContentNode branch(String name, Map<String, AttrValue> attrs, int index) {
        return new StackContentNode(name, attrs, index);
    }

    /**
     * Renders this step as the Python object whose {@code repr} feeds the rule hash.
     *
     * @return a tuple of the name, the attribute dict, and the index when present
     */
    public PyRepr.PyTuple toPyObject() {
        Map<String, Object> pyAttrs = new LinkedHashMap<>();
        for (Map.Entry<String, AttrValue> entry : attrs.entrySet()) {
            pyAttrs.put(entry.getKey(), toPyValue(entry.getValue()));
        }
        List<Object> items = new ArrayList<>();
        items.add(name);
        items.add(pyAttrs);
        if (index != null) {
            items.add(index);
        }
        return new PyRepr.PyTuple(items);
    }

    private static Object toPyValue(AttrValue value) {
        if (value instanceof AttrValue.Text text) {
            return text.value();
        }
        return new ArrayList<>(((AttrValue.Tokens) value).values());
    }
}
