package net.jordimp.redistoolkit.ratelimit.support;

import java.time.Instant;
import net.jordimp.redistoolkit.ratelimit.port.Clock;

public final class FakeClock implements Clock {

    private Instant current;

    public FakeClock(Instant initial) {
        this.current = initial;
    }

    @Override
    public Instant now() {
        return current;
    }

    public void advanceSeconds(long seconds) {
        this.current = current.plusSeconds(seconds);
    }
}
