package net.jordimp.redistoolkit.ratelimit.api.mapper;

import net.jordimp.redistoolkit.ratelimit.api.dto.ApiResponse;
import net.jordimp.redistoolkit.ratelimit.api.dto.ErrorBody;
import net.jordimp.redistoolkit.ratelimit.domain.Decision;
import net.jordimp.redistoolkit.ratelimit.domain.Reason;

public final class DecisionMapper {

    public ApiResponse<?> toResponse(Decision decision, Object successBody) {
        if (decision.isAllowed()) {
            return new ApiResponse<>(200, decision.headers(), successBody);
        }
        int status = statusCode(decision.reason());
        ErrorBody body = new ErrorBody(codeFor(decision.reason()), messageFor(decision.reason()));
        return new ApiResponse<>(status, decision.headers(), body);
    }

    private static int statusCode(Reason reason) {
        return switch (reason) {
            case LIMIT_EXCEEDED -> 429;
            case STORE_UNAVAILABLE -> 503;
            case CONFIG_ERROR -> 500;
            case OK -> 200;
        };
    }

    private static String codeFor(Reason reason) {
        return switch (reason) {
            case LIMIT_EXCEEDED -> "rate_limited";
            case STORE_UNAVAILABLE -> "store_unavailable";
            case CONFIG_ERROR -> "config_error";
            case OK -> "ok";
        };
    }

    private static String messageFor(Reason reason) {
        return switch (reason) {
            case LIMIT_EXCEEDED -> "Rate limit exceeded; retry after the Retry-After interval.";
            case STORE_UNAVAILABLE -> "Quota store unavailable.";
            case CONFIG_ERROR -> "No valid rate-limit configuration for this request.";
            case OK -> "OK";
        };
    }
}
