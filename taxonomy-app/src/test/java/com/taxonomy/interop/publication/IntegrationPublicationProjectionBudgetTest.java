package com.taxonomy.interop.publication;

import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import com.taxonomy.extension.api.integration.PublicationContracts.*;
import com.taxonomy.interop.IntegrationProblem;
import com.taxonomy.interop.publication.PublicationEvidence.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static com.taxonomy.extension.api.integration.PublicationBounds.*;
import static org.junit.jupiter.api.Assertions.*;

/** Journal contract boundary: bounded planner inputs, real database reservation, no mocked domain success. */
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
class IntegrationPublicationProjectionBudgetTest extends PublicationIntegrationFixture {
    @Test void aggregateReadProjectionMustFitBeforeTheJournalAcceptsEffects() throws Exception {
        edit(new com.taxonomy.dsl.command.ArchitectureCommand.CreateArchitectureElement("budget-anchor", "System", Map.of("title", "Real local anchor")));
        var expected = state();
        var c = store.read(context, connection);
        var authority = new IntegrationContext(connection, c.authority(), c.externalScope(), expected, context.username(), c.connectorId(), c.profileVersion());
        var artifacts = java.util.stream.IntStream.range(0, 900).mapToObj(i -> new Artifact("urn:budget:" + i, ArtifactKind.ELEMENT, "ApplicationComponent", "T".repeat(1300), "", Map.of(), Map.of("canonicalType", "System"))).toList();
        var localDocument = new ExchangeDocument(c.connectorId(), c.profileVersion(), null, true, "", artifacts, List.of(), List.of(), Map.of(), List.of());
        var remote = provider.snapshot();
        var request = new PublicationPreviewRequest(UUID.randomUUID(), expected, PublicationMode.PUSH, PublicationContractProvider.SCOPE, remote.revision());
        var digests = new PublicationDigests(json);
        var unsigned = new PublicationPreviewEnvelope(1, request, authority, c.revision(), null, null, localDocument, remote, new PublicationDiff().compare(null, localDocument, remote), List.of(), "unsigned");
        var preview = new PublicationPreviewEnvelope(1, request, authority, c.revision(), null, null, localDocument, remote, unsigned.changes(), List.of(), digests.previewFingerprint(unsigned));
        var resolutions = new TreeMap<String, PublicationResolution>(); preview.changes().forEach(change -> resolutions.put(change.id(), PublicationResolution.KEEP_LOCAL));
        var review = new PublicationReview(new ReviewedChangeSet(request.operationId(), preview.previewFingerprint(), Map.of(), "Projection budget boundary"), resolutions);
        var plan = new PublicationPlanner(digests, policy, PublicationContractProvider.CAPS).plan(preview, review);
        // Independently demonstrate a legal receipt combination exceeding the aggregate projection,
        // rather than relying on an arbitrary item-count threshold in the new guard.
        String version = "v".repeat(2048);
        var receipts = new ArrayList<PublicationReceipt>(); var outcomes = new ArrayList<PublicationItemOutcome>();
        var resources = new TreeMap<String, ResourceState>();
        for (var item : plan.items()) {
            var resource = new ResourceState(item.resourceId(), true, version, digests.semantic(item.target())); resources.put(item.resourceId(), resource);
            var receipt = new PublicationReceipt(1, CONTRACT_VERSION, plan.capabilities().provider(), plan.capabilities().scope(), plan.operationId(), item.itemId(), item.idempotencyKey(), plan.planFingerprint(), "f".repeat(64), ReceiptState.APPLIED, 1, true, version, "w".repeat(2048), resource, null, Instant.EPOCH);
            receipts.add(receipt); outcomes.add(new PublicationItemOutcome(item.itemId(), item.resourceId(), item.mutation(), ItemState.ACKNOWLEDGED, 1, null, receipt));
        }
        var after = new ScopeSnapshot(1, remote.provider(), remote.scope(), version, digests.semantic(plan.remoteTarget()), true, plan.remoteTarget(), resources);
        var completion = new PublicationCompletion(1, plan.operationId(), plan.planFingerprint(), receipts, after, expected, digests.semantic(plan.remoteTarget()));
        assertThrows(IllegalArgumentException.class, () -> new PublicationOperation(1, plan.operationId(), connection, null, PublicationMode.PUSH, PublicationPhase.COMPLETED, OperationStatus.COMPLETED, plan.planFingerprint(), plan.reviewFingerprint(), preview.publicPreview(), outcomes, completion, null, UUID.randomUUID(), expected, outcomes.size(), 0, 0, Set.of(), null, review, request.scope(), request.expectedExternalRevision(), plan.requestFingerprint()));
        store.locked(context, connection, s -> { s.publications().initialize(request, authority, null); s.publications().publicationPreview(request.operationId(), preview); return null; });
        int semantic = journal.read(context).operations().size(); long commits = git.resolveRepository(context).getCommitCount(context.branch());
        var error = assertThrows(IntegrationProblem.class, () -> store.locked(context, connection, s -> { s.publications().beginPublication(review, plan); return null; }));
        assertEquals("PUBLICATION_PROJECTION_LIMIT", error.code());
        assertEquals(0, provider.writes.get()); assertEquals(semantic, journal.read(context).operations().size());
        assertEquals(commits, git.resolveRepository(context).getCommitCount(context.branch()));
        assertNull(store.read(context, connection).activeOperationId()); assertNull(store.read(context, connection).commonCheckpointId());
    }
}
