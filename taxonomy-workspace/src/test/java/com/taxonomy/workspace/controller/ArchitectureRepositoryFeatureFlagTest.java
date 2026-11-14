package com.taxonomy.workspace.controller;

import com.taxonomy.workspace.service.ArchitectureRepositoryProvisioningService;
import com.taxonomy.workspace.service.RepositoryMembershipService;
import com.taxonomy.workspace.service.RepositoryWorkspaceService;
import com.taxonomy.workspace.service.SystemRepositoryService;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ArchitectureRepositoryFeatureFlagTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withInitializer(context -> {
                        // Register ready-made controller dependencies outside their service bean lifecycles.
                        var beans = context.getBeanFactory();
                        beans.registerSingleton("systemRepositoryService", mock(SystemRepositoryService.class));
                        beans.registerSingleton("provisioningService", mock(ArchitectureRepositoryProvisioningService.class));
                        beans.registerSingleton("repositoryWorkspaceService", mock(RepositoryWorkspaceService.class));
                        beans.registerSingleton("repositoryMembershipService", mock(RepositoryMembershipService.class));
                        beans.registerSingleton("workspaceResolver", mock(WorkspaceResolver.class));
                    })
                    .withUserConfiguration(ArchitectureRepositoryController.class);

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
}
