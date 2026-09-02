package io.github.autoscraper.soup;

import java.util.List;

/**
 * The value of a parsed HTML attribute, as BeautifulSoup models it.
 *
 * <p>bs4 stores most attributes as a plain string, but splits a fixed set of
 * "CDATA list" attributes (notably {@code class}) into a list of tokens. The distinction is
 * observable: attribute matching tries each token individually before falling back to the
 * space-joined form, and {@code class=""} becomes an <em>empty list</em> rather than an empty
 * string. Modelling this as a sealed type keeps the two shapes apart in the type system.
 */
public sealed interface AttrValue {

    /** A single-valued attribute, e.g. {@code style="color:red"}. */
    record Text(String value) implements AttrValue {}

    /** A multi-valued attribute split on whitespace, e.g. {@code class="a b"}. */
    record Tokens(List<String> values) implements AttrValue {
        public Tokens {
            values = List.copyOf(values);
        }
    }

    /**
     * Returns the space-joined form bs4 falls back to when no individual token matches.
     *
     * @return the value for {@link Text}, or the tokens joined with a single space
     */
    default String joined() {
        if (this instanceof Text text) {
            return text.value();
        }
        return String.join(" ", ((Tokens) this).values());
    }
}
