package net.jordimp.redistoolkit.jobqueue.infra.metrics;

import net.jordimp.redistoolkit.jobqueue.port.Metrics;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

public final class InMemoryMetrics implements Metrics {

    private final ConcurrentMap<String, AtomicLong> counters = new ConcurrentHashMap<>();

    private AtomicLong counter(String queue, String name) {
        return counters.computeIfAbsent(queue + ":" + name, k -> new AtomicLong());
    }

    @Override
    public void submitted(String queue) {
        counter(queue, "submitted").incrementAndGet();
    }

    @Override
    public void delivered(String queue) {
        counter(queue, "delivered").incrementAndGet();
    }

    @Override
    public void acked(String queue) {
        counter(queue, "acked").incrementAndGet();
    }

    @Override
    public void failed(String queue) {
        counter(queue, "failed").incrementAndGet();
    }

    @Override
    public void reclaimed(String queue) {
        counter(queue, "reclaimed").incrementAndGet();
    }

    @Override
    public void promoted(String queue) {
        counter(queue, "promoted").incrementAndGet();
    }

    public long count(String queue, String metric) {
        return counters.getOrDefault(queue + ":" + metric, new AtomicLong()).get();
    }
}
