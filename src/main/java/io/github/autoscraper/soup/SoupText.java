package io.github.autoscraper.soup;

import java.util.Objects;

/**
 * A text node, carrying the bs4 subclass it would have been wrapped in.
 *
 * <p>For a comment, {@code text} is the comment body <em>without</em> the {@code <!--} and
 * {@code -->} delimiters, matching {@code str(Comment(' note '))} in bs4.
 *
 * @param text the raw, unnormalized character data
 * @param kind the bs4 string subclass, which decides {@code get_text()} visibility
 */
public record SoupText(String text, StringKind kind) implements SoupNode {
    public SoupText {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(kind, "kind");
    }
}
