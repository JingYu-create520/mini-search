#!/usr/bin/env bash
# Zero-Maven developer loop: compile with javac and run the CLI. `mvn package` is the release path.
set -e
rm -rf target/classes
mkdir -p target/classes
javac -Xmaxerrs 60 -encoding UTF-8 -d target/classes $(find src/main/java -name '*.java')
cp -r src/main/resources/. target/classes/
cp -r data/corpus target/classes/corpus
echo "compiled OK"
