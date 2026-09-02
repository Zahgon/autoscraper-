package io.github.autoscraper.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.autoscraper.soup.AttrValue;
import io.github.autoscraper.util.PyRepr;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One learned extraction rule, the Java form of an entry in Python's {@code stack_list}.
 *
 * <p>The identity fields are derived, not supplied: {@link #hash()} is the SHA-256 of the Python
 * {@code repr} of the rule dict as it exists <em>before</em> the hash, id and alias are added, and
 * {@link #stackId()} is {@code rule_} plus the first eight hex characters. Reproducing that repr
 * byte for byte is what keeps rule ids identical to Python's.
 */
public final class Rule {

    private final List<StackContentNode> content;
    private final String wantedAttr;
    private final Boolean fullUrl;
    private final Boolean nonRecText;
    private final String url;
    private final String hash;
    private final String stackId;
    private String alias;

    private Rule(List<StackContentNode> content, String wantedAttr, Boolean fullUrl,
                 Boolean nonRecText, String url, String hash, String stackId, String alias) {
        this.content = Collections.unmodifiableList(new ArrayList<>(content));
        this.wantedAttr = wantedAttr;
        this.fullUrl = fullUrl;
        this.nonRecText = nonRecText;
        this.url = url;
        this.hash = hash;
        this.stackId = stackId;
        this.alias = alias;
    }

    /**
     * Derives a rule and its identity from a freshly built path.
     *
     * @param content    the root-to-target path
     * @param wantedAttr the attribute holding the value, or {@code null} for element text
     * @param fullUrl    {@link Boolean#TRUE} when the value is a resolved URL, else {@code null}
     * @param nonRecText {@link Boolean#TRUE} when only direct text is wanted, else {@code null}
     * @param url        the page the rule was learned from
     * @return a rule with computed hash and id
     */
    public static Rule create(List<StackContentNode> content, String wantedAttr, Boolean fullUrl,
                              Boolean nonRecText, String url) {
        String effectiveUrl = Boolean.TRUE.equals(fullUrl) ? url : "";
        Map<String, Object> hashed = new LinkedHashMap<>();
        List<Object> pyContent = new ArrayList<>();
        for (StackContentNode node : content) {
            pyContent.add(node.toPyObject());
        }
        hashed.put("content", pyContent);
        hashed.put("wanted_attr", wantedAttr);
        hashed.put("is_full_url", fullUrl);
        hashed.put("is_non_rec_text", nonRecText);
        hashed.put("url", effectiveUrl);
        String hash = PyRepr.sha256Hex(PyRepr.repr(hashed));
        return new Rule(content, wantedAttr, fullUrl, nonRecText, effectiveUrl, hash,
                "rule_" + hash.substring(0, 8), null);
    }

    /** @return the root-to-target path */
    public List<StackContentNode> content() {
        return content;
    }

    /** @return the attribute holding the value, or {@code null} when the element text is wanted */
    public String wantedAttr() {
        return wantedAttr;
    }

    /** @return {@link Boolean#TRUE} when the value is resolved against the page URL, else null */
    public Boolean isFullUrl() {
        return fullUrl;
    }

    /** @return {@link Boolean#TRUE} when only non-recursive text is wanted, else null */
    public Boolean isNonRecText() {
        return nonRecText;
    }

    /** @return the URL this rule was learned from, or an empty string when not URL-valued */
    public String url() {
        return url;
    }

    /** @return the SHA-256 of the rule's Python repr */
    public String hash() {
        return hash;
    }

    /** @return the public rule identifier, {@code rule_} plus eight hex characters */
    public String stackId() {
        return stackId;
    }

    /** @return the caller-assigned group name, or {@code null} when never set */
    public String alias() {
        return alias;
    }

    /**
     * Returns the alias used for grouping, mirroring Python's {@code stack.get("alias", "")}.
     *
     * @return the alias, or an empty string when unset
     */
    public String aliasOrEmpty() {
        return alias == null ? "" : alias;
    }

    /**
     * Assigns the group name. This happens after hashing, so it never affects the rule id.
     *
     * @param alias the group name
     */
    public void setAlias(String alias) {
        this.alias = alias;
    }

    /**
     * Serializes this rule in the exact key order Python's {@code json.dump} emits.
     *
     * @param nodes the node factory to allocate with
     * @return the JSON object form
     */
    public ObjectNode toJson(JsonNodeFactory nodes) {
        ObjectNode out = nodes.objectNode();
        ArrayNode contentNode = out.putArray("content");
        for (StackContentNode node : content) {
            ArrayNode entry = contentNode.addArray();
            entry.add(node.name());
            ObjectNode attrs = entry.addObject();
            for (Map.Entry<String, AttrValue> attr : node.attrs().entrySet()) {
                if (attr.getValue() instanceof AttrValue.Tokens tokens) {
                    ArrayNode values = attrs.putArray(attr.getKey());
                    tokens.values().forEach(values::add);
                } else {
                    attrs.put(attr.getKey(), ((AttrValue.Text) attr.getValue()).value());
                }
            }
            if (node.index() != null) {
                entry.add(node.index().intValue());
            }
        }
        putNullableText(out, "wanted_attr", wantedAttr);
        putNullableBoolean(out, "is_full_url", fullUrl);
        putNullableBoolean(out, "is_non_rec_text", nonRecText);
        out.put("url", url);
        out.put("hash", hash);
        out.put("stack_id", stackId);
        if (alias != null) {
            out.put("alias", alias);
        }
        return out;
    }

    private static void putNullableText(ObjectNode target, String field, String value) {
        if (value == null) {
            target.putNull(field);
        } else {
            target.put(field, value);
        }
    }

    private static void putNullableBoolean(ObjectNode target, String field, Boolean value) {
        if (value == null) {
            target.putNull(field);
        } else {
            target.put(field, value.booleanValue());
        }
    }

    /**
     * Reads a rule back, trusting the stored hash and id rather than recomputing them so that
     * files written by Python load unchanged.
     *
     * @param node the JSON object form
     * @return the deserialized rule
     */
    public static Rule fromJson(JsonNode node) {
        List<StackContentNode> content = new ArrayList<>();
        for (JsonNode entry : node.get("content")) {
            Map<String, AttrValue> attrs = new LinkedHashMap<>();
            JsonNode attrsNode = entry.get(1);
            attrsNode.fieldNames().forEachRemaining(field -> {
                JsonNode value = attrsNode.get(field);
                if (value.isArray()) {
                    List<String> tokens = new ArrayList<>();
                    value.forEach(item -> tokens.add(item.asText()));
                    attrs.put(field, new AttrValue.Tokens(tokens));
                } else {
                    attrs.put(field, new AttrValue.Text(value.asText()));
                }
            });
            String name = entry.get(0).asText();
            content.add(entry.size() > 2
                    ? StackContentNode.branch(name, attrs, entry.get(2).asInt())
                    : StackContentNode.leaf(name, attrs));
        }
        return new Rule(content,
                textOrNull(node.get("wanted_attr")),
                booleanOrNull(node.get("is_full_url")),
                booleanOrNull(node.get("is_non_rec_text")),
                node.hasNonNull("url") ? node.get("url").asText() : "",
                node.get("hash").asText(),
                node.get("stack_id").asText(),
                textOrNull(node.get("alias")));
    }

    private static String textOrNull(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }

    private static Boolean booleanOrNull(JsonNode node) {
        return node == null || node.isNull() ? null : Boolean.valueOf(node.asBoolean());
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Rule rule && hash.equals(rule.hash) && Objects.equals(alias, rule.alias);
    }

    @Override
    public int hashCode() {
        return Objects.hash(hash, alias);
    }

    @Override
    public String toString() {
        return stackId;
    }
}
