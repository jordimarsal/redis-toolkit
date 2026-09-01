package net.jordimp.redistoolkit.gateway.bench;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class BenchmarkRunner {

    public record Summary(long ok, long failed, double meanMs, double p95Ms, double p99Ms, double rps) {
        public String render() {
            return String.format(Locale.ROOT, "ok=%d failed=%d mean_ms=%.2f p95_ms=%.2f p99_ms=%.2f rps=%.1f",
                    ok, failed, meanMs, p95Ms, p99Ms, rps);
        }
    }

    private BenchmarkRunner() {
    }

    public static Summary run(String baseUrl, int n) throws IOException {
        if (n <= 0) {
            throw new IllegalArgumentException("n must be > 0");
        }
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        try {
            client.send(request(baseUrl, "probe"), HttpResponse.BodyHandlers.discarding());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("gateway unreachable at " + baseUrl, e);
        } catch (IOException e) {
            throw new IllegalStateException("gateway unreachable at " + baseUrl, e);
        }

        List<Double> latencies = new ArrayList<>(n);
        long ok = 0;
        long failed = 0;
        long startNanos = System.nanoTime();
        for (int i = 0; i < n; i++) {
            long t0 = System.nanoTime();
            try {
                HttpResponse<Void> resp = client.send(request(baseUrl, "bench-" + i), HttpResponse.BodyHandlers.discarding());
                if (resp.statusCode() == 200) {
                    ok++;
                } else {
                    failed++;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failed++;
            } catch (IOException e) {
                failed++;
            } finally {
                latencies.add((System.nanoTime() - t0) / 1_000_000.0);
            }
        }
        double wallMs = (System.nanoTime() - startNanos) / 1_000_000.0;
        double mean = latencies.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        return new Summary(ok, failed, mean, percentile(latencies, 0.95), percentile(latencies, 0.99), n * 1000.0 / wallMs);
    }

    private static HttpRequest request(String baseUrl, String prompt) {
        return HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/completions"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"stub\",\"prompt\":\"" + prompt + "\"}"))
                .build();
    }

    private static double percentile(List<Double> values, double q) {
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Double::compareTo);
        int idx = (int) Math.ceil(q * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 1 || args[0].isBlank()) {
            System.err.println("usage: BenchmarkRunner <base-url> [n]");
            System.exit(2);
        }
        int n = args.length > 1 ? Integer.parseInt(args[1]) : 100;
        try {
            Summary summary = run(args[0], n);
            System.out.println(summary.render());
            System.exit(summary.failed() > 0 ? 1 : 0);
        } catch (IllegalStateException e) {
            System.err.println(e.getMessage());
            System.exit(1);
        }
    }
}
