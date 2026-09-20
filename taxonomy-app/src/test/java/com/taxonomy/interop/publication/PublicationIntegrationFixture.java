package com.taxonomy.interop.publication;

import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import com.taxonomy.extension.api.integration.PublicationContracts.*;
import com.taxonomy.interop.*;
import com.taxonomy.interop.persistence.*;
import com.taxonomy.workspace.service.*;
import com.taxonomy.workspace.model.RepositoryVisibility;
import com.taxonomy.dsl.command.ArchitectureCommand.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = { com.taxonomy.TaxonomyApplication.class, PublicationIntegrationFixture.Configuration.class })
abstract class PublicationIntegrationFixture {

    @TestConfiguration
    static class Configuration {

        @Bean
        PublicationContractConnector contractConnector() {
            return new PublicationContractConnector();
        }

        @Bean
        @Primary
        IntegrationDomainAdapter publicationDomain(IntegrationPortfolioPort projects, IntegrationJson json) {
            return new IntegrationDomainAdapter(projects, json, List.of(new IntegrationDomainAdapter.ProjectionProfile(PublicationContractProvider.PROFILE, "1")));
        }

        @Bean
        PublicationPolicy publicationPolicy() {
            return new PublicationPolicy(List.of(new PublicationPolicy.VerifiedContract(PublicationContractProvider.PROFILE, "1", PublicationContractProvider.CAPS)));
        }
    }

    @Autowired
    IntegrationPublicationService publication;

    @Autowired
    IntegrationService integrations;

    @Autowired
    IntegrationStore store;

    @Autowired
    PublicationLocalAuthority local;

    @Autowired
    PublicationPolicy policy;

    @Autowired
    IntegrationJson json;

    @Autowired
    ExchangeConnectorRegistry registry;

    @Autowired
    com.taxonomy.portfolio.service.ProjectPortfolioService projects;

    @Autowired
    com.taxonomy.editor.persistence.EditorJournal journal;

    @Autowired
    com.taxonomy.workspace.storage.DslGitRepositoryFactory git;

    @Autowired
    PublicationContractConnector connector;

    @Autowired
    ArchitectureRepositoryProvisioningService repositories;

    @Autowired
    RepositoryWorkspaceService workspaces;

    @Autowired
    WorkspaceArchitectureIntegrationPort editor;

    @TempDir
    Path directory;

    RepositoryContext context;

    UUID connection;

    PublicationContractProvider provider;

    @BeforeEach
    void setup() throws Exception {
        provider = new PublicationContractProvider(directory.resolve("provider.json"));
        connector.provider = provider;
        String actor = "publication-" + UUID.randomUUID().toString().substring(0, 8);
        var repo = repositories.createRepository("Publication", actor, "", RepositoryVisibility.PRIVATE, actor, "draft");
        var workspace = workspaces.createWorkingCopy(actor, repo.getRepositoryId(), "draft", "Publication", "");
        context = RepositoryContext.workspace(repo.getRepositoryId(), workspace.getWorkspaceId(), workspace.getCurrentBranch(), actor);
        connection = integrations.create(context, new IntegrationService.CreateConnection(UUID.randomUUID(), "Contract", PublicationContractProvider.PROFILE, AuthorityMode.BIDIRECTIONAL, PublicationContractProvider.SCOPE.externalScope(), null, null)).id();
    }

    @AfterEach
    void close() {
        provider.close();
    }

    InternalState state() {
        return integrations.overview(context, connection).current();
    }

    void edit(com.taxonomy.dsl.command.ArchitectureCommand... commands) throws Exception {
        var before = editor.read(context, null);
        UUID id = UUID.randomUUID();
        var metadata = new WorkspaceArchitectureIntegrationPort.CommandMetadata(id, id, id, "Reviewed local change");
        var checkpoint = new WorkspaceArchitectureIntegrationPort.CommandMetadata(UUID.randomUUID(), id, id, "Reviewed local checkpoint");
        var accepted = store.locked(context, connection, session -> {
            try {
                return editor.acceptIntegration(context, before.state(), metadata, id.toString(), List.of(commands), null, checkpoint);
            } catch (java.io.IOException failure) {
                throw new java.io.UncheckedIOException(failure);
            }
        });
        editor.checkpoint(context, accepted, checkpoint);
    }

    PublicationOperation preview(PublicationMode mode) {
        return publication.previewPublication(context, connection, new PublicationPreviewRequest(UUID.randomUUID(), state(), mode, PublicationContractProvider.SCOPE, provider.snapshot().revision()));
    }

    PublicationReview review(PublicationOperation p) {
        var resolutions = new TreeMap<String, PublicationResolution>();
        p.preview().changes().forEach(c -> resolutions.put(c.id(), PublicationResolution.KEEP_LOCAL));
        return new PublicationReview(new ReviewedChangeSet(p.operationId(), p.preview().fingerprint(), Map.of(), "Reviewed publication"), resolutions);
    }
}
