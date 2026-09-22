# mini-search

A local-first hybrid search engine for Chinese. Crawler, Chinese analyser, inverted index, BM25, word vectors, HNSW, RRF fusion, web UI — all written by hand, packaged as one 224 KB jar with no runtime dependencies.

```bash
java -jar mini-search.jar          # then open http://localhost:9200
```

The demo corpus ships inside the jar, so it works with the network unplugged. If you would rather not build it, download the file; Java 17 is the only requirement:
<https://github.com/JingYu-create520/mini-search/releases/latest/download/mini-search.jar>

![demo](docs/demo.gif)

Every query in that recording is a real request. The page runs the tour itself at `http://localhost:9200/?tour=1`; `scripts/record-demo.sh` grabs the frames. To land on one query directly: `?q=索引落盘为什么要带校验&mode=bm25`.

[中文 README](README.zh-CN.md) · [design doc](docs/PLAN.md) · [evaluation report](data/eval/report.md) · [benchmark](data/eval/bench.json) · [security](SECURITY.md) · [contributing](CONTRIBUTING.md)

---

## The gap this fills

Chinese search tutorials usually stop at "it runs", and industrial engines are too heavy to read end to end. Nothing sits in between: something you can demo on the spot and still explain layer by layer.

So there are no third-party libraries here. HTTP comes from the JDK, and the JSON parser, analyser, HNSW graph and main-text extractor are all in this repo. Not a preference for wheel-spinning — import Lucene and "every layer is readable" stops being true.

The other rule was that every claim needs a number you can reproduce. `mini-search eval` for quality, `mini-search bench` for scale, both outputs committed under `data/eval/`. Break the ranking and the table shows it.

What it cannot do is listed at the end, in the open, rather than three commits down in an issue thread.

## Measured quality — 38 hand-labelled queries, k=5

Out of the box (zero dependencies, no model):

| mode | recall@5 | precision@5 | nDCG@5 | MRR | queries with a top-5 hit |
|---|---|---|---|---|---|
| bm25 | 0.934 | 0.254 | 0.912 | 0.918 | 36/38 |
| semantic (thesaurus expansion) | 0.934 | 0.216 | 0.899 | 0.898 | 36/38 |
| hybrid (RRF) | **0.934** | 0.216 | **0.916** | **0.923** | 36/38 |

With the optional local model (`scripts/fetch-model.sh`, 24 MB bge-small-zh in ONNX, still offline):

| mode | recall@5 | nDCG@5 | MRR | queries with a top-5 hit |
|---|---|---|---|---|
| bm25 | 0.934 | 0.912 | 0.918 | 36/38 |
| hybrid | **0.987** | 0.955 | 0.954 | **38/38** |
| vector | **0.987** | **0.957** | 0.961 | **38/38** |

Split by query type, where `semantic` queries are written so the target document **does not contain the query words** (no-model run):

| | bm25 | semantic (thesaurus) | hybrid |
|---|---|---|---|
| lexical-match queries | 0.981 / 0.983 | 0.981 / 0.986 | 0.981 / 0.983 |
| word-mismatch queries | 0.818 / 0.739 | 0.818 / 0.687 | 0.818 / **0.751** |

Read the two rows together: the thesaurus is what wins on the lexical row and what loses worst on the
word-mismatch row, and `hybrid` keeps bm25's recall while taking the nDCG gain where it matters. Fusion
is not a free lunch — the weights `{1, 0.5, 6}` were swept for this shape, and in the with-model run
equal weights drag hybrid to 0.927, below the 0.957 the vector list reaches on its own.

Two queries miss entirely on the zero-dependency path: *"does a child's persistent fever mean a bacterial infection"* (target: a document about cold vs. flu) and *"why are coastal cities mild in winter"* (target: a document about specific heat capacity). That is a genuine synonym-knowledge gap that neither lexical matching nor a corpus-derived co-occurrence table closes. **Both come back once the local bge model is loaded — 38/38.**

Reproduce:

```bash
mvn -B package && java -jar target/mini-search.jar eval
scripts/fetch-model.sh
java -cp "target/mini-search.jar;libs/onnxruntime.jar" dev.jingyu.ms.MiniSearch \
     eval --model models/bge-small-zh-v1.5 --out data/eval/report-bge.md
```

Both reports are committed: `data/eval/report.md` and `data/eval/report-bge.md`. The fusion weights are swept, not guessed (`--fusion 1,0.5,6`); the reasoning is in the next section.

## Scale — 50,000 documents

One run of `bench --docs 50000`, committed as `data/eval/bench.json`. Every number below is in that
file, stage times included:

| | measured |
|---|---|
| engine build (everything under it) | 304 s = 164.3 docs/s |
| — dictionary mining | 41.9 s |
| — inverted index build | 15.6 s |
| — word2vec training (2 epochs, 103M updates, 30k vocab) | 211.3 s |
| — HNSW graph (m=16, efConstruction=200) | 14.7 s |
| — vector layer: training + graph + tokenising | 234.4 s |
| — thesaurus build | 12.3 s |
| BM25 query latency | p50 15.5 ms · p95 48.9 ms |
| hybrid query latency | p50 28.7 ms · p95 107.9 ms |
| heap in use after the build | 1.9 GB (JVM ceiling 8 GB) |

Same jar, same laptop, three runs an hour apart: 304 s / 338 s / 340 s of build, BM25 p50
15.5 / 20.7 / 16.4 ms. Read that as "tens of milliseconds with everything in RAM, ±20%", not as a
figure to reproduce to the decimal — the machine had other things on it and the artifact is one sample.

Two things this table used to say and no longer does. "HNSW build 156 s" was the vector layer's
*cumulative* time mislabelled; the graph itself is 14.7 s. And "index build 13.6 s" came from a run
whose artifact said 201 s overall, which nobody noticed because the stage times only ever went to
stderr. They are in the JSON now, so the docs cannot drift away from the measurement again.

Single machine, zero dependencies, laptop. That is the entire performance promise — this is not an
industrial benchmark.

## How many lines is each layer

| layer | lines | what lives there |
|---|---|---|
| `analyzer/` | 695 | normalisation, bidirectional maximum matching, mixed CN/EN cutting, statistical new-word mining (NPMI cohesion + branch entropy) |
| `index/` | 479 | inverted index, varbyte postings, positions, delete bitmap, phrase merge |
| `ranking/` | 82 | BM25 with tunable k1/b and per-field boosts |
| `hybrid/` | 76 | RRF and weighted score fusion |
| `vector/` | 1086 | corpus-trained SGNS word vectors, brute-force k-NN, HNSW, `Encoder` SPI |
| `semantic/` | 145 | distributed thesaurus (co-occurrence cosine) — the semantic model that works on small corpora |
| `search/` | 447 | query orchestration, four modes, highlighting |
| `crawl/` | 863 | polite crawler, robots, 64-bit dedupe, charset detection, link-density main-text extraction, a target policy that refuses private addresses |
| `api/` | 404 | routes and static assets on the JDK HTTP server |
| `core/` | 992 | engine assembly, CRC-checked snapshot, corpus loading, the read/write guard |
| `eval/` | 358 | recall / precision / nDCG / MRR, scale benchmark |
| `mcp/` | 200 | MCP server over stdio JSON-RPC |
| `util/` + CLI | 725 | JSON, varbyte, logging, commands |
| **main** | **6,552** | 39 files |
| tests | 1562 | 68 cases |
| UI | 255 | single-file search page + index admin page |

## Architecture

```
URL ─► robots ─► rate limit ─► fetch ─► charset ─► main text ─┐
                                                               ▼
                     analyser (dictionary + mined words) ─► inverted index
                                                               │
                                                               ├─► BM25 ─────────────┐
                                                               ├─► thesaurus expand ──┤
        corpus ─► word vectors(SGNS) ─► HNSW ─► vector recall ─┘                      ▼
                                                                            RRF ─► highlight ─► JSON / UI / MCP
```

The whole query path is one file: [`search/Searcher.java`](src/main/java/dev/jingyu/ms/search/Searcher.java), about 240 lines.

## Usage

```bash
java -jar mini-search.jar index my-notes.jsonl        # {id,title,body,url,tags} per line
java -jar mini-search.jar crawl https://example.com --max 30 --depth 2
java -jar mini-search.jar search "inverted index" --mode hybrid
java -jar mini-search.jar analyze "中文分词" --dict company.dict
java -jar mini-search.jar eval                        # the table above
java -jar mini-search.jar bench --docs 50000
java -jar mini-search.jar mcp                         # MCP server on stdio
```

Index state persists to a CRC-checked snapshot (`data/index.msnap`). A corrupt or stale snapshot is detected and rebuilt rather than half-trusted. The words from `--dict` travel with it as well: they decided how your documents were cut, so a restore that forgot them would split a query differently from the postings and hand back an empty list for a word that is plainly in the index.

### HTTP API

```
GET    /api/search?q=倒排索引&mode=hybrid&topK=10&phrase=true
GET    /api/doc?id=tech-001
POST   /api/index      {"id":"note-1","title":"…","body":"…","tags":"a,b"}
POST   /api/crawl      {"url":"https://example.com/page"}
DELETE /api/index?id=note-1
GET    /api/stats
```

Every hit carries an `explain` object with its BM25 score, thesaurus score and cosine, so you can verify the ranking against the source instead of trusting it.

### MCP

```json
{ "mcpServers": { "mini-search": {
    "command": "java", "args": ["-jar", "/absolute/path/mini-search.jar", "mcp"] } } }
```

Tools: `search`, `index_url`, `index_text`, `stats`. Companion skill: [`skills/mini-search/SKILL.md`](skills/mini-search/SKILL.md).

### Docker

```bash
docker compose up --build      # http://localhost:9200
```

The server binds `127.0.0.1` by default — `serve --host 0.0.0.0` opts into the network, which the container
image does on purpose (inside a container the loopback interface is unreachable from the host, so a published
port would connect to nothing). Reach for it only when you mean it: `/api/index` and `/api/crawl` are
unauthenticated writes.

The published mapping is host-loopback only (`127.0.0.1:9200:9200`), so `compose up` does not by itself put
the engine on the LAN — `MS_HOST=0.0.0.0` says you want that. `MS_XMX` moves the 1 GB heap ceiling that
keeps a runaway crawl from eating the container.

> Verified on this machine: `docker build` produces a 425 MB image, `docker compose up -d` starts it, and `:9200` serves both the stats and the search API with CJK intact. The first build is slow because Maven downloads its dependencies inside the container; the second one hits the cache.

## What each layer actually does

### Analyser: if the dictionary is not enough, grow words out of the corpus

The shipped dictionary is ~1,900 general entries — it cannot know your domain. The work is done by statistical new-word mining, which scores every character n-gram on three signals: **frequency**, **cohesion** (normalised pointwise mutual information, minimised over the string's internal splits), and **freedom** (entropy of the characters observed on each side, taking the more constrained side).

Using NPMI rather than raw PMI is the point: raw PMI drifts with corpus size, so any fixed threshold stops working when the corpus changes — the most common failure in hand-rolled segmenters. NPMI is scale-free and lands in [-1, 1].

`AnalyzerTest` pins 62 mixed sentences against a golden master. Changing the analyser requires regenerating it and reading the diff.

### Ranking: three knobs, and one bug worth telling you about

`k1` controls how much a tenth occurrence helps; `b` controls the length penalty; IDF lets rare terms speak. Title/body/tags share one index with boosts of 3/1/2.

IDF's denominator must be the **document count across fields after de-duplication**. It was first written as the *field* count, which quietly flattened IDF and degraded ordering. `IndexTest.rareTermDominates` caught it — not a code review.

### <a name="the-vector-layer-honest-about-its-own-data-requirement"></a>The vector layer, honest about its own data requirement

**On 84 documents, self-trained word vectors are noise.** Measured recall@5: 0.026. Fusing that into hybrid made results worse than BM25 alone. The reason is not mysterious: SGNS needs millions of tokens of context, and 7k tokens teaches it nothing.

So two things happen:

1. **The default semantic model for small corpora is a distributed thesaurus** (`mode=semantic`) — SMART-era co-occurrence cosine. No training, and it does connect *纸币* to *纸质货币*.
2. **The dense path is gated on data.** Below 400k corpus tokens the vector layer does not turn on and says why. Fusing a noise list is worse than having one fewer list. `--force-vectors` overrides it.

The gate is calibrated, not guessed: at 50k documents the same code trains a 30k-word vocabulary over 51M updates and builds the HNSW graph, and hybrid costs only ~2× BM25 latency. Plenty of data → it participates. Not enough → it stays out of the way.

**Optional: a real pretrained model, locally.** `scripts/fetch-model.sh` pulls bge-small-zh-v1.5 as int8 ONNX (24 MB) plus ONNX Runtime from hf-mirror, which works from a mainland-China connection without a proxy; `--model models/bge-small-zh-v1.5` swaps the encoder and the token gate stops applying, because a pretrained model is precisely what makes semantics work on a small corpus. Measured: recall@5 0.934 → 0.987, and the two impossible queries come back.

It is not the default and it is not in the jar: ONNX Runtime is a `provided` dependency, so the base artifact stays 224 KB with zero runtime dependencies. Trading one extra `-cp` for the strongest semantic layer is a decision you should make per deployment, which is why it is a flag and not a default.

Two model-specific traps are exposed as flags because both change ranking: `--pool cls|mean` (BGE ships with CLS pooling; mean still runs and still looks plausible) and the query-side instruction prefix (`为这个句子生成表示以用于检索文章：`, which the Chinese BGE models expect on queries only). Defaults are the measured-better side of each.

### HNSW: correct first, then fast

Fully handwritten multi-layer navigable small world graph: geometric level sampling, greedy descent, `efConstruction`-wide expansion on the target layer, reciprocal links with distance-based pruning.

Correctness is asserted, not felt: `VectorTest` generates 1,500 random vectors and compares against exact k-NN — **recall@10 must be ≥ 0.95** — and additionally checks that shrinking `ef` cannot *increase* recall, so the knob is real. Pruning is plain distance-based rather than the paper's relative-neighbour heuristic; that costs a little recall on clustered data, and the test measures how much.

### Crawler: politeness is a feature

Longest-prefix-wins robots rules (`Allow` overrides `Disallow`), per-host rate limits with `Crawl-delay`, URL normalisation (fragment, parameter order, host case, default port) before 64-bit fingerprinting, and a charset ladder of BOM → header → `<meta>` → strict UTF-8 → GBK.

Tests run against a local HTTP server and never touch the internet: 404, 500, a robots-disallowed path, a page with no prose, a GBK page, and the rate-limit interval each have assertions. Failures come back as `CrawlResult{ok:false, reason:…}`, never as exceptions.

## Known limits

- **Not implemented**: distribution, faceted aggregation, spell correction, learning to rank, multilingual analysers, access control. They are parked in `docs/PLAN.md`.
- The snapshot is a **single segment** — no merge. Merging buys nothing at 50k documents, so it waits.
- The demo corpus is **84 hand-written CC0 documents**, not scraped Wikipedia. Hand-written keeps licensing clean, and the small size is what exposed the embedding finding above.
- The thesaurus **degrades on synthetic corpora**: the 50k bench documents are recombined from one sentence pool, so almost every term co-occurs with almost every other and cosine loses all discriminating power (6 entries extracted there). Real documents are sparse in exactly the way this needs; notes and crawled pages are fine.
- The UI is a hand-written single file, not Vue 3 + Vite as the original design doc specified. That was a deliberate trade: no npm in the build path keeps "one jar, zero toolchain" true, and the search page only needs `fetch` plus a template. Swapping in a real frontend is a `web/` directory away — `api/StaticFiles` already prefers `web/dist` when it exists.
- Startup loads the whole snapshot; it will get noticeably slower somewhere in the hundreds of thousands of documents.
- The shipped dictionary is small and crude; it works because mining compensates, which also means a new corpus can invent words nobody reviewed.
- No coverage report: every package has tests, but I am not going to quote you a percentage.

## Security

The honest version of "local-first" is: unauthenticated, because it is yours. `serve` binds
`127.0.0.1` by default, and the write endpoints (`POST`/`DELETE /api/index`, `POST /api/crawl`) have
no accounts and no tokens — the right trade on a laptop, the wrong one on a network. Two defaults follow:

- **Exposing it is your decision, not its.** `--host 0.0.0.0` prints a warning naming exactly what it
  opens up, and the Docker image passes that flag because container loopback is unreachable from the
  host anyway. If the port must be reachable, put something that authenticates in front of it.
- **The crawler refuses private addresses.** Before any request leaves the process — the `robots.txt`
  lookup included — a target is checked for scheme, resolution and every address its name resolves to:
  loopback, `0.0.0.0/8`, link-local (where cloud metadata lives), RFC 1918, carrier-grade NAT, IPv6
  unique-local and multicast are refused with a reason in the result instead of fetched. A public page
  that redirects into one of those ranges is refused too, so the body is neither indexed nor readable
  back out of `/api/search`. `--allow-private` / `--allow-private-crawls` switch this off for a whole
  process, deliberately not for a single request.

- **Writes are capped, and a bad request still gets an answer.** `POST /api/index` and `POST /api/crawl`
  answer 413 above 8 MiB instead of reading the body, `topK` / `from` are clamped rather than trusted,
  and the JSON parser stops at 96 levels of nesting. Every route is wrapped, so malformed input returns
  a 400 with the reason instead of closing the socket — a dropped connection is indistinguishable from
  a server that is down, and used to be what a truncated body produced.

Not solved: the name-to-address check is not pinned to the connection, so DNS rebinding is still open;
there is no TLS, no rate limiting, and the snapshot file is trusted (CRCs catch corruption, not a
hostile author). [SECURITY.md](SECURITY.md) states the residual risks and how to report one.

## Development

```bash
./build.sh                          # javac path, no Maven needed
mvn -B test                         # 68 cases
mvn -B -DskipTests package          # target/mini-search.jar
java -cp target/classes dev.jingyu.ms.MiniSearch eval
scripts/check-claims.sh             # verify the README's self-referential numbers
scripts/regold.sh                   # after an intentional analyser change
```

Java 17. Maven is a build-time tool only (JUnit 5); running the thing is one jar.

## License

MIT. The bundled demo corpus ships under the same terms.
