package io.github.autoscraper.soup;

/**
 * The concrete type BeautifulSoup assigns to a text node.
 *
 * <p>bs4 does not store plain strings: it wraps every text node in a subclass of
 * {@code NavigableString} chosen from the <em>parent tag name</em>, via
 * {@code TreeBuilder.DEFAULT_STRING_CONTAINERS}. {@code Tag.get_text()} then keeps only
 * descendants whose type is exactly the caller's own {@code interesting_string_types}.
 * That is why {@code <div>x<script>SCR</script>y</div>.get_text()} is {@code "xy"} while
 * {@code script.get_text()} is {@code "SCR"}, and why comments never appear in
 * {@code get_text()} but do appear in {@code get_non_rec_text()}.
 */
public enum StringKind {
    /** bs4 {@code NavigableString}: the default for text under any ordinary tag. */
    NAVIGABLE_STRING,
    /** bs4 {@code Comment}. Never equal to any tag's interesting type, so always hidden from {@code get_text()}. */
    COMMENT,
    /** bs4 {@code Stylesheet}: text directly under {@code <style>}. */
    STYLESHEET,
    /** bs4 {@code Script}: text directly under {@code <script>}. */
    SCRIPT,
    /** bs4 {@code TemplateString}: text directly under {@code <template>}. */
    TEMPLATE_STRING,
    /** bs4 {@code RubyTextString}: text directly under {@code <rt>}. */
    RUBY_TEXT,
    /** bs4 {@code RubyParenthesisString}: text directly under {@code <rp>}. */
    RUBY_PARENTHESIS;

    /**
     * Returns the string kind that {@code tagName} both produces for its direct text children
     * and accepts in {@code get_text()}.
     *
     * @param tagName lowercase tag name
     * @return the matching kind, or {@link #NAVIGABLE_STRING} for ordinary tags
     */
    public static StringKind interestingFor(String tagName) {
        return switch (tagName) {
            case "style" -> STYLESHEET;
            case "script" -> SCRIPT;
            case "template" -> TEMPLATE_STRING;
            case "rt" -> RUBY_TEXT;
            case "rp" -> RUBY_PARENTHESIS;
            default -> NAVIGABLE_STRING;
        };
    }
}
