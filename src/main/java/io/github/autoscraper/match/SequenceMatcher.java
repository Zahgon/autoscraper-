package io.github.autoscraper.match;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A faithful port of CPython's {@code difflib.SequenceMatcher} (see {@code Lib/difflib.py}),
 * specialised to {@link String} sequences with {@code isjunk=None}.
 *
 * <p>The Python {@code autoscraper} library uses {@code SequenceMatcher(None, a, b).ratio()} for
 * both of its fuzzy-matching paths ({@code text_fuzz_ratio} and {@code attr_fuzz_ratio}). That
 * ratio is <em>not</em> normalised edit distance and is <em>not</em> symmetric in general, so any
 * approximation (Levenshtein, Jaro-Winkler, ...) would silently change which elements a scraper
 * matches. This class therefore reproduces the CPython algorithm exactly, including the
 * {@code autojunk} "popular element" heuristic.
 *
 * <h2>Asymmetry</h2>
 * {@code ratio(a, b)} may differ from {@code ratio(b, a)} because the {@code b2j} index and the
 * junk heuristic are built from {@code b} only. Always keep the argument order used by the Python
 * source.
 *
 * <h2>Unicode</h2>
 * Elements are UTF-16 {@code char} values, not code points. CPython iterates {@code str} by code
 * point, so a sequence containing astral characters (outside the BMP) could in principle produce a
 * different ratio here. Web page text handled by this library is overwhelmingly BMP, and using
 * {@code char} keeps the index arrays compact; callers needing exact astral behaviour should
 * normalise beforehand.
 *
 * <p>Instances are not thread-safe: {@link #getMatchingBlocks()} memoises lazily.
 */
public final class SequenceMatcher {

    /**
     * A triple {@code (a, b, size)} meaning {@code a[a:a+size] == b[b:b+size]}.
     *
     * @param a    start index in the first sequence
     * @param b    start index in the second sequence
     * @param size length of the matching run
     */
    public record Match(int a, int b, int size) {}

    private final String a;
    private final String b;
    private final boolean autojunk;

    /** Maps an element of {@code b} to the ascending list of its indices in {@code b}. */
    private final Map<Character, int[]> b2j;

    /**
     * Elements of {@code b} considered junk by {@code isjunk}. Always empty here because the
     * Python call site passes {@code isjunk=None}; retained as a field so the two junk-extension
     * loops in {@link #findLongestMatch} keep their CPython shape.
     */
    private final Set<Character> bjunk;

    /** Elements dropped from {@code b2j} by the autojunk heuristic. Not part of {@code bjunk}. */
    private final Set<Character> bpopular;

    private List<Match> matchingBlocks;

    /**
     * Creates a matcher with CPython's default {@code autojunk=True}.
     *
     * @param a first sequence, must not be {@code null}
     * @param b second sequence, must not be {@code null}
     */
    public SequenceMatcher(String a, String b) {
        this(a, b, true);
    }

    /**
     * Creates a matcher.
     *
     * @param a        first sequence, must not be {@code null}
     * @param b        second sequence, must not be {@code null}
     * @param autojunk whether to enable the "popular element" heuristic that CPython applies to
     *                 sequences of length &ge; 200
     */
    public SequenceMatcher(String a, String b, boolean autojunk) {
        if (a == null || b == null) {
            throw new IllegalArgumentException("SequenceMatcher operands must not be null");
        }
        this.a = a;
        this.b = b;
        this.autojunk = autojunk;
        this.bjunk = new HashSet<>();
        this.bpopular = new HashSet<>();
        this.b2j = chainB();
    }

    /**
     * Convenience for {@code new SequenceMatcher(a, b).ratio()}.
     *
     * @param a first sequence
     * @param b second sequence
     * @return the CPython {@code difflib} similarity ratio in {@code [0.0, 1.0]}
     */
    public static double ratio(String a, String b) {
        return new SequenceMatcher(a, b).ratio();
    }

    /** Port of {@code SequenceMatcher.__chain_b}. */
    private Map<Character, int[]> chainB() {
        int n = b.length();
        Map<Character, List<Integer>> index = new HashMap<>();
        for (int i = 0; i < n; i++) {
            index.computeIfAbsent(b.charAt(i), k -> new ArrayList<>()).add(i);
        }

        // isjunk is None in every autoscraper call site, so bjunk stays empty and no element is
        // removed on that account. The loop is kept (as a no-op) to mirror the CPython source.

        if (autojunk && n >= 200) {
            int ntest = n / 100 + 1;
            for (Map.Entry<Character, List<Integer>> e : index.entrySet()) {
                if (e.getValue().size() > ntest) {
                    bpopular.add(e.getKey());
                }
            }
            for (Character elt : bpopular) {
                index.remove(elt);
            }
        }

        Map<Character, int[]> packed = new HashMap<>(index.size() * 2);
        for (Map.Entry<Character, List<Integer>> e : index.entrySet()) {
            List<Integer> v = e.getValue();
            int[] arr = new int[v.size()];
            for (int i = 0; i < arr.length; i++) {
                arr[i] = v.get(i);
            }
            packed.put(e.getKey(), arr);
        }
        return packed;
    }

    private boolean isBJunk(char c) {
        return bjunk.contains(c);
    }

    /**
     * Port of {@code SequenceMatcher.find_longest_match}.
     *
     * <p>Finds the longest matching block in {@code a[alo:ahi]} and {@code b[blo:bhi]}, preferring
     * the earliest such block in {@code a} and then in {@code b}, and extending it over junk
     * elements at both ends.
     *
     * @param alo inclusive lower bound in {@code a}
     * @param ahi exclusive upper bound in {@code a}
     * @param blo inclusive lower bound in {@code b}
     * @param bhi exclusive upper bound in {@code b}
     * @return the best {@link Match}; {@code size} is {@code 0} when there is none
     */
    public Match findLongestMatch(int alo, int ahi, int blo, int bhi) {
        int besti = alo;
        int bestj = blo;
        int bestsize = 0;

        Map<Integer, Integer> j2len = new HashMap<>();
        for (int i = alo; i < ahi; i++) {
            Map<Integer, Integer> newj2len = new HashMap<>();
            int[] js = b2j.get(a.charAt(i));
            if (js != null) {
                for (int j : js) {
                    if (j < blo) {
                        continue;
                    }
                    if (j >= bhi) {
                        // b2j lists are ascending, so nothing further can be in range.
                        break;
                    }
                    int k = j2len.getOrDefault(j - 1, 0) + 1;
                    newj2len.put(j, k);
                    if (k > bestsize) {
                        besti = i - k + 1;
                        bestj = j - k + 1;
                        bestsize = k;
                    }
                }
            }
            j2len = newj2len;
        }

        while (besti > alo && bestj > blo
                && !isBJunk(b.charAt(bestj - 1))
                && a.charAt(besti - 1) == b.charAt(bestj - 1)) {
            besti--;
            bestj--;
            bestsize++;
        }
        while (besti + bestsize < ahi && bestj + bestsize < bhi
                && !isBJunk(b.charAt(bestj + bestsize))
                && a.charAt(besti + bestsize) == b.charAt(bestj + bestsize)) {
            bestsize++;
        }

        while (besti > alo && bestj > blo
                && isBJunk(b.charAt(bestj - 1))
                && a.charAt(besti - 1) == b.charAt(bestj - 1)) {
            besti--;
            bestj--;
            bestsize++;
        }
        while (besti + bestsize < ahi && bestj + bestsize < bhi
                && isBJunk(b.charAt(bestj + bestsize))
                && a.charAt(besti + bestsize) == b.charAt(bestj + bestsize)) {
            bestsize++;
        }

        return new Match(besti, bestj, bestsize);
    }

    /**
     * Port of {@code SequenceMatcher.get_matching_blocks}.
     *
     * @return the non-adjacent matching blocks in increasing order, always terminated by the
     *         sentinel {@code (len(a), len(b), 0)}; the result is memoised and unmodifiable
     */
    public List<Match> getMatchingBlocks() {
        if (matchingBlocks != null) {
            return matchingBlocks;
        }
        int la = a.length();
        int lb = b.length();

        // CPython uses a LIFO list here; the pop-from-the-end order affects nothing observable,
        // but is preserved for fidelity.
        List<int[]> queue = new ArrayList<>();
        queue.add(new int[] {0, la, 0, lb});
        List<Match> blocks = new ArrayList<>();
        while (!queue.isEmpty()) {
            int[] q = queue.remove(queue.size() - 1);
            int alo = q[0];
            int ahi = q[1];
            int blo = q[2];
            int bhi = q[3];
            Match x = findLongestMatch(alo, ahi, blo, bhi);
            int i = x.a();
            int j = x.b();
            int k = x.size();
            if (k > 0) {
                blocks.add(x);
                if (alo < i && blo < j) {
                    queue.add(new int[] {alo, i, blo, j});
                }
                if (i + k < ahi && j + k < bhi) {
                    queue.add(new int[] {i + k, ahi, j + k, bhi});
                }
            }
        }
        blocks.sort((m, n) -> {
            int c = Integer.compare(m.a(), n.a());
            if (c != 0) {
                return c;
            }
            c = Integer.compare(m.b(), n.b());
            if (c != 0) {
                return c;
            }
            return Integer.compare(m.size(), n.size());
        });

        int i1 = 0;
        int j1 = 0;
        int k1 = 0;
        List<Match> nonAdjacent = new ArrayList<>();
        for (Match m : blocks) {
            int i2 = m.a();
            int j2 = m.b();
            int k2 = m.size();
            if (i1 + k1 == i2 && j1 + k1 == j2) {
                k1 += k2;
            } else {
                if (k1 > 0) {
                    nonAdjacent.add(new Match(i1, j1, k1));
                }
                i1 = i2;
                j1 = j2;
                k1 = k2;
            }
        }
        if (k1 > 0) {
            nonAdjacent.add(new Match(i1, j1, k1));
        }
        nonAdjacent.add(new Match(la, lb, 0));

        matchingBlocks = Collections.unmodifiableList(nonAdjacent);
        return matchingBlocks;
    }

    /**
     * Port of {@code SequenceMatcher.ratio}: {@code 2 * M / T} where {@code M} is the total size of
     * all matching blocks and {@code T} is the combined length of both sequences.
     *
     * @return a value in {@code [0.0, 1.0]}; two empty sequences yield {@code 1.0}
     */
    public double ratio() {
        int matches = 0;
        for (Match m : getMatchingBlocks()) {
            matches += m.size();
        }
        return calculateRatio(matches, a.length() + b.length());
    }

    /**
     * Port of {@code SequenceMatcher.quick_ratio}: an upper bound on {@link #ratio()} computed from
     * multiset intersection, ignoring order.
     *
     * @return a value in {@code [0.0, 1.0]}, never less than {@link #ratio()}
     */
    public double quickRatio() {
        Map<Character, Integer> fullbcount = new HashMap<>();
        for (int i = 0; i < b.length(); i++) {
            fullbcount.merge(b.charAt(i), 1, Integer::sum);
        }
        int matches = 0;
        Map<Character, Integer> avail = new HashMap<>();
        for (int i = 0; i < a.length(); i++) {
            char elt = a.charAt(i);
            Integer numb = avail.get(elt);
            if (numb == null) {
                numb = fullbcount.getOrDefault(elt, 0);
            }
            avail.put(elt, numb - 1);
            if (numb > 0) {
                matches++;
            }
        }
        return calculateRatio(matches, a.length() + b.length());
    }

    /**
     * Port of {@code SequenceMatcher.real_quick_ratio}: an even cheaper upper bound based only on
     * the sequence lengths.
     *
     * @return a value in {@code [0.0, 1.0]}, never less than {@link #quickRatio()}
     */
    public double realQuickRatio() {
        int la = a.length();
        int lb = b.length();
        return calculateRatio(Math.min(la, lb), la + lb);
    }

    /** Port of the module-level {@code difflib._calculate_ratio}. */
    private static double calculateRatio(int matches, int length) {
        if (length != 0) {
            return 2.0 * matches / length;
        }
        return 1.0;
    }
}
