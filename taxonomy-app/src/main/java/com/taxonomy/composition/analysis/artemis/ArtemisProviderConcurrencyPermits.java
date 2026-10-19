package com.taxonomy.composition.analysis.artemis;

import com.taxonomy.extension.api.llm.ProviderId;
import com.taxonomy.analysis.service.ProviderConcurrencyPermits;
import jakarta.jms.DeliveryMode;
import jakarta.jms.Destination;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.MessageProducer;
import jakarta.jms.Session;
import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.jms.client.ActiveMQSession;
import org.apache.activemq.artemis.jms.client.ActiveMQMessage;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Available slots are transactionally converted to durable IN_FLIGHT receipts
 * before HTTP. A lost holder redelivers only that unavailable receipt, never an
 * available slot. Known-complete responses return one exact receipt atomically;
 * uncertain outcomes require explicit operator reconciliation. No automatic refill.
 */
public final class ArtemisProviderConcurrencyPermits implements ProviderConcurrencyPermits {
    private static final long RECEIVE_SLICE_MILLIS = 100;
    static final String AVAILABLE_SELECTOR = "permitState IS NULL";
    static final String IN_FLIGHT = "IN_FLIGHT";
    private static final long RECEIPT_WAIT_MILLIS = 5000;
    private final ArtemisAnalysisConnection connection;
    private final ArtemisProviderPermitSettings settings;

    public ArtemisProviderConcurrencyPermits(ArtemisAnalysisConnection connection,
                                            ArtemisProviderPermitSettings settings) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    @Override
    public Permit acquire(ProviderId provider, Runnable checkpoint) {
        Objects.requireNonNull(checkpoint, "checkpoint").run();
        String group = settings.providerGroups().get(Objects.requireNonNull(provider, "provider"));
        if (group == null) throw unavailable("No explicit cluster quota group configured for " + provider, null);
        Session session = null;
        boolean handedOff = false;
        try {
            session = connection.createSession(true);
            String queue = settings.queueName(group);
            var query = ((ActiveMQSession) session).getCoreSession().queueQuery(SimpleString.of(queue));
            if (!query.isExists() || !query.isDurable()) {
                throw unavailable("Cluster provider permit queue is not provisioned and durable", null);
            }
            Destination destination = session.createQueue(queue + "::" + queue);
            var consumer = session.createConsumer(destination, AVAILABLE_SELECTOR);
            long started = System.nanoTime();
            while (true) {
                checkpoint.run();
                long remaining = settings.maximumWaitMillis()
                        - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                if (remaining <= 0) throw unavailable("Cluster provider permit wait expired", null);
                Message token = consumer.receive(Math.min(RECEIVE_SLICE_MILLIS, remaining));
                checkpoint.run();
                if (token == null) continue;
                String id = validate(token, group);
                var permit = new TransactionalPermit(session, destination, group, id,
                        () -> connection.createSession(true), checkpoint, settings.maximumWaitMillis(), consumer);
                handedOff = true;
                return permit;
            }
        } catch (ProviderConcurrencyPermits.UnavailableException failure) {
            throw failure;
        } catch (com.taxonomy.analysis.service.AnalysisStoppedException stopped) {
            throw stopped;
        } catch (Exception failure) {
            restoreInterrupt(failure);
            checkpoint.run();
            throw unavailable("Cluster provider permit acquisition unavailable", failure);
        } finally {
            if (!handedOff) closeSession(session);
        }
    }

    private static String validate(Message token, String group) throws JMSException {
        return validate(token, group, 1);
    }

    private static String validate(Message token, String group, int version) throws JMSException {
        if (token.getIntProperty("schemaVersion") != version
                || !group.equals(token.getStringProperty("quotaGroup"))
                || token.getJMSDeliveryMode() != DeliveryMode.PERSISTENT
                || token.getJMSExpiration() != 0
                || !(token instanceof ActiveMQMessage artemis)
                || artemis.getCoreMessage().getType() != ActiveMQMessage.TYPE
                || artemis.getCoreMessage().getBodySize() != 0) {
            throw unavailable("Invalid cluster provider permit token", null);
        }
        String id = token.getStringProperty("permitId");
        try { return canonicalId(id); }
        catch (IllegalArgumentException | NullPointerException invalid) {
            throw unavailable("Invalid cluster provider permit identity", null);
        }
    }

    static Message token(Session session, String group, String id) throws JMSException {
        Message token = session.createMessage();
        token.setIntProperty("schemaVersion", 1);
        token.setStringProperty("quotaGroup", group);
        token.setStringProperty("permitId", id);
        return token;
    }

    static String canonicalId(String value) {
        if (!UUID.fromString(value).toString().equals(value)) {
            throw new IllegalArgumentException("Expected canonical UUID");
        }
        return value;
    }

    static String receiptSelector(String id, String attempt) {
        return "permitState = '" + IN_FLIGHT + "' AND permitId = '" + canonicalId(id)
                + "' AND attemptId = '" + canonicalId(attempt) + "'";
    }

    static void validateReceipt(Message receipt, String group, String id, String attempt) throws JMSException {
        if (receipt == null || !id.equals(validate(receipt, group, 2))
                || !IN_FLIGHT.equals(receipt.getStringProperty("permitState"))
                || !attempt.equals(receipt.getStringProperty("attemptId"))
                || receipt.getLongProperty("startedAt") <= 0) {
            throw unavailable("Exact in-flight provider receipt is unavailable", null);
        }
    }

    /** Must consume the privately identified receipt before producing any available capacity. */
    static void returnReceipt(Session session, Destination destination, String group, String id,
                              String attempt, long maximumWait) throws JMSException {
        try (var consumer = session.createConsumer(destination, receiptSelector(id, attempt))) {
            Message receipt = consumer.receive(Math.max(1, Math.min(maximumWait, RECEIPT_WAIT_MILLIS)));
            validateReceipt(receipt, group, id, attempt);
            try (var producer = session.createProducer(destination)) {
                producer.send(token(session, group, id), DeliveryMode.PERSISTENT, Message.DEFAULT_PRIORITY, 0);
                session.commit();
            }
        }
    }

    /** Package-visible for deterministic transaction-fault unit tests; broker acceptance is separate. */
    static final class TransactionalPermit implements Permit {
        private enum State { UNUSED, UNCERTAIN, IN_FLIGHT, COMPLETE, CLOSED }
        private final Session session;
        private final Destination destination;
        private final String group;
        private final String id;
        private final String attempt = UUID.randomUUID().toString();
        private final java.util.function.Supplier<Session> sessions;
        private final Runnable checkpoint;
        private final long maximumWait;
        private final jakarta.jms.MessageConsumer availableConsumer;
        private State state = State.UNUSED;

        TransactionalPermit(Session session, Destination destination, String group, String id,
                            java.util.function.Supplier<Session> sessions, Runnable checkpoint,
                            long maximumWait, jakarta.jms.MessageConsumer availableConsumer) {
            this.session = session; this.destination = destination; this.group = group; this.id = id;
            this.sessions = sessions; this.checkpoint = checkpoint; this.maximumWait = maximumWait;
            this.availableConsumer = availableConsumer;
        }

        @Override
        public synchronized void beforeTransport() {
            if (state != State.UNUSED) throw unavailable("Provider permit has already been used", null);
            checkpoint.run();
            // Mark uncertainty BEFORE sending: an exception can follow a committed write.
            state = State.UNCERTAIN;
            try {
                Message receipt = token(session, group, id);
                receipt.setIntProperty("schemaVersion", 2);
                receipt.setStringProperty("permitState", IN_FLIGHT);
                receipt.setStringProperty("attemptId", attempt);
                receipt.setLongProperty("startedAt", System.currentTimeMillis());
                try (var producer = session.createProducer(destination)) {
                    producer.send(receipt, DeliveryMode.PERSISTENT, Message.DEFAULT_PRIORITY, 0);
                    session.commit();
                }
                availableConsumer.close();
                var holder = session.createConsumer(destination, receiptSelector(id, attempt));
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(
                        Math.max(1, Math.min(maximumWait, RECEIPT_WAIT_MILLIS)));
                Message held;
                do {
                    checkpoint.run();
                    held = holder.receive(RECEIVE_SLICE_MILLIS);
                } while (held == null && System.nanoTime() < deadline);
                validateReceipt(held, group, id, attempt);
                checkpoint.run();
                // Receipt delivery is deliberately uncommitted during HTTP. Redelivery
                // after worker/connection/broker loss still excludes it from available slots.
                state = State.IN_FLIGHT;
            } catch (JMSException failure) {
                restoreInterrupt(failure);
                throw unavailable("Durable provider capacity start was not confirmed", failure);
            }
        }

        @Override
        public synchronized void responseReceived(int statusCode) {
            if (state != State.IN_FLIGHT) throw unavailable("Provider response has no active capacity owner", null);
            // Only synchronous success and explicit client rejection prove completion.
            // In particular 202, 408 and proxy/server errors are NOT proof of termination.
            state = switch (statusCode) {
                case 200, 400, 401, 403, 404, 413, 415, 422, 429 -> State.COMPLETE;
                default -> State.UNCERTAIN;
            };
        }

        @Override
        public synchronized void close() {
            State previous = state;
            if (previous == State.CLOSED) return;
            state = State.CLOSED;
            if (previous == State.UNUSED) {
                try (MessageProducer producer = session.createProducer(destination)) {
                    producer.send(token(session, group, id), DeliveryMode.PERSISTENT, Message.DEFAULT_PRIORITY, 0);
                    session.commit();
                } catch (JMSException failure) {
                    restoreInterrupt(failure);
                    throw unavailable("Cluster provider permit return was not confirmed", failure);
                } finally { closeSession(session); }
                return;
            }
            // End the old delivery first. A late owner cannot send on a recovered,
            // rollback-only session or create capacity already reconciled elsewhere.
            closeSession(session);
            if (previous != State.COMPLETE) return; // retain durable receipt; never auto-expire it
            try (Session returning = sessions.get()) {
                returnReceipt(returning, destination, group, id, attempt, maximumWait);
            } catch (Exception failure) {
                restoreInterrupt(failure);
                // The complete HTTP answer remains valid even if capacity cleanup fails.
                // Do not cause a second paid request by masking it with a cleanup error.
                // A lost commit acknowledgement may mean available OR quarantined, never
                // permission for an unpaired compensating send.
                org.slf4j.LoggerFactory.getLogger(ArtemisProviderConcurrencyPermits.class)
                        .warn("Provider capacity cleanup was not confirmed; inspect retained receipts before reconciliation");
            }
        }
    }

    private static UnavailableException unavailable(String message, Throwable cause) {
        return new UnavailableException(message, cause);
    }

    private static void closeSession(Session session) {
        if (session == null) return;
        boolean interrupted = Thread.currentThread().isInterrupted();
        try { session.close(); }
        catch (JMSException failure) {
            restoreInterrupt(failure);
            // Broker recovery returns the exact unacknowledged available token or unavailable receipt.
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static void restoreInterrupt(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof InterruptedException
                    || cause instanceof org.apache.activemq.artemis.api.core.ActiveMQInterruptedException) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
