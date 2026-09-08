package com.taxonomy.interop;

import com.taxonomy.dsl.storage.DslGitRepositoryFactory;
import com.taxonomy.editor.ArchitectureEditorService;
import com.taxonomy.editor.persistence.EditorJournal;
import com.taxonomy.exchange.ReqifExchangeCodec;
import com.taxonomy.exchange.OslcRequirementsCodec;
import com.taxonomy.interop.oslc.OslcProviderService;
import com.taxonomy.interop.oslc.OslcRemoteProfiles;
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

@SpringBootTest(properties = "OSLC_REFERENCE_TOKEN=reference-fixture-token")
class IntegrationFlowTest {
    @Autowired IntegrationService integrations;
    @Autowired ProjectPortfolioService projects;
    @Autowired ArchitectureRepositoryProvisioningService repositories;
    @Autowired RepositoryWorkspaceService workspaces;
    @Autowired DslGitRepositoryFactory git;
    @Autowired ArchitectureEditorService editor;
    @Autowired EditorJournal journal;
    @Autowired OslcProviderService provider;
    @Autowired OslcRemoteProfiles remoteProfiles;
    @Autowired com.taxonomy.interop.persistence.IntegrationStore store;
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
        var binding = integrations.identities(context, connection).stream().filter(i -> i.externalId().equals("REQUIREMENT:" + added.id())).findFirst().orElseThrow();
        assertNull(binding.external(), "File delivery must not claim a confirmed external baseline");
        assertNotNull(binding.requirementId());
        Operation returned = integrations.preview(context, connection, request(), integrations.file(context, connection, export.id()).content());
        integrations.apply(context, connection, accept(returned));
        assertEquals(2, projects.listRequirements(project, context.username(), IntegrationDomainAdapter.workspace(context)).size(), "Returning a native export must not create a copy of the local requirement");
        assertEquals(binding.requirementId(), integrations.identities(context, connection).stream().filter(i -> i.externalId().equals(binding.externalId())).findFirst().orElseThrow().requirementId());
    }

    @Test void realOslcDiscoveryAndAuthorizedLinksKeepStableUrisAcrossProviderRename() throws Exception {
        var scope = IntegrationDomainAdapter.workspace(context);
        var published = projects.createRequirement(project, new CreateRequirementRequest("PUBLISHED", "Approved source", "Approved immutable text", RequirementStatus.APPROVED,
                50, Criticality.MEDIUM, RequirementType.FUNCTIONAL, ReviewStatus.PROPOSED, context.username(), "Approved fixture", null), context.username(), scope);
        var target = projects.createRequirement(project, new CreateRequirementRequest("TRACE-TARGET", "Internal target", "Internal body", RequirementStatus.DRAFT,
                50, Criticality.MEDIUM, RequirementType.FUNCTIONAL, ReviewStatus.PROPOSED, context.username(), "Link target", null), context.username(), scope);
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        var base = java.net.URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/rm/");
        OslcProviderService.Links links = path -> base.resolve(path.substring(1)).toString() + "?repositoryId=" + context.repositoryId();
        String uri = links.uri("/projects/" + project + "/requirements/" + published.id());
        var version = new java.util.concurrent.atomic.AtomicReference<>("\"v1\"");
        var requests = new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/rm/", exchange -> {
            requests.incrementAndGet();
            if (!"Bearer reference-fixture-token".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                exchange.sendResponseHeaders(401, -1); exchange.close(); return;
            }
            String expected = exchange.getRequestHeaders().getFirst("If-Match");
            if (expected != null && !expected.equals(version.get())) { exchange.sendResponseHeaders(412, -1); exchange.close(); return; }
            byte[] body = exchange.getRequestURI().getPath().endsWith("catalog") ? provider.catalog(context, links).xml()
                    : provider.requirement(context, project, published.id(), null, links).xml();
            exchange.getResponseHeaders().set("Content-Type", "application/rdf+xml"); exchange.getResponseHeaders().set("ETag", version.get());
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        var previousProfiles = remoteProfiles.getRemotes();
        remoteProfiles.setRemotes(Map.of("reference", new OslcRemoteProfiles.RemoteProfile(context.repositoryId(), "USER:" + context.username(), base,
                "OSLC_REFERENCE_TOKEN", true, true)));
        server.start();
        try {
            String head = git.resolveRepository(context).getHeadCommit(context.branch());
            connection = integrations.create(context, new CreateConnection(UUID.randomUUID(), "OSLC reference", OslcRequirementsCodec.PROFILE, AuthorityMode.LINK_ONLY,
                    new ExternalScope("Reference OSLC", base.toString(), null), project, "reference")).id();
            Operation discovery = integrations.previewRemote(context, connection, new RemoteRequest(UUID.randomUUID(), integrations.overview(context, connection).current(), base.resolve("catalog").toString(), null));
            assertTrue(discovery.document().metadata().get("discovery").contains("serviceProvider"));
            integrations.cancel(context, connection, discovery.id(), "Discovery inspected before choosing resource");
            var request = new RemoteRequest(UUID.randomUUID(), integrations.overview(context, connection).current(), uri, "\"v1\"");
            Operation preview = integrations.previewRemote(context, connection, request);
            String item = "REQUIREMENT:" + uri;
            integrations.apply(context, connection, new ReviewedChangeSet(preview.id(), preview.fingerprint(), decisions(preview), "Reviewed existing trace target",
                    Map.of(item, new MappingOverride(null, null, null, "requirement:TRACE-TARGET"))));
            int reads = requests.get(); integrations.previewRemote(context, connection, request); assertEquals(reads, requests.get(), "Idempotent retry does not fetch a different resource version");
            projects.updateRequirement(project, published.id(), new UpdateRequirementRequest("Renamed approved source", null, null, null, null, null, null), context.username(), scope);
            version.set("\"v2\"");
            Operation renamed = integrations.previewRemote(context, connection, new RemoteRequest(UUID.randomUUID(), integrations.overview(context, connection).current(), uri, "\"v2\""));
            integrations.apply(context, connection, accept(renamed));
            var mapping = integrations.identities(context, connection).stream().filter(i -> i.externalId().equals(item)).findFirst().orElseThrow();
            assertEquals(target.id(), mapping.requirementId()); assertEquals("Renamed approved source", mapping.external().title());
            assertEquals("Internal target", projects.getRequirement(project, target.id(), context.username(), scope).title());
            assertEquals(head, git.resolveRepository(context).getHeadCommit(context.branch())); assertTrue(journal.read(context).operations().isEmpty());
            var consumer = new OslcRequirementsCodec();
            assertFalse(consumer.discover(provider.service(context, project, links).xml(), base, null, null).resources().isEmpty());
            assertEquals(1, consumer.read(provider.query(context, project, 0, 20, links).xml(), base, null, null).artifacts().size(), "Unapproved target is not exposed");
            assertThrows(IntegrationProblem.class, () -> provider.requirement(context, project, target.id(), null, links));
            assertEquals(404, assertThrows(IntegrationProblem.class, () -> provider.authorize(RepositoryContext.workspace(context.repositoryId(), context.workspaceId(), context.branch(), "foreign"), EditorJournal.scope(context))).status());
        } finally { remoteProfiles.setRemotes(previousProfiles); server.stop(0); }
    }

    @Test void restartAfterGitRejectionReportsDurableConflictThroughRepositoryExceptionTranslation() throws Exception {
        connection = integrations.create(context, new CreateConnection(UUID.randomUUID(), "Checkpoint recovery", "archimate-3.1", AuthorityMode.BIDIRECTIONAL,
                new ExternalScope("Reference", "model", null), null, null)).id();
        UUID operation = UUID.randomUUID(), checkpoint = UUID.nameUUIDFromBytes((operation + ":checkpoint").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String rationale = "Reviewed before an independent Git writer moved the branch";
        var metadata = new com.taxonomy.editor.ArchitectureCommandPort.Metadata(operation.toString(), operation.toString(), operation.toString(), rationale);
        var checkpointMetadata = new com.taxonomy.editor.ArchitectureCommandPort.Metadata(checkpoint.toString(), operation.toString(), operation.toString(), rationale);
        var before = editor.read(context, null); var initial = integrations.overview(context, connection).current();
        var authority = new IntegrationContext(connection, AuthorityMode.BIDIRECTIONAL, new ExternalScope("Reference", "model", null), initial, context.username(), "archimate-3.1", "1");
        var payload = new ExchangeDocument("archimate-3.1", "1", "v1", true, "", List.of(), List.of(), List.of(), Map.of(), List.of());
        store.locked(context, connection, session -> {
            session.preview(operation, authority, "INBOUND", "frozen-preview", payload, List.of());
            session.beginReview(new ReviewedChangeSet(operation, "frozen-preview", Map.of(), rationale));
            try {
                var accepted = editor.acceptIntegration(context, before.context(), metadata, "review-fingerprint",
                        List.of(new com.taxonomy.dsl.command.ArchitectureCommand.CreateArchitectureElement("arch-durable-import", "System", Map.of("title", "Accepted import"))), null, checkpointMetadata);
                session.applied(operation, new InternalState(accepted.repositoryId(), accepted.workspaceScopeKey(), accepted.branch(), accepted.commit(), accepted.revision(), null, initial.projectFingerprint()), payload, true);
            } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
            return null;
        });
        String moved = git.resolveRepository(context).commitDsl(context.branch(), before.dsl() + "\nelement arch-independent type System { title: \"Independent version\"; }\n", context.username(), "Independent Git version");
        assertThrows(RuntimeException.class, () -> editor.resumeCheckpoint(context));
        assertEquals(OperationStatus.CHECKPOINT_PENDING, integrations.operation(context, connection, operation).status(), "Simulated crash before integration acknowledgement");
        assertEquals("CHECKPOINT_CONFLICT", assertThrows(IntegrationProblem.class, () -> integrations.retry(context, connection, operation)).code());
        assertEquals(OperationStatus.CONFLICT, integrations.operation(context, connection, operation).status());
        assertTrue(integrations.events(context, connection, operation).stream().anyMatch(e -> e.type().equals("MODEL_APPLIED_CHECKPOINT_CONFLICT")));
        assertTrue(journal.read(context).state().dsl().contains("arch-durable-import"));
        assertEquals(moved, git.resolveRepository(context).getHeadCommit(context.branch()));
    }
    private static byte[] file(String title, String text) {
        return new ReqifExchangeCodec().write(new ExchangeDocument(ReqifExchangeCodec.PROFILE, "1", null, true, "",
                List.of(new Artifact("external-requirement-1", ArtifactKind.REQUIREMENT, "taxonomy-object", title, text, Map.of(), Map.of())), List.of(), List.of(), Map.of(), List.of()));
    }
    private static Map<String, Decision> decisions(Operation operation) { Map<String, Decision> choices = new HashMap<>(); operation.changes().forEach(c -> choices.put(c.id(), Decision.ACCEPT)); return choices; }
    private static ReviewedChangeSet accept(Operation operation) { return new ReviewedChangeSet(operation.id(), operation.fingerprint(), decisions(operation), "Reviewed independent tool exchange"); }
}
