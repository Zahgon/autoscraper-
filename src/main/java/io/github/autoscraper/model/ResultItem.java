package io.github.autoscraper.model;

/**
 * A single extracted value together with the document position it came from.
 *
 * <p>{@code index} is nullable because Python reads it with {@code getattr(i, "child_index", 0)}
 * against a bs4 tag, and bs4 resolves unknown attributes to {@code None} instead of raising, so the
 * {@code 0} default is unreachable. The position is only populated when a caller asks for ordered
 * or alias-grouped output, and only those paths sort on it.
 *
 * @param text  the extracted value, or {@code null} when the wanted attribute was absent
 * @param index the pre-order document position, or {@code null} when ordering was not requested
 */
public record ResultItem(String text, Integer index) {

    /**
     * Reports whether Python would keep this item when blanks are being dropped.
     *
     * @return {@code true} when the text is neither {@code null} nor empty
     */
    public boolean isTruthy() {
        return text != null && !text.isEmpty();
    }
}
