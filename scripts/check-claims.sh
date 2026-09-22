#!/usr/bin/env bash
# Fails when a number advertised in the README tables has drifted away from the tree.
#
#   scripts/check-claims.sh
#
# The repo's pitch is "every claim has a reproducible number", so the numbers that describe the repo
# itself have to be true too. Line counts and test counts move with every commit and nobody updates
# them, which is how a README ends up advertising 53 tests while `mvn test` prints 57. This is the
# cheap tripwire; CI runs it.
set -uo pipefail
cd "$(dirname "$0")/.."

fail=0
check() { # label claimed actual
  if [ -z "${2:-}" ] || [ "$2" != "$3" ]; then
    echo "::error::$1 -- the docs say '${2:-not found}', the tree says $3"
    fail=1
  else
    printf 'ok   %-26s %s\n' "$1" "$2"
  fi
}

# cell FILE "row label substring" column -> first integer found in that pipe-separated table cell
cell() {
  awk -F'|' -v pat="$2" -v col="$3" '
    index($0, pat) {
      if (match($col, /[0-9][0-9,]*/)) {
        v = substr($col, RSTART, RLENGTH)
        gsub(/,/, "", v)
        print v
        exit
      }
    }' "$1"
}

count() { cat "$@" | wc -l | tr -d ' '; }
module() { count $(find "src/main/java/dev/jingyu/ms/$1" -name '*.java'); }

MAIN=$(count $(find src/main/java -name '*.java'))
FILES=$(find src/main/java -name '*.java' | wc -l | tr -d ' ')
TESTLINES=$(count $(find src/test/java -name '*.java'))
CASES=$(grep -h "@Test" src/test/java/dev/jingyu/ms/*.java | wc -l | tr -d ' ')
UI=$(wc -l < src/main/resources/static/index.html | tr -d ' ')
UTILCLI=$(( $(module util) + $(count src/main/java/dev/jingyu/ms/MiniSearch.java) ))

for f in README.md README.zh-CN.md; do
  [ -f "$f" ] || continue
  for d in analyzer index ranking vector hybrid crawl api core eval search semantic mcp; do
    check "$f $d/" "$(cell "$f" "\`$d/\`" 3)" "$(module $d)"
  done
  check "$f util+CLI" "$(cell "$f" "\`util/\`" 3)" "$UTILCLI"
  check "$f main"  "$(cell "$f" '**main**' 3)$(cell "$f" '**主代码合计**' 3)" "$MAIN"
  check "$f files" "$(cell "$f" '**main**' 4)$(cell "$f" '**主代码合计**' 4)" "$FILES"
  check "$f tests" "$(cell "$f" '| tests ' 3)$(cell "$f" '| 测试 ' 3)" "$TESTLINES"
  check "$f cases" "$(cell "$f" '| tests ' 4)$(cell "$f" '| 测试 ' 4)" "$CASES"
  check "$f ui"    "$(cell "$f" '| UI ' 3)$(cell "$f" '| 前端 ' 3)" "$UI"
  # the quickstart block repeats the test count in prose
  check "$f dev-block" "$(grep -oE 'mvn -B test[^#]*#[^0-9]*[0-9]+' "$f" | head -1 | grep -oE '[0-9]+$')" "$CASES"
done

# The launch copy repeats the same figures in prose, in two languages, and in a fixed-width module
# block. It is the text that goes out in public, so it drifts exactly as easily as the tables.
ARTICLE=docs/ARTICLE.zh-CN.md
if [ -f "$ARTICLE" ]; then
  for d in analyzer index ranking semantic vector hybrid search crawl; do
    check "article $d/" "$(awk -v m="$d/" '$1==m {print $2; exit}' "$ARTICLE")" "$(module $d)"
  done
  MAXFILE=$(wc -l $(find src/main/java -name '*.java') | sort -rn | sed -n 2p | awk '{print $1}')
  check "article maxfile" "$(grep -oE '单个文件最长 [0-9]+ 行' "$ARTICLE" | grep -oE '[0-9]+')" "$MAXFILE"
  # no prose line count anywhere in the docs may disagree with the tree (4+ digit ones only: 800/453
  # are per-file claims about other things, and "38 个" is a doc count)
  for f in "$ARTICLE" docs/LAUNCH.md; do
    [ -f "$f" ] || continue
    stale=$(grep -oE '[0-9][0-9,]{3,6} ?行|[0-9][0-9,]{3,6} lines' "$f" | grep -oE '[0-9][0-9,]*' | tr -d , | sort -u | grep -v "^${MAIN}$" || true)
    if [ -n "$stale" ]; then
      echo "::error::$f quotes line count(s) $(echo $stale | tr '\n' ' ') but src/main is $MAIN lines"
      fail=1
    else
      printf 'ok   %-26s %s\n' "$f line counts" "$MAIN"
    fi
  done
  check "launch cases" "$(grep -oE '[0-9]+ 个测试' docs/LAUNCH.md | grep -oE '[0-9]+' | head -1)" "$CASES"
fi

# ---------------------------------------------------------------------------
# The quality tables in the READMEs are quotes from data/eval/report.md and report-bge.md, and they
# had already drifted apart once: a thesaurus-mode nDCG was sitting in the hybrid column, so the
# README credited the wrong layer with the win. The repo's pitch is "every claim has a committed
# number behind it", so every quoted cell is now compared against the report it came from.
# Only mismatches print; the total is summarised at the end.

# tcell FILE LABEL COL [OCCURRENCE] -> a markdown table cell, selected by the row's leading cell
# (prefix match, so "semantic" finds both "| semantic |" and "| semantic (thesaurus expansion) |"),
# disambiguated by which occurrence of that label in the file to take.
tcell() {
  awk -F'|' -v lab="$2" -v c="$3" -v occ="${4:-1}" '
    NF > 3 { k = $2; gsub(/[ *]/, "", k)
             if (index(k, lab) == 1 && ++seen[lab] == occ) { v = $c; gsub(/[ *]/, "", v); print v; exit } }' "$1"
}

QUOTE=0
mcell() { # label DOCFILE DOCLABEL DOCCOL DOCCCC OCC SRCFILE SRCLABEL SRCCOL
  local d s
  d=$(tcell "$2" "$3" "$4" "$5"); s=$(tcell "$6" "$7" "$8" 1)
  QUOTE=$((QUOTE + 1))
  if [ "$d" != "$s" ]; then
    echo "::error::$1 -- the doc says $d, ${6##*/} says $s"
    fail=1
  fi
}
mpair() { # label DOCFILE DOCLABEL DOCCOL SRCFILE SRCLABEL  (the doc cell holds "recall / nDCG")
  local d a b s1 s2
  d=$(tcell "$2" "$3" "$4" 1); a=${d%%/*}; b=${d##*/}
  s1=$(tcell "$5" "$6" 3 1); s2=$(tcell "$5" "$6" 4 1)
  QUOTE=$((QUOTE + 2))
  if [ "$a" != "$s1" ] || [ "$b" != "$s2" ]; then
    echo "::error::$1 -- the doc says $a/$b, $6 in ${5##*/} is $s1/$s2"
    fail=1
  fi
}

R0=data/eval/report.md
R1=data/eval/report-bge.md
if [ -f "$R0" ] && [ -f "$R1" ]; then
  for f in README.md README.zh-CN.md; do
    [ -f "$f" ] || continue
    case "$f" in
      README.md) LEX=lexical- MIS=word- ;;
      *)         LEX=词面匹配 MIS=词面不匹配 ;;
    esac
    # out-of-the-box table: recall, precision, nDCG, MRR, hits -- same order as report.md
    for row in bm25 semantic hybrid; do
      for c in 3 4 5 6 7; do
        mcell "$f $row#$c" "$f" "$row" "$c" 1 "$R0" "$row" "$c"
      done
    done
    # with-model table: recall, nDCG, MRR, hits (precision dropped); bm25/hybrid are 2nd occurrence
    for spec in "bm25 3 2 bm25 3" "bm25 4 2 bm25 5" "bm25 5 2 bm25 6" "bm25 6 2 bm25 7" \
                "hybrid 3 2 hybrid 3" "hybrid 4 2 hybrid 5" "hybrid 5 2 hybrid 6" "hybrid 6 2 hybrid 7" \
                "vector 3 1 vector 3" "vector 4 1 vector 5" "vector 5 1 vector 6" "vector 6 1 vector 7"; do
      # shellcheck disable=SC2086
      set -- $spec
      mcell "$f bge $1#$2" "$f" "$1" "$2" "$3" "$R1" "$4" "$5"
    done
    # per-query-kind split: one doc column per mode, "recall / nDCG" in each cell
    for spec in "$LEX 3 bm25:lexical" "$LEX 4 semantic:lexical" "$LEX 5 hybrid:lexical" \
                "$MIS 3 bm25:semantic" "$MIS 4 semantic:semantic" "$MIS 5 hybrid:semantic"; do
      # shellcheck disable=SC2086
      set -- $spec
      mpair "$f split $1#$2" "$f" "$1" "$2" "$R0" "$3"
    done
  done
  if [ "$fail" = 0 ]; then
    printf 'ok   %-26s %s\n' "quoted metrics vs reports" "$QUOTE cells agree"
  fi
fi

# ---------------------------------------------------------------------------
# The Scale table quotes data/eval/bench.json. Latency in particular is a property of the machine a
# run happened to land on, which is exactly why the artifact and the table have to agree: a reader who
# runs `bench` and gets a different number should find the doc admitting that, not catching it out.
BJ=data/eval/bench.json
if [ -f "$BJ" ]; then
  jv() { grep -oE "\"$1\":-?[0-9.]+" "$BJ" | sed -n "${2:-1}p" | cut -d: -f2; }
  dec() { awk -v v="${1:-}" 'BEGIN { if (v != "") printf "%.'"${2:-1}"'f", v }'; }
  # rawcell: the text of a table cell, uninterpreted. cell() above stops at the first non-integer,
  # which is right for line counts and wrong for "211.3 s".
  rawcell() {
    awk -F'|' -v pat="$2" -v col="$3" 'NF > 3 && index($2,pat){gsub(/^[ \t]+|[ \t]+$/,"",$col); print $col; exit}' "$1"
  }
  num() { echo "$1" | grep -oE '[0-9]+(\.[0-9]+)?' | sed -n "${2}p"; }
  pct() { echo "$1" | grep -oE "$2 [0-9]+(\.[0-9]+)?" | sed -E "s/^$2 //" | head -1; }
  for f in README.md README.zh-CN.md; do
    [ -f "$f" ] || continue
    row() { rawcell "$f" "$1" 3; }
    build="$(row 'engine build')$(row '整机构建')"
    check "bench build in $f"       "$(num "$build" 1)" "$(dec "$(jv indexBuildSeconds)" 0)"
    check "bench docs/s in $f"      "$(num "$build" 2)" "$(dec "$(jv docsPerSecond)" 1)"
    check "bench mining in $f"      "$(num "$(row 'dictionary mining')$(row '词典挖掘')" 1)" "$(dec "$(jv mining)" 1)"
    check "bench index in $f"       "$(num "$(row 'inverted index build')$(row '倒排索引构建')" 1)" "$(dec "$(jv invertedIndex)" 1)"
    check "bench word2vec in $f"    "$(num "$(row 'word2vec training')$(row 'word2vec 训练')" 1)" "$(dec "$(jv word2vec)" 1)"
    check "bench hnsw in $f"        "$(num "$(row 'HNSW graph')$(row 'HNSW 建图')" 1)" "$(dec "$(jv vectorIndex)" 1)"
    check "bench vec layer in $f"   "$(num "$(row 'vector layer')$(row '向量层')" 1)" "$(dec "$(jv vectorLayer)" 1)"
    check "bench thesaurus in $f"   "$(num "$(row 'thesaurus build')$(row '同源词典构建')" 1)" "$(dec "$(jv thesaurus)" 1)"
    check "bench bm25 p50 in $f"    "$(pct "$(row 'BM25 query latency')$(row 'BM25 查询延迟')" p50)" "$(dec "$(jv p50ms)" 1)"
    check "bench bm25 p95 in $f"    "$(pct "$(row 'BM25 query latency')$(row 'BM25 查询延迟')" p95)" "$(dec "$(jv p95ms)" 1)"
    check "bench hyb p50 in $f"     "$(pct "$(row 'hybrid query latency')$(row 'hybrid 查询延迟')" p50)" "$(dec "$(jv p50ms 2)" 1)"
    check "bench hyb p95 in $f"     "$(pct "$(row 'hybrid query latency')$(row 'hybrid 查询延迟')" p95)" "$(dec "$(jv p95ms 2)" 1)"
    check "bench heap in $f"        "$(num "$(row 'heap in use')$(row '堆占用')" 1)" \
      "$(awk -v mb="$(jv usedHeapMB)" 'BEGIN{printf "%.1f", mb/1024}')"
  done
fi

if [ "$fail" = 1 ]; then
  echo
  echo "Fix the tables in README.md / README.zh-CN.md, and docs/ARTICLE.zh-CN.md + docs/LAUNCH.md if"
  echo "they repeat the same figure, so the advertised numbers match the tree again."
  exit 1
fi
echo "all advertised counts match the tree: ${MAIN} main lines / ${FILES} files / ${CASES} tests"
