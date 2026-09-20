package com.taxonomy.interop.publication;

import com.taxonomy.extension.api.integration.IntegrationContracts.Artifact;
import com.taxonomy.extension.api.integration.PublicationContracts.*;
import com.taxonomy.interop.IntegrationProblem;
import java.util.*;
import static com.taxonomy.extension.api.integration.PublicationBounds.*;

/** Side-effect-free guards. A failed guard means the dispatch outcome is UNKNOWN, never no-effect. */
public final class PublicationReceiptValidator {
    private final PublicationDigests digests;
    public PublicationReceiptValidator(PublicationDigests digests) { this.digests = Objects.requireNonNull(digests); }
    public void validate(PublicationItemRequest request, PublicationReceipt receipt) {
        if (receipt == null || request.schemaVersion() != receipt.schemaVersion() || !CONTRACT_VERSION.equals(receipt.contractVersion())
                || !request.provider().equals(receipt.provider()) || !request.scope().equals(receipt.scope()) || !request.operationId().equals(receipt.operationId())
                || !request.item().itemId().equals(receipt.itemId()) || !request.item().idempotencyKey().equals(receipt.idempotencyKey())
                || !request.planFingerprint().equals(receipt.planFingerprint()) || !request.requestFingerprint().equals(receipt.requestFingerprint())
                || !request.requestFingerprint().equals(digests.requestFingerprint(request)) || !request.item().intentFingerprint().equals(digests.intentFingerprint(request.item()))
                || !request.expectedScopeRevision().equals(receipt.beforeScopeRevision()) || !request.item().resourceId().equals(receipt.resultingResource().resourceId())) fail();
        if (receipt.terminal() != (receipt.state() != ReceiptState.RETRYABLE_NO_EFFECT)) fail();
        if (receipt.state() == ReceiptState.APPLIED) {
            if (receipt.failureCode() != null || receipt.beforeScopeRevision().equals(receipt.afterScopeRevision())) fail();
            ResourceState result = receipt.resultingResource();
            if (request.item().mutation() == MutationKind.DELETE) { if (result.exists() || result.version() != null || result.semanticFingerprint() != null) fail(); }
            else if (!result.exists() || !digests.semantic(request.item().target()).equals(result.semanticFingerprint())
                    || Objects.equals(request.item().expectedResource().version(), result.version())) fail();
        } else {
            // No-effect means the exact attempted scope/resource state remains unchanged. Stale may report a different current scope,
            // but the envelope's before/after tokens still describe this attempt's no-effect transition.
            if (receipt.failureCode() == null || !receipt.beforeScopeRevision().equals(receipt.afterScopeRevision())
                    || !request.item().expectedResource().equals(receipt.resultingResource())) fail();
        }
    }
    /** Must run before persisting/sending a dispatch, including retries with a longer predecessor token. */
    public void validateRequest(PublicationPlan plan, PublicationItemRequest request) {
        try { bounded(plan.capabilities().maxRequestBytes(), request); }
        catch (IllegalArgumentException tooLarge) { throw new IntegrationProblem("PUBLICATION_REQUEST_LIMIT", 422, "Publication request exceeds the verified provider limit"); }
        if (!plan.planFingerprint().equals(digests.planFingerprint(plan)) || !plan.operationId().equals(request.operationId())
                || !plan.planFingerprint().equals(request.planFingerprint()) || !plan.capabilities().provider().equals(request.provider())
                || !plan.capabilities().scope().equals(request.scope()) || !plan.items().contains(request.item())
                || !request.requestFingerprint().equals(digests.requestFingerprint(request))
                || !request.item().intentFingerprint().equals(digests.intentFingerprint(request.item()))) fail();
        if (plan.items().getFirst().equals(request.item()) && !plan.remoteBefore().revision().equals(request.expectedScopeRevision())) fail();
    }
    public void validateSnapshot(ScopeSnapshot snapshot) {
        if (!snapshot.complete() || !snapshot.document().completeScope() || !snapshot.semanticFingerprint().equals(digests.semantic(snapshot.document()))) fail();
        Map<String, Artifact> items = PublicationDigests.items(snapshot.document());
        Set<String> present = new TreeSet<>();
        for (Artifact item : items.values()) {
            present.add(item.id()); ResourceState state = snapshot.resources().get(item.id());
            if (state == null || !state.exists() || !digests.semantic(item).equals(state.semanticFingerprint())) fail();
        }
        for (ResourceState state : snapshot.resources().values()) if (state.exists() != present.contains(state.resourceId())) fail();
    }
    /** Verifies remote convergence evidence. The journal must separately recheck the exact local state and Git completion under lock. */
    public void validateCompletion(PublicationPlan plan, PublicationCompletion completion) {
        if (!plan.planFingerprint().equals(digests.planFingerprint(plan)) || !plan.operationId().equals(completion.operationId())
                || !plan.planFingerprint().equals(completion.planFingerprint()) || !digests.converged(plan)
                || !digests.semantic(plan.localTarget()).equals(completion.commonSemanticFingerprint())
                || !plan.capabilities().provider().equals(completion.remoteAfter().provider()) || !plan.capabilities().scope().equals(completion.remoteAfter().scope())) fail();
        var expectedLocal = plan.context().internalState(); var local = completion.localAfter();
        if (!expectedLocal.repositoryId().equals(local.repositoryId()) || !expectedLocal.workspaceScopeKey().equals(local.workspaceScopeKey())
                || !expectedLocal.branch().equals(local.branch()) || !Objects.equals(expectedLocal.projectId(), local.projectId())) fail();
        if (completion.receipts().size() != plan.items().size()) fail();
        Map<String, PublicationReceipt> receipts = new HashMap<>();
        for (PublicationReceipt receipt : completion.receipts()) if (receipts.putIfAbsent(receipt.itemId(), receipt) != null) fail();
        String revision = plan.remoteBefore().revision();
        for (PublicationItemIntent item : plan.items()) {
            PublicationReceipt receipt = receipts.get(item.itemId());
            var request = digests.request(plan.operationId(), plan.planFingerprint(), plan.capabilities().provider(), plan.capabilities().scope(), item, revision);
            validate(request, receipt);
            if (receipt.state() != ReceiptState.APPLIED) fail();
            ResourceState finalResource = completion.remoteAfter().resources().get(item.resourceId());
            if (item.mutation() == MutationKind.DELETE) {
                if (finalResource != null && finalResource.exists()) fail();
            } else if (!receipt.resultingResource().equals(finalResource)) fail();
            revision = receipt.afterScopeRevision();
        }
        validateSnapshot(completion.remoteAfter());
        if (!revision.equals(completion.remoteAfter().revision()) || !digests.semantic(plan.remoteTarget()).equals(completion.remoteAfter().semanticFingerprint())) fail();
    }
    private static void fail() { throw IntegrationProblem.conflict("PUBLICATION_RECEIPT_INVALID"); }
}
