package com.taxonomy.composition.architecture;

import com.taxonomy.dsl.command.ArchitectureCommand.SetCatalogueResponsibility;
import com.taxonomy.dsl.command.ArchitectureCommand.UpsertOrganization;
import com.taxonomy.extension.api.plugin.PluginIdentity;
import com.taxonomy.dsl.export.ProjectDataSeedTest;
import com.taxonomy.dsl.mapper.AstToModelMapper;
import com.taxonomy.dsl.parser.TaxDslParser;
import com.taxonomy.editor.ArchitectureCommandPort.*;
import com.taxonomy.extension.api.project.ProjectDataProvider.Unit;
import com.taxonomy.extension.runtime.PluginCatalog;
import com.taxonomy.workspace.service.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import com.taxonomy.security.service.DatabaseUserDetailsService;
import com.taxonomy.security.service.UserManagementService;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Tag("ci-app-shard-spring-context-a")
class ProjectDataImportTest extends LifecycleTestSupport {
    @Autowired ArchitectureRepositoryProvisioningService provisioner;
    @Autowired ProjectDataImportController api;
    @Autowired PluginCatalog plugins;
    @Autowired UserManagementService accounts;
    @Autowired DatabaseUserDetailsService users;
    private String username;
    private PluginIdentity installed;

    @AfterEach void uninstallFixture() {
        if (installed != null) { plugins.beginDraining(installed); plugins.remove(installed); }
    }
    @Autowired RepositoryWorkspaceService workingCopies;

    @Test void repeatImportsRetainNativeIdsAndLocalResponsibilitiesAcrossIndependentProjects() throws Exception {
        username = "dataset-owner-" + UUID.randomUUID();
        accounts.createUser(Map.of("username", username, "roles", List.of("USER", "ARCHITECT", "ADMIN")),
                UUID.randomUUID().toString(), "test");
        var actor = users.loadUserByUsername(username);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(actor, null, actor.getAuthorities()));
        String provider = "authority/directory";
        var nativeUnits = List.of(new Unit("{100}", "Service", null), new Unit("{200}", "Service", "{100}"));
        ProjectDataSeedTest.register(plugins, "authority", ProjectDataSeedTest.directory("directory", null, nativeUnits));
        installed = new PluginIdentity("authority", "1.0.0", "a".repeat(64));
        String slug = "provider-" + UUID.randomUUID();
        assertThatThrownBy(() -> provisioner.createOrResumePrivateRepository("Same project", slug, null, username, "unknown/missing"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(repositories.byCreationSlug(slug, "Same project")).isEmpty();
        var first = provisioner.createOrResumePrivateRepository("Same project", slug, null, username, provider).repository();
        assertThat(provisioner.createOrResumePrivateRepository("Same project", slug, null, username, provider).created()).isFalse();
        assertThatThrownBy(() -> provisioner.createOrResumePrivateRepository("Same project", slug, null, username, "other/directory"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        var second = provisioner.createOrResumePrivateRepository("Same project", "provider-" + UUID.randomUUID(), null, username).repository();
        var a = context(first.getRepositoryId());
        assertThatThrownBy(() -> workingCopies.createWorkingCopy(username, second.getRepositoryId(), "draft",
                "Workspace for " + first.getRepositoryId(), null)).isInstanceOf(RepositoryWorkspaceService.WorkspaceNameConflict.class);
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

        var prior = editor.read(a, null).context();
        var repeated = importDataset(api, a, provider, 0, 2);
        assertThat(repeated.context()).isEqualTo(prior);
        assertThat(editor.read(a, repeated.context().commit()).dsl()).isEqualTo(before);
        assertThat(editor.read(b, null).dsl()).doesNotContain(ownerId);
        var imported = importDataset(api, b, provider, 2, 0);
        var projectB = new AstToModelMapper().map(new TaxDslParser().parse(editor.read(b, imported.context().commit()).dsl()));
        assertThat(projectB.getOrganizations()).containsExactlyInAnyOrderElementsOf(original);
        assertThat(projectB.getCatalogueResponsibilities()).isEmpty();
        importDataset(api, b, provider, 0, 2);
        assertThat(editor.read(a, null).dsl()).isEqualTo(before);
        assertThat(before).contains("Local service", "catalogueResponsibility BP", ownerId);
    }

    private RepositoryContext context(String repositoryId) {
        var copy = workingCopies.createWorkingCopy(username, repositoryId, "draft", "Workspace for " + repositoryId, null);
        return RepositoryContext.workspace(repositoryId, copy.getWorkspaceId(), copy.getCurrentBranch(), username);
    }

    private ArchitectureLifecycleService.Publication importDataset(ProjectDataImportController api, RepositoryContext target,
            String provider, int added, int existing) throws Exception {
        var head = editor.read(target, null).context();
        var intent = metadata("Import the source directory");
        String etag = "\"workspace-revision-" + head.revision() + "\"";
        var previous = RequestContextHolder.getRequestAttributes();
        try {
            bindRequest(target, "preview");
            var preview = api.preview(new ProjectDataImportController.Request(head, intent, provider, null), etag).getBody();
            assertThat(preview.added()).isEqualTo(added);
            assertThat(preview.existing()).isEqualTo(existing);
            bindRequest(target, "import");
            var result = api.apply(new ProjectDataImportController.Request(head, intent, provider, preview.datasetHash()), etag).getBody();
            assertThat(result.state()).isEqualTo(added == 0 ? "UNCHANGED" : "CHECKPOINTED");
            return result;
        } finally {
            RequestContextHolder.setRequestAttributes(previous);
        }
    }

    private static void bindRequest(RepositoryContext workspace, String operation) {
        var request = new MockHttpServletRequest("POST", "/api/architecture/editor/project-data/" + operation);
        request.addHeader(WorkspaceContextResolver.WORKSPACE_HEADER, workspace.workspaceId());
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
}
