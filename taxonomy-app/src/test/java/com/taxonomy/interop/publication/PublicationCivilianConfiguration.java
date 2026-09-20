package com.taxonomy.interop.publication;

import com.taxonomy.interop.*;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import java.nio.file.Files;
import java.util.List;

/** Only imported by acceptance tests. This is a genuine HTTP CAS contract, never PCS. */
@TestConfiguration(proxyBeanMethods = false)
public class PublicationCivilianConfiguration {
    @Bean(destroyMethod = "close") PublicationContractProvider civilianPublicationProvider() throws Exception {
        return new PublicationContractProvider(Files.createTempDirectory("publication-civilian-").resolve("provider.json"));
    }
    @Bean PublicationContractConnector civilianPublicationConnector(PublicationContractProvider provider) {
        var connector = new PublicationContractConnector(); connector.provider = provider; return connector;
    }
    @Bean PublicationPolicy civilianPublicationPolicy() { return new PublicationPolicy(List.of(new PublicationPolicy.VerifiedContract(PublicationContractProvider.PROFILE, "1", PublicationContractProvider.CAPS))); }
    @Bean @Primary IntegrationDomainAdapter civilianPublicationDomain(IntegrationPortfolioPort projects, IntegrationJson json) {
        return new IntegrationDomainAdapter(projects, json, List.of(new IntegrationDomainAdapter.ProjectionProfile(PublicationContractProvider.PROFILE, "1")));
    }
}
