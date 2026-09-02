package io.github.autoscraper.match;

import java.util.Objects;

/**
 * An approximate string filter, ported from {@code autoscraper.utils.FuzzyText}.
 *
 * <p>In Python this class is accepted by BeautifulSoup's attribute matching purely because it
 * exposes a {@code search} method, which makes it duck-type as a compiled regular expression.
 * The Java port reproduces that role explicitly through {@link AttrMatcher.Fuzzy}.
 *
 * @param text       the reference text to compare candidates against
 * @param ratioLimit the inclusive similarity threshold
 */
public record FuzzyText(String text, double ratioLimit) {
    public FuzzyText {
        Objects.requireNonNull(text, "text");
    }

    /**
     * Reports whether {@code candidate} is similar enough to {@link #text}.
     *
     * <p>Argument order is significant: {@code difflib.SequenceMatcher} is not symmetric, and the
     * Python original passes the reference text first.
     *
     * @param candidate the string being tested
     * @return {@code true} when the similarity ratio is at least {@link #ratioLimit}
     */
    public boolean search(String candidate) {
        return SequenceMatcher.ratio(text, candidate) >= ratioLimit;
    }
}
