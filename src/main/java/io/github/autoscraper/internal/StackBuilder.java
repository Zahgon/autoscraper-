package io.github.autoscraper.internal;

import io.github.autoscraper.match.AttrMatcher;
import io.github.autoscraper.match.TextTarget;
import io.github.autoscraper.model.Rule;
import io.github.autoscraper.model.StackContentNode;
import io.github.autoscraper.soup.AttrValue;
import io.github.autoscraper.soup.SoupElement;
import io.github.autoscraper.util.PyUrl;
import io.github.autoscraper.util.TextUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Learns rules from a parsed page: locates elements carrying a wanted value and records the path
 * from the document root to each of them.
 */
public final class StackBuilder {

    private static final Set<String> URL_ATTRIBUTES = Set.of("href", "src");

    /**
     * The attributes kept when identifying an element, and the order missing ones are appended in.
     *
     * <p>Python iterates the set literal {@code {"class", "style"}} here, so its fill order varies
     * with {@code PYTHONHASHSEED} and the resulting rule ids are not reproducible across
     * interpreter processes. This port fixes the order to the literal's source order, which matches
     * a Python run under {@code PYTHONHASHSEED=42}.
     */
    private static final List<String> KEY_ATTRIBUTES = List.of("class", "style");

    private StackBuilder() {
    }

    /**
     * Keeps only the identifying attributes, normalising an empty class list to an empty string and
     * adding any missing key with an empty string.
     *
     * @param element the element to describe
     * @return the identifying attributes, in document order followed by filled-in defaults
     */
    public static Map<String, AttrValue> validAttrs(SoupElement element) {
        Map<String, AttrValue> attrs = new LinkedHashMap<>();
        for (Map.Entry<String, AttrValue> entry : element.attributes().entrySet()) {
            if (!KEY_ATTRIBUTES.contains(entry.getKey())) {
                continue;
            }
            AttrValue value = entry.getValue();
            boolean emptyList = value instanceof AttrValue.Tokens tokens && tokens.values().isEmpty();
            attrs.put(entry.getKey(), emptyList ? new AttrValue.Text("") : value);
        }
        for (String key : KEY_ATTRIBUTES) {
            attrs.putIfAbsent(key, new AttrValue.Text(""));
        }
        return attrs;
    }

    /**
     * Converts identifying attributes into filters for {@code findAll}.
     *
     * @param attrs the identifying attributes
     * @return equivalent attribute filters
     */
    public static Map<String, AttrMatcher> toMatchers(Map<String, AttrValue> attrs) {
        Map<String, AttrMatcher> out = new LinkedHashMap<>();
        attrs.forEach((key, value) -> out.put(key, value instanceof AttrValue.Tokens tokens
                ? new AttrMatcher.Tokens(tokens.values())
                : new AttrMatcher.Literal(((AttrValue.Text) value).value())));
        return out;
    }

    /**
     * Decides whether an element carries the wanted value, recording on the element <em>how</em> it
     * carries it so {@code buildStack} can describe the rule.
     *
     * <p>The first branch rejects an element whose text is identical to its parent's, which is what
     * drives the search down to the innermost element actually holding the text. The guard is
     * skipped when the parent is the document root, since that root has no parent of its own.
     *
     * @param child          the candidate element
     * @param wanted         the value being searched for
     * @param url            the page URL, used to resolve relative links
     * @param textFuzzRatio  the fuzziness threshold
     * @return {@code true} when the element carries the wanted value
     */
    public static boolean childHasText(SoupElement child, TextTarget wanted, String url, double textFuzzRatio) {
        String childText = TextUtils.strip(child.getText());

        if (wanted.matches(childText, textFuzzRatio)) {
            SoupElement parent = child.findParent();
            String parentText = TextUtils.strip(parent.getText());
            if (childText.equals(parentText) && parent.findParent() != null) {
                return false;
            }
            child.setWantedAttr(null);
            return true;
        }

        if (wanted.matches(TextUtils.getNonRecText(child), textFuzzRatio)) {
            child.markNonRecText();
            child.setWantedAttr(null);
            return true;
        }

        for (Map.Entry<String, AttrValue> entry : child.attributes().entrySet()) {
            if (!(entry.getValue() instanceof AttrValue.Text text)) {
                continue;
            }
            String value = TextUtils.strip(text.value());
            if (wanted.matches(value, textFuzzRatio)) {
                child.setWantedAttr(entry.getKey());
                return true;
            }
            if (URL_ATTRIBUTES.contains(entry.getKey())
                    && wanted.matches(PyUrl.urljoin(url, value), textFuzzRatio)) {
                child.setWantedAttr(entry.getKey());
                child.markFullUrl();
                return true;
            }
        }

        return false;
    }

    /**
     * Finds every element carrying the wanted value, deepest first.
     *
     * <p>The reversed document order is load-bearing rather than cosmetic: it makes the innermost
     * matches appear first in the learned rule list, which determines rule order in the output.
     *
     * @param soup          the parsed document
     * @param wanted        the value being searched for
     * @param url           the page URL
     * @param textFuzzRatio the fuzziness threshold
     * @return matching elements in reverse document order
     */
    public static List<SoupElement> findCarriers(SoupElement soup, TextTarget wanted, String url,
                                                 double textFuzzRatio) {
        List<SoupElement> descendants = new ArrayList<>(soup.findChildren());
        Collections.reverse(descendants);
        List<SoupElement> out = new ArrayList<>();
        for (SoupElement candidate : descendants) {
            if (childHasText(candidate, wanted, url, textFuzzRatio)) {
                out.add(candidate);
            }
        }
        return out;
    }

    /**
     * Records the path from the document root down to an element.
     *
     * <p>Each ancestor step stores the index at which the child appears among the siblings matching
     * its name and identifying attributes. That lookup uses bs4's structural equality, so identical
     * siblings all resolve to the first index and therefore hash to a single rule.
     *
     * @param child the element carrying the wanted value
     * @param url   the page the rule is learned from
     * @return the learned rule, with hash and id derived
     */
    public static Rule buildStack(SoupElement child, String url) {
        List<StackContentNode> content = new ArrayList<>();
        content.add(StackContentNode.leaf(child.name(), validAttrs(child)));

        SoupElement parent = child;
        while (true) {
            SoupElement grandParent = parent.findParent();
            if (grandParent == null) {
                break;
            }
            List<SoupElement> siblings =
                    grandParent.findAll(parent.name(), toMatchers(validAttrs(parent)), false);
            for (int i = 0; i < siblings.size(); i++) {
                if (siblings.get(i).pyEquals(parent)) {
                    content.add(0, StackContentNode.branch(grandParent.name(), validAttrs(grandParent), i));
                    break;
                }
            }
            if (grandParent.findParent() == null) {
                break;
            }
            parent = grandParent;
        }

        return Rule.create(content, child.wantedAttr(), child.isFullUrl(), child.isNonRecText(), url);
    }
}
