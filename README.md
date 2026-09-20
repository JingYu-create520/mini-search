# mini-search

**A local-first hybrid search engine for Chinese that fits in a laptop bag.** Crawler → hand-written Chinese analyser → inverted index (BM25) → corpus-trained word vectors + HNSW → RRF fusion → web UI.

One 213 KB jar. **Zero runtime dependencies, no external services, no model download by default.** (A 24 MB local bge model can be added with one script if you want the strongest semantics — still fully offline.) Your data never leaves the machine, and every layer is small enough for one person to read.

```bash
java -jar mini-search.jar          # then open http://localhost:9200
```

That is the whole setup. The demo corpus ships inside the jar: no network, no database, no weights to fetch.

![demo](docs/demo.gif)

Every query in that recording is a real request. The page drives itself at `http://localhost:9200/?tour=1`, and `scripts/record-demo.sh` grabs the frames. To land on one query directly: `?q=索引落盘为什么要带校验&mode=bm25`.

[中文 README](README.zh-CN.md) · [design doc](docs/PLAN.md) · [evaluation report](data/eval/report.md) · [benchmark](data/eval/bench.json)

---

## The gap this fills

Chinese search tutorials usually stop at "it runs". Industrial engines are usually too heavy to read. Nothing sits in between: **a complete product shape that you can both demo on the spot and explain layer by layer.**

So the constraints are deliberate:

- **No Lucene, no HanLP, no Spring, no vector database.** HTTP is the JDK's own `HttpServer`. JSON, the analyser, HNSW and main-text extraction are all here, because importing them would void the promise that every layer is readable.
- **Every claim carries a reproducible number.** `mini-search eval` for quality, `mini-search bench` for scale. Both outputs are committed to this repo, so a ranking change is judged by a diff in a table, not by vibes.
- **Failure modes are documented in the README**, not in a footnote three commits later. See [Known limits](#known-limits).

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

| | bm25 | hybrid |
|---|---|---|
| lexical-match queries | 0.981 / 0.983 | 0.981 / **0.986** |
| word-mismatch queries | 0.818 / 0.739 | 0.818 / **0.751** |

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

| | measured |
|---|---|
| inverted index build | 13.6 s (+22.5 s for dictionary mining) |
| BM25 query latency | p50 10.3 ms · p95 35.2 ms |
| hybrid query latency | p50 21.6 ms · p95 75.6 ms |
| word2vec training (2 epochs, 51M updates, 30k vocab) | 140 s |
| HNSW build (m=16, efConstruction=200) | 156 s |

Single machine, zero dependencies, laptop, ten-millisecond queries at 50k documents. That is the entire performance promise — this is not an industrial benchmark.

## How many lines is each layer

| layer | lines | what lives there |
|---|---|---|
| `analyzer/` | 683 | normalisation, bidirectional maximum matching, mixed CN/EN cutting, statistical new-word mining (NPMI cohesion + branch entropy) |
| `index/` | 479 | inverted index, varbyte postings, positions, delete bitmap, phrase merge |
| `ranking/` | 82 | BM25 with tunable k1/b and per-field boosts |
| `hybrid/` | 76 | RRF and weighted score fusion |
| `vector/` | 1054 | corpus-trained SGNS word vectors, brute-force k-NN, HNSW, `Encoder` SPI |
| `semantic/` | 145 | distributed thesaurus (co-occurrence cosine) — the semantic model that works on small corpora |
| `search/` | 413 | query orchestration, four modes, highlighting |
| `crawl/` | 726 | polite crawler, robots, 64-bit dedupe, charset detection, link-density main-text extraction |
| `api/` | 311 | routes and static assets on the JDK HTTP server |
| `core/` | 844 | engine assembly, CRC-checked snapshot, corpus loading |
| `eval/` | 349 | recall / precision / nDCG / MRR, scale benchmark |
| `mcp/` | 194 | MCP server over stdio JSON-RPC |
| `util/` + CLI | 649 | JSON, varbyte, logging, commands |
| **main** | **6,005** | 38 files |
| tests | 1057 | 53 cases |
| UI | 213 | single-file search page + index admin page |

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

Index state persists to a CRC-checked snapshot (`data/index.msnap`). A corrupt or stale snapshot is detected and rebuilt rather than half-trusted.

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

It is not the default and it is not in the jar: ONNX Runtime is a `provided` dependency, so the base artifact stays 213 KB with zero runtime dependencies. Trading one extra `-cp` for the strongest semantic layer is a decision you should make per deployment, which is why it is a flag and not a default.

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

## Development

```bash
./build.sh                          # javac path, no Maven needed
mvn -B test                         # 53 cases
mvn -B -DskipTests package          # target/mini-search.jar
java -cp target/classes dev.jingyu.ms.MiniSearch eval
scripts/regold.sh                   # after an intentional analyser change
```

Java 17. Maven is a build-time tool only (JUnit 5); running the thing is one jar.

## License

MIT. The bundled demo corpus ships under the same terms.
