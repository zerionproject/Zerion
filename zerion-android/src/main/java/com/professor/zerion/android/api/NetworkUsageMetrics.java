package com.professor.zerion.android.api;

public interface NetworkUsageMetrics {
    class Metrics {
        private final long rxBytes;
        private final long txBytes;
        private final long sessionDurationMs;

        public Metrics(long rxBytes, long txBytes, long sessionDurationMs) {
            this.rxBytes = rxBytes;
            this.txBytes = txBytes;
            this.sessionDurationMs = sessionDurationMs;
        }

        public long getRxBytes() { return rxBytes; }
        public long getTxBytes() { return txBytes; }
        public long getSessionDurationMs() { return sessionDurationMs; }
    }

    Metrics getMetrics();
}