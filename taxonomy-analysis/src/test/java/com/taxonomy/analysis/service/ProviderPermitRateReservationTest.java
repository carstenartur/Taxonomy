package com.taxonomy.analysis.service;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProviderPermitRateReservationTest {
    @Test
    void clusterWaitRefreshesRpmAtPhysicalStartWithoutAdmittingAnExtraAttempt() {
        var clock = new AtomicLong();
        var limiter = new ProviderRequestLimiter(new ProviderRequestLimiter.Limits(4, 8), clock::get);
        try (var waitingForCluster = limiter.acquire(1, () -> {}, (reason, millis) -> clock.addAndGet(millis))) {
            clock.set(60_001);
            try (var other = limiter.acquire(1, () -> {}, (reason, millis) -> clock.addAndGet(millis))) {
                assertThat(other.refreshRateAdmission(1)).isTrue();
                assertThat(waitingForCluster.refreshRateAdmission(1)).isFalse();
            }
            clock.set(120_002);
            assertThat(waitingForCluster.refreshRateAdmission(1)).isTrue();
            try (var next = limiter.acquire(1, () -> {}, (reason, millis) -> clock.addAndGet(millis))) {
                assertThat(clock.get()).isGreaterThanOrEqualTo(180_002);
            }
        }
    }

    @Test
    void retryAfterArrivingDuringClusterWaitIsRecheckedBeforeHttp() {
        var clock = new AtomicLong();
        var limiter = new ProviderRequestLimiter(new ProviderRequestLimiter.Limits(4, 8), clock::get);
        try (var waitingForCluster = limiter.acquire(0, () -> {}, (reason, millis) -> clock.addAndGet(millis))) {
            limiter.deferFor(1000);
            assertThat(waitingForCluster.refreshRateAdmission(0)).isFalse();
            clock.set(1000);
            assertThat(waitingForCluster.refreshRateAdmission(0)).isTrue();
        }
    }

    @Test
    void clusterWaitCannotResetOrOutliveTheAttemptAdmissionDeadline() {
        var admission = new LlmRequestAdmission(LlmProvider.OPENAI, 0, null);
        admission.configure(new ProviderRequestLimiter.Limits(4, 8, 100));
        var returned = new AtomicInteger();
        admission.configure((provider, checkpoint) -> {
            try { Thread.sleep(150); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
            return returned::incrementAndGet;
        });
        assertThatThrownBy(() -> admission.execute(() -> "unpermitted"))
                .isInstanceOf(LlmRateLimitException.class).hasMessageContaining("admission wait expired");
        assertThat(returned.get()).isOne();
    }

    @Test
    void failedClusterAcquisitionDoesNotSpendLocalRpmWithoutAnHttpAttempt() {
        var settings = mock(AnalysisRuntimeSettings.class);
        when(settings.getInt("llm.rpm.openai", 1)).thenReturn(1);
        var admission = new LlmRequestAdmission(LlmProvider.OPENAI, 1, settings);
        admission.configure(new ProviderRequestLimiter.Limits(4, 8, 10));
        var acquisitions = new AtomicInteger();
        admission.configure((provider, checkpoint) -> {
            if (acquisitions.getAndIncrement() == 0) {
                throw new ProviderConcurrencyPermits.UnavailableException("fixture broker outage", null);
            }
            return () -> {};
        });
        assertThatThrownBy(() -> admission.execute(() -> "unpermitted"))
                .isInstanceOf(ProviderConcurrencyPermits.UnavailableException.class);
        assertThat(admission.execute(() -> "permitted after recovery")).isEqualTo("permitted after recovery");
        assertThat(acquisitions.get()).isEqualTo(2);
    }
}
