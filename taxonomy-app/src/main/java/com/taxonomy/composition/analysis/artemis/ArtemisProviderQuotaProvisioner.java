package com.taxonomy.composition.analysis.artemis;

import jakarta.jms.DeliveryMode;
import jakarta.jms.Message;
import jakarta.jms.Session;
import org.apache.activemq.artemis.api.core.ActiveMQQueueExistsException;
import org.apache.activemq.artemis.api.core.QueueConfiguration;
import org.apache.activemq.artemis.api.core.RoutingType;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.apache.activemq.artemis.jms.client.ActiveMQSession;
import tools.jackson.databind.json.JsonMapper;
import java.util.Map;
import java.util.Objects;

/**
 * Administrative one-shot command, never a startup refill. Atomic queue creation
 * is the exclusive claim. Existing queues ALWAYS reject, including empty/delivering
 * queues. A crash between creation and state commit requires offline operator review.
 */
public final class ArtemisProviderQuotaProvisioner {
    private ArtemisProviderQuotaProvisioner() {}
    public static void provision(ActiveMQConnectionFactory factory,String prefix,String group,ArtemisProviderQuotaPolicy policy) {
        Objects.requireNonNull(policy,"policy");
        String queue=new ArtemisProviderPermitSettings(prefix,Map.of(),1).queueName(group);
        try(var connection=factory.createConnection();var session=connection.createSession(true,Session.SESSION_TRANSACTED)) {
            ((ActiveMQSession)session).getCoreSession().createQueue(QueueConfiguration.of(queue)
                    .setAddress(queue).setRoutingType(RoutingType.ANYCAST).setDurable(true)
                    .setExclusive(true).setAutoCreated(false).setAutoDelete(false).setPurgeOnNoConsumers(false));
            var state=session.createTextMessage(JsonMapper.builder().build().writeValueAsString(ProviderQuotaLedger.empty(policy.ledgerPolicy())));
            state.setIntProperty("schemaVersion",1);state.setStringProperty("quotaGroup",group);
            state.setStringProperty("policyFingerprint",policy.ledgerPolicy().fingerprint());
            state.setStringProperty("tokenAdapter",Objects.requireNonNullElse(policy.tokenAdapter(),""));
            try(var producer=session.createProducer(session.createQueue(queue+"::"+queue))) {
                producer.send(state,DeliveryMode.PERSISTENT,Message.DEFAULT_PRIORITY,0);session.commit();
            }
        }catch(ActiveMQQueueExistsException exists) {
            throw new IllegalStateException("Provider quota queue already exists; no state or budget was modified",exists);
        }catch(Exception failure) {
            throw new IllegalStateException("Quota provisioning was not confirmed; inspect the queue with consumers stopped",failure);
        }
    }
    /** Shares the existing external broker/TLS credentials; none enter quota messages. */
    public static void main(String[] args) {
        if(args.length!=4&&args.length!=7) throw new IllegalArgumentException(
                "Usage: ArtemisProviderQuotaProvisioner <group> <rpm> <tpm> <rpd> [<token-adapter> <input-upper-bound> <output-cap>]");
        var env=System.getenv();String requireTls=env.getOrDefault("TAXONOMY_ANALYSIS_ARTEMIS_REQUIRE_TLS","true");
        if(!"true".equalsIgnoreCase(requireTls)&&!"false".equalsIgnoreCase(requireTls))
            throw new IllegalArgumentException("TAXONOMY_ANALYSIS_ARTEMIS_REQUIRE_TLS must be 'true' or 'false'");
        var policy=new ArtemisProviderQuotaPolicy(Integer.parseInt(args[1]),Long.parseLong(args[2]),Integer.parseInt(args[3]),
                args.length==7?Long.parseLong(args[5]):0,args.length==7?Integer.parseInt(args[6]):0,args.length==7?args[4]:null,
                Long.parseLong(env.getOrDefault("TAXONOMY_ANALYSIS_PROVIDER_QUOTAS_CLOCK_SKEW_MILLIS","1000")),
                Integer.parseInt(env.getOrDefault("TAXONOMY_ANALYSIS_PROVIDER_QUOTAS_MAXIMUM_RESERVATIONS","20000")));
        var broker=new ArtemisAnalysisSettings(env.get("TAXONOMY_ANALYSIS_ARTEMIS_BROKER_URL"),Boolean.parseBoolean(requireTls),1000,30_000);
        try(var factory=ArtemisAnalysisTransportConfiguration.connectionFactory(broker,
                env.get("TAXONOMY_ANALYSIS_ARTEMIS_USER"),env.get("TAXONOMY_ANALYSIS_ARTEMIS_PASSWORD"))) {
            factory.setInitialConnectAttempts(0);factory.setReconnectAttempts(0);
            provision(factory,env.getOrDefault("TAXONOMY_ANALYSIS_PROVIDER_QUOTAS_DESTINATION_PREFIX","taxonomy.analysis.provider-quotas"),args[0],policy);
        }
        System.out.println("Provider quota state provisioned");
    }
}
