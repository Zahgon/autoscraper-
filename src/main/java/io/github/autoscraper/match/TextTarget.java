package io.github.autoscraper.match;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A wanted-text specification: either a literal string or a compiled regular expression.
 *
 * <p>Python decides between the two at runtime with {@code hasattr(t1, 'fullmatch')}; the sealed
 * type makes the same distinction statically.
 */
public sealed interface TextTarget {

    /** A literal string, compared exactly or fuzzily depending on the text fuzz ratio. */
    record Literal(String value) implements TextTarget {
        public Literal {
            Objects.requireNonNull(value, "value");
        }
    }

    /** A regular expression, always applied as a full match and never fuzzily. */
    record Regex(Pattern pattern) implements TextTarget {
        public Regex {
            Objects.requireNonNull(pattern, "pattern");
        }
    }

    /**
     * Reports whether {@code candidate} satisfies this target.
     *
     * <p>Ported from {@code autoscraper.utils.text_match}. A {@link Regex} ignores
     * {@code ratioLimit} entirely, mirroring the early return in the Python original.
     *
     * @param candidate  the text extracted from the document
     * @param ratioLimit similarity threshold; {@code >= 1} means exact equality
     * @return {@code true} on a match
     */
    default boolean matches(String candidate, double ratioLimit) {
        if (this instanceof Regex regex) {
            return regex.pattern().matcher(candidate).matches();
        }
        String wanted = ((Literal) this).value();
        return ratioLimit >= 1
                ? wanted.equals(candidate)
                : SequenceMatcher.ratio(wanted, candidate) >= ratioLimit;
    }
}
