package io.github.autoscraper.util;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Emulates CPython's {@code repr()} for the value shapes that the {@code autoscraper} rule-hashing
 * path produces.
 *
 * <p>This is load bearing, not cosmetic. The Python implementation derives a rule's identity from
 * <pre>{@code
 * stack["hash"] = hashlib.sha256(str(stack).encode("utf-8")).hexdigest()
 * stack["stack_id"] = "rule_" + stack["hash"][:8]
 * }</pre>
 * where {@code stack} is a {@code dict} containing nested tuples, lists, strings, booleans and
 * {@code None}. {@code str(dict)} is {@code repr(dict)}, so a single differing character — a quote
 * style, an escape, the trailing comma of a one-element tuple — changes every rule ID and breaks
 * model files written by the Python library.
 *
 * <p>Reference: {@code Objects/unicodeobject.c::unicode_repr},
 * {@code Objects/dictobject.c::dict_repr}, {@code Objects/tupleobject.c::tuplerepr},
 * {@code Objects/listobject.c::list_repr}.
 *
 * @see PyRepr#repr(Object)
 */
public final class PyRepr {

    private PyRepr() {
    }

    /**
     * Marker for a Python {@code tuple}, which reprs differently from a {@code list}
     * ({@code ('a',)} versus {@code ['a']}).
     *
     * <p>Java has no tuple type and {@link List} already maps to Python's {@code list}, so callers
     * must wrap tuple-valued data explicitly via {@link #tuple(Object...)}.
     *
     * @param items the tuple elements, in order
     */
    public record PyTuple(List<Object> items) {}

    /**
     * Creates a {@link PyTuple}.
     *
     * @param items the tuple elements, in order; may contain {@code null} for Python {@code None}
     * @return a tuple marker suitable for {@link #repr(Object)}
     */
    public static PyTuple tuple(Object... items) {
        return new PyTuple(Arrays.asList(items));
    }

    /**
     * Renders a value exactly as CPython's {@code repr()} would.
     *
     * <p>Supported types and their Python counterparts:
     * <table>
     *   <caption>Type mapping</caption>
     *   <tr><th>Java</th><th>Python</th><th>Example output</th></tr>
     *   <tr><td>{@code null}</td><td>{@code None}</td><td>{@code None}</td></tr>
     *   <tr><td>{@link Boolean}</td><td>{@code bool}</td><td>{@code True}</td></tr>
     *   <tr><td>{@link String}</td><td>{@code str}</td><td>{@code 'hi'}</td></tr>
     *   <tr><td>{@link Integer} etc.</td><td>{@code int}</td><td>{@code -12}</td></tr>
     *   <tr><td>{@link PyTuple}</td><td>{@code tuple}</td><td>{@code ('a',)}</td></tr>
     *   <tr><td>{@link List}</td><td>{@code list}</td><td>{@code ['a', 1]}</td></tr>
     *   <tr><td>{@link Map}</td><td>{@code dict}</td><td>{@code {'k': 'v'}}</td></tr>
     * </table>
     *
     * <p>Map iteration order is significant and is emitted as-is, so callers must pass an
     * insertion-ordered map (typically {@link java.util.LinkedHashMap}).
     *
     * @param value the value to render
     * @return the Python {@code repr()} of {@code value}
     * @throws IllegalArgumentException if {@code value} has an unsupported runtime type; the port
     *                                  deliberately refuses to guess rather than silently emit a
     *                                  string that would corrupt every downstream rule ID
     */
    public static String repr(Object value) {
        if (value == null) {
            return "None";
        }
        if (value instanceof Boolean b) {
            return b ? "True" : "False";
        }
        if (value instanceof String s) {
            return reprString(s);
        }
        if (value instanceof Integer || value instanceof Long || value instanceof Short
                || value instanceof Byte || value instanceof BigInteger) {
            return value.toString();
        }
        if (value instanceof PyTuple t) {
            return reprTuple(t.items());
        }
        if (value instanceof List<?> l) {
            return reprList(l);
        }
        if (value instanceof Map<?, ?> m) {
            return reprMapRaw(m);
        }
        throw new IllegalArgumentException(
                "PyRepr cannot render " + value.getClass().getName()
                        + "; supported: null, Boolean, String, integral numbers, PyTuple, List, Map");
    }

    /**
     * Renders a Python {@code list}: {@code [] }, {@code ['a']}, {@code ['a', 1]}.
     *
     * @param items the elements, in order
     * @return the Python {@code repr()} of the corresponding list
     */
    public static String reprList(List<?> items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(repr(items.get(i)));
        }
        return sb.append(']').toString();
    }

    /**
     * Renders a Python {@code tuple}, including the mandatory trailing comma for one-element
     * tuples: {@code ()}, {@code ('a',)}, {@code ('a', 1)}.
     *
     * @param items the elements, in order
     * @return the Python {@code repr()} of the corresponding tuple
     */
    public static String reprTuple(List<?> items) {
        if (items.isEmpty()) {
            return "()";
        }
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(repr(items.get(i)));
        }
        if (items.size() == 1) {
            sb.append(',');
        }
        return sb.append(')').toString();
    }

    /**
     * Renders a Python {@code dict} with string keys: {@code {}}, {@code {'k': 'v', 'x': 1}}.
     *
     * @param map the entries; iteration order is emitted verbatim, so pass an insertion-ordered map
     * @return the Python {@code repr()} of the corresponding dict
     */
    public static String reprMap(Map<String, ?> map) {
        return reprMapRaw(map);
    }

    private static String reprMapRaw(Map<?, ?> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (!first) {
                sb.append(", ");
            }
            first = false;
            sb.append(repr(e.getKey())).append(": ").append(repr(e.getValue()));
        }
        return sb.append('}').toString();
    }

    /**
     * Renders a Python {@code str} literal, reproducing CPython's quote selection and escaping.
     *
     * <p>Quote selection: single quotes by default; double quotes only when the value contains a
     * {@code '} but no {@code "}. Escapes, in priority order: the active quote, {@code \},
     * {@code \t}, {@code \n}, {@code \r}, then any code point that is not
     * {@code str.isprintable()} as {@code \xHH}, {@code &#92;uHHHH} or {@code \UHHHHHHHH} with
     * lowercase hex digits. Note that {@code "} is <em>not</em> escaped inside a single-quoted
     * literal.
     *
     * @param s the string to render
     * @return the Python {@code repr()} of {@code s}, including surrounding quotes
     */
    public static String reprString(String s) {
        boolean hasSingle = s.indexOf('\'') >= 0;
        boolean hasDouble = s.indexOf('"') >= 0;
        char quote = (hasSingle && !hasDouble) ? '"' : '\'';

        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append(quote);
        int i = 0;
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            int width = Character.charCount(cp);
            i += width;

            if (cp == quote || cp == '\\') {
                sb.append('\\').append((char) cp);
            } else if (cp == '\t') {
                sb.append("\\t");
            } else if (cp == '\n') {
                sb.append("\\n");
            } else if (cp == '\r') {
                sb.append("\\r");
            } else if (isPrintable(cp)) {
                sb.appendCodePoint(cp);
            } else if (cp < 0x100) {
                sb.append(String.format("\\x%02x", cp));
            } else if (cp < 0x10000) {
                sb.append(String.format("\\u%04x", cp));
            } else {
                sb.append(String.format("\\U%08x", cp));
            }
        }
        return sb.append(quote).toString();
    }

    /**
     * Mirrors Python's {@code str.isprintable()}.
     *
     * <p>A code point is non-printable when its Unicode general category is one of
     * {@code Cc, Cf, Cs, Co, Cn, Zl, Zp, Zs}, with the single exception that ASCII space
     * (U+0020) <em>is</em> printable.
     *
     * <p>This relies on the JDK's Unicode tables, which can lag or lead the CPython build's. The
     * golden fixtures in {@code src/test/resources/golden/pyrepr.tsv} pin the behaviour for every
     * code point the library actually encounters.
     */
    private static boolean isPrintable(int cp) {
        if (cp == 0x20) {
            return true;
        }
        return switch (Character.getType(cp)) {
            case Character.CONTROL,
                 Character.FORMAT,
                 Character.SURROGATE,
                 Character.PRIVATE_USE,
                 Character.UNASSIGNED,
                 Character.LINE_SEPARATOR,
                 Character.PARAGRAPH_SEPARATOR,
                 Character.SPACE_SEPARATOR -> false;
            default -> true;
        };
    }

    /**
     * Computes the lowercase hex SHA-256 of the UTF-8 encoding of {@code s}, matching
     * {@code hashlib.sha256(s.encode("utf-8")).hexdigest()}.
     *
     * @param s the string to hash
     * @return 64 lowercase hex characters
     */
    public static String sha256Hex(String s) {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
        byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte x : digest) {
            sb.append(Character.forDigit((x >> 4) & 0xF, 16));
            sb.append(Character.forDigit(x & 0xF, 16));
        }
        return sb.toString();
    }
}
