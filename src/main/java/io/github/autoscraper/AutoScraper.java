package io.github.autoscraper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.autoscraper.internal.ResultExtractor;
import io.github.autoscraper.internal.StackBuilder;
import io.github.autoscraper.match.TextTarget;
import io.github.autoscraper.model.ResultItem;
import io.github.autoscraper.model.Rule;
import io.github.autoscraper.model.ScrapeResult;
import io.github.autoscraper.net.HtmlFetcher;
import io.github.autoscraper.options.BuildOptions;
import io.github.autoscraper.options.ExactOptions;
import io.github.autoscraper.options.RequestArgs;
import io.github.autoscraper.options.ResultOptions;
import io.github.autoscraper.options.SimilarOptions;
import io.github.autoscraper.soup.Soup;
import io.github.autoscraper.soup.SoupElement;
import io.github.autoscraper.util.PyUrl;
import io.github.autoscraper.util.TextUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Learns how to find data on a page from examples, then finds the same data on similar pages.
 *
 * <p>Call {@link #build(BuildOptions)} with a sample page and the values you want from it. The
 * scraper records the path to each value as a rule; afterwards
 * {@link #getResultSimilar(SimilarOptions)} and {@link #getResultExact(ExactOptions)} replay those
 * rules against new pages.
 *
 * <p>The two retrieval modes differ in how strictly they follow the recorded path. "Similar"
 * explores every branch that fits and so picks up the whole set a value belonged to; "exact"
 * follows one branch by position and returns just that value. Rules can be inspected, grouped,
 * named and pruned, which is how you narrow a noisy first attempt down to what you meant.
 *
 * <p>Instances are not thread-safe.
 */
public final class AutoScraper {

    /**
     * The request headers used for every fetch. Shared and mutable, matching the Python original.
     */
    public static final Map<String, String> REQUEST_HEADERS = HtmlFetcher.REQUEST_HEADERS;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private List<Rule> stackList;

    /**
     * Creates a scraper with no rules.
     */
    public AutoScraper() {
        this.stackList = new ArrayList<>();
    }

    /**
     * Creates a scraper preloaded with rules.
     *
     * @param stackList the rules, or {@code null} for none
     */
    public AutoScraper(List<Rule> stackList) {
        this.stackList = stackList == null ? new ArrayList<>() : new ArrayList<>(stackList);
    }

    /**
     * Returns the learned rules, in order.
     *
     * @return a live view of the rules
     */
    public List<Rule> getStackList() {
        return stackList;
    }

    /**
     * Replaces the learned rules.
     *
     * @param stackList the rules, or {@code null} for none
     */
    public void setStackList(List<Rule> stackList) {
        this.stackList = stackList == null ? new ArrayList<>() : new ArrayList<>(stackList);
    }

    // ---------------------------------------------------------------- persistence

    /**
     * Writes the learned rules to a file.
     *
     * @param filePath the destination
     * @throws UncheckedIOException when writing fails
     */
    public void save(Path filePath) {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode rules = root.putArray("stack_list");
        for (Rule rule : stackList) {
            rules.add(rule.toJson(JsonNodeFactory.instance));
        }
        try {
            Files.writeString(filePath, MAPPER.writeValueAsString(root));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to save rules to " + filePath, e);
        }
    }

    /**
     * Reads rules from a file, replacing any currently loaded.
     *
     * <p>Accepts both the current object form and the bare array written by older versions.
     *
     * @param filePath the source
     * @throws UncheckedIOException when reading fails
     */
    public void load(Path filePath) {
        JsonNode data;
        try {
            data = MAPPER.readTree(Files.readString(filePath));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load rules from " + filePath, e);
        }
        JsonNode rules = data.isArray() ? data : data.get("stack_list");
        List<Rule> loaded = new ArrayList<>();
        for (JsonNode node : rules) {
            loaded.add(Rule.fromJson(node));
        }
        this.stackList = loaded;
    }

    // ---------------------------------------------------------------- parsing

    private static SoupElement getSoup(String url, String html, RequestArgs requestArgs) {
        String source = html == null || html.isEmpty() ? HtmlFetcher.fetchHtml(url, requestArgs) : html;
        return Soup.parse(TextUtils.normalize(PyUrl.unescape(source)));
    }

    // ---------------------------------------------------------------- learning

    /**
     * Learns rules from a sample page.
     *
     * @param options the sample page and the values wanted from it
     * @return the values the learned rules find on that same page
     * @throws IllegalArgumentException when no wanted values were supplied
     */
    public List<String> build(BuildOptions options) {
        Map<String, List<TextTarget>> wantedDict = resolveTargets(options);
        SoupElement soup = getSoup(options.url(), options.html(), options.requestArgs());

        if (!options.update()) {
            stackList = new ArrayList<>();
        }

        List<ResultItem> resultList = new ArrayList<>();
        wantedDict.forEach((alias, targets) -> {
            for (TextTarget target : targets) {
                TextTarget wanted = normalizeTarget(target);
                for (SoupElement child :
                        StackBuilder.findCarriers(soup, wanted, options.url(), options.textFuzzRatio())) {
                    Rule rule = StackBuilder.buildStack(child, options.url());
                    resultList.addAll(
                            ResultExtractor.withStack(rule, soup, options.url(), 1.0, false, false));
                    rule.setAlias(alias);
                    stackList.add(rule);
                }
            }
        });

        stackList = uniqueByHash(stackList);

        List<String> texts = new ArrayList<>();
        for (ResultItem item : resultList) {
            texts.add(item.text());
        }
        return TextUtils.uniqueHashable(texts);
    }

    /**
     * Applies the alias-to-targets mapping Python derives, where a plain wanted list wins over an
     * aliased mapping and is filed under the empty alias.
     *
     * @param options the build options
     * @return the targets to learn, keyed by alias
     * @throws IllegalArgumentException when nothing was supplied
     */
    private static Map<String, List<TextTarget>> resolveTargets(BuildOptions options) {
        List<TextTarget> wantedList = options.wantedList();
        Map<String, List<TextTarget>> wantedDict = options.wantedDict();

        boolean dictHasValues = wantedDict != null
                && wantedDict.values().stream().anyMatch(v -> v != null && !v.isEmpty());
        if ((wantedList == null || wantedList.isEmpty()) && !dictHasValues) {
            throw new IllegalArgumentException("No targets were supplied");
        }
        if (wantedList != null && !wantedList.isEmpty()) {
            Map<String, List<TextTarget>> single = new LinkedHashMap<>();
            single.put("", wantedList);
            return single;
        }
        return wantedDict;
    }

    private static TextTarget normalizeTarget(TextTarget target) {
        return target instanceof TextTarget.Literal literal
                ? new TextTarget.Literal(TextUtils.normalize(literal.value()))
                : target;
    }

    private static List<Rule> uniqueByHash(List<Rule> rules) {
        Map<String, Rule> seen = new LinkedHashMap<>();
        for (Rule rule : rules) {
            seen.putIfAbsent(rule.hash(), rule);
        }
        return new ArrayList<>(seen.values());
    }

    // ---------------------------------------------------------------- retrieval

    /**
     * Finds every value each rule can reach, generalising beyond the exact position learned.
     *
     * @param options the page and retrieval settings
     * @return the values, flat or grouped as requested
     */
    public ScrapeResult getResultSimilar(SimilarOptions options) {
        return getResultByFunc(
                (rule, soup, url) -> ResultExtractor.withStack(rule, soup, url, options.attrFuzzRatio(),
                        options.keepBlank(), options.containSiblingLeaves()),
                options.url(), options.html(), options.soup(), options.requestArgs(),
                options.grouped(), options.groupByAlias(), options.unique(), options.keepOrder());
    }

    /**
     * Finds the single value at the exact position each rule learned.
     *
     * @param options the page and retrieval settings
     * @return the values, flat or grouped as requested
     */
    public ScrapeResult getResultExact(ExactOptions options) {
        return getResultByFunc(
                (rule, soup, url) -> ResultExtractor.indexBased(rule, soup, url, options.attrFuzzRatio(),
                        options.keepBlank()),
                options.url(), options.html(), options.soup(), options.requestArgs(),
                options.grouped(), options.groupByAlias(), options.unique(), false);
    }

    /**
     * Runs both retrieval modes over a single parse of the page.
     *
     * @param options the page and retrieval settings
     * @return the similar results and the exact results
     */
    public ResultPair getResult(ResultOptions options) {
        SoupElement soup = getSoup(options.url(), options.html(), options.requestArgs());
        return new ResultPair(
                getResultSimilar(options.toSimilar(soup)),
                getResultExact(options.toExact(soup)));
    }

    /**
     * The output of {@link #getResult(ResultOptions)}.
     *
     * @param similar the generalising results
     * @param exact   the position-faithful results
     */
    public record ResultPair(ScrapeResult similar, ScrapeResult exact) {
    }

    /**
     * One retrieval strategy.
     *
     * <p>The soup is a parameter rather than a captured value because the caller may leave it unset,
     * in which case the driver parses the page and must hand the strategy what it parsed.
     */
    @FunctionalInterface
    private interface Extractor {
        List<ResultItem> apply(Rule rule, SoupElement soup, String url);
    }

    /**
     * Drives one retrieval pass over every rule.
     *
     * <p>The {@code url} parameter is deliberately reassigned from the first rule that carries one
     * and then reused for all later rules, reproducing the original's cross-rule carry-over.
     */
    private ScrapeResult getResultByFunc(Extractor func,
                                         String url, String html, SoupElement providedSoup,
                                         RequestArgs requestArgs, boolean grouped, boolean groupByAlias,
                                         Boolean unique, boolean keepOrder) {
        SoupElement soup = providedSoup == null ? getSoup(url, html, requestArgs) : providedSoup;

        if (groupByAlias || (keepOrder && !grouped)) {
            List<SoupElement> descendants = soup.findChildren();
            for (int index = 0; index < descendants.size(); index++) {
                descendants.get(index).setChildIndex(index);
            }
        }

        List<ResultItem> resultList = new ArrayList<>();
        Map<String, List<ResultItem>> groupedResult = new LinkedHashMap<>();

        String effectiveUrl = url;
        for (Rule rule : stackList) {
            if (effectiveUrl == null || effectiveUrl.isEmpty()) {
                effectiveUrl = rule.url() == null ? "" : rule.url();
            }
            List<ResultItem> result = func.apply(rule, soup, effectiveUrl);
            if (!grouped && !groupByAlias) {
                resultList.addAll(result);
                continue;
            }
            String groupId = groupByAlias ? rule.aliasOrEmpty() : rule.stackId();
            groupedResult.computeIfAbsent(groupId, key -> new ArrayList<>()).addAll(result);
        }

        return ResultExtractor.cleanResult(
                resultList, groupedResult, grouped, groupByAlias, unique, keepOrder);
    }

    // ---------------------------------------------------------------- rule management

    /**
     * Drops the named rules.
     *
     * @param rules the rule ids to drop
     */
    public void removeRules(Collection<String> rules) {
        Set<String> drop = new HashSet<>(rules);
        stackList.removeIf(rule -> drop.contains(rule.stackId()));
    }

    /**
     * Drops every rule except the named ones.
     *
     * @param rules the rule ids to keep
     */
    public void keepRules(Collection<String> rules) {
        Set<String> keep = new HashSet<>(rules);
        stackList.removeIf(rule -> !keep.contains(rule.stackId()));
    }

    /**
     * Names rules so results can be grouped meaningfully.
     *
     * @param aliases rule id to alias
     * @throws IllegalArgumentException when a rule id is unknown
     */
    public void setRuleAliases(Map<String, String> aliases) {
        Map<String, Rule> byId = new LinkedHashMap<>();
        for (Rule rule : stackList) {
            byId.put(rule.stackId(), rule);
        }
        aliases.forEach((ruleId, alias) -> {
            Rule rule = byId.get(ruleId);
            if (rule == null) {
                throw new IllegalArgumentException(ruleId);
            }
            rule.setAlias(alias);
        });
    }

    /**
     * Returns the distinct aliases currently assigned, in rule order.
     *
     * @return the aliases
     */
    public List<String> getRuleAliases() {
        Set<String> aliases = new LinkedHashSet<>();
        for (Rule rule : stackList) {
            aliases.add(rule.aliasOrEmpty());
        }
        return new ArrayList<>(aliases);
    }

    /**
     * Reports that code generation is gone.
     *
     * @deprecated superseded by {@link #save(Path)} and {@link #load(Path)}.
     */
    @Deprecated
    public void generatePythonCode() {
        System.out.println("This function is deprecated. Please use save() and load() instead.");
    }
}
