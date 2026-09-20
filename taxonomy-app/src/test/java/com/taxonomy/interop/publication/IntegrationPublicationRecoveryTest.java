package com.taxonomy.interop.publication;

import com.taxonomy.dsl.command.ArchitectureCommand.*;
import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import com.taxonomy.extension.api.integration.PublicationContracts.*;
import com.taxonomy.interop.*;
import com.taxonomy.interop.publication.PublicationEvidence.*;
import com.taxonomy.workspace.service.RepositoryContext;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class IntegrationPublicationRecoveryTest extends PublicationIntegrationFixture {

    @Test
    void concurrentRetryAndExpiredLeaseReuseTheFrozenRequestAndAdoptLateAcknowledgment() throws Exception {
        edit(new CreateArchitectureElement("race", "System", Map.of("title", "Race")));
        var waiting = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var visits = new AtomicInteger();
        provider.beforeWrite = () -> {
            if (visits.incrementAndGet() == 1) {
                waiting.countDown();
                await(release);
            }
        };
        var review = review(preview(PublicationMode.PUSH));
        try (var executor = Executors.newSingleThreadExecutor()) {
            var original = executor.submit(() -> publication.publish(context, connection, review));
            assertTrue(waiting.await(15, TimeUnit.SECONDS));
            var inFlight = publication.retryPublication(context, connection, review.review().operationId());
            assertEquals(1, provider.writes.get());
            assertEquals(ItemState.IN_FLIGHT, inFlight.items().getFirst().state());
            assertEquals(0, inFlight.unknownCount());
            UUID id = inFlight.operationId();
            Instant expired = Instant.now().plusSeconds(31);
            var lookup = store.locked(context, connection, s -> s.publications().claimPublicationAttempt(id, expired));
            assertEquals(AttemptKind.LOOKUP, lookup.attemptKind());
            var request = lookup.frozenRequest();
            var result = connector.lookupPublicationReceipt(null, new PublicationReceiptQuery(request.provider(), request.scope(), id, request.item().itemId(), request.item().idempotencyKey(), request.requestFingerprint()));
            assertEquals(LookupState.NOT_FOUND, result.state());
            store.locked(context, connection, s -> {
                s.publications().recordPublicationLookup(lookup, result);
                return null;
            });
            var resend = store.locked(context, connection, s -> s.publications().claimPublicationAttempt(id, expired));
            assertEquals(AttemptKind.SEND, resend.attemptKind());
            assertEquals(request, resend.frozenRequest());
            var receipt = connector.publishItem(null, resend.frozenRequest());
            store.locked(context, connection, s -> {
                s.publications().recordPublicationReceipt(resend, receipt);
                s.publications().recordPublicationUnknown(lookup, "PUBLICATION_DEADLINE");
                return null;
            });
            release.countDown();
            original.get(15, TimeUnit.SECONDS);
            var complete = publication.retryPublication(context, connection, id);
            assertEquals(PublicationPhase.COMPLETED, complete.phase());
            assertEquals(1, provider.mutationCount(request.item().resourceId()));
            assertEquals(2, provider.writes.get());
            assertEquals(provider.requests.getFirst(), provider.requests.getLast());
            assertEquals(ItemState.ACKNOWLEDGED, complete.items().getFirst().state());
        } finally {
            release.countDown();
        }
    }

    @Test
    void malformedReceiptKeepsUnknownAndProviderReloadRecoversWithoutResend() throws Exception {
        edit(new CreateArchitectureElement("malformed", "System", Map.of("title", "Malformed response")));
        provider.malformedAt = 1;
        var operation = publication.publish(context, connection, review(preview(PublicationMode.PUSH)));
        assertEquals(ItemState.UNKNOWN, operation.items().getFirst().state());
        assertFalse(operation.allowedActions().contains(PublicationAction.RECONCILE));
        assertNull(operation.commonCheckpointId());
        provider.close();
        provider = new PublicationContractProvider(directory.resolve("provider.json"));
        connector.provider = provider;
        var reloaded = new IntegrationPublicationService(store, registry, local, json, policy, Clock.systemUTC());
        var recovered = reloaded.retryPublication(context, connection, operation.operationId());
        assertEquals(PublicationPhase.COMPLETED, recovered.phase());
        assertEquals(0, provider.writes.get());
        assertEquals(1, provider.lookups.get());
        assertEquals(1, provider.mutationCount(recovered.items().getFirst().resourceId()));
    }

    @Test
    void commonPromotionAndBindingsRollbackTogetherAndGenericCompletionCannotBypassIt() throws Exception {
        edit(new CreateArchitectureElement("rollback", "System", Map.of("title", "Rollback")));
        var ready = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var reads = new AtomicInteger();
        provider.beforeRead = () -> {
            if (reads.incrementAndGet() == 2) {
                ready.countDown();
                await(release);
            }
        };
        var review = review(preview(PublicationMode.PUSH));
        try (var executor = Executors.newSingleThreadExecutor()) {
            var pending = executor.submit(() -> publication.publish(context, connection, review));
            assertTrue(ready.await(15, TimeUnit.SECONDS));
            var operation = publication.publication(context, connection, review.review().operationId());
            assertEquals(PublicationPhase.VERIFY_PENDING, operation.phase());
            var plan = store.locked(context, connection, s -> s.publications().plan(operation.operationId()));
            var completion = completion(operation, plan);
            var baseline = baseline(plan, completion);
            assertEquals("PUBLICATION_COMPLETION_REQUIRED", assertThrows(IntegrationProblem.class, () -> store.locked(context, connection, s -> {
                s.complete(operation.operationId(), operation.localCheckpoint(), "scope", "digest", true);
                return null;
            })).code());
            assertThrows(RollbackProbe.class, () -> store.locked(context, connection, s -> {
                s.publications().completePublication(operation.operationId(), completion, baseline);
                throw new RollbackProbe();
            }));
            assertNull(store.read(context, connection).commonCheckpointId());
            assertTrue(store.identities(context, connection).isEmpty());
            assertEquals(PublicationPhase.VERIFY_PENDING, publication.publication(context, connection, operation.operationId()).phase());
            release.countDown();
            assertEquals(PublicationPhase.COMPLETED, pending.get(15, TimeUnit.SECONDS).phase());
            assertFalse(store.identities(context, connection).isEmpty());
        } finally {
            release.countDown();
        }
    }

    @Test
    void movedLocalStateAtFinalScopeReadCannotCreateCommonEvenThroughDirectStore() throws Exception {
        edit(new CreateArchitectureElement("final", "System", Map.of("title", "Final")));
        var ready = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var reads = new AtomicInteger();
        provider.beforeRead = () -> {
            if (reads.incrementAndGet() == 2) {
                ready.countDown();
                await(release);
            }
        };
        var review = review(preview(PublicationMode.PUSH));
        try (var executor = Executors.newSingleThreadExecutor()) {
            var pending = executor.submit(() -> publication.publish(context, connection, review));
            assertTrue(ready.await(15, TimeUnit.SECONDS));
            var operation = publication.publication(context, connection, review.review().operationId());
            var plan = store.locked(context, connection, s -> s.publications().plan(operation.operationId()));
            var completion = completion(operation, plan);
            edit(new UpdateArchitectureElement("final", "System", Map.of("title", "Moved final")));
            assertThrows(IntegrationProblem.class, () -> store.locked(context, connection, s -> {
                s.publications().completePublication(operation.operationId(), completion, baseline(plan, completion));
                return null;
            }));
            release.countDown();
            var stopped = pending.get(15, TimeUnit.SECONDS);
            assertEquals(PublicationPhase.RECONCILIATION_REQUIRED, stopped.phase());
            assertEquals(1, stopped.acknowledgedCount());
            assertNull(stopped.commonCheckpointId());
            assertTrue(store.identities(context, connection).isEmpty());
        } finally {
            release.countDown();
        }
    }

    @Test
    void originalActorBranchScopeAndReviewRemainBoundBeforeAnyWrite() throws Exception {
        edit(new CreateArchitectureElement("authority", "System", Map.of("title", "Authority")));
        var draft = preview(PublicationMode.PUSH);
        var foreign = RepositoryContext.workspace(context.repositoryId(), context.workspaceId(), context.branch(), "foreign-actor");
        var branch = RepositoryContext.workspace(context.repositoryId(), context.workspaceId(), "another-branch", context.username());
        assertThrows(IntegrationProblem.class, () -> publication.publication(foreign, connection, draft.operationId()));
        assertThrows(IntegrationProblem.class, () -> publication.publication(branch, connection, draft.operationId()));
        assertThrows(IntegrationProblem.class, () -> publication.previewPublication(context, connection, new PublicationPreviewRequest(draft.operationId(), state(), PublicationMode.SYNCHRONIZE, PublicationContractProvider.SCOPE, provider.snapshot().revision())));
        var accepted = review(draft);
        var completed = publication.publish(context, connection, accepted);
        var changed = new PublicationReview(new ReviewedChangeSet(draft.operationId(), draft.preview().fingerprint(), Map.of(), "Changed review"), accepted.resolutions());
        assertEquals("REVIEW_ID_REUSED", assertThrows(IntegrationProblem.class, () -> publication.publish(context, connection, changed)).code());
        assertEquals(completed, publication.retryPublication(context, connection, completed.operationId()));
        assertEquals(1, provider.writes.get());
    }

    @org.springframework.beans.factory.annotation.Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Test
    void configurationMovementDoesNotEraseAnAlreadyAppliedReceiptAndPreventsFurtherSends() throws Exception {
        edit(new CreateArchitectureElement("config-a", "System", Map.of("title", "A")), new CreateArchitectureElement("config-b", "System", Map.of("title", "B")));
        provider.afterCommit = () -> jdbc.update("update interop_connection set remote_profile=? where id=?", "changed-profile", connection.toString());
        var operation = publication.publish(context, connection, review(preview(PublicationMode.PUSH)));
        assertEquals(ItemState.ACKNOWLEDGED, operation.items().getFirst().state());
        assertEquals(PublicationPhase.RECONCILIATION_REQUIRED, operation.phase());
        assertEquals(1, provider.writes.get());
        assertNull(operation.commonCheckpointId());
        assertEquals(operation, publication.publication(context, connection, operation.operationId()));
        assertEquals(operation, publication.retryPublication(context, connection, operation.operationId()));
        assertEquals(1, provider.writes.get());
    }

    @Test
    void unknownExpiredReceiptCannotBeReplacedOrCancelledAndAttemptsAreBounded() throws Exception {
        edit(new CreateArchitectureElement("expired", "System", Map.of("title", "Expired")));
        provider.closeAt = 1;
        var unknown = publication.publish(context, connection, review(preview(PublicationMode.PUSH)));
        UUID id = unknown.operationId();
        assertThrows(IntegrationProblem.class, () -> store.locked(context, connection, s -> {
            s.cancel(id, "Cannot forget a dispatch");
            return null;
        }));
        var successor = new PublicationPreviewRequest(UUID.randomUUID(), state(), PublicationMode.PUSH, PublicationContractProvider.SCOPE, provider.snapshot().revision());
        assertThrows(IntegrationProblem.class, () -> publication.previewPublicationReconciliation(context, connection, id, new ReconciliationPreviewRequest(id, successor, "Cannot replace unresolved effect")));
        Instant time = Instant.now().plusSeconds(31);
        for (int attempt = 1; attempt < 100; attempt++) {
            Instant claimTime = time.plusSeconds(attempt * 31L);
            var claim = store.locked(context, connection, s -> s.publications().claimPublicationAttempt(id, claimTime));
            assertNotNull(claim);
            assertEquals(AttemptKind.LOOKUP, claim.attemptKind());
            store.locked(context, connection, s -> {
                s.publications().recordPublicationLookup(claim, new PublicationReceiptLookup(LookupState.UNAVAILABLE, null));
                return null;
            });
        }
        assertNull(store.locked(context, connection, s -> s.publications().claimPublicationAttempt(id, time.plusSeconds(4000))));
        var bounded = publication.publication(context, connection, id);
        assertEquals(100, bounded.items().getFirst().attempts());
        assertFalse(bounded.allowedActions().contains(PublicationAction.RETRY));
        assertFalse(bounded.allowedActions().contains(PublicationAction.RECONCILE));
        assertNotNull(store.read(context, connection).activeOperationId());
        assertEquals(1, provider.writes.get());
    }

    @Test
    void acceptedPlanResumesTheLocalPhaseAfterJournalReload() throws Exception {
        edit(new CreateArchitectureElement("staged", "System", Map.of("title", "Staged plan")));
        var draft = preview(PublicationMode.PUSH);
        var review = review(draft);
        var envelope = store.locked(context, connection, s -> s.publications().preview(draft.operationId()));
        var plan = new PublicationPlanner(new PublicationDigests(json), policy, PublicationContractProvider.CAPS).plan(envelope, review);
        store.locked(context, connection, s -> {
            s.publications().beginPublication(review, plan);
            return null;
        });
        assertEquals(PublicationPhase.LOCAL_APPLY_PENDING, publication.publication(context, connection, draft.operationId()).phase());
        var restored = new IntegrationPublicationService(store, registry, local, json, policy, Clock.systemUTC());
        assertEquals(PublicationPhase.COMPLETED, restored.retryPublication(context, connection, draft.operationId()).phase());
        assertEquals(1, provider.writes.get());
        assertEquals(1, journal.read(context).operations().size());
    }

    @Test
    void invocationDeadlineStopsSchedulingAndNextInvocationResumes() throws Exception {
        edit(new CreateArchitectureElement("deadline-a", "System", Map.of("title", "A")), new CreateArchitectureElement("deadline-b", "System", Map.of("title", "B")));
        var now = new java.util.concurrent.atomic.AtomicReference<>(Instant.now());
        Clock controlled = new Clock() {

            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            public Clock withZone(ZoneId zone) {
                return this;
            }

            public Instant instant() {
                return now.get();
            }
        };
        var service = new IntegrationPublicationService(store, registry, local, json, policy, controlled);
        provider.afterCommit = () -> now.updateAndGet(value -> value.plusSeconds(31));
        var operation = service.publish(context, connection, review(preview(PublicationMode.PUSH)));
        assertEquals(1, operation.acknowledgedCount());
        assertEquals(ItemState.READY, operation.items().getLast().state());
        assertEquals(1, provider.writes.get());
        provider.afterCommit = () -> {
        };
        assertEquals(PublicationPhase.COMPLETED, service.retryPublication(context, connection, operation.operationId()).phase());
        assertEquals(2, provider.writes.get());
    }

    @Test
    void provenNoEffectRetryKeepsKeyAndLateOlderReceiptCannotDowngradeSuccess() throws Exception {
        edit(new CreateArchitectureElement("no-effect", "System", Map.of("title", "No effect")));
        provider.noEffectAt = 1;
        var operation = publication.publish(context, connection, review(preview(PublicationMode.PUSH)));
        assertEquals(ItemState.RETRYABLE_NO_EFFECT, operation.items().getFirst().state());
        var earlier = operation.items().getFirst().receipt();
        assertFalse(earlier.terminal());
        var claim = store.locked(context, connection, s -> s.publications().claimPublicationAttempt(operation.operationId(), Instant.now()));
        var applied = connector.publishItem(null, claim.frozenRequest());
        assertEquals(2, applied.receiptSequence());
        store.locked(context, connection, s -> {
            s.publications().recordPublicationReceipt(claim, applied);
            s.publications().recordPublicationReceipt(claim, earlier);
            return null;
        });
        var complete = publication.retryPublication(context, connection, operation.operationId());
        assertEquals(PublicationPhase.COMPLETED, complete.phase());
        assertEquals(applied, complete.items().getFirst().receipt());
        assertEquals(provider.requests.getFirst(), provider.requests.getLast());
        assertEquals(1, provider.mutationCount(applied.resultingResource().resourceId()));
    }

    @Test
    void expiredRemoteReceiptRetainsUnknownReservationForExternalRecovery() throws Exception {
        edit(new CreateArchitectureElement("forgotten", "System", Map.of("title", "Forgotten")));
        provider.closeAt = 1;
        var unknown = publication.publish(context, connection, review(preview(PublicationMode.PUSH)));
        provider.lookupState = LookupState.EXPIRED;
        var stopped = publication.retryPublication(context, connection, unknown.operationId());
        assertEquals(PublicationPhase.RECONCILIATION_REQUIRED, stopped.phase());
        assertEquals(ItemState.UNKNOWN, stopped.items().getFirst().state());
        assertFalse(stopped.allowedActions().contains(PublicationAction.RETRY));
        assertFalse(stopped.allowedActions().contains(PublicationAction.RECONCILE));
        assertNotNull(store.read(context, connection).activeOperationId());
        assertNull(stopped.commonCheckpointId());
        assertEquals(1, provider.writes.get());
    }

    @org.springframework.beans.factory.annotation.Autowired
    com.taxonomy.workspace.service.SystemRepositoryService repositoryAuthority;

    @org.springframework.beans.factory.annotation.Autowired
    com.taxonomy.workspace.service.RepositoryMembershipService memberships;

    @org.springframework.beans.factory.annotation.Autowired
    com.taxonomy.workspace.service.WorkspaceAccessService workspaceAccess;

    @Test
    void transientGitFailureRetainsItsDurableCheckpointIntentForRetry() throws Exception {
        edit(new CreateArchitectureElement("git-retry", "System", Map.of("title", "Git retry")));
        var attempts = new AtomicInteger();
        var portType = com.taxonomy.workspace.service.WorkspaceArchitectureIntegrationPort.class;
        var interrupted = (com.taxonomy.workspace.service.WorkspaceArchitectureIntegrationPort) java.lang.reflect.Proxy.newProxyInstance(portType.getClassLoader(), new Class<?>[] { portType }, (proxy, method, args) -> {
            if (method.getName().equals("checkpoint") && attempts.incrementAndGet() == 1) {
                throw new java.io.IOException("Injected temporary Git storage failure");
            }
            try {
                return method.invoke(editor, args);
            } catch (java.lang.reflect.InvocationTargetException failure) {
                throw failure.getCause();
            }
        });
        var authority = new PublicationLocalAuthority(local.domain, interrupted, repositoryAuthority, memberships, workspaceAccess);
        var service = new IntegrationPublicationService(store, registry, authority, json, policy, Clock.systemUTC());
        var operation = service.publish(context, connection, review(preview(PublicationMode.PUSH)));
        assertEquals(PublicationPhase.LOCAL_CHECKPOINT_PENDING, operation.phase());
        assertTrue(operation.allowedActions().contains(PublicationAction.RETRY));
        assertEquals(0, provider.writes.get());
        assertEquals(PublicationPhase.COMPLETED, service.retryPublication(context, connection, operation.operationId()).phase());
        assertEquals(1, provider.writes.get());
        assertEquals(1, journal.read(context).operations().size());
    }

    @Test
    void failedInitialFetchKeepsFrozenRequestAndOffersRetry() {
        UUID id = UUID.randomUUID();
        var request = new PublicationPreviewRequest(id, state(), PublicationMode.PUSH, PublicationContractProvider.SCOPE, provider.snapshot().revision());
        provider.beforeRead = () -> assertEquals(request, store.locked(context, connection, session -> session.publications().request(id)));
        provider.failReadAt = 1;
        assertThrows(IntegrationProblem.class, () -> publication.previewPublication(context, connection, request));
        var failed = publication.publication(context, connection, id);
        assertTrue(failed.allowedActions().contains(PublicationAction.RETRY));
        assertEquals(0, provider.writes.get());
        var recovered = publication.retryPublication(context, connection, id);
        assertNotNull(recovered.preview());
        assertEquals(2, provider.reads.get());
        assertEquals(id, recovered.operationId());
    }

    @Test
    void movedUnknownRequestCanLookupLateCommitAfterNotFoundAndClientReload() throws Exception {
        edit(new CreateArchitectureElement("late-moved", "System", Map.of("title", "Original")));
        var review = review(preview(PublicationMode.PUSH));
        var operation = checkpointPending(review);
        var clock = new MutableClock();
        var recovery = new IntegrationPublicationService(store, registry, local, json, policy, clock);
        UUID id = operation.operationId();
        var expected = operation.localCheckpoint();
        var checkpoint = editor.checkpoint(context, new com.taxonomy.workspace.service.WorkspaceArchitectureIntegrationPort.State(expected.workspaceScopeKey(), expected.commitId(), expected.semanticRevision()), checkpointMetadata(id, review));
        var checkpointed = new InternalState(expected.repositoryId(), expected.workspaceScopeKey(), expected.branch(), checkpoint.state().commitId(), checkpoint.state().semanticRevision(), expected.projectId(), expected.projectFingerprint());
        var original = store.locked(context, connection, session -> {
            session.publications().publicationLocalCheckpointed(id, checkpointed);
            return session.publications().claimPublicationAttempt(id, clock.instant());
        });
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var committed = new CountDownLatch(1);
        provider.beforeWrite = () -> {
            entered.countDown();
            await(release);
        };
        provider.afterCommit = committed::countDown;
        provider.closeAt = 1;
        var executor = Executors.newSingleThreadExecutor();
        var originalHttp = executor.submit(() -> connector.publishItem(null, original.frozenRequest()));
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            clock.advance(31);
            var lookup = store.locked(context, connection, session -> session.publications().claimPublicationAttempt(id, clock.instant()));
            var request = lookup.frozenRequest();
            var query = new PublicationReceiptQuery(request.provider(), request.scope(), id, request.item().itemId(), request.item().idempotencyKey(), request.requestFingerprint());
            var notFound = connector.lookupPublicationReceipt(null, query);
            assertEquals(LookupState.NOT_FOUND, notFound.state());
            store.locked(context, connection, session -> {
                session.publications().recordPublicationLookup(lookup, notFound);
                return null;
            });
            assertLookupState(id, 2, true);
            edit(new UpdateArchitectureElement("late-moved", "System", Map.of("title", "Moved locally")));
            var moved = recovery.retryPublication(context, connection, id);
            assertEquals("PUBLICATION_LOCAL_MOVED", moved.failureCode());
            assertLookupState(id, 2, true);
            assertEquals(PublicationPhase.RECONCILIATION_REQUIRED, moved.phase());
            int lookupsBefore = provider.lookups.get();
            var stillUnknown = recovery.retryPublication(context, connection, id);
            assertLookupState(id, 3, true);
            int lookupsAfter = provider.lookups.get();
            assertEquals(ItemState.UNKNOWN, stillUnknown.items().getFirst().state());
            release.countDown();
            assertTrue(committed.await(10, TimeUnit.SECONDS));
            assertThrows(ExecutionException.class, () -> originalHttp.get(10, TimeUnit.SECONDS));
            // The original callback is lost; only the remote receipt and journal survive this client reload.
            var restored = new IntegrationPublicationService(store, registry, local, json, policy, clock);
            var recovered = restored.retryPublication(context, connection, id);
            var recoveryEvidence = "failure=" + recovered.failureCode() + ", actions=" + recovered.allowedActions() + ", attempts=" + recovered.items().getFirst().attempts() + ", events=" + store.events(context, connection, id);
            assertAll(() -> assertEquals(lookupsBefore + 1, lookupsAfter, "Lookup-only NOT_FOUND returns after one attempt"), () -> assertEquals(lookupsAfter + 1, provider.lookups.get(), "A later invocation must query the durable receipt"), () -> assertEquals(ItemState.ACKNOWLEDGED, recovered.items().getFirst().state(), recoveryEvidence), () -> assertTrue(recovered.allowedActions().contains(PublicationAction.RECONCILE)), () -> assertEquals(1, provider.writes.get()), () -> assertEquals(1, provider.mutationCount(request.item().resourceId())), () -> assertEquals(original.frozenRequest(), provider.requests.getFirst()), () -> assertNull(recovered.commonCheckpointId()), () -> assertEquals(id, store.read(context, connection).activeOperationId()));
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void publishBudgetIncludesRealLocalStagingBeforeCheckpointOrHttp() throws Exception {
        assertStagingConsumesBudget(false);
    }

    @Test
    void acceptedPlanResumeDoesNotRenewBudgetAfterRealLocalStaging() throws Exception {
        assertStagingConsumesBudget(true);
    }

    private void assertStagingConsumesBudget(boolean resume) throws Exception {
        var review = remoteElementReview();
        UUID id = review.review().operationId();
        if (resume) {
            var envelope = store.locked(context, connection, session -> session.publications().preview(id));
            var plan = new PublicationPlanner(new PublicationDigests(json), policy, PublicationContractProvider.CAPS).plan(envelope, review);
            store.locked(context, connection, session -> {
                session.publications().beginPublication(review, plan);
                return null;
            });
        }
        var clock = new MutableClock();
        var checkpoints = new AtomicInteger();
        var advanced = new java.util.concurrent.atomic.AtomicBoolean();
        var port = probingPort((method, args) -> {
            if (method.getName().equals("checkpoint")) {
                checkpoints.incrementAndGet();
            }
            Object result = invokeEditor(method, args);
            if (method.getName().equals("locked") && advanced.compareAndSet(false, true)) {
                clock.advance(31);
            }
            return result;
        });
        var service = service(port, clock);
        int reads = provider.reads.get();
        var pending = resume ? service.retryPublication(context, connection, id) : service.publish(context, connection, review);
        assertAll(() -> assertEquals(PublicationPhase.LOCAL_CHECKPOINT_PENDING, pending.phase()), () -> assertEquals(0, checkpoints.get(), "Expired local work must not schedule Git"), () -> assertEquals(reads, provider.reads.get(), "Expired local work must not schedule scope verification"), () -> assertEquals(0, provider.writes.get()));
        assertEquals(1, journal.read(context).operations().size());
        assertNotNull(journal.read(context).state().pendingCheckpoint());
        String fingerprint = pending.planFingerprint();
        var completed = service.retryPublication(context, connection, id);
        assertEquals(PublicationPhase.COMPLETED, completed.phase(), completed.failureCode());
        assertEquals(fingerprint, completed.planFingerprint());
        assertEquals(1, journal.read(context).operations().size());
        assertEquals(1, journal.checkpoints(context).size());
    }

    @Test
    void fetchRetryDoesNotRenewBudgetConsumedByScopedAuthorization() {
        UUID id = UUID.randomUUID();
        var request = new PublicationPreviewRequest(id, state(), PublicationMode.PUSH, PublicationContractProvider.SCOPE, provider.snapshot().revision());
        provider.failReadAt = 1;
        assertThrows(IntegrationProblem.class, () -> publication.previewPublication(context, connection, request));
        var clock = new MutableClock();
        var once = new java.util.concurrent.atomic.AtomicBoolean();
        var authority = new PublicationLocalAuthority(local.domain, editor, repositoryAuthority, memberships, workspaceAccess) {

            @Override
            public void authorize(RepositoryContext scoped, boolean write, com.taxonomy.interop.persistence.IntegrationStore.Connection current) {
                super.authorize(scoped, write, current);
                if (once.compareAndSet(false, true)) {
                    clock.advance(31);
                }
            }
        };
        var service = new IntegrationPublicationService(store, registry, authority, json, policy, clock);
        var pending = service.retryPublication(context, connection, id);
        assertNull(pending.preview());
        assertTrue(pending.allowedActions().contains(PublicationAction.RETRY));
        assertEquals(1, provider.reads.get());
        assertNotNull(service.retryPublication(context, connection, id).preview());
        assertEquals(2, provider.reads.get());
    }

    @Test
    void blockedGitReturnsPendingAndLateEditorCompletionReplaysExactContextAndIntent() throws Exception {
        var review = remoteElementReview();
        UUID id = review.review().operationId();
        var clock = new MutableClock();
        var advanced = new java.util.concurrent.atomic.AtomicBoolean();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        var contexts = new CopyOnWriteArrayList<RepositoryContext>();
        var metadata = new CopyOnWriteArrayList<Object>();
        var port = probingPort((method, args) -> {
            if (method.getName().equals("checkpoint")) {
                assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
                contexts.add((RepositoryContext) args[0]);
                metadata.add(args[2]);
                entered.countDown();
                await(release);
                try {
                    return invokeEditor(method, args);
                } finally {
                    finished.countDown();
                }
            }
            Object result = invokeEditor(method, args);
            if (method.getName().equals("locked") && advanced.compareAndSet(false, true)) {
                clock.advance(29);
            }
            return result;
        });
        var service = service(port, clock);
        var executor = Executors.newSingleThreadExecutor();
        var call = executor.submit(() -> service.publish(context, connection, review));
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            var pending = call.get(5, TimeUnit.SECONDS);
            assertEquals(PublicationPhase.LOCAL_CHECKPOINT_PENDING, pending.phase());
            assertTrue(pending.allowedActions().contains(PublicationAction.RETRY));
            assertEquals(0, provider.writes.get());
            assertNotNull(journal.read(context).state().pendingCheckpoint());
            release.countDown();
            assertTrue(finished.await(10, TimeUnit.SECONDS));
            assertNull(journal.read(context).state().pendingCheckpoint(), "Late Git completes its durable editor intent");
            long commits = git.resolveRepository(context).getCommitCount(context.branch());
            var recovered = service.retryPublication(context, connection, id);
            assertEquals(PublicationPhase.COMPLETED, recovered.phase(), recovered.failureCode());
            assertEquals(List.of(context, context), contexts);
            assertEquals(metadata.getFirst(), metadata.getLast());
            assertEquals(checkpointMetadata(id, review), metadata.getFirst());
            assertEquals(commits, git.resolveRepository(context).getCommitCount(context.branch()));
            assertEquals(1, journal.read(context).operations().size());
            assertEquals(1, journal.checkpoints(context).size());
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void lateCheckpointFailureCannotDowngradeAConcurrentRetryCompletion() throws Exception {
        var review = remoteElementReview();
        var pending = checkpointPending(review);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var port = probingPort((method, args) -> {
            if (method.getName().equals("checkpoint")) {
                entered.countDown();
                await(release);
                throw new java.io.IOException("Late failure from the older checkpoint invocation");
            }
            return invokeEditor(method, args);
        });
        var service = service(port, Clock.systemUTC());
        var executor = Executors.newSingleThreadExecutor();
        var oldCall = executor.submit(() -> service.retryPublication(context, connection, pending.operationId()));
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            var completed = publication.retryPublication(context, connection, pending.operationId());
            assertEquals(PublicationPhase.COMPLETED, completed.phase());
            release.countDown();
            assertEquals(completed, oldCall.get(10, TimeUnit.SECONDS));
            assertEquals(completed, publication.publication(context, connection, pending.operationId()));
            assertEquals(1, journal.read(context).operations().size());
            assertEquals(1, journal.checkpoints(context).size());
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @org.springframework.beans.factory.annotation.Autowired
    jakarta.persistence.EntityManagerFactory entityManagerFactory;

    private void assertLookupState(UUID id, int attempts, boolean resubmitAllowed) {
        try (var em = entityManagerFactory.createEntityManager()) {
            Object[] row = (Object[]) em.createNativeQuery("select p.lease_owner,p.lease_until,i.state,i.resubmit_allowed,i.attempt_count from interop_publication p join interop_publish_item i on i.operation_id=p.id where p.id=:id and p.connection_id=:connection").setParameter("id", id.toString()).setParameter("connection", connection.toString()).getSingleResult();
            String evidence = Arrays.toString(row);
            assertAll(() -> assertNull(row[0], evidence), () -> assertNull(row[1], evidence), () -> assertEquals("UNKNOWN", row[2], evidence), () -> assertEquals(resubmitAllowed, row[3], evidence), () -> assertEquals(attempts, ((Number) row[4]).intValue(), evidence));
        }
    }

    private PublicationReview remoteElementReview() throws Exception {
        var element = new Artifact("urn:budget:element", ArtifactKind.ELEMENT, "ApplicationComponent", "Remote budget element", "", Map.of(), Map.of("canonicalType", "System"));
        provider.seed(new ExchangeDocument(PublicationContractProvider.PROFILE, "1", null, true, "", List.of(element), List.of(), List.of(), Map.of(), List.of()));
        var draft = preview(PublicationMode.SYNCHRONIZE);
        var choices = new TreeMap<String, PublicationResolution>();
        draft.preview().changes().forEach(change -> choices.put(change.id(), PublicationResolution.TAKE_REMOTE));
        return new PublicationReview(new ReviewedChangeSet(draft.operationId(), draft.preview().fingerprint(), Map.of(), "Budgeted synchronization"), choices);
    }

    private PublicationOperation checkpointPending(PublicationReview review) {
        return service(probingPort((method, args) -> {
            if (method.getName().equals("checkpoint")) {
                throw new java.io.IOException("Stop at durable checkpoint boundary");
            }
            return invokeEditor(method, args);
        }), Clock.systemUTC()).publish(context, connection, review);
    }

    private com.taxonomy.workspace.service.WorkspaceArchitectureIntegrationPort.CommandMetadata checkpointMetadata(UUID id, PublicationReview review) {
        return new com.taxonomy.workspace.service.WorkspaceArchitectureIntegrationPort.CommandMetadata(UUID.nameUUIDFromBytes((id + ":checkpoint").getBytes(java.nio.charset.StandardCharsets.UTF_8)), id, id, review.review().rationale());
    }

    private IntegrationPublicationService service(com.taxonomy.workspace.service.WorkspaceArchitectureIntegrationPort port, Clock clock) {
        return new IntegrationPublicationService(store, registry, new PublicationLocalAuthority(local.domain, port, repositoryAuthority, memberships, workspaceAccess), json, policy, clock);
    }

    private com.taxonomy.workspace.service.WorkspaceArchitectureIntegrationPort probingPort(EditorProbe probe) {
        var type = com.taxonomy.workspace.service.WorkspaceArchitectureIntegrationPort.class;
        return (com.taxonomy.workspace.service.WorkspaceArchitectureIntegrationPort) java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, (proxy, method, args) -> probe.invoke(method, args));
    }

    private Object invokeEditor(java.lang.reflect.Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(editor, args);
        } catch (java.lang.reflect.InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    private interface EditorProbe {

        Object invoke(java.lang.reflect.Method method, Object[] args) throws Throwable;
    }

    private static final class MutableClock extends Clock {

        private final java.util.concurrent.atomic.AtomicReference<Instant> now = new java.util.concurrent.atomic.AtomicReference<>(Instant.now());

        void advance(long seconds) {
            now.updateAndGet(value -> value.plusSeconds(seconds));
        }

        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        public Clock withZone(ZoneId zone) {
            return this;
        }

        public Instant instant() {
            return now.get();
        }
    }

    private PublicationCompletion completion(PublicationOperation operation, PublicationPlan plan) {
        return new PublicationCompletion(1, operation.operationId(), plan.planFingerprint(), operation.items().stream().map(PublicationItemOutcome::receipt).toList(), provider.snapshot(), operation.localCheckpoint(), new PublicationDigests(json).semantic(plan.localTarget()));
    }

    private CommonBaseline baseline(PublicationPlan plan, PublicationCompletion completion) {
        return new CommonBaseline(1, plan.capabilities().provider(), plan.capabilities().scope(), plan.context().profile(), plan.context().profileVersion(), plan.localTarget(), completion.remoteAfter().document(), completion.commonSemanticFingerprint());
    }

    private static void await(CountDownLatch release) {
        try {
            if (!release.await(20, TimeUnit.SECONDS)) {
                throw new AssertionError("Barrier timeout");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    private static final class RollbackProbe extends RuntimeException {
    }
}
