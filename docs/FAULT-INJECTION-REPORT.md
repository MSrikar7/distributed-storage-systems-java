# Controlled fault injection: follower outage and leader restart

## Purpose and system boundary

This report documents a localhost two-node Java 21 storage **prototype** (leader at `127.0.0.1:8090`, follower at `127.0.0.1:8091`). It uses synchronous numbered mutations, a persisted pending intent and operator-triggered reconciliation. It does **not** implement Raft, quorum, automatic failover, production authentication, or a linearizability guarantee.

The goal was to test two safety behaviors: (1) when replication becomes uncertain, an uncommitted value is not exposed by the leader and further leader writes are fenced; (2) after a controlled leader restart, the recovery state is retained, and explicit reconciliation allows normal writes to resume.

## Environment

- Windows PowerShell; Java 21; local HTTP service, no external infrastructure.
- Project modules: `kv-engine`, `metadata-service`, `storage-load-tester`.
- Uses dedicated persistent directories for each node; restarting must reuse the **same** directories.
- Fault model: an **orderly process stop** with Ctrl+C, not sudden power loss, packet-level partition, or kernel crash.

## Observed manual experiments — October 21, 2026

| Scenario | Observed response | Interpretation |
| --- | --- | --- |
| Normal replication | Leader PUT `object-1`: `Stored`; follower GET: `etag-123` | Initial replica propagation worked |
| Follower unavailable during PUT | `HTTP 503`, `Replication uncertain` | Write not acknowledged |
| Read pending key on leader | `HTTP 404`, `Not found` | Pending value not exposed locally |
| Leader health while fenced | `HTTP 503`, `REPLICATION_UNCERTAIN` | Failed replication reported |
| Follower restarted; explicit reconciliation | `HTTP 200`, `RECONCILED` | Operator recovery completed |
| Recovered key on both nodes | Both returned `etag-456` | Both held expected test value |
| Service recovery and next write | Leader health `200 OK`; new PUT `Stored`; follower read `etag-789` | Normal writes resumed |
| New pending write; leader restarted | Restarted leader health `503 REPLICATION_UNCERTAIN` | Fence persisted across orderly leader restart |
| Recovery after restarted leader | Follower returned `recover-after-restart`; health `200 OK`; new write returned `Stored` and follower read `after-recovery` | Recovery and forward progress observed |

For the final restart experiment, the supplied console excerpt did not include the `/admin/reconcile` response or leader read of the recovered key, so those individual response codes were **not independently recorded** in that excerpt. The independent automated `ReconciliationTest` did pass and covers these invariants.

## Automated regression test added

`LeaderRestartFaultInjectionTest` checks the entire sequence with temporary data directories and an in-process orderly shutdown/restart of services. Checks include:

1. Successful baseline replication.
2. Follower stopped, leader returns 503 for a new write.
3. Pending value hidden on leader, health becomes 503, further writes blocked.
4. Leader closed and reopened using the same data; health remains 503 and writes stay blocked.
5. Follower reopened using its prior data and address; operator POST `/admin/reconcile` returns 200.
6. Recovered value appears on both nodes; health returns 200; new replicated writes succeed.

The test does not simulate actual process killing, network packet loss, power failure, multi-leader races, or consistency during uncertain follower reads. Do not claim those behaviors were tested.

## Local test result

On October 21, 2026, the automated fault-injection test passed, as did all six existing test suites. GroupCommitTest recorded 800 writes and 98 fsync batches in that local run.

## How to run (Windows PowerShell)

From repository root:

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
.\scripts\build-windows.ps1
java -cp "build/kv;build/metadata;build/test" com.s3index.metadata.LeaderRestartFaultInjectionTest
```

Expected final line (only if the test succeeds):

```text
PASS: outage fencing; hidden pending write; durable restart fence; operator reconciliation; replica agreement; resumed writes
```

## Design trade-off

When a follower is unavailable, the leader sacrifices write availability to avoid treating an ambiguous replication attempt as a committed local write. A persisted intent and write fence are retained across a service restart; human-triggered reconciliation with the same follower is required to clear the uncertainty. These are deliberately narrow guarantees and are not equivalent to general consensus or high availability.

## Benchmarks are separate

The manually recorded standalone mixed workload produced 417.57 requests/sec over 500 requests with 8 threads and 0 request failures (p50 2.589 ms; p95 97.655 ms; p99 118.592 ms). This is a different workload from earlier write-only benchmarks and must not be used as an apples-to-apples speedup comparison. No new performance result is claimed by this fault-injection test.
