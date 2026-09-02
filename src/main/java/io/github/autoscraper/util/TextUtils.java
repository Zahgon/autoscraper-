package io.github.autoscraper.util;

import io.github.autoscraper.soup.SoupElement;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * String helpers ported from {@code autoscraper.utils}.
 */
public final class TextUtils {

    private TextUtils() {
    }

    /**
     * Strips then NFKD-normalizes, matching {@code utils.normalize}.
     *
     * <p>The order matters and is not interchangeable: NFKD can turn a non-breaking space into an
     * ordinary space, so normalizing first would strip characters the Python original keeps.
     *
     * @param item the text to normalize
     * @return the normalized text
     */
    public static String normalize(String item) {
        Objects.requireNonNull(item, "item");
        return Normalizer.normalize(strip(item), Normalizer.Form.NFKD);
    }

    /**
     * Joins the direct text children and strips, matching {@code utils.get_non_rec_text}.
     *
     * @param element the element to read
     * @return the stripped direct text, comment bodies included
     */
    public static String getNonRecText(SoupElement element) {
        Objects.requireNonNull(element, "element");
        return strip(element.directText());
    }

    /**
     * Removes duplicates while preserving first-occurrence order, matching
     * {@code utils.unique_hashable}.
     *
     * @param items the input sequence
     * @param <T>   the element type
     * @return a new list without duplicates
     */
    public static <T> List<T> uniqueHashable(Collection<T> items) {
        Objects.requireNonNull(items, "items");
        return new ArrayList<>(new LinkedHashSet<>(items));
    }

    /**
     * Strips leading and trailing whitespace using Python's definition of whitespace.
     *
     * <p>{@code String.strip()} is not a substitute: it defers to
     * {@code Character.isWhitespace}, which rejects the non-breaking spaces U+00A0, U+2007 and
     * U+202F that Python's {@code str.strip()} removes.
     *
     * @param value the text to strip
     * @return the stripped text
     */
    public static String strip(String value) {
        Objects.requireNonNull(value, "value");
        int start = 0;
        int end = value.length();
        while (start < end && isPythonSpace(value.charAt(start))) {
            start++;
        }
        while (end > start && isPythonSpace(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(start, end);
    }

    /**
     * Reports whether Python's {@code str.isspace()} would be true for {@code c}.
     *
     * @param c the character to test
     * @return {@code true} for a Python whitespace character
     */
    private static boolean isPythonSpace(char c) {
        switch (c) {
            // The ASCII whitespace run plus the file/group/record/unit separators and the
            // NEL and NBSP characters, all of which CPython treats as whitespace.
            case '\t', '\n', '\u000B', '\f', '\r',
                 '\u001C', '\u001D', '\u001E', '\u001F',
                 ' ', '\u0085', '\u00A0' -> {
                return true;
            }
            default -> {
                int type = Character.getType(c);
                return type == Character.SPACE_SEPARATOR
                        || type == Character.LINE_SEPARATOR
                        || type == Character.PARAGRAPH_SEPARATOR;
            }
        }
    }
}
