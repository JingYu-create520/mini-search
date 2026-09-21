package dev.jingyu.ms;

import dev.jingyu.ms.analyzer.ChineseAnalyzer;
import dev.jingyu.ms.analyzer.Lexicon;
import dev.jingyu.ms.api.HttpApi;
import dev.jingyu.ms.core.Corpus;
import dev.jingyu.ms.core.Engine;
import dev.jingyu.ms.crawl.Crawler;
import dev.jingyu.ms.eval.Bench;
import dev.jingyu.ms.eval.EvalHarness;
import dev.jingyu.ms.mcp.McpServer;
import dev.jingyu.ms.search.Searcher;
import dev.jingyu.ms.util.Json;
import dev.jingyu.ms.util.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Command line entry point. Every command opens the same {@link Engine} the HTTP server and the MCP
 * server expose, so there is exactly one code path from a query to a ranked list.
 *
 * <pre>
 *   java -jar mini-search.jar                       # build from the bundled corpus, serve on :9200
 *   java -jar mini-search.jar serve --corpus DIR --port 9200
 *   java -jar mini-search.jar search "中文分词" --mode hybrid
 *   java -jar mini-search.jar index notes.jsonl
 *   java -jar mini-search.jar crawl https://example.com --max 20
 *   java -jar mini-search.jar eval                  # recall@5 / nDCG@5 per mode
 *   java -jar mini-search.jar bench --docs 50000    # index-scale timing
 *   java -jar mini-search.jar analyze "文本"
 *   java -jar mini-search.jar mcp                   # stdio MCP server
 * </pre>
 */
public final class MiniSearch {

    public static void main(String[] args) throws Exception {        if (args.length > 0 && (args[0].equals("--help") || args[0].equals("-h"))) {
            System.out.println(usage());
            return;
        }
        String cmd = args.length == 0 ? "serve" : args[0];
        Map<String, String> opt = parseArgs(args, cmd.startsWith("-") ? 0 : 1);
        if (cmd.startsWith("-")) opt.putAll(parseArgs(args, 0));

        switch (cmd) {
            case "serve", "-s" -> serve(opt);
            case "search" -> search(opt);
            case "index" -> index(opt);
            case "delete" -> delete(opt);
            case "crawl" -> crawl(opt);
            case "eval" -> EvalHarness.run(open(opt, true), opt);
            case "bench" -> Bench.run(opt);
            case "analyze" -> analyze(opt);
            case "regold" -> regold(opt);
            case "vector" -> vector(opt);
            case "stats" -> System.out.println(Json.write(open(opt).stats()));
            case "mcp" -> new McpServer(open(opt)).serve(System.in, System.out);
            case "build" -> {
                Engine e = open(opt);
                System.out.println(Json.write(e.stats()));
            }
            default -> {
                System.out.println(usage());
                System.exit(2);
            }
        }
    }

    private static String usage() {
        return """
                mini-search - local-first hybrid search engine for Chinese, single jar, no dependencies

                usage: java -jar mini-search.jar <command> [options]

                commands
                  serve        start the web UI + HTTP API (default command)
                  search TEXT  one-shot query, prints ranked hits
                  index FILE   index a JSONL file ({id,title,body,url,tags})
                  delete ID    remove a document by its id
                  crawl URL    fetch and index pages, breadth first
                  eval         run the labelled query set, print recall@5 / nDCG@5 per mode
                  bench        measure index build time at scale
                  analyze TEXT show the analyser's cut
                  regold      regenerate the segmentation golden master (review the diff)
                  vector WORD  nearest vocabulary words by cosine
                  stats        print engine statistics as JSON
                  mcp          serve the MCP tool interface over stdio

                options
                  --data DIR     index/snapshot directory            (default: data)
                  --corpus DIR   index this JSONL directory instead of the bundled corpus
                  --port N       HTTP port for serve                 (default: 9200)
                  --mode M       bm25 | vector | hybrid              (default: hybrid)
                  --topK N       results to return                   (default: 10)
                  --no-vectors   skip training, BM25 only (fastest start)
                  --no-mining    skip statistical new-word discovery
                  --knn auto|hnsw|brute
                  --epochs N     word2vec epochs
                  --stopwords    enable stopword removal (measurable, off by default)
                  --dict FILE    extra dictionary for the analyser
                  --model DIR    local pretrained embeddings, e.g. models/bge-small-zh-v1.5
                                 (needs scripts/fetch-model.sh and -cp libs/onnxruntime.jar)
                  --pool cls|mean  dense pooling for the pretrained encoder (default cls)
                  --no-instruct    drop the query-side instruction the Chinese BGE models expect
                  --fusion a,b,c   RRF weights for [bm25, thesaurus, dense], e.g. 1,0.5,6
                  --rebuild      ignore any snapshot on disk
                  --quiet        no progress output
                """;
    }

    // ------------------------------------------------------------------ commands

    private static void serve(Map<String, String> opt) throws IOException, InterruptedException {
        Engine engine = open(opt);
        int port = Integer.parseInt(opt.getOrDefault("port", "9200"));
        HttpApi api = new HttpApi(engine, port).withCrawler(Crawler.standard());
        api.start();
        Map<String, Object> s = engine.stats();
        System.out.printf("%n  mini-search is up  ->  http://localhost:%d/%n", port);
        System.out.printf("  %s documents, %s terms, %s mined words, encoder=%s, knn=%s%n",
                s.get("documents"), s.get("terms"), s.get("minedWords"), s.get("encoder"), s.get("knn"));
        System.out.printf("  endpoints: %s%n%n", String.join("  ", HttpApi.endpoints()));
        Runtime.getRuntime().addShutdownHook(new Thread(api::stop));
        Thread.currentThread().join();
    }

    private static void search(Map<String, String> opt) {
        String q = opt.getOrDefault("_", "").trim();
        if (q.isEmpty()) {
            System.err.println("usage: search \"query\" [--mode hybrid] [--topK 10]");
            return;
        }
        Engine e = open(opt);
        Searcher.Mode mode = Searcher.Mode.parse(opt.get("mode"), Searcher.Mode.HYBRID);
        int topK = Integer.parseInt(opt.getOrDefault("topK", "10"));
        Searcher.Result r = e.searcher().search(q, mode, topK, 0, false, true);
        List<Object> out = new ArrayList<>();
        for (var h : r.hits()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rank", h.rank() + 1);
            m.put("id", h.id());
            m.put("title", h.title());
            m.put("score", Math.round(h.score() * 1000) / 1000.0);
            m.put("snippet", h.snippet());
            out.add(m);
        }
        System.out.println(Json.write(out));
    }

    private static void index(Map<String, String> opt) throws IOException {
        Engine e = open(opt);
        String file = opt.getOrDefault("_", "").trim();
        List<Corpus.RawDoc> docs;
        if (file.startsWith("http://") || file.startsWith("https://")) {
            Crawler.standard().fetchAndIndex(e, file);
            System.out.println(Json.write(e.stats()));
            return;
        }
        if (file.isEmpty()) {
            System.err.println("usage: index FILE.jsonl");
            return;
        }
        docs = Corpus.parseJsonl(Files.readString(Path.of(file)), Path.of(file).getFileName().toString());
        int n = 0;
        for (Corpus.RawDoc d : docs) {
            e.addRaw(d);
            n++;
        }
        e.refreshVectors();
        persist(e, opt);
        System.out.println(Json.write(Map.of("indexed", n, "documents", e.index().numDocs())));
    }

    private static void delete(Map<String, String> opt) throws IOException {
        Engine e = open(opt);
        String id = opt.getOrDefault("_", "").trim();
        boolean ok = e.delete(id);
        persist(e, opt);
        System.out.println(Json.write(Map.of("deleted", ok, "id", id)));
    }

    private static void crawl(Map<String, String> opt) throws IOException {
        Engine e = open(opt);
        String url = opt.getOrDefault("_", "").trim();
        if (url.isEmpty()) {
            System.err.println("usage: crawl URL [--max 20] [--depth 2]");
            return;
        }
        int max = Integer.parseInt(opt.getOrDefault("max", "10"));
        int depth = Integer.parseInt(opt.getOrDefault("depth", "1"));
        List<Crawler.CrawlResult> results = Crawler.standard().crawl(e, List.of(url), max, depth);
        persist(e, opt);
        List<Object> out = new ArrayList<>();
        for (Crawler.CrawlResult r : results) out.add(r.toMap());
        System.out.println(Json.write(out));
    }

    private static void analyze(Map<String, String> opt) {
        Engine e = open(opt);
        String text = opt.getOrDefault("_", "");
        List<Object> out = new ArrayList<>();
        for (var t : e.analyzer().analyze(text)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("term", t.term());
            m.put("start", t.start());
            m.put("end", t.end());
            out.add(m);
        }
        System.out.println(Json.write(out));
    }

    /**
     * Rewrite the golden segmentation master from the current analyser. Intentional analyser changes
     * go through here, and the reviewer is the diff: run it, read what changed, then commit.
     */
    private static void regold(Map<String, String> opt) throws IOException {
        Path sents = Path.of(opt.getOrDefault("sents", "src/test/resources/analyzer-sents.txt"));
        Path out = Path.of(opt.getOrDefault("out", "src/test/resources/analyzer-gold.txt"));
        Lexicon lex = Lexicon.core();
        if (opt.containsKey("dict")) lex.loadFile(Path.of(opt.get("dict")));
        ChineseAnalyzer a = new ChineseAnalyzer(lex.seal());
        StringBuilder sb = new StringBuilder();
        sb.append("# Golden segmentation master: sentence <TAB> expected cuts (space separated).\n");
        sb.append("# Generated by `mini-search regold` from analyzer-sents.txt. Review the diff before\n");
        sb.append("# committing; AnalyzerTest fails on any difference.\n");
        int n = 0;
        for (String line : Files.readAllLines(sents, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            sb.append(line).append('\t').append(String.join(" ", a.terms(line))).append('\n');
            n++;
        }
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
        System.out.println(n + " gold rows -> " + out);
    }

    private static void vector(Map<String, String> opt) {
        Engine e = open(opt);
        String word = opt.getOrDefault("_", "").trim();
        if (e.model() == null) {
            System.err.println("no vector layer: rebuild without --no-vectors");
            return;
        }
        System.out.println(Json.write(e.model().nearest(word, Integer.parseInt(opt.getOrDefault("topK", "10")))));
    }

    // ------------------------------------------------------------------ engine lifecycle

    /** Open the engine: reuse a snapshot when it matches the corpus, otherwise build and save. */
    private static Engine open(Map<String, String> opt) {
        return open(opt, false);
    }

    /**
     * @param ignoreSnapshot measure from the corpus instead of from whatever is lying in
     *     {@code data/}. {@code eval} uses this: a snapshot left behind by an earlier run --
     *     one indexed with the optional bge model, say -- silently flattened all three modes
     *     down to bm25's numbers, which is the opposite of "every claim has a number you can
     *     reproduce". The snapshot is neither read nor written on this path.
     */
    private static Engine open(Map<String, String> opt, boolean ignoreSnapshot) {
        Log.setQuiet(Boolean.parseBoolean(opt.getOrDefault("quiet", "false")) || opt.containsKey("quiet"));
        Path data = Path.of(opt.getOrDefault("data", "data"));
        Engine.Options o = new Engine.Options();
        if (opt.containsKey("no-vectors")) o.trainVectors = false;
        if (opt.containsKey("force-vectors")) o.forceVectors = true;
        if (opt.containsKey("no-mining")) o.mine = false;
        if (opt.containsKey("stopwords")) o.stopwords = true;
        if (opt.containsKey("dict")) o.dictPath = opt.get("dict");
        if (opt.containsKey("model")) o.modelPath = opt.get("model");
        if (opt.containsKey("pool")) o.clsPooling = !"mean".equalsIgnoreCase(opt.get("pool"));
        if (opt.containsKey("no-instruct")) o.queryInstruction = false;
        if (opt.containsKey("max-pieces")) o.maxWordPieces = Integer.parseInt(opt.get("max-pieces"));
        if (opt.containsKey("knn")) o.knn = opt.get("knn");
        if (opt.containsKey("epochs")) o.epochs = Integer.parseInt(opt.get("epochs"));
        if (opt.containsKey("dim")) o.dim = Integer.parseInt(opt.get("dim"));
        if (opt.containsKey("k1")) o.k1 = Double.parseDouble(opt.get("k1"));
        if (opt.containsKey("b")) o.b = Double.parseDouble(opt.get("b"));

        List<Corpus.RawDoc> docs = null;
        try {
            docs = opt.containsKey("corpus") ? Corpus.loadDir(Path.of(opt.get("corpus"))) : Corpus.loadDemo();
        } catch (IOException e) {
            Log.warn("cannot read --corpus: %s", e.getMessage());
        }
        Path snap = data.resolve("index.msnap");
        boolean rebuild = ignoreSnapshot || opt.containsKey("rebuild") || !Files.exists(snap) || docs == null;
        if (ignoreSnapshot && Files.exists(snap)) {
            Log.info("eval measures a freshly built index; the existing %s was ignored", snap);
        }
        Engine e;
        if (rebuild) {
            e = Engine.build(docs == null ? List.of() : docs, o);
            if (!ignoreSnapshot) {
                try {
                    Files.createDirectories(data);
                    e.save(snap);
                } catch (IOException ex) {
                    Log.warn("snapshot not saved: %s", ex.getMessage());
                }
            }
        } else {
            e = Engine.restore(snap, o, docs);
        }
        if (opt.containsKey("fusion")) {
            String[] parts = opt.get("fusion").split("[, ]+");
            double[] w = new double[parts.length];
            for (int i = 0; i < parts.length; i++) w[i] = Double.parseDouble(parts[i]);
            e.searcher().setFusionWeights(w);
        }
        return e;
    }

    private static void persist(Engine e, Map<String, String> opt) throws IOException {
        Path data = Path.of(opt.getOrDefault("data", "data"));
        Files.createDirectories(data);
        e.save(data.resolve("index.msnap"));
    }

    // ------------------------------------------------------------------ args

    private static Map<String, String> parseArgs(String[] args, int from) {
        Map<String, String> out = new LinkedHashMap<>();
        List<String> positional = new ArrayList<>();
        for (int i = from; i < args.length; i++) {
            String a = args[i];
            if (a.startsWith("--")) {
                String key = a.substring(2);
                int eq = key.indexOf('=');
                if (eq >= 0) {
                    out.put(key.substring(0, eq), key.substring(eq + 1));
                } else if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                    out.put(key, args[++i]);
                } else {
                    out.put(key, "");
                }
            } else {
                positional.add(a);
            }
        }
        out.put("_", String.join(" ", positional));
        return out;
    }
}
