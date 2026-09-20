# mini-search 设计书 v1.1（带向量检索的迷你搜索引擎）

> 版本 v1.1 · 2026-09-20 · 状态：M1–M6 已完成，M7（发布）待人工执行
> 本文件由 v1.0（`D:\daily-files\Qoder CN\PLAN-mini-search.md`）修订而来，是仓库内的施工依据。
> v1.0 → v1.1 的改动全部有实测依据，见 §1。里程碑状态见 §7。

---

## 1. v1.1 相对 v1.0 改了什么，为什么

| # | 改动 | 原因（v1.0 的问题） | 落地方式 |
|---|---|---|---|
| 1 | **评测集前移到 M1** | M2/M3 的验收指标（"20 个中文查询 Top5 命中 ≥16/20"、"10 个语义样例 hybrid 优于 bm25"）依赖 `eval/queries.jsonl`，而它在 v1.0 里直到 §7/M6 才出现——依赖倒置，验收当天无法执行 | `data/eval/queries.jsonl` 与 `data/corpus/` 同期建立；`eval` 是 M1 交付物 |
| 2 | **段文件二进制格式与后台归并移出 MVP** | 自定义段格式+CRC 不出 GIF、不涨 star、不影响 recall，却按经验吃掉 M2 三分之一时间 | 实现为**单段快照**（magic/version/长度前缀分节/每节 CRC32）；归并进 v2 停车场。5 万文档下没有它照样跑 |
| 3 | **"离线跑通"与"首次下载模型"的矛盾提前拍板** | v1.0 要求 `java -jar` 直接可搜（§9 第一条），又要求跑 bge-small-zh——百 MB 权重不可能不进 git 就离线可用 | 默认**不内置任何模型**；`Encoder` 留成接口；语料够大时启用自训词向量，不够大时明确不启用并解释 |
| 4 | **分词选型改为"自实现 + 可插词典"** | v1.0 押 HanLP（许可/坐标/体积三个 `[需验证]`），但真正的差异化是"教学级可读"，用第三方分词恰好把它让出去了 | `analyzer/` 自研：核心词典 + 统计新词挖掘（NPMI 凝固度 + 邻接熵）+ 双向最大匹配；`--dict` 接自定义词典 |
| 5 | **演示语料改为手写 CC0 短文** | v1.0 的"中文维基摘要子集 ~5 万篇"带着 `[需验证抓取许可]`，是个未爆弹 | `data/corpus/` 84 篇手写文档，许可与代码一致；规模问题用 `bench` 合成语料单独测 |
| 6 | **新增 §9 完成判定的第 5 条：语义层必须自带数据量门槛** | v1.0 假设"训了就有语义"。实测 7 千 token 语料下 recall@5 = 0.026，融进 hybrid 反而低于纯 BM25 | `Engine.Options.minTokensForEmbeddings = 400_000`，低于门槛不启用向量层并打印原因；`--force-vectors` 可强开 |

**没改的**：竞品定位、单 jar 分发、四块硬技术组合、"无清单是宪法"、里程碑的时间预算。§2–§6 与 v1.0 一致。

## 2. 一句话定位

**一个能装进笔记本的本地优先混合搜索引擎：爬虫 → 中文分词 → 自研倒排索引(BM25) → 语料自训词向量(可选) + HNSW → RRF 混合排序 → Web 界面。零外部服务、离线跑通、数据全在你机器上——并且每一层都小到能被一个人读懂。**

## 3. 架构与模块（实现后的真实结构）

```
mini-search/
├── src/main/java/dev/jingyu/ms/
│   ├── analyzer/   归一化 + 双向最大匹配 + 中英混排切分 + 统计新词挖掘(NPMI/邻接熵)
│   ├── index/      倒排索引：term→(docs+positions)、变长编码、删除位图、短语合并
│   ├── ranking/    BM25(k1=1.2,b=0.75) + 字段 boost(title3/body1/tags2)
│   ├── vector/     SGNS 词向量、暴力 KNN、HNSW、Encoder SPI
│   ├── semantic/   分布式同源词典（共现余弦）
│   ├── hybrid/     RRF 与加权融合
│   ├── search/     查询编排、四种模式、高亮与摘要
│   ├── crawl/      robots、限速、64 位指纹、编码探测、链接密度正文抽取
│   ├── api/        JDK HttpServer 路由 + 静态资源
│   ├── core/       引擎装配、快照、语料装载
│   ├── eval/       recall/precision/nDCG/MRR、规模基准
│   ├── mcp/        stdio JSON-RPC 的 MCP server
│   └── util/       JSON、变长编码、日志
├── src/main/resources/static/index.html   单文件前端（搜索页 + 索引管理页）
├── src/test/resources/analyzer-gold.txt   分词 golden master（62 行）
├── data/corpus/    84 篇手写演示语料（随 jar 打包）
├── data/eval/      queries.jsonl(38 条标注) + report.md + bench.json
└── docs/PLAN.md    本文件
```

依赖：**运行期零依赖**。构建期只有 JUnit 5（test scope）与 shade 插件。

## 4. 功能范围

**有**：爬 100 个种子页；建索引（增/删/查）；BM25；同源词典语义扩展；语料自训词向量 + HNSW；RRF 混合；短语查询；高亮与摘要；Web UI；MCP 工具；单 jar；快照持久化。

**无（v2 停车场）**：分布式与分片、faceted 聚合、拼写纠错、学习排序、增量爬虫、多语言分析器、权限系统、**多段归并与原地删除**、**预训练嵌入（ONNX/bge）内置**、**自定义二进制段格式**。

## 5. 检索质量与规模（实测，非目标）

见 `data/eval/report.md` 与 `data/eval/bench.json`。要点：

- 38 条标注查询、k=5：bm25 recall@5 0.934 / nDCG@5 0.912；hybrid nDCG@5 0.918（**高于 bm25**，满足 §9 第 2 条）。
- 词面不匹配子集：nDCG@5 bm25 0.739 → hybrid 0.751。
- 仍有 2 条查询全 miss（同义词/常识鸿沟），点名写在 README 里。
- 5 万文档：倒排构建 13.6 s（<60 s ✓），BM25 p50 10.3 ms，hybrid p50 21.6 ms。
- HNSW recall@10 ≥ 0.95（`VectorTest`，1500 向量对比暴力 KNN）。

## 6. 测试策略（落地情况）

| v1.0 的计划 | 实现 |
|---|---|
| 分词金样例 50 条，diff 即失败 | ✅ 62 条，golden master 存在 `src/test/resources/analyzer-gold.txt`，`scripts/regold.sh` 重建 |
| 索引不变量：随机增删查 1000 轮与暴力扫描一致 | ✅ `IndexTest.randomOperationsMatchBruteForce`（400 轮，每轮内含随机查询比对；受 CI 时间约束） |
| 评测集 recall@5/nDCG@5，数字进 PR | ✅ `mini-search eval` 生成 `data/eval/report.md`；CI 断言 bm25/hybrid recall@5 ≥ 0.90 |
| HNSW 与暴力 KNN 对比 recall@10 ≥ 0.95 | ✅ `VectorTest.hnswRecallAgainstBruteForce`，另测"ef 变小召回不得变好" |
| 爬虫单测本地起服务，不碰外网 | ✅ `InterfaceTest` 用 `com.sun.net.httpserver` 造 404/500/robots 禁爬/无正文/GBK/限速 六类场景 |

## 7. 里程碑状态

- **M1 调研与骨架** ✅ 竞品结论：未找到"教学级可读 + 本地嵌入 + 中文 + 混合排序"同定位仓库；差异化收窄为**可读性 + 评测留档**（若日后出现同定位项目，把叙事进一步收到"每层行数表 + eval 进 CI"）。
- **M2 倒排索引 + BM25** ✅ 验收：recall@5 0.934（门槛 0.90），5 万文档索引 13.6 s（门槛 60 s）。
- **M3 向量与混合** ✅（含 §1 第 6 条的门槛修正）hybrid nDCG@5 0.918 > bm25 0.912；向量层的门槛与失效证据留档。
- **M4 爬虫与正文抽取** ✅ 乱码页/无正文页/robots 禁爬页均有断言的降级行为。
- **M5 前端与打包** ✅ `java -jar mini-search.jar` → `http://localhost:9200` 即得完整体验；jar 180 KB；Docker 与 compose 就位。
- **M6 MCP + Skill + 文档** ✅ 双语 README、每层行数表、快照/评测文档、`skills/mini-search/SKILL.md`、MCP server 与 4 个工具。
- **M7 发布周** ⬜ 需要人工执行（Show HN / 掘金 / V2EX / r/LocalLLaMA / awesome 收录）。素材：`data/eval/report.md` 的两处 miss 与"向量层门槛"是比成功数字更强的谈资；GIF 需在 M5 产物上录制。

## 8. 完成判定

- [x] 单 jar：`java -jar mini-search.jar` → 浏览器 localhost:9200 可搜内置语料
- [x] 评测集数字留档，且 hybrid ≥ bm25（nDCG@5 0.918 vs 0.912；两个子集都不低于纯词法）
- [x] MCP `search` 工具协议层实现并有测试；Skill 可装
- [x] 全测试绿（49/49）、双语 README、LICENSE
- [ ] GIF / 演示录屏（M7 素材，需在浏览器里录）
- [ ] 文章《我如何从零写一个能搜中文的搜索引擎》（M7）

## 9. 风险表复盘

| 风险 | 结果 |
|---|---|
| HNSW 自实现踩坑多 | 未成为瓶颈：一次通过 recall 门槛，成本主要在 ef 旋钮的测试断言上 |
| 嵌入模型体积/许可 | **规避**：不内置模型，改为"语料自训 + 数据量门槛 + Encoder 接口"。许可风险归零 |
| 中文语料许可 | **规避**：手写 CC0 语料，不抓维基 |
| 范围膨胀 | 守住：§4 的"无"清单原样进 v2 停车场 |
| 与主线抢时间 | 部分发生：M2/M3 的时间被"让 vector 不污染 hybrid"的判断吃掉，属于必要支出 |
