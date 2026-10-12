package com.taxonomy.analysis.service;

import com.taxonomy.extension.api.llm.ProviderId;
import java.util.Objects;

/** Shared physical-attempt rate policy; distinct from HTTP in-flight capacity. */
public interface ProviderRateQuotas {
    record TokenBudget(String adapterId, long inputUpperBound, int maximumOutputTokens) {
        public TokenBudget {
            if (adapterId == null || !adapterId.matches("[a-z0-9][a-z0-9.-]{0,127}")
                    || inputUpperBound < 1 || inputUpperBound > 1_000_000_000
                    || maximumOutputTokens < 1 || maximumOutputTokens > 1_000_000) {
                throw new IllegalArgumentException("Invalid provider token budget contract");
            }
        }
        public long maximumTokens() { return Math.addExact(inputUpperBound, maximumOutputTokens); }
    }
    interface Reservation {
        String id();
        /** Atomically consumes this same still-live receipt immediately before physical HTTP. */
        void beforeTransport();
        void settle(LlmTransportMeter.Usage usage);
    }
    record Decision(Reservation reservation, long waitMillis) {
        public Decision {
            if (reservation == null ? waitMillis < 1 : waitMillis != 0) throw new IllegalArgumentException("Invalid quota admission decision");
        }
        public boolean granted() { return reservation != null; }
    }
    /** Null denotes request-only RPM/RPD; no unsupported TPM estimate is permitted. */
    TokenBudget requiredBudget(ProviderId provider);
    Decision tryReserve(ProviderId provider, String attemptId, TokenBudget supportedBudget, Runnable checkpoint);
    void defer(ProviderId provider, long delayMillis);

    final class UnavailableException extends RuntimeException {
        public UnavailableException(String message, Throwable cause) { super(message, cause); }
    }
    ProviderRateQuotas NONE = new ProviderRateQuotas() {
        public TokenBudget requiredBudget(ProviderId provider) { return null; }
        public Decision tryReserve(ProviderId provider, String id, TokenBudget budget, Runnable checkpoint) {
            Objects.requireNonNull(checkpoint).run();
            return new Decision(new Reservation() {
                public String id() { return id; }
                public void beforeTransport() {}
                public void settle(LlmTransportMeter.Usage usage) {}
            }, 0);
        }
        public void defer(ProviderId provider, long delayMillis) {}
    };
}
