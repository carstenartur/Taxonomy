package com.taxonomy.interop;

import com.taxonomy.dsl.command.ArchitectureSemanticPatch;
import com.taxonomy.editor.persistence.EditorJournal;
import com.taxonomy.exchange.sparx.*;
import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import com.taxonomy.interop.IntegrationService.*;
import com.taxonomy.interop.persistence.IntegrationStore.Operation;
import com.taxonomy.portfolio.dto.PortfolioDtos.*;
import com.taxonomy.portfolio.model.PortfolioTypes.*;
import com.taxonomy.portfolio.service.ProjectPortfolioService;
import com.taxonomy.workspace.model.RepositoryVisibility;
import com.taxonomy.workspace.service.*;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class SparxIntegrationFlowTest {
    @Autowired IntegrationService integrations;
    @Autowired ArchitectureRepositoryProvisioningService repositories;
    @Autowired RepositoryWorkspaceService workspaces;
    @Autowired DslGitRepositoryFactory git;
    @Autowired EditorJournal journal;
    @Autowired ProjectPortfolioService projects;
    private RepositoryContext context;
    private UUID connection;
    private static final String A = "{11111111-1111-4111-8111-111111111111}";
    private static final String B = "{22222222-2222-4222-8222-222222222222}";
    private static final String P = "{33333333-3333-4333-8333-333333333333}";

    @BeforeEach void workspace() {
        String actor = "sparx-" + UUID.randomUUID().toString().substring(0, 8);
        var repo = repositories.createRepository("Sparx test", actor, "", RepositoryVisibility.PRIVATE, actor, "draft");
        var workspace = workspaces.createWorkingCopy(actor, repo.getRepositoryId(), "draft", "Sparx exchange", "");
        context = RepositoryContext.workspace(repo.getRepositoryId(), workspace.getWorkspaceId(), workspace.getCurrentBranch(), actor);
        connection = create(null);
    }

    @Test void reviewedImportIsDurableIdempotentAndFileDeliveryDoesNotAdvanceCheckpoint() throws Exception {
        var request = request(true);
        var preview = integrations.preview(context, connection, request, file("First", false, false));
        assertNull(journal.read(context));
        assertEquals(OperationStatus.PREVIEWED, integrations.operation(context, connection, preview.id()).status());
        var applied = integrations.apply(context, connection, accept(preview));
        assertEquals(OperationStatus.COMPLETED, applied.status());
        var identities = integrations.identities(context, connection);
        var element = identities.stream().filter(i -> i.externalId().equals("ELEMENT:" + A)).findFirst().orElseThrow();
        assertEquals("First", ArchitectureSemanticPatch.index(git.resolveRepository(context).getDslAtHead(context.branch()))
                .get("element:" + element.businessIdentity()).property("title"));
        assertEquals(1, journal.read(context).operations().size());
        assertEquals(applied.id(), integrations.preview(context, connection, request, file("First", false, false)).id());
        assertEquals(applied.id(), integrations.apply(context, connection, accept(preview)).id());
        var repeated = integrations.preview(context, connection, request(true), file("First", false, false));
        assertTrue(repeated.changes().stream().allMatch(c -> c.kind() == ChangeKind.UNCHANGED));
        integrations.apply(context, connection, accept(repeated));
        assertEquals(1, journal.read(context).operations().size());

        var before = integrations.overview(context, connection);
        var export = integrations.previewExport(context, connection, new ExportRequest(UUID.randomUUID(), before.current(), before.checkpoint().externalVersion()));
        integrations.prepareFile(context, connection, accept(export));
        assertEquals(before.checkpoint().id(), integrations.overview(context, connection).checkpoint().id());
        byte[] delivered = integrations.file(context, connection, export.id()).content();
        var returned = integrations.preview(context, connection, request(true), delivered);
        integrations.apply(context, connection, accept(returned));
        assertEquals(element.businessIdentity(), integrations.identities(context, connection).stream().filter(i -> i.externalId().equals("ELEMENT:" + A)).findFirst().orElseThrow().businessIdentity());
        assertEquals(2, ArchitectureSemanticPatch.index(git.resolveRepository(context).getDslAtHead(context.branch())).values().stream().filter(b -> b.getKind().equals("element")).count());
    }

    @Test void renameAndMoveKeepMappingsAndIncompleteListingCannotDelete() {
        integrations.apply(context, connection, accept(integrations.preview(context, connection, request(true), file("Before", false, false))));
        var identity = integrations.identities(context, connection).stream().filter(i -> i.externalId().equals("ELEMENT:" + A)).findFirst().orElseThrow().businessIdentity();
        var preview = integrations.preview(context, connection, request(true), file("Renamed", true, false));
        assertTrue(preview.changes().stream().anyMatch(c -> c.kind() == ChangeKind.MOVE));
        integrations.apply(context, connection, accept(preview));
        assertEquals(identity, integrations.identities(context, connection).stream().filter(i -> i.externalId().equals("ELEMENT:" + A)).findFirst().orElseThrow().businessIdentity());
        var doc = model("Renamed", true, false);
        var partial = new ExchangeDocument(doc.profile(), doc.profileVersion(), doc.externalVersion(), false, "",
                List.of(), List.of(), List.of(), doc.metadata(), List.of());
        var incomplete = integrations.preview(context, connection, request(false), new SparxXmiCodec().write(partial));
        assertFalse(incomplete.changes().stream().anyMatch(c -> c.kind() == ChangeKind.REMOVE_CANDIDATE));
    }

    @Test void requirementNeedsAnExplicitProjectAndCanBeRoundTrippedWithArchitecture() {
        var preview = integrations.preview(context, connection, request(true), file("Mixed", false, true));
        var failure = assertThrows(IntegrationProblem.class, () -> integrations.apply(context, connection, accept(preview)));
        assertEquals("REQUIREMENT_PROJECT_REQUIRED", failure.code());
        assertTrue(integrations.identities(context, connection).isEmpty());
        assertNull(journal.read(context));
        Long project = projects.createProject(new CreateProjectRequest("SPARX-REQ", "Sparx requirements", null, ProjectStatus.ACTIVE, null, null, null, null),
                context.username(), IntegrationDomainAdapter.workspace(context)).id();
        connection = create(project);
        integrations.apply(context, connection, accept(integrations.preview(context, connection, request(true), file("Mixed", false, true))));
        assertEquals(1, projects.listRequirements(project, context.username(), IntegrationDomainAdapter.workspace(context)).size());
        var overview = integrations.overview(context, connection);
        var export = integrations.previewExport(context, connection, new ExportRequest(UUID.randomUUID(), overview.current(), overview.checkpoint().externalVersion()));
        integrations.prepareFile(context, connection, accept(export));
        var returned = new SparxXmiCodec().read(integrations.file(context, connection, export.id()).content(), null, true);
        assertTrue(returned.artifacts().stream().anyMatch(a -> a.kind() == ArtifactKind.REQUIREMENT));
        assertTrue(returned.artifacts().stream().anyMatch(a -> a.kind() == ArtifactKind.ELEMENT));
    }

    @Test void previewIsScopedToWorkspaceAndRequiresTheExactInternalState() {
        var request = request(true);
        var stale = new InternalState(request.expected().repositoryId(), request.expected().workspaceScopeKey(), request.expected().branch(),
                request.expected().commitId(), request.expected().semanticRevision() + 1, null, request.expected().projectFingerprint());
        assertEquals("INTERNAL_STATE_CHANGED", assertThrows(IntegrationProblem.class, () -> integrations.preview(context, connection,
                new PreviewRequest(UUID.randomUUID(), stale, "application/xmi+xml", true), file("Name", false, false))).code());
        var foreign = RepositoryContext.workspace(context.repositoryId(), "different-workspace", context.branch(), context.username());
        assertThrows(IntegrationProblem.class, () -> integrations.overview(foreign, connection));
    }

    @Test void removingAnObjectRetainsItsUuidReservationAndRejectsAnotherGuidClaimingIt() {
        integrations.apply(context, connection, accept(integrations.preview(context, connection, request(true), file("Original", false, false))));
        String reserved = integrations.identities(context, connection).stream().filter(i -> i.externalId().equals("ELEMENT:" + A))
                .findFirst().orElseThrow().internal().extensions().get("internalIdentity");
        var template = model("Original", false, false);
        var empty = new ExchangeDocument(template.profile(), "1", null, true, "", List.of(), List.of(), List.of(), template.metadata(), List.of());
        integrations.apply(context, connection, accept(integrations.preview(context, connection, request(true), new SparxXmiCodec().write(empty))));
        assertTrue(integrations.identities(context, connection).stream().filter(i -> i.externalId().equals("ELEMENT:" + A))
                .findFirst().orElseThrow().removed());

        String newcomer = "{77777777-7777-4777-8777-777777777777}";
        var artifact = new Artifact(newcomer, ArtifactKind.ELEMENT, "Component", "Reclaimed", "",
                Map.of("tag:taxonomy.id", reserved), Map.of("canonicalType", "Component"));
        var claimed = new ExchangeDocument(template.profile(), "1", null, true, "", List.of(artifact), List.of(),
                List.of(new Placement("placement:" + newcomer, template.metadata().get("identifier"), null, newcomer, 0, Map.of())), template.metadata(), List.of());
        var before = integrations.overview(context, connection).current();
        var preview = integrations.preview(context, connection, request(true), new SparxXmiCodec().write(claimed));
        assertTrue(preview.changes().stream().anyMatch(c -> c.externalId().equals("ELEMENT:" + newcomer)
                && c.kind() == ChangeKind.CONFLICT && c.conflicts().contains("INTERNAL_IDENTITY_REUSED")));
        Map<String, Decision> decisions = new TreeMap<>(); preview.changes().forEach(c -> decisions.put(c.id(), Decision.TAKE_EXTERNAL));
        assertEquals("IDENTITY_REMAP_REQUIRED", assertThrows(IntegrationProblem.class, () -> integrations.apply(context, connection,
                new ReviewedChangeSet(preview.id(), preview.fingerprint(), decisions, "Cannot override identity ownership"))).code());
        assertEquals(before, integrations.overview(context, connection).current());
    }

    private UUID create(Long project) {
        return integrations.create(context, new CreateConnection(UUID.randomUUID(), "Sparx", SparxMappingProfile.PROFILE,
                AuthorityMode.BIDIRECTIONAL, new ExternalScope("SPARX", "fixture-model", null), project, null)).id();
    }
    private PreviewRequest request(boolean complete) { return new PreviewRequest(UUID.randomUUID(), integrations.overview(context, connection).current(), "application/xmi+xml", complete); }
    private static ReviewedChangeSet accept(Operation operation) {
        Map<String, Decision> decisions = new TreeMap<>(); operation.changes().forEach(c -> decisions.put(c.id(), Decision.ACCEPT));
        return new ReviewedChangeSet(operation.id(), operation.fingerprint(), decisions, "Reviewed Sparx semantic exchange");
    }
    private static byte[] file(String title, boolean moved, boolean requirement) { return new SparxXmiCodec().write(model(title, moved, requirement)); }
    private static ExchangeDocument model(String title, boolean moved, boolean requirement) {
        String root = "{00000000-0000-4000-8000-000000000000}";
        List<Artifact> artifacts = new ArrayList<>(List.of(new Artifact(P, ArtifactKind.SPECIFICATION, "Package", "Package", "", Map.of(), Map.of()),
                new Artifact(A, ArtifactKind.ELEMENT, "Component", title, "Body", Map.of(), Map.of("canonicalType", "Component")),
                new Artifact(B, ArtifactKind.ELEMENT, "Component", "Second", "", Map.of(), Map.of("canonicalType", "Component"))));
        List<Placement> placements = new ArrayList<>(List.of(new Placement("placement:" + P, root, null, P, 0, Map.of()),
                new Placement("placement:" + A, root, moved ? null : "placement:" + P, A, 1, Map.of()),
                new Placement("placement:" + B, root, "placement:" + P, B, 2, Map.of())));
        if (requirement) {
            String id = "{55555555-5555-4555-8555-555555555555}";
            artifacts.add(new Artifact(id, ArtifactKind.REQUIREMENT, "Class", "Requirement", "The model shall retain identity.", Map.of(), Map.of()));
            placements.add(new Placement("placement:" + id, root, "placement:" + P, id, 3, Map.of()));
        }
        return new ExchangeDocument(SparxMappingProfile.PROFILE, "1", "v1", true, "", artifacts,
                List.of(new Relation("{44444444-4444-4444-8444-444444444444}", "Dependency", A, B, Map.of(), Map.of("canonicalType", "DEPENDS_ON", "direction", "Source -> Destination"))),
                placements, Map.of("identifier", root, "title", "Fixture"), List.of());
    }
}
