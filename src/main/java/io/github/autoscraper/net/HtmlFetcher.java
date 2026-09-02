package io.github.autoscraper.net;

import io.github.autoscraper.options.RequestArgs;
import org.mozilla.universalchardet.UniversalDetector;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Downloads pages and decodes them the way Python's {@code requests} library would.
 *
 * <p>Decoding is not a detail: the character set decides what the parsed text is, and therefore
 * which rules match. The rules below reproduce {@code requests.utils.get_encoding_from_headers}
 * together with the caller's override of a header-declared Latin-1.
 */
public final class HtmlFetcher {

    /**
     * The default request headers, shared and mutable so callers can adjust them globally.
     *
     * <p>The user agent contains a run of spaces inherited verbatim from the Python source, where
     * an implicit string literal concatenation across two lines left the indentation embedded.
     */
    public static final Map<String, String> REQUEST_HEADERS = new LinkedHashMap<>(Map.of(
            "User-Agent",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_5) AppleWebKit/537.36             "
                    + "(KHTML, like Gecko) Chrome/84.0.4147.135 Safari/537.36"));

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private static volatile HttpClient sharedClient;

    private HtmlFetcher() {
    }

    private static HttpClient client() {
        HttpClient local = sharedClient;
        if (local == null) {
            synchronized (HtmlFetcher.class) {
                local = sharedClient;
                if (local == null) {
                    local = HttpClient.newBuilder()
                            .followRedirects(HttpClient.Redirect.NORMAL)
                            .connectTimeout(DEFAULT_TIMEOUT)
                            .build();
                    sharedClient = local;
                }
            }
        }
        return local;
    }

    /**
     * Downloads a page and decodes it to text.
     *
     * @param url         the page to fetch
     * @param requestArgs per-request options, or {@code null} for none
     * @return the decoded page source
     * @throws IllegalArgumentException when {@code url} is null or not a valid absolute URL
     * @throws UncheckedIOException     when the request fails
     */
    public static String fetchHtml(String url, RequestArgs requestArgs) {
        if (url == null) {
            throw new IllegalArgumentException(
                    "Invalid URL 'None': No scheme supplied. Perhaps you meant https://None?");
        }
        URI uri = parse(url);

        Map<String, String> headers = new LinkedHashMap<>(REQUEST_HEADERS);
        if (!url.isEmpty() && uri.getHost() != null) {
            headers.put("Host", authorityOf(uri));
        }
        RequestArgs args = requestArgs == null ? new RequestArgs() : requestArgs;
        headers.putAll(args.takeHeaders());

        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(args.timeout() == null ? DEFAULT_TIMEOUT : args.timeout())
                .GET();
        headers.forEach(request::header);

        HttpResponse<byte[]> response;
        try {
            response = client().send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to fetch " + url, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while fetching " + url, e);
        }

        String contentType = response.headers().firstValue("content-type").orElse("");
        return new String(response.body(), resolveCharset(contentType, response.body()));
    }

    private static URI parse(String url) {
        try {
            URI uri = new URI(url);
            if (uri.getScheme() == null) {
                throw new IllegalArgumentException(
                        "Invalid URL '" + url + "': No scheme supplied. Perhaps you meant https://" + url + "?");
            }
            return uri;
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid URL '" + url + "'", e);
        }
    }

    private static String authorityOf(URI uri) {
        return uri.getPort() == -1 ? uri.getHost() : uri.getHost() + ":" + uri.getPort();
    }

    /**
     * Chooses the character set exactly as the Python code path does.
     *
     * <p>A declared charset wins. Otherwise {@code requests} would assume Latin-1 for any
     * {@code text/*} response, and the caller replaces that assumption with content sniffing;
     * the two steps collapse into sniffing here. JSON defaults to UTF-8 per its own standard.
     *
     * @param contentType the response Content-Type header, possibly empty
     * @param body        the raw response body, used for sniffing
     * @return the character set to decode with
     */
    private static Charset resolveCharset(String contentType, byte[] body) {
        String lower = contentType.toLowerCase(Locale.ROOT);
        Optional<Charset> declared = declaredCharset(lower);
        if (declared.isPresent()) {
            return declared.get();
        }
        if (lower.contains("application/json")) {
            return StandardCharsets.UTF_8;
        }
        return sniffCharset(body);
    }

    private static Optional<Charset> declaredCharset(String lowerContentType) {
        int at = lowerContentType.indexOf("charset=");
        if (at < 0) {
            return Optional.empty();
        }
        String value = lowerContentType.substring(at + "charset=".length()).trim();
        int end = value.indexOf(';');
        if (end >= 0) {
            value = value.substring(0, end).trim();
        }
        value = value.replace("\"", "").replace("'", "").trim();
        try {
            return Optional.of(Charset.forName(value));
        } catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
            return Optional.empty();
        }
    }

    private static Charset sniffCharset(byte[] body) {
        UniversalDetector detector = new UniversalDetector(null);
        detector.handleData(body, 0, body.length);
        detector.dataEnd();
        String detected = detector.getDetectedCharset();
        if (detected != null) {
            try {
                return Charset.forName(detected);
            } catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
                return StandardCharsets.UTF_8;
            }
        }
        return StandardCharsets.UTF_8;
    }
}
