package dev.jingyu.ms;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.jingyu.ms.core.Corpus;
import dev.jingyu.ms.core.Engine;
import dev.jingyu.ms.crawl.Crawler;
import dev.jingyu.ms.crawl.Fingerprint;
import dev.jingyu.ms.crawl.HtmlExtractor;
import dev.jingyu.ms.crawl.Robots;
import dev.jingyu.ms.crawl.UrlPolicy;
import dev.jingyu.ms.eval.EvalHarness;
import dev.jingyu.ms.mcp.McpServer;
import dev.jingyu.ms.search.Searcher;
import dev.jingyu.ms.util.Json;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** JSON codec, HTML extraction, robots parsing, the polite crawler, MCP framing, and end to end. */
class InterfaceTest {

    // ------------------------------------------------------------------ json

    @Test
    void jsonRoundTripsNestedStructures() {
        String src = "{\"a\":1,\"b\":[true,null,\"中\\u6587\"],\"c\":{\"d\":-2.5,\"e\":[]}}";
        Object parsed = Json.parse(src);
        String out = Json.write(parsed);
        assertEquals(1.0, Json.obj(parsed).get("a"));
        assertTrue(out.contains("中文"), "CJK must be written verbatim, not escaped: " + out);
        assertEquals("中\u6587".equals("中文") ? "中文" : "", Json.arr(Json.obj(parsed).get("b")).get(2));
    }

    @Test
    void jsonRejectsMalformedInput() {
        assertThrows(RuntimeException.class, () -> Json.parse("{\"a\":"));
        assertThrows(RuntimeException.class, () -> Json.parse("{\"a\":1,}"));
        assertThrows(RuntimeException.class, () -> Json.parse("nope"));
    }

    @Test
    @DisplayName("递归下降要有天花板：超深嵌套是异常，不是 StackOverflowError")
    void jsonRefusesAbsurdNestingInsteadOfBlowingTheStack() {
        String deep = "[".repeat(Json.MAX_DEPTH + 8) + "]".repeat(Json.MAX_DEPTH + 8);
        // Asserting the *type* is the point: without the cap this throws StackOverflowError, an Error
        // that every `catch (RuntimeException)` on the HTTP and MCP paths walks straight past.
        assertThrows(IllegalArgumentException.class, () -> Json.parse(deep));
        assertThrows(IllegalArgumentException.class,
                () -> Json.parseObject("{\"a\":" + "{".repeat(500) + "}".repeat(500) + "}"));
        // A depth anyone would actually post still parses.
        Object nested = Json.parse("[".repeat(40) + "1" + "]".repeat(40));
        assertTrue(nested instanceof List);
    }

    // ------------------------------------------------------------------ extraction

    @Test
    void articleBeatsNavigationAndFooter() {
        StringBuilder nav = new StringBuilder("<div id=\"nav\">");
        for (int i = 0; i < 40; i++) nav.append("<a href=\"/p").append(i).append("\">推荐链接").append(i).append("</a>");
        nav.append("</div>");
        String html = "<html><head><title>混合检索的实践&#19982;总结</title></head><body>" + nav
                + "<article><p>倒排索引把词项映射到包含它的文档列表，查询时只需要合并posting列表。</p>"
                + "<p>向量检索把句子映射成高维空间中的点，语义相近的文本彼此距离更近。</p>"
                + "<p>两者用倒数排名融合在一起，比单独任何一路都更稳。</p></article>"
                + "<footer><a href=\"/about\">关于</a> <a href=\"/contact\">联系</a></footer></body></html>";
        HtmlExtractor.Page page = HtmlExtractor.extract(html, "https://example.com/post");
        assertTrue(page.text().contains("倒排索引"), () -> "body lost: " + page.text());
        assertTrue(page.text().contains("倒数排名"));
        assertFalse(page.text().contains("推荐链接"), "navigation must not be treated as prose");
        assertFalse(page.text().contains("关于"), "footer must not be treated as prose");
        assertTrue(page.title().contains("与"), "numeric entity must be decoded: " + page.title());
        assertEquals(42, page.links().size(), "40 navigation links plus 2 in the footer");
    }

    @Test
    void pageWithNoProseDegradesGracefully() {
        HtmlExtractor.Page p = HtmlExtractor.extract(
                "<html><body><a href=/>x</a><script>var a=1;function f(){}</script></body></html>",
                "https://example.com/");
        assertEquals("", p.text().trim(), "no prose must mean empty text, not an exception");
        assertTrue(p.links().isEmpty() || p.links().size() == 1);
        assertEquals("", HtmlExtractor.extract(null, "https://e.com/").title());
        assertEquals("", HtmlExtractor.extract("   ", "https://e.com/").text());
    }

    @Test
    void relativeLinksResolveAgainstThePageUrl() {
        HtmlExtractor.Page p = HtmlExtractor.extract(
                "<html><body><a href=\"/a/b\">锚文本一</a><a href=\"c.html\">锚文本二</a>"
                        + "<a href=\"#x\">锚文本三</a><a href=\"javascript:void(0)\">锚文本四</a>"
                        + "<p>正文需要足够长才能被判定为文章内容，而不是导航区域的一部分。</p></body></html>",
                "https://example.com/dir/page.html");
        assertTrue(p.links().contains("https://example.com/a/b"), p.links().toString());
        assertTrue(p.links().contains("https://example.com/dir/c.html"), p.links().toString());
        assertTrue(p.links().stream().noneMatch(s -> s.endsWith("#x") || s.startsWith("javascript")),
                "fragments and javascript: urls are not links: " + p.links());
    }

    @Test
    @DisplayName("robots 规则：最长前缀优先，通配符生效，全程不联网")
    void robotsRulesAreParsedAndLongestPrefixWins() {
        Robots r = new Robots("mini-search/0.1").preload("https://x.com/a",
                "User-agent: *\nDisallow: /private\nAllow: /private/public.html\n\n"
                        + "User-agent: other-bot\nDisallow: /\n");
        assertTrue(r.allowed("https://x.com/public/page"));
        assertFalse(r.allowed("https://x.com/private/page"));
        assertTrue(r.allowed("https://x.com/private/public.html"), "the longer Allow must win");
        assertTrue(r.allowed("https://x.com/"), "a file that restricts only /private leaves the root open");
        Robots wild = new Robots("mini-search/0.1").preload("https://y.com/a",
                "User-agent: mini-search\nDisallow: /*.php$\nCrawl-delay: 2\n");
        assertFalse(wild.allowed("https://y.com/s?id=1.php"));
        assertTrue(wild.allowed("https://y.com/s.php?x=1"), "$ anchors the end of the pattern");
        assertTrue(wild.allowed("https://y.com/docs/intro"));
        Robots nothing = new Robots("mini-search/0.1");
        assertTrue(nothing.allowed("https://unreachable.invalid/x"),
                "a robots.txt that cannot be reached publishes no rules, so the path is not restricted");
    }

    @Test
    void urlFingerprintNormalisesBeforeHashing() {
        assertEquals(Fingerprint.of("https://EXAMPLE.com/a/?x=1&y=2#frag"),
                Fingerprint.of("https://example.com/a?y=2&x=1"));
        assertEquals(Fingerprint.of("https://example.com/a"), Fingerprint.of("https://example.com/a/"));
        assertNotEquals(Fingerprint.of("https://example.com/a"), Fingerprint.of("https://example.com/b"));
    }

    // ------------------------------------------------------------------ crawler, against a local server

    private HttpServer server;
    private int port;
    /** Per-path request counts, so "did the crawler fetch this page once or twice?" is answerable. */
    private final Map<String, java.util.concurrent.atomic.AtomicInteger> requests =
            new java.util.concurrent.ConcurrentHashMap<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        handler("/ok.html", 200, "text/html; charset=utf-8", article().getBytes(StandardCharsets.UTF_8));
        handler("/robots.txt", 200, "text/plain; charset=utf-8", "User-agent: *\nDisallow: /secret\n".getBytes());
        handler("/secret/page.html", 200, "text/html; charset=utf-8", article().getBytes());
        handler("/missing.html", 404, "text/html; charset=utf-8", "<html><body>nope</body></html>".getBytes());
        handler("/broken.html", 500, "text/html; charset=utf-8", new byte[0]);
        handler("/empty.html", 200, "text/html; charset=utf-8",
                "<html><head><title>只有标题</title></head><body><ul><li><a href=/1>一</a></li></ul></body></html>".getBytes());
        handler("/gbk.html", 200, "text/html", gbkBytes());
        handler("/charset-mismatch.html", 200, "text/html; charset=iso-8859-1", article().getBytes(StandardCharsets.UTF_8));
        redirect("/moved.html", "/ok.html");
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    private void handler(String path, int status, String type, byte[] body) {
        server.createContext(path, ex -> {
            requests.computeIfAbsent(path, k -> new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet();
            respond(ex, status, type, body);
        });
    }

    private int requestsTo(String path) {
        return requests.containsKey(path) ? requests.get(path).get() : 0;
    }

    private void redirect(String path, String to) {
        server.createContext(path, ex -> {
            requests.computeIfAbsent(path, k -> new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet();
            ex.getResponseHeaders().add("Location", "http://127.0.0.1:" + port + to);
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
    }

    private static void respond(HttpExchange ex, int status, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().add("Content-Type", type);
        ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream os = ex.getResponseBody()) { os.write(body); }
        }
        ex.close();
    }

    private static String article() {
        return "<html><head><title>礼貌爬虫的三条底线</title></head><body><div id=nav><a href=/1>首页</a>"
                + "<a href=/2>文档</a><a href=/3>关于</a></div><article><p>抓取别人的站点要先看 robots 协议里"
                + "禁止爬取的路径，越线的页面一个都不要碰。</p><p>其次要按域名限速，同一台服务器两次请求之间"
                + "留够间隔，并且在请求头里留下可联系的身份标识。</p><p>最后要对重复网址去重，避免在参数排列组合"
                + "里反复绕圈，把带宽花在同一个页面上。</p></article><footer><a href=/4>备案</a></footer></body></html>";
    }

    private static byte[] gbkBytes() {
        String html = "<html><head><meta charset=\"gbk\"><title>编码探测</title></head><body><p>这个页面用"
                + "GBK 声明字符集，服务器却不告诉内容类型，解码必须靠文档内的声明和字节特征。</p></body></html>";
        return html.getBytes(Charset.forName("GBK"));
    }

    private String url(String p) { return "http://127.0.0.1:" + port + p; }

    /**
     * These tests crawl a server on 127.0.0.1, which the production target policy refuses on purpose.
     * They say so here rather than relying on the code under test being lax by default.
     */
    private Crawler localCrawler(long minIntervalMillis) {
        return new Crawler("mini-search-test", minIntervalMillis, 1 << 20, 5000, UrlPolicy.ALLOW_PRIVATE);
    }

    @Test
    @DisplayName("爬虫：正常页面被抓取、抽取并进入索引")
    void crawlIndexesAGoodPage() {
        Engine e = Engine.empty(new Engine.Options().mining(false).vectors(false));
        Crawler.CrawlResult r = localCrawler(0).fetchAndIndex(e, url("/ok.html"));
        assertTrue(r.ok(), () -> "expected success, got " + r.reason());
        assertEquals("礼貌爬虫的三条底线", r.title());
        assertEquals(1, e.index().numDocs());
        assertEquals(List.of(0), e.searcher().rankedIds("robots 协议 限速", Searcher.Mode.BM25, 5));
    }

    @Test
    @DisplayName("M4 验收：坏页面一律降级，不抛异常、不脏索引")
    void crawlReportsFailuresInsteadOfThrowing() {
        Engine e = Engine.empty(new Engine.Options().mining(false).vectors(false));
        Crawler c = localCrawler(0);
        assertEquals("HTTP 404", c.fetchAndIndex(e, url("/missing.html")).reason());
        assertEquals("HTTP 500", c.fetchAndIndex(e, url("/broken.html")).reason());
        Crawler.CrawlResult empty = c.fetchAndIndex(e, url("/empty.html"));
        assertTrue(empty.ok(), "a page with no prose is still searchable by title, so it is kept");
        assertEquals(0, empty.chars(), "but it contributes no body text");
        assertFalse(c.fetchAndIndex(e, "ftp://x/y").ok());
        assertEquals("only http(s) URLs are crawled", c.fetchAndIndex(e, "ftp://x/y").reason());
        assertFalse(c.fetchAndIndex(e, "not a url").ok());
        assertFalse(c.fetchAndIndex(e, "").ok());
        assertEquals(1, e.index().numDocs(), "only the title-only page may have entered the index");
    }

    @Test
    void robotsDisallowsAreRespected() {
        Engine e = Engine.empty(new Engine.Options().mining(false).vectors(false));
        Crawler c = localCrawler(0);
        Crawler.CrawlResult r = c.fetchAndIndex(e, url("/secret/page.html"));
        assertFalse(r.ok(), "the server publishes a robots.txt that forbids /secret");
        assertEquals("disallowed by robots.txt", r.reason());
        assertEquals(0, e.index().numDocs());
    }

    @Test
    void gbkPagesDecodeWithoutMojibake() {
        Engine e = Engine.empty(new Engine.Options().mining(false).vectors(false));
        Crawler.CrawlResult r = localCrawler(0).fetchAndIndex(e, url("/gbk.html"));
        assertTrue(r.ok(), r.reason());
        assertTrue(e.searcher().rankedIds("字符集 声明", Searcher.Mode.BM25, 5).contains(0),
                "GBK body must be decoded, not replaced with question marks");
    }

    @Test
    @DisplayName("302 会被跟随：抽取的是落地页，不是跳转壳")
    void redirectsAreFollowed() {
        Engine e = Engine.empty(new Engine.Options().mining(false).vectors(false));
        Crawler.CrawlResult r = localCrawler(0).fetchAndIndex(e, url("/moved.html"));
        assertTrue(r.ok(), r.reason());
        assertEquals("礼貌爬虫的三条底线", r.title(), "that title only exists on the page the redirect lands on");
    }

    @Test
    void crawlerRateLimitsPerHost() {
        Engine e = Engine.empty(new Engine.Options().mining(false).vectors(false));
        Crawler c = localCrawler(400);
        long t0 = System.nanoTime();
        c.fetchAndIndex(e, url("/ok.html"));
        c.fetchAndIndex(e, url("/charset-mismatch.html"));
        long elapsed = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(elapsed >= 350, "two requests to one host must be spaced apart, took " + elapsed + "ms");
    }

    @Test
    @DisplayName("默认策略拒绝内网目标：这个测试服务器本身就是 127.0.0.1")
    void defaultPolicyRefusesALoopbackTarget() {
        Engine e = Engine.empty(new Engine.Options().mining(false).vectors(false));
        Crawler.CrawlResult r = new Crawler("mini-search-test", 0, 1 << 20, 5000)
                .fetchAndIndex(e, url("/ok.html"));
        assertFalse(r.ok(), "the four-argument crawler is the production one: public addresses only");
        assertTrue(r.reason().startsWith("refused: loopback target"), () -> "reason was " + r.reason());
        assertEquals(0, requestsTo("/ok.html"), "a refused target must not be requested at all");
        assertEquals(0, requestsTo("/robots.txt"), "not even robots.txt is fetched for a refused host");
        assertEquals(0, e.index().numDocs());
    }

    @Test
    void urlPolicyKeepsPrivateAndMetadataRangesOut() {
        UrlPolicy p = UrlPolicy.STANDARD;
        for (String blocked : List.of("http://127.0.0.1/x", "http://localhost:9200/x", "http://[::1]/x",
                "http://169.254.169.254/latest/meta-data/", "http://10.0.0.5/", "http://192.168.1.1/admin",
                "http://172.16.5.4/", "http://100.64.0.1/", "http://0.0.0.0/", "http://[fc00::1]/",
                "http://224.0.0.5/", "file:///etc/passwd")) {
            assertFalse(p.check(blocked).allowed(), () -> blocked + " should be refused");
        }
        // Literal public addresses: no DNS, so this half of the test works offline like the rest.
        for (String ok : List.of("http://8.8.8.8/", "https://1.1.1.1/robots.txt", "http://93.184.216.34/")) {
            assertTrue(p.check(ok).allowed(), () -> ok + " should be allowed");
        }
        assertTrue(UrlPolicy.ALLOW_PRIVATE.check("http://127.0.0.1:9200/x").allowed());
        assertTrue(UrlPolicy.ALLOW_PRIVATE.check("gopher://127.0.0.1/").allowed() == false,
                "allowing private addresses is not the same as allowing other schemes");
    }

    @Test
    @DisplayName("收链接不许重抓：一个页面一次请求")
    void crawlReusesThePageItAlreadyFetchedForLinkHarvesting() {
        Engine e = Engine.empty(new Engine.Options().mining(false).vectors(false));
        List<Crawler.CrawlResult> rs = localCrawler(0).crawl(e, List.of(url("/ok.html")), 1, 2);
        assertEquals(1, rs.size());
        assertTrue(rs.get(0).ok(), rs.get(0).reason());
        assertEquals(1, requestsTo("/ok.html"),
                "the body parsed for indexing is the body the links come from; a second GET doubles"
                        + " the request rate against the host and breaks the politeness promise");
    }

    // ------------------------------------------------------------------ mcp

    @Test
    void mcpHandshakeAndToolCall() {
        Engine e = Engine.build(Corpus.loadDemo(), new Engine.Options().vectors(false));
        McpServer mcp = new McpServer(e);
        String init = mcp.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}");
        assertTrue(init.contains("protocolVersion"), init);
        assertTrue(init.contains("mini-search"), init);
        assertNull(mcp.handle("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"),
                "notifications must not be answered");
        String listed = mcp.handle("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
        assertTrue(listed.contains("\"search\"") && listed.contains("inputSchema"), listed);
        assertFalse(listed.contains("index_url"),
                "this server has no crawler attached, so it must not advertise a tool that only fails");
        assertTrue(new McpServer(e).withCrawler(dev.jingyu.ms.crawl.Crawler.standard())
                        .handle("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}")
                        .contains("index_url"),
                "with a crawler attached the tool is there");
        String called = mcp.handle("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":"
                + "{\"name\":\"search\",\"arguments\":{\"query\":\"倒排索引\",\"topK\":3}}}");
        assertTrue(called.contains("tech-001"), "the tool must return the right document: " + called);
        assertTrue(called.contains("\"isError\":false"));
        String unknown = mcp.handle("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"bogus\"}");
        assertTrue(unknown.contains("-32601"), unknown);
        assertTrue(mcp.handle("{not json").contains("-32700"));
        // A nesting bomb must answer with an error and leave the session usable: the whole point of the
        // parser's depth cap is that this arrives as an IllegalArgumentException, not a StackOverflowError
        // that walks past the catch and kills the stdio loop.
        String bomb = "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/list\",\"params\":{\"a\":"
                + "[".repeat(4000) + "]".repeat(4000) + "}}";
        assertTrue(mcp.handle(bomb).contains("-32700"), "the bomb must be refused with a reply");
        assertTrue(mcp.handle("{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tools/list\"}")
                        .contains("\"result\""),
                "and the next request must still be served");
    }

    // ------------------------------------------------------------------ end to end

    @Test
    @DisplayName("M2 验收：内置语料上 BM25 的 recall@5 必须达到计划门槛")
    void bm25MeetsTheAcceptanceBar() throws IOException {
        Engine e = Engine.build(Corpus.loadDemo(), new Engine.Options());
        List<EvalHarness.Query> queries = EvalHarness.loadQueries(Path.of("data/eval/queries.jsonl"));
        assertTrue(queries.size() >= 30, "the labelled query set must cover at least 30 queries");
        double recall = EvalHarness.average(EvalHarness.evaluate(e.searcher(), queries, Searcher.Mode.BM25, 5))
                .get("recall@k");
        assertTrue(recall >= 0.90, "recall@5 on the demo corpus dropped to " + recall);
        long withHit = EvalHarness.evaluate(e.searcher(), queries, Searcher.Mode.BM25, 5)
                .stream().filter(r -> r.recall() > 0).count();
        assertTrue(withHit >= Math.ceil(queries.size() * 0.9),
                withHit + "/" + queries.size() + " queries got a hit in the top 5");
    }

    @Test
    @DisplayName("M3 验收：hybrid 的 nDCG@5 不得低于纯 BM25")
    void hybridIsNotWorseThanLexical() throws IOException {
        Engine e = Engine.build(Corpus.loadDemo(), new Engine.Options());
        List<EvalHarness.Query> queries = EvalHarness.loadQueries(Path.of("data/eval/queries.jsonl"));
        double bm25 = EvalHarness.average(EvalHarness.evaluate(e.searcher(), queries, Searcher.Mode.BM25, 5)).get("ndcg@k");
        double hybrid = EvalHarness.average(EvalHarness.evaluate(e.searcher(), queries, Searcher.Mode.HYBRID, 5)).get("ndcg@k");
        assertTrue(hybrid >= bm25 - 1e-9, "hybrid nDCG@5 " + hybrid + " must not fall below bm25 " + bm25);
    }

    @Test
    void metricsMatchHandComputedValues() {
        List<String> ranked = List.of("a", "b", "c", "d", "e");
        assertEquals(1.0, EvalHarness.recallAt(ranked, List.of("a", "c"), 5), 1e-9, "both labels are inside@5");
        assertEquals(0.5, EvalHarness.recallAt(ranked, List.of("a", "z"), 5), 1e-9);
        assertEquals(0.4, EvalHarness.precisionAt(ranked, List.of("a", "c"), 5), 1e-9);
        assertEquals(1.0 / 3, EvalHarness.rr(ranked, List.of("c")), 1e-9, "third place gets a third of the credit");
        assertEquals(0.0, EvalHarness.rr(ranked, List.of("zzz")), 1e-9);
        double ideal = (1 + 1 / (Math.log(3) / Math.log(2))) / (1 + 1 / (Math.log(3) / Math.log(2)));
        assertEquals(1.0, EvalHarness.ndcgAt(List.of("a", "c"), List.of("a", "c"), 5), 1e-9, "ideal order");
        assertEquals(ideal, EvalHarness.ndcgAt(List.of("a", "c"), List.of("a", "c"), 5), 1e-9);
        assertEquals(0.0, EvalHarness.ndcgAt(List.of("x", "y"), List.of("a"), 5), 1e-9);
        assertTrue(Double.isNaN(EvalHarness.recallAt(ranked, List.of(), 5)), "no labels means no measurement");
    }

    @Test
    void corpusManifestListsEveryFile() throws IOException {
        Path dir = Path.of("data/corpus");
        assertTrue(Files.isDirectory(dir), "tests run from the repository root");
        String manifest = Files.readString(dir.resolve("manifest.txt"));
        List<String> listed = manifest.lines().map(String::trim)
                .filter(s -> !s.isEmpty() && !s.startsWith("#")).toList();
        List<String> present;
        try (var s = Files.list(dir)) {
            present = s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".jsonl")).sorted().toList();
        }
        assertEquals(present, listed.stream().sorted().toList(),
                "a corpus file is not in manifest.txt, so it would silently not ship in the jar");
    }

    @Test
    void demoCorpusIsBundledForTheJar() {
        List<Corpus.RawDoc> docs = Corpus.loadDemo();
        assertEquals(84, docs.size(), "the demo corpus shipped in the jar");
        assertTrue(docs.stream().allMatch(d -> !d.body().isBlank()));
        assertEquals(docs.size(), docs.stream().map(Corpus.RawDoc::id).distinct().count(),
                "two documents sharing an id would silently overwrite each other on index");
    }

    @Test
    @DisplayName("dump 导得出来运行时写进去的东西：index → dump → 再解析回 RawDoc")
    void dumpExportsRuntimeAddsAsRebuildableCorpus(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        Path incoming = tmp.resolve("in.jsonl");
        Files.writeString(incoming, "{\"id\":\"added\",\"title\":\"运行时新增\","
                + "\"body\":\"这条走的是 POST /api/index 那条路径\"}\n");
        Path data = tmp.resolve("data");
        Files.createDirectories(data);

        MiniSearch.main(new String[]{"index", incoming.toString(), "--data", data.toString(),
                "--no-vectors", "--quiet"});
        Path out = tmp.resolve("all.jsonl");
        MiniSearch.main(new String[]{"dump", "--data", data.toString(), "--out", out.toString(),
                "--no-vectors", "--quiet"});

        List<Corpus.RawDoc> back = Corpus.parseJsonl(Files.readString(out, StandardCharsets.UTF_8), "dump");
        assertEquals(85, back.size(), "the bundled 84 plus the one added at runtime");
        Corpus.RawDoc added = back.stream().filter(d -> d.id().equals("added")).findFirst()
                .orElseThrow(() -> new AssertionError("a document indexed after the build must be exported"));
        assertEquals("运行时新增", added.title());
        assertTrue(added.body().contains("POST /api/index"), "bodies round-trip verbatim");
    }
}
