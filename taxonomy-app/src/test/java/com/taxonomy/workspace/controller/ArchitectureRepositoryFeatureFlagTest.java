package com.taxonomy.workspace.controller;

import com.taxonomy.workspace.service.ArchitectureRepositoryProvisioningService;
import com.taxonomy.workspace.service.RepositoryMembershipService;
import com.taxonomy.workspace.service.RepositoryWorkspaceService;
import com.taxonomy.workspace.service.SystemRepositoryService;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ArchitectureRepositoryFeatureFlagTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(FeatureFlagContext.class);

    @Test
    void publicMultiRepositoryApiIsAbsentByDefault() {
        contextRunner.run(context -> assertThat(context)
                .doesNotHaveBean(ArchitectureRepositoryController.class));
    }

    @Test
    void explicitFalseKeepsPublicMultiRepositoryApiAbsent() {
        contextRunner
                .withPropertyValues(
                        "taxonomy.features.multi-repository-api.enabled=false")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(ArchitectureRepositoryController.class));
    }

    @Test
    void publicMultiRepositoryApiRequiresExplicitOptIn() {
        contextRunner
                .withPropertyValues(
                        "taxonomy.features.multi-repository-api.enabled=true")
                .run(context -> assertThat(context)
                        .hasSingleBean(ArchitectureRepositoryController.class));
    }

    @Configuration(proxyBeanMethods = false)
    @Import(ArchitectureRepositoryController.class)
    static class FeatureFlagContext {

        @Bean
        SystemRepositoryService systemRepositoryService() {
            return mock(SystemRepositoryService.class);
        }

        @Bean
        ArchitectureRepositoryProvisioningService provisioningService() {
            return mock(ArchitectureRepositoryProvisioningService.class);
        }

        @Bean
        RepositoryWorkspaceService repositoryWorkspaceService() {
            return mock(RepositoryWorkspaceService.class);
        }

        @Bean
        RepositoryMembershipService repositoryMembershipService() {
            return mock(RepositoryMembershipService.class);
        }

        @Bean
        WorkspaceResolver workspaceResolver() {
            return mock(WorkspaceResolver.class);
        }
    }
}
