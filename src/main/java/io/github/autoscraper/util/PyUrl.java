package io.github.autoscraper.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ports of the two CPython standard-library functions that {@code autoscraper} depends on for
 * exact output: {@code urllib.parse.urljoin} and {@code html.unescape}.
 *
 * <h2>Why not the JDK equivalents?</h2>
 * {@link java.net.URI#resolve(String)} is <em>not</em> a drop-in replacement for {@code urljoin}.
 * It throws on an empty or opaque base, treats an empty reference differently, normalises dot
 * segments differently, and rejects inputs that {@code urlsplit} happily accepts. Because
 * {@code autoscraper} stores joined URLs directly in its extracted results, any divergence is
 * user-visible.
 *
 * <h2>Which CPython?</h2>
 * This is a port of the <strong>Python 3.14</strong> implementation, which is the interpreter used
 * to generate the golden fixtures in {@code src/test/resources/golden/urljoin.tsv}. Python 3.13
 * reworked {@code urljoin} to track "component absent" as {@code None} rather than {@code ""},
 * which changes observable output for references such as {@code "?query=2"}: 3.14 yields
 * {@code https://example.com/index.html?query=2} whereas older releases yielded
 * {@code https://example.com/?query=2}. The tri-state is therefore reproduced faithfully here with
 * nullable {@link String} components. See {@code PORTING-NOTES.md}.
 *
 * <p>Reference: {@code Lib/urllib/parse.py}, {@code Lib/html/__init__.py}.
 */
public final class PyUrl {

    private PyUrl() {
    }

    /** Schemes for which {@code urljoin} performs relative resolution. */
    private static final Set<String> USES_RELATIVE = Set.of(
            "", "ftp", "http", "gopher", "nntp", "imap", "wais", "file", "https", "shttp", "mms",
            "prospero", "rtsp", "rtsps", "rtspu", "sftp", "svn", "svn+ssh", "ws", "wss");

    /** Schemes that carry a {@code //netloc} authority component. */
    private static final Set<String> USES_NETLOC = Set.of(
            "", "ftp", "http", "gopher", "nntp", "telnet", "imap", "wais", "file", "mms", "https",
            "shttp", "snews", "prospero", "rtsp", "rtsps", "rtspu", "rsync", "svn", "svn+ssh",
            "sftp", "nfs", "git", "git+ssh", "ws", "wss", "itms-services");

    /** CPython {@code urllib.parse._UNSAFE_URL_BYTES_TO_REMOVE}. */
    private static final char[] UNSAFE_URL_CHARS = {'\t', '\r', '\n'};

    /** CPython {@code urllib.parse.scheme_chars}, excluding the mandatory leading ASCII letter. */
    private static final String SCHEME_CHARS =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789+-.";

    private static final Pattern IPV_FUTURE = Pattern.compile("\\A[vV][a-fA-F0-9]+\\..+\\z");

    // ------------------------------------------------------------------------------------------
    // urlsplit / urlunsplit / urljoin
    // ------------------------------------------------------------------------------------------

    /**
     * The five components produced by CPython's internal {@code urllib.parse._urlsplit}.
     *
     * <p>Unlike the public {@code urlsplit}, which normalises missing components to {@code ""},
     * every field here is {@code null} when the component is <em>absent</em> and {@code ""} when
     * it is present but empty. {@code urljoin} depends on that distinction: {@code "http://x/p?"}
     * has an empty query, {@code "http://x/p"} has none, and the two resolve differently.
     *
     * @param scheme   lowercased URL scheme without the trailing colon, or {@code null}
     * @param netloc   authority component without the leading {@code //}, or {@code null}
     * @param path     hierarchical path; never {@code null}, possibly {@code ""}
     * @param query    query string without the leading {@code ?}, or {@code null}
     * @param fragment fragment without the leading {@code #}, or {@code null}
     */
    public record Split(String scheme, String netloc, String path, String query, String fragment) {}

    /**
     * Port of {@code urllib.parse.urljoin(base, url, allow_fragments=True)}.
     *
     * <p>Notable behaviours that {@code autoscraper} relies on:
     * <ul>
     *   <li>an empty {@code base} returns {@code url} verbatim;</li>
     *   <li>an empty {@code url} returns {@code base} verbatim;</li>
     *   <li>a {@code url} whose scheme differs from the base's, or whose scheme is not
     *       relative-capable (for example {@code mailto:} or {@code javascript:}), is returned
     *       unchanged rather than resolved.</li>
     * </ul>
     *
     * @param base the base URL; may be {@code null} or empty
     * @param url  the reference to resolve; may be {@code null} or empty
     * @return the joined URL
     * @throws IllegalArgumentException if either input has a malformed authority component,
     *                                  mirroring CPython's {@code ValueError}
     */
    public static String urljoin(String base, String url) {
        if (base == null || base.isEmpty()) {
            return url;
        }
        if (url == null || url.isEmpty()) {
            return base;
        }
        Split b = urlsplit(base, null);
        Split u = urlsplit(url, null);

        String scheme = u.scheme();
        String netloc = u.netloc();
        String path = u.path();
        String query = u.query();
        String fragment = u.fragment();

        if (scheme == null) {
            scheme = b.scheme();
        }
        if (!java.util.Objects.equals(scheme, b.scheme())
                || (scheme != null && !scheme.isEmpty() && !USES_RELATIVE.contains(scheme))) {
            return url;
        }
        if (scheme == null || scheme.isEmpty() || USES_NETLOC.contains(scheme)) {
            if (netloc != null && !netloc.isEmpty()) {
                return urlunsplit(scheme, netloc, path, query, fragment);
            }
            netloc = b.netloc();
        }

        if (path.isEmpty()) {
            path = b.path();
            if (query == null) {
                query = b.query();
                if (fragment == null) {
                    fragment = b.fragment();
                }
            }
            return urlunsplit(scheme, netloc, path, query, fragment);
        }

        List<String> baseParts = pySplit(b.path(), '/');
        if (!baseParts.get(baseParts.size() - 1).isEmpty()) {
            // The last component names a file, not a directory, so it plays no part in resolution.
            baseParts.remove(baseParts.size() - 1);
        }

        List<String> segments;
        if (path.startsWith("/")) {
            // RFC 3986: a root-relative reference discards the whole base path.
            segments = pySplit(path, '/');
        } else {
            segments = new ArrayList<>(baseParts);
            segments.addAll(pySplit(path, '/'));
            // segments[1:-1] = filter(None, segments[1:-1]) - drop interior empties that would
            // otherwise produce doubled slashes on re-joining.
            if (segments.size() > 2) {
                List<String> rebuilt = new ArrayList<>(segments.size());
                rebuilt.add(segments.get(0));
                for (int i = 1; i < segments.size() - 1; i++) {
                    if (!segments.get(i).isEmpty()) {
                        rebuilt.add(segments.get(i));
                    }
                }
                rebuilt.add(segments.get(segments.size() - 1));
                segments = rebuilt;
            }
        }

        List<String> resolved = new ArrayList<>();
        for (String seg : segments) {
            if ("..".equals(seg)) {
                // CPython swallows the IndexError, so extra ".." segments are simply ignored.
                if (!resolved.isEmpty()) {
                    resolved.remove(resolved.size() - 1);
                }
            } else if (".".equals(seg)) {
                continue;
            } else {
                resolved.add(seg);
            }
        }
        String last = segments.get(segments.size() - 1);
        if (".".equals(last) || "..".equals(last)) {
            // A trailing relative directory keeps its trailing slash.
            resolved.add("");
        }

        String joined = String.join("/", resolved);
        return urlunsplit(scheme, netloc, joined.isEmpty() ? "/" : joined, query, fragment);
    }

    /**
     * Port of CPython's internal {@code urllib.parse._urlsplit(url, scheme, allow_fragments=True)}.
     *
     * @param url    the URL to split; must not be {@code null}
     * @param scheme the default scheme applied when {@code url} carries none, or {@code null} for
     *               "absent"
     * @return the five URL components, with {@code null} marking absent ones
     * @throws IllegalArgumentException on a malformed authority component
     */
    public static Split urlsplit(String url, String scheme) {
        // CPython only lstrips: some applications rely on a preserved trailing space.
        String u = lstripC0(url);
        u = stripUnsafe(u);
        String sch = scheme;
        if (sch != null) {
            sch = stripC0(sch);
            sch = stripUnsafe(sch);
        }

        String netloc = null;
        String query = null;
        String fragment = null;

        int i = u.indexOf(':');
        if (i > 0 && isAsciiLetter(u.charAt(0)) && allSchemeChars(u, i)) {
            sch = u.substring(0, i).toLowerCase(Locale.ROOT);
            u = u.substring(i + 1);
        }
        if (u.startsWith("//")) {
            int delim = u.length();
            for (char c : new char[] {'/', '?', '#'}) {
                int w = u.indexOf(c, 2);
                if (w >= 0) {
                    delim = Math.min(delim, w);
                }
            }
            netloc = u.substring(2, delim);
            u = u.substring(delim);
            boolean open = netloc.indexOf('[') >= 0;
            boolean close = netloc.indexOf(']') >= 0;
            if (open != close) {
                throw new IllegalArgumentException("Invalid IPv6 URL");
            }
            if (open) {
                checkBracketedNetloc(netloc);
            }
        }
        int hash = u.indexOf('#');
        if (hash >= 0) {
            fragment = u.substring(hash + 1);
            u = u.substring(0, hash);
        }
        int question = u.indexOf('?');
        if (question >= 0) {
            query = u.substring(question + 1);
            u = u.substring(0, question);
        }
        checkNetloc(netloc);
        return new Split(sch, netloc, u, query, fragment);
    }

    /**
     * Port of CPython's internal {@code urllib.parse._urlunsplit}.
     *
     * @param scheme   URL scheme, or {@code null}/{@code ""} for none
     * @param netloc   authority component, or {@code null} for none (an empty string still emits
     *                 the {@code //} marker)
     * @param path     hierarchical path
     * @param query    query string, or {@code null} for none (an empty string still emits {@code ?})
     * @param fragment fragment, or {@code null} for none (an empty string still emits {@code #})
     * @return the recombined URL
     */
    public static String urlunsplit(String scheme, String netloc, String path, String query,
                                    String fragment) {
        String url = path;
        if (netloc != null) {
            if (!url.isEmpty() && !url.startsWith("/")) {
                url = "/" + url;
            }
            url = "//" + netloc + url;
        } else if (url.startsWith("//")) {
            url = "//" + url;
        }
        if (scheme != null && !scheme.isEmpty()) {
            url = scheme + ":" + url;
        }
        if (query != null) {
            url = url + "?" + query;
        }
        if (fragment != null) {
            url = url + "#" + fragment;
        }
        return url;
    }

    /** Port of {@code urllib.parse._check_bracketed_netloc}. */
    private static void checkBracketedNetloc(String netloc) {
        int at = netloc.lastIndexOf('@');
        String hostAndPort = at >= 0 ? netloc.substring(at + 1) : netloc;
        int open = hostAndPort.indexOf('[');
        String hostname;
        if (open >= 0) {
            if (open > 0) {
                throw new IllegalArgumentException("Invalid IPv6 URL");
            }
            String bracketed = hostAndPort.substring(open + 1);
            int close = bracketed.indexOf(']');
            hostname = close >= 0 ? bracketed.substring(0, close) : bracketed;
            String port = close >= 0 ? bracketed.substring(close + 1) : "";
            if (!port.isEmpty() && !port.startsWith(":")) {
                throw new IllegalArgumentException("Invalid IPv6 URL");
            }
        } else {
            int colon = hostAndPort.indexOf(':');
            hostname = colon >= 0 ? hostAndPort.substring(0, colon) : hostAndPort;
        }
        checkBracketedHost(hostname);
    }

    /** Port of {@code urllib.parse._check_bracketed_host}. */
    private static void checkBracketedHost(String hostname) {
        if (hostname.startsWith("v") || hostname.startsWith("V")) {
            if (!IPV_FUTURE.matcher(hostname).matches()) {
                throw new IllegalArgumentException("IPvFuture address is invalid");
            }
            return;
        }
        // CPython delegates to ipaddress.ip_address, which accepts IPv4 and IPv6 and then rejects
        // IPv4 inside brackets. Only the IPv6 shape is legal here.
        if (hostname.indexOf(':') < 0) {
            throw new IllegalArgumentException("An IPv4 address cannot be in brackets");
        }
    }

    /** Port of {@code urllib.parse._checknetloc}: guards NFKC-expanding lookalike characters. */
    private static void checkNetloc(String netloc) {
        if (netloc == null || netloc.isEmpty() || isAscii(netloc)) {
            return;
        }
        String n = netloc.replace("@", "").replace(":", "").replace("#", "").replace("?", "");
        String normalized = Normalizer.normalize(n, Normalizer.Form.NFKC);
        if (n.equals(normalized)) {
            return;
        }
        for (char c : new char[] {'/', '?', '#', '@', ':'}) {
            if (normalized.indexOf(c) >= 0) {
                throw new IllegalArgumentException("netloc '" + netloc
                        + "' contains invalid characters under NFKC normalization");
            }
        }
    }

    private static boolean isAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 0x7F) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static boolean allSchemeChars(String u, int end) {
        for (int k = 0; k < end; k++) {
            if (SCHEME_CHARS.indexOf(u.charAt(k)) < 0) {
                return false;
            }
        }
        return true;
    }

    private static String stripUnsafe(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        outer:
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            for (char bad : UNSAFE_URL_CHARS) {
                if (c == bad) {
                    continue outer;
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** Strips CPython's {@code _WHATWG_C0_CONTROL_OR_SPACE} set from the left only. */
    private static String lstripC0(String s) {
        int start = 0;
        while (start < s.length() && s.charAt(start) <= 0x20) {
            start++;
        }
        return s.substring(start);
    }

    /** Strips CPython's {@code _WHATWG_C0_CONTROL_OR_SPACE} set from both ends. */
    private static String stripC0(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && s.charAt(start) <= 0x20) {
            start++;
        }
        while (end > start && s.charAt(end - 1) <= 0x20) {
            end--;
        }
        return s.substring(start, end);
    }

    /**
     * Python's {@code str.split(sep)} semantics: never drops empty fields, and always yields at
     * least one element. {@link String#split(String)} discards trailing empties, which would break
     * the directory-detection step of {@link #urljoin}.
     */
    private static List<String> pySplit(String s, char sep) {
        List<String> out = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == sep) {
                out.add(s.substring(start, i));
                start = i + 1;
            }
        }
        out.add(s.substring(start));
        return out;
    }

    // ------------------------------------------------------------------------------------------
    // html.unescape
    // ------------------------------------------------------------------------------------------

    /** CPython {@code html._charref}. */
    private static final Pattern CHARREF = Pattern.compile(
            "&(#[0-9]+;?|#[xX][0-9a-fA-F]+;?|[^\t\n\f <&#;]{1,32};?)");

    /**
     * Lazily-loaded HTML5 reference tables, dumped from the CPython {@code html} module by
     * the entity-table generator and shipped as classpath resources.
     */
    private static final class Tables {
        static final Map<String, String> HTML5 =
                load("html5_entities.json", new TypeReference<Map<String, String>>() {});
        static final Map<Integer, String> INVALID_CHARREFS = invalidCharrefs();
        static final Set<Integer> INVALID_CODEPOINTS = invalidCodepoints();

        private Tables() {
        }

        private static <T> T load(String resource, TypeReference<T> type) {
            try (InputStream in = PyUrl.class.getResourceAsStream(resource)) {
                if (in == null) {
                    throw new IllegalStateException(
                            "Missing classpath resource io/github/autoscraper/util/" + resource
                                    + "; regenerate it with the entity-table generator");
                }
                return new ObjectMapper().readValue(in, type);
            } catch (IOException e) {
                throw new IllegalStateException("Cannot read HTML entity table " + resource, e);
            }
        }

        private static Map<Integer, String> invalidCharrefs() {
            Map<String, String> raw =
                    load("invalid_charrefs.json", new TypeReference<Map<String, String>>() {});
            Map<Integer, String> out = new HashMap<>(raw.size() * 2);
            raw.forEach((k, v) -> out.put(Integer.valueOf(k), v));
            return out;
        }

        private static Set<Integer> invalidCodepoints() {
            return new HashSet<>(
                    load("invalid_codepoints.json", new TypeReference<List<Integer>>() {}));
        }
    }

    /**
     * Port of {@code html.unescape}: converts all named and numeric character references in a
     * string to their Unicode characters.
     *
     * <p>This follows the HTML5 rules, which are considerably more permissive than XML: an
     * unterminated reference such as {@code &amp;amp} is still resolved, an unknown reference is
     * left alone, the longest matching prefix wins for a name like {@code &amp;notit;}, and a
     * number of legacy numeric references are remapped (for example {@code &amp;#151;} becomes an
     * em dash rather than a C1 control).
     *
     * @param s the string to unescape; {@code null} is returned unchanged
     * @return {@code s} with character references resolved
     */
    public static String unescape(String s) {
        if (s == null || s.indexOf('&') < 0) {
            return s;
        }
        Matcher m = CHARREF.matcher(s);
        StringBuilder out = new StringBuilder(s.length());
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(replaceCharref(m.group(1))));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** Port of {@code html._replace_charref}. */
    private static String replaceCharref(String s) {
        if (s.charAt(0) == '#') {
            int num;
            try {
                if (s.length() > 1 && (s.charAt(1) == 'x' || s.charAt(1) == 'X')) {
                    num = Integer.parseInt(rstripSemicolon(s.substring(2)), 16);
                } else {
                    num = Integer.parseInt(rstripSemicolon(s.substring(1)));
                }
            } catch (NumberFormatException e) {
                // Python has arbitrary-precision ints, so a reference above 2**31 is merely
                // "> 0x10FFFF" and maps to the replacement character rather than failing.
                return "\uFFFD";
            }
            String legacy = Tables.INVALID_CHARREFS.get(num);
            if (legacy != null) {
                return legacy;
            }
            if ((num >= 0xD800 && num <= 0xDFFF) || num > 0x10FFFF) {
                return "\uFFFD";
            }
            if (Tables.INVALID_CODEPOINTS.contains(num)) {
                return "";
            }
            return new String(Character.toChars(num));
        }

        String exact = Tables.HTML5.get(s);
        if (exact != null) {
            return exact;
        }
        // Longest matching prefix wins: "&notit;" resolves to U+00AC followed by "it;".
        for (int x = s.length() - 1; x > 1; x--) {
            String hit = Tables.HTML5.get(s.substring(0, x));
            if (hit != null) {
                return hit + s.substring(x);
            }
        }
        return "&" + s;
    }

    private static String rstripSemicolon(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == ';') {
            end--;
        }
        return s.substring(0, end);
    }
}
