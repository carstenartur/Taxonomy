package com.taxonomy.interop.publication;

import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import com.taxonomy.extension.api.integration.PublicationContracts.*;
import com.taxonomy.interop.IntegrationProblem;
import com.taxonomy.interop.publication.PublicationEvidence.PublicationPreviewEnvelope;
import java.time.Instant;
import java.util.*;
import static com.taxonomy.extension.api.integration.PublicationBounds.*;

/** Reserves the bounded public evidence envelope before the journal accepts any effects. */
public final class PublicationProjectionBudget {
    private PublicationProjectionBudget() {}
    public static void require(PublicationPreviewEnvelope preview, PublicationReview review, PublicationPlan plan) {
        try {
            // Quotes are legal opaque token characters and require two JSON bytes each.
            // Identities/provider/scope are already frozen; only genuinely variable token fields
            // reserve their contract maximum. Receipts occur in outcomes and completion.
            String token = "\"".repeat(2048), digest = "f".repeat(64), code = "X".repeat(100);
            var resources = new TreeMap<String, ResourceState>();
            plan.remoteBefore().resources().forEach((id, state) -> resources.put(id, state));
            PublicationDigests.items(plan.remoteTarget()).values().forEach(a -> resources.put(a.id(), new ResourceState(a.id(), true, token, digest)));
            var receipts = new ArrayList<PublicationReceipt>(); var outcomes = new ArrayList<PublicationItemOutcome>();
            for (var item : plan.items()) {
                var resource = item.mutation() == MutationKind.DELETE ? new ResourceState(item.resourceId(), false, null, null) : resources.get(item.resourceId());
                resources.put(item.resourceId(), resource);
                var receipt = new PublicationReceipt(1, plan.capabilities().contractVersion(), plan.capabilities().provider(), plan.capabilities().scope(), plan.operationId(), item.itemId(), item.idempotencyKey(), plan.planFingerprint(), digest, ReceiptState.APPLIED, Long.MAX_VALUE, true, token, token, resource, code, Instant.MAX);
                receipts.add(receipt); outcomes.add(new PublicationItemOutcome(item.itemId(), item.resourceId(), item.mutation(), ItemState.ACKNOWLEDGED, MAX_ATTEMPTS, code, receipt));
            }
            var after = new ScopeSnapshot(1, plan.capabilities().provider(), plan.capabilities().scope(), token, digest, true, plan.remoteTarget(), resources);
            var completion = new PublicationCompletion(1, plan.operationId(), plan.planFingerprint(), receipts, after, plan.context().internalState(), digest);
            var maximum = new PublicationOperation(1, plan.operationId(), plan.context().connectionId(), plan.operationId(), plan.mode(), PublicationPhase.LOCAL_CHECKPOINT_PENDING, OperationStatus.COMPLETED, plan.planFingerprint(), plan.reviewFingerprint(), preview.publicPreview(), outcomes, completion, plan.operationId(), plan.operationId(), plan.context().internalState(), outcomes.size(), 0, 0, Set.of(PublicationAction.values()), code, review, preview.request().scope(), preview.request().expectedExternalRevision(), plan.requestFingerprint());
            // Room for the resulting local checkpoint token and fixed scalar state changes.
            bounded(MAX_DOCUMENT_BYTES - 8192, maximum);
        } catch (IllegalArgumentException tooLarge) {
            throw new IntegrationProblem("PUBLICATION_PROJECTION_LIMIT", 422, "The complete publication evidence exceeds the bounded response budget");
        }
    }
}
