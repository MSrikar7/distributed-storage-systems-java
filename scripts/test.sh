#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
./scripts/build.sh
java -cp build/kv:build/test com.s3index.kv.StoreTest
java -cp build/kv:build/test com.s3index.kv.GroupCommitTest
java -cp build/kv:build/metadata:build/test com.s3index.metadata.MetadataTest
