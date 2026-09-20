#!/usr/bin/env bash
# Pull the optional local embedding model + ONNX Runtime into ./models and ./libs.
#
#   scripts/fetch-model.sh                    # bge-small-zh-v1.5, int8 ONNX, ~24 MB
#   ENDPOINT=https://huggingface.co scripts/fetch-model.sh
#
# Default endpoint is hf-mirror.com because it works from a mainland-China connection without a
# proxy; the base jar does not need any of this -- it only unlocks `--model models/bge-small-zh-v1.5`.
set -eu
cd "$(dirname "$0")/.."

ENDPOINT="${ENDPOINT:-https://hf-mirror.com}"
REPO="${REPO:-Xenova/bge-small-zh-v1.5}"
FILE="${FILE:-onnx/model_quantized.onnx}"
RUNTIME_VERSION="${RUNTIME_VERSION:-1.19.2}"
MODEL_DIR="models/bge-small-zh-v1.5"
RUNTIME_URL="https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime/${RUNTIME_VERSION}/onnxruntime-${RUNTIME_VERSION}.jar"

mkdir -p "$MODEL_DIR/onnx" libs

get() { # url dest
  echo "  $1"
  curl -fL --retry 3 --max-time "${4:-900}" -o "$2" "$1"
}

get "$ENDPOINT/$REPO/resolve/main/$FILE" "$MODEL_DIR/onnx/model_quantized.onnx"
get "$ENDPOINT/$REPO/resolve/main/vocab.txt" "$MODEL_DIR/vocab.txt"
get "$ENDPOINT/$REPO/resolve/main/config.json" "$MODEL_DIR/config.json"
get "$RUNTIME_URL" "libs/onnxruntime.jar"

echo
echo "ready. run it with the runtime on the classpath:"
echo "  java -cp \"target/mini-search.jar;libs/onnxruntime.jar\" dev.jingyu.ms.MiniSearch \\"
echo "       search \"为什么海边城市冬天不太冷\" --model $MODEL_DIR --topK 5"
ls -la "$MODEL_DIR/onnx/model_quantized.onnx" "$MODEL_DIR/vocab.txt" libs/onnxruntime.jar
