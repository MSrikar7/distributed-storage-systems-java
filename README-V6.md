# V6 — Observable storage prototype

Version 6 adds a **Prometheus-compatible text endpoint** at `GET /metrics/prometheus` alongside the original JSON `GET /metrics`. The endpoint is served by the existing loopback-only Java metadata service and does not require dependencies, Docker or external accounts.

## Run

Requirements: Java 21 and Bash.

```bash
bash scripts/test.sh
bash scripts/build.sh
java -cp build/kv:build/metadata com.s3index.metadata.MetadataServer ./runtime-v6 8080
```

From a second terminal:

```bash
curl http://127.0.0.1:8080/metrics/prometheus
curl http://127.0.0.1:8080/metrics
```

Prometheus scrape configuration (only for local development):

```yaml
scrape_configs:
  - job_name: s3index-local
    scrape_interval: 5s
    metrics_path: /metrics/prometheus
    static_configs:
      - targets: ['127.0.0.1:8080']
```

Available metrics include reads, writes, cumulative read/WAL timing, WAL batch counts, current key count and the local replication fence state. `s3index_node_info{role="..."}` identifies each service role. All counters are **per-process lifetime** and restart at zero; `s3index_keys` includes internal replication metadata keys. Timings are in cumulative nanoseconds, not p95/p99 latency.

## Engineering limits

This is **not a full dashboard**: it makes metrics scrapeable for a Prometheus/Grafana setup. It does not add network authentication, high availability, quorum, or automatic failover. Keep the service on loopback; do not expose metrics or the unprotected reconciliation endpoint publicly. Do not infer end-to-end distributed correctness from the local replication health gauge.

See [ARCHITECTURE.md](ARCHITECTURE.md) and [README-V5.md](README-V5.md) for safety and reconciliation details.
