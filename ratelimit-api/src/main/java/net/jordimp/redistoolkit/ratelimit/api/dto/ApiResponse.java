package net.jordimp.redistoolkit.ratelimit.api.dto;

import java.util.Map;

public record ApiResponse<T>(int status, Map<String,String> headers, T body) {
}
