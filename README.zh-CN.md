# mini-search

本地优先的中文混合搜索引擎。爬虫、中文分词、倒排索引、BM25、词向量、HNSW、RRF 融合、Web 界面，全都自己写的，一个 224 KB 的 jar，运行时零依赖。

```bash
java -jar mini-search.jar          # 然后打开 http://localhost:9200
```

演示语料在 jar 里面，所以断网也能跑。不想自己构建就直接下这个文件，机器上有 Java 17 就行：
<https://github.com/JingYu-create520/mini-search/releases/latest/download/mini-search.jar>

![demo](docs/demo.gif)

GIF 里那五条查询都是真跑出来的。界面会自己走一遍演示：`http://localhost:9200/?tour=1`。想直接看某一条：`?q=索引落盘为什么要带校验&mode=bm25`。

[English README](README.md) · [设计文档](docs/PLAN.md) · [评测报告](data/eval/report.md) · [规模基准](data/eval/bench.json) · [安全说明](SECURITY.md) · [参与开发](CONTRIBUTING.md)

---

## 为什么写这个

教程式的中文检索项目大多停在"能跑"，工业引擎又重到没人读完。中间这块是空的：一个能当场演示、又能一层层讲清楚的完整东西。

所以我没引第三方库。HTTP 用 JDK 自带的 `HttpServer`，JSON、分词、HNSW、正文抽取都手写。这不是偏爱造轮子——一旦引入 Lucene，"每层都能读懂"这句话就作废了。

另一条要求是每个说法都能被验证。质量跑 `mini-search eval`，规模跑 `mini-search bench`，两份输出都提交在仓库里（`data/eval/`）。改坏排序会被数字抓到。

做不到的地方写在文末"已知边界"里。

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

| | bm25 | semantic（同源词典） | hybrid |
|---|---|---|---|
| 词面匹配类 recall@5 / nDCG@5 | 0.981 / 0.983 | 0.981 / 0.986 | 0.981 / 0.983 |
| 词面不匹配类 recall@5 / nDCG@5 | 0.818 / 0.739 | 0.818 / 0.687 | 0.818 / **0.751** |

两行一起看：词面匹配那行赢的是同源词典，词面不匹配那行输得最狠的也是它；hybrid 拿到的是 bm25 的
召回加上关键那行的 nDCG 提升。融合不是白吃的午饭，`{1, 0.5, 6}` 是扫出来的权重，带模型那次等权融合
会把 hybrid 压到 0.927，比向量单路的 0.957 还低。

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
| 整机构建（含下面所有阶段） | 304 s = 164.3 docs/s |
| — 词典挖掘 | 41.9 s |
| — 倒排索引构建 | 15.6 s |
| — word2vec 训练（2 epoch，累计 1.03 亿次更新，3 万词表） | 211.3 s |
| — HNSW 建图（m=16, efConstruction=200） | 14.7 s |
| — 向量层：训练 + 建图 + 全量分词 | 234.4 s |
| — 同源词典构建 | 12.3 s |
| BM25 查询延迟 | p50 15.5 ms / p95 48.9 ms |
| hybrid 查询延迟 | p50 28.7 ms / p95 107.9 ms |
| 构建后堆占用 | 1.9 GB（JVM 上限 8 GB） |

同一个 jar、同一台笔记本，隔一小时跑三次：构建 304 / 338 / 340 秒，BM25 p50 15.5 / 20.7 / 16.4 毫秒。
所以这张表要读成"内存里装着全部索引、单次查询几十毫秒、±20% 波动"，不是照着复现到小数点的数字——
机器上当时还跑着别的东西，而 artifact 只是一次采样。

有两行现在没有了。"HNSW 建图 156 秒"其实是向量层的累计时间被贴错了标签，建图本身 14.7 秒；
"倒排索引构建 13.6 秒"来自另一次运行，那次的 artifact 总构建是 201 秒，而阶段耗时只打在 stderr 上，
没人能核对。现在这些阶段名和秒数都进了 `data/eval/bench.json`，表格再漂一次就会被 CI 抓住。

单机、零依赖、笔记本上的 5 万文档、单次查询几十毫秒——这就是本项目对"性能"的全部承诺，它不是工业 benchmark。

## 代码量透明度表

一个人能不能读完，是个可以数的东西：

| 层 | 行数 | 职责 |
|---|---|---|
| `analyzer/` | 683 | 归一化、双向最大匹配、中英混排切分、统计新词挖掘（凝固度 NPMI + 左右邻接熵） |
| `index/` | 479 | 倒排索引、变长编码 posting、位置信息、删除位图、短语合并 |
| `ranking/` | 82 | BM25（k1/b 可调，多字段加权） |
| `hybrid/` | 76 | RRF 与加权分数融合 |
| `vector/` | 1086 | 语料自训词向量(SGNS)、暴力 KNN、HNSW、WordPiece 分词器、可选 ONNX/bge 编码器 |
| `semantic/` | 145 | 分布式同源词典（共现余弦），小语料下的语义层 |
| `search/` | 447 | 查询编排、四种模式、高亮与摘要 |
| `crawl/` | 863 | 礼貌爬虫、robots、64 位指纹去重、编码探测、链接密度正文抽取、内网目标默认拒绝 |
| `api/` | 404 | JDK HttpServer 路由与静态资源 |
| `core/` | 973 | 引擎装配、快照持久化、语料装载、读写互斥的锁 |
| `eval/` | 358 | recall/precision/nDCG/MRR 与规模基准 |
| `mcp/` | 200 | stdio JSON-RPC 的 MCP server |
| `util/` + 入口 | 725 | JSON、变长编码、日志、CLI |
| **主代码合计** | **6,521** | 39 个文件 |
| 测试 | 1532 | 67 个用例（4 个需要本地模型，缺模型时自动跳过） |
| 前端 | 255 | 单文件搜索页 + 索引管理页 |

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

服务默认只绑 `127.0.0.1`；`serve --host 0.0.0.0` 才对外网卡开放。容器镜像里是特意传了 `--host 0.0.0.0`
的——容器内的回环地址宿主机访问不到，不这么做映射端口就是空映射。本机用不着就别开：
`/api/index` 和 `/api/crawl` 是没有鉴权的写接口。

对外映射默认也只落在宿主机的回环上（`127.0.0.1:9200:9200`），所以 `docker compose up` 本身不会把服务
摊到局域网里；要摊得显式写 `MS_HOST=0.0.0.0`。`MS_XMX` 用来调那个 1 GB 堆上限——它存在的意义就是让一次
失控的抓取撞墙而死，而不是把整台机器吃掉。

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

它**不是默认**，也**不进 jar**：ONNX Runtime 在 pom 里是 `provided`，基础 jar 仍然 224 KB、零运行时依赖。想要真语义就多一个 `java -cp "mini-search.jar;libs/onnxruntime.jar"` 的写法，代价摆在这儿，你自己选。

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
- 预训练模型是**可选外挂**：24 MB 权重 + 93 MB ONNX Runtime 都不进 jar、不进 git，`scripts/fetch-model.sh` 才下载。所以"零依赖开箱即用"和"最强语义"你只能同时要两步操作，不能同时要一个 224 KB 的 jar。
- 前端是手写的单文件页面，**不是** v1.0 里写的 Vue 3 + Vite。这是有意的取舍：构建链里不出现 npm，"单 jar + 零工具链"这句话才成立，而搜索页只需要 `fetch` 加一段模板。想换真前端，`api/StaticFiles` 已经优先读 `web/dist`，放进目录就能顶掉内置页。
- 快照是单文件全量写，文档数到几十万时启动加载会明显变慢。
- 内置词典小而糙。它靠"词典 + 语料挖掘"两条腿工作，换个领域的语料就会长出让你自己都认不出的词——这是特性也是风险。
- 没有测试覆盖率报告：结构上每个包都有对应用例，但不给你看那个百分比。

## 安全

"本地优先"的老实说法是：没有鉴权，因为它是你一个人的。`serve` 默认只绑 `127.0.0.1`，
写接口（`POST`/`DELETE /api/index`、`POST /api/crawl`）没有账号也没有 token——在自己笔记本上是
正确的取舍，放到网络上就是错的取舍。由此有两条默认：

- **要不要暴露由你决定。** `--host 0.0.0.0` 会打印一行警告，说清楚它开放了什么；Docker 镜像里
  必须带这个参数（容器内的 loopback 宿主机访问不到）。真要给别人访问，前面放一个会鉴权的东西。
- **爬虫默认拒绝内网目标。** 请求发出之前——连 robots.txt 都还没抓——会检查协议、域名能否解析，
  以及解析到的每一个地址：环回、`0.0.0.0/8`、链路本地（云元数据服务就在这儿）、RFC 1918 私网、
  运营商级 NAT、IPv6 唯一本地地址、组播一律拒绝，结果里给原因而不是照样抓。公网页面重定向到这些
  地址也一样拒绝，正文既不进索引，也没法再通过 `/api/search` 读回去。`--allow-private` /
  `--allow-private-crawls` 是**按进程**关掉这条检查，故意不做成按请求，否则攻击者用同一个请求就能
  把开关打开。

- **写接口有上限，坏请求也必须有人答。** `POST /api/index`、`POST /api/crawl` 超过 8 MiB 直接回 413 而不
  是把请求体读进内存，`topK` / `from` 是钳制而不是照信，手写的 JSON 解析器最深只跟到 96 层嵌套。每条路由
  都套了一层兜底：截断的 body、多出来的括号、内部意外，都换成带原因的 400/500，而不是默默关连接——
  客户端看到的是"服务器挂了"，而之前一个截断的 JSON body 就足够让它长这样。

没解决的：域名到地址的解析结果没有钉住到连接上，所以 DNS rebinding 仍然成立；没有 TLS、没有限速；
快照文件是可信输入（CRC 只防损坏，不防别人伪造）。残余风险和上报方式写在 [SECURITY.md](SECURITY.md)。

## 开发

```bash
./build.sh                                   # 不用 Maven 的本地编译
mvn -B test                                  # 67 个用例
mvn -B -DskipTests package                   # 产出 target/mini-search.jar
java -cp target/classes dev.jingyu.ms.MiniSearch eval
java -cp target/classes dev.jingyu.ms.MiniSearch bench --docs 50000
scripts/regold.sh                            # 有意改分词后重建 golden master
scripts/check-claims.sh                      # 校验 README 里那些自指数字没有说谎
```

Java 17，Maven 只在构建期需要（测试用 JUnit 5）；运行期一个 jar 就够。

## 许可

MIT。演示语料同许可。
