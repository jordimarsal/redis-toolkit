package net.jordimp.redistoolkit.jobqueue.support;

import net.jordimp.redistoolkit.jobqueue.port.Metrics;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class RecordingMetrics implements Metrics {

    private final ConcurrentMap<String, AtomicInteger> counters = new ConcurrentHashMap<>();

    private AtomicInteger counter(String group, String name) {
        return counters.computeIfAbsent(group + ":" + name, k -> new AtomicInteger());
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

    public int count(String queue, String metric) {
        return counter(queue, metric).get();
    }
}
