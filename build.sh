#!/usr/bin/env bash
# Zero-Maven developer loop: compile with javac and run the CLI. `mvn package` is the release path.
# ONNX Runtime is a `provided` dependency, so it goes on the compile classpath when present but is
# never packaged into the jar -- see scripts/fetch-model.sh.
set -e
rm -rf target/classes
mkdir -p target/classes
CP=$(printf '%s;' $(ls libs/*.jar 2>/dev/null) | sed 's/;$//')
if [ -n "$CP" ]; then
  javac -encoding UTF-8 -cp "$CP" -Xmaxerrs 60 -d target/classes $(find src/main/java -name '*.java')
else
  javac -encoding UTF-8 -Xmaxerrs 60 -d target/classes \
    $(find src/main/java -name '*.java' ! -name 'OnnxEncoder.java')
  echo "note: libs/onnxruntime.jar missing -- compiled without the optional ONNX encoder"
fi
cp -r src/main/resources/. target/classes/
cp -r data/corpus target/classes/corpus
echo "compiled OK"
