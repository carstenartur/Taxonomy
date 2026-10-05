package com.taxonomy.composition.analysis.artemis;

import com.taxonomy.analysis.dag.TaxonomyShardRoot;
import org.apache.activemq.artemis.api.core.RoutingType;
import org.apache.activemq.artemis.core.config.Configuration;
import org.apache.activemq.artemis.core.config.CoreAddressConfiguration;
import org.apache.activemq.artemis.core.deployers.impl.FileConfigurationParser;
import org.apache.activemq.artemis.core.security.Role;
import org.apache.activemq.artemis.core.settings.impl.AddressFullMessagePolicy;
import org.apache.activemq.artemis.core.settings.impl.AddressSettings;
import org.apache.activemq.artemis.core.settings.impl.HierarchicalObjectRepository;
import org.apache.activemq.artemis.utils.XMLUtil;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Validates the operator's actual XML with the same Artemis version used by the application. */
class ArtemisBrokerConfigurationTest {
    private static final String PREFIX = AnalysisDestinations.DEFAULT_PREFIX;

    @Test
    void realBrokerSchemaAndParserAcceptEveryApplicationDestination() throws Exception {
        Configuration config = configuration();
        assertThat(config.isPersistenceEnabled()).isTrue();
        assertThat(config.isSecurityEnabled()).isTrue();
        assertThat(config.isJournalSyncTransactional()).isTrue();
        assertThat(config.isJournalSyncNonTransactional()).isTrue();
        assertThat(config.getAcceptorConfigurations()).singleElement().satisfies(acceptor -> {
            assertThat(acceptor.getParams()).containsEntry("sslEnabled", "true")
                    .containsEntry("port", "61617").containsEntry("protocols", "CORE");
        });
        Map<String, CoreAddressConfiguration> addresses = config.getAddressConfigurations().stream()
                .collect(Collectors.toMap(CoreAddressConfiguration::getName, Function.identity()));
        AnalysisDestinations destinations = new AnalysisDestinations(PREFIX);
        Set<String> queues = new HashSet<>();
        for (TaxonomyShardRoot root : TaxonomyShardRoot.DEFAULT_ROOTS) {
            queues.add(destinations.subtaxonomy(root));
            queues.add(destinations.relation(root));
        }
        queues.addAll(Set.of(destinations.generalRelation(), destinations.completion(), destinations.rejected(),
                PREFIX + ".dlq", PREFIX + ".expiry", PREFIX + ".failed"));
        assertThat(addresses).hasSize(queues.size() + 2);
        for (String name : queues) {
            assertThat(addresses.get(name)).as("configured durable destination %s", name).isNotNull();
            assertThat(addresses.get(name).getRoutingTypes()).containsExactly(RoutingType.ANYCAST);
            assertThat(addresses.get(name).getQueueConfigs()).singleElement().satisfies(queue -> {
                assertThat(queue.getName().toString()).isEqualTo(name);
                assertThat(queue.isDurable()).isTrue();
            });
        }
        for (String live : Set.of(destinations.progress(), destinations.control())) {
            assertThat(addresses.get(live).getRoutingTypes()).containsExactly(RoutingType.MULTICAST);
            assertThat(addresses.get(live).getQueueConfigs()).isEmpty();
        }
    }

    @Test
    void taskFailurePolicyCannotExpireOrDeadLetterProviderCapacity() throws Exception {
        Configuration config = configuration();
        HierarchicalObjectRepository<AddressSettings> settings = new HierarchicalObjectRepository<>();
        config.getAddressSettings().forEach(settings::addMatch);
        AddressSettings work = settings.getMatch(PREFIX + ".subtaxonomy.CP");
        assertThat(work.getMaxDeliveryAttempts()).isEqualTo(10);
        assertThat(work.getDeadLetterAddress().toString()).isEqualTo(PREFIX + ".dlq");
        assertThat(work.getExpiryAddress().toString()).isEqualTo(PREFIX + ".expiry");
        assertThat(work.getExpiryDelay()).isEqualTo(86_400_000L);
        assertThat(work.isAutoCreateQueues()).isFalse();
        assertThat(work.isAutoDeleteQueues()).isFalse();
        assertThat(work.getAddressFullMessagePolicy()).isEqualTo(AddressFullMessagePolicy.PAGE);
        AddressSettings permits = settings.getMatch(PREFIX + ".provider-permits.shared-openai");
        assertThat(permits.getMaxDeliveryAttempts()).isEqualTo(-1);
        assertThat(permits.isNoExpiry()).isTrue();
        assertThat(permits.getExpiryDelay()).isEqualTo(-1);
        assertThat(permits.isAutoDeleteQueues()).isFalse();
        assertThat(permits.isAutoDeleteAddresses()).isFalse();
        assertThat(permits.isDefaultPurgeOnNoConsumers()).isFalse();
        assertThat(permits.getAddressFullMessagePolicy()).isEqualTo(AddressFullMessagePolicy.PAGE);
        assertThat(config.getAddressConfigurations()).noneSatisfy(address ->
                assertThat(address.getName()).startsWith(PREFIX + ".provider-permits."));
        for (String diagnostic : Set.of("completion", "rejected", "dlq", "expiry", "failed")) {
            assertThat(settings.getMatch(PREFIX + "." + diagnostic).isNoExpiry()).isTrue();
        }
    }

    @Test
    void applicationCanSubscribeAndSettleFailuresWithoutManagementOrPermitCreationRights() throws Exception {
        Configuration config = configuration();
        HierarchicalObjectRepository<Set<Role>> permissions = new HierarchicalObjectRepository<>();
        config.getSecurityRoles().forEach(permissions::addMatch);
        assertThat(permissions.getMatch("activemq.management")).noneMatch(role -> role.getName().equals("taxonomy-analysis"));
        for (String destination : Set.of("subtaxonomy.CP", "relation.IP", "completion", "dlq", "expiry")) {
            Role application = role(permissions, PREFIX + "." + destination, "taxonomy-analysis");
            assertThat(application.isConsume()).isTrue();
            assertThat(application.isManage()).isFalse();
            assertThat(application.isCreateDurableQueue()).isFalse();
        }
        for (String live : Set.of("progress", "control")) {
            Role application = role(permissions, PREFIX + "." + live, "taxonomy-analysis");
            assertThat(application.isCreateNonDurableQueue()).isTrue();
            assertThat(application.isDeleteNonDurableQueue()).isTrue();
            assertThat(application.isCreateDurableQueue()).isFalse();
        }
        Role application = role(permissions, PREFIX + ".provider-permits.shared-openai", "taxonomy-analysis");
        assertThat(application.isSend()).isTrue();
        assertThat(application.isConsume()).isTrue();
        assertThat(application.isCreateDurableQueue()).isFalse();
        assertThat(application.isDeleteDurableQueue()).isFalse();
        Role provisioner = role(permissions, PREFIX + ".provider-permits.shared-openai", "taxonomy-provisioner");
        assertThat(provisioner.isCreateDurableQueue()).isTrue();
        assertThat(provisioner.isConsume()).isFalse();
        assertThat(role(permissions, PREFIX + ".failed", "taxonomy-analysis").isConsume()).isFalse();
    }

    private static Role role(HierarchicalObjectRepository<Set<Role>> repository, String address, String name) {
        return repository.getMatch(address).stream().filter(role -> role.getName().equals(name)).findFirst().orElseThrow();
    }

    private static Configuration configuration() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("deploy/artemis/broker.xml"))) root = root.getParent();
        assertThat(root).isNotNull();
        Path example = root.resolve("deploy/artemis/broker.xml");
        try (var input = Files.newInputStream(example)) {
            var configuration = XMLUtil.streamToElement(input);
            var core = configuration.getElementsByTagName("core").item(0);
            assertThat(core).isNotNull();
            XMLUtil.validate(core, "schema/artemis-configuration.xsd");
        }
        try (var input = Files.newInputStream(example)) {
            return new FileConfigurationParser().parseMainConfig(input);
        }
    }
}
