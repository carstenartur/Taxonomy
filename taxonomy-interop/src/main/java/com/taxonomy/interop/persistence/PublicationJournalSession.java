package com.taxonomy.interop.persistence;

import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import com.taxonomy.extension.api.integration.PublicationContracts.*;
import com.taxonomy.interop.*;
import com.taxonomy.interop.publication.*;
import com.taxonomy.interop.publication.PublicationEvidence.*;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.*;
import static com.taxonomy.extension.api.integration.PublicationBounds.*;

/**
 * Short durable transitions under IntegrationStore's connection lock. No transport or Git writes here.
 */
public final class PublicationJournalSession {

    private final IntegrationStore.Session session;

    private final EntityManager em;

    private final IntegrationJson json;

    private final PublicationDigests digests;

    private final PublicationReceiptValidator validator;

    private final PublicationLocalAuthority local;

    PublicationJournalSession(IntegrationStore.Session session, EntityManager em, IntegrationJson json, PublicationLocalAuthority local) {
        this.session = session;
        this.em = em;
        this.json = json;
        this.local = local;
        digests = new PublicationDigests(json);
        validator = new PublicationReceiptValidator(digests);
    }

    public PublicationOperation find(UUID id) {
        return em.find(IntegrationPublicationEntity.class, id.toString()) == null ? null : publication(id);
    }

    public PublicationPreviewRequest request(UUID id) {
        return json.read(require(id).requestJson, PublicationPreviewRequest.class);
    }

    public PublicationPreviewEnvelope preview(UUID id) {
        return json.read(require(id).previewJson, PublicationPreviewEnvelope.class);
    }

    public PublicationPlan plan(UUID id) {
        return json.read(require(id).planJson, PublicationPlan.class);
    }

    public PublicationReview review(UUID id) {
        return json.read(require(id).reviewJson, PublicationReview.class);
    }

    public InternalState localState(UUID id) {
        return json.read(require(id).localStateJson, InternalState.class);
    }

    public CommonBaseline commonBaseline() {
        String id = session.connection.commonCheckpointId;
        if (id == null) {
            return null;
        }
        var c = em.find(IntegrationCheckpointEntity.class, id);
        if (c == null || !c.scopeId.equals(session.connection.scopeId) || !c.connectionId.equals(session.connection.id) || !"COMMON".equals(c.kind) || c.publicationCompletionJson == null) {
            throw IntegrationProblem.missing();
        }
        var publicationEntity = require(UUID.fromString(c.operationId));
        if (!PublicationPhase.COMPLETED.name().equals(publicationEntity.phase) || !Objects.equals(publicationEntity.completionJson, c.publicationCompletionJson)) {
            throw IntegrationProblem.conflict("PUBLICATION_COMMON_INVALID");
        }
        var baseline = json.read(c.baselineJson, CommonBaseline.class);
        if (!baseline.semanticFingerprint().equals(digests.semantic(baseline.localDocument())) || !baseline.semanticFingerprint().equals(digests.semantic(baseline.remoteDocument()))) {
            throw IntegrationProblem.conflict("PUBLICATION_COMMON_INVALID");
        }
        return baseline;
    }

    public PublicationOperation initialize(PublicationPreviewRequest request, IntegrationContext authority, UUID predecessor) {
        var prior = find(request.operationId());
        if (prior != null) {
            if (!request(request.operationId()).equals(request)) {
                throw IntegrationProblem.conflict("OPERATION_ID_REUSED");
            }
            return prior;
        }
        if (predecessor != null) {
            requireResolved(require(predecessor));
        }
        if (session.connection.activeOperationId != null && !Objects.equals(session.connection.activeOperationId, predecessor == null ? null : predecessor.toString())) {
            throw IntegrationProblem.conflict("INTEGRATION_PENDING");
        }
        // preview() normally refuses a reservation; linked previews do not release the predecessor yet.
        String active = session.connection.activeOperationId;
        session.connection.activeOperationId = null;
        var empty = new ExchangeDocument(authority.profile(), authority.profileVersion(), null, true, "", List.of(), List.of(), List.of(), Map.of(), List.of());
        session.preview(request.operationId(), authority, request.mode().name(), digests.fingerprint(request), empty, List.of());
        session.connection.activeOperationId = active;
        session.fetching(request.operationId());
        var publicationEntity = new IntegrationPublicationEntity();
        publicationEntity.id = request.operationId().toString();
        publicationEntity.scopeId = session.connection.scopeId;
        publicationEntity.connectionId = session.connection.id;
        publicationEntity.schemaVersion = 1;
        publicationEntity.requestJson = json.write(request);
        publicationEntity.configFingerprint = config();
        publicationEntity.phase = PublicationPhase.PREVIEWED.name();
        publicationEntity.predecessorId = predecessor == null ? null : predecessor.toString();
        publicationEntity.commonCheckpointId = session.connection.commonCheckpointId;
        publicationEntity.reservedRevision = session.connection.revision;
        publicationEntity.createdAt = Instant.now().toString();
        publicationEntity.updatedAt = publicationEntity.createdAt;
        em.persist(publicationEntity);
        return publication(request.operationId());
    }

    public PublicationOperation publicationPreview(UUID id, PublicationPreviewEnvelope envelope) {
        var publicationEntity = require(id);
        requireConfigured(publicationEntity);
        if (publicationEntity.previewJson != null) {
            if (!json.read(publicationEntity.previewJson, PublicationPreviewEnvelope.class).equals(envelope)) {
                throw IntegrationProblem.conflict("PREVIEW_CHANGED");
            }
            return publication(id);
        }
        if (!request(id).equals(envelope.request()) || !session.operation(id).context().equals(envelope.context()) || envelope.connectionRevision() != session.connection.revision || !Objects.equals(envelope.commonCheckpointId(), uuid(session.connection.commonCheckpointId)) || !envelope.previewFingerprint().equals(digests.previewFingerprint(envelope))) {
            throw IntegrationProblem.conflict("PUBLICATION_PREVIEW_CHANGED");
        }
        validator.validateSnapshot(envelope.remoteSnapshot());
        publicationEntity.previewJson = json.write(envelope);
        session.fetched(id, envelope.remoteSnapshot().document(), List.of());
        return publication(id);
    }

    public void beginPublication(PublicationReview review, PublicationPlan plan) {
        var publicationEntity = require(plan.operationId());
        requireConfigured(publicationEntity);
        var operation = session.requireOperation(plan.operationId());
        if (publicationEntity.planJson != null) {
            if (!json.read(publicationEntity.reviewJson, PublicationReview.class).equals(review)) {
                throw IntegrationProblem.conflict("REVIEW_ID_REUSED");
            }
            return;
        }
        if (!publicationEntity.phase.equals(PublicationPhase.PREVIEWED.name())) {
            throw IntegrationProblem.conflict("PUBLICATION_PHASE");
        }
        var preview = preview(plan.operationId());
        if (preview == null || !plan.context().equals(preview.context()) || !plan.planFingerprint().equals(digests.planFingerprint(plan)) || !plan.reviewFingerprint().equals(digests.fingerprint(review)) || !review.review().previewFingerprint().equals(preview.previewFingerprint()) || !plan.requestFingerprint().equals(digests.fingerprint(request(plan.operationId()))) || session.connection.revision != plan.connectionRevision() || !Objects.equals(plan.commonCheckpointId(), uuid(session.connection.commonCheckpointId))) {
            throw IntegrationProblem.conflict("PUBLICATION_PLAN_CHANGED");
        }
        com.taxonomy.interop.publication.PublicationProjectionBudget.require(preview, review, plan);
        if (session.connection.activeOperationId != null && !session.connection.activeOperationId.equals(publicationEntity.id)) {
            if (!Objects.equals(session.connection.activeOperationId, publicationEntity.predecessorId)) {
                throw IntegrationProblem.conflict("INTEGRATION_PENDING");
            }
            supersedePublication(uuid(publicationEntity.predecessorId), plan.operationId(), review.review().rationale());
        }
        requireLocal(plan.operationId(), plan.context().internalState(), false);
        publicationEntity.planJson = json.write(plan);
        publicationEntity.reviewJson = json.write(review);
        operation.reviewJson = json.write(review.review());
        operation.reviewFingerprint = plan.reviewFingerprint();
        session.connection.activeOperationId = publicationEntity.id;
        int ordinal = 0;
        for (var intent : plan.items()) {
            var item = new IntegrationPublishItemEntity();
            item.id = digests.fingerprint(List.of(publicationEntity.id, intent.itemId()));
            item.scopeId = publicationEntity.scopeId;
            item.connectionId = publicationEntity.connectionId;
            item.operationId = publicationEntity.id;
            item.itemKey = intent.itemId();
            item.ordinal = ordinal++;
            item.idempotencyKey = intent.idempotencyKey();
            item.intentJson = json.write(intent);
            item.state = ItemState.READY.name();
            em.persist(item);
        }
        phase(publicationEntity, PublicationPhase.LOCAL_APPLY_PENDING, null);
    }

    public void stagePublicationLocal(UUID id, InternalState result, ExchangeDocument document, List<StagedBinding> bindings) {
        var publicationEntity = require(id);
        requireActive(publicationEntity);
        if (!publicationEntity.phase.equals(PublicationPhase.LOCAL_APPLY_PENDING.name())) {
            throw IntegrationProblem.conflict("PUBLICATION_PHASE");
        }
        var plan = plan(id);
        if (!digests.semantic(document).equals(digests.semantic(plan.localTarget()))) {
            throw IntegrationProblem.conflict("PUBLICATION_LOCAL_TARGET");
        }
        bounded(MAX_DOCUMENT_BYTES, bindings);
        publicationEntity.bindingsJson = json.write(bindings);
        publicationEntity.localStateJson = json.write(result);
        session.applied(id, result, document, true);
        publicationEntity.reservedRevision = session.connection.revision;
        phase(publicationEntity, PublicationPhase.LOCAL_CHECKPOINT_PENDING, null);
    }

    public void checkpointDeferred(UUID id) {
        var publication = require(id);
        requireActive(publication);
        if (!PublicationPhase.LOCAL_CHECKPOINT_PENDING.name().equals(publication.phase)) {
            throw IntegrationProblem.conflict("PUBLICATION_PHASE");
        }
        phase(publication, PublicationPhase.LOCAL_CHECKPOINT_PENDING, "PUBLICATION_CHECKPOINT_UNAVAILABLE");
    }

    public void publicationLocalCheckpointed(UUID id, InternalState result) {
        var publicationEntity = require(id);
        requireActive(publicationEntity);
        if (!publicationEntity.phase.equals(PublicationPhase.LOCAL_CHECKPOINT_PENDING.name())) {
            throw IntegrationProblem.conflict("PUBLICATION_PHASE");
        }
        InternalState staged = localState(id);
        if (staged.semanticRevision() != result.semanticRevision() || !Objects.equals(staged.projectFingerprint(), result.projectFingerprint())) {
            throw IntegrationProblem.conflict("PUBLICATION_LOCAL_MOVED");
        }
        requireLocal(id, result, true);
        publicationEntity.localStateJson = json.write(result);
        var operation = session.requireOperation(id);
        operation.resultStateJson = json.write(result);
        operation.resultCommit = result.commitId();
        operation.resultRevision = result.semanticRevision();
        phase(publicationEntity, items(publicationEntity).isEmpty() ? PublicationPhase.VERIFY_PENDING : PublicationPhase.PUBLISH_PENDING, null);
    }

    public List<IntegrationStore.Identity> identities(UUID id) {
        var publicationEntity = require(id);
        var map = new TreeMap<String, IntegrationStore.Identity>();
        session.identities().forEach(i -> map.put(i.externalId(), i));
        if (publicationEntity.predecessorId != null) {
            identities(uuid(publicationEntity.predecessorId)).forEach(i -> map.put(i.externalId(), i));
        }
        if (publicationEntity.bindingsJson != null) {
            for (var b : json.read(publicationEntity.bindingsJson, StagedBinding[].class)) {
                map.put(bindingKey(b), new IntegrationStore.Identity(bindingKey(b), b.businessIdentity(), b.requirementId(), null, digests.fingerprint(b.externalArtifact()), b.externalArtifact(), b.internalArtifact(), id, b.removed()));
            }
        }
        return List.copyOf(map.values());
    }

    public PublicationClaim claimPublicationAttempt(UUID id, Instant now) {
        var publicationEntity = require(id);
        requireActive(publicationEntity);
        if (publicationEntity.planJson == null || publicationEntity.localStateJson == null) {
            return null;
        }
        if (List.of(PublicationPhase.COMPLETED.name(), PublicationPhase.LOCAL_APPLY_PENDING.name(), PublicationPhase.LOCAL_CHECKPOINT_PENDING.name()).contains(publicationEntity.phase)) {
            return null;
        }
        var items = items(publicationEntity);
        var item = items.stream().filter(i -> !i.state.equals(ItemState.ACKNOWLEDGED.name())).findFirst().orElse(null);
        if (item == null) {
            return null;
        }
        if (publicationEntity.leaseUntil != null && now.isBefore(Instant.parse(publicationEntity.leaseUntil))) {
            return null;
        }
        if (item.state.equals(ItemState.IN_FLIGHT.name())) {
            item.state = ItemState.UNKNOWN.name();
            event(publicationEntity, "PUBLICATION_UNKNOWN", "LEASE_EXPIRED");
        }
        if (!Set.of(ItemState.READY.name(), ItemState.UNKNOWN.name(), ItemState.RETRYABLE_NO_EFFECT.name()).contains(item.state)) {
            return null;
        }
        if (item.attemptCount >= MAX_ATTEMPTS) {
            item.failureCode = "PUBLICATION_ATTEMPT_LIMIT";
            phase(publicationEntity, PublicationPhase.RECOVERY_REQUIRED, item.failureCode);
            return null;
        }
        // NOT_FOUND never fences an overlapping original SEND. After movement, recovery
        // remains read-only even when a previous lookup permitted resubmission.
        boolean reconciliation = publicationEntity.phase.equals(PublicationPhase.RECONCILIATION_REQUIRED.name());
        boolean lookup = item.state.equals(ItemState.UNKNOWN.name()) && (reconciliation || !item.resubmitAllowed);
        if (!lookup) {
            if (publicationEntity.phase.equals(PublicationPhase.RECONCILIATION_REQUIRED.name())) {
                return null;
            }
            requireLocal(id, localState(id), true);
        }
        if (item.requestJson == null) {
            var plan = plan(id);
            String revision = plan.remoteBefore().revision();
            for (var previous : items) {
                if (previous == item) {
                    break;
                }
                var receipt = json.read(previous.receiptJson, PublicationReceipt.class);
                if (receipt == null || receipt.state() != ReceiptState.APPLIED) {
                    throw IntegrationProblem.conflict("PUBLICATION_PREDECESSOR");
                }
                revision = receipt.afterScopeRevision();
            }
            var request = digests.request(id, plan.planFingerprint(), plan.capabilities().provider(), plan.capabilities().scope(), json.read(item.intentJson, PublicationItemIntent.class), revision);
            validator.validateRequest(plan, request);
            item.requestJson = json.write(request);
            item.requestFingerprint = request.requestFingerprint();
        }
        var request = json.read(item.requestJson, PublicationItemRequest.class);
        validator.validateRequest(plan(id), request);
        publicationEntity.leaseEpoch++;
        var attempt = new IntegrationPublishAttemptEntity();
        attempt.id = UUID.randomUUID().toString();
        attempt.scopeId = publicationEntity.scopeId;
        attempt.connectionId = publicationEntity.connectionId;
        attempt.operationId = publicationEntity.id;
        attempt.itemId = item.id;
        attempt.leaseEpoch = publicationEntity.leaseEpoch;
        attempt.kind = (lookup ? AttemptKind.LOOKUP : AttemptKind.SEND).name();
        attempt.startedAt = now.toString();
        em.persist(attempt);
        publicationEntity.leaseOwner = attempt.id;
        publicationEntity.leaseUntil = now.plusSeconds(CLAIM_LEASE_SECONDS).toString();
        item.attemptCount++;
        item.resubmitAllowed = false;
        if (!lookup) {
            item.state = ItemState.IN_FLIGHT.name();
        }
        event(publicationEntity, lookup ? "PUBLICATION_LOOKUP" : "PUBLICATION_DISPATCH", null);
        return new PublicationClaim(id, item.itemKey, uuid(attempt.id), publicationEntity.leaseEpoch, AttemptKind.valueOf(attempt.kind), request);
    }

    public void recordPublicationReceipt(PublicationClaim claim, PublicationReceipt receipt) {
        var publicationEntity = require(claim.operationId());
        var attempt = attempt(publicationEntity, claim);
        var item = em.find(IntegrationPublishItemEntity.class, attempt.itemId);
        validator.validate(claim.frozenRequest(), receipt);
        validator.validateRequest(plan(claim.operationId()), claim.frozenRequest());
        if (!json.read(item.requestJson, PublicationItemRequest.class).equals(claim.frozenRequest())) {
            throw IntegrationProblem.conflict("PUBLICATION_REQUEST_CHANGED");
        }
        var previous = json.read(item.receiptJson, PublicationReceipt.class);
        if (previous != null && (previous.terminal() || receipt.receiptSequence() <= previous.receiptSequence())) {
            if (previous.terminal() && receipt.receiptSequence() > previous.receiptSequence() && !previous.equals(receipt)) {
                throw IntegrationProblem.conflict("PUBLICATION_RECEIPT_CHANGED");
            }
            finishAttempt(attempt, "RECEIPT_REPLAY", receipt);
            return;
        }
        finishAttempt(attempt, receipt.state().name(), receipt);
        // A historical no-effect response cannot resolve a newer invocation of the same request.
        // Validated terminal receipts remain authoritative regardless of the current lease.
        if (!receipt.terminal() && !ownsLease(publicationEntity, claim)) {
            return;
        }
        item.receiptJson = json.write(receipt);
        item.failureCode = receipt.failureCode();
        item.state = switch(receipt.state()) {
            case APPLIED ->
                ItemState.ACKNOWLEDGED.name();
            case REJECTED_STALE ->
                ItemState.REJECTED_STALE.name();
            case REJECTED_PERMANENT ->
                ItemState.REJECTED_PERMANENT.name();
            case RETRYABLE_NO_EFFECT ->
                ItemState.RETRYABLE_NO_EFFECT.name();
        };
        releaseLease(publicationEntity, claim);
        event(publicationEntity, "PUBLICATION_RECEIPT", receipt.failureCode());
        if (publicationEntity.phase.equals(PublicationPhase.RECONCILIATION_REQUIRED.name())) {
            return;
        }
        if (receipt.state() == ReceiptState.REJECTED_STALE || receipt.state() == ReceiptState.REJECTED_PERMANENT) {
            phase(publicationEntity, PublicationPhase.PARTIAL, receipt.failureCode());
        } else {
            phase(publicationEntity, items(publicationEntity).stream().allMatch(i -> i.state.equals(ItemState.ACKNOWLEDGED.name())) ? PublicationPhase.VERIFY_PENDING : PublicationPhase.PUBLISH_PENDING, null);
        }
    }

    public void recordPublicationUnknown(PublicationClaim claim, String code) {
        var publicationEntity = require(claim.operationId());
        var a = attempt(publicationEntity, claim);
        var item = em.find(IntegrationPublishItemEntity.class, a.itemId);
        if (hasTerminalReceipt(item) || !ownsLease(publicationEntity, claim)) {
            return;
        }
        finishAttempt(a, code, null);
        item.state = ItemState.UNKNOWN.name();
        item.failureCode = code;
        releaseLease(publicationEntity, claim);
        event(publicationEntity, "PUBLICATION_UNKNOWN", code);
        if (!publicationEntity.phase.equals(PublicationPhase.RECONCILIATION_REQUIRED.name())) {
            phase(publicationEntity, PublicationPhase.RECOVERY_REQUIRED, code);
        }
    }

    public void recordPublicationLookup(PublicationClaim claim, PublicationReceiptLookup lookup) {
        var publicationEntity = require(claim.operationId());
        var a = attempt(publicationEntity, claim);
        if (claim.attemptKind() != AttemptKind.LOOKUP) {
            throw IntegrationProblem.conflict("PUBLICATION_ATTEMPT_KIND");
        }
        if (lookup.state() == LookupState.FOUND) {
            recordPublicationReceipt(claim, lookup.receipt());
            return;
        }
        if (!ownsLease(publicationEntity, claim)) {
            return;
        }
        var item = em.find(IntegrationPublishItemEntity.class, a.itemId);
        if (hasTerminalReceipt(item)) {
            return;
        }
        finishAttempt(a, "LOOKUP_" + lookup.state().name(), null);
        releaseLease(publicationEntity, claim);
        item.resubmitAllowed = lookup.state() == LookupState.NOT_FOUND;
        item.state = ItemState.UNKNOWN.name();
        item.failureCode = "LOOKUP_" + lookup.state().name();
        event(publicationEntity, "PUBLICATION_LOOKUP_RESULT", item.failureCode);
        if (lookup.state() == LookupState.EXPIRED) {
            item.resubmitAllowed = false;
            phase(publicationEntity, PublicationPhase.RECONCILIATION_REQUIRED, "PUBLICATION_RECEIPT_EXPIRED");
        }
    }

    public void cancel(UUID id) {
        var publication = require(id);
        requireConfigured(publication);
        if (publication.planJson != null || !PublicationPhase.PREVIEWED.name().equals(publication.phase)) {
            throw IntegrationProblem.conflict("CANNOT_CANCEL_APPLIED_OPERATION");
        }
        phase(publication, PublicationPhase.CANCELLED, null);
    }

    public void requirePublicationReconciliation(UUID id, String code) {
        var publicationEntity = require(id);
        if (!publicationEntity.phase.equals(PublicationPhase.COMPLETED.name())) {
            phase(publicationEntity, PublicationPhase.RECONCILIATION_REQUIRED, code);
        }
    }

    public void incomplete(UUID id) {
        phase(require(id), PublicationPhase.PARTIAL, "PUBLICATION_SCOPE_DIVERGENT");
    }

    public void completePublication(UUID id, PublicationCompletion completion, CommonBaseline baseline) {
        var publicationEntity = require(id);
        if (publicationEntity.completionJson != null) {
            if (!json.read(publicationEntity.completionJson, PublicationCompletion.class).equals(completion)) {
                throw IntegrationProblem.conflict("PUBLICATION_COMPLETION_CHANGED");
            }
            return;
        }
        requireActive(publicationEntity);
        if (!publicationEntity.phase.equals(PublicationPhase.VERIFY_PENDING.name())) {
            throw IntegrationProblem.conflict("PUBLICATION_PHASE");
        }
        var plan = plan(id);
        validator.validateCompletion(plan, completion);
        if (!Objects.equals(session.connection.commonCheckpointId, publicationEntity.commonCheckpointId) || !localState(id).equals(completion.localAfter()) || !items(publicationEntity).stream().allMatch(i -> i.state.equals(ItemState.ACKNOWLEDGED.name()))) {
            throw IntegrationProblem.conflict("PUBLICATION_COMPLETION_INVALID");
        }
        for (var item : items(publicationEntity)) {
            if (!completion.receipts().contains(json.read(item.receiptJson, PublicationReceipt.class))) {
                throw IntegrationProblem.conflict("PUBLICATION_RECEIPT_INVALID");
            }
        }
        if (!baseline.provider().equals(plan.capabilities().provider()) || !baseline.scope().equals(plan.capabilities().scope()) || !baseline.localDocument().equals(plan.localTarget()) || !baseline.remoteDocument().equals(completion.remoteAfter().document()) || !baseline.semanticFingerprint().equals(completion.commonSemanticFingerprint())) {
            throw IntegrationProblem.conflict("PUBLICATION_BASELINE_INVALID");
        }
        requireLocal(id, completion.localAfter(), true);
        // Caller holds the workspace boundary; re-read all native/portfolio projections independently before promotion.
        local.locked(session.context, document -> {
            var snapshot = local.domain.snapshot(session.context, session.connection(), identities(id), document);
            var projected = local.domain.exportDocument(session.connection(), snapshot, document, identities(id), plan.localTarget());
            if (!digests.semantic(projected).equals(completion.commonSemanticFingerprint())) {
                throw IntegrationProblem.conflict("PUBLICATION_LOCAL_DIVERGENT");
            }
            if (publicationEntity.bindingsJson != null) {
                for (var binding : json.read(publicationEntity.bindingsJson, StagedBinding[].class)) {
                    session.mapping(id, bindingKey(binding), binding.businessIdentity(), binding.requirementId(), completion.remoteAfter().revision(), binding.externalArtifact(), binding.internalArtifact(), binding.removed());
                }
            }
            var c = new IntegrationCheckpointEntity();
            c.id = publicationEntity.id;
            c.scopeId = publicationEntity.scopeId;
            c.connectionId = publicationEntity.connectionId;
            c.operationId = publicationEntity.id;
            c.gitCommit = completion.localAfter().commitId();
            c.externalVersion = completion.remoteAfter().revision();
            c.fingerprint = completion.commonSemanticFingerprint();
            c.contextJson = json.write(completion.localAfter());
            c.createdAt = Instant.now().toString();
            c.kind = "COMMON";
            c.baselineJson = json.write(baseline);
            c.publicationCompletionJson = json.write(completion);
            em.persist(c);
            publicationEntity.completionJson = c.publicationCompletionJson;
            session.connection.commonCheckpointId = c.id;
            session.connection.activeOperationId = null;
            phase(publicationEntity, PublicationPhase.COMPLETED, null);
            return null;
        });
    }

    public void supersedePublication(UUID id, UUID successor, String rationale) {
        var publicationEntity = require(id);
        requireResolved(publicationEntity);
        if (!Objects.equals(session.connection.activeOperationId, publicationEntity.id)) {
            throw IntegrationProblem.conflict("PUBLICATION_RESERVATION_CHANGED");
        }
        var next = require(successor);
        if (!Objects.equals(next.predecessorId, publicationEntity.id)) {
            throw IntegrationProblem.conflict("PUBLICATION_SUCCESSOR_CHANGED");
        }
        session.connection.activeOperationId = next.id;
        phase(publicationEntity, PublicationPhase.RECONCILIATION_REQUIRED, "PUBLICATION_SUPERSEDED");
        event(publicationEntity, "PUBLICATION_SUPERSEDED", null);
    }

    public PublicationOperation publication(UUID id) {
        var publicationEntity = require(id);
        var operation = session.operation(id);
        var plan = plan(id);
        var outcomes = new ArrayList<PublicationItemOutcome>();
        int acknowledged = 0, unknown = 0;
        for (var item : items(publicationEntity)) {
            var intent = json.read(item.intentJson, PublicationItemIntent.class);
            var state = ItemState.valueOf(item.state);
            if (state == ItemState.ACKNOWLEDGED) {
                acknowledged++;
            }
            if (state == ItemState.UNKNOWN) {
                unknown++;
            }
            outcomes.add(new PublicationItemOutcome(item.itemKey, intent.resourceId(), intent.mutation(), state, item.attemptCount, item.failureCode, json.read(item.receiptJson, PublicationReceipt.class)));
        }
        var phase = PublicationPhase.valueOf(publicationEntity.phase);
        Set<PublicationAction> actions = new HashSet<>();
        if (phase == PublicationPhase.PREVIEWED && publicationEntity.previewJson != null) {
            actions.addAll(Set.of(PublicationAction.REVIEW, PublicationAction.PUBLISH, PublicationAction.CANCEL));
        }
        if (phase == PublicationPhase.PREVIEWED && publicationEntity.previewJson == null) {
            actions.addAll(Set.of(PublicationAction.RETRY, PublicationAction.CANCEL));
        }
        boolean active = Objects.equals(session.connection.activeOperationId, publicationEntity.id);
        boolean unresolved = outcomes.stream().anyMatch(item -> item.state() == ItemState.UNKNOWN || item.state() == ItemState.IN_FLIGHT || item.state() == ItemState.RETRYABLE_NO_EFFECT);
        if (active && Set.of(PublicationPhase.LOCAL_APPLY_PENDING, PublicationPhase.LOCAL_CHECKPOINT_PENDING, PublicationPhase.PUBLISH_PENDING, PublicationPhase.RECOVERY_REQUIRED, PublicationPhase.VERIFY_PENDING).contains(phase) && outcomes.stream().noneMatch(i -> i.attempts() >= MAX_ATTEMPTS)) {
            actions.add(PublicationAction.RETRY);
        }
        if (active && phase == PublicationPhase.RECONCILIATION_REQUIRED && unknown > 0 && !"PUBLICATION_RECEIPT_EXPIRED".equals(publicationEntity.failureCode)) {
            actions.add(PublicationAction.RETRY);
        }
        if (active && !unresolved && Set.of(PublicationPhase.PARTIAL, PublicationPhase.RECONCILIATION_REQUIRED).contains(phase)) {
            actions.add(PublicationAction.RECONCILE);
        }
        return new PublicationOperation(1, id, uuid(publicationEntity.connectionId), uuid(publicationEntity.predecessorId), request(id).mode(), phase, operation.status(), plan == null ? null : plan.planFingerprint(), plan == null ? null : plan.reviewFingerprint(), publicationEntity.previewJson == null ? null : preview(id).publicPreview(), outcomes, json.read(publicationEntity.completionJson, PublicationCompletion.class), uuid(session.connection.checkpointId), uuid(session.connection.commonCheckpointId), localState(id), acknowledged, outcomes.size() - acknowledged, unknown, actions, publicationEntity.failureCode, review(id), request(id).scope(), request(id).expectedExternalRevision(), operation.fingerprint());
    }

    // Receipt callbacks retain frozen scoped authority even after current configuration or permissions move.
    // New dispatch, checkpoint and COMMON transitions additionally requireConfigured + live local authority.
    private IntegrationPublicationEntity require(UUID id) {
        var publicationEntity = em.find(IntegrationPublicationEntity.class, id.toString());
        if (publicationEntity == null || !publicationEntity.scopeId.equals(session.connection.scopeId) || !publicationEntity.connectionId.equals(session.connection.id)) {
            throw IntegrationProblem.missing();
        }
        var context = session.operation(id).context();
        if (!context.actor().equals(session.context.username()) || !context.internalState().branch().equals(session.context.branch()) || !context.internalState().repositoryId().equals(session.context.repositoryId())) {
            throw IntegrationProblem.missing();
        }
        if (publicationEntity.schemaVersion != 1) {
            throw IntegrationProblem.conflict("PUBLICATION_SCHEMA_UNSUPPORTED");
        }
        return publicationEntity;
    }

    private void requireConfigured(IntegrationPublicationEntity publication) {
        if (!publication.configFingerprint.equals(config())) {
            throw IntegrationProblem.conflict("PUBLICATION_CONFIGURATION_CHANGED");
        }
        if (local == null) {
            throw IntegrationProblem.conflict("PUBLICATION_LOCAL_AUTHORITY_REQUIRED");
        }
        local.authorize(session.context, true, session.connection());
    }

    private String config() {
        var c = session.connection();
        return digests.fingerprint(Arrays.asList(c.organizationId(), c.connectorId(), c.profileVersion(), c.authority(), c.externalScope(), c.projectId(), c.remoteProfile()));
    }

    private void requireLocal(UUID id, InternalState state, boolean checkpoint) {
        if (local == null) {
            throw IntegrationProblem.conflict("PUBLICATION_LOCAL_AUTHORITY_REQUIRED");
        }
        local.requireExact(session.context, session.connection(), identities(id), state, checkpoint);
    }

    private void requireActive(IntegrationPublicationEntity publicationEntity) {
        requireConfigured(publicationEntity);
        if (!Objects.equals(session.connection.activeOperationId, publicationEntity.id) || publicationEntity.reservedRevision != session.connection.revision) {
            throw IntegrationProblem.conflict("PUBLICATION_RESERVATION_CHANGED");
        }
    }

    private void requireResolved(IntegrationPublicationEntity publicationEntity) {
        if (items(publicationEntity).stream().anyMatch(i -> i.state.equals(ItemState.UNKNOWN.name()) || i.state.equals(ItemState.IN_FLIGHT.name()) || i.state.equals(ItemState.RETRYABLE_NO_EFFECT.name()))) {
            throw IntegrationProblem.conflict("PUBLICATION_UNKNOWN_UNRESOLVED");
        }
        if (!Set.of(PublicationPhase.PARTIAL.name(), PublicationPhase.RECONCILIATION_REQUIRED.name()).contains(publicationEntity.phase)) {
            throw IntegrationProblem.conflict("PUBLICATION_RECONCILIATION_REQUIRED");
        }
    }

    private List<IntegrationPublishItemEntity> items(IntegrationPublicationEntity publicationEntity) {
        return em.createQuery("select i from IntegrationPublishItemEntity i where i.scopeId=:scope and i.connectionId=:connection and i.operationId=:operation order by i.ordinal", IntegrationPublishItemEntity.class).setParameter("scope", publicationEntity.scopeId).setParameter("connection", publicationEntity.connectionId).setParameter("operation", publicationEntity.id).getResultList();
    }

    private IntegrationPublishAttemptEntity attempt(IntegrationPublicationEntity publicationEntity, PublicationClaim c) {
        var a = em.find(IntegrationPublishAttemptEntity.class, c.attemptId().toString());
        if (a == null || !a.scopeId.equals(publicationEntity.scopeId) || !a.connectionId.equals(publicationEntity.connectionId) || !a.operationId.equals(publicationEntity.id) || a.leaseEpoch != c.leaseEpoch() || !a.kind.equals(c.attemptKind().name())) {
            throw IntegrationProblem.missing();
        }
        var i = em.find(IntegrationPublishItemEntity.class, a.itemId);
        if (i == null || !i.itemKey.equals(c.itemId()) || !i.requestFingerprint.equals(c.frozenRequest().requestFingerprint())) {
            throw IntegrationProblem.missing();
        }
        return a;
    }

    private void finishAttempt(IntegrationPublishAttemptEntity a, String code, PublicationReceipt receipt) {
        safe(code);
        a.endedAt = Instant.now().toString();
        a.outcome = code;
        if (receipt != null) {
            a.receiptJson = json.write(receipt);
        }
    }

    private boolean hasTerminalReceipt(IntegrationPublishItemEntity item) {
        var receipt = json.read(item.receiptJson, PublicationReceipt.class);
        return receipt != null && receipt.terminal();
    }

    private boolean ownsLease(IntegrationPublicationEntity publicationEntity, PublicationClaim claim) {
        return publicationEntity.leaseEpoch == claim.leaseEpoch() && Objects.equals(publicationEntity.leaseOwner, claim.attemptId().toString());
    }

    private void releaseLease(IntegrationPublicationEntity publicationEntity, PublicationClaim c) {
        if (ownsLease(publicationEntity, c)) {
            publicationEntity.leaseOwner = null;
            publicationEntity.leaseUntil = null;
        }
    }

    private void phase(IntegrationPublicationEntity publicationEntity, PublicationPhase phase, String code) {
        safe(code);
        publicationEntity.phase = phase.name();
        publicationEntity.failureCode = code;
        publicationEntity.updatedAt = Instant.now().toString();
        var status = switch(phase) {
            case COMPLETED ->
                OperationStatus.COMPLETED;
            case PREVIEWED ->
                OperationStatus.PREVIEWED;
            case PARTIAL, RECOVERY_REQUIRED ->
                OperationStatus.PARTIAL;
            case RECONCILIATION_REQUIRED ->
                OperationStatus.CONFLICT;
            case CANCELLED ->
                OperationStatus.CANCELLED;
            default ->
                OperationStatus.APPLYING;
        };
        session.transition(session.requireOperation(uuid(publicationEntity.id)), status, "PUBLICATION_" + phase.name(), null, code);
    }

    private void event(IntegrationPublicationEntity publicationEntity, String type, String code) {
        safe(code);
        session.event(session.requireOperation(uuid(publicationEntity.id)), type, null, code);
    }

    private static void safe(String code) {
        if (code != null && !code.matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw new IllegalArgumentException("Invalid publication outcome code");
        }
    }

    private static String bindingKey(StagedBinding binding) {
        Artifact artifact = binding.internalArtifact() != null ? binding.internalArtifact() : binding.externalArtifact();
        if (artifact == null || !artifact.id().equals(binding.externalId())) {
            throw IntegrationProblem.conflict("PUBLICATION_BINDING_INVALID");
        }
        return ExchangeItems.key(artifact);
    }

    private static UUID uuid(String s) {
        return s == null ? null : UUID.fromString(s);
    }
}
