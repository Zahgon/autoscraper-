# AutoScraper for Java

A Java 17 port of [autoscraper](https://github.com/alirezamika/autoscraper) — smart, automatic,
fast and lightweight web scraping.

You do not write selectors. You give it a page and some example values you want from it, and it
learns the rules to extract them. Point it at a similar page afterwards and it finds the
equivalent data.

This is a faithful port: rule ids, saved model files and extraction results are identical to the
Python original's. See [PORTING-NOTES.md](PORTING-NOTES.md) for what that involved.

---

## Install

```xml
<dependency>
    <groupId>io.github.autoscraper</groupId>
    <artifactId>autoscraper</artifactId>
    <version>1.1.14</version>
</dependency>
```

Requires Java 17 or newer.

---

## Getting similar results

Say you want all the related post titles on a Stack Overflow page:

```java
import io.github.autoscraper.AutoScraper;
import io.github.autoscraper.options.BuildOptions;

AutoScraper scraper = new AutoScraper();

List<String> result = scraper.build(BuildOptions.builder()
        .url("https://stackoverflow.com/questions/2081586/web-scraping-with-python")
        .wantedList("What are metaclasses in Python?")
        .build());
```

Give it **one** example. The result contains every value the learned rules matched:

```
['How do I merge two dictionaries in a single expression in Python (taking union of dictionaries)?',
 'How to call an external command?',
 'What are metaclasses in Python?',
 ...]
```

Now run the same rules against a different page:

```java
import io.github.autoscraper.options.SimilarOptions;

scraper.getResultSimilar(SimilarOptions.builder()
        .url("https://stackoverflow.com/questions/606191/convert-bytes-to-a-string")
        .build())
        .asList();
```

> **Note.** Fetching by URL requires the JVM flag `-Djdk.httpclient.allowRestrictedHeaders=host`,
> because the scraper sends an explicit `Host` header. Passing `.html(...)` instead needs no flag.

---

## Getting exact results

To pull a specific value from the same position on many pages — a live price, say:

```java
AutoScraper scraper = new AutoScraper();

scraper.build(BuildOptions.builder()
        .url("https://finance.yahoo.com/quote/AAPL/")
        .wantedList("124.81")
        .build());

// Reuse on any other ticker
scraper.getResultExact(ExactOptions.builder()
        .url("https://finance.yahoo.com/quote/MSFT/")
        .build())
        .asList();
```

`getResultExact` follows the learned path by position and returns at most one value per rule.
`getResultSimilar` explores every branch and returns everything that matches.

---

## Multiple targets, grouped by name

Use aliases when you want several fields at once:

```java
import io.github.autoscraper.match.TextTarget;

scraper.build(BuildOptions.builder()
        .url("https://example.com/product")
        .wanted("title", List.of(new TextTarget.Literal("Acer Laptop")))
        .wanted("price", List.of(new TextTarget.Literal("US $1,229.49")))
        .build());

Map<String, List<String>> byField = scraper.getResultSimilar(SimilarOptions.builder()
        .html(html)
        .groupByAlias(true)
        .build())
        .asMap();
// {"title": [...], "price": [...]}
```

To inspect which rule produced what, group by rule id instead:

```java
scraper.getResultSimilar(SimilarOptions.builder().html(html).grouped(true).build()).asMap();
// {"rule_424f7105": [...], "rule_b3db34a9": [...]}
```

Then keep only the rules you trust:

```java
scraper.keepRules(List.of("rule_424f7105", "rule_b3db34a9"));
scraper.removeRules(List.of("rule_810cdd85"));
scraper.setRuleAliases(Map.of("rule_424f7105", "title"));
```

---

## Regular expressions and fuzzy matching

Match a target by pattern rather than by literal text:

```java
scraper.build(BuildOptions.builder()
        .html(html)
        .wantedTargets(List.of(new TextTarget.Regex(Pattern.compile("Sony PlayStation.*"))))
        .build());
```

When a page's text or attributes drift slightly between visits, relax the thresholds:

```java
// Tolerate small differences in the target text while learning
.textFuzzRatio(0.8)

// Tolerate small differences in class/style attributes while extracting
.attrFuzzRatio(0.8)
```

Both use Python's `difflib` similarity ratio, ported exactly, so thresholds transfer over
unchanged from existing Python code.

---

## Saving and loading models

```java
scraper.save(Path.of("model.json"));

AutoScraper loaded = new AutoScraper();
loaded.load(Path.of("model.json"));
```

The file format is **byte-compatible with the Python version** — models are interchangeable in
both directions. Files written by very old releases, which stored a bare JSON array, also load.

---

## Result options

| Option | Default | Effect |
| --- | --- | --- |
| `unique(Boolean)` | unset | Deduplicate. Unset means *on* for flat results, *off* for grouped ones. |
| `keepOrder(boolean)` | `false` | Return values in document order. |
| `keepBlank(boolean)` | `false` | Keep empty and missing values instead of dropping them. |
| `containSiblingLeaves(boolean)` | `false` | Return all matching siblings at the final step, not just one. |
| `grouped(boolean)` | `false` | Group results by rule id. |
| `groupByAlias(boolean)` | `false` | Group results by alias. |
| `attrFuzzRatio(double)` | `1.0` | Attribute similarity threshold. |

`getResult(ResultOptions)` returns both result kinds at once as a `ResultPair`, parsing the page
only once.

---

## Custom request settings

```java
import io.github.autoscraper.options.RequestArgs;

RequestArgs args = new RequestArgs()
        .header("Cookie", "session=abc123")
        .timeout(Duration.ofSeconds(10));

scraper.build(BuildOptions.builder().url(url).wantedList("...").requestArgs(args).build());
```

Default headers live in the mutable static `AutoScraper.REQUEST_HEADERS`.

> Matching the original, a `RequestArgs` instance is **consumed** by the request — its headers are
> removed as they are applied. Call `args.copy()` to reuse one.

---

## Differences from the Python version

Fully catalogued in [PORTING-NOTES.md](PORTING-NOTES.md). In brief:

- Java throws `IllegalArgumentException` where Python throws `ValueError` or `KeyError`; the
  messages are identical.
- Rule ids are deterministic here. In Python they depend on `PYTHONHASHSEED` and can differ
  between runs; this port always uses the `class, style` ordering.
- One upstream test asserts behaviour that only holds under the stubbed parser in its own test
  suite. This port matches real BeautifulSoup instead.

---

## Building

```bash
mvn verify
```

Runs 72 tests, including 36 end-to-end scenarios and roughly 900 golden fixtures recorded from the
running Python package.

---

## License

MIT, as the original. See [LICENSE](LICENSE).
