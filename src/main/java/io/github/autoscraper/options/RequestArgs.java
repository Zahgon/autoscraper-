package io.github.autoscraper.options;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Extra parameters forwarded to the HTTP request, the typed form of Python's {@code request_args}.
 *
 * <p>This class is intentionally mutable. Python does {@code request_args.pop("headers", {})},
 * which removes the entry from the caller's own dictionary, so reusing one {@code request_args}
 * across two calls silently drops the custom headers on the second. {@link #takeHeaders()}
 * reproduces that, and {@link #copy()} exists for callers who want to opt out.
 */
public final class RequestArgs {

    private final Map<String, String> headers = new LinkedHashMap<>();
    private Duration timeout;

    /** Creates an empty set of request parameters. */
    public RequestArgs() {
    }

    /**
     * Adds one request header.
     *
     * @param name  the header name
     * @param value the header value
     * @return this instance, for chaining
     */
    public RequestArgs header(String name, String value) {
        headers.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(value, "value"));
        return this;
    }

    /**
     * Sets the request timeout.
     *
     * @param timeout the timeout, or {@code null} for none
     * @return this instance, for chaining
     */
    public RequestArgs timeout(Duration timeout) {
        this.timeout = timeout;
        return this;
    }

    /** @return the configured timeout, or {@code null} */
    public Duration timeout() {
        return timeout;
    }

    /** @return an unmodifiable view of the headers without consuming them */
    public Map<String, String> headers() {
        return Map.copyOf(headers);
    }

    /**
     * Removes and returns the headers, reproducing Python's destructive {@code pop}.
     *
     * @return the headers that were configured
     */
    public Map<String, String> takeHeaders() {
        Map<String, String> taken = Map.copyOf(headers);
        headers.clear();
        return taken;
    }

    /**
     * Creates an independent copy, so a reused instance is not drained by {@link #takeHeaders()}.
     *
     * @return a detached copy
     */
    public RequestArgs copy() {
        RequestArgs copy = new RequestArgs();
        copy.headers.putAll(headers);
        copy.timeout = timeout;
        return copy;
    }
}
