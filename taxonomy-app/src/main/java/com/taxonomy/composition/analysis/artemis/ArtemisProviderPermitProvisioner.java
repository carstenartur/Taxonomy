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

    /** Uses the same external broker/TLS/secret environment as the application. */
    public static void main(String[] args) {
        if (args.length != 2) throw new IllegalArgumentException("Usage: ArtemisProviderPermitProvisioner <quota-group> <capacity>");
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
            provision(factory, env.getOrDefault("TAXONOMY_ANALYSIS_PROVIDER_PERMITS_DESTINATION_PREFIX",
                    ArtemisProviderPermitSettings.DEFAULT_PREFIX), args[0], Integer.parseInt(args[1]));
        }
        System.out.println("Provider permit queue provisioned");
    }
}
