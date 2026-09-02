package io.github.autoscraper.match;

import io.github.autoscraper.soup.AttrValue;

import java.util.List;
import java.util.Objects;

/**
 * A filter applied to a single HTML attribute, reproducing
 * {@code bs4.element.SoupStrainer._matches}.
 *
 * <p>The Python implementation is a chain of {@code isinstance} checks whose <em>order</em> is
 * load-bearing. {@link #matches(AttrValue)} follows that order exactly:
 *
 * <ol>
 *   <li>a multi-valued markup value matches if any token matches, otherwise the space-joined
 *       form is retried once;</li>
 *   <li>an <em>absent</em> attribute matches when the filter itself is falsy, which is how an
 *       empty-string filter selects elements that lack the attribute;</li>
 *   <li>a string filter is compared with equality;</li>
 *   <li>only if equality failed does a filter exposing {@code search} get consulted.</li>
 * </ol>
 */
public sealed interface AttrMatcher {

    /** Matches when the attribute equals {@code value}; an empty value also matches an absent attribute. */
    record Literal(String value) implements AttrMatcher {
        public Literal {
            Objects.requireNonNull(value, "value");
        }
    }

    /** Matches when the attribute equals any listed value; an empty list also matches an absent attribute. */
    record Tokens(List<String> values) implements AttrMatcher {
        public Tokens {
            values = List.copyOf(values);
        }
    }

    /** Matches approximately, standing in for Python's {@code FuzzyText} duck-typed as a regex. */
    record Fuzzy(FuzzyText fuzzy) implements AttrMatcher {
        public Fuzzy {
            Objects.requireNonNull(fuzzy, "fuzzy");
        }
    }

    /**
     * Matches when any nested filter matches, standing in for a Python list of mixed
     * {@code FuzzyText} and plain string entries as produced by {@code _get_fuzzy_attrs}.
     */
    record Any(List<AttrMatcher> options) implements AttrMatcher {
        public Any {
            options = List.copyOf(options);
        }
    }

    /**
     * Applies this filter to an attribute value.
     *
     * @param markup the parsed attribute, or {@code null} when the element lacks the attribute
     * @return {@code true} on a match
     */
    default boolean matches(AttrValue markup) {
        if (markup instanceof AttrValue.Tokens tokens) {
            for (String token : tokens.values()) {
                if (matchesScalar(token)) {
                    return true;
                }
            }
            return matchesScalar(tokens.joined());
        }
        if (markup == null) {
            return isFalsy();
        }
        return matchesScalar(markup.joined());
    }

    /**
     * Reports whether Python would consider this filter falsy, which is the test applied to an
     * absent attribute.
     *
     * @return {@code true} for an empty string or empty list filter
     */
    private boolean isFalsy() {
        if (this instanceof Literal literal) {
            return literal.value().isEmpty();
        }
        if (this instanceof Tokens tokens) {
            return tokens.values().isEmpty();
        }
        if (this instanceof Any any) {
            return any.options().isEmpty();
        }
        return false;
    }

    /**
     * Applies this filter to one already-scalar candidate string.
     *
     * @param candidate a single attribute token or the joined attribute value
     * @return {@code true} on a match
     */
    private boolean matchesScalar(String candidate) {
        if (this instanceof Literal literal) {
            return literal.value().equals(candidate);
        }
        if (this instanceof Tokens tokens) {
            return tokens.values().contains(candidate);
        }
        if (this instanceof Any any) {
            for (AttrMatcher option : any.options()) {
                if (option.matchesScalar(candidate)) {
                    return true;
                }
            }
            return false;
        }
        return ((Fuzzy) this).fuzzy().search(candidate);
    }
}
