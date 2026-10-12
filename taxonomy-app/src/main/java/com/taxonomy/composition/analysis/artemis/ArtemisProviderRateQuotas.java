package com.taxonomy.composition.analysis.artemis;

import com.taxonomy.analysis.service.ProviderRateQuotas;
import com.taxonomy.extension.api.llm.ProviderId;
import jakarta.jms.Session;
import jakarta.jms.DeliveryMode;
import jakarta.jms.Message;
import jakarta.jms.JMSException;
import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.api.core.RoutingType;
import org.apache.activemq.artemis.jms.client.ActiveMQSession;
import org.apache.activemq.artemis.jms.client.ActiveMQTextMessage;
import com.taxonomy.analysis.service.AnalysisStoppedException;
import com.taxonomy.analysis.service.LlmTransportMeter;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ExecutionException;
import java.util.function.BiFunction;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;

/** A committed consume/replace transaction is permanent rate admission before physical HTTP. */
final class ArtemisProviderRateQuotas implements ProviderRateQuotas {
    private final Supplier<SessionResource> sessions;
    private final ArtemisProviderQuotaSettings settings;
    private final ObjectMapper json;
    private final LongSupplier clock;
    ArtemisProviderRateQuotas(ArtemisAnalysisConnection connection, ArtemisProviderQuotaSettings settings,
                             ObjectMapper json, LongSupplier clock) {
        this(() -> { var session = connection.createSession(true);
            return new SessionResource(session, ((ActiveMQSession)session).getCoreSession()); }, settings, json, clock);
    }
    record SessionResource(Session session, org.apache.activemq.artemis.api.core.client.ClientSession core) {}
    ArtemisProviderRateQuotas(Supplier<SessionResource> sessions, ArtemisProviderQuotaSettings settings,
                             ObjectMapper json, LongSupplier clock) {
        this.sessions = Objects.requireNonNull(sessions); this.settings = Objects.requireNonNull(settings);
        this.json = Objects.requireNonNull(json); this.clock = Objects.requireNonNull(clock);
    }
    public TokenBudget requiredBudget(ProviderId provider) { return settings.tokenBudgets().get(settings.group(provider)); }
    public Decision tryReserve(ProviderId provider, String id, TokenBudget budget, Runnable checkpoint) {
        String group = settings.group(provider);
        if (!Objects.equals(requiredBudget(provider), budget)) throw unavailable("Unsupported physical provider TPM budget contract", null);
        return update(group, checkpoint, (state, now) -> {
            var decision = ProviderQuotaLedger.reserve(state, settings.policies().get(group), id,
                    budget == null ? 0 : budget.maximumTokens(), now);
            Decision result = decision.granted() ? new Decision(new DurableReservation(group, id, checkpoint), 0)
                    : new Decision(null, decision.waitMillis());
            return new Change<>(decision.state(), result);
        });
    }
    public void defer(ProviderId provider, long delayMillis) {
        String group = settings.group(provider);
        update(group, () -> {}, (state, now) -> new Change<>(ProviderQuotaLedger.defer(state,
                settings.policies().get(group), delayMillis, now), null));
    }
    private final class DurableReservation implements Reservation {
        private final String group, id;
        private final Runnable checkpoint;
        // This is process-local confirmed-start evidence, never reconstructed from an ID/JMS/archive.
        private volatile boolean startedConfirmed;
        DurableReservation(String group, String id, Runnable checkpoint) {
            this.group = group; this.id = id; this.checkpoint = Objects.requireNonNull(checkpoint);
        }
        public String id() { return id; }
        public void beforeTransport() {
            update(group, checkpoint, (state, now) -> new Change<>(ProviderQuotaLedger.start(state,
                    settings.policies().get(group), id, now), null));
            checkpoint.run();
            startedConfirmed = true;
        }
        public void settle(LlmTransportMeter.Usage usage) {
            if (!startedConfirmed) return;
            update(group, () -> {}, (state, now) -> new Change<>(ProviderQuotaLedger.settleConfirmedStartedUsage(state,
                    settings.policies().get(group), id, usage, now), null));
        }
    }
    private record Change<T>(ProviderQuotaLedger.State state, T value) {}
    private <T> T update(String group, Runnable checkpoint, BiFunction<ProviderQuotaLedger.State, Long, Change<T>> mutation) {
        Objects.requireNonNull(checkpoint).run();
        Session session = null;
        try {
            var opened = sessions.get(); session = opened.session();
            String queue = settings.queueName(group);
            var query = opened.core().queueQuery(SimpleString.of(queue));
            if (!query.isExists() || !query.isDurable() || query.getRoutingType() != RoutingType.ANYCAST
                    || !Boolean.TRUE.equals(query.isExclusive()))
                throw unavailable("Provider quota ledger is missing, non-durable or nonexclusive", null);
            var destination = session.createQueue(queue + "::" + queue);
            try (var consumer = session.createConsumer(destination)) {
                long started = System.nanoTime(); Message delivered = null;
                while (delivered == null) {
                    checkpoint.run();
                    long remaining = settings.maximumWaitMillis() - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                    if (remaining <= 0) throw unavailable("Provider quota ledger acquisition expired", null);
                    delivered = consumer.receive(Math.min(100, remaining));
                }
                checkpoint.run();
                // Queue statistics are not an atomic snapshot. This exclusive consumer asks the
                // actual broker for a second message/end-marker before any mutation or admission.
                if (secondReceive(consumer, checkpoint, started) != null)
                    throw unavailable("Provider quota ledger contains duplicate committed state", null);
                checkpoint.run();
                String expected = settings.policies().get(group).fingerprint();
                String adapter = settings.tokenBudgets().containsKey(group) ? settings.tokenBudgets().get(group).adapterId() : "";
                if (!(delivered instanceof ActiveMQTextMessage text) || delivered.getJMSDeliveryMode() != DeliveryMode.PERSISTENT
                        || delivered.getJMSExpiration() != 0 || delivered.getIntProperty("schemaVersion") != 1
                        || !group.equals(delivered.getStringProperty("quotaGroup"))
                        || !expected.equals(delivered.getStringProperty("policyFingerprint"))
                        || !adapter.equals(Objects.requireNonNullElse(delivered.getStringProperty("tokenAdapter"), ""))
                        || text.getCoreMessage().getBodySize() > 6_000_000) throw unavailable("Invalid provider quota ledger envelope", null);
                String body = text.getText();
                if (body == null || body.length() > 3_000_000) throw unavailable("Provider quota ledger exceeds bounded size", null);
                ProviderQuotaLedger.State state = json.readerFor(ProviderQuotaLedger.State.class)
                        .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                        .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(body);
                var changed = mutation.apply(state, clock.getAsLong());
                checkpoint.run();
                var replacement = session.createTextMessage(json.writeValueAsString(changed.state()));
                replacement.setIntProperty("schemaVersion", 1); replacement.setStringProperty("quotaGroup", group);
                replacement.setStringProperty("policyFingerprint", expected); replacement.setStringProperty("tokenAdapter", adapter);
                try (var producer = session.createProducer(destination)) {
                    producer.send(replacement, DeliveryMode.PERSISTENT, Message.DEFAULT_PRIORITY, 0);
                    session.commit();
                }
                // A lost commit acknowledgement never returns admission or sends a compensating refund.
                return changed.value();
            }
        } catch (UnavailableException | AnalysisStoppedException failure) { throw failure;
        } catch (Exception failure) {
            restoreInterrupt(failure);
            checkpoint.run();
            throw unavailable("Provider quota transaction was not confirmed", failure);
        } finally {
            if (session != null) {
                boolean interrupted = Thread.currentThread().isInterrupted();
                try { session.close(); } catch (JMSException failure) { restoreInterrupt(failure); }
                finally { if (interrupted) Thread.currentThread().interrupt(); }
            }
        }
    }
    private Message secondReceive(jakarta.jms.MessageConsumer consumer, Runnable checkpoint, long started) throws Exception {
        var result = new FutureTask<Message>(consumer::receiveNoWait);
        Thread receiver = Thread.ofVirtual().name("taxonomy-provider-quota-proof").start(result);
        try {
            while (true) {
                checkpoint.run();
                long remaining = settings.maximumWaitMillis() - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                if (remaining <= 0) throw unavailable("Provider quota duplicate proof expired", null);
                try { return result.get(Math.min(100, remaining), TimeUnit.MILLISECONDS); }
                catch (TimeoutException waiting) { /* Keep the ordinary cancellation/deadline checkpoint active. */ }
                catch (ExecutionException failed) {
                    if (failed.getCause() instanceof Exception exception) throw exception;
                    if (failed.getCause() instanceof Error error) throw error;
                    throw unavailable("Provider quota duplicate proof was not confirmed", failed);
                }
            }
        } finally {
            // No caller-side session/consumer operation overlaps the receiver. JMS explicitly
            // permits close to unblock receive; it is used only if interruption did not finish it.
            boolean interrupted = Thread.interrupted();
            receiver.interrupt();
            try {
                receiver.join(1000);
                if (receiver.isAlive()) { consumer.close(); receiver.join(1000); }
                if (receiver.isAlive()) throw unavailable("Provider quota proof receiver did not terminate", null);
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt(); throw stopped;
            } finally { if (interrupted) Thread.currentThread().interrupt(); }
        }
    }
    private static UnavailableException unavailable(String message, Throwable cause) { return new UnavailableException(message, cause); }
    private static void restoreInterrupt(Throwable failure) {
        for (var cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof InterruptedException || cause instanceof org.apache.activemq.artemis.api.core.ActiveMQInterruptedException) {
                Thread.currentThread().interrupt(); return;
            }
        }
    }
}
