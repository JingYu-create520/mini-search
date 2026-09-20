# mini-search

**一个能装进笔记本的本地优先中文混合搜索引擎。** 爬虫 → 自研中文分词 → 倒排索引(BM25) → 语料自训词向量 + HNSW → RRF 融合 → Web 界面。

单个 213 KB 的 jar，**运行时零依赖、零外部服务、默认不下载任何模型**（想要更强的语义可以一条命令加装 24 MB 的本地 bge 模型，仍然离线）。数据全在你机器上，而且每一层都小到能被一个人读完。

```bash
java -jar mini-search.jar          # 然后打开 http://localhost:9200
```

这一句就能用：演示语料打在 jar 里，不需要联网，不需要装数据库，不需要下载权重。

![demo](docs/demo.gif)

录屏里那 5 条查询都是真实请求，界面自己走完的：`http://localhost:9200/?tour=1`（帧由 `scripts/record-demo.sh` 抓）。想直达某一条：`?q=索引落盘为什么要带校验&mode=bm25`。

---

## 为什么要做这个

中文检索的教学项目通常停在"能跑"，工业引擎通常重到没法读。这两者之间有一块空白：**一个既能当场演示、又能逐层讲清楚的完整产品形态。**

所以这个仓库的取舍很明确：

- **不用 Lucene、不用 HanLP、不用 Spring、不用向量数据库。** HTTP 用 JDK 自带的 `HttpServer`，JSON 自己写，分词自己写，HNSW 自己写，正文抽取自己写。不是因为造轮子有意思，而是因为一旦引入这些，"每一层都能读懂"这句话就作废了。
- **每个说法后面都跟着一个可复现的数字。** 想看质量：`mini-search eval`；想看规模：`mini-search bench`。两个命令的输出都在仓库里（`data/eval/report.md`、`data/eval/bench.json`），改一行排序代码，数字就会告诉你变好还是变坏。
- **做不到的地方直接写在 README 里。** 见文末"已知边界"。

## 实测质量（38 条人工标注查询，k=5）

**开箱（零依赖，无模型）**

| 模式 | recall@5 | precision@5 | nDCG@5 | MRR | top5 有命中的查询 |
|---|---|---|---|---|---|
| bm25 | 0.934 | 0.254 | 0.912 | 0.918 | 36/38 |
| semantic（同源词扩展） | 0.934 | 0.216 | 0.899 | 0.898 | 36/38 |
| hybrid（RRF 融合） | **0.934** | 0.216 | **0.916** | **0.923** | 36/38 |

**加上本地 bge 模型（`scripts/fetch-model.sh`，24 MB 权重，仍然完全离线）**

| 模式 | recall@5 | nDCG@5 | MRR | top5 有命中的查询 |
|---|---|---|---|---|
| bm25 | 0.934 | 0.912 | 0.918 | 36/38 |
| hybrid | **0.987** | **0.955** | 0.954 | **38/38** |
| vector | **0.987** | **0.957** | 0.961 | **38/38** |

按查询类型拆开看更说明问题——`semantic` 类查询被刻意写成"目标文档里不出现查询词"（无模型时）：

| | bm25 | hybrid |
|---|---|---|
| 词面匹配类 recall@5 / nDCG@5 | 0.981 / 0.983 | 0.981 / 0.986 |
| 词面不匹配类 recall@5 / nDCG@5 | 0.818 / 0.739 | 0.818 / **0.751** |

零依赖那一路有 2 条查询在 top5 里全空："小孩高烧不退是细菌感染吗"（目标是《感冒和流感的区别》）、"为什么海边城市冬天不太冷"（目标是《海洋如何调节气候》）。这是真正的同义词/常识鸿沟，词法方法和共现词典都跨不过去——而**装上本地 bge 模型后这两条都进了 top5，38/38 全命中**。

复现：

```bash
mvn -B package
java -jar target/mini-search.jar eval                    # 零依赖那一路
scripts/fetch-model.sh                                   # 拉 24MB 权重 + ONNX Runtime（走 hf-mirror，不用代理）
java -cp "target/mini-search.jar;libs/onnxruntime.jar" \
     dev.jingyu.ms.MiniSearch eval --model models/bge-small-zh-v1.5 \
     --out data/eval/report-bge.md                        # 加上本地模型
```

两份报告都在仓库里：`data/eval/report.md` 与 `data/eval/report-bge.md`。融合权重不是拍脑袋：`--fusion 1,0.5,6` 是扫出来的，理由写在下面。

## 规模基准（50000 篇合成文档，句子来自真实语料）

| 指标 | 实测 |
|---|---|
| 倒排索引构建 | 13.6 s（另有分词词典挖掘 22.5 s） |
| BM25 查询延迟 | p50 10.3 ms / p95 35.2 ms |
| hybrid 查询延迟 | p50 21.6 ms / p95 75.6 ms |
| 词向量训练（2 epoch，5100 万次更新） | 140 s |
| HNSW 建图（m=16, efConstruction=200） | 156 s |
| 峰值堆占用 | 约 0.9 GB（默认 -Xmx8g 环境下） |

单机、零依赖、笔记本上的 5 万文档十毫秒级——这就是本项目对"性能"的全部承诺，它不是工业 benchmark。

## 代码量透明度表

一个人能不能读完，是个可以数的东西：

| 层 | 行数 | 职责 |
|---|---|---|
| `analyzer/` | 683 | 归一化、双向最大匹配、中英混排切分、统计新词挖掘（凝固度 NPMI + 左右邻接熵） |
| `index/` | 479 | 倒排索引、变长编码 posting、位置信息、删除位图、短语合并 |
| `ranking/` | 82 | BM25（k1/b 可调，多字段加权） |
| `hybrid/` | 76 | RRF 与加权分数融合 |
| `vector/` | 1054 | 语料自训词向量(SGNS)、暴力 KNN、HNSW、WordPiece 分词器、可选 ONNX/bge 编码器 |
| `semantic/` | 145 | 分布式同源词典（共现余弦），小语料下的语义层 |
| `search/` | 413 | 查询编排、四种模式、高亮与摘要 |
| `crawl/` | 726 | 礼貌爬虫、robots、64 位指纹去重、编码探测、链接密度正文抽取 |
| `api/` | 311 | JDK HttpServer 路由与静态资源 |
| `core/` | 844 | 引擎装配、快照持久化、语料装载 |
| `eval/` | 349 | recall/precision/nDCG/MRR 与规模基准 |
| `mcp/` | 194 | stdio JSON-RPC 的 MCP server |
| `util/` + 入口 | 649 | JSON、变长编码、日志、CLI |
| **主代码合计** | **6005** | 38 个文件 |
| 测试 | 1057 | 53 个用例（4 个需要本地模型，缺模型时自动跳过） |
| 前端 | 213 | 单文件搜索页 + 索引管理页 |

## 架构

```
        ┌──────────── crawl ────────────┐
URL ──► robots ─► 限速 ─► 抓取 ─► 编码探测 ─► 正文抽取 ─┐
                                                     ▼
                        中文分词（词典 + 统计新词） ─► 倒排索引（term → docs+positions）
                                                     │
                                                     ├─► BM25 ─────────┐
                                                     ├─► 同源词典扩展 ──┤
                            语料 ─► 词向量(SGNS) ─► HNSW ─► 向量召回 ───┤
                                                                       ▼
                                                          RRF 融合 ─► 高亮 ─► JSON / UI / MCP
```

一条查询的完整路径都在 `search/Searcher.java` 里，约 240 行。

## 使用

### 索引你自己的东西

```bash
# 一个 JSONL 文件，一行一篇
java -jar mini-search.jar index my-notes.jsonl

# 或者直接抓网页（遵守 robots + 限速 + 去重）
java -jar mini-search.jar crawl https://example.com/start --max 30 --depth 2

# 自定义词典（配合分词）
java -jar mini-search.jar analyze "我的领域词" --dict company.dict
```

`data/corpus/*.jsonl` 的格式就是最小格式：`id / title / body / url / tags`。索引会落盘成带 CRC 的快照（`data/index.msnap`），下次启动直接恢复，损坏的快照会被识别并重建，而不是半信半疑地服务。

### HTTP API

```
GET  /api/search?q=倒排索引&mode=hybrid&topK=10&phrase=true
GET  /api/doc?id=tech-001
POST /api/index      {"id":"note-1","title":"...","body":"...","tags":"a,b"}
POST /api/crawl      {"url":"https://example.com/page"}
DELETE /api/index?id=note-1
GET  /api/stats
```

`explain` 字段会把每条命中的 BM25 分、语义扩展分和向量余弦都摊开，方便你对着代码验证排序为什么这样。

### 让 agent 把它当本地 Google 用

```bash
java -jar mini-search.jar mcp      # stdio MCP server
```

工具面：`search`、`index_url`、`index_text`、`stats`。配套 Skill 在 `skills/mini-search/SKILL.md`。

MCP 客户端配置（以支持 stdio 的客户端通用格式为例）：

```json
{
  "mcpServers": {
    "mini-search": {
      "command": "java",
      "args": ["-jar", "/绝对路径/mini-search.jar", "mcp"]
    }
  }
}
```

### Docker

```bash
docker compose up --build      # http://localhost:9200
```

> 已在本机验证：`docker build` 产出 425 MB 镜像，`docker compose up -d` 起容器后 `:9200` 的统计与搜索接口均正常，中文不乱码。第一次构建慢，是因为容器里要下一遍 Maven 依赖，第二次走缓存。

## 每一层到底在做什么

### 分词：词典不够，就让它从语料里长出来

内置词典只有约 1900 条通用词，覆盖不了任何具体领域。真正干活的是统计新词挖掘：对语料里的字符 n-gram 同时算三个数——**频次**、**凝固度**（归一化点互信息 NPMI，取所有内部切分里最差的那个）、**自由度**（左右邻接字符的信息熵，取两边更受约束的那边）。三个都过关才承认它是一个词。

凝固度用 NPMI 而不是原始 PMI 是有意为之：原始 PMI 的数值随语料规模漂移，任何固定阈值换个语料就失效，这是手写分词器最常见的坑。阈值因此变成尺度无关的 [-1, 1]。

`AnalyzerTest` 用一份 golden master（`src/test/resources/analyzer-gold.txt`，62 条中英混排句）锁死切分结果，改分词必须显式跑 `scripts/regold.sh` 并复核 diff。

### 排序：BM25 的三个旋钮

`k1` 决定同一个词出现十次比出现三次强多少（饱和），`b` 决定长文档被惩罚多少，IDF 让罕见词说话更响。三个字段各有 boost（标题 3、正文 1、标签 2），所以"标题命中一次"会盖过"正文命中一次"，而它们共用一份索引。

IDF 的分母是**跨字段去重后的文档数**。这一度写成了"字段数"，于是 IDF 被压成近似常数、排序悄悄变坏——是 `IndexTest.rareTermDominates` 把它抓出来的，不是靠肉眼盯结果。

### 语义层：小语料用同源词典，大语料才用嵌入

这是本项目最诚实的一块。

**84 篇文档时，自训词向量基本等于噪声。** 实测 recall@5 只有 0.026，把它融进 hybrid 反而把关键词那一拉下去。原因不神秘：SGNS 需要几百万 token 的上下文才能收敛，7 千 token 的语料不够它学到任何东西。

所以两件事同时做了：

1. **默认用小语料也能工作的方法**：分布式同源词典（SMART 那一代的共现余弦），`mode=semantic`。它不需要训练，扫一遍共现就能把"纸币"和"纸质货币"接上。
2. **给向量层加了数据量门槛**：语料 token 数低于 40 万时，向量层直接不启用，并且把原因打印出来。宁可少一路召回，也不要让一路噪声去污染排名。想强行打开：`--force-vectors`。

门槛不是猜测：5 万篇文档的 bench 里，同一套代码训出 30000 词表、5100 万次梯度更新、HNSW 图，混合检索 p50 21.6 ms。语料够大它就工作，语料不够它就不出场。

**可选：本地预训练模型。** `scripts/fetch-model.sh` 从 hf-mirror 拉 bge-small-zh-v1.5 的 int8 ONNX（24 MB）和 ONNX Runtime，`--model models/bge-small-zh-v1.5` 就换上这个编码器——门槛自动失效，因为预训练模型正是小语料唯一能拿到语义的办法。实测 recall@5 从 0.934 涨到 0.987，那两条谁都打不中的查询都回来了。

它**不是默认**，也**不进 jar**：ONNX Runtime 在 pom 里是 `provided`，基础 jar 仍然 213 KB、零运行时依赖。想要真语义就多一个 `java -cp "mini-search.jar;libs/onnxruntime.jar"` 的写法，代价摆在这儿，你自己选。

顺带两个模型相关的坑做成了开关：`--pool cls|mean`（BGE 是 CLS 池化，用 mean 也能跑，但排序会悄悄变差）和查询侧指令前缀（中文 BGE 只在 query 上加"为这个句子生成表示以用于检索文章："，加到文档上或两边都不加都会掉召回）。默认值都是实测更好的那个。

### HNSW：先正确，再快

多层可导航小世界图全部手写：节点层级按几何分布抽样，插入时逐层贪心逼近再在目标层做 `efConstruction` 宽度的候选扩展，双向连边、超容量就按距离裁剪。

正确性不靠感觉：`VectorTest` 生成 1500 条随机向量，与暴力 KNN 逐查询比对，**recall@10 必须 ≥ 0.95**，并且验证 ef 变小召回一定跟着变小（否则这个旋钮就是假的）。裁剪用的是"按距离取最近 M 个"，比论文的相对近邻启发式简单，在聚簇数据上会亏一点召回——这个数字被测试测出来了，所以它是个已知的取舍，不是隐藏的问题。

### 爬虫：讲礼貌是功能的一部分

robots 规则按最长前缀优先，`Allow` 能盖过 `Disallow`；每个域名独立限速，并支持 `Crawl-delay`；URL 先归一化（去 fragment、参数排序、主机小写、默认端口折叠）再做 64 位指纹去重；编码按 BOM → 响应头 → meta 声明 → UTF-8 严格试解码 → GBK 的顺序判。

测试对着本地 HTTP 服务器跑，不碰外网：404、500、robots 禁爬、无正文页、GBK 页、限速间隔，每项都有断言。所有失败都变成 `CrawlResult{ok:false, reason:...}`，而不是异常。

正文抽取用链接密度：块级标签切成行，行内链接文字单独计数，`linkRatio > 0.5` 或短于 12 字的行丢掉，剩下的取连续段里非链接字符最多的一段。它不完美（一段被链接海洋包围的短正文可能输给壳子），但"抓不到正文时降级成只索引标题、且不抛异常"是保证并测了的。

## 已知边界（说清楚，免得你来发现）

- **不做**：分布式、faceted 聚合、拼写纠错、学习排序、多语言分析器、权限系统。这些在 `docs/PLAN.md` 的 v2 停车场里。
- 索引落盘目前是**单段快照**，不是多段归并。归并在 5 万文档规模上买不到任何东西，所以被推到了 v2。
- 演示语料是本项目手写的 84 篇 CC0 短文（`data/corpus/`），**不是**维基抓取。手写让许可证干净，代价是语料小，而这正好暴露了上面"小语料学不出嵌入"的事实。
- 同源词典在**合成语料上会退化**：5 万篇 bench 文档是同一批句子重组出来的，几乎所有词都互相共现，共现余弦因此失去区分度（该场景只挖出 6 个词条）。它需要真实文档那种"每篇只用一小部分词汇"的稀疏性——真实抓取或真实笔记都没问题。
- 同义词鸿沟真实存在：零依赖那一路点名的 2 条查询就是，装上本地 bge 才补得回来。
- 预训练模型是**可选外挂**：24 MB 权重 + 93 MB ONNX Runtime 都不进 jar、不进 git，`scripts/fetch-model.sh` 才下载。所以"零依赖开箱即用"和"最强语义"你只能同时要两步操作，不能同时要一个 213 KB 的 jar。
- 前端是手写的单文件页面，**不是** v1.0 里写的 Vue 3 + Vite。这是有意的取舍：构建链里不出现 npm，"单 jar + 零工具链"这句话才成立，而搜索页只需要 `fetch` 加一段模板。想换真前端，`api/StaticFiles` 已经优先读 `web/dist`，放进目录就能顶掉内置页。
- 快照是单文件全量写，文档数到几十万时启动加载会明显变慢。
- 内置词典小而糙。它靠"词典 + 语料挖掘"两条腿工作，换个领域的语料就会长出让你自己都认不出的词——这是特性也是风险。
- 没有测试覆盖率报告：结构上每个包都有对应用例，但不给你看那个百分比。

## 开发

```bash
./build.sh                                   # 不用 Maven 的本地编译
mvn -B test                                  # 53 个用例
mvn -B -DskipTests package                   # 产出 target/mini-search.jar
java -cp target/classes dev.jingyu.ms.MiniSearch eval
java -cp target/classes dev.jingyu.ms.MiniSearch bench --docs 50000
scripts/regold.sh                            # 有意改分词后重建 golden master
```

Java 17，Maven 只在构建期需要（测试用 JUnit 5）；运行期一个 jar 就够。

## 许可

MIT。演示语料同许可。
