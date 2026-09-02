package net.jordimp.redistoolkit.jobqueue.port;

public record PendingStats(long unackedEntries, long outstandingClaims) {
}
