# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); the version follows
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
