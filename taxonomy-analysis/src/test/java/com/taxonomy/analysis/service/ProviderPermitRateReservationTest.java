package com.taxonomy.analysis.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

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
        var clock = new AtomicLong();
        var admission = new LlmRequestAdmission(LlmProvider.OPENAI.id(), 0, null,
                () -> TimeUnit.MILLISECONDS.toNanos(clock.get()), (reason, millis) -> {
                    throw new AssertionError("Unexpected local wait: " + reason);
                });
        admission.configure(new ProviderRequestLimiter.Limits(4, 8, 100));
        var returned = new AtomicInteger();
        var requests = new AtomicInteger();
        admission.configure((provider, checkpoint) -> {
            clock.addAndGet(150);
            return returned::incrementAndGet;
        });
        assertThatThrownBy(() -> admission.execute(requests::incrementAndGet))
                .isInstanceOf(LlmRateLimitException.class).hasMessageContaining("admission wait expired");
        assertThat(returned.get()).isOne();
        assertThat(requests.get()).isZero();
    }

    @Test
    void failedClusterAcquisitionDoesNotSpendLocalRpmWithoutAnHttpAttempt() {
        var settings = mock(AnalysisRuntimeSettings.class);
        when(settings.getInt("llm.rpm.openai", 1)).thenReturn(1);
        var clock = new AtomicLong();
        var waits = new ArrayList<ProviderRequestLimiter.WaitReason>();
        var admission = new LlmRequestAdmission(LlmProvider.OPENAI.id(), 1, settings,
                () -> TimeUnit.MILLISECONDS.toNanos(clock.get()), (reason, millis) -> {
                    waits.add(reason);
                    clock.addAndGet(millis);
                });
        admission.configure(new ProviderRequestLimiter.Limits(4, 8, 10));
        var acquisitions = new AtomicInteger();
        var requests = new AtomicInteger();
        admission.configure((provider, checkpoint) -> {
            if (acquisitions.getAndIncrement() == 0) {
                throw new ProviderConcurrencyPermits.UnavailableException("fixture broker outage", null);
            }
            return () -> {};
        });
        assertThatThrownBy(() -> admission.execute(requests::incrementAndGet))
                .isInstanceOf(ProviderConcurrencyPermits.UnavailableException.class);
        assertThat(requests.get()).isZero();
        assertThat(admission.execute(requests::incrementAndGet)).isEqualTo(1);
        assertThat(waits).isEmpty();
        assertThat(acquisitions.get()).isEqualTo(2);

        assertThatThrownBy(() -> admission.execute(requests::incrementAndGet))
                .isInstanceOf(LlmRateLimitException.class).hasMessageContaining("admission wait expired");
        assertThat(waits).containsExactly(ProviderRequestLimiter.WaitReason.RATE_LIMIT);
        assertThat(acquisitions.get()).isEqualTo(2);
        assertThat(requests.get()).isOne();
    }
}
