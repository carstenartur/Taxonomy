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
    @Autowired com.taxonomy.editor.ArchitectureEditorService nativeEditor;
    @Autowired ProjectPortfolioService projects;
    @Autowired IntegrationPortfolioPort portfolioPort;
    @Autowired com.taxonomy.portfolio.service.PortfolioGitService portfolioGit;
    @Autowired com.taxonomy.interop.oslc.OslcRemoteProfiles remoteProfiles;
    private RepositoryContext context;
    private UUID connection;
    private static final String A = "{11111111-1111-4111-8111-111111111111}";
    private static final String B = "{22222222-2222-4222-8222-222222222222}";
    private static final String P = "{33333333-3333-4333-8333-333333333333}";

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"1", "2"})
    void pcsReadRetriesDurablyAndRejectsRemoteChangesBeforeReviewedPull(String profileVersion) throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        var base = java.net.URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/model/oslc/am/");
        String resource = base + "resource/el_" + A.replace("{", "%7B").replace("}", "%7D") + "/";
        var title = new java.util.concurrent.atomic.AtomicReference<>("Flood observation reader");
        var unauthorized = new java.util.concurrent.atomic.AtomicBoolean(true);
        var tagValue = new java.util.concurrent.atomic.AtomicReference<>("first");
        var featureFailure = new java.util.concurrent.atomic.AtomicBoolean(false);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var oldProfiles = remoteProfiles.getRemotes();
        String credential = "SPARX_CONTRACT_TOKEN";
        String oldToken = System.getProperty(credential);
        try {
            System.setProperty(credential, B);
            var profiles = new HashMap<>(oldProfiles);
            profiles.put("pcs-contract", new com.taxonomy.interop.oslc.OslcRemoteProfiles.RemoteProfile(
                    context.repositoryId(), "USER:" + context.username(), base, credential, true, true));
            remoteProfiles.setRemotes(profiles);
            server.createContext(base.getPath(), exchange -> {
                calls.incrementAndGet();
                if (unauthorized.get()) { exchange.sendResponseHeaders(401, -1); exchange.close(); return; }
                var rdf = new com.taxonomy.exchange.OslcRdf();
                if (exchange.getRequestURI().getPath().endsWith("sp/")) {
                    rdf.link(base + "query", com.taxonomy.exchange.OslcRdf.OSLC + "resourceType", SparxOslcAmCodec.AM + "Resource")
                            .link(base + "query", com.taxonomy.exchange.OslcRdf.OSLC + "queryBase", base + "qc/");
                } else if (exchange.getRequestURI().getPath().endsWith("qc/")) {
                    rdf.type(resource, SparxOslcAmCodec.AM + "Resource")
                            .literal(resource, com.taxonomy.exchange.OslcRdf.DCT + "identifier", "el_" + A)
                            .literal(resource, com.taxonomy.exchange.OslcRdf.DCT + "type", "Component")
                            .literal(resource, com.taxonomy.exchange.OslcRdf.DCT + "title", title.get())
                            .literal(resource, com.taxonomy.exchange.OslcRdf.DCT + "description", "Published observations with source and time");
                }
                if (profileVersion.equals("2") && exchange.getRequestURI().getPath().contains("/taggedvalues/")) {
                    if (featureFailure.get()) { exchange.sendResponseHeaders(503, -1); exchange.close(); return; }
                    rdf.literal(base + "tag", com.taxonomy.exchange.OslcRdf.DCT + "identifier", "tv_{55555555-5555-4555-8555-555555555555}")
                            .literal(base + "tag", com.taxonomy.exchange.OslcRdf.DCT + "title", "owner")
                            .literal(base + "tag", SparxOslcAmCodec.SS + "value", tagValue.get());
                }
                byte[] body = rdf.xml(); exchange.getResponseHeaders().set("Content-Type", "application/rdf+xml");
                exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
            });
            UUID pcs = UUID.randomUUID();
            integrations.create(context, new CreateConnection(pcs, "PCS contract", SparxOslcAmCodec.PROFILE,
                    AuthorityMode.IMPORT_COPY, new ExternalScope("SPARX", base.toString(), null), null, "pcs-contract", profileVersion));
            UUID id = UUID.randomUUID();
            var request = new RemoteRequest(id, integrations.overview(context, pcs).current(), base + "sp/", null);
            assertEquals("REMOTE_UNAUTHORIZED", assertThrows(IntegrationProblem.class,
                    () -> integrations.previewRemote(context, pcs, request)).code());
            assertEquals(OperationStatus.FETCH_FAILED, integrations.operation(context, pcs, id).status());
            assertNull(integrations.overview(context, pcs).checkpoint()); assertTrue(integrations.identities(context, pcs).isEmpty());
            unauthorized.set(false);
            var preview = integrations.retry(context, pcs, id);
            assertEquals(OperationStatus.PREVIEWED, preview.status());
            assertFalse(preview.document().completeScope());
            var applied = integrations.apply(context, pcs, accept(preview));
            assertEquals(OperationStatus.COMPLETED, applied.status());
            assertEquals(1, journal.read(context).operations().size());
            int after = calls.get();
            assertEquals(applied.id(), integrations.apply(context, pcs, accept(preview)).id());
            assertEquals(after, calls.get(), "Replay must not refetch or mutate an already accepted operation");
            var next = integrations.previewRemote(context, pcs, new RemoteRequest(UUID.randomUUID(),
                    integrations.overview(context, pcs).current(), base + "sp/", null));
            if (profileVersion.equals("2")) tagValue.set("Changed while the reviewer was reading");
            else title.set("Changed while the reviewer was reading");
            assertEquals("REMOTE_STALE", assertThrows(IntegrationProblem.class, () -> integrations.apply(context, pcs, accept(next))).code());
            assertEquals(1, journal.read(context).operations().size());
            assertFalse(integrations.operation(context, pcs, id).toString().contains("useridentifier"));
            integrations.cancel(context, pcs, next.id(), "Remote state changed; request a fresh preview");
            assertEquals(profileVersion, integrations.overview(context, pcs).connection().profileVersion());
            if (profileVersion.equals("2")) {
                var state = integrations.overview(context, pcs).current();
                featureFailure.set(true); UUID failed = UUID.randomUUID();
                assertEquals("REMOTE_UNAVAILABLE", assertThrows(IntegrationProblem.class, () -> integrations.previewRemote(context, pcs,
                        new RemoteRequest(failed, state, base + "sp/", null))).code());
                assertEquals(OperationStatus.FETCH_FAILED, integrations.operation(context, pcs, failed).status());
                assertEquals(state, integrations.overview(context, pcs).current());
                assertEquals(1, journal.read(context).operations().size());
                assertFalse(integrations.operation(context, pcs, failed).toString().contains(B));
                assertFalse(integrations.events(context, pcs, failed).toString().contains(B));
            }
        } finally {
            server.stop(0); remoteProfiles.setRemotes(oldProfiles);
            if (oldToken == null) System.clearProperty(credential); else System.setProperty(credential, oldToken);
        }
    }

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

    @Test void unsupportedSparxRelationRemapHasActionableCodeWithoutMutation() {
        var preview = integrations.preview(context, connection, request(true), file("Mapped", false, false));
        var relation = preview.changes().stream().filter(c -> c.after() != null
                && c.after().kind() == ArtifactKind.RELATION).findFirst().orElseThrow();
        var accepted = accept(preview);
        var review = new ReviewedChangeSet(preview.id(), preview.fingerprint(), accepted.decisions(),
                "Reviewed relation remap", Map.of(relation.id(), new MappingOverride("SUPPORTS", null, null, null)));
        var before = integrations.overview(context, connection).current();
        var failure = assertThrows(IntegrationProblem.class, () -> integrations.apply(context, connection, review));
        assertEquals("SPARX_TYPE_MAPPING", failure.code());
        assertEquals(before, integrations.overview(context, connection).current());
        assertTrue(integrations.identities(context, connection).isEmpty());
        assertNull(journal.read(context));
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

    @Test void v2RequirementPlanIsAtomicAndContributesRealCanonicalMappingWithNativePackage() throws Exception {
        Long project = projects.createProject(new CreateProjectRequest("V2-PROJECT", "Requirements", null, ProjectStatus.ACTIVE, null, null, null, null),
                context.username(), IntegrationDomainAdapter.workspace(context)).id();
        connection = integrations.create(context, new CreateConnection(UUID.randomUUID(), "V2 native", SparxMappingProfile.PROFILE,
                AuthorityMode.BIDIRECTIONAL, new ExternalScope("SPARX", "v2-contract", null), project, null, "2")).id();
        var base = model("Native", false, true);
        String req = base.artifacts().stream().filter(a -> a.kind() == ArtifactKind.REQUIREMENT).findFirst().orElseThrow().id();
        String connector = "{66666666-6666-4666-8666-666666666666}";
        var relations = new ArrayList<>(base.relations());
        relations.add(new Relation(connector, "Dependency", req, A, Map.of(), Map.of("canonicalType", "DEPENDS_ON", "direction", "Source -> Destination")));
        var doc = new ExchangeDocument(base.profile(), "2", "v2", true, "", base.artifacts(), relations, base.placements(), base.metadata(), List.of());
        byte[] fixture = new SparxXmiCodec("2").write(doc);
        var preview = integrations.preview(context, connection, request(true), fixture);
        var before = integrations.overview(context, connection).current();
        assertEquals("SPARX_ENDPOINT_MAPPING_REQUIRED", assertThrows(IntegrationProblem.class,
                () -> integrations.apply(context, connection, accept(preview))).code());
        assertEquals(before, integrations.overview(context, connection).current());
        assertTrue(projects.listRequirements(project, context.username(), IntegrationDomainAdapter.workspace(context)).isEmpty());
        assertNull(journal.read(context));
        var endpoints = integrations.endpointOptions(context, connection, preview.id());
        assertTrue(journal.read(context).operations().isEmpty());
        assertNull(integrations.overview(context, connection).checkpoint());
        assertNull(integrations.operation(context, connection, preview.id()).reviewFingerprint());
        assertTrue(projects.listRequirements(project, context.username(), IntegrationDomainAdapter.workspace(context)).isEmpty());
        String requirementIdentity = endpoints.external().get(req).businessIdentity();
        String elementIdentity = endpoints.external().get(A).businessIdentity();
        String changeId = preview.changes().stream().filter(c -> c.externalId().equals("RELATION:" + connector)).findFirst().orElseThrow().id();
        var review = new ReviewedChangeSet(preview.id(), preview.fingerprint(), accept(preview).decisions(), "Reviewed requirement endpoint",
                Map.of(), Map.of(changeId, new EndpointOverride(requirementIdentity, elementIdentity, RelationProjection.REQUIREMENT_MAPPING, null)));
        integrations.apply(context, connection, review);
        var blocks = ArchitectureSemanticPatch.index(git.resolveRepository(context).getDslAtHead(context.branch()));
        assertNotNull(blocks.get("mapping:" + requirementIdentity + " -> " + elementIdentity));
        assertEquals("V2-PROJECT", blocks.get("requirement:" + requirementIdentity).property("x-project-key"));
        assertEquals(1, blocks.values().stream().filter(b -> b.getKind().equals("requirement")).count());
        assertEquals(1, blocks.values().stream().filter(b -> b.getKind().equals("package")).count());
        assertEquals(2, blocks.values().stream().filter(b -> b.getKind().equals("element")).count());
        for (int i = 0; i < 2; i++) {
            var repeat = integrations.preview(context, connection, request(true), fixture);
            integrations.apply(context, connection, accept(repeat));
        }
        assertEquals(1, journal.read(context).operations().size());
        var retargeted = new ArrayList<>(relations);
        retargeted.set(retargeted.size() - 1, new Relation(connector, "Dependency", req, B, Map.of(), Map.of("canonicalType", "DEPENDS_ON", "direction", "Source -> Destination")));
        var changedEnds = new ExchangeDocument(base.profile(), "2", "changed", true, "", base.artifacts(), retargeted, base.placements(), base.metadata(), List.of());
        var changedPreview = integrations.preview(context, connection, request(true), new SparxXmiCodec("2").write(changedEnds));
        assertEquals("SPARX_ENDPOINT_KIND_UNMAPPED", assertThrows(IntegrationProblem.class,
                () -> integrations.apply(context, connection, accept(changedPreview))).code());
        assertEquals(1, journal.read(context).operations().size());
        integrations.cancel(context, connection, changedPreview.id(), "Changed connector ends require fresh explicit projection review");
        String nativeCommand = UUID.randomUUID().toString();
        nativeEditor.execute(context, new com.taxonomy.editor.ArchitectureCommandPort.Command(nativeEditor.read(context, null).context(),
                new com.taxonomy.editor.ArchitectureCommandPort.Metadata(nativeCommand, nativeCommand, nativeCommand, "Reviewed native mapping"),
                new com.taxonomy.editor.ArchitectureCommandPort.SemanticCommand(new com.taxonomy.dsl.command.ArchitectureCommand.UpsertRequirementMapping(
                        requirementIdentity, endpoints.external().get(B).businessIdentity(), "Native mapping addition", Map.of()))));
        var overview = integrations.overview(context, connection);
        var outgoing = integrations.previewExport(context, connection, new ExportRequest(UUID.randomUUID(), overview.current(), overview.checkpoint().externalVersion()));
        integrations.prepareFile(context, connection, accept(outgoing));
        var returned = new SparxXmiCodec("2").read(integrations.file(context, connection, outgoing.id()).content(), "export", true);
        assertEquals(1, returned.artifacts().stream().filter(a -> a.kind() == ArtifactKind.SPECIFICATION).count());
        assertTrue(returned.relations().stream().anyMatch(r -> r.id().equals(connector)));
        assertEquals(2, returned.relations().stream().filter(r -> r.source().equals(req)).count());
        byte[] delivered = integrations.file(context, connection, outgoing.id()).content();
        var reimport = integrations.preview(context, connection, request(true), delivered);
        integrations.apply(context, connection, accept(reimport));
        assertEquals(2, new com.taxonomy.dsl.command.ArchitectureDslCommands().model(nativeEditor.read(context, null).dsl()).getMappings().size());
        UUID otherConnection = integrations.create(context, new CreateConnection(UUID.randomUUID(), "Another review authority", SparxMappingProfile.PROFILE,
                AuthorityMode.BIDIRECTIONAL, new ExternalScope("SPARX", "separate-v2-contract", null), project, null, "2")).id();
        var otherPreview = integrations.preview(context, otherConnection, new PreviewRequest(UUID.randomUUID(), integrations.overview(context, otherConnection).current(), "application/xmi+xml", true), delivered);
        int requirementsBefore = projects.listRequirements(project, context.username(), IntegrationDomainAdapter.workspace(context)).size();
        assertEquals("SPARX_ENDPOINT_MAPPING_REQUIRED", assertThrows(IntegrationProblem.class,
                () -> integrations.apply(context, otherConnection, accept(otherPreview))).code());
        assertEquals(requirementsBefore, projects.listRequirements(project, context.username(), IntegrationDomainAdapter.workspace(context)).size());
        assertTrue(integrations.identities(context, otherConnection).isEmpty());
    }

    @Test void exactRequirementPairsPreserveLegacyIdsAndRejectSanitizerCollisions() {
        var workspace = IntegrationDomainAdapter.workspace(context);
        Long first = projects.createProject(new CreateProjectRequest("A__B", "First", null, ProjectStatus.ACTIVE, null, null, null, null), context.username(), workspace).id();
        Long second = projects.createProject(new CreateProjectRequest("A", "Second", null, ProjectStatus.ACTIVE, null, null, null, null), context.username(), workspace).id();
        portfolioPort.createRequirement(first, new IntegrationPortfolioPort.ImportedRequirement("C", "First requirement", "Text", "reviewed", new IntegrationPortfolioPort.ImportProvenance("test", "Text")), context.username(), workspace);
        String contributed = portfolioGit.contributeTo("", context.username(), workspace);
        String legacy = contributed.replace("requirement A__B__C", "requirement LEGACY");
        assertEquals("LEGACY", portfolioPort.planRequirementApply(first, "C", legacy, context.username(), workspace).canonicalIdentity());
        assertTrue(portfolioGit.contributeTo(legacy, context.username(), workspace).contains("requirement LEGACY"));
        assertNotEquals(portfolioPort.planRequirementApply(first, "SHARED", "", context.username(), workspace).canonicalIdentity(),
                portfolioPort.planRequirementApply(second, "SHARED", "", context.username(), workspace).canonicalIdentity());
        assertEquals("REQUIREMENT_IDENTITY_MISMATCH", assertThrows(IntegrationProblem.class,
                () -> portfolioPort.planRequirementApply(second, "B__C", contributed, context.username(), workspace)).code());
        assertTrue(projects.listRequirements(second, context.username(), workspace).isEmpty());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = Decision.class, names = {"REJECT", "KEEP_INTERNAL"})
    void rejectedEndpointReviewsCannotDeleteRelationsOrMappingsBesideAcceptedEdits(Decision decision) throws Exception {
        Long project = projects.createProject(new CreateProjectRequest("SELECTION", "Selection", null, ProjectStatus.ACTIVE, null, null, null, null),
                context.username(), IntegrationDomainAdapter.workspace(context)).id();
        createV2(project);
        var fixture = v2Model("Before", true);
        var initial = integrations.preview(context, connection, request(true), new SparxXmiCodec("2").write(fixture));
        var ends = integrations.endpointOptions(context, connection, initial.id());
        String requirement = fixture.artifacts().stream().filter(a -> a.kind() == ArtifactKind.REQUIREMENT).findFirst().orElseThrow().id();
        String requirementConnector = fixture.relations().getLast().id();
        integrations.apply(context, connection, new ReviewedChangeSet(initial.id(), initial.fingerprint(), accept(initial).decisions(), "Approve mapping",
                Map.of(), Map.of(changeId(initial, requirementConnector), new EndpointOverride(ends.external().get(requirement).businessIdentity(),
                ends.external().get(A).businessIdentity(), RelationProjection.REQUIREMENT_MAPPING, null))));
        String relationKey = "relation:" + ends.external().get(A).businessIdentity() + " DEPENDS_ON " + ends.external().get(B).businessIdentity();
        String mappingKey = "mapping:" + ends.external().get(requirement).businessIdentity() + " -> " + ends.external().get(A).businessIdentity();
        var before = ArchitectureSemanticPatch.index(git.resolveRepository(context).getDslAtHead(context.branch()));
        assertNotNull(before.get(relationKey)); assertNotNull(before.get(mappingKey));
        var edit = integrations.preview(context, connection, request(true), new SparxXmiCodec("2").write(v2Model("Accepted unrelated rename", true)));
        var decisions = new TreeMap<>(accept(edit).decisions());
        Map<String, EndpointOverride> endpoints = new TreeMap<>();
        for (var relation : fixture.relations()) {
            String id = changeId(edit, relation.id());
            decisions.put(id, decision);
            endpoints.put(id, new EndpointOverride(null, null, RelationProjection.PRESERVE_ONLY, null));
        }
        integrations.apply(context, connection, new ReviewedChangeSet(edit.id(), edit.fingerprint(), decisions, "Keep connectors and accept rename", Map.of(), endpoints));
        var after = ArchitectureSemanticPatch.index(git.resolveRepository(context).getDslAtHead(context.branch()));
        assertNotNull(after.get(relationKey), "Rejected endpoint review must retain the architecture relation");
        assertNotNull(after.get(mappingKey), "Rejected endpoint review must retain the requirement mapping");
        assertEquals("Accepted unrelated rename", after.get("element:" + ends.external().get(A).businessIdentity()).property("title"));
        assertEquals(2, journal.read(context).operations().size());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void laterTypeReviewReplacesPriorEndpointTypeAndSurvivesExportReimport(boolean freshEndpointWithoutType) throws Exception {
        createV2(null);
        var initial = integrations.preview(context, connection, request(true), new SparxXmiCodec("2").write(v2Model("Before", false)));
        var ends = integrations.endpointOptions(context, connection, initial.id());
        String connector = initial.document().relations().getFirst().id();
        String source = ends.external().get(A).businessIdentity(), target = ends.external().get(B).businessIdentity();
        integrations.apply(context, connection, new ReviewedChangeSet(initial.id(), initial.fingerprint(), accept(initial).decisions(), "Approve related relation",
                Map.of(), Map.of(changeId(initial, connector), new EndpointOverride(source, target, RelationProjection.ARCHITECTURE_RELATION, "RELATED_TO"))));
        assertNotNull(ArchitectureSemanticPatch.index(git.resolveRepository(context).getDslAtHead(context.branch())).get("relation:" + source + " RELATED_TO " + target));
        var next = integrations.preview(context, connection, request(true), new SparxXmiCodec("2").write(v2Model("After", false)));
        String change = changeId(next, connector);
        Map<String, EndpointOverride> endpoints = freshEndpointWithoutType
                ? Map.of(change, new EndpointOverride(source, target, RelationProjection.ARCHITECTURE_RELATION, null)) : Map.of();
        integrations.apply(context, connection, new ReviewedChangeSet(next.id(), next.fingerprint(), accept(next).decisions(), "Review new dependency type",
                Map.of(change, new MappingOverride("DEPENDS_ON", null, null, null)), endpoints));
        assertEffectiveDependency(source, target, connector);
        var overview = integrations.overview(context, connection);
        var outgoing = integrations.previewExport(context, connection, new ExportRequest(UUID.randomUUID(), overview.current(), overview.checkpoint().externalVersion()));
        integrations.prepareFile(context, connection, accept(outgoing));
        byte[] delivered = integrations.file(context, connection, outgoing.id()).content();
        var returned = new SparxXmiCodec("2").read(delivered, "returned", true);
        assertEquals("DEPENDS_ON", returned.relations().stream().filter(r -> r.id().equals(connector)).findFirst().orElseThrow().extensions().get("canonicalType"));
        int operations = journal.read(context).operations().size();
        var reimport = integrations.preview(context, connection, request(true), delivered);
        integrations.apply(context, connection, accept(reimport));
        assertEffectiveDependency(source, target, connector);
        assertEquals(operations, journal.read(context).operations().size(), "Reimport must preserve the reviewed native type without another semantic operation");
    }

    @Test void incompatibleExplicitRelationTypesFailBeforeAnyWrite() {
        createV2(null);
        var preview = integrations.preview(context, connection, request(true), new SparxXmiCodec("2").write(v2Model("Before", false)));
        var ends = integrations.endpointOptions(context, connection, preview.id());
        var before = integrations.overview(context, connection);
        String change = changeId(preview, preview.document().relations().getFirst().id());
        assertEquals("SPARX_ENDPOINT_MAPPING_REQUIRED", assertThrows(IntegrationProblem.class, () -> integrations.apply(context, connection,
                new ReviewedChangeSet(preview.id(), preview.fingerprint(), accept(preview).decisions(), "Conflicting types",
                        Map.of(change, new MappingOverride("DEPENDS_ON", null, null, null)),
                        Map.of(change, new EndpointOverride(ends.external().get(A).businessIdentity(), ends.external().get(B).businessIdentity(),
                                RelationProjection.ARCHITECTURE_RELATION, "RELATED_TO"))))).code());
        assertEquals(before.current(), integrations.overview(context, connection).current());
        assertNull(integrations.overview(context, connection).checkpoint());
        assertTrue(integrations.identities(context, connection).isEmpty());
        assertTrue(journal.read(context).operations().isEmpty());
        assertNull(integrations.operation(context, connection, preview.id()).reviewFingerprint());
    }

    private void assertEffectiveDependency(String source, String target, String connector) throws Exception {
        var blocks = ArchitectureSemanticPatch.index(git.resolveRepository(context).getDslAtHead(context.branch()));
        assertNotNull(blocks.get("relation:" + source + " DEPENDS_ON " + target));
        assertNull(blocks.get("relation:" + source + " RELATED_TO " + target));
        var relation = integrations.identities(context, connection).stream().filter(i -> i.externalId().equals("RELATION:" + connector)).findFirst().orElseThrow();
        assertEquals("DEPENDS_ON", relation.internal().extensions().get("canonicalType"));
        assertEquals("DEPENDS_ON", relation.internal().extensions().get("nativeType"));
        assertEquals("DEPENDS_ON", relation.internal().attributes().get("tag:taxonomy.relationType"));
        assertEquals("DEPENDS_ON", relation.internal().attributes().get("tag:taxonomy.RelationType"));
    }
    private void createV2(Long project) {
        connection = integrations.create(context, new CreateConnection(UUID.randomUUID(), "V2 review", SparxMappingProfile.PROFILE,
                AuthorityMode.BIDIRECTIONAL, new ExternalScope("SPARX", "v2-review", null), project, null, "2")).id();
    }
    private static String changeId(Operation operation, String connector) {
        return operation.changes().stream().filter(c -> c.externalId().equals("RELATION:" + connector)).findFirst().orElseThrow().id();
    }
    private static ExchangeDocument v2Model(String title, boolean requirement) {
        var base = model(title, false, requirement);
        var relations = new ArrayList<>(base.relations());
        if (requirement) relations.add(new Relation("{66666666-6666-4666-8666-666666666666}", "Dependency",
                base.artifacts().stream().filter(a -> a.kind() == ArtifactKind.REQUIREMENT).findFirst().orElseThrow().id(), A,
                Map.of(), Map.of("canonicalType", "DEPENDS_ON", "direction", "Source -> Destination")));
        return new ExchangeDocument(base.profile(), "2", "v2", true, "", base.artifacts(), relations, base.placements(), base.metadata(), List.of());
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
