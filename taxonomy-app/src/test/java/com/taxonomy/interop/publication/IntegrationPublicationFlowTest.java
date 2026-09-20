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

class IntegrationPublicationFlowTest extends PublicationIntegrationFixture {

    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void realHttpPushCreatesUpdatesAndDeletesWithCommonCheckpoint() throws Exception {
        edit(new CreateArchitectureElement("system-a", "System", Map.of("title", "First")));
        var first = publication.publish(context, connection, review(preview(PublicationMode.PUSH)));
        assertEquals(PublicationPhase.COMPLETED, first.phase());
        assertEquals(1, first.acknowledgedCount());
        assertNotNull(first.commonCheckpointId());
        assertNull(first.observationCheckpointId());
        String resource = first.items().getFirst().resourceId();
        assertEquals(1, provider.mutationCount(resource));
        assertEquals(first, publication.retryPublication(context, connection, first.operationId()));
        edit(new UpdateArchitectureElement("system-a", "System", Map.of("title", "Second")));
        var second = publication.publish(context, connection, review(preview(PublicationMode.PUSH)));
        assertEquals(PublicationPhase.COMPLETED, second.phase());
        assertEquals(MutationKind.UPDATE, second.items().getFirst().mutation());
        edit(new DeleteArchitectureElement("system-a"));
        var third = publication.publish(context, connection, review(preview(PublicationMode.PUSH)));
        assertEquals(PublicationPhase.COMPLETED, third.phase());
        assertEquals(MutationKind.DELETE, third.items().getFirst().mutation());
        assertEquals(3, provider.mutationCount(resource));
    }

    @Test
    void availabilityUsesActualScopedState() {
        assertTrue(publication.availability(context, connection, PublicationContractProvider.SCOPE).available());
    }

    @Test
    void cancelledPreviewCannotBePublished() {
        var draft = preview(PublicationMode.PUSH);
        store.locked(context, connection, session -> {
            session.cancel(draft.operationId(), "Cancelled review");
            return null;
        });
        assertEquals(PublicationPhase.CANCELLED, publication.publication(context, connection, draft.operationId()).phase());
        assertThrows(IntegrationProblem.class, () -> publication.publish(context, connection, review(draft)));
        assertEquals(0, provider.writes.get());
    }

    @Test
    void neutralNativePackagePushUsesOneExactIdentityForContainment() throws Exception {
        provider.seed(new ExchangeDocument(PublicationContractProvider.PROFILE, "1", null, true, "", List.of(), List.of(), List.of(), Map.of("identifier", PublicationContractProvider.SCOPE.rootResource()), List.of()));
        edit(new CreateArchitecturePackage("local-package", Map.of("title", "Local package")), new CreateArchitectureElement("local-element", "System", Map.of("title", "Local element")), new SetArchitecturePackagePlacements(List.of(new PackagePlacement(PackageMemberKind.ELEMENT, "local-element", "local-package", 0)), Set.of("local-package")));
        var operation = publication.publish(context, connection, review(preview(PublicationMode.PUSH)));
        assertEquals(PublicationPhase.COMPLETED, operation.phase(), operation.failureCode());
        var artifacts = PublicationDigests.items(provider.snapshot().document());
        var element = artifacts.values().stream().filter(a -> a.kind() == ArtifactKind.ELEMENT).findFirst().orElseThrow();
        var placement = artifacts.values().stream().filter(a -> a.kind() == ArtifactKind.PLACEMENT && element.id().equals(a.extensions().get("artifact"))).findFirst().orElseThrow();
        var parent = artifacts.values().stream().filter(a -> a.kind() == ArtifactKind.PLACEMENT && a.id().equals(placement.extensions().get("parent"))).findFirst().orElseThrow();
        assertTrue(artifacts.values().stream().anyMatch(a -> a.kind() == ArtifactKind.SPECIFICATION && a.id().equals(parent.extensions().get("artifact"))));
    }

    @Test
    void pushRefusesPortfolioValuesMissingFromItsExactGitCheckpoint() throws Exception {
        Long project = projects.createProject(new com.taxonomy.portfolio.dto.PortfolioDtos.CreateProjectRequest("PUB-UNSAVED", "Uncheckpointed portfolio", null, com.taxonomy.portfolio.model.PortfolioTypes.ProjectStatus.ACTIVE, null, null, null, null), context.username(), IntegrationDomainAdapter.workspace(context)).id();
        projects.createRequirement(project, new com.taxonomy.portfolio.dto.PortfolioDtos.CreateRequirementRequest("R1", "Local requirement", "Uncheckpointed content", null, null, null, null, null, null, "New requirement", null), context.username(), IntegrationDomainAdapter.workspace(context));
        connection = integrations.create(context, new IntegrationService.CreateConnection(UUID.randomUUID(), "Uncheckpointed HTTP", PublicationContractProvider.PROFILE, AuthorityMode.BIDIRECTIONAL, PublicationContractProvider.SCOPE.externalScope(), project, null)).id();
        var before = editor.read(context, null);
        var projected = local.domain.portfolioContribution(context).apply(before.dsl());
        assertNotEquals(before.dsl(), projected, "Current portfolio must actually differ from saved editor/Git source");
        assertEquals(before.dsl(), git.resolveRepository(context).getDslAtHead(context.branch()));
        var draft = preview(PublicationMode.PUSH);
        assertEquals("PUBLICATION_CHECKPOINT_REQUIRED", assertThrows(IntegrationProblem.class, () -> publication.publish(context, connection, review(draft))).code());
        assertEquals(0, provider.writes.get());
        assertNull(store.read(context, connection).commonCheckpointId());
    }

    @Test
    void providerCasAbsenceReplayAndRestartAreIndependentOfClientJournal() throws Exception {
        var d = provider.digests;
        var target = new Artifact("urn:test:a", ArtifactKind.ELEMENT, "ApplicationComponent", "A", "", Map.of(), Map.of("canonicalType", "System"));
        UUID id = UUID.randomUUID();
        var intent = d.intent(id, MutationKind.CREATE, target.id(), new ResourceState(target.id(), false, null, null), target, Set.of());
        var request = d.request(id, "plan", PublicationContractProvider.PROVIDER, PublicationContractProvider.SCOPE, intent, "scope-0");
        var receipt = connector.publishItem(null, request);
        assertEquals(ReceiptState.APPLIED, receipt.state());
        assertEquals(receipt, connector.publishItem(null, request));
        var altered = d.request(id, "changed-plan", request.provider(), request.scope(), intent, "scope-0");
        assertThrows(IllegalStateException.class, () -> connector.publishItem(null, altered));
        UUID second = UUID.randomUUID();
        var duplicate = d.request(second, "plan", request.provider(), request.scope(), d.intent(second, MutationKind.CREATE, target.id(), intent.expectedResource(), target, Set.of()), receipt.afterScopeRevision());
        assertEquals(ReceiptState.REJECTED_STALE, connector.publishItem(null, duplicate).state());
        provider.close();
        provider = new PublicationContractProvider(directory.resolve("provider.json"));
        connector.provider = provider;
        assertEquals(receipt, connector.publishItem(null, request));
        assertEquals(1, provider.mutationCount(target.id()));
    }

    @Test
    void verifiedContractAndExactProjectionRegistrationAreIndependent() {
        var c = store.read(context, connection);
        var unregistered = new IntegrationDomainAdapter(null, json);
        assertFalse(unregistered.supportsNativePackages(c));
        assertTrue(local.domain.supportsNativePackages(c));
        var otherVersion = new IntegrationStore.Connection(c.id(), c.organizationId(), c.displayName(), c.connectorId(), "2", c.authority(), c.externalScope(), null, null, c.revision(), null, null, c.createdBy());
        assertFalse(local.domain.supportsNativePackages(otherVersion));
        assertThrows(IllegalArgumentException.class, () -> new IntegrationDomainAdapter(null, json, List.of(new IntegrationDomainAdapter.ProjectionProfile(c.connectorId(), "1"), new IntegrationDomainAdapter.ProjectionProfile(c.connectorId(), "1"))));
        var denied = new IntegrationPublicationService(store, registry, local, json, new PublicationPolicy(), java.time.Clock.systemUTC());
        var request = new PublicationPreviewRequest(UUID.randomUUID(), state(), PublicationMode.PUSH, PublicationContractProvider.SCOPE, provider.snapshot().revision());
        assertEquals("PUBLICATION_GUARANTEES_UNVERIFIED", assertThrows(IntegrationProblem.class, () -> denied.previewPublication(context, connection, request)).code());
        assertEquals(0, provider.writes.get());
        assertTrue(store.history(context, connection).isEmpty());
    }

    @Test
    void legacyPcsAdapterCannotBeEnabledByCraftedAuthority() {
        var c = store.read(context, connection);
        UUID pcs = UUID.randomUUID();
        store.create(context, pcs, c.organizationId(), "PCS", com.taxonomy.exchange.sparx.SparxOslcAmCodec.PROFILE, "2", AuthorityMode.BIDIRECTIONAL, c.externalScope(), null, "fixture");
        var request = new PublicationPreviewRequest(UUID.randomUUID(), state(), PublicationMode.PUSH, PublicationContractProvider.SCOPE, provider.snapshot().revision());
        assertEquals("PUBLICATION_GUARANTEES_UNVERIFIED", assertThrows(IntegrationProblem.class, () -> publication.previewPublication(context, pcs, request)).code());
        assertEquals(0, provider.writes.get());
        assertTrue(store.history(context, pcs).isEmpty());
    }

    @Test
    void realNativeSyncAppliesPackagesAndRequirementMappingThenPublishesReviewedAuthority() throws Exception {
        Long project = projects.createProject(new com.taxonomy.portfolio.dto.PortfolioDtos.CreateProjectRequest("PUB-NATIVE", "Publication native", null, com.taxonomy.portfolio.model.PortfolioTypes.ProjectStatus.ACTIVE, null, null, null, null), context.username(), IntegrationDomainAdapter.workspace(context)).id();
        connection = integrations.create(context, new IntegrationService.CreateConnection(UUID.randomUUID(), "Native HTTP", PublicationContractProvider.PROFILE, AuthorityMode.BIDIRECTIONAL, PublicationContractProvider.SCOPE.externalScope(), project, null)).id();
        var artifacts = List.of(new Artifact("urn:package:p", ArtifactKind.SPECIFICATION, "Package", "Package", "", Map.of(), Map.of()), new Artifact("urn:element:e", ArtifactKind.ELEMENT, "ApplicationComponent", "Component", "", Map.of(), Map.of("canonicalType", "System")), new Artifact("urn:requirement:r", ArtifactKind.REQUIREMENT, "taxonomy-object", "Requirement", "Initial requirement", Map.of(), Map.of()));
        var relation = new Relation("urn:relation:m", "Dependency", "urn:requirement:r", "urn:element:e", Map.of(), Map.of("canonicalType", "DEPENDS_ON", "direction", "Source -> Destination"));
        var placements = List.of(new Placement("urn:placement:p", "urn:contract:scope", null, "urn:package:p", 0, Map.of()), new Placement("urn:placement:e", "urn:contract:scope", "urn:placement:p", "urn:element:e", 0, Map.of()));
        provider.seed(new ExchangeDocument(PublicationContractProvider.PROFILE, "1", "seed", true, "", artifacts, List.of(relation), placements, Map.of("identifier", "urn:contract:scope"), List.of()));
        assertEquals("urn:contract:scope", connector.readPublicationScope(null, PublicationContractProvider.SCOPE, null).document().metadata().get("identifier"));
        var preview = preview(PublicationMode.SYNCHRONIZE);
        var endpoints = publication.endpointOptions(context, connection, preview.operationId());
        var choices = new TreeMap<String, PublicationResolution>();
        preview.preview().changes().forEach(c -> choices.put(c.id(), PublicationResolution.TAKE_REMOTE));
        String requirementIdentity = endpoints.external().get("urn:requirement:r").businessIdentity(), elementIdentity = endpoints.external().get("urn:element:e").businessIdentity();
        var review = new PublicationReview(new ReviewedChangeSet(preview.operationId(), preview.preview().fingerprint(), Map.of(), "Reviewed native synchronization", Map.of(), Map.of("RELATION:urn:relation:m", new EndpointOverride(requirementIdentity, elementIdentity, RelationProjection.REQUIREMENT_MAPPING, null))), choices);
        var completed = publication.publish(context, connection, review);
        assertEquals(state(), completed.localCheckpoint(), "Native staged state must match live scoped portfolio/editor state");
        var actualNativeState = editor.read(context, null).state();
        boolean exactNativeCheckpoint = store.locked(context, connection, s -> {
            try {
                return editor.isExactCheckpoint(context, actualNativeState);
            } catch (java.io.IOException failure) {
                throw new java.io.UncheckedIOException(failure);
            }
        });
        assertTrue(exactNativeCheckpoint);
        assertEquals(PublicationPhase.COMPLETED, completed.phase(), completed.failureCode());
        var model = com.taxonomy.dsl.command.ArchitectureSemanticPatch.index(editor.read(context, null).dsl());
        assertEquals(1, model.values().stream().filter(b -> b.getKind().equals("package")).count());
        assertNotNull(model.get("mapping:" + requirementIdentity + " -> " + elementIdentity));
        var requirements = projects.listRequirements(project, context.username(), IntegrationDomainAdapter.workspace(context));
        assertEquals(1, requirements.size());
        assertEquals("Initial requirement", requirements.getFirst().currentVersion().text());
        assertEquals(1, journal.read(context).operations().size());
        assertEquals(1, provider.mutationCount("urn:relation:m"));
        long commits = git.resolveRepository(context).getCommitCount(context.branch());
        assertEquals(completed, publication.publish(context, connection, review));
        assertEquals(1, journal.read(context).operations().size());
        assertEquals(commits, git.resolveRepository(context).getCommitCount(context.branch()));
        edit(new UpdateArchitectureElement(elementIdentity, "System", Map.of("title", "Local component title")));
        var remote = provider.snapshot().document();
        var revised = remote.artifacts().stream().map(a -> a.id().equals("urn:requirement:r") ? new Artifact(a.id(), a.kind(), a.type(), a.title(), "Remote requirement revision", a.attributes(), a.extensions()) : a).toList();
        provider.seed(new ExchangeDocument(remote.profile(), remote.profileVersion(), remote.externalVersion(), true, "", revised, remote.relations(), remote.placements(), remote.metadata(), remote.losses()));
        var mergePreview = preview(PublicationMode.SYNCHRONIZE);
        var merges = new TreeMap<String, PublicationResolution>();
        mergePreview.preview().changes().forEach(change -> merges.put(change.id(), PublicationResolution.MERGE));
        var mergeReview = new PublicationReview(new ReviewedChangeSet(mergePreview.operationId(), mergePreview.preview().fingerprint(), Map.of(), "Merge independent native changes"), merges);
        var merged = publication.publish(context, connection, mergeReview);
        assertEquals(PublicationPhase.COMPLETED, merged.phase(), merged.failureCode());
        assertEquals("Remote requirement revision", projects.listRequirements(project, context.username(), IntegrationDomainAdapter.workspace(context)).getFirst().currentVersion().text());
        assertEquals("Local component title", provider.snapshot().document().artifacts().stream().filter(a -> a.id().equals("urn:element:e")).findFirst().orElseThrow().title());
        assertEquals(3, journal.read(context).operations().size());
        assertNotEquals(completed.commonCheckpointId(), merged.commonCheckpointId());
    }
}
