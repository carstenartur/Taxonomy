package com.taxonomy.composition.analysis.artemis;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import com.taxonomy.analysis.service.LlmTransportMeter.Usage;
import static org.assertj.core.api.Assertions.*;

/** Exercises the same serialized state transition that independent broker clients commit. */
class ProviderQuotaLedgerTest {
    private static final long MINUTE = 60_000, DAY = 86_400_000;
    private static final ProviderQuotaLedger.Policy REQUESTS =
            new ProviderQuotaLedger.Policy(2, 0, 3, 0, 0, 1000, 16);

    @Test void independentDebitsShareBothMinuteAndRollingDayBudget() {
        var state = ProviderQuotaLedger.empty(REQUESTS);
        state = ProviderQuotaLedger.reserve(state, REQUESTS, id(), 0, 100_000).state();
        state = ProviderQuotaLedger.reserve(state, REQUESTS, id(), 0, 100_000).state();
        var third = ProviderQuotaLedger.reserve(state, REQUESTS, id(), 0, 100_001);
        assertThat(third.granted()).isFalse();
        assertThat(third.waitMillis()).isEqualTo(MINUTE + 1999);
        var afterMinute = ProviderQuotaLedger.reserve(state, REQUESTS, id(), 0, 162_000);
        assertThat(afterMinute.granted()).isTrue();
        var fourth = ProviderQuotaLedger.reserve(afterMinute.state(), REQUESTS, id(), 0, 162_001);
        assertThat(fourth.granted()).isFalse();
        assertThat(fourth.waitMillis()).isEqualTo(DAY - MINUTE - 1);
    }

    @Test void oppositeExtremeClockErrorsCannotReleaseBeforeAnActualMinute() {
        var policy = new ProviderQuotaLedger.Policy(1, 0, 0, 0, 0, 1000, 16);
        var first = ProviderQuotaLedger.reserve(ProviderQuotaLedger.empty(policy), policy, id(), 0, 99_000);
        assertThat(ProviderQuotaLedger.reserve(first.state(), policy, id(), 0, 160_999).granted()).isFalse();
        assertThat(ProviderQuotaLedger.reserve(first.state(), policy, id(), 0, 161_000).granted()).isTrue();
        assertThatThrownBy(() -> ProviderQuotaLedger.reserve(first.state(), policy, id(), 0, 96_999))
                .hasMessageContaining("clock");
    }

    @Test void unknownUsageKeepsTheEntireReservationAndKnownUsageSettlesOnlyOnce() {
        var policy = new ProviderQuotaLedger.Policy(0, 100, 0, 80, 20, 0, 16);
        String attempt = id();
        var first = ProviderQuotaLedger.reserve(ProviderQuotaLedger.empty(policy), policy, attempt, 90, 100_000);
        var started = ProviderQuotaLedger.start(first.state(), policy, attempt, 100_000);
        var unknown = ProviderQuotaLedger.settle(started, policy, attempt, null, 100_001);
        assertThat(ProviderQuotaLedger.reserve(unknown, policy, id(), 20, 100_001).granted()).isFalse();
        var settled = ProviderQuotaLedger.settle(unknown, policy, attempt, 30L, 100_002);
        assertThat(ProviderQuotaLedger.reserve(settled, policy, id(), 70, 100_002).granted()).isTrue();
        var duplicate = ProviderQuotaLedger.settle(settled, policy, attempt, 0L, 100_003);
        assertThat(duplicate).isEqualTo(settled);
        assertThat(ProviderQuotaLedger.reserve(duplicate, policy, id(), 71, 100_003).granted()).isFalse();
        assertThat(ProviderQuotaLedger.settle(settled, policy, id(), 0L, 100_003)).isEqualTo(settled);
    }

    @Test void oversizeBudgetOrObservedBoundViolationCannotAdmitAnUnsafeAttempt() {
        var policy = new ProviderQuotaLedger.Policy(0, 100, 0, 80, 20, 0, 16);
        var empty = ProviderQuotaLedger.empty(policy);
        assertThatThrownBy(() -> ProviderQuotaLedger.reserve(empty, policy, id(), 101, 100_000))
                .hasMessageContaining("budget");
        String attempt = id();
        var first = ProviderQuotaLedger.reserve(empty, policy, attempt, 90, 100_000);
        var unsafe = ProviderQuotaLedger.settle(ProviderQuotaLedger.start(first.state(), policy, attempt, 100_000), policy, attempt, 91L, 100_001);
        assertThatThrownBy(() -> ProviderQuotaLedger.reserve(unsafe, policy, id(), 1, 100_002))
                .hasMessageContaining("bound");
    }

    @Test void longestSharedCooldownAndPolicyIdentitySurviveNewCallers() {
        var state = ProviderQuotaLedger.empty(REQUESTS);
        state = ProviderQuotaLedger.defer(state, REQUESTS, 5000, 100_000);
        state = ProviderQuotaLedger.defer(state, REQUESTS, 1000, 101_000);
        var waiting = ProviderQuotaLedger.reserve(state, REQUESTS, id(), 0, 103_000);
        assertThat(waiting.granted()).isFalse();
        assertThat(waiting.waitMillis()).isEqualTo(4000);
        assertThat(ProviderQuotaLedger.reserve(state, REQUESTS, id(), 0, 107_000).granted()).isTrue();
        var incompatible = new ProviderQuotaLedger.Policy(3, 0, 3, 0, 0, 1000, 16);
        final var saved = state;
        assertThatThrownBy(() -> ProviderQuotaLedger.reserve(saved, incompatible, id(), 0, 107_000))
                .hasMessageContaining("policy");
    }

    @Test void longAndOverflowingCooldownRemainSharedWithoutAnEarlyReset() {
        var day = ProviderQuotaLedger.defer(ProviderQuotaLedger.empty(REQUESTS), REQUESTS,
                ProviderQuotaLedger.DAY + 1, 100_000);
        assertThat(ProviderQuotaLedger.reserve(day, REQUESTS, id(), 0, 100_000).waitMillis())
                .isEqualTo(ProviderQuotaLedger.DAY + 1 + 2000);
        var blocked = ProviderQuotaLedger.defer(day, REQUESTS, Long.MAX_VALUE, 100_000);
        assertThat(blocked.cooldownUntil()).isEqualTo(Long.MAX_VALUE);
        blocked = ProviderQuotaLedger.defer(blocked, REQUESTS, 0, 100_001);
        var denied = ProviderQuotaLedger.reserve(blocked, REQUESTS, id(), 0, 100_001);
        assertThat(denied.granted()).isFalse();
        assertThat(denied.waitMillis()).isEqualTo(Long.MAX_VALUE - 100_001);
    }

    @Test void onlyCompleteCoherentProviderUsageCanReduceAConservativeTokenDebit() {
        var policy = new ProviderQuotaLedger.Policy(0, 100, 0, 80, 20, 0, 16);
        String attempt = id();
        var state = ProviderQuotaLedger.reserve(ProviderQuotaLedger.empty(policy), policy, attempt, 100, 100_000).state();
        state = ProviderQuotaLedger.start(state,policy,attempt,100_000);
        for (var usage : new Usage[]{
                new Usage(null, 2L, 3L, null, null, false),
                new Usage(1L, 2L, null, null, null, false),
                new Usage(-1L, 2L, 1L, null, null, false),
                new Usage(1L, 2L, 4L, null, null, false),
                new Usage(Long.MAX_VALUE, 1L, 0L, null, null, false),
                new Usage(1L, 2L, 3L, 2L, null, false),
                new Usage(1L, 2L, 3L, null, 3L, false),
                new Usage(1L, 2L, 3L, null, null, true)}) {
            assertThat(ProviderQuotaLedger.settleUsage(state, policy, attempt, usage, 100_001)).isEqualTo(state);
        }
        var accepted = ProviderQuotaLedger.settleUsage(state, policy, attempt,
                new Usage(10L, 2L, 12L, 8L, 1L, false), 100_002);
        assertThat(ProviderQuotaLedger.reserve(accepted, policy, id(), 88, 100_002).granted()).isTrue();
        assertThat(ProviderQuotaLedger.settleUsage(accepted, policy, attempt,
                new Usage(0L, 0L, 0L, null, null, false), 100_003)).isEqualTo(accepted);
    }

    @Test void aCompleteUsageOutsideEitherBoundStopsFurtherTokenAdmission() {
        var policy = new ProviderQuotaLedger.Policy(0, 100, 0, 80, 20, 0, 16);
        for (var usage : new Usage[]{new Usage(81L, 1L, 82L, null, null, false),
                new Usage(1L, 21L, 22L, null, null, false)}) {
            String attempt = id();
            var state = ProviderQuotaLedger.reserve(ProviderQuotaLedger.empty(policy), policy, attempt, 100, 100_000).state();
        state = ProviderQuotaLedger.start(state,policy,attempt,100_000);
            var unsafe = ProviderQuotaLedger.settleUsage(state, policy, attempt, usage, 100_001);
            assertThatThrownBy(() -> ProviderQuotaLedger.reserve(unsafe, policy, id(), 1, 100_002))
                    .hasMessageContaining("bound");
        }
    }

    @Test void usageCannotRefundAReceiptWithoutAnAcceptedPhysicalStart() {
        var policy=new ProviderQuotaLedger.Policy(0,100,0,80,20,1000,16);String attempt=id();
        var state=ProviderQuotaLedger.reserve(ProviderQuotaLedger.empty(policy),policy,attempt,100,100_000).state();
        assertThat(ProviderQuotaLedger.settleUsage(state,policy,attempt,new Usage(1L,1L,2L,null,null,false),100_001)).isEqualTo(state);
    }

    @Test void lateCompleteBoundViolationLocksOnlyAnIdentifiableStartedReceipt() {
        var policy=new ProviderQuotaLedger.Policy(0,100,5,80,20,1000,16);String attempt=id();
        var admitted=ProviderQuotaLedger.reserve(ProviderQuotaLedger.empty(policy),policy,attempt,100,100_000).state();
        var overBound=new Usage(81L,1L,82L,null,null,false);
        assertThat(ProviderQuotaLedger.settleUsage(admitted,policy,attempt,overBound,162_001)).isEqualTo(admitted);
        var started=ProviderQuotaLedger.start(admitted,policy,attempt,100_000);
        assertThat(ProviderQuotaLedger.settleUsage(started,policy,id(),overBound,162_001)).isEqualTo(started);
        assertThat(ProviderQuotaLedger.settleUsage(started,policy,attempt,new Usage(1L,1L,2L,null,null,false),162_001))
                .as("an expired token reservation is never refunded").isEqualTo(started);
        var unsafe=ProviderQuotaLedger.settleUsage(started,policy,attempt,overBound,162_001);
        assertThat(unsafe.unsafeTokenBound()).isTrue();
        assertThatThrownBy(()->ProviderQuotaLedger.reserve(unsafe,policy,id(),100,162_002)).hasMessageContaining("bound");
        var minuteOnly=new ProviderQuotaLedger.Policy(0,100,0,80,20,1000,16);
        var beforePrune=ProviderQuotaLedger.reserve(ProviderQuotaLedger.empty(minuteOnly),minuteOnly,attempt,100,100_000).state();
        beforePrune=ProviderQuotaLedger.start(beforePrune,minuteOnly,attempt,100_000);
        var pruned=ProviderQuotaLedger.reserve(beforePrune,minuteOnly,id(),100,162_001).state();
        assertThat(ProviderQuotaLedger.settleUsage(pruned,minuteOnly,attempt,overBound,162_002)).isEqualTo(pruned);
    }

    @Test void lateTotalBoundViolationAlsoLocksAnIdentifiableStartedReceipt() {
        var policy=new ProviderQuotaLedger.Policy(0,100,5,80,20,1000,16);String attempt=id();
        var admitted=ProviderQuotaLedger.reserve(ProviderQuotaLedger.empty(policy),policy,attempt,100,100_000).state();
        assertThat(ProviderQuotaLedger.settle(admitted,policy,attempt,101L,162_001)).isEqualTo(admitted);
        var started=ProviderQuotaLedger.start(admitted,policy,attempt,100_000);
        assertThat(ProviderQuotaLedger.settle(started,policy,id(),101L,162_001)).isEqualTo(started);
        assertThat(ProviderQuotaLedger.settle(started,policy,attempt,2L,162_001)).isEqualTo(started);
        var unsafe=ProviderQuotaLedger.settle(started,policy,attempt,101L,162_001);
        assertThat(unsafe.unsafeTokenBound()).isTrue();
        assertThatThrownBy(()->ProviderQuotaLedger.reserve(unsafe,policy,id(),100,162_002)).hasMessageContaining("bound");
    }

    private static String id() { return UUID.randomUUID().toString(); }
}
