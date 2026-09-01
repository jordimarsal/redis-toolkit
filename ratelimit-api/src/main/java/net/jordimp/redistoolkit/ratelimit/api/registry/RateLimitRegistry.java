package net.jordimp.redistoolkit.ratelimit.api.registry;

import java.util.Map;
import java.util.Optional;
import net.jordimp.redistoolkit.ratelimit.domain.RateLimitSpec;

public final class RateLimitRegistry {

    private final Map<String, RateLimitSpec> policies;

    public RateLimitRegistry(Map<String, RateLimitSpec> policies) {
        this.policies = Map.copyOf(policies);
    }

    public Optional<RateLimitSpec> find(String routeId) {
        if (routeId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(policies.get(routeId));
    }
}
