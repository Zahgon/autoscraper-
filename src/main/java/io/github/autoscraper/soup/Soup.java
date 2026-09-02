package io.github.autoscraper.soup;

import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Comment;
import org.jsoup.nodes.DataNode;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.Range;
import org.jsoup.nodes.TextNode;
import org.jsoup.parser.Parser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses HTML into a tree that matches {@code BeautifulSoup(html, "lxml")}.
 *
 * <p>jsoup is used as the underlying parser, then the tree is adjusted in two ways so that it
 * agrees with lxml, because rule identities are hashes of the element stack and any extra or
 * missing node changes every hash:
 *
 * <ul>
 *   <li><b>Implied {@code <tbody>} is removed.</b> jsoup inserts one into every table; lxml only
 *       keeps a {@code <tbody>} that was written explicitly.</li>
 *   <li><b>An implied, empty {@code <head>} is removed.</b> jsoup always creates a head; lxml
 *       omits it unless it was written explicitly or has content.</li>
 * </ul>
 *
 * <p>Implied elements are told apart from written ones by enabling jsoup position tracking: an
 * element the parser synthesised has a zero-width start-tag source range, whereas even
 * {@code <head></head>} spans six characters.
 */
public final class Soup {

    /** Matches runs of non-whitespace, mirroring bs4's {@code nonwhitespace_re} used to split class-like attributes. */
    private static final Pattern NON_WHITESPACE = Pattern.compile("\\S+");

    /** Tags whose text is exempt from whitespace collapsing, from bs4's {@code DEFAULT_PRESERVE_WHITESPACE_TAGS}. */
    private static final Set<String> PRESERVE_WHITESPACE_TAGS = Set.of("pre", "textarea");

    /** Attributes bs4 splits into token lists for every tag, from {@code DEFAULT_CDATA_LIST_ATTRIBUTES["*"]}. */
    private static final Set<String> GLOBAL_LIST_ATTRIBUTES = Set.of("class", "accesskey", "dropzone");

    /** Per-tag additions to {@link #GLOBAL_LIST_ATTRIBUTES}, from bs4's {@code DEFAULT_CDATA_LIST_ATTRIBUTES}. */
    private static final Map<String, Set<String>> TAG_LIST_ATTRIBUTES = Map.of(
            "a", Set.of("rel", "rev"),
            "link", Set.of("rel", "rev"),
            "td", Set.of("headers"),
            "th", Set.of("headers"),
            "form", Set.of("accept-charset"),
            "object", Set.of("archive"),
            "area", Set.of("rel"),
            "icon", Set.of("sizes"),
            "iframe", Set.of("sandbox"),
            "output", Set.of("for"));

    private Soup() {
    }

    /**
     * Parses a document.
     *
     * @param html the markup, already unescaped and NFKD-normalized by the caller
     * @return the {@code [document]} root
     */
    public static SoupElement parse(String html) {
        Objects.requireNonNull(html, "html");
        Parser parser = Parser.htmlParser().setTrackPosition(true);
        Document document = parser.parseInput(html, "");
        SoupElement root = new SoupElement(SoupElement.DOCUMENT_NAME, Map.of());
        convertChildren(document, root, false);
        return root;
    }

    private static void convertChildren(Node jsoupParent, SoupElement target, boolean preserveWhitespace) {
        for (Node child : jsoupParent.childNodes()) {
            if (child instanceof Element element) {
                boolean preserveInside = preserveWhitespace
                        || PRESERVE_WHITESPACE_TAGS.contains(element.normalName());
                if (isImplied(element)) {
                    convertChildren(element, target, preserveInside);
                } else {
                    SoupElement converted = new SoupElement(element.normalName(), convertAttributes(element));
                    target.appendChild(converted);
                    convertChildren(element, converted, preserveInside);
                }
            } else if (child instanceof Comment comment) {
                target.appendChild(new SoupText(comment.getData(), StringKind.COMMENT));
            } else if (child instanceof TextNode text) {
                appendText(target, text.getWholeText(), preserveWhitespace);
            } else if (child instanceof DataNode data) {
                appendText(target, data.getWholeData(), preserveWhitespace);
            }
            // Doctype, XML declarations and CDATA sections carry no text autoscraper can match
            // on, and lxml does not expose them as tags, so they are dropped.
        }
    }

    private static void appendText(SoupElement target, String text, boolean preserveWhitespace) {
        target.appendChild(new SoupText(
                normalizeText(text, preserveWhitespace), StringKind.interestingFor(target.name())));
    }

    /**
     * Applies libxml2's text-node handling, which jsoup does not perform.
     *
     * <p>Two adjustments are needed. Carriage returns are folded into newlines everywhere. Then a
     * text node made up <em>entirely</em> of XML whitespace is collapsed to a single character:
     * a newline if the run contained one, otherwise a space. Mixed text is never touched, so
     * {@code "a  b"} and {@code "&#92;n   hello  &#92;n"} survive intact.
     *
     * <p>The collapse is suppressed inside {@code <pre>} and {@code <textarea>}, and that applies
     * to any depth: {@code <pre><span>   </span></pre>} keeps all three spaces.
     *
     * @param text               the raw character data from jsoup
     * @param preserveWhitespace {@code true} when inside a whitespace-preserving tag
     * @return the text as lxml would report it
     */
    private static String normalizeText(String text, boolean preserveWhitespace) {
        String folded = text.indexOf('\r') < 0 ? text : text.replace("\r\n", "\n").replace('\r', '\n');
        if (preserveWhitespace || folded.isEmpty()) {
            return folded;
        }
        boolean sawNewline = false;
        for (int i = 0; i < folded.length(); i++) {
            char c = folded.charAt(i);
            if (c == '\n') {
                sawNewline = true;
            } else if (c != ' ' && c != '\t' && c != '\f') {
                return folded;
            }
        }
        return sawNewline ? "\n" : " ";
    }

    /**
     * Reports whether jsoup synthesised this element rather than reading it from the source.
     *
     * @param element the jsoup element
     * @return {@code true} when lxml would not have produced the element
     */
    private static boolean isImplied(Element element) {
        Range range = element.sourceRange();
        boolean synthesised = range.isTracked() && range.start().pos() == range.end().pos();
        if (!synthesised) {
            return false;
        }
        return switch (element.normalName()) {
            case "tbody" -> true;
            case "head" -> element.childNodeSize() == 0;
            default -> false;
        };
    }

    private static Map<String, AttrValue> convertAttributes(Element element) {
        Map<String, AttrValue> out = new LinkedHashMap<>();
        Set<String> listAttributes = TAG_LIST_ATTRIBUTES.get(element.normalName());
        for (Attribute attribute : element.attributes()) {
            String key = attribute.getKey();
            boolean multiValued = GLOBAL_LIST_ATTRIBUTES.contains(key)
                    || (listAttributes != null && listAttributes.contains(key));
            out.put(key, multiValued ? new AttrValue.Tokens(splitTokens(attribute.getValue()))
                    : new AttrValue.Text(attribute.getValue()));
        }
        return out;
    }

    /**
     * Splits a class-like attribute the way bs4 does.
     *
     * @param value the raw attribute value
     * @return the non-whitespace runs; empty for a blank value, which is why {@code class=""}
     *         becomes an empty list rather than a single empty token
     */
    private static List<String> splitTokens(String value) {
        List<String> tokens = new ArrayList<>();
        Matcher matcher = NON_WHITESPACE.matcher(value);
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        return tokens;
    }
}
