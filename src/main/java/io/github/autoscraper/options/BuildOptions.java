package io.github.autoscraper.options;

import io.github.autoscraper.match.TextTarget;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Arguments for learning a set of rules, mirroring Python's {@code build()} keyword parameters.
 *
 * <p>All fields default to the Python defaults, so {@code BuildOptions.builder()} with only the
 * targets set behaves exactly like calling {@code build(wanted_list=...)}.
 */
public final class BuildOptions {

    private final String url;
    private final List<TextTarget> wantedList;
    private final Map<String, List<TextTarget>> wantedDict;
    private final String html;
    private final RequestArgs requestArgs;
    private final boolean update;
    private final double textFuzzRatio;

    private BuildOptions(Builder builder) {
        this.url = builder.url;
        this.wantedList = builder.wantedList;
        this.wantedDict = builder.wantedDict;
        this.html = builder.html;
        this.requestArgs = builder.requestArgs;
        this.update = builder.update;
        this.textFuzzRatio = builder.textFuzzRatio;
    }

    /** @return a builder holding the Python default values */
    public static Builder builder() {
        return new Builder();
    }

    /** @return the page URL, or {@code null} */
    public String url() {
        return url;
    }

    /** @return the wanted targets, or {@code null} when a dict was supplied instead */
    public List<TextTarget> wantedList() {
        return wantedList;
    }

    /** @return the aliased wanted targets, or {@code null} */
    public Map<String, List<TextTarget>> wantedDict() {
        return wantedDict;
    }

    /** @return the page HTML, or {@code null} */
    public String html() {
        return html;
    }

    /** @return extra request parameters, or {@code null} */
    public RequestArgs requestArgs() {
        return requestArgs;
    }

    /** @return {@code true} to add to the existing rules rather than replace them */
    public boolean update() {
        return update;
    }

    /** @return the fuzziness threshold for matching wanted text */
    public double textFuzzRatio() {
        return textFuzzRatio;
    }

    /** Mutable builder for {@link BuildOptions}. */
    public static final class Builder {

        private String url;
        private List<TextTarget> wantedList;
        private Map<String, List<TextTarget>> wantedDict;
        private String html;
        private RequestArgs requestArgs;
        private boolean update;
        private double textFuzzRatio = 1.0;

        /**
         * @param url the page URL
         * @return this builder
         */
        public Builder url(String url) {
            this.url = url;
            return this;
        }

        /**
         * Sets the wanted targets as literal strings.
         *
         * @param wanted the wanted texts
         * @return this builder
         */
        public Builder wantedList(String... wanted) {
            List<TextTarget> targets = new ArrayList<>();
            for (String item : wanted) {
                targets.add(new TextTarget.Literal(item));
            }
            this.wantedList = targets;
            return this;
        }

        /**
         * Sets the wanted targets.
         *
         * @param wanted the wanted targets
         * @return this builder
         */
        public Builder wantedTargets(List<TextTarget> wanted) {
            this.wantedList = wanted == null ? null : new ArrayList<>(wanted);
            return this;
        }

        /**
         * Adds one aliased group of wanted targets.
         *
         * @param alias  the group name
         * @param wanted the wanted targets
         * @return this builder
         */
        public Builder wanted(String alias, List<TextTarget> wanted) {
            if (wantedDict == null) {
                wantedDict = new LinkedHashMap<>();
            }
            wantedDict.put(alias, new ArrayList<>(wanted));
            return this;
        }

        /**
         * @param html the page HTML
         * @return this builder
         */
        public Builder html(String html) {
            this.html = html;
            return this;
        }

        /**
         * @param requestArgs extra request parameters
         * @return this builder
         */
        public Builder requestArgs(RequestArgs requestArgs) {
            this.requestArgs = requestArgs;
            return this;
        }

        /**
         * @param update {@code true} to keep previously learned rules
         * @return this builder
         */
        public Builder update(boolean update) {
            this.update = update;
            return this;
        }

        /**
         * @param textFuzzRatio the fuzziness threshold in {@code [0, 1]}
         * @return this builder
         */
        public Builder textFuzzRatio(double textFuzzRatio) {
            this.textFuzzRatio = textFuzzRatio;
            return this;
        }

        /** @return the immutable options */
        public BuildOptions build() {
            return new BuildOptions(this);
        }
    }
}
