package dev.jingyu.ms.eval;

import dev.jingyu.ms.core.Corpus;
import dev.jingyu.ms.core.Engine;
import dev.jingyu.ms.search.Searcher;
import dev.jingyu.ms.util.Json;
import dev.jingyu.ms.util.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Scale benchmark: synthesise N documents by recombining sentences from the real demo corpus, then
 * time the build and the query path.
 *
 * <p>The documents are synthetic on purpose. The point is not to pretend we have 50k hand-written
 * articles; it is to answer "does the inverted index, the mining pass and the k-NN build still behave
 * at that size, and where does the time go". Query latencies are measured with real queries from the
 * eval file so the p50/p95 numbers are about the same workload the report describes.
 */
public final class Bench {

    private Bench() {}

    public static void run(Map<String, String> opt) throws IOException {
        int docs = Integer.parseInt(opt.getOrDefault("docs", "50000"));
        boolean vectors = !opt.containsKey("no-vectors");
        boolean mine = !opt.containsKey("no-mining");
        Path corpusDir = Path.of(opt.getOrDefault("corpus", "data/corpus"));
        List<Corpus.RawDoc> seeds = Files.isDirectory(corpusDir) ? Corpus.loadDir(corpusDir) : Corpus.loadDemo();
        if (seeds.isEmpty()) {
            System.err.println("no seed corpus found for bench");
            return;
        }
        List<Corpus.RawDoc> synth = synthesize(seeds, docs, Long.parseLong(opt.getOrDefault("seed", "7")));
        Log.info("bench: %d synthetic documents (seeded from %d real ones)", docs, seeds.size());

        Engine.Options o = new Engine.Options();
        o.mine = mine;
        o.trainVectors = vectors;
        o.knn = opt.getOrDefault("knn", vectors ? "hnsw" : "brute");
        o.epochs = Integer.parseInt(opt.getOrDefault("epochs", vectors ? "2" : "0"));
        o.dim = Integer.parseInt(opt.getOrDefault("dim", "120"));

        long t0 = System.nanoTime();
        Engine e = Engine.build(synth, o);
        double buildSecs = (System.nanoTime() - t0) / 1e9;

        List<String> queries = new ArrayList<>();
        Path qf = Path.of(opt.getOrDefault("queries", "data/eval/queries.jsonl"));
        if (Files.exists(qf)) {
            for (EvalHarness.Query q : EvalHarness.loadQueries(qf)) queries.add(q.text());
        }
        if (queries.isEmpty()) for (int i = 0; i < Math.min(20, seeds.size()); i++) queries.add(seeds.get(i).title());

        Map<String, double[]> latency = new LinkedHashMap<>();
        for (Searcher.Mode mode : List.of(Searcher.Mode.BM25, Searcher.Mode.HYBRID)) {
            if (mode == Searcher.Mode.HYBRID && !e.searcher().vectorsEnabled()) continue;
            List<Double> ms = new ArrayList<>();
            for (int rep = 0; rep < 3; rep++) {
                for (String q : queries) {
                    long s = System.nanoTime();
                    e.searcher().search(q, mode, 10, 0, false, true);
                    ms.add((System.nanoTime() - s) / 1e6);
                }
            }
            Collections.sort(ms);
            latency.put(mode.name().toLowerCase(),
                    new double[]{pct(ms, 0.5), pct(ms, 0.95), ms.get(ms.size() - 1)});
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("documents", docs);
        out.put("indexBuildSeconds", round(buildSecs));
        out.put("docsPerSecond", round(docs / buildSecs));
        out.put("mining", mine);
        out.put("vectors", vectors);
        out.put("terms", e.index().vocabularySize());
        out.put("minedWords", e.minedWordCount());
        Map<String, Object> lat = new LinkedHashMap<>();
        latency.forEach((k, v) -> lat.put(k, Map.of("p50ms", round(v[0]), "p95ms", round(v[1]), "maxms", round(v[2]))));
        out.put("latency", lat);
        out.put("queries", queries.size());
        out.put("jvmMaxHeapMB", Runtime.getRuntime().maxMemory() / (1 << 20));
        out.put("usedHeapMB", (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1 << 20));
        String json = Json.write(out);
        System.out.println(json);
        Path report = Path.of(opt.getOrDefault("out", "data/eval/bench.json"));
        Files.createDirectories(report.getParent());
        Files.writeString(report, json, StandardCharsets.UTF_8);
        Log.info("bench written to %s", report);
    }

    /** Recombine real sentences into fresh documents; topics mix, which is what stress-tests scoring. */
    static List<Corpus.RawDoc> synthesize(List<Corpus.RawDoc> seeds, int n, long seed) {
        List<String> bodies = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        for (Corpus.RawDoc d : seeds) {
            titles.add(d.title());
            for (String s : d.body().split("(?<=[。！？])")) {
                String t = s.trim();
                if (t.length() > 8) bodies.add(t);
            }
        }
        Random rnd = new Random(seed);
        List<Corpus.RawDoc> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Corpus.RawDoc base = seeds.get(rnd.nextInt(seeds.size()));
            int paras = 2 + rnd.nextInt(3);
            StringBuilder sb = new StringBuilder();
            for (int p = 0; p < paras; p++) {
                int sentences = 2 + rnd.nextInt(3);
                for (int s = 0; s < sentences; s++) sb.append(bodies.get(rnd.nextInt(bodies.size())));
                sb.append('\n');
            }
            String title = titles.get(rnd.nextInt(titles.size())) + "（变体 " + i + "）";
            out.add(new Corpus.RawDoc("bench-" + i, "", title, sb.toString().trim(), base.tags()));
        }
        return out;
    }

    private static double pct(List<Double> sorted, double p) {
        if (sorted.isEmpty()) return 0;
        return sorted.get((int) Math.min(sorted.size() - 1, Math.ceil(p * sorted.size()) - 1));
    }

    private static Object round(double v) { return Math.round(v * 1000) / 1000.0; }
}
