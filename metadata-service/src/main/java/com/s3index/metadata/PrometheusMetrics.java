package com.s3index.metadata;

import com.s3index.kv.DurableKeyValueStore;
import java.util.Locale;

/** Prometheus text exposition for the localhost-only educational metadata service. */
public final class PrometheusMetrics {
    private PrometheusMetrics() {}

    public static String render(DurableKeyValueStore.Metrics m, long batches, long keys,
                                String role, boolean replicationHealthy) {
        StringBuilder b = new StringBuilder();
        counter(b, "s3index_reads_total", "KV lookup operations", m.reads());
        counter(b, "s3index_writes_total", "Durable KV mutations", m.writes());
        counter(b, "s3index_read_nanoseconds_total", "Cumulative read time in nanoseconds", m.readNanos());
        counter(b, "s3index_write_queue_nanoseconds_total", "Cumulative time awaiting WAL batching in nanoseconds", m.writeWaitNanos());
        counter(b, "s3index_wal_append_nanoseconds_total", "Cumulative WAL append time in nanoseconds", m.walAppendNanos());
        counter(b, "s3index_wal_force_nanoseconds_total", "Cumulative WAL synchronization time in nanoseconds", m.walForceNanos());
        counter(b, "s3index_wal_batches_total", "Completed WAL force batches", batches);
        gauge(b, "s3index_keys", "Current number of keys including internal keys", keys);
        gauge(b, "s3index_replication_healthy", "Local replication fence state (1 healthy, 0 uncertain)", replicationHealthy ? 1 : 0);
        b.append("# HELP s3index_node_info Node role (value is always 1)\n");
        b.append("# TYPE s3index_node_info gauge\n");
        b.append(String.format(Locale.ROOT, "s3index_node_info{role=\"%s\"} 1\n", role));
        return b.toString();
    }

    private static void counter(StringBuilder b, String name, String help, long value) {
        metric(b, name, help, "counter", value);
    }
    private static void gauge(StringBuilder b, String name, String help, long value) {
        metric(b, name, help, "gauge", value);
    }
    private static void metric(StringBuilder b, String name, String help, String type, long value) {
        b.append("# HELP ").append(name).append(' ').append(help).append('\n');
        b.append("# TYPE ").append(name).append(' ').append(type).append('\n');
        b.append(name).append(' ').append(value).append('\n');
    }
}
