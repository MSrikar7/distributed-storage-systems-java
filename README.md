# S3 Index-inspired Java engineering portfolio

Three connected projects, implemented using **Java 21 and the Java standard library**, so no external dependencies, Maven, cloud account, or Docker are needed. The service is **not Amazon S3**, nor is it a production distributed/replicated system.

## Projects

1. **Concurrent Durable Key-Value Engine** (`kv-engine`): in-memory key index protected by read/write locks, append-only checksummed write-ahead log, `FileChannel.force(true)` before write acknowledgement, deletion, restart recovery and truncated-tail handling.
2. **Object Metadata HTTP Service** (`metadata-service`): multithreaded HTTP API backed by the engine, health check, PUT/GET/DELETE and restart persistence. **This is a single-node service**, not a highly available replicated cluster yet.
3. **Concurrent Storage Load Tester** (`storage-load-tester`): configurable workers and request counts, p50/p95/p99 latency, measured requests per second, and HTTP failure count.

## Requirements

Java JDK 21, Bash. Compile with `./scripts/build.sh`.

## Automated tests

```bash
./scripts/test.sh
```

Includes parallel-write checks, delete/restart checks, torn-WAL-tail recovery, and HTTP CRUD/restart tests.

## Run server

```bash
./scripts/build.sh
java -cp build/kv:build/metadata com.s3index.metadata.MetadataServer ./runtime-data 8080
```

In another terminal:

```bash
curl -X PUT --data-binary '{"owner":"alice"}' http://127.0.0.1:8080/metadata/photo-1
curl http://127.0.0.1:8080/metadata/photo-1
curl -X DELETE http://127.0.0.1:8080/metadata/photo-1
java -cp build/load com.s3index.load.LoadTester http://127.0.0.1:8080 8 500
```

## Explicit limitations / engineering roadmap

- **No replication, quorum, leader election or automatic failover.** Do not claim high availability or a distributed cluster on a resume based on this implementation.
- Persistent acknowledged writes use `force(true)` but real durability additionally depends on filesystem, storage hardware and power-loss semantics.
- The current WAL scans at startup and grows without compaction; add snapshots/compaction and disk space accounting next.
- A checksum mismatch in an otherwise complete record fails startup rather than risking silent data corruption.
- Operations within one running process are serialized with a lock at the write-commit point; there is no cross-node consistency guarantee.
- The API binds to loopback only, with no authentication, TLS, user quotas, rate limits or production monitoring.
- The performance tool generates unique PUTs to the local service. Results depend on machine/OS/storage and are **not** comparable to real S3 throughput.
- Suggested future enhancements: PostgreSQL-backed replicated metadata with transactional version checks; HAProxy routing across service instances; fault injection; Docker; JUnit 5; CI; read/write mix workloads; Prometheus metrics.

## Resume integrity

Only claim what has actually been implemented and tested. Project descriptions should say "single-node metadata API" or "concurrent key-value storage", **not** production high availability, 11-nines durability, or large-scale distributed deployment.


## Version 2: storage instrumentation and workload modes

The store exposes lifetime counters: `reads`, `writes`, `read_nanos`, `write_lock_wait_nanos`,
`wal_append_nanos`, and `wal_force_nanos` at `GET /metrics`. Timings are cumulative nanoseconds;
compute an average by dividing the elapsed time sum by the relevant operation count.
These measurements are not per-request percentiles. The write lock wait counter is distinct
from WAL append and force time. Disk synchronization (`force(true)`) is intentionally retained.

PowerShell (run from the repository folder, Java 21 installed):

```powershell
New-Item -ItemType Directory -Force -Path build/kv,build/metadata,build/load,build/test | Out-Null
$kv=@(Get-ChildItem kv-engine/src/main/java -Recurse -Filter *.java | ForEach-Object FullName)
javac -d build/kv $kv
$meta=@(Get-ChildItem metadata-service/src/main/java -Recurse -Filter *.java | ForEach-Object FullName)
javac -cp build/kv -d build/metadata $meta
$load=@(Get-ChildItem storage-load-tester/src/main/java -Recurse -Filter *.java | ForEach-Object FullName)
javac -d build/load $load
java -cp 'build/kv;build/metadata' com.s3index.metadata.MetadataServer ./runtime-data 8080
```

In a second terminal:

```powershell
java -cp build/load com.s3index.load.LoadTester http://127.0.0.1:8080 8 2000 read
java -cp build/load com.s3index.load.LoadTester http://127.0.0.1:8080 8 2000 write
java -cp build/load com.s3index.load.LoadTester http://127.0.0.1:8080 8 2000 mixed
Invoke-RestMethod http://127.0.0.1:8080/metrics
```

`mixed` means 80% GET / 20% PUT. The `read` and `mixed` runs preload 100 keys before timing.
For meaningful comparisons, restart the server between tests if you need reset counters,
use a dedicated test directory, repeat runs, and avoid other CPU/disk-intensive processes.
The HTTP server still runs with a 16-worker pool, and this remains single-node software.

## V3 — WAL group commit (October 2026)

The KV engine uses one dedicated WAL worker to batch up to 64 queued mutations, append them in submission order, and `force(true)` once per batch. Only after successful synchronization are the values published to the in-memory map and the callers acknowledged. Reads do not hold the WAL synchronization lock. `close()` drains queued requests and waits for the WAL worker.

The new `GroupCommitTest` tests 800 concurrent writes, multiple requests per `force`, recovery across restart, defensive copies, and deletes. Run all tests with `bash scripts/test.sh` on macOS/Linux, or use the PowerShell compilation commands below.

**Windows PowerShell:**

```powershell
New-Item -ItemType Directory -Force -Path build/kv,build/metadata,build/load,build/test | Out-Null
$kv = @(Get-ChildItem kv-engine/src/main/java -Recurse -Filter *.java | ForEach-Object FullName)
javac -d build/kv $kv
$meta = @(Get-ChildItem metadata-service/src/main/java -Recurse -Filter *.java | ForEach-Object FullName)
javac -cp build/kv -d build/metadata $meta
$load = @(Get-ChildItem storage-load-tester/src/main/java -Recurse -Filter *.java | ForEach-Object FullName)
javac -d build/load $load
$tests = @(Get-ChildItem kv-engine/src/test/java -Recurse -Filter *.java | ForEach-Object FullName)
javac -cp build/kv -d build/test $tests
java -cp "build/kv;build/test" com.s3index.kv.StoreTest
java -cp "build/kv;build/test" com.s3index.kv.GroupCommitTest
java -cp "build/kv;build/metadata" com.s3index.metadata.MetadataServer ./runtime-data-v3 8082
```

In another terminal:

```powershell
java -cp "build/load" com.s3index.load.LoadTester http://127.0.0.1:8082 16 5000 write
java -cp "build/load" com.s3index.load.LoadTester http://127.0.0.1:8082 16 5000 mixed
Invoke-RestMethod http://127.0.0.1:8082/metrics
```

**Limits:** This is single-process group commit, **not** multi-node replication. `wal_force_nanos` tracks total synchronization work per batch, while `write_lock_wait_nanos` now tracks write queue time until batch append rather than exclusive lock acquisition. The queue is unbounded; a future production version needs backpressure. A disk `force` failure puts the worker in a failed state and rejects pending operations; operators must inspect the WAL before reusing the directory. `FileChannel.force(true)` durability ultimately depends on filesystem and hardware guarantees. Tests cover orderly restarts and torn trailing WAL records, not power-loss verification or fault-injected filesystem errors. Performance gains must be measured on your machine; no numerical speedup is claimed here.

## V4 — two-node synchronous metadata replication demo (October 2026)

This iteration adds an **explicit leader/follower demonstration** with synchronous HTTP forwarding of client mutations. The leader first durably writes its local WAL, forwards the mutation to the follower, and returns HTTP 200 **only after the follower has completed its WAL-backed mutation and returned 200**. A failed/ambiguous follower response returns HTTP 503 and fences all later writes until an operator stops and reconciles the nodes. Followers accept reads and reject ordinary client writes (403). A private `/replica/apply/{key}` endpoint requires a shared secret and applies the forwarded mutations.

**This is not a production high-availability system:** There is **no consensus protocol, election, automatic failover, membership changes, fencing lease, catch-up synchronization, transaction IDs, version checks, snapshot transfer, or split-brain prevention**. A 503 means the leader's local WAL **may already have committed** a mutation; the replica may or may not have applied it. **Never promote the follower after a 503 without reconciling the two nodes.** Only manually promote the follower when the former leader has been shut down *and* you know replication was healthy. Restarting two nodes with divergent WALs is not supported. The in-memory flag only fences while the process is alive, not persistently across restarts. The shared-secret header over plain HTTP is only a localhost learning mechanism, not production transport security. Store directories must never be used by multiple processes concurrently.

### Compile and test on Windows PowerShell

Run from the V4 `s3-index-projects` folder:

```powershell
New-Item -ItemType Directory -Force -Path build/kv,build/metadata,build/load,build/test | Out-Null
$kv = @(Get-ChildItem kv-engine/src/main/java -Recurse -Filter *.java | ForEach-Object FullName)
javac -d build/kv $kv
$meta = @(Get-ChildItem metadata-service/src/main/java -Recurse -Filter *.java | ForEach-Object FullName)
javac -cp build/kv -d build/metadata $meta
$load = @(Get-ChildItem storage-load-tester/src/main/java -Recurse -Filter *.java | ForEach-Object FullName)
javac -d build/load $load
$tests = @(Get-ChildItem kv-engine/src/test/java,metadata-service/src/test/java -Recurse -Filter *.java | ForEach-Object FullName)
javac -cp 'build/kv;build/metadata' -d build/test $tests
java -cp 'build/kv;build/metadata;build/test' com.s3index.metadata.ReplicationTest
```

### Run two nodes (separate PowerShell terminals)

Set the *same demo-only secret in both terminals*. Choose **new distinct directories** for each node. Start the follower first:

```powershell
$env:S3_INDEX_REPLICA_SECRET = 'local-demo-secret'
java -cp 'build/kv;build/metadata' com.s3index.metadata.MetadataServer ./runtime-follower 8091 follower
```

Second PowerShell terminal, start the leader:

```powershell
$env:S3_INDEX_REPLICA_SECRET = 'local-demo-secret'
java -cp 'build/kv;build/metadata' com.s3index.metadata.MetadataServer ./runtime-leader 8090 leader http://127.0.0.1:8091
```

Third PowerShell terminal, test write/replicated read:

```powershell
Invoke-WebRequest -Method Put -Uri http://127.0.0.1:8090/metadata/object-1 -Body 'etag-123' -UseBasicParsing
Invoke-RestMethod http://127.0.0.1:8091/metadata/object-1
Invoke-RestMethod http://127.0.0.1:8090/role
Invoke-RestMethod http://127.0.0.1:8091/role
```

Follower write denial and replication failure behavior are exercised by `ReplicationTest`. To test failure manually, stop the follower before a leader write and expect `503`; **do not treat the two directories as in sync after that**. Restart from new directories for another clean experiment.

### Verified automated tests

`StoreTest`, `GroupCommitTest`, `MetadataTest`, and `ReplicationTest` passed locally. The replication test covers durable follower PUT/DELETE, follower read-only enforcement, shutdown/restart recovery, manual role change only after stopping the previous leader, and fail-closed behavior when the follower becomes unavailable. These tests are not proof against real power loss, network partitions, or concurrent-primary scenarios.
