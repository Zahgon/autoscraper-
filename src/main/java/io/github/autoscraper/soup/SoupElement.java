package io.github.autoscraper.soup;

import io.github.autoscraper.match.AttrMatcher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A parsed element, equivalent to a BeautifulSoup {@code Tag}.
 *
 * <p>The document root is itself a {@code SoupElement} named {@code [document]} with a
 * {@code null} parent, matching bs4 where the {@code BeautifulSoup} object is a {@code Tag}
 * subclass. Several autoscraper algorithms depend on that: {@code _child_has_text} guards on
 * {@code child.parent.parent}, and the root's own parent being absent is what lets {@code <html>}
 * pass that guard.
 */
public final class SoupElement implements SoupNode {

    /** The name bs4 gives the root of a parsed document. */
    public static final String DOCUMENT_NAME = "[document]";

    private final String name;
    private final Map<String, AttrValue> attributes;
    private final List<SoupNode> children = new ArrayList<>();
    private SoupElement parent;

    private String wantedAttr;
    private Boolean fullUrl;
    private Boolean nonRecText;
    private Integer childIndex;

    /**
     * Creates a detached element.
     *
     * @param name       lowercase tag name, or {@link #DOCUMENT_NAME} for a document root
     * @param attributes parsed attributes in document order
     */
    public SoupElement(String name, Map<String, AttrValue> attributes) {
        this.name = Objects.requireNonNull(name, "name");
        this.attributes = new LinkedHashMap<>(Objects.requireNonNull(attributes, "attributes"));
    }

    /**
     * Appends a child and, for elements, records this node as its parent.
     *
     * @param child the node to append
     */
    void appendChild(SoupNode child) {
        children.add(Objects.requireNonNull(child, "child"));
        if (child instanceof SoupElement element) {
            element.parent = this;
        }
    }

    /** @return the lowercase tag name */
    public String name() {
        return name;
    }

    /** @return the parent element, or {@code null} for the document root */
    public SoupElement findParent() {
        return parent;
    }

    /** @return an unmodifiable view of the attributes, in document order */
    public Map<String, AttrValue> attributes() {
        return Collections.unmodifiableMap(attributes);
    }

    /**
     * Looks up one attribute.
     *
     * @param attribute the attribute name
     * @return the value, or {@code null} when absent
     */
    public AttrValue attr(String attribute) {
        return attributes.get(attribute);
    }

    /** @return an unmodifiable view of the direct children, text nodes included */
    public List<SoupNode> children() {
        return Collections.unmodifiableList(children);
    }

    /**
     * Returns every descendant element in document (pre-order) order, equivalent to bs4's
     * {@code findChildren()}.
     *
     * <p>The ordering is load-bearing: {@code _child_has_text} scans this list <em>reversed</em>
     * to find the deepest matching node first.
     *
     * @return descendants, excluding this element itself
     */
    public List<SoupElement> findChildren() {
        List<SoupElement> out = new ArrayList<>();
        collectDescendants(this, out);
        return out;
    }

    private static void collectDescendants(SoupElement element, List<SoupElement> out) {
        for (SoupNode child : element.children) {
            if (child instanceof SoupElement childElement) {
                out.add(childElement);
                collectDescendants(childElement, out);
            }
        }
    }

    /**
     * Finds matching elements, equivalent to {@code find_all(name, attrs, recursive=...)}.
     *
     * @param name      required tag name, or {@code null} to accept any element
     * @param attrs     attribute filters that must all match; may be empty
     * @param recursive {@code true} to search all descendants, {@code false} for direct children
     * @return matches in document order
     */
    public List<SoupElement> findAll(String name, Map<String, AttrMatcher> attrs, boolean recursive) {
        Objects.requireNonNull(attrs, "attrs");
        List<SoupElement> candidates = recursive ? findChildren() : directChildElements();
        List<SoupElement> out = new ArrayList<>();
        for (SoupElement candidate : candidates) {
            if (candidate.matchesQuery(name, attrs)) {
                out.add(candidate);
            }
        }
        return out;
    }

    /**
     * Returns the direct child elements, equivalent to {@code findChildren(recursive=False)}.
     *
     * @return direct child elements in document order
     */
    public List<SoupElement> directChildElements() {
        List<SoupElement> out = new ArrayList<>();
        for (SoupNode child : children) {
            if (child instanceof SoupElement childElement) {
                out.add(childElement);
            }
        }
        return out;
    }

    private boolean matchesQuery(String wantedName, Map<String, AttrMatcher> attrs) {
        if (wantedName != null && !wantedName.equals(name)) {
            return false;
        }
        for (Map.Entry<String, AttrMatcher> entry : attrs.entrySet()) {
            if (!entry.getValue().matches(attributes.get(entry.getKey()))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Concatenates the visible descendant text, equivalent to bs4's {@code get_text()}.
     *
     * <p>Only text nodes whose {@link StringKind} equals this element's own interesting kind are
     * included, so comments, and {@code <script>}/{@code <style>} bodies seen from an ancestor,
     * are skipped. No separator is inserted and no whitespace is collapsed.
     *
     * @return the concatenated text
     */
    public String getText() {
        StringBuilder sb = new StringBuilder();
        collectText(this, StringKind.interestingFor(name), sb);
        return sb.toString();
    }

    private static void collectText(SoupElement element, StringKind interesting, StringBuilder sb) {
        for (SoupNode child : element.children) {
            if (child instanceof SoupText text) {
                if (text.kind() == interesting) {
                    sb.append(text.text());
                }
            } else if (child instanceof SoupElement childElement) {
                collectText(childElement, interesting, sb);
            }
        }
    }

    /**
     * Concatenates the direct text children without stripping, equivalent to
     * {@code ''.join(element.find_all(text=True, recursive=False))}.
     *
     * <p>Unlike {@link #getText()} this includes comment bodies, because {@code find_all(text=True)}
     * filters by position rather than by string subclass.
     *
     * @return the concatenated direct text
     */
    public String directText() {
        StringBuilder sb = new StringBuilder();
        for (SoupNode child : children) {
            if (child instanceof SoupText text) {
                sb.append(text.text());
            }
        }
        return sb.toString();
    }

    /**
     * Compares two nodes the way bs4's {@code Tag.__eq__} does: same name, equal attribute maps,
     * and recursively equal child lists (text nodes included).
     *
     * <p>This is deliberately <em>not</em> {@link #equals(Object)}. {@code _build_stack} locates a
     * node among its siblings with {@code if c == parent}, so two siblings with identical markup
     * both resolve to the <em>first</em> index. That collapse is what makes repeated elements hash
     * to a single rule, so identity comparison here would change every learned rule.
     *
     * @param other the node to compare against
     * @return {@code true} when the subtrees are structurally identical
     */
    public boolean pyEquals(SoupNode other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SoupElement otherElement)) {
            return false;
        }
        if (!name.equals(otherElement.name)
                || !attributes.equals(otherElement.attributes)
                || children.size() != otherElement.children.size()) {
            return false;
        }
        for (int i = 0; i < children.size(); i++) {
            if (!nodesEqual(children.get(i), otherElement.children.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean nodesEqual(SoupNode left, SoupNode right) {
        if (left instanceof SoupElement leftElement) {
            return leftElement.pyEquals(right);
        }
        if (right instanceof SoupElement) {
            return false;
        }
        return ((SoupText) left).text().equals(((SoupText) right).text());
    }

    /** @return the attribute name whose value was wanted, or {@code null} for element text */
    public String wantedAttr() {
        return wantedAttr;
    }

    /** @return {@link Boolean#TRUE} when the wanted value was a resolved absolute URL, else null */
    public Boolean isFullUrl() {
        return fullUrl;
    }

    /** @return {@link Boolean#TRUE} when only non-recursive text was wanted, else null */
    public Boolean isNonRecText() {
        return nonRecText;
    }

    /** @return the document-order position assigned for ordered results, or {@code null} */
    public Integer childIndex() {
        return childIndex;
    }

    /**
     * Records which attribute holds the wanted value; {@code null} means the element's text.
     *
     * @param attribute the attribute name, or {@code null}
     */
    public void setWantedAttr(String attribute) {
        this.wantedAttr = attribute;
    }

    /** Marks the wanted value as an absolute URL resolved against the page URL. */
    public void markFullUrl() {
        this.fullUrl = Boolean.TRUE;
    }

    /** Marks the wanted value as this element's non-recursive text. */
    public void markNonRecText() {
        this.nonRecText = Boolean.TRUE;
    }

    /**
     * Assigns the document-order position used to restore page order in results.
     *
     * @param index the pre-order index among all descendants
     */
    public void setChildIndex(int index) {
        this.childIndex = index;
    }

    @Override
    public String toString() {
        return "<" + name + ">";
    }
}
