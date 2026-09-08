package com.taxonomy.interop;

import com.taxonomy.dsl.storage.DslGitRepositoryFactory;
import com.taxonomy.editor.ArchitectureEditorService;
import com.taxonomy.editor.persistence.EditorJournal;
import com.taxonomy.exchange.ReqifExchangeCodec;
import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import com.taxonomy.interop.IntegrationService.*;
import com.taxonomy.interop.persistence.IntegrationStore.Operation;
import com.taxonomy.portfolio.dto.PortfolioDtos.*;
import com.taxonomy.portfolio.model.PortfolioTypes.*;
import com.taxonomy.portfolio.service.ProjectPortfolioService;
import com.taxonomy.workspace.model.RepositoryVisibility;
import com.taxonomy.workspace.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class IntegrationFlowTest {
    @Autowired IntegrationService integrations;
    @Autowired ProjectPortfolioService projects;
    @Autowired ArchitectureRepositoryProvisioningService repositories;
    @Autowired RepositoryWorkspaceService workspaces;
    @Autowired DslGitRepositoryFactory git;
    @Autowired ArchitectureEditorService editor;
    @Autowired EditorJournal journal;
    private RepositoryContext context;
    private Long project;
    private UUID connection;
    @BeforeEach void workspace() {
        String actor = "interop-" + UUID.randomUUID().toString().substring(0, 8);
        var repository = repositories.createRepository("Interoperability test", actor, "", RepositoryVisibility.PRIVATE, actor, "draft");
        var workspace = workspaces.createWorkingCopy(actor, repository.getRepositoryId(), "draft", "Exchange", "");
        context = RepositoryContext.workspace(repository.getRepositoryId(), workspace.getWorkspaceId(), workspace.getCurrentBranch(), actor);
        project = projects.createProject(new CreateProjectRequest("P-IMPORT", "Imported requirements", null, ProjectStatus.ACTIVE, null, null, null, null), actor, IntegrationDomainAdapter.workspace(context)).id();
        connection = integrations.create(context, new CreateConnection(UUID.randomUUID(), "ReqIF", ReqifExchangeCodec.PROFILE, AuthorityMode.BIDIRECTIONAL,
                new ExternalScope("Reference producer", "requirements-1", "baseline-a"), project, null)).id();
    }
    @Test void reviewedReqifImportIsAtomicIdempotentAndProducesOneExplicitCheckpoint() throws Exception {
        String beforeHead = git.resolveRepository(context).getHeadCommit(context.branch());
        byte[] payload = file("Original", "Original text");
        PreviewRequest request = request(); Operation preview = integrations.preview(context, connection, request, payload);
        assertNull(journal.read(context)); assertTrue(projects.listRequirements(project, context.username(), IntegrationDomainAdapter.workspace(context)).isEmpty());
        assertEquals(beforeHead, git.resolveRepository(context).getHeadCommit(context.branch()));
        var review = accept(preview); Operation applied = integrations.apply(context, connection, review);
        assertEquals(OperationStatus.COMPLETED, applied.status()); assertNotEquals(beforeHead, applied.resultCommit());
        assertEquals(1, journal.read(context).operations().size());
        var requirements = projects.listRequirements(project, context.username(), IntegrationDomainAdapter.workspace(context));
        assertEquals(1, requirements.size()); assertEquals("Original text", requirements.getFirst().currentVersion().text());
        assertTrue(git.resolveRepository(context).getDslAtHead(context.branch()).contains("Original text"));
        assertEquals(applied.id(), integrations.apply(context, connection, review).id());
        assertEquals(applied.id(), integrations.preview(context, connection, request, payload).id());
        assertEquals(1, journal.read(context).operations().size());
        Operation unchanged = integrations.preview(context, connection, request(), payload);
        integrations.apply(context, connection, accept(unchanged));
        assertEquals(applied.resultCommit(), git.resolveRepository(context).getHeadCommit(context.branch()));
        assertEquals(1, journal.read(context).operations().size());
        assertThrows(IntegrationProblem.class, () -> integrations.preview(context, connection, request, file("Reused ID", "Different")));
    }
    @Test void localAndExternalEditsConflictAndReviewedExportUsesFrozenVersionAfterLaterEdits() {
        integrations.apply(context, connection, accept(integrations.preview(context, connection, request(), file("Title", "Initial"))));
        var requirement = projects.listRequirements(project, context.username(), IntegrationDomainAdapter.workspace(context)).getFirst();
        projects.addRequirementVersion(project, requirement.id(), new CreateRequirementVersionRequest("Local text", "Local refinement", null), context.username(), IntegrationDomainAdapter.workspace(context));
        Operation conflict = integrations.preview(context, connection, request(), file("Title", "Remote text"));
        assertTrue(conflict.changes().stream().anyMatch(c -> c.kind() == ChangeKind.CONFLICT));
        assertThrows(IntegrationProblem.class, () -> integrations.apply(context, connection, accept(conflict)));
        Map<String, Decision> resolved = decisions(conflict); conflict.changes().stream().filter(c -> c.kind() == ChangeKind.CONFLICT).forEach(c -> resolved.put(c.id(), Decision.TAKE_EXTERNAL));
        integrations.apply(context, connection, new ReviewedChangeSet(conflict.id(), conflict.fingerprint(), resolved, "External clarification reviewed"));
        var overview = integrations.overview(context, connection);
        Operation export = integrations.previewExport(context, connection, new ExportRequest(UUID.randomUUID(), overview.current(), overview.checkpoint().externalVersion()));
        integrations.prepareFile(context, connection, accept(export)); byte[] frozen = integrations.file(context, connection, export.id()).content();
        projects.addRequirementVersion(project, requirement.id(), new CreateRequirementVersionRequest("After export", "Another edit", null), context.username(), IntegrationDomainAdapter.workspace(context));
        assertArrayEquals(frozen, integrations.file(context, connection, export.id()).content());
        assertEquals("Remote text", new ReqifExchangeCodec().read(frozen, null, true).artifacts().stream().filter(a -> a.kind() == ArtifactKind.REQUIREMENT).findFirst().orElseThrow().text());
        assertNotEquals(export.id(), integrations.overview(context, connection).checkpoint().operationId());
    }
    @Test void staleProjectFingerprintRejectsTheWholeImportAndForeignWorkspaceIsNotDisclosed() {
        Operation preview = integrations.preview(context, connection, request(), file("Title", "Text"));
        projects.createRequirement(project, new CreateRequirementRequest("LOCAL", "Local", "Local body", RequirementStatus.DRAFT, 50, Criticality.MEDIUM, RequirementType.FUNCTIONAL,
                ReviewStatus.PROPOSED, context.username(), "Created after preview", null), context.username(), IntegrationDomainAdapter.workspace(context));
        assertEquals("INTERNAL_STATE_CHANGED", assertThrows(IntegrationProblem.class, () -> integrations.apply(context, connection, accept(preview))).code());
        assertEquals(1, projects.listRequirements(project, context.username(), IntegrationDomainAdapter.workspace(context)).size());
        assertNull(journal.read(context));
        var intruder = RepositoryContext.workspace(context.repositoryId(), context.workspaceId(), context.branch(), "intruder");
        assertEquals(404, assertThrows(IntegrationProblem.class, () -> integrations.overview(intruder, connection)).status());
    }
    private PreviewRequest request() { return new PreviewRequest(UUID.randomUUID(), integrations.overview(context, connection).current(), "application/reqif+xml", true); }
    @Test void linkOnlyBindsAnAuthorizedExistingRequirementWithoutCopyingOrCommitting() throws Exception {
        var target = projects.createRequirement(project, new CreateRequirementRequest("LINK-TARGET", "Internal target", "Unchanged internal body", RequirementStatus.DRAFT,
                50, Criticality.MEDIUM, RequirementType.FUNCTIONAL, ReviewStatus.PROPOSED, context.username(), "Existing internal target", null), context.username(), IntegrationDomainAdapter.workspace(context));
        connection = integrations.create(context, new CreateConnection(UUID.randomUUID(), "Trace", ReqifExchangeCodec.PROFILE, AuthorityMode.LINK_ONLY,
                new ExternalScope("Reference tool", "linked-requirements", null), project, null)).id();
        String head = git.resolveRepository(context).getHeadCommit(context.branch());
        Operation preview = integrations.preview(context, connection, request(), file("External title", "External body"));
        String item = preview.changes().stream().filter(c -> c.after() != null && c.after().kind() == ArtifactKind.REQUIREMENT).findFirst().orElseThrow().id();
        var review = new ReviewedChangeSet(preview.id(), preview.fingerprint(), decisions(preview), "Link to the reviewed internal requirement",
                Map.of(item, new MappingOverride(null, null, null, "requirement:LINK-TARGET")));
        integrations.apply(context, connection, review);
        assertEquals(head, git.resolveRepository(context).getHeadCommit(context.branch())); assertTrue(journal.read(context).operations().isEmpty());
        var mapping = integrations.identities(context, connection).stream().filter(i -> i.externalId().equals(item)).findFirst().orElseThrow();
        assertEquals(target.id(), mapping.requirementId()); assertEquals("requirement:LINK-TARGET", mapping.businessIdentity());
        assertEquals("Unchanged internal body", projects.getRequirement(project, target.id(), context.username(), IntegrationDomainAdapter.workspace(context)).currentVersion().text());
        assertEquals(1, projects.listRequirements(project, context.username(), IntegrationDomainAdapter.workspace(context)).size());
        var again = integrations.preview(context, connection, request(), file("External title", "External body"));
        assertTrue(again.changes().stream().allMatch(c -> c.kind() == ChangeKind.UNCHANGED));
    }
    @Test void exportedLocalAdditionsAfterImportHaveStableIdentitiesAndOccurrences() {
        integrations.apply(context, connection, accept(integrations.preview(context, connection, request(), file("Imported", "Imported body"))));
        projects.createRequirement(project, new CreateRequirementRequest("LOCAL-ADDITION", "Local addition", "New canonical text", RequirementStatus.DRAFT, 50,
                Criticality.MEDIUM, RequirementType.FUNCTIONAL, ReviewStatus.PROPOSED, context.username(), "Added locally", null), context.username(), IntegrationDomainAdapter.workspace(context));
        var overview = integrations.overview(context, connection);
        Operation export = integrations.previewExport(context, connection, new ExportRequest(UUID.randomUUID(), overview.current(), overview.checkpoint().externalVersion()));
        integrations.prepareFile(context, connection, accept(export));
        var result = new ReqifExchangeCodec().read(integrations.file(context, connection, export.id()).content(), null, true);
        Artifact added = result.artifacts().stream().filter(a -> a.title().equals("Local addition")).findFirst().orElseThrow();
        assertEquals("New canonical text", added.text()); assertTrue(result.placements().stream().anyMatch(p -> p.artifactId().equals(added.id())));
        var next = integrations.overview(context, connection);
        Operation repeated = integrations.previewExport(context, connection, new ExportRequest(UUID.randomUUID(), next.current(), next.checkpoint().externalVersion()));
        assertTrue(repeated.document().artifacts().stream().anyMatch(a -> a.id().equals(added.id()) && a.title().equals(added.title())));
    }
    private static byte[] file(String title, String text) {
        return new ReqifExchangeCodec().write(new ExchangeDocument(ReqifExchangeCodec.PROFILE, "1", null, true, "",
                List.of(new Artifact("external-requirement-1", ArtifactKind.REQUIREMENT, "taxonomy-object", title, text, Map.of(), Map.of())), List.of(), List.of(), Map.of(), List.of()));
    }
    private static Map<String, Decision> decisions(Operation operation) { Map<String, Decision> choices = new HashMap<>(); operation.changes().forEach(c -> choices.put(c.id(), Decision.ACCEPT)); return choices; }
    private static ReviewedChangeSet accept(Operation operation) { return new ReviewedChangeSet(operation.id(), operation.fingerprint(), decisions(operation), "Reviewed independent tool exchange"); }
}
