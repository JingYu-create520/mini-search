package dev.jingyu.ms.eval;

import dev.jingyu.ms.core.Engine;
import dev.jingyu.ms.search.Searcher;
import dev.jingyu.ms.util.Json;
import dev.jingyu.ms.util.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Retrieval metrics over a labelled query file, so "is this change an improvement" is a number and
 * not a vibe.
 *
 * <p>{@code data/eval/queries.jsonl} holds one query per line:
 * <pre>{"query":"...","relevant":["doc-id",...],"kind":"lexical|semantic","note":"why"}</pre>
 * For each mode we take the ranked ids, then compute
 * <ul>
 *   <li><b>recall@k</b> -- share of relevant docs that appear in the top k;</li>
 *   <li><b>precision@k</b> -- share of the top k that are relevant;</li>
 *   <li><b>nDCG@k</b> -- graded gain 1/log2(rank+1) discounted by rank, over the ideal ordering,
 *       which is the one that punishes burying a good hit;</li>
 *   <li><b>MRR</b> -- reciprocal rank of the first hit, i.e. "how soon does the user get an answer".</li>
 * </ul>
 * Everything assumes binary relevance, which is what the file actually labels.
 */
public final class EvalHarness {

    public record Query(String id, String text, List<String> relevant, String kind, String note) {}

    public record Row(String mode, String id, double recall, double precision, double ndcg,
                      double reciprocalRank, int topK) {}

    private EvalHarness() {}

    public static List<Query> loadQueries(Path file) throws IOException {
        List<Query> out = new ArrayList<>();
        int line = 0;
        for (String l : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            line++;
            if (l.isBlank() || l.trim().startsWith("#")) continue;
            Map<String, Object> o = Json.parseObject(l);
            List<String> rel = new ArrayList<>();
            Object r = o.get("relevant");
            if (r instanceof List<?> rl) for (Object x : rl) rel.add(String.valueOf(x));
            out.add(new Query(Json.str(o, "id", "q" + line), Json.str(o, "query", ""), rel,
                    Json.str(o, "kind", "lexical"), Json.str(o, "note", "")));
        }
        return out;
    }

    public static List<Row> evaluate(Searcher searcher, List<Query> queries, Searcher.Mode mode, int k) {
        List<Row> rows = new ArrayList<>();
        for (Query q : queries) {
            List<Integer> ranked = searcher.rankedIds(q.text(), mode, 100);
            List<String> ids = new ArrayList<>();
            for (int docId : ranked) {
                var d = searcher.index().doc(docId);
                if (d != null) ids.add(d.externalId());
            }
            rows.add(new Row(mode.name().toLowerCase(), q.id(), recallAt(ids, q.relevant(), k),
                    precisionAt(ids, q.relevant(), k), ndcgAt(ids, q.relevant(), k), rr(ids, q.relevant()), k));
        }
        return rows;
    }

    // ------------------------------------------------------------------ metrics

    public static double recallAt(List<String> ranked, List<String> relevant, int k) {
        if (relevant.isEmpty()) return Double.NaN;
        Set<String> seen = new HashSet<>(ranked.subList(0, Math.min(k, ranked.size())));
        int hit = 0;
        for (String r : relevant) if (seen.contains(r)) hit++;
        return (double) hit / relevant.size();
    }

    public static double precisionAt(List<String> ranked, List<String> relevant, int k) {
        if (ranked.isEmpty()) return 0;
        Set<String> rel = new HashSet<>(relevant);
        int hit = 0;
        for (int i = 0; i < Math.min(k, ranked.size()); i++) if (rel.contains(ranked.get(i))) hit++;
        return (double) hit / Math.min(k, ranked.size());
    }

    public static double ndcgAt(List<String> ranked, List<String> relevant, int k) {
        Set<String> rel = new HashSet<>(relevant);
        double dcg = 0;
        for (int i = 0; i < Math.min(k, ranked.size()); i++) {
            if (rel.contains(ranked.get(i))) dcg += 1.0 / (Math.log(i + 2) / Math.log(2));
        }
        double idcg = 0;
        for (int i = 0; i < Math.min(k, relevant.size()); i++) idcg += 1.0 / (Math.log(i + 2) / Math.log(2));
        return idcg == 0 ? Double.NaN : dcg / idcg;
    }

    public static double rr(List<String> ranked, List<String> relevant) {
        Set<String> rel = new HashSet<>(relevant);
        for (int i = 0; i < ranked.size(); i++) if (rel.contains(ranked.get(i))) return 1.0 / (i + 1);
        return 0;
    }

    public static Map<String, Double> average(List<Row> rows) {
        Map<String, Double> out = new LinkedHashMap<>();
        double r = 0, p = 0, n = 0, m = 0;
        int c = 0;
        for (Row x : rows) {
            if (Double.isNaN(x.recall())) continue;
            r += x.recall();
            p += x.precision();
            n += x.ndcg();
            m += x.reciprocalRank();
            c++;
        }
        out.put("n", (double) c);
        out.put("recall@k", c == 0 ? 0 : r / c);
        out.put("precision@k", c == 0 ? 0 : p / c);
        out.put("ndcg@k", c == 0 ? 0 : n / c);
        out.put("mrr", c == 0 ? 0 : m / c);
        out.put("top@k_hit", c == 0 ? 0 : rows.stream().filter(x -> x.recall() > 0).count() / (double) c);
        return out;
    }

    // ------------------------------------------------------------------ CLI entry

    public static void run(Engine engine, Map<String, String> opt) throws IOException {
        Path queries = Path.of(opt.getOrDefault("queries", "data/eval/queries.jsonl"));
        int k = Integer.parseInt(opt.getOrDefault("topK", "5"));
        List<Query> qs = loadQueries(queries);
        List<Searcher.Mode> modes = new ArrayList<>(List.of(Searcher.Mode.BM25, Searcher.Mode.SEMANTIC,
                Searcher.Mode.VECTOR, Searcher.Mode.HYBRID));
        Map<String, Map<String, Double>> summary = new LinkedHashMap<>();
        List<Row> all = new ArrayList<>();
        for (Searcher.Mode m : modes) {
            if (m == Searcher.Mode.VECTOR && !engine.searcher().vectorsEnabled()) {
                Log.warn("vector layer off (--no-vectors); skipping mode=vector");
                continue;
            }
            List<Row> rows = evaluate(engine.searcher(), qs, m, k);
            all.addAll(rows);
            summary.put(m.name().toLowerCase(), average(rows));
        }
        Map<String, Map<String, Double>> byKind = new LinkedHashMap<>();
        for (String kind : List.of("lexical", "semantic")) {
            List<Query> subset = qs.stream().filter(q -> kind.equals(q.kind())).toList();
            if (subset.isEmpty()) continue;
            for (Searcher.Mode m : modes) {
                if (!engine.searcher().vectorsEnabled() && m == Searcher.Mode.VECTOR) continue;
                byKind.put(m.name().toLowerCase() + ":" + kind, average(evaluate(engine.searcher(), subset, m, k)));
            }
        }
        String md = report(qs, all, summary, byKind, k);
        Path out = Path.of(opt.getOrDefault("out", "data/eval/report.md"));
        Files.createDirectories(out.getParent());
        Files.writeString(out, md, StandardCharsets.UTF_8);
        System.out.println(md);
        System.out.println("# report written to " + out);
    }

    static String report(List<Query> qs, List<Row> rows, Map<String, Map<String, Double>> summary,
                         Map<String, Map<String, Double>> byKind, int k) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Evaluation report\n\n");
        sb.append("Generated by `mini-search eval` against `data/eval/queries.jsonl` (")
                .append(qs.size()).append(" labelled queries, binary relevance, k=").append(k).append(").\n\n");
        sb.append("| mode | recall@").append(k).append(" | precision@").append(k)
                .append(" | nDCG@").append(k).append(" | MRR | queries with a hit |\n");
        sb.append("|---|---|---|---|---|---|\n");
        List<String> order = new ArrayList<>(summary.keySet());
        order.sort(Comparator.comparing(x -> -summary.get(x).get("ndcg@k")));
        for (String mode : order) {
            Map<String, Double> s = summary.get(mode);
            sb.append(String.format("| %s | %.3f | %.3f | %.3f | %.3f | %d/%d |%n", mode,
                    s.get("recall@k"), s.get("precision@k"), s.get("ndcg@k"), s.get("mrr"),
                    Math.round(s.get("top@k_hit") * s.get("n")), s.get("n").intValue()));
        }
        if (!byKind.isEmpty()) {
            sb.append("\n## Split by query type\n\n");
            sb.append("`semantic` queries are written so the query words do not appear in the target ")
                    .append("document -- that is the case BM25 cannot reach and the vector layer exists for.\n\n");
            sb.append("| mode:kind | recall@").append(k).append(" | nDCG@").append(k).append(" |\n|---|---|---|\n");
            for (Map.Entry<String, Map<String, Double>> e : byKind.entrySet()) {
                sb.append(String.format("| %s | %.3f | %.3f |%n", e.getKey(),
                        e.getValue().get("recall@k"), e.getValue().get("ndcg@k")));
            }
        }
        sb.append("\n## Per query\n\n");
        sb.append("| query | kind | BM25 | semantic | vector | hybrid | relevant docs |\n");
        sb.append("|---|---|---|---|---|---|---|\n");
        for (Query q : qs) {
            sb.append("| ").append(q.text().replace("|", "/")).append(" | ").append(q.kind()).append(" |");
            for (String mode : List.of("bm25", "semantic", "vector", "hybrid")) {
                String top = "-";
                for (Row r : rows) {
                    if (r.mode().equals(mode) && r.id().equals(q.id())) {
                        top = r.recall() > 0 ? "hit" : "miss";
                    }
                }
                sb.append(" ").append(top).append(" |");
            }
            sb.append(" ").append(q.relevant().size()).append(" |\n");
        }
        return sb.toString();
    }
}
