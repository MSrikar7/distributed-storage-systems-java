#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/kv build/metadata build/load build/test
javac -d build/kv kv-engine/src/main/java/com/s3index/kv/*.java
javac -cp build/kv -d build/metadata metadata-service/src/main/java/com/s3index/metadata/*.java
javac -d build/load storage-load-tester/src/main/java/com/s3index/load/*.java
javac -cp build/kv -d build/test kv-engine/src/test/java/com/s3index/kv/*.java
javac -cp build/kv:build/metadata -d build/test metadata-service/src/test/java/com/s3index/metadata/*.java
