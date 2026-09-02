package io.github.autoscraper.soup;

/**
 * A node in a parsed document: either a {@link SoupElement} (a bs4 {@code Tag}) or a
 * {@link SoupText} (a bs4 {@code NavigableString} or {@code Comment}).
 *
 * <p>bs4 code distinguishes the two by testing {@code node.name}, which is {@code None} for
 * strings. This sealed hierarchy makes that test a pattern match instead.
 */
public sealed interface SoupNode permits SoupElement, SoupText {}
