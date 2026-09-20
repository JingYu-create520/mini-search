# 发布文案（直接复制，不要改格式）

> 仓库地址假定为 `https://github.com/JingYu-create520/mini-search`。若最终用户名不同，全局替换这一处即可。
> 所有数字都来自仓库里已提交的 `data/eval/report.md` 与 `data/eval/bench.json`，可复现。

---

## GitHub → Settings → Description（一个输入框）

```
Local-first hybrid search engine for Chinese: hand-written analyser, inverted index (BM25), distributed thesaurus, self-trained word vectors + HNSW, RRF fusion. One 180KB jar, zero runtime dependencies, no model download.
```

## GitHub → Settings → Topics（逐个回车）

```
search-engine
chinese
information-retrieval
bm25
hnsw
local-first
offline-first
vector-search
hybrid-search
mcp
java17
no-dependencies
self-trained-embeddings
inverted-index
```

---

## Hacker News — Show HN

**Title（67 字符，HN 上限 80）**

```
Show HN: A local-first Chinese hybrid search engine in a 180KB jar
```

**URL**

```
https://github.com/JingYu-create520/mini-search
```

**First comment（正文框）**

```
Everything in here is hand-written on purpose: the Chinese analyser, the inverted index, BM25, SGNS
word vectors, HNSW, RRF fusion, the polite crawler, the main-text extractor, the HTTP server and the
JSON parser. No Lucene, no HanLP, no Spring, no vector DB, no model download. Total: 5,573 lines of
main code in 36 files, and the jar is 180KB.

The reason for not importing anything is narrow: I wanted every layer to be small enough to read, and
that promise dies the moment "the search part" is a black-box dependency.

Numbers, all reproducible from the repo (mini-search eval / mini-search bench):
- 38 hand-labelled queries over an 84-document CC0 corpus: bm25 recall@5 0.934, nDCG@5 0.912; hybrid
  nDCG@5 0.918
- 50k documents: index build 13.6s, BM25 p50 10.3ms / p95 35.2ms, hybrid p50 21.6ms
- HNSW vs exact k-NN: recall@10 >= 0.95, asserted in a test

Two findings I'd rather publish than bury:

1. Self-trained embeddings are noise on a small corpus. On the 84-document demo set, vector recall@5
   was 0.026 -- and fusing it made hybrid *worse* than BM25 alone. So the dense path is gated behind
   400k corpus tokens and says so at startup; below that, semantics come from a distributed thesaurus
   (SMART-era co-occurrence cosine), which needs no training and does work at that size. Plenty of
   data, it participates; not enough, it stays out of the way.

2. A ranking bug I shipped: my IDF denominator counted *fields* containing a term instead of
   *documents*, which flattened IDF and quietly degraded ordering. The output still looked plausible.
   A single test -- "a term with df=1 must outrank a term with df=2 at equal tf" -- caught it, and
   fixing it moved recall@5 from 0.921 to 0.934.

Known limits, stated in the README: two labelled queries that no lexical method reaches (real synonym
gap), single-segment snapshot with no background merge, and the thesaurus degrades on the synthetic
benchmark corpus because recombined sentences destroy co-occurrence sparsity.

Java 17. `java -jar mini-search.jar` -> http://localhost:9200, demo corpus inside the jar. There is
also an MCP server (`mcp` subcommand) so an agent can use it as a local Google.
```

---

## Reddit — r/LocalLLaMA

**Title**

```
I wrote a local-first hybrid search engine for Chinese in 5.5k lines of dependency-free Java — self-trained embeddings are noise below ~400k tokens, so I gated them
```

**Body**

```
What it is: crawler -> hand-written Chinese analyser (dictionary + statistical new-word mining) ->
inverted index with BM25 -> distributed thesaurus and/or self-trained word vectors with HNSW -> RRF
fusion -> web UI. One 180KB jar. No Lucene, no HanLP, no Spring, no vector DB, and no model download,
so it runs fully offline.

Repo: https://github.com/JingYu-create520/mini-search

The embedding finding is the reason I'm posting this in here rather than a Java board. I trained
skip-gram with negative sampling on my own corpus and measured it honestly:

- 84 documents (~7k tokens): vector recall@5 = 0.026. Essentially random. Worse, RRF-fusing that list
  dragged hybrid *below* plain BM25.
- 50,000 documents (~7M tokens): 30k vocabulary, 51M gradient updates, 140s of training, HNSW build
  156s, hybrid p50 21.6ms -- and it earns its place in the fusion.

So the dense encoder is now gated on corpus size (400k tokens) and logs why when it stands down. For
semantics on small corpora I use a distributed thesaurus instead: co-occurrence cosine over document
indicator vectors, query expansion, no training at all. That is a 1970s SMART idea and it works at a
size where modern embeddings simply cannot.

Numbers: 38 hand-labelled queries, bm25 recall@5 0.934 / nDCG@5 0.912, hybrid nDCG@5 0.918. HNSW
recall@10 >= 0.95 against exact k-NN, asserted in CI. 50k docs: index build 13.6s, p50 10.3ms.

What it does NOT have: pretrained embeddings (the Encoder interface is where a bge/ONNX model would
go), spell correction, facets, distribution, background segment merging. Two labelled queries are
named in the README that nothing here can answer -- they need real lexical knowledge, not statistics.

Java 17, MIT. `java -jar mini-search.jar` and it serves a UI plus an HTTP API on :9200; there's also
an MCP server so agents can use it as a local Google.
```

---

## 掘金

**标题**

```
我从零写了一个能搜中文的搜索引擎：5573 行、零依赖、180KB 一个 jar
```

**正文**：直接用仓库里的 `docs/ARTICLE.zh-CN.md`（已按掘金 Markdown 写好，含表格与代码块）。

**标签**：`后端` `Java` `搜索引擎` `NLP` `RAG`

---

## V2EX — /go/create

**标题**

```
[开源] 本地优先的中文混合搜索引擎：自研分词+倒排+BM25+HNSW+RRF，5573 行零依赖，一个 180KB jar
```

**正文**

```
写了一个能搜中文的本地搜索引擎，全部手写：没有 Lucene、没有 HanLP、没有 Spring、没有向量数据库，
也没有任何需要下载的模型。主代码 5573 行 / 36 个文件，jar 180KB，运行时零依赖。

java -jar mini-search.jar  →  http://localhost:9200，演示语料打在 jar 里，断网可用。

仓库：https://github.com/JingYu-create520/mini-search

实测（38 条人工标注查询，可自己跑 mini-search eval）：
· bm25 recall@5 0.934，nDCG@5 0.912
· hybrid（RRF 融合）nDCG@5 0.918
· 5 万文档：建索引 13.6 秒，查询 p50 10.3ms / p95 35.2ms
· HNSW 对比暴力 KNN，recall@10 ≥ 0.95（这条写在测试里，改坏了 CI 就红）

两个踩过的坑值得单独说：
1）IDF 的分母我一开始写成"包含这个词的字段数"而不是"文档数"，等于把 IDF 压平，排序肉眼看不出来地
   变差。是一条"df=1 的词必须比 df=2 的词响"的单测抓出来的。修完 recall@5 从 0.921 到 0.934。
2）84 篇文档自训词向量基本是噪声（recall@5 0.026），而且混进 hybrid 会把整体拉到比纯 BM25 还差。
   所以给向量层加了 40 万 token 的门槛，不够就不启用并打印原因；小语料的语义改用分布式同源词典
   （SMART 那代的共现余弦，不用训练）。同一套代码在 5 万文档上就正常干活了。

不做的：分布式、faceted、拼写纠错、学习排序、内置预训练模型。README 里还点名了两条谁都答不出的查询。

也带 MCP server（java -jar mini-search.jar mcp），可以直接给 agent 当本地 Google 用。
```

---

## awesome-list 提 PR 时的一行

```
[mini-search](https://github.com/JingYu-create520/mini-search) - Local-first hybrid search engine for Chinese in one dependency-free jar: hand-written analyser, BM25, distributed thesaurus, self-trained vectors + HNSW, RRF fusion, with a labelled evaluation set in CI.
```

---

## 发布前自检（我已经做完的部分标了 ✅）

- ✅ 49 个测试全绿；`mvn -B package` 出 180KB jar；无关目录下 `java -jar` 冷启动可搜
- ✅ README 双语、GIF 是真界面（`?tour=1` 自走）、评测与基准数字已提交进仓库
- ✅ LICENSE(MIT)、CI 工作流（测试 + 评测门槛 + jar 冒烟）、Dockerfile/compose
- ✅ Docker 已在本机验证：`docker build` 出 425MB 镜像，`docker compose up -d` 后 `:9200` 的统计与搜索接口均正常，中文不乱码
- ⬜ 建仓并推送（需要你点）；发布动作一律由你提交，我只把文案写到能直接粘贴的程度
