package com.taxonomy.composition.analysis.artemis;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import com.taxonomy.analysis.service.LlmTransportMeter.Usage;

/** Pure transitions on the single durable state message, never process-local quota authority. */
final class ProviderQuotaLedger {
    static final long MINUTE = 60_000, DAY = 86_400_000;
    record Policy(int rpm, long tpm, int rpd, long inputUpperBound, int outputCap,
                  long clockSkewMillis, int maximumReservations) {
        Policy {
            if (rpm < 0 || rpm > 1_000_000 || tpm < 0 || tpm > 1_000_000_000L || rpd < 0 || rpd > 1_000_000
                    || rpm + tpm + rpd == 0 || clockSkewMillis < 0 || clockSkewMillis > 60_000
                    || maximumReservations < 1 || maximumReservations > 20_000
                    || inputUpperBound < 0 || outputCap < 0 || outputCap > 1_000_000
                    || (tpm > 0 && (inputUpperBound < 1 || outputCap < 1))
                    || (tpm == 0 && (inputUpperBound != 0 || outputCap != 0))) {
                throw new IllegalArgumentException("Invalid bounded provider quota policy");
            }
        }
        String fingerprint() {
            try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(toString().getBytes(StandardCharsets.UTF_8))); }
            catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        }
    }
    record Debit(String id, long admittedAt, long reservedTokens, boolean settled, boolean started) {}
    record State(String policyFingerprint, long highWaterMillis, long cooldownUntil,
                 boolean unsafeTokenBound, List<Debit> debits) {}
    record Decision(State state, boolean granted, long waitMillis) {}

    static State empty(Policy policy) { return new State(policy.fingerprint(), 0, 0, false, List.of()); }
    static Decision reserve(State state, Policy policy, String id, long tokens, long now) {
        validate(state, policy, now);
        identity(id);
        if (state.unsafeTokenBound()) throw unavailable("Provider token bound was exceeded; operator review required");
        if (tokens < 0 || (policy.tpm() > 0 && (tokens == 0 || tokens > policy.tpm()
                || tokens > Math.addExact(policy.inputUpperBound(), policy.outputCap())))
                || (policy.tpm() == 0 && tokens != 0)) throw unavailable("Provider token budget cannot be admitted");
        long at = Math.max(now, state.highWaterMillis());
        var retained = new ArrayList<Debit>();
        long retention = policy.rpd() > 0 ? DAY : MINUTE;
        for (var debit : state.debits()) {
            if (expires(debit, retention, policy) > now) retained.add(debit);
        }
        if (retained.stream().anyMatch(debit -> debit.id().equals(id))) {
            throw unavailable("Provider attempt identity was already admitted");
        }
        long wait = Math.max(0, state.cooldownUntil() - now);
        long minuteRequests = 0, minuteTokens = 0, earliestMinute = Long.MAX_VALUE;
        for (var debit : retained) {
            long expiry = expires(debit, MINUTE, policy);
            if (expiry > now) {
                minuteRequests++;
                minuteTokens = Math.addExact(minuteTokens, debit.reservedTokens());
                earliestMinute = Math.min(earliestMinute, expiry);
            }
        }
        if ((policy.rpm() > 0 && minuteRequests >= policy.rpm())
                || (policy.tpm() > 0 && Math.addExact(minuteTokens, tokens) > policy.tpm())) {
            wait = Math.max(wait, earliestMinute - now);
        }
        if (policy.rpd() > 0 && retained.size() >= policy.rpd()) {
            long earliestDay = retained.stream().mapToLong(debit -> expires(debit, DAY, policy)).min().orElseThrow();
            wait = Math.max(wait, earliestDay - now);
        }
        var updated = new State(state.policyFingerprint(), at, state.cooldownUntil(), false, List.copyOf(retained));
        if (wait > 0) return new Decision(updated, false, wait);
        if (retained.size() >= policy.maximumReservations()) throw unavailable("Provider quota ledger capacity exhausted");
        retained.add(new Debit(id, at, tokens, false, false));
        return new Decision(new State(state.policyFingerprint(), at, state.cooldownUntil(), false,
                List.copyOf(retained)), true, 0);
    }
    /** Consumes one still-live debit; only extends occupancy, never creates capacity or another debit. */
    static State start(State state, Policy policy, String id, long now) {
        validate(state, policy, now); identity(id);
        if (state.unsafeTokenBound()) throw unavailable("Provider token bound was exceeded; operator review required");
        if (state.cooldownUntil() > now) throw unavailable("Provider cooldown changed before physical transport");
        var updated = new ArrayList<>(state.debits());
        for (int i = 0; i < updated.size(); i++) {
            var debit = updated.get(i);
            if (!debit.id().equals(id)) continue;
            long window = policy.rpm() > 0 || policy.tpm() > 0 ? MINUTE : DAY;
            if (debit.started() || debit.settled() || expires(debit, window, policy) <= now)
                throw unavailable("Provider quota receipt was consumed or expired before transport");
            long at = Math.max(now, state.highWaterMillis());
            updated.set(i, new Debit(id, at, debit.reservedTokens(), false, true));
            return new State(state.policyFingerprint(), at, state.cooldownUntil(), false, List.copyOf(updated));
        }
        throw unavailable("Provider quota receipt is no longer retained; a fresh admission is required");
    }
    static State settle(State state, Policy policy, String id, Long actualTokens, long now) {
        validate(state, policy, now);
        identity(id);
        if (actualTokens == null || actualTokens < 0 || policy.tpm() == 0) return state;
        var updated = new ArrayList<>(state.debits());
        for (int i = 0; i < updated.size(); i++) {
            var debit = updated.get(i);
            if (!debit.id().equals(id) || !debit.started() || debit.settled()) continue;
            if (actualTokens > debit.reservedTokens()) {
                return new State(state.policyFingerprint(), state.highWaterMillis(), state.cooldownUntil(), true, state.debits());
            }
            if (expires(debit, MINUTE, policy) <= now) continue;
            updated.set(i, new Debit(id, debit.admittedAt(), actualTokens, true, debit.started()));
            return new State(state.policyFingerprint(), state.highWaterMillis(), state.cooldownUntil(),
                    state.unsafeTokenBound(), List.copyOf(updated));
        }
        return state;
    }
    static State settleUsage(State state, Policy policy, String id, Usage usage, long now) {
        return settleUsage(state, policy, id, usage, now, false);
    }
    /** Called only by the private local receipt after a confirmed start; never by an archive/ID parser. */
    static State settleConfirmedStartedUsage(State state, Policy policy, String id, Usage usage, long now) {
        return settleUsage(state, policy, id, usage, now, true);
    }
    private static State settleUsage(State state, Policy policy, String id, Usage usage, long now, boolean confirmedStart) {
        validate(state, policy, now);
        identity(id);
        if (usage == null || usage.invalid() || usage.inputTokens() == null || usage.outputTokens() == null
                || usage.totalTokens() == null || usage.inputTokens() < 0 || usage.outputTokens() < 0
                || usage.totalTokens() < 0 || policy.tpm() == 0) return state;
        long total;
        try { total = Math.addExact(usage.inputTokens(), usage.outputTokens()); }
        catch (ArithmeticException overflow) { return state; }
        if (total != usage.totalTokens()
                || (usage.cachedInputTokens() != null && (usage.cachedInputTokens() < 0 || usage.cachedInputTokens() > usage.inputTokens()))
                || (usage.reasoningTokens() != null && (usage.reasoningTokens() < 0 || usage.reasoningTokens() > usage.outputTokens()))) return state;
        // A slow response can disprove a bound after its refund window has closed.
        // Only a retained, confirmed-started, unsettled receipt owns that evidence.
        if (!confirmedStart && state.debits().stream().noneMatch(debit -> debit.id().equals(id) && debit.started() && !debit.settled())) return state;
        if (usage.inputTokens() > policy.inputUpperBound() || usage.outputTokens() > policy.outputCap()) {
            return new State(state.policyFingerprint(), state.highWaterMillis(), state.cooldownUntil(), true, state.debits());
        }
        return settle(state, policy, id, total, now);
    }
    static State defer(State state, Policy policy, long delay, long now) {
        validate(state, policy, now);
        if (delay < 0) throw unavailable("Invalid provider cooldown budget");
        long at = Math.max(now, state.highWaterMillis());
        long allowance = 2 * policy.clockSkewMillis();
        // A long/overflowing provider Retry-After must never reset the group's cooldown.
        long until = delay > Long.MAX_VALUE - at - allowance ? Long.MAX_VALUE : at + delay + allowance;
        return new State(state.policyFingerprint(), at, Math.max(state.cooldownUntil(), until),
                state.unsafeTokenBound(), state.debits());
    }
    private static long expires(Debit debit, long window, Policy policy) {
        return Math.addExact(debit.admittedAt(), window + 2 * policy.clockSkewMillis());
    }
    private static void validate(State state, Policy policy, long now) {
        if (state == null || !policy.fingerprint().equals(state.policyFingerprint())) throw unavailable("Provider quota policy mismatch");
        if (now < 0 || now > Long.MAX_VALUE - DAY - 120_000 || state.highWaterMillis() < 0
                || state.cooldownUntil() < 0) throw unavailable("Invalid provider quota clock state");
        if (state.highWaterMillis() - now > 2 * policy.clockSkewMillis()) throw unavailable("Provider quota clock is outside configured skew");
        if (state.debits() == null || state.debits().size() > policy.maximumReservations()) throw unavailable("Invalid provider quota ledger size");
        var ids = new HashSet<String>();
        for (var debit : state.debits()) {
            if (debit == null) throw unavailable("Invalid provider quota ledger entry");
            identity(debit.id());
            if (!ids.add(debit.id()) || debit.admittedAt() < 0 || debit.admittedAt() > state.highWaterMillis()
                    || debit.reservedTokens() < 0 || debit.reservedTokens() > policy.tpm()) {
                throw unavailable("Invalid provider quota ledger entry");
            }
        }
    }
    private static void identity(String id) {
        try { if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException(); }
        catch (IllegalArgumentException | NullPointerException invalid) { throw unavailable("Invalid provider quota attempt identity"); }
    }
    private static IllegalStateException unavailable(String message) { return new IllegalStateException(message); }
}
