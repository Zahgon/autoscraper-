package io.github.autoscraper.options;

import io.github.autoscraper.soup.SoupElement;

/**
 * Arguments for {@code getResultSimilar}, mirroring Python's keyword parameters and defaults.
 *
 * <p>{@link #unique()} is a nullable {@link Boolean} on purpose. Python distinguishes three states:
 * unset defaults to deduplicating ungrouped results but <em>not</em> grouped ones, while an
 * explicit {@code true} or {@code false} applies to both.
 */
public final class SimilarOptions {

    private final String url;
    private final String html;
    private final SoupElement soup;
    private final RequestArgs requestArgs;
    private final boolean grouped;
    private final boolean groupByAlias;
    private final Boolean unique;
    private final double attrFuzzRatio;
    private final boolean keepBlank;
    private final boolean keepOrder;
    private final boolean containSiblingLeaves;

    private SimilarOptions(Builder builder) {
        this.url = builder.url;
        this.html = builder.html;
        this.soup = builder.soup;
        this.requestArgs = builder.requestArgs;
        this.grouped = builder.grouped;
        this.groupByAlias = builder.groupByAlias;
        this.unique = builder.unique;
        this.attrFuzzRatio = builder.attrFuzzRatio;
        this.keepBlank = builder.keepBlank;
        this.keepOrder = builder.keepOrder;
        this.containSiblingLeaves = builder.containSiblingLeaves;
    }

    /** @return a builder holding the Python default values */
    public static Builder builder() {
        return new Builder();
    }

    /** @return the page URL, or {@code null} */
    public String url() {
        return url;
    }

    /** @return the page HTML, or {@code null} */
    public String html() {
        return html;
    }

    /** @return an already parsed document, or {@code null} */
    public SoupElement soup() {
        return soup;
    }

    /** @return extra request parameters, or {@code null} */
    public RequestArgs requestArgs() {
        return requestArgs;
    }

    /** @return {@code true} to key results by rule id */
    public boolean grouped() {
        return grouped;
    }

    /** @return {@code true} to key results by rule alias */
    public boolean groupByAlias() {
        return groupByAlias;
    }

    /** @return the tri-state deduplication flag, {@code null} when unset */
    public Boolean unique() {
        return unique;
    }

    /** @return the fuzziness threshold for matching attributes */
    public double attrFuzzRatio() {
        return attrFuzzRatio;
    }

    /** @return {@code true} to keep missing values as empty strings */
    public boolean keepBlank() {
        return keepBlank;
    }

    /** @return {@code true} to restore document order */
    public boolean keepOrder() {
        return keepOrder;
    }

    /** @return {@code true} to also return sibling leaves of the wanted elements */
    public boolean containSiblingLeaves() {
        return containSiblingLeaves;
    }

    /** Mutable builder for {@link SimilarOptions}. */
    public static final class Builder {

        private String url;
        private String html;
        private SoupElement soup;
        private RequestArgs requestArgs;
        private boolean grouped;
        private boolean groupByAlias;
        private Boolean unique;
        private double attrFuzzRatio = 1.0;
        private boolean keepBlank;
        private boolean keepOrder;
        private boolean containSiblingLeaves;

        /**
         * @param url the page URL
         * @return this builder
         */
        public Builder url(String url) {
            this.url = url;
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
         * @param soup an already parsed document
         * @return this builder
         */
        public Builder soup(SoupElement soup) {
            this.soup = soup;
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
         * @param grouped {@code true} to key results by rule id
         * @return this builder
         */
        public Builder grouped(boolean grouped) {
            this.grouped = grouped;
            return this;
        }

        /**
         * @param groupByAlias {@code true} to key results by alias
         * @return this builder
         */
        public Builder groupByAlias(boolean groupByAlias) {
            this.groupByAlias = groupByAlias;
            return this;
        }

        /**
         * @param unique the tri-state deduplication flag
         * @return this builder
         */
        public Builder unique(Boolean unique) {
            this.unique = unique;
            return this;
        }

        /**
         * @param attrFuzzRatio the fuzziness threshold in {@code [0, 1]}
         * @return this builder
         */
        public Builder attrFuzzRatio(double attrFuzzRatio) {
            this.attrFuzzRatio = attrFuzzRatio;
            return this;
        }

        /**
         * @param keepBlank {@code true} to keep missing values as empty strings
         * @return this builder
         */
        public Builder keepBlank(boolean keepBlank) {
            this.keepBlank = keepBlank;
            return this;
        }

        /**
         * @param keepOrder {@code true} to restore document order
         * @return this builder
         */
        public Builder keepOrder(boolean keepOrder) {
            this.keepOrder = keepOrder;
            return this;
        }

        /**
         * @param containSiblingLeaves {@code true} to also return sibling leaves
         * @return this builder
         */
        public Builder containSiblingLeaves(boolean containSiblingLeaves) {
            this.containSiblingLeaves = containSiblingLeaves;
            return this;
        }

        /** @return the immutable options */
        public SimilarOptions build() {
            return new SimilarOptions(this);
        }
    }
}
