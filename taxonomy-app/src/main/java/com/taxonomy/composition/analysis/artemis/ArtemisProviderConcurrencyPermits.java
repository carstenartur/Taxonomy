package com.taxonomy.composition.analysis.artemis;

import com.taxonomy.analysis.service.LlmProvider;
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
 * An uncommitted durable token delivery is one cluster-wide HTTP slot. Returning
 * the token and acknowledging its delivery share one local JMS transaction.
 * Closing a lost holder's connection rolls back its delivery at the broker.
 * Runtime code never initializes or replenishes a queue.
 */
public final class ArtemisProviderConcurrencyPermits implements ProviderConcurrencyPermits {
    private static final long RECEIVE_SLICE_MILLIS = 100;
    private final ArtemisAnalysisConnection connection;
    private final ArtemisProviderPermitSettings settings;

    public ArtemisProviderConcurrencyPermits(ArtemisAnalysisConnection connection,
                                            ArtemisProviderPermitSettings settings) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    @Override
    public Permit acquire(LlmProvider provider, Runnable checkpoint) {
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
            var consumer = session.createConsumer(destination);
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
                var permit = new TransactionalPermit(session, destination, group, id);
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
        if (token.getIntProperty("schemaVersion") != 1
                || !group.equals(token.getStringProperty("quotaGroup"))
                || token.getJMSDeliveryMode() != DeliveryMode.PERSISTENT
                || token.getJMSExpiration() != 0
                || !(token instanceof ActiveMQMessage artemis)
                || artemis.getCoreMessage().getType() != ActiveMQMessage.TYPE
                || artemis.getCoreMessage().getBodySize() != 0) {
            throw unavailable("Invalid cluster provider permit token", null);
        }
        String id = token.getStringProperty("permitId");
        try { UUID.fromString(id); }
        catch (IllegalArgumentException | NullPointerException invalid) {
            throw unavailable("Invalid cluster provider permit identity", null);
        }
        return id;
    }

    static Message token(Session session, String group, String id) throws JMSException {
        Message token = session.createMessage();
        token.setIntProperty("schemaVersion", 1);
        token.setStringProperty("quotaGroup", group);
        token.setStringProperty("permitId", id);
        return token;
    }

    private static final class TransactionalPermit implements Permit {
        private final Session session;
        private final Destination destination;
        private final String group;
        private final String id;
        private boolean closed;

        private TransactionalPermit(Session session, Destination destination, String group, String id) {
            this.session = session;
            this.destination = destination;
            this.group = group;
            this.id = id;
        }

        @Override
        public synchronized void close() {
            if (closed) return;
            closed = true;
            try (MessageProducer producer = session.createProducer(destination)) {
                producer.send(token(session, group, id), DeliveryMode.PERSISTENT, Message.DEFAULT_PRIORITY, 0);
                session.commit();
            } catch (JMSException failure) {
                restoreInterrupt(failure);
                // Never compensate by sending another token: commit may have reached the broker.
                throw unavailable("Cluster provider permit return was not confirmed", failure);
            } finally {
                // An uncommitted return rolls back both consumption and replacement together.
                closeSession(session);
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
            // Broker recovery returns unacknowledged tokens.
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
