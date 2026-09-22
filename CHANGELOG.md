# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); the version follows
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## 0.1.4 — 2026-09-22

### Added — the snapshot remembers how it was cut
`--stopwords` and `--no-mining` change which terms exist, so restoring a snapshot under different flags
means the query is tokenized differently from the stored postings. The dictionary case (0.1.3) failed
loudly by returning nothing; this one only shifts rankings, which is harder to notice and easier to
spend an afternoon on. The snapshot now records the term-stream switches it was built with, and a
restore that disagrees warns with both sides named and the `--rebuild` escape:

```
WARN snapshot was cut with stopwords=false,mining=true, this run is stopwords=true,mining=true
     -- results will differ from the run that wrote it; pass the same flags, or --rebuild to re-cut
```

Old files carry no such record, so they stay quiet rather than warning about every snapshot written
before this existed.

## 0.1.3 — 2026-09-22

### Fixed — a custom dictionary that vanished on restart
`--dict` words were loaded into the analyser, used to cut every document, and then written nowhere.
A restart without the flag restored the postings but re-split the query into single characters, so
`search 貔貅` returned an empty list for a term that was sitting in the index -- no error, no warning,
just the impression that the corpus is empty. The words now go into their own snapshot section
(`udict`, absent-and-harmless for files written before this), are re-added before the lexicon seals,
and the restore says so when it is the reason a query still works.

Reproduced from the CLI before fixing and re-run after: index one document whose rare word appears a
single time (below the mining threshold, mining off), restart without `--dict`, search it -- empty
before, one hit after. `IndexTest.customDictionarySurvivesTheSnapshot` covers it, and was checked to
fail when the restore side is pointed at a section name that does not exist.

## 0.1.2 — 2026-09-22

### Added — the README can no longer lie about its own repo
`scripts/check-claims.sh` re-counts the tree and compares it against every self-referential number in
the two READMEs, the launch copy and the article: per-module line counts, main-code total, file count,
test lines, test cases, UI lines, longest single file. CI runs it as a build step.

### Added — the crawler stops being a request the server will make for you
`crawl`, `index http://…` and `POST /api/crawl` now check the target before anything leaves the
process, `robots.txt` included: non-http(s) schemes, names that do not resolve, and loopback,
`0.0.0.0/8`, link-local, RFC 1918, carrier-grade NAT, IPv6 unique-local and multicast addresses are
refused with a reason in the result. A redirect that lands in one of those ranges is refused as well,
so the body cannot be indexed and read back through `/api/search`. `--allow-private` (CLI) and
`--allow-private-crawls` (server) opt a whole process back in; they are not per-request flags on
purpose. `SECURITY.md` writes down the threat model and what is still open (DNS rebinding, no TLS, no
rate limiting, snapshot files are trusted input). `CONTRIBUTING.md` writes down the one rule the rest
of this changelog keeps having to restate.

### Fixed — a search and an index write could run at the same time
`Engine`'s own comment claimed readers need no lock because they only take the published `Searcher`.
The `Searcher` is stable; the `InvertedIndex` it points at is mutated in place by `POST /api/index`,
`/api/crawl` and `DELETE`, so a query could walk a postings map mid-update. There is now one
`ReentrantReadWriteLock`: writes take the write side, a query takes the read side for its duration, and
`/api/doc`, `stats` and `save` go through it too. Searches stay parallel with each other.

`ConcurrencyTest` runs 6 readers and 2 writers for 1.5 s. It is reported honestly: with the read side
removed the test still passes here, because the Java memory model's bad outcome is not guaranteed to
materialise on x86 in two seconds. The lock is there because the semantics should not depend on which
machine compiles the project, and `CONTRIBUTING.md` says so rather than implying a greener test.

### Fixed — the crawler fetched every page twice
`crawl()` re-fetched each page to harvest its links after `fetchAndIndex()` had already downloaded and
parsed it. That doubled the request rate against every host and bypassed the per-host politeness
interval for the second one, which is the exact promise this class exists to keep. Links now come out
of the body that was already fetched; `InterfaceTest` counts requests on the local server to keep it
that way.

### Fixed — a bad request answered like a server that is down
`POST /api/index` with a truncated JSON body returned nothing at all: `curl` reported `000`, no status
line, because `Json.parseObject` threw out of a handler that nothing was wrapped in, and the JDK server
just closed the socket. Same for a body of plain garbage, and for `POST /api/crawl` with no body at all
-- which is what forgetting `-d` looks like. Every route is now wrapped: malformed input is a 400 with
the reason, an unexpected failure is a 500, the worker survives, and the client can tell "you sent me
junk" from "the process is gone".

The parser itself was the worse half of that. It is recursive descent with no depth limit, so
60,000 nested arrays in a 120 KB body produced a `StackOverflowError` -- an `Error`, which is why the
`catch (RuntimeException)` in the MCP path never saw it coming and why the stdio loop was one malformed
frame away from dying. Depth is capped at 96 levels now, which is far past anything this engine's own
payloads reach. All four cases are asserted end to end against a live server, and the JSON test asserts
the *exception type*, since passing on a `StackOverflowError` would have looked like a pass.

### Fixed — the HTTP surface, one pass further
- `POST /api/index` and `POST /api/crawl` read the whole request body with `readAllBytes()`. On a
  server whose write endpoints have no authentication, that is an unbounded memory grant: a 2 GB body
  is a 2 GB heap. Now capped at 8 MiB, answered with 413, and the cap is what the test asserts.
- `from=-1` reached `ranked.get(-1)`, i.e. a client typo became a 500. `topK` and `from` are clamped
  (`[1, POOL]` and `[0, ∞)`); the response for a nonsense window is an empty page, not a stack trace.
- `Highlighter.ellipsize` returned raw text while every other path returned escaped text with `<em>`
  markers -- an inconsistency one call away from being a stored-XSS route through the search page. It
  escapes now, and the UI only renders `http(s)` URLs as links, since the `url` field is whatever an
  unauthenticated `POST /api/index` put there (`javascript:` used to be clickable).
- `docker-compose.yml` published `9200:9200`, i.e. every host interface, while the whole pitch is that
  this thing stays on your machine. It now maps `127.0.0.1:9200:9200`, with `MS_HOST=0.0.0.0` as the
  explicit way out and `MS_XMX` exposed for the 1 GB heap ceiling that SECURITY.md now describes
  accurately (it claimed the heap is unbounded, which the compose file has been contradicting).
- The stdio MCP server advertised an `index_url` tool it could never serve, because nothing attached a
  crawler to it. It now runs with one under the same target policy as every other network path, and
  `tools/list` only offers `index_url` when a crawler is actually present.

### Fixed — a second pass over the claims
- Those numbers had drifted: the READMEs advertised 6,005 main lines / 53 cases / a 213-line UI while
  the tree held 6,104 / 57 / 252, and the article still carried the pre-M8 `vector/` size.
- The article claimed "每层都不到 800 行", which `vector/` at 1,086 lines contradicts. It now claims the
  smaller true thing — longest single file, currently 577 lines — and the checker enforces that too.
- The Scale table's stage times came from a different run than the committed `bench.json`, and one of
  them was mislabelled outright: "HNSW build 156 s" was the vector layer's *cumulative* time. `bench`
  now writes every stage into the artifact (`word2vec`, `vectorIndex`, `vectorLayer`, …) and
  `check-claims.sh` compares the README's Scale table against that file, so a stage name cannot be
  attached to the wrong number again. Current run: 304 s build, 15.6 s index, 14.7 s graph.
- Latency is quoted with the three runs that were actually measured, and "ten-millisecond queries"
  became "tens of milliseconds" — the 10.3 ms p50 in the old artifact does not reproduce here.

## 0.1.1 — 2026-09-21

Found by running every documented command on a real machine rather than reading the code.

### Changed — **the server no longer listens on every interface**
`serve` binds `127.0.0.1` by default and takes `--host` for the cases that mean it, warning when the
bind is not loopback. It previously used `new InetSocketAddress(port)`, i.e. the wildcard address,
behind unauthenticated writes: `POST`/`DELETE /api/index` rewrite the index and `POST /api/crawl`
points the crawler at any URL the caller names. **If you reached this service from another machine,
start it with `--host 0.0.0.0`.** The Docker image already does, because inside a container the
loopback interface is unreachable from the host and a published port would map to nothing.

### Fixed
- `HttpApi` captured `engine.searcher()` in its constructor and never re-read it, so a running server
  kept answering from a searcher frozen at startup: documents indexed through the admin page were
  searchable only via the shared index, while `refresh=true` never switched the vector or thesaurus
  layers on for the HTTP path. It now takes the live searcher per request.
- `Engine` had no synchronisation at all while the HTTP server runs a fixed thread pool, so
  concurrent admin writes or a crawl plus a delete mutated the inverted index and the vector map
  underneath each other. Mutating entry points are now `synchronized`; the published `searcher` is
  `volatile`, so readers still take no lock.
- `eval` measured whatever was lying in `data/`. It opened the engine through the server's path, so a
  leftover `index.msnap` — one indexed with the optional bge model, say — silently flattened all three
  modes onto bm25's row. `eval` now builds from the corpus, ignores any snapshot and writes none, and
  says so on stderr.
- `build.sh`, the advertised "zero-Maven developer loop", could not compile when
  `libs/onnxruntime.jar` was absent — which is every fresh clone, since `libs/` is gitignored — because
  `Engine` named `OnnxEncoder` directly in three places. The optional class is now reached through
  `Encoder.loadPretrained(...)` and `Encoder.pretrained()`.
- The generated `data/eval/report.md` mixed CRLF and LF on Windows (two rows were built with
  `%n`, the rest with literal `\n`), so a clean regeneration showed as a diff even when every number
  matched. It is now byte-identical across platforms.

### Added
- The repository's first HTTP tests: index → search in all three modes → delete round trips against a
  live server, the 400/404 paths, and two tests pinning the bind address. 57 tests, up from 53.

## 0.1.0 — 2026-09-21

First release. A local-first hybrid search engine for Chinese in one 214 KB jar with no runtime
dependencies: analyser with statistical new-word mining, inverted index with varbyte postings, BM25,
corpus-trained word vectors, HNSW, distributed thesaurus, RRF fusion, polite crawler, CRC-checked
snapshot, web UI, MCP server over stdio. Quality and scale are measured, not asserted —
`mini-search eval` and `mini-search bench`, both outputs committed under `data/eval/`.
