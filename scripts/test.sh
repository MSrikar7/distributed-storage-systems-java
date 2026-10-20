#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
bash scripts/build.sh
classpath="build/kv:build/metadata:build/test"
for test in \
  com.s3index.kv.StoreTest \
  com.s3index.kv.GroupCommitTest \
  com.s3index.metadata.MetadataTest \
  com.s3index.metadata.ReplicationTest \
  com.s3index.metadata.ReconciliationTest \
  com.s3index.metadata.PrometheusMetricsTest; do
  echo "==> $test"
  java -cp "$classpath" "$test"
done
