package net.jordimp.redistoolkit.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.Counter;
import io.prometheus.client.exporter.common.TextFormat;
import java.io.IOException;
import java.io.StringWriter;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import net.jordimp.redistoolkit.gateway.backend.CompletionRequest;
import net.jordimp.redistoolkit.gateway.backend.InferenceBackend;
import net.jordimp.redistoolkit.ratelimit.api.KeyExtractor;
import net.jordimp.redistoolkit.ratelimit.api.dto.ApiResponse;
import net.jordimp.redistoolkit.ratelimit.api.dto.ErrorBody;
import net.jordimp.redistoolkit.ratelimit.api.mapper.DecisionMapper;
import net.jordimp.redistoolkit.ratelimit.api.registry.RateLimitRegistry;
import net.jordimp.redistoolkit.ratelimit.domain.Decision;
import net.jordimp.redistoolkit.ratelimit.domain.Dimension;
import net.jordimp.redistoolkit.ratelimit.domain.QuotaKey;
import net.jordimp.redistoolkit.ratelimit.domain.RateLimitSpec;
import net.jordimp.redistoolkit.ratelimit.domain.Reason;
import net.jordimp.redistoolkit.ratelimit.usecase.RateLimiterService;

public final class GatewayApp {

    private static final String ROUTE = "/v1/completions";
    private static final String METRICS_ROUTE = "/metrics";
    private static final String DECISIONS_COUNTER = "ratelimit_decisions_total";
    private static final String DEFAULT_MODEL = "stub";

    private final RateLimiterService service;
    private final KeyExtractor extractor;
    private final RateLimitRegistry registry;
    private final DecisionMapper mapper;
    private final InferenceBackend backend;
    private final ObjectMapper json;
    private final CollectorRegistry metricsRegistry;
    private final AutoCloseable resource;
    private final Counter decisions;

    private Javalin app;
    private int actualPort;
    private boolean stopped;

    public GatewayApp(RateLimiterService service,
                      KeyExtractor extractor,
                      RateLimitRegistry registry,
                      DecisionMapper mapper,
                      InferenceBackend backend,
                      ObjectMapper json) {
        this(service, extractor, registry, mapper, backend, json, null, null);
    }

    public GatewayApp(RateLimiterService service,
                      KeyExtractor extractor,
                      RateLimitRegistry registry,
                      DecisionMapper mapper,
                      InferenceBackend backend,
                      ObjectMapper json,
                      CollectorRegistry metricsRegistry,
                      AutoCloseable resource) {
        this.service = service;
        this.extractor = extractor;
        this.registry = registry;
        this.mapper = mapper;
        this.backend = backend;
        this.json = json;
        this.metricsRegistry = metricsRegistry;
        this.resource = resource;
        if (metricsRegistry != null) {
            this.decisions = Counter.build()
                    .name(DECISIONS_COUNTER)
                    .help("Rate-limit decisions produced by the gateway.")
                    .labelNames("result")
                    .create();
            metricsRegistry.register(decisions);
        } else {
            this.decisions = null;
        }
    }

    public Javalin start(int port) {
        int bound = (port == 0) ? firstFreePort() : port;
        this.actualPort = bound;
        this.app = Javalin.create();
        this.app.post(ROUTE, this::handleCompletions);
        if (metricsRegistry != null) {
            this.app.get(METRICS_ROUTE, this::handleMetrics);
        }
        this.app.start(bound);
        return this.app;
    }

    public int port() {
        return actualPort;
    }

    public void stop() {
        if (stopped) {
            return;
        }
        stopped = true;
        if (app != null) {
            app.stop();
        }
        if (resource != null) {
            try {
                resource.close();
            } catch (Exception e) {
                throw new IllegalStateException("Failed to close gateway resource", e);
            }
        }
    }

    private void handleMetrics(Context ctx) {
        try {
            StringWriter writer = new StringWriter();
            TextFormat.write004(writer, metricsRegistry.metricFamilySamples());
            ctx.contentType("text/plain; charset=utf-8");
            ctx.result(writer.toString());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to render Prometheus metrics", e);
        }
    }

    private void handleCompletions(Context ctx) {
        CompletionRequest request;
        try {
            request = parseRequest(ctx.body());
        } catch (Exception e) {
            writeJson(ctx, 400, Map.of(), new ErrorBody("bad_request", "Invalid JSON body"));
            return;
        }

        Decision decision;
        Object successBody = null;
        try {
            QuotaKey key = extractor.extract(Dimension.IP, remoteAddr(ctx));
            Optional<RateLimitSpec> specOpt = registry.find(ROUTE);
            if (specOpt.isEmpty()) {
                decision = Decision.rejected(Reason.CONFIG_ERROR, 0L, Duration.ZERO);
            } else {
                decision = service.evaluate(key, specOpt.get());
                if (decision.isAllowed()) {
                    successBody = backend.complete(request);
                }
            }
        } catch (IllegalArgumentException e) {
            decision = Decision.rejected(Reason.CONFIG_ERROR, 0L, Duration.ZERO);
        } catch (RuntimeException e) {
            writeJson(ctx, 500, Map.of(), new ErrorBody("backend_error", "Inference backend failure"));
            return;
        }

        if (decisions != null) {
            decisions.labels(decision.isAllowed() ? "allowed" : "denied").inc();
        }
        ApiResponse<?> response = mapper.toResponse(decision, successBody);
        writeJson(ctx, response.status(), response.headers(), response.body());
    }

    private CompletionRequest parseRequest(String raw) throws IOException {
        if (raw == null || raw.isBlank()) {
            return new CompletionRequest(DEFAULT_MODEL, "");
        }
        return json.readValue(raw, CompletionRequest.class);
    }

    private static String remoteAddr(Context ctx) {
        return ctx.req().getRemoteAddr();
    }

    private void writeJson(Context ctx, int status, Map<String, String> headers, Object body) {
        ctx.status(status);
        if (headers != null) {
            headers.forEach(ctx::header);
        }
        ctx.contentType("application/json");
        try {
            String out = (body == null) ? "" : json.writeValueAsString(body);
            ctx.result(out);
        } catch (Exception e) {
            ctx.status(500);
            ctx.result("");
        }
    }

    private static int firstFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("Could not allocate a free local port", e);
        }
    }
}
