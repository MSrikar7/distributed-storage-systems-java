# Version 5 — Durable intent + operator-triggered reconciliation

## Important: V5 is a learning prototype, NOT an HA production implementation

The V4 leader could make a failed write locally visible even though replication failed. V5 persists a single **intent** before contacting the follower, sends monotonically numbered idempotent PUT/DELETE operations, then applies the write on the leader only after the follower has durably acknowledged. If the follower is unreachable, the leader returns HTTP 503, fences subsequent writes, and retains the intent on disk. Restarting the leader does not remove that fence. An operator can retry with `POST /admin/reconcile` once the **same** follower is reachable. On success, both peers hold the value and the leader accepts new writes.

### Limitations / safety

- **Use NEW, EMPTY data directories for both V5 nodes.** Never use V4 `runtime-leader` or `runtime-follower` directly. The V4 protocol lacks sequence markers. Archive your original V4 data first.
- There is **no automatic failover, consensus, leader election, quorum, fencing token, or split-brain prevention**. Never start a second leader while one may still be active.
- The follower may expose a newly applied value before the leader acknowledges success, and after a replication failure the follower may be ahead of the leader. **This is not linearizable replicated storage.** Followers should not serve authoritative client reads in this prototype.
- The leader returns 503 even when a follower might have applied the write. An operator retries the same durable intent (not a new client write) to resolve ambiguity.
- Only single-operation PUT/DELETE with one outstanding intent are supported. `X-Sequence` prevents reapplying acknowledged requests; this provides retry-safe effects for PUT/DELETE, not a general exactly-once distributed transaction guarantee.
- `POST /admin/reconcile` is an **unprotected local demonstration endpoint**. Bind is loopback-only. Do not expose this service on a network.
- `get` status and health are not full cluster health or replication correctness proofs.
- The WAL and intent storage rely on local fsync and the underlying host's durability guarantees.

## Windows instructions

Unzip and open PowerShell in `s3-index-projects` (the folder containing `scripts`).

```powershell
Set-ExecutionPolicy -Scope Process Bypass
.\scripts\build-windows.ps1
java -cp "build/kv;build/metadata;build/test" com.s3index.metadata.ReconciliationTest
java -cp "build/kv;build/metadata;build/test" com.s3index.metadata.ReplicationTest
```

Open first terminal, use a **new** follower directory:

```powershell
$env:S3_INDEX_REPLICA_SECRET="local-demo-secret"
java -cp "build/kv;build/metadata" com.s3index.metadata.MetadataServer ./runtime-v5-follower 8091 follower
```

Open second terminal, use a **new** leader directory:

```powershell
$env:S3_INDEX_REPLICA_SECRET="local-demo-secret"
java -cp "build/kv;build/metadata" com.s3index.metadata.MetadataServer ./runtime-v5-leader 8090 leader http://127.0.0.1:8091
```

In third terminal:

```powershell
Invoke-WebRequest -Method PUT -Uri http://127.0.0.1:8090/metadata/object-1 -Body "etag-123" -UseBasicParsing
Invoke-RestMethod http://127.0.0.1:8091/metadata/object-1
```

Simulate failure: stop follower terminal using Ctrl+C, then request PUT on the leader. Expect HTTP 503, and the leader should not expose the new object. Restart the **same** follower on the **same** data directory, then explicitly resolve the durable pending intent:

```powershell
Invoke-WebRequest -Method POST -Uri http://127.0.0.1:8090/admin/reconcile -UseBasicParsing
Invoke-RestMethod http://127.0.0.1:8090/metadata/object-2
Invoke-RestMethod http://127.0.0.1:8091/metadata/object-2
```

DO NOT delete data directories to 'fix' replication. Do not promote followers automatically.
