# Contributing

Java 17 and nothing else. There is no npm, no docker-compose required to work on the code, and no
service to stand up. If a change needs any of those to be reviewable, it is the wrong change for this
repo -- say so in the issue before building it.

## The one rule

**Every number in this repository has to be reproducible by someone who has only the source.** The
README's quality tables, the article's timings, the jar size, the line counts: they are claims, and a
claim without a command behind it is marketing. `scripts/check-claims.sh` exists to keep the boring
ones honest (it re-counts the tree and diffs it against every table), and `mini-search eval` /
`mini-search bench` exist so the interesting ones can be re-run.

Concretely, if you change retrieval, you re-run the evaluation and commit the report. If you change
segmentation, you regenerate the golden master and read the diff. If you add a claim to a README, the
build tells you whether the number is true.

## Getting a working loop

```bash
./build.sh                            # javac, no Maven, ~5 s: target/classes
java -cp target/classes dev.jingyu.ms.MiniSearch search "中文分词"
java -cp target/classes dev.jingyu.ms.MiniSearch eval
```

Maven is only needed for the test suite (JUnit 5) and the shaded jar:

```bash
mvn -B test                           # 68 cases, no network
mvn -B -DskipTests package            # target/mini-search.jar
bash scripts/check-claims.sh          # every advertised count vs. the tree
scripts/regold.sh                     # only after an intentional analyser change
```

CI runs those plus a jar-size guard, a fresh `eval` compared byte-for-byte against the committed
`data/eval/report.md`, and a smoke test that runs the jar from `/tmp` with no corpus directory in
sight. The last one is not ceremony: "works on my machine" usually means "found the data directory by
accident".

To run a CI step the way CI runs it, extract the `run:` block and execute it with `bash -e` -- GitHub
uses that shell, and a step that only passes interactively is a step that cannot fail.

## What the code is allowed to depend on

The runtime jar has zero dependencies, and `pom.xml` has exactly two non-build entries: JUnit in
`test` scope and ONNX Runtime in `provided` scope. The second one is load-bearing for the whole design:
it means the pretrained-embedding path is a flag plus a download, and that `java -jar mini-search.jar`
still works with no weights, no network and no runtime library. Keep it that way.

Tests never touch the network. The crawler tests run against a `HttpServer` on `127.0.0.1` inside the
test, and the crawl target policy is explicitly relaxed there (`UrlPolicy.ALLOW_PRIVATE`) because the
production default refuses loopback on purpose. If your test needs the internet, it is not a test.

## Style that is worth keeping

- Comments and identifiers in English; user-visible strings in Chinese.
- No framework, including a logging one: `util/Log` is 31 lines and prints to stderr.
- A failure is a value with a reason (`CrawlResult{ok:false, reason:"…"}`), not a thrown exception, in
  anything that walks a corpus -- one bad page must not end a crawl.
- Readability beats micro-optimisation. The biggest package is `vector/` at 1,086 lines across seven
  files and the biggest single file is `core/Engine.java` at 577; if a file grows past roughly 600
  lines, split it or explain in the commit message why not.
- New behaviour gets a test that would fail without it. Where a check cannot be made to fail (a race
  that does not reproduce on x86, for instance), the test says so rather than implying it proves more
  than it does.

## Proposing a change

Open an issue first for anything that changes retrieval quality, the on-disk snapshot format, or the
CLI surface. The format point matters: `Snapshot.VERSION` is what gets bumped when the layout changes,
and a file whose version or checksum does not match is refused and rebuilt from the corpus rather than
half-loaded -- so a format change is a compatibility decision, not a refactor.

PRs that fix a bug get priority over PRs that add a feature, and a PR that deletes code and keeps the
tests green is the most welcome kind there is.
