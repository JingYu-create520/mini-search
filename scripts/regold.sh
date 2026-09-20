#!/usr/bin/env bash
# Regenerate the golden segmentation master after an intentional analyser change.
# Usage: scripts/regold.sh [--dict my.dict]
# Then READ THE DIFF. The gold file is the contract AnalyzerTest enforces.
set -e
cd "$(dirname "$0")/.."
if [ ! -d target/classes ]; then ./build.sh; fi
java -cp target/classes dev.jingyu.ms.MiniSearch regold "$@"
git --no-pager diff --stat -- src/test/resources/analyzer-gold.txt || true
git --no-pager diff -- src/test/resources/analyzer-gold.txt | head -60 || true
