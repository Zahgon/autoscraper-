package io.github.autoscraper.internal;

import io.github.autoscraper.match.AttrMatcher;
import io.github.autoscraper.match.FuzzyText;
import io.github.autoscraper.model.ResultItem;
import io.github.autoscraper.model.Rule;
import io.github.autoscraper.model.ScrapeResult;
import io.github.autoscraper.model.StackContentNode;
import io.github.autoscraper.soup.AttrValue;
import io.github.autoscraper.soup.SoupElement;
import io.github.autoscraper.util.PyUrl;
import io.github.autoscraper.util.TextUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Applies learned rules to a parsed page.
 *
 * <p>Two strategies exist. {@link #withStack} walks every branch of the recorded path and returns
 * all elements it reaches, which generalises to pages whose layout only resembles the original.
 * {@link #indexBased} follows a single branch by sibling index and returns at most one element,
 * which reproduces the original position exactly.
 */
public final class ResultExtractor {

    private ResultExtractor() {
    }

    private static Map<String, AttrMatcher> matchersFor(StackContentNode node, double attrFuzzRatio) {
        return attrFuzzRatio < 1.0
                ? fuzzyMatchers(node.attrs(), attrFuzzRatio)
                : StackBuilder.toMatchers(node.attrs());
    }

    /**
     * Relaxes identifying attributes into approximate filters.
     *
     * <p>Empty values stay exact, because Python's {@code if isinstance(val, str) and val} guard
     * leaves them untouched, and an empty filter is what matches an absent attribute.
     *
     * @param attrs         the identifying attributes
     * @param attrFuzzRatio the fuzziness threshold
     * @return approximate attribute filters
     */
    public static Map<String, AttrMatcher> fuzzyMatchers(Map<String, AttrValue> attrs, double attrFuzzRatio) {
        Map<String, AttrMatcher> out = new LinkedHashMap<>();
        attrs.forEach((key, value) -> {
            if (value instanceof AttrValue.Tokens tokens) {
                List<AttrMatcher> options = new ArrayList<>();
                for (String token : tokens.values()) {
                    options.add(token.isEmpty()
                            ? new AttrMatcher.Literal(token)
                            : new AttrMatcher.Fuzzy(new FuzzyText(token, attrFuzzRatio)));
                }
                out.put(key, new AttrMatcher.Any(options));
            } else {
                String text = ((AttrValue.Text) value).value();
                out.put(key, text.isEmpty()
                        ? new AttrMatcher.Literal(text)
                        : new AttrMatcher.Fuzzy(new FuzzyText(text, attrFuzzRatio)));
            }
        });
        return out;
    }

    /**
     * Reads the wanted value out of a located element.
     *
     * @param child      the located element
     * @param rule       the rule that located it
     * @param url        the page URL, used to resolve relative links
     * @return the value, or {@code null} when the wanted attribute is absent
     */
    public static String fetchValue(SoupElement child, Rule rule, String url) {
        String wantedAttr = rule.wantedAttr();
        if (wantedAttr == null) {
            return Boolean.TRUE.equals(rule.isNonRecText())
                    ? TextUtils.getNonRecText(child)
                    : TextUtils.strip(child.getText());
        }
        AttrValue value = child.attr(wantedAttr);
        if (value == null) {
            return null;
        }
        return Boolean.TRUE.equals(rule.isFullUrl())
                ? PyUrl.urljoin(url, value.joined())
                : value.joined();
    }

    /**
     * Collects every element the rule's path can reach.
     *
     * @param rule                 the rule to apply
     * @param soup                 the parsed document
     * @param url                  the page URL
     * @param attrFuzzRatio        the attribute fuzziness threshold
     * @param keepBlank            {@code true} to keep values that are absent or empty
     * @param containSiblingLeaves {@code true} to keep all final-step siblings rather than one
     * @return the extracted items
     */
    public static List<ResultItem> withStack(Rule rule, SoupElement soup, String url, double attrFuzzRatio,
                                             boolean keepBlank, boolean containSiblingLeaves) {
        List<StackContentNode> content = rule.content();
        List<SoupElement> parents = new ArrayList<>(List.of(soup));

        for (int index = 0; index < content.size(); index++) {
            StackContentNode item = content.get(index);
            if (SoupElement.DOCUMENT_NAME.equals(item.name())) {
                continue;
            }
            List<SoupElement> children = new ArrayList<>();
            for (SoupElement parent : parents) {
                List<SoupElement> found =
                        parent.findAll(item.name(), matchersFor(item, attrFuzzRatio), false);
                if (found.isEmpty()) {
                    continue;
                }
                if (!containSiblingLeaves && index == content.size() - 1) {
                    int wanted = siblingIndex(content, index);
                    found = List.of(found.get(Math.min(found.size() - 1, wanted)));
                }
                children.addAll(found);
            }
            parents = children;
        }

        return toItems(parents, rule, url, keepBlank);
    }

    /**
     * Reads the sibling index recorded on the step before {@code index}, reproducing Python's
     * negative list indexing when {@code index} is zero.
     *
     * @param content the recorded path
     * @param index   the position of the current step
     * @return the recorded sibling index
     * @throws IllegalStateException when the referenced step carries no index
     */
    private static int siblingIndex(List<StackContentNode> content, int index) {
        StackContentNode previous = content.get(index == 0 ? content.size() - 1 : index - 1);
        if (previous.index() == null) {
            throw new IllegalStateException(
                    "Rule path step carries no sibling index; the path does not start at the document root");
        }
        return previous.index();
    }

    /**
     * Follows the rule's path by sibling index, reaching at most one element.
     *
     * @param rule          the rule to apply
     * @param soup          the parsed document
     * @param url           the page URL
     * @param attrFuzzRatio the attribute fuzziness threshold
     * @param keepBlank     {@code true} to keep values that are absent or empty
     * @return the extracted item, or an empty list when the path breaks
     */
    public static List<ResultItem> indexBased(Rule rule, SoupElement soup, String url, double attrFuzzRatio,
                                              boolean keepBlank) {
        List<StackContentNode> content = rule.content();
        SoupElement current = soup.directChildElements().get(0);

        for (int index = 0; index < content.size() - 1; index++) {
            StackContentNode item = content.get(index);
            if (SoupElement.DOCUMENT_NAME.equals(item.name())) {
                continue;
            }
            StackContentNode next = content.get(index + 1);
            List<SoupElement> found =
                    current.findAll(next.name(), matchersFor(next, attrFuzzRatio), false);
            if (found.isEmpty()) {
                return List.of();
            }
            current = found.get(Math.min(found.size() - 1, item.index()));
        }

        return toItems(List.of(current), rule, url, keepBlank);
    }

    private static List<ResultItem> toItems(List<SoupElement> elements, Rule rule, String url, boolean keepBlank) {
        List<ResultItem> items = new ArrayList<>();
        for (SoupElement element : elements) {
            ResultItem item = new ResultItem(fetchValue(element, rule, url), element.childIndex());
            if (keepBlank || item.isTruthy()) {
                items.add(item);
            }
        }
        return items;
    }

    /**
     * Turns extracted items into the caller-visible result, applying ordering and deduplication.
     *
     * <p>The tri-state {@code unique} is why grouped and ungrouped results differ by default: an
     * unset flag becomes {@code true} on the ungrouped path only, leaving grouped values as-is.
     *
     * @param flat          items collected when not grouping
     * @param grouped       items collected per group when grouping
     * @param isGrouped     {@code true} when grouping by rule id
     * @param isByAlias     {@code true} when grouping by alias
     * @param unique        the tri-state deduplication flag
     * @param keepOrder     {@code true} to restore document order
     * @return the result in list or dict form
     */
    public static ScrapeResult cleanResult(List<ResultItem> flat, Map<String, List<ResultItem>> grouped,
                                           boolean isGrouped, boolean isByAlias, Boolean unique,
                                           boolean keepOrder) {
        if (!isGrouped && !isByAlias) {
            boolean deduplicate = unique == null || unique;
            List<ResultItem> ordered = new ArrayList<>(flat);
            if (keepOrder) {
                ordered.sort(Comparator.comparing(ResultItem::index));
            }
            return new ScrapeResult.Flat(texts(ordered, deduplicate));
        }

        Map<String, List<String>> out = new LinkedHashMap<>();
        grouped.forEach((key, items) -> {
            List<ResultItem> ordered = new ArrayList<>(items);
            if (isByAlias) {
                ordered.sort(Comparator.comparing(ResultItem::index));
            }
            out.put(key, texts(ordered, Boolean.TRUE.equals(unique)));
        });
        return new ScrapeResult.Grouped(out);
    }

    private static List<String> texts(List<ResultItem> items, boolean deduplicate) {
        List<String> values = new ArrayList<>();
        for (ResultItem item : items) {
            values.add(item.text());
        }
        return deduplicate ? TextUtils.uniqueHashable(values) : values;
    }
}
