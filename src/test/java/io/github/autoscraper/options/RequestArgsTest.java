package io.github.autoscraper.options;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks the header-consuming behaviour that mirrors Python's {@code request_args.pop("headers")}.
 *
 * <p>The Python implementation pops the headers out of the caller's own dictionary, so a request
 * argument object reused across two calls supplies its headers only to the first. That is a real
 * observable behaviour rather than an accident of the port, so it is pinned here.
 */
class RequestArgsTest {

    @Test
    void headersAccumulateInInsertionOrder() {
        RequestArgs args = new RequestArgs().header("Accept", "text/html").header("X-Trace", "1");
        assertThat(args.headers()).containsExactlyInAnyOrderEntriesOf(
                Map.of("Accept", "text/html", "X-Trace", "1"));
    }

    @Test
    void readingHeadersDoesNotConsumeThem() {
        RequestArgs args = new RequestArgs().header("Accept", "text/html");
        assertThat(args.headers()).hasSize(1);
        assertThat(args.headers()).hasSize(1);
    }

    @Test
    void takingHeadersConsumesThem() {
        RequestArgs args = new RequestArgs().header("Accept", "text/html");
        assertThat(args.takeHeaders()).containsEntry("Accept", "text/html");
        assertThat(args.takeHeaders()).isEmpty();
        assertThat(args.headers()).isEmpty();
    }

    @Test
    void copyingProtectsTheOriginalFromBeingConsumed() {
        RequestArgs args = new RequestArgs().header("Accept", "text/html").timeout(Duration.ofSeconds(5));
        RequestArgs copy = args.copy();

        assertThat(copy.timeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(copy.takeHeaders()).containsEntry("Accept", "text/html");
        assertThat(copy.headers()).isEmpty();
        assertThat(args.headers()).containsEntry("Accept", "text/html");
    }

    @Test
    void timeoutIsUnsetByDefault() {
        assertThat(new RequestArgs().timeout()).isNull();
    }
}
