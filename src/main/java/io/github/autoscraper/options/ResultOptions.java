package io.github.autoscraper.options;

import io.github.autoscraper.soup.SoupElement;

/**
 * Arguments for {@code getResult}, which returns the similar and exact results together.
 *
 * <p>Python parses the page once and forwards only {@code url}, {@code soup}, {@code grouped},
 * {@code group_by_alias}, {@code unique} and {@code attr_fuzz_ratio} to the two extractors, so
 * {@code keepBlank}, {@code keepOrder} and {@code containSiblingLeaves} are unavailable here and
 * always take their defaults.
 */
public final class ResultOptions {

    private final String url;
    private final String html;
    private final RequestArgs requestArgs;
    private final boolean grouped;
    private final boolean groupByAlias;
    private final Boolean unique;
    private final double attrFuzzRatio;

    private ResultOptions(Builder builder) {
        this.url = builder.url;
        this.html = builder.html;
        this.requestArgs = builder.requestArgs;
        this.grouped = builder.grouped;
        this.groupByAlias = builder.groupByAlias;
        this.unique = builder.unique;
        this.attrFuzzRatio = builder.attrFuzzRatio;
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

    /** @return extra request parameters, or {@code null} */
    public RequestArgs requestArgs() {
        return requestArgs;
    }

    /**
     * Projects these arguments onto the similar extractor against an already parsed document.
     *
     * @param soup the parsed document
     * @return the equivalent similar-extraction arguments
     */
    public SimilarOptions toSimilar(SoupElement soup) {
        return SimilarOptions.builder()
                .url(url)
                .soup(soup)
                .grouped(grouped)
                .groupByAlias(groupByAlias)
                .unique(unique)
                .attrFuzzRatio(attrFuzzRatio)
                .build();
    }

    /**
     * Projects these arguments onto the exact extractor against an already parsed document.
     *
     * @param soup the parsed document
     * @return the equivalent exact-extraction arguments
     */
    public ExactOptions toExact(SoupElement soup) {
        return ExactOptions.builder()
                .url(url)
                .soup(soup)
                .grouped(grouped)
                .groupByAlias(groupByAlias)
                .unique(unique)
                .attrFuzzRatio(attrFuzzRatio)
                .build();
    }

    /** Mutable builder for {@link ResultOptions}. */
    public static final class Builder {

        private String url;
        private String html;
        private RequestArgs requestArgs;
        private boolean grouped;
        private boolean groupByAlias;
        private Boolean unique;
        private double attrFuzzRatio = 1.0;

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

        /** @return the immutable options */
        public ResultOptions build() {
            return new ResultOptions(this);
        }
    }
}
