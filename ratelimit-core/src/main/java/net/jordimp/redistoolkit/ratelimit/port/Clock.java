package net.jordimp.redistoolkit.ratelimit.port;

import java.time.Instant;

public interface Clock {
    Instant now();
}
