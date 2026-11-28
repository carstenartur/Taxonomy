package com.taxonomy.composition.architecture;

import com.taxonomy.dsl.command.ArchitectureCommand.SetCatalogueResponsibility;
import com.taxonomy.dsl.command.ArchitectureCommand.UpsertOrganization;
import com.taxonomy.dsl.export.ProjectDataSeed;
import com.taxonomy.dsl.export.ProjectDataSeedTest;
import com.taxonomy.dsl.mapper.AstToModelMapper;
import com.taxonomy.dsl.parser.TaxDslParser;
import com.taxonomy.editor.ArchitectureCommandPort.*;
import com.taxonomy.extension.api.project.ProjectDataProvider.Unit;
import com.taxonomy.extension.runtime.PluginCatalog;
import com.taxonomy.workspace.service.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@WithMockUser(username = "alice", roles = {"USER", "ARCHITECT", "ADMIN"})
@Tag("ci-app-shard-spring-context-a")
class ProjectDataImportTest extends LifecycleTestSupport {
    @Autowired com.taxonomy.dsl.export.TaxDslExportService ordinarySeed;
    @Autowired RepositoryWorkspaceService workingCopies;

    @Test void repeatImportsRetainNativeIdsAndLocalResponsibilitiesAcrossIndependentProjects() throws Exception {
        var plugins = new PluginCatalog();
        String provider = "authority/directory";
        var nativeUnits = List.of(new Unit("{100}", "Service", null), new Unit("{200}", "Service", "{100}"));
        ProjectDataSeedTest.register(plugins, "authority", ProjectDataSeedTest.directory("directory", null, nativeUnits));
        var datasets = new ProjectDataSeed(plugins);
        var provisioner = new ArchitectureRepositoryProvisioningService(repositories, membership, git, new ProjectCatalogueSeed() {
            public String newProject(String namespace) { return ordinarySeed.newProject(namespace); }
            public String newProject(String namespace, String selected) {
                var generation = new TaxDslParser().parse(newProject(namespace)).getMeta().catalogueRevision();
                return datasets.initial(namespace, selected, UUID.fromString(generation), ignored -> fail("Unexpected catalogue"));
            }
        });
        String slug = "provider-" + UUID.randomUUID();
        assertThatThrownBy(() -> provisioner.createOrResumePrivateRepository("Same project", slug, null, "alice", "unknown/missing"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(repositories.byCreationSlug(slug, "Same project")).isEmpty();
        var first = provisioner.createOrResumePrivateRepository("Same project", slug, null, "alice", provider).repository();
        assertThat(provisioner.createOrResumePrivateRepository("Same project", slug, null, "alice", provider).created()).isFalse();
        assertThatThrownBy(() -> provisioner.createOrResumePrivateRepository("Same project", slug, null, "alice", "other/directory"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        var second = provisioner.createOrResumePrivateRepository("Same project", "provider-" + UUID.randomUUID(), null, "alice").repository();
        var a = context(first.getRepositoryId());
        var b = context(second.getRepositoryId());
        String initial = editor.read(a, null).dsl();
        var original = new AstToModelMapper().map(new TaxDslParser().parse(initial)).getOrganizations();
        assertThat(original).hasSize(2).extracting(unit -> unit.title()).containsOnly("Service");
        String ownerId = original.stream().filter(unit -> "{200}".equals(unit.extensions().get("x-source-id")))
                .findFirst().orElseThrow().id();
        var ctx = editor.read(a, null).context();
        var edited = editor.execute(a, new Command(ctx, metadata("Rename the local service"),
                new SemanticCommand(new UpsertOrganization(ownerId, Map.of("title", "Local service")))));
        var assigned = editor.execute(a, new Command(edited.context(), metadata("Assign the catalogue branch"),
                new SemanticCommand(new SetCatalogueResponsibility("BP", ownerId))));
        editor.checkpoint(a, new CreateCheckpointCommand(assigned.context(), metadata("Keep local responsibilities")));
        String before = editor.read(a, null).dsl();

        var contexts = mock(LifecycleHttpContext.class);
        var api = new ProjectDataImportController(contexts, lifecycle, editor, datasets, transactions);
        when(contexts.resolveForRead()).thenReturn(a);
        var prior = editor.read(a, null).context();
        var repeated = importDataset(api, a, provider, 0, 2);
        assertThat(repeated.context()).isEqualTo(prior);
        assertThat(editor.read(a, repeated.context().commit()).dsl()).isEqualTo(before);
        assertThat(editor.read(b, null).dsl()).doesNotContain(ownerId);
        when(contexts.resolveForRead()).thenReturn(b);
        var imported = importDataset(api, b, provider, 2, 0);
        var projectB = new AstToModelMapper().map(new TaxDslParser().parse(editor.read(b, imported.context().commit()).dsl()));
        assertThat(projectB.getOrganizations()).containsExactlyInAnyOrderElementsOf(original);
        assertThat(projectB.getCatalogueResponsibilities()).isEmpty();
        importDataset(api, b, provider, 0, 2);
        assertThat(editor.read(a, null).dsl()).isEqualTo(before);
        assertThat(before).contains("Local service", "catalogueResponsibility BP", ownerId);
    }

    private RepositoryContext context(String repositoryId) {
        var copy = workingCopies.createWorkingCopy("alice", repositoryId, "draft", "Independent workspace", null);
        return RepositoryContext.workspace(repositoryId, copy.getWorkspaceId(), copy.getCurrentBranch(), "alice");
    }

    private ArchitectureLifecycleService.Publication importDataset(ProjectDataImportController api, RepositoryContext target,
            String provider, int added, int existing) throws Exception {
        var head = editor.read(target, null).context();
        var intent = metadata("Import the source directory");
        String etag = "\"workspace-revision-" + head.revision() + "\"";
        var preview = api.preview(new ProjectDataImportController.Request(head, intent, provider, null), etag).getBody();
        assertThat(preview.added()).isEqualTo(added);
        assertThat(preview.existing()).isEqualTo(existing);
        var result = api.apply(new ProjectDataImportController.Request(head, intent, provider, preview.datasetHash()), etag).getBody();
        assertThat(result.state()).isEqualTo(added == 0 ? "UNCHANGED" : "CHECKPOINTED");
        return result;
    }
}
