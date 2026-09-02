# Porting notes

This is a line-by-line port of [autoscraper](https://github.com/alirezamika/autoscraper) 1.1.14
from Python to Java 17. The goal was **functional equivalence**, not a rewrite: the Java code
reproduces the original's behaviour including its quirks, and every rule id it computes is
byte-identical to the one Python computes for the same page.

This document records what that cost, what had to be reverse-engineered, and where the two
implementations still differ.

---

## 1. How equivalence is verified

Equivalence is not asserted by eye. An oracle fixture generator runs the **real Python package** and
records its behaviour as golden fixtures under `src/test/resources/golden/`; the Java tests replay
them.

That generator is migration tooling, not part of this library, so it is archived alongside the
migration record rather than shipped here — see `oracle-tools/gen_fixtures.py` and
`oracle-tools/gen_entities.sh` in the migration deliverables. The fixtures it produced are
committed, so the suite is fully reproducible without it.

| Fixture | Rows | Locks |
| --- | --- | --- |
| `sequence_matcher.tsv` | 541 | `difflib.SequenceMatcher.ratio` to 17 significant digits |
| `pyrepr.tsv` | 42 | `repr()` output and its SHA-256, i.e. rule ids |
| `urljoin.tsv` | 260 | `urllib.parse.urljoin` |
| `unescape.tsv` | 29 | `html.unescape` |
| `normalize.tsv` | 20 | `unicodedata.normalize("NFKD", s.strip())` |
| `parse_trees.json` | 25 | the full lxml parse tree of every fixture page |
| `scenarios.json` | 36 | end-to-end behaviour, including rule ids and error messages |

Plus `PortedPythonSuiteTest`, a one-to-one port of the upstream 23-test suite.

**120 tests, all green.** Regenerate the fixtures from the archived tooling with:

```bash
PYTHONHASHSEED=42 python -W ignore gen_fixtures.py /path/to/python/autoscraper src/test/resources/golden
./gen_entities.sh python3
```

---

## 2. The upstream test suite does not test upstream

The single most important discovery. `tests/conftest.py` in the Python repo does this:

```python
sys.modules.setdefault("bs4", _stub_bs4)
sys.modules.setdefault("requests", _stub_requests)
```

Because `conftest.py` is imported before anything else, **the stub always wins**. All 23 upstream
tests run against a hand-rolled ~100-line fake parser, never against BeautifulSoup. The stub's
attribute matcher is a simplification (it joins class lists with spaces and compares for equality),
so it silently disagrees with real bs4 on multi-valued attributes and empty-attribute matching.

Running the same 23 tests against real bs4:

- **bs4 4.15.0** — 15 fail. bs4 4.13 removed the `text=` keyword that `get_non_rec_text` depends on.
- **bs4 4.12.3** — 22 pass, 1 fails.

The port therefore targets **bs4 4.12.3 + lxml**, the newest release on which upstream actually
works. The one genuine failure is documented in §7.

---

## 3. Rule ids depend on Python implementation details

A rule's identity is `sha256(str(stack_dict))[:8]`. That makes an internal `repr()` string part of
the file format, so `util/PyRepr.java` reimplements CPython's `repr` for `str`, `int`, `bool`,
`None`, `list`, `tuple` and `dict`, including:

- quote selection (prefer `'`, switch to `"` only when the string contains `'` and no `"`),
- `\x`/`\u`/`\U` escaping driven by Python's `str.isprintable()`, which is a Unicode
  general-category test, not ASCII,
- the trailing comma in 1-tuples,
- dict **insertion** order.

`model/Rule.java` builds its map in the key order `content, wanted_attr, is_full_url,
is_non_rec_text, url` for exactly this reason. Reordering those five lines changes every rule id.

### 3.1 `stack_id` is not deterministic in Python

`_get_valid_attrs` fills in missing keys by iterating a **set literal**:

```python
for attr in {"class", "style"}:
    if attr not in attrs:
        attrs[attr] = ""
```

Set iteration order depends on `PYTHONHASHSEED`, so the resulting dict's insertion order — and
therefore the hash — varies between interpreter processes. Verified on `<ul><li>Banana</li></ul>`:

| `PYTHONHASHSEED` | key order | rule id |
| --- | --- | --- |
| 0, 1, 2 | `style, class` | `rule_65f0591b` |
| 3, 42 | `class, style` | `rule_d019dc8b` |

This is an upstream bug: a model saved by one Python process can be unusable by another.

**Java is deterministic and always uses `class, style`** (the source order of the literal). Models
produced by a Python process that happened to iterate the other way will not match; rebuild them.
`scenarios_style_class.json` holds the other ordering, and `AttributeOrderInvarianceTest` asserts
against both corpora that the ordering moves rule identifiers only: 25 of the 36 scenarios differ
between the two, but once identifiers are canonicalised all 36 agree, so no scraped value depends
on it. That test also pins the port to the `class, style` ordering.

### 3.2 The three-valued booleans are `true` or `null`, never `false`

In bs4 4.12, `Tag.__getattr__` returns `self.find(tag)` for any unknown lowercase attribute rather
than raising `AttributeError`. So in `_build_stack`, `getattr(child, "is_full_url", False)` never
reaches its default — it returns `None`. The saved JSON contains `null`, and the hashed `repr`
contains `None`.

`Rule` models these as `Boolean` fields that are only ever `TRUE` or `null`. Using `false` would
change every rule id.

---

## 4. Reimplemented standard-library behaviour

Four CPython functions are load-bearing and had no faithful Java equivalent.

### 4.1 `difflib.SequenceMatcher` (`match/SequenceMatcher.java`)

Both fuzzy-matching paths compare against a 0.8 ratio, so this is not interchangeable with
Levenshtein, Jaro-Winkler or any library metric. The port reproduces the algorithm exactly,
including the **autojunk heuristic** (for sequences of 200+ elements, elements appearing in more
than 1% of positions are ignored) and the `(la, lb, 0)` sentinel block.

`SequenceMatcher.ratio(a, b)` is **not symmetric** — argument order is preserved at every call site.

### 4.2 `urllib.parse.urljoin` (`util/PyUrl.java`)

`java.net.URI.resolve` is not a substitute: it throws on relative bases and disagrees on empty
references. The port follows CPython's algorithm.

**The oracle interpreter is CPython 3.14.7, and Python 3.13 changed this function.** It now tracks
"component absent" as `None` rather than `""`, which is observable:

```
urljoin("https://example.com/index.html", "?query=2")
  3.14  -> https://example.com/index.html?query=2
  3.12  -> https://example.com/?query=2
```

`PyUrl.Split` therefore uses `null` for absent and `""` for present-but-empty. Java matches
**3.13+**. On older Pythons a query-only relative link resolves differently.

### 4.3 `html.unescape` (`util/PyUrl.java`)

HTML5 unescaping is more permissive than XML: `&amp` without a semicolon resolves, unknown
references are left alone, the longest matching prefix wins, and 34 legacy numeric references are
remapped to Windows-1252 characters. The 2231-entry reference table is dumped from CPython by the
archived entity-table generator rather than transcribed.

### 4.4 `str.strip()` (`util/TextUtils.java`)

Java's `String.strip()` uses `Character.isWhitespace`, which **rejects** U+00A0, U+2007 and U+202F
— all of which Python strips. Since scraped pages are full of `&nbsp;`, using `String.strip()`
would leave stray non-breaking spaces on extracted text. `TextUtils.strip` implements Python's
definition.

Note also that `normalize` strips **before** applying NFKD, not after. NFKD maps U+00A0 to a plain
space, so the two orders give different results.

---

## 5. Making jsoup behave like lxml (`soup/`)

The parse tree shape is part of the hash, so the parsers must agree on tree structure. jsoup and
libxml2 do not agree out of the box. Three corrections are applied in `Soup.parse`.

### 5.1 Implied elements

lxml never synthesises `<head>` or `<tbody>`; jsoup always does. `<table><tr><td>Cell</td></table>`
gives lxml `table > tr > td` and jsoup `table > tbody > tr > td`.

But lxml *keeps* those tags when they were written explicitly, so they cannot simply be deleted.
The discriminator is jsoup's position tracking: an element the parser invented has a **zero-width
source range** (`start().pos() == end().pos()`). With `Parser.setTrackPosition(true)`, implied
`head` and `tbody` elements are dropped and their children spliced into the parent. `html` and
`body` are never dropped, since lxml creates those too.

### 5.2 Whitespace-only text nodes

libxml2 collapses a text node made entirely of `space \t \n \r \f` to a **single** character —
`"\n"` if the run contained a newline, otherwise `" "` — and folds `\r` to `\n` everywhere. Mixed
text is never touched: `"a  b"` and `"\n   hello  \n"` survive intact. The collapse is suppressed
anywhere inside `<pre>` or `<textarea>`, at any depth.

This matters because indentation in the source HTML changes an element's child count, which
`Tag.__eq__` compares, which decides sibling indices, which are hashed.

### 5.3 Multi-valued attributes

bs4 splits certain attributes into token lists using `re.findall(r"\S+")`, which **drops empties**:
`class=""` becomes `[]`, not `[""]`. The full per-tag table (`class`, `rel`, `headers`,
`accept-charset`, …) is reproduced in `Soup`. `AttrValue` is a sealed type with `Text` and `Tokens`
cases so the distinction survives into the hash.

### 5.4 `get_text()` excludes comments, `get_non_rec_text()` includes them

bs4 types every string by its parent tag and `get_text()` only collects strings whose type matches
the caller's. The consequence is genuinely surprising:

```
<div>a<!--HIDDEN-->b</div>
  div.get_text()        -> "ab"
  get_non_rec_text(div) -> "aHIDDENb"
```

`<script>` and `<style>` contents are likewise invisible to an ancestor's `get_text()` but visible
to the tag's own. `SoupElement.getText()` and `directText()` reproduce both behaviours via
`StringKind`.

`getText()` also concatenates raw text with **no separator and no whitespace collapsing**, unlike
jsoup's `Element.text()`.

### 5.5 Structural equality

`_build_stack` finds an element's sibling index with `if c == parent: break`, and bs4's `Tag.__eq__`
is **recursive structural equality**, not identity. Two identical `<li>Banana</li>` siblings are
equal, so the second one's index is computed as 0. `SoupElement.pyEquals` implements this; it is
the mechanism behind duplicate-rule collapse in §7.

---

## 6. Preserved upstream quirks

Each of these is locked by a test named after it in `ScenarioGoldenTest`.

| Quirk | Behaviour |
| --- | --- |
| `url` leak | In `_get_result_by_func`, `if not url: url = stack.get("url")` assigns to the loop variable, so the first rule carrying a URL supplies it to **all later rules** in the same call. |
| `unique` tri-state | Unset means `True` when ungrouped and `False` when grouped. `unique` is a nullable `Boolean`, not a `boolean`. |
| `wanted_list` wins | Passing both `wantedList` and `wanted(alias, …)` silently discards the dict; the list is filed under alias `""`. |
| alias is not hashed | `stack["alias"] = alias` runs *after* the hash is computed, so aliasing never changes a rule id. |
| empty `html` fetches | `if html:` is falsy for `""`, so an empty string falls through to a network request for `url` — which, if `url` is null, produces the requests error message `Invalid URL 'None': No scheme supplied.` |
| `headers` is consumed | `_fetch_html` calls `request_args.pop("headers")`, mutating the caller's dict. `RequestArgs.takeHeaders()` reproduces this; use `copy()` to opt out. |
| missing key asymmetry | The similar extractor reads `is_non_rec_text` with `.get(...)` but the exact extractor uses `[...]`. See §7. |
| `content[index-1]` | When a rule's path does not begin at `[document]`, Python's negative indexing silently wraps to the last element. Java reproduces the wrap and throws `IllegalStateException` if that element carries no index, where Python raises `TypeError`. |

---

## 7. Known divergences

Four, all deliberate and all documented in code.

1. **`test_similar_unique_false`.** Upstream asserts `["Banana", "Banana"]` for
   `<ul><li>Banana</li><li>Banana</li></ul>`. That holds only under the stub parser. With real bs4
   the two `<li>` elements are structurally equal, hash identically, and `unique_stack_list`
   collapses them to one rule — so the real answer is `["Banana"]`, and `["Banana", "Banana"]`
   requires `containSiblingLeaves(true)`. `PortedPythonSuiteTest.similarUniqueFalse` asserts the
   real-bs4 behaviour and pins the cause by asserting the rule count is 1.

2. **Exception types.** Java raises `IllegalArgumentException` where Python raises `ValueError` or
   `KeyError`; messages are preserved verbatim. `setRuleAliases` with an unknown id throws
   `IllegalArgumentException(ruleId)` rather than `KeyError`.

3. **Missing `is_non_rec_text` key.** In Python, a hand-edited model file lacking this key works in
   `get_result_similar` but raises `KeyError` in `get_result_exact`. Java reads a missing key as
   `null` in both paths. Reproducing the asymmetry would require tracking key presence separately
   from value, which was judged not worth the model complexity.

4. **`PYTHONHASHSEED`.** See §3.1. Java is deterministic where Python is not.

---

## 8. Runtime requirements

- **Java 17+.** The build targets `--release 17`; note that pattern matching in `switch` is a
  Java 21 feature and is deliberately not used.
- **`-Djdk.httpclient.allowRestrictedHeaders=host`** is required when fetching by URL. `_fetch_html`
  sets an explicit `Host` header, which `java.net.http.HttpClient` refuses by default. The flag is
  already set in the Surefire configuration; applications that call `build(url=…)` must set it too.
- Character-set detection uses juniversalchardet to stand in for `requests`' `apparent_encoding`,
  applied under the same condition (declared ISO-8859-1 that the `Content-Type` header does not
  actually mention).

---

## 9. Map of the port

| Python | Java |
| --- | --- |
| `AutoScraper` | `AutoScraper` |
| `_build_stack`, `_child_has_text`, `_get_children`, `_get_valid_attrs` | `internal/StackBuilder` |
| `_get_result_with_stack`, `_get_result_with_stack_index_based`, `_fetch_result_from_child`, `_get_fuzzy_attrs`, `_clean_result` | `internal/ResultExtractor` |
| `_fetch_html` | `net/HtmlFetcher` |
| `utils.normalize`, `get_non_rec_text`, `unique_hashable` | `util/TextUtils` |
| `utils.text_match` | `match/TextTarget.matches` |
| `utils.FuzzyText` | `match/FuzzyText` |
| `utils.ResultItem` | `model/ResultItem` |
| stack dicts | `model/Rule`, `model/StackContentNode` |
| `bs4` | `soup/` |
| `difflib` | `match/SequenceMatcher` |
| `repr` | `util/PyRepr` |
| `urllib.parse`, `html.unescape` | `util/PyUrl` |
| keyword arguments | `options/` builders |
