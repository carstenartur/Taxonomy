package com.taxonomy.composition.analysis.artemis;

import jakarta.jms.DeliveryMode;
import jakarta.jms.Message;
import jakarta.jms.Session;
import org.apache.activemq.artemis.api.core.ActiveMQQueueExistsException;
import org.apache.activemq.artemis.api.core.QueueConfiguration;
import org.apache.activemq.artemis.api.core.RoutingType;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.apache.activemq.artemis.jms.client.ActiveMQSession;

import java.util.Map;
import java.util.UUID;

/**
 * Explicit administrative one-shot command, never a Spring startup hook. Broker
 * queue creation is the exclusive claim: an existing queue ALWAYS rejects, even
 * when empty or when every token is currently delivering. Token creation commits
 * as one transaction. A crash between queue creation and commit fails closed and
 * requires operator inspection with all consumers stopped, never auto-refilling.
 */
public final class ArtemisProviderPermitProvisioner {
    private ArtemisProviderPermitProvisioner() {}

    public static void provision(ActiveMQConnectionFactory factory, String prefix, String group, int capacity) {
        if (capacity < 1 || capacity > 10_000) throw new IllegalArgumentException("Permit capacity must be within 1..10000");
        String queue = new ArtemisProviderPermitSettings(prefix, Map.of(), 1).queueName(group);
        try (var connection = factory.createConnection();
             var session = connection.createSession(true, Session.SESSION_TRANSACTED)) {
            // Atomic broker operation, not a racy browse/count/check-then-seed protocol.
            ((ActiveMQSession) session).getCoreSession().createQueue(QueueConfiguration.of(queue)
                    .setAddress(queue).setRoutingType(RoutingType.ANYCAST).setDurable(true)
                    .setAutoCreated(false).setAutoDelete(false).setPurgeOnNoConsumers(false));
            try (var producer = session.createProducer(session.createQueue(queue + "::" + queue))) {
                for (int i = 0; i < capacity; i++) {
                    producer.send(ArtemisProviderConcurrencyPermits.token(session, group, UUID.randomUUID().toString()),
                            DeliveryMode.PERSISTENT, Message.DEFAULT_PRIORITY, 0);
                }
                session.commit();
            }
        } catch (ActiveMQQueueExistsException exists) {
            throw new IllegalStateException("Provider permit queue already exists; capacity was not modified", exists);
        } catch (Exception failure) {
            throw new IllegalStateException("Permit provisioning was not confirmed; inspect the queue with consumers stopped", failure);
        }
    }

    /** Metadata only. Browsing is an administrative capability, not worker admission. */
    public record QuarantinedPermit(String permitId, String attemptId, long startedAt) { }

    public record Inspection(java.util.List<QuarantinedPermit> receipts, boolean truncated) {
        public Inspection { receipts = java.util.List.copyOf(receipts); }
    }

    /** A bounded observation, never proof of absence while other consumers are active. */
    public static Inspection inspect(ActiveMQConnectionFactory factory, String prefix, String group, int limit) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("Inspection limit must be within 1..1000");
        String queue = new ArtemisProviderPermitSettings(prefix, Map.of(), 1).queueName(group);
        try (var connection = factory.createConnection();
             var session = connection.createSession(true, Session.SESSION_TRANSACTED)) {
            requireExistingQueue(session, queue);
            connection.start();
            try (var browser = session.createBrowser(session.createQueue(queue + "::" + queue),
                    "permitState = '" + ArtemisProviderConcurrencyPermits.IN_FLIGHT + "'")) {
                var result = new java.util.ArrayList<QuarantinedPermit>();
                var messages = browser.getEnumeration();
                while (result.size() < limit && messages.hasMoreElements()) {
                    Message message = (Message) messages.nextElement();
                    String id = ArtemisProviderConcurrencyPermits.canonicalId(message.getStringProperty("permitId"));
                    String attempt = ArtemisProviderConcurrencyPermits.canonicalId(message.getStringProperty("attemptId"));
                    ArtemisProviderConcurrencyPermits.validateReceipt(message, group, id, attempt);
                    result.add(new QuarantinedPermit(id, attempt, message.getLongProperty("startedAt")));
                }
                return new Inspection(result, messages.hasMoreElements());
            }
        } catch (Exception failure) {
            throw new IllegalStateException("Provider receipt inspection was not confirmed", failure);
        }
    }

    /**
     * Operator must stop competing consumers and independently confirm the remote
     * request ended. Exact receipt consumption prevents retry/late-owner duplication.
     * This never creates a missing queue or resets the independent rate ledger.
     */
    public static void reconcile(ActiveMQConnectionFactory factory, String prefix, String group,
                                 String permitId, String attemptId, boolean upstreamStopped, boolean workersStopped) {
        if (!upstreamStopped) throw new IllegalArgumentException("Explicit upstream-stopped confirmation is required");
        if (!workersStopped) throw new IllegalArgumentException("Explicit stopped-workers confirmation is required");
        ArtemisProviderConcurrencyPermits.canonicalId(permitId);
        ArtemisProviderConcurrencyPermits.canonicalId(attemptId);
        String queue = new ArtemisProviderPermitSettings(prefix, Map.of(), 1).queueName(group);
        try (var connection = factory.createConnection();
             var session = connection.createSession(true, Session.SESSION_TRANSACTED)) {
            requireExistingQueue(session, queue);
            connection.start();
            ArtemisProviderConcurrencyPermits.returnReceipt(session, session.createQueue(queue + "::" + queue),
                    group, permitId, attemptId, 1000);
        } catch (Exception failure) {
            throw new IllegalStateException("Exact receipt reconciliation was not confirmed; never create replacement capacity", failure);
        }
    }

    private static void requireExistingQueue(Session session, String queue) throws Exception {
        var existing = ((ActiveMQSession) session).getCoreSession().queueQuery(
                org.apache.activemq.artemis.api.core.SimpleString.of(queue));
        if (!existing.isExists() || !existing.isDurable()) {
            throw new IllegalStateException("Provider permit queue is not provisioned and durable");
        }
    }

    /** Uses the same external broker/TLS/secret environment as the application. */
    public static void main(String[] args) {
        boolean inspect = args.length == 2 && "--inspect".equals(args[0]);
        boolean reconcile = args.length == 6 && "--reconcile".equals(args[0])
                && "--confirmed-upstream-stopped".equals(args[4])
                && "--confirmed-workers-stopped".equals(args[5]);
        boolean provision = args.length == 2 && !args[0].startsWith("--");
        if (!inspect && !reconcile && !provision) throw new IllegalArgumentException(
                "Usage: ArtemisProviderPermitProvisioner <quota-group> <capacity> | --inspect <quota-group> | "
                        + "--reconcile <quota-group> <permit-id> <attempt-id> --confirmed-upstream-stopped --confirmed-workers-stopped");
        var env = System.getenv();
        String requireTls = env.getOrDefault("TAXONOMY_ANALYSIS_ARTEMIS_REQUIRE_TLS", "true");
        if (!"true".equalsIgnoreCase(requireTls) && !"false".equalsIgnoreCase(requireTls)) {
            throw new IllegalArgumentException("TAXONOMY_ANALYSIS_ARTEMIS_REQUIRE_TLS must be 'true' or 'false'");
        }
        var settings = new ArtemisAnalysisSettings(env.get("TAXONOMY_ANALYSIS_ARTEMIS_BROKER_URL"),
                Boolean.parseBoolean(requireTls), 1000, 30_000);
        try (var factory = ArtemisAnalysisTransportConfiguration.connectionFactory(settings,
                env.get("TAXONOMY_ANALYSIS_ARTEMIS_USER"), env.get("TAXONOMY_ANALYSIS_ARTEMIS_PASSWORD"))) {
            factory.setInitialConnectAttempts(0);
            factory.setReconnectAttempts(0);
            String prefix = env.getOrDefault("TAXONOMY_ANALYSIS_PROVIDER_PERMITS_DESTINATION_PREFIX",
                    ArtemisProviderPermitSettings.DEFAULT_PREFIX);
            if (inspect) {
                Inspection result = inspect(factory, prefix, args[1], 1000);
                result.receipts().forEach(receipt -> System.out.println(receipt.permitId() + "\t"
                        + receipt.attemptId() + "\t" + receipt.startedAt()));
                System.out.println("Inspection truncated=" + result.truncated()
                        + "; observation is not proof of no active remote requests");
            } else if (reconcile) {
                reconcile(factory, prefix, args[1], args[2], args[3], true, true);
                System.out.println("Exact provider receipt reconciled; rate quota unchanged");
            } else {
                provision(factory, prefix, args[0], Integer.parseInt(args[1]));
                System.out.println("Provider permit queue provisioned");
            }
        }
    }
}
