package io.github.autoscraper.testutil;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Reader for the tab-separated golden fixtures produced by the oracle fixture generator.
 *
 * <p>Each file starts with a {@code #}-prefixed header line naming the columns. Field values are
 * escaped so that a record always occupies exactly one physical line:
 * {@code \\} for a backslash, {@code \t} for a tab, {@code \n} for a newline and {@code \r} for a
 * carriage return.
 */
public final class GoldenTsv {

    private GoldenTsv() {
    }

    /**
     * Loads a golden TSV from the test classpath.
     *
     * @param resource absolute classpath path, for example {@code /golden/urljoin.tsv}
     * @return the data rows, with the header line removed and all fields unescaped
     */
    public static List<String[]> rows(String resource) {
        try (InputStream in = GoldenTsv.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing golden fixture on the test classpath: "
                        + resource + " (regenerate with the oracle fixture generator)");
            }
            BufferedReader reader =
                    new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            List<String[]> out = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("#") || line.isEmpty()) {
                    continue;
                }
                String[] raw = line.split("\t", -1);
                String[] fields = new String[raw.length];
                for (int i = 0; i < raw.length; i++) {
                    fields[i] = unescape(raw[i]);
                }
                out.add(fields);
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read golden fixture " + resource, e);
        }
    }

    /**
     * Reverses the escaping applied by the fixture generator.
     *
     * @param s an escaped field value
     * @return the original text
     */
    public static String unescape(String s) {
        if (s.indexOf('\\') < 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) {
                sb.append(c);
                continue;
            }
            char n = s.charAt(++i);
            switch (n) {
                case '\\' -> sb.append('\\');
                case 't' -> sb.append('\t');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                default -> sb.append('\\').append(n);
            }
        }
        return sb.toString();
    }
}
