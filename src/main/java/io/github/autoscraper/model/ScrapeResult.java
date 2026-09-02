package io.github.autoscraper.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The outcome of a scrape.
 *
 * <p>Python returns either a list or a dict from the same method depending on the {@code grouped}
 * and {@code group_by_alias} flags. This sealed type makes that union explicit instead of exposing
 * {@code Object}; {@link Flat} corresponds to the list form and {@link Grouped} to the dict form.
 */
public sealed interface ScrapeResult {

    /**
     * The ungrouped list form.
     *
     * @param values extracted values in result order
     */
    record Flat(List<String> values) implements ScrapeResult {
        public Flat {
            values = Collections.unmodifiableList(new java.util.ArrayList<>(values));
        }
    }

    /**
     * The grouped dict form, keyed by rule id or by alias.
     *
     * @param groups extracted values per group, in the order groups were first seen
     */
    record Grouped(Map<String, List<String>> groups) implements ScrapeResult {
        public Grouped {
            Map<String, List<String>> copy = new LinkedHashMap<>();
            groups.forEach((key, value) -> copy.put(key, Collections.unmodifiableList(new java.util.ArrayList<>(value))));
            groups = Collections.unmodifiableMap(copy);
        }
    }

    /**
     * Returns the list form.
     *
     * @return the extracted values
     * @throws IllegalStateException when this result is grouped
     */
    default List<String> asList() {
        if (this instanceof Flat flat) {
            return flat.values();
        }
        throw new IllegalStateException("Result is grouped; call asMap() instead");
    }

    /**
     * Returns the dict form.
     *
     * @return the extracted values per group
     * @throws IllegalStateException when this result is ungrouped
     */
    default Map<String, List<String>> asMap() {
        if (this instanceof Grouped grouped) {
            return grouped.groups();
        }
        throw new IllegalStateException("Result is not grouped; call asList() instead");
    }
}
