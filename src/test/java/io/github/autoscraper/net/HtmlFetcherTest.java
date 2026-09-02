package io.github.autoscraper.net;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.autoscraper.AutoScraper;
import io.github.autoscraper.model.ScrapeResult;
import io.github.autoscraper.options.BuildOptions;
import io.github.autoscraper.options.ExactOptions;
import io.github.autoscraper.options.RequestArgs;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the download path against a loopback server.
 *
 * <p>The Python original delegates to {@code requests}, which decodes a response by consulting the
 * declared character set and otherwise guessing. Those choices decide what text the parser sees and
 * therefore which rules match, so they are verified here rather than assumed. No external network
 * is involved: every test starts its own server on an ephemeral loopback port.
 */
class HtmlFetcherTest {

    /** A one-shot server that records the request it received. */
    private static final class RecordingServer implements AutoCloseable {

        private final HttpServer server;
        private volatile Headers received;

        RecordingServer(byte[] body, String contentType) throws IOException {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/", exchange -> {
                received = exchange.getRequestHeaders();
                respond(exchange, body, contentType);
            });
            server.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        }

        String authority() {
            return "127.0.0.1:" + server.getAddress().getPort();
        }

        String requestHeader(String name) {
            return received == null ? null : received.getFirst(name);
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private static void respond(HttpExchange exchange, byte[] body, String contentType) throws IOException {
        if (contentType != null) {
            exchange.getResponseHeaders().set("Content-Type", contentType);
        }
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static byte[] bytes(String text, Charset charset) {
        return text.getBytes(charset);
    }

    @Test
    void sendsTheDefaultUserAgentAndDerivesTheHostHeader() throws IOException {
        try (RecordingServer server = new RecordingServer(bytes("<p>hi</p>", StandardCharsets.UTF_8), "text/html")) {
            String html = HtmlFetcher.fetchHtml(server.url(), null);

            assertThat(html).isEqualTo("<p>hi</p>");
            assertThat(server.requestHeader("User-Agent"))
                    .isEqualTo(HtmlFetcher.REQUEST_HEADERS.get("User-Agent"));
            assertThat(server.requestHeader("Host")).isEqualTo(server.authority());
        }
    }

    @Test
    void callerSuppliedHeadersAreSentAndConsumed() throws IOException {
        try (RecordingServer server = new RecordingServer(bytes("<p>hi</p>", StandardCharsets.UTF_8), "text/html")) {
            RequestArgs args = new RequestArgs().header("X-Token", "secret");

            HtmlFetcher.fetchHtml(server.url(), args);

            assertThat(server.requestHeader("X-Token")).isEqualTo("secret");
            assertThat(args.headers()).isEmpty();
        }
    }

    @Test
    void aDeclaredCharsetDecidesTheDecoding() throws IOException {
        byte[] latin1 = bytes("<p>caf\u00e9</p>", StandardCharsets.ISO_8859_1);
        try (RecordingServer server = new RecordingServer(latin1, "text/html; charset=ISO-8859-1")) {
            assertThat(HtmlFetcher.fetchHtml(server.url(), null)).isEqualTo("<p>caf\u00e9</p>");
        }
    }

    @Test
    void aQuotedCharsetParameterIsAccepted() throws IOException {
        byte[] latin1 = bytes("<p>caf\u00e9</p>", StandardCharsets.ISO_8859_1);
        try (RecordingServer server = new RecordingServer(latin1, "text/html; charset=\"ISO-8859-1\"")) {
            assertThat(HtmlFetcher.fetchHtml(server.url(), null)).isEqualTo("<p>caf\u00e9</p>");
        }
    }

    @Test
    void anUndeclaredCharsetIsSniffedRatherThanAssumedLatin1() throws IOException {
        String text = "<p>caf\u00e9 na\u00efve r\u00e9sum\u00e9 \u00e0 la cr\u00e8me br\u00fbl\u00e9e</p>";
        try (RecordingServer server = new RecordingServer(bytes(text, StandardCharsets.UTF_8), "text/html")) {
            assertThat(HtmlFetcher.fetchHtml(server.url(), null)).isEqualTo(text);
        }
    }

    @Test
    void anUnknownCharsetNameFallsBackToSniffing() throws IOException {
        String text = "<p>caf\u00e9 na\u00efve r\u00e9sum\u00e9 \u00e0 la cr\u00e8me br\u00fbl\u00e9e</p>";
        try (RecordingServer server =
                     new RecordingServer(bytes(text, StandardCharsets.UTF_8), "text/html; charset=definitely-not-real")) {
            assertThat(HtmlFetcher.fetchHtml(server.url(), null)).isEqualTo(text);
        }
    }

    @Test
    void jsonDefaultsToUtf8() throws IOException {
        String text = "{\"name\": \"caf\u00e9\"}";
        try (RecordingServer server = new RecordingServer(bytes(text, StandardCharsets.UTF_8), "application/json")) {
            assertThat(HtmlFetcher.fetchHtml(server.url(), null)).isEqualTo(text);
        }
    }

    @Test
    void redirectsAreFollowed() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/from", exchange -> {
            exchange.getResponseHeaders().set("Location", "/to");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/to", exchange ->
                respond(exchange, bytes("<p>arrived</p>", StandardCharsets.UTF_8), "text/html"));
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/from";
            assertThat(HtmlFetcher.fetchHtml(url, null)).isEqualTo("<p>arrived</p>");
        } finally {
            server.stop(0);
        }
    }

    /**
     * The exact wording for a missing URL is pinned by {@code ScenarioGoldenTest}, which reads it
     * from the Python-generated corpus instead of restating it. This test covers the path through
     * the fetcher, so it asserts the shape only and leaves the wording to the corpus.
     */
    @Test
    void aMissingUrlIsRejectedBeforeAnyRequestIsMade() {
        assertThatThrownBy(() -> HtmlFetcher.fetchHtml(null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("Invalid URL ")
                .hasMessageContaining("No scheme supplied");
    }

    @Test
    void aSchemelessUrlReportsThePythonMessage() {
        assertThatThrownBy(() -> HtmlFetcher.fetchHtml("example.com/page", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid URL 'example.com/page': No scheme supplied. "
                        + "Perhaps you meant https://example.com/page?");
    }

    @Test
    void aScraperCanLearnAndExtractOverHttp() throws IOException {
        String page = "<div><ul><li class=\"fruit\"><a href=\"/banana\">Banana</a></li>"
                + "<li class=\"fruit\"><a href=\"/apple\">Apple</a></li></ul></div>";
        try (RecordingServer server = new RecordingServer(bytes(page, StandardCharsets.UTF_8), "text/html")) {
            AutoScraper scraper = new AutoScraper();

            List<String> learned = scraper.build(BuildOptions.builder()
                    .url(server.url())
                    .wantedList("Banana")
                    .build());

            assertThat(learned).containsExactly("Banana");

            ScrapeResult again = scraper.getResultExact(ExactOptions.builder()
                    .url(server.url())
                    .build());

            assertThat(again.asList()).containsExactly("Banana");
        }
    }

    @Test
    void aLearnedLinkRuleResolvesAgainstTheFetchedUrl() throws IOException {
        String page = "<div><ul><li><a href=\"/banana\">Banana</a></li>"
                + "<li><a href=\"/apple\">Apple</a></li></ul></div>";
        try (RecordingServer server = new RecordingServer(bytes(page, StandardCharsets.UTF_8), "text/html")) {
            AutoScraper scraper = new AutoScraper();

            List<String> learned = scraper.build(BuildOptions.builder()
                    .url(server.url())
                    .wantedList(server.url() + "banana")
                    .build());

            assertThat(learned)
                    .containsExactly(server.url() + "banana", server.url() + "apple");

            ScrapeResult exact = scraper.getResultExact(ExactOptions.builder()
                    .url(server.url())
                    .build());

            assertThat(exact.asList()).containsExactly(server.url() + "banana");
        }
    }
}
