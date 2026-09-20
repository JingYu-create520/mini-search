---
name: mini-search
description: Search, index, and crawl local documents with the mini-search hybrid search engine (BM25 + distributed thesaurus + optional embeddings + HNSW + RRF). Use when the user asks to search their local corpus, index notes or JSONL files, crawl a page into the index, or look up why a search result ranked the way it did.
---

# mini-search

A local-first search engine for Chinese text in one dependency-free jar. Everything happens on the
user's machine; nothing is uploaded and no model download is required.

## When to use this skill

- "搜一下我笔记里关于倒排索引的东西"
- "把这几个网址收进索引"
- "为什么这条结果排在前面"
- "给我的语料跑一下检索评测"

## Tools exposed over MCP

The server is started with `java -jar target/mini-search.jar mcp` (stdio JSON-RPC).

| tool | arguments | what it does |
|---|---|---|
| `search` | `query` (required), `mode` = `hybrid`\|`bm25`\|`semantic`\|`vector`, `topK` 1–50 | Ranked hits with id, url, title, score and an `<em>`-highlighted snippet |
| `index_url` | `url` | Politely fetches one page (robots + rate limit + charset detection + main-text extraction) and indexes it |
| `index_text` | `id`, `title`, `body`, optional `url`, `tags` | Indexes text directly; re-using an `id` updates that document |
| `stats` | – | Document and term counts, dictionary size, which retrieval models are active |

## How to pick a mode

- **`hybrid`** (default) — RRF fusion of the lexical list, the thesaurus-expanded list, and the dense
  list when it is active. Best nDCG on the shipped evaluation; use it unless you have a reason not to.
- **`bm25`** — pure lexical. Use it when the user expects an exact phrase or a code identifier, since
  expansion can pull in near-but-wrong documents.
- **`semantic`** — BM25 over the query plus its distributed-thesaurus neighbours. This is the semantic
  model that works on small corpora; it costs nothing to train.
- **`vector`** — dense k-NN over corpus-trained word vectors. **The engine disables this below roughly
  400k corpus tokens and logs why**: at 7k tokens measured recall@5 is 0.026, which is noise, and
  fusing noise into hybrid makes results worse than BM25 alone. Do not promise vector quality on a
  small corpus; say which mode is actually active by reading `stats`.

## Reading results

Each hit carries `explain` with its per-model scores (`bm25`, `semantic`, `cosine`) and the analyser's
tokens for the query are returned as `tokens`. If a result looks wrong, check `tokens` first: most
surprising rankings in a Chinese engine come from a term being cut differently than expected, and
`mini-search analyze "<text>"` shows the cut immediately.

## Indexing guidance

- Ids are stable and user-visible; re-indexing the same id updates the document, so use a URL hash or
  a note slug rather than a counter.
- Bulk load with `java -jar mini-search.jar index file.jsonl`, one JSON object per line with
  `id/title/body/url/tags`.
- After ad-hoc writes through the API or MCP, ask for `refresh=true` on the last `index_text` call (or
  restart) if the user wants the semantic layer to see the new documents right away.

## Limits worth stating out loud

No spell correction, no facets, no distribution, single-segment snapshot (no background merge), and no
pretrained embedding model bundled. A query whose vocabulary shares nothing with the target document
and no co-occurrence path can still miss — the shipped evaluation names two such queries.
