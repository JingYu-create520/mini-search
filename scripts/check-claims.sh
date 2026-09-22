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

if [ "$fail" = 1 ]; then
  echo
  echo "Fix the tables in README.md / README.zh-CN.md, and docs/ARTICLE.zh-CN.md + docs/LAUNCH.md if"
  echo "they repeat the same figure, so the advertised numbers match the tree again."
  exit 1
fi
echo "all advertised counts match the tree: ${MAIN} main lines / ${FILES} files / ${CASES} tests"
