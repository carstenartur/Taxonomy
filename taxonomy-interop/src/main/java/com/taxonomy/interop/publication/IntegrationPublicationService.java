package com.taxonomy.interop.publication;

import com.taxonomy.dsl.command.ArchitectureCommand;
import com.taxonomy.extension.api.integration.*;
import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import com.taxonomy.extension.api.integration.PublicationContracts.*;
import com.taxonomy.interop.*;
import com.taxonomy.interop.persistence.*;
import com.taxonomy.interop.persistence.IntegrationStore.*;
import com.taxonomy.interop.publication.PublicationEvidence.*;
import com.taxonomy.workspace.service.*;
import com.taxonomy.workspace.service.WorkspaceArchitectureIntegrationPort.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static com.taxonomy.extension.api.integration.PublicationBounds.*;

/**
 * Conditional publication orchestrator. Every network/Git effect is outside journal/model transactions.
 */
@Service
public class IntegrationPublicationService {

    private final IntegrationStore store;

    private final ExchangeConnectorRegistry connectors;

    private final PublicationLocalAuthority local;

    private final IntegrationJson json;

    private final PublicationDigests digests;

    private final PublicationReceiptValidator validator;

    private final PublicationPolicy policy;

    private final Clock clock;

    @Autowired
    public IntegrationPublicationService(IntegrationStore store, ExchangeConnectorRegistry connectors, PublicationLocalAuthority local, IntegrationJson json, ObjectProvider<PublicationPolicy> policies, ObjectProvider<Clock> clocks) {
        this(store, connectors, local, json, policies.getIfAvailable(PublicationPolicy::new), clocks.getIfAvailable(Clock::systemUTC));
    }

    public IntegrationPublicationService(IntegrationStore store, ExchangeConnectorRegistry connectors, PublicationLocalAuthority local, IntegrationJson json, PublicationPolicy policy, Clock clock) {
        this.store = store;
        this.connectors = connectors;
        this.local = local;
        this.json = json;
        this.policy = policy;
        this.clock = clock;
        this.digests = new PublicationDigests(json);
        this.validator = new PublicationReceiptValidator(digests);
    }

    public PublicationAvailability availability(RepositoryContext context, UUID connectionId, PublicationScope scope) {
        local.authorize(context, false, null);
        var connection = store.read(context, connectionId);
        local.authorize(context, false, connection);
        var connector = connectors.require(connection.connectorId(), connection.profileVersion());
        if (!(connector instanceof ConditionalPublicationConnector conditional)) {
            return new PublicationAvailability(false, Set.of(), Set.of(), "PUBLICATION_GUARANTEES_UNVERIFIED");
        }
        if (scope == null) return new PublicationAvailability(false, Set.of(), Set.of(), "PUBLICATION_SCOPE_REQUIRED");
        var state = store.locked(context, connectionId, session -> local.locked(context, d -> local.domain.snapshot(context, connection, session.identities(), d).state()));
        var authority = authority(context, connection, state);
        var capabilities = conditional.publicationCapabilities(authority, scope);
        if (!scope.equals(capabilities.scope())) return new PublicationAvailability(false, Set.of(), Set.of(), "PUBLICATION_SCOPE_CHANGED");
        return policy.availability(authority, connector, capabilities);
    }

    public PublicationOperation previewPublication(RepositoryContext context, UUID connectionId, PublicationPreviewRequest request) {
        return preview(context, connectionId, request, null, new InvocationBudget());
    }

    private PublicationOperation preview(RepositoryContext context, UUID connectionId, PublicationPreviewRequest request, UUID predecessor, InvocationBudget budget) {
        local.authorize(context, true, null);
        var connection = store.read(context, connectionId);
        var authority = authority(context, connection, request.expected());
        var connector = verified(context, connection, authority, request.scope(), request.mode());
        var existing = store.locked(context, connectionId, session -> session.publications().find(request.operationId()));
        if (existing != null) {
            var frozen = store.locked(context, connectionId, session -> session.publications().request(request.operationId()));
            if (!frozen.equals(request)) {
                throw IntegrationProblem.conflict("OPERATION_ID_REUSED");
            }
            if (existing.preview() != null) {
                return existing;
            }
        } else {
            store.locked(context, connectionId, session -> local.locked(context, before -> {
                local.domain.lockProject(context, connection.projectId());
                expect(request.expected(), local.domain.snapshot(context, connection, session.identities(), before).state());
                return session.publications().initialize(request, authority, predecessor);
            }));
        }
        if (budget.expired()) {
            return publication(context, connectionId, request.operationId());
        }
        try {
            ScopeSnapshot remote = boundedCall(() -> connector.readPublicationScope(authority, request.scope(), request.expectedExternalRevision()), budget);
            validator.validateSnapshot(remote);
            if (!remote.provider().equals(connector.publicationCapabilities(authority, request.scope()).provider()) || !remote.scope().equals(request.scope()) || request.expectedExternalRevision() != null && !request.expectedExternalRevision().equals(remote.revision())) {
                throw IntegrationProblem.conflict("PUBLICATION_REMOTE_CHANGED");
            }
            return store.locked(context, connectionId, session -> local.locked(context, before -> {
                var frozen = session.publications().preview(request.operationId());
                if (frozen != null) {
                    return session.publications().publication(request.operationId());
                }
                local.domain.lockProject(context, connection.projectId());
                var mappings = session.publications().identities(request.operationId());
                var current = local.domain.snapshot(context, connection, mappings, before);
                expect(request.expected(), current.state());
                var projected = local.domain.exportDocument(connection, current, before, mappings, remote.document());
                var baseline = session.publications().commonBaseline();
                var changes = new PublicationDiff().compare(baseline, projected, remote);
                var losses = new ArrayList<>(projected.losses());
                losses.addAll(remote.document().losses());
                var unsigned = new PublicationPreviewEnvelope(1, request, authority, session.connection().revision(), session.connection().commonCheckpointId(), baseline, projected, remote, changes, losses, "unsigned");
                var envelope = new PublicationPreviewEnvelope(1, request, authority, unsigned.connectionRevision(), unsigned.commonCheckpointId(), baseline, projected, remote, changes, losses, digests.previewFingerprint(unsigned));
                return session.publications().publicationPreview(request.operationId(), envelope);
            }));
        } catch (RuntimeException failure) {
            store.locked(context, connectionId, session -> {
                session.fetchFailed(request.operationId(), "PUBLICATION_FETCH_FAILED");
                return null;
            });
            throw IntegrationProblem.conflict("PUBLICATION_FETCH_FAILED");
        }
    }

    public PublicationOperation publication(RepositoryContext context, UUID connectionId, UUID operationId) {
        local.authorize(context, false, store.read(context, connectionId));
        return store.locked(context, connectionId, session -> session.publications().publication(operationId));
    }

    public IntegrationDomainAdapter.EndpointIndex endpointOptions(RepositoryContext context, UUID connectionId, UUID operationId) {
        local.authorize(context, true, null);
        return store.locked(context, connectionId, session -> local.locked(context, before -> {
            var preview = session.publications().preview(operationId);
            expect(preview.request().expected(), local.domain.snapshot(context, session.connection(), session.publications().identities(operationId), before).state());
            var candidates = new TreeMap<>(PublicationDigests.items(preview.localDocument()));
            candidates.putAll(PublicationDigests.items(preview.remoteSnapshot().document()));
            var mappings = exportMappings(session.connection(), local.domain.snapshot(context, session.connection(), session.publications().identities(operationId), before), before, preview.localDocument(), session.publications().identities(operationId));
            var planningDsl = session.connection().projectId() == null ? before.dsl() : local.domain.portfolioContribution(context).apply(before.dsl());
            var plans = local.domain.planRequirements(context, session.connection(), candidates, mappings, planningDsl);
            return local.domain.indexEndpoints(session.connection(), candidates, mappings, plans);
        }));
    }

    public PublicationOperation publish(RepositoryContext context, UUID connectionId, PublicationReview review) {
        return publish(context, connectionId, review, new InvocationBudget());
    }

    private PublicationOperation publish(RepositoryContext context, UUID connectionId, PublicationReview review, InvocationBudget budget) {
        local.authorize(context, true, null);
        UUID id = review.review().operationId();
        var connection = store.read(context, connectionId);
        var envelope = store.locked(context, connectionId, session -> session.publications().preview(id));
        if (envelope == null) {
            throw IntegrationProblem.conflict("PUBLICATION_PREVIEW_REQUIRED");
        }
        var connector = verified(context, connection, envelope.context(), envelope.request().scope(), envelope.request().mode());
        var caps = connector.publicationCapabilities(envelope.context(), envelope.request().scope());
        store.locked(context, connectionId, session -> {
            var previous = session.publications().plan(id);
            if (previous != null) {
                if (!previous.reviewFingerprint().equals(digests.fingerprint(review))) {
                    throw IntegrationProblem.conflict("REVIEW_ID_REUSED");
                }
                if (session.publications().publication(id).phase() != PublicationPhase.LOCAL_APPLY_PENDING) {
                    return null;
                }
            }
            return local.locked(context, before -> {
                local.domain.lockProject(context, connection.projectId());
                var current = local.domain.snapshot(context, connection, session.publications().identities(id), before);
                expect(envelope.request().expected(), current.state());
                var mappings = exportMappings(connection, current, before, envelope.localDocument(), session.publications().identities(id));
                var candidates = selected(envelope, review);
                String planningDsl = connection.projectId() == null ? before.dsl() : local.domain.portfolioContribution(context).apply(before.dsl());
                var requirementPlans = local.domain.planRequirements(context, connection, candidates, mappings, planningDsl);
                var endpoints = local.domain.indexEndpoints(connection, candidates, mappings, requirementPlans);
                var planner = new PublicationPlanner(digests, policy, caps, new PublicationMappings(envelope.context(), envelope.request().scope(), endpoints));
                var plan = planner.plan(envelope, review);
                var target = PublicationDigests.items(plan.localTarget());
                boolean changed = !digests.semantic(plan.localBefore()).equals(digests.semantic(plan.localTarget()));
                if (changed) {
                    connector.validateInboundSelection(new OutboundRequest(plan.context(), plan.localTarget(), plan.remoteBefore().revision()));
                }
                List<ArchitectureCommand> commands = new ArrayList<>();
                if (changed && local.domain.supportsArchitecture(connection)) {
                    commands.addAll(local.domain.architectureCommands(connection, planningDsl, PublicationDigests.items(plan.localBefore()), target, mappings, endpoints, Map.of(), review.review().rationale()));
                }
                if (changed) {
                    local.domain.validateCompletePlan(planningDsl, commands, requirementPlans);
                }
                if (plan.mode() == PublicationMode.PUSH) {
                    local.requireExact(context, connection, session.publications().identities(id), current.state(), true);
                }
                session.publications().beginPublication(review, plan);
                var known = new TreeMap<String, Identity>();
                mappings.forEach(i -> known.put(i.externalId(), i));
                var staged = new ArrayList<StagedBinding>();
                var remoteTarget = PublicationDigests.items(plan.remoteTarget());
                var keys = new TreeSet<>(target.keySet());
                keys.addAll(known.keySet());
                for (String key : keys) {
                    Artifact value = target.get(key);
                    var prior = known.get(key);
                    Artifact remote = remoteTarget.get(key);
                    String business = prior == null ? local.domain.businessId(connection, null, value) : prior.businessIdentity();
                    Long requirement = prior == null ? null : prior.requirementId();
                    if (changed && ((value != null && value.kind() == ArtifactKind.REQUIREMENT) || (value == null && prior != null && prior.internal() != null && prior.internal().kind() == ArtifactKind.REQUIREMENT))) {
                        var applied = local.domain.applyRequirement(context, connection, value, prior, review.review().rationale());
                        business = applied.businessIdentity();
                        requirement = applied.requirementId();
                    } else if (value != null && value.kind() == ArtifactKind.RELATION) {
                        business = local.domain.relationBusinessId(value, endpoints, null);
                    }
                    staged.add(new StagedBinding(value != null ? value.id() : prior.internal().id(), business, requirement, remote == null && prior != null ? prior.external() : remote, value == null && prior != null ? prior.internal() : value, value == null));
                }
                if (changed) {
                    local.domain.refreshIntegrationRequirements(context, connection.projectId());
                }
                State accepted = before.state();
                if (changed) {
                    try {
                        accepted = local.editor.acceptIntegration(context, before.state(), metadata(id, review.review().rationale()), plan.reviewFingerprint(), commands, connection.projectId() == null ? null : local.domain.portfolioContribution(context), checkpointMetadata(id, review.review().rationale()));
                    } catch (IOException failure) {
                        throw IntegrationProblem.conflict("PUBLICATION_LOCAL_UNAVAILABLE");
                    }
                }
                var stagedSnapshot = local.domain.snapshot(context, connection, mappings, before);
                var state = stagedSnapshot.state();
                var result = new InternalState(context.repositoryId(), accepted.workspaceScopeKey(), context.branch(), accepted.commitId(), accepted.semanticRevision(), connection.projectId(), state.projectFingerprint());
                session.publications().stagePublicationLocal(id, result, plan.localTarget(), staged);
                return null;
            });
        });
        return resume(context, connectionId, id, budget);
    }

    public PublicationOperation retryPublication(RepositoryContext context, UUID connectionId, UUID id) {
        return resume(context, connectionId, id, new InvocationBudget());
    }

    private PublicationOperation resume(RepositoryContext context, UUID connectionId, UUID id, InvocationBudget budget) {
        local.authorize(context, true, null);
        var operation = publication(context, connectionId, id);
        if (operation.phase() == PublicationPhase.COMPLETED || budget.expired()) {
            return operation;
        }
        var plan = store.locked(context, connectionId, session -> session.publications().plan(id));
        if (plan == null) {
            var request = store.locked(context, connectionId, session -> session.publications().request(id));
            return preview(context, connectionId, request, null, budget);
        }
        var connection = store.read(context, connectionId);
        var connector = verified(context, connection, plan.context(), plan.capabilities().scope(), plan.mode());
        if (!plan.capabilities().equals(connector.publicationCapabilities(plan.context(), plan.capabilities().scope()))) {
            throw IntegrationProblem.conflict("PUBLICATION_CONFIGURATION_CHANGED");
        }
        if (operation.phase() == PublicationPhase.LOCAL_APPLY_PENDING) {
            return publish(context, connectionId, store.locked(context, connectionId, session -> session.publications().review(id)), budget);
        }
        if (operation.phase() == PublicationPhase.LOCAL_CHECKPOINT_PENDING) {
            if (budget.expired()) {
                return operation;
            }
            try {
                var state = operation.localCheckpoint();
                var checkpointCommand = checkpointMetadata(id, store.locked(context, connectionId, session -> session.publications().review(id)).review().rationale());
                // A timed-out writer keeps its deterministic editor intent. A later invocation
                // replays that durable result; this caller never cancels or joins the writer.
                var checkpoint = boundedCall(() -> {
                    try {
                        return local.editor.checkpoint(context, new State(state.workspaceScopeKey(), state.commitId(), state.semanticRevision()), checkpointCommand);
                    } catch (IOException failure) {
                        throw new UncheckedIOException(failure);
                    }
                }, budget);
                var result = new InternalState(state.repositoryId(), state.workspaceScopeKey(), state.branch(), checkpoint.state().commitId(), checkpoint.state().semanticRevision(), state.projectId(), state.projectFingerprint());
                store.locked(context, connectionId, session -> {
                    if (session.publications().publication(id).phase() == PublicationPhase.LOCAL_CHECKPOINT_PENDING) {
                        session.publications().publicationLocalCheckpointed(id, result);
                    }
                    return null;
                });
            } catch (RuntimeException failure) {
                boolean deferred = failure instanceof UncheckedIOException || failure instanceof IntegrationProblem problem && "PUBLICATION_DEADLINE".equals(problem.code());
                store.locked(context, connectionId, session -> {
                    // Another retry may already have completed this same deterministic intent.
                    if (session.publications().publication(id).phase() == PublicationPhase.LOCAL_CHECKPOINT_PENDING) {
                        if (deferred) {
                            session.publications().checkpointDeferred(id);
                        } else {
                            session.publications().requirePublicationReconciliation(id, "PUBLICATION_CHECKPOINT_CONFLICT");
                        }
                    }
                    return null;
                });
                return publication(context, connectionId, id);
            }
        }
        while (!budget.expired()) {
            operation = publication(context, connectionId, id);
            if (operation.phase() == PublicationPhase.VERIFY_PENDING) {
                verify(context, connectionId, id, plan, connector, budget);
                break;
            }
            if (!operation.allowedActions().contains(PublicationAction.RETRY)) {
                break;
            }
            verified(context, store.read(context, connectionId), plan.context(), plan.capabilities().scope(), plan.mode());
            if (budget.expired()) {
                break;
            }
            PublicationClaim claim;
            try {
                claim = store.locked(context, connectionId, session -> session.publications().claimPublicationAttempt(id, clock.instant()));
            } catch (IntegrationProblem moved) {
                store.locked(context, connectionId, session -> {
                    session.publications().requirePublicationReconciliation(id, moved.code());
                    return null;
                });
                break;
            }
            if (claim == null) {
                break;
            }
            try {
                Boolean proceed = boundedCall(() -> {
                    try {
                        if (claim.attemptKind() == AttemptKind.LOOKUP) {
                            var r = claim.frozenRequest();
                            var query = new PublicationReceiptQuery(r.provider(), r.scope(), id, r.item().itemId(), r.item().idempotencyKey(), r.requestFingerprint());
                            var lookup = connector.lookupPublicationReceipt(plan.context(), query);
                            return store.locked(context, connectionId, session -> {
                                session.publications().recordPublicationLookup(claim, lookup);
                                boolean reconciliation = session.publications().publication(id).phase() == PublicationPhase.RECONCILIATION_REQUIRED;
                                // A nonterminal FOUND or lookup-only NOT_FOUND ends this invocation.
                                // Neither fences an older SEND; await later authoritative evidence.
                                return lookup.state() == LookupState.FOUND && lookup.receipt().terminal()
                                        || lookup.state() == LookupState.NOT_FOUND && !reconciliation;
                            });
                        }
                        var receipt = connector.publishItem(plan.context(), claim.frozenRequest());
                        validator.validate(claim.frozenRequest(), receipt);
                        store.locked(context, connectionId, session -> {
                            session.publications().recordPublicationReceipt(claim, receipt);
                            return null;
                        });
                        return receipt.state() == ReceiptState.APPLIED;
                    } catch (RuntimeException failure) {
                        store.locked(context, connectionId, session -> {
                            session.publications().recordPublicationUnknown(claim, failure instanceof IntegrationProblem problem ? problem.code() : "PUBLICATION_OUTCOME_UNKNOWN");
                            return null;
                        });
                        return false;
                    }
                }, budget);
                if (!proceed) {
                    break;
                }
            } catch (RuntimeException timeout) {
                store.locked(context, connectionId, session -> {
                    session.publications().recordPublicationUnknown(claim, "PUBLICATION_DEADLINE");
                    return null;
                });
                break;
            }
        }
        return publication(context, connectionId, id);
    }

    public PublicationOperation previewPublicationReconciliation(RepositoryContext context, UUID connectionId, UUID id, ReconciliationPreviewRequest request) {
        var budget = new InvocationBudget();
        if (!id.equals(request.predecessorOperationId())) {
            throw IntegrationProblem.conflict("PUBLICATION_PREDECESSOR_CHANGED");
        }
        var operation = publication(context, connectionId, id);
        if (!operation.allowedActions().contains(PublicationAction.RECONCILE)) {
            throw IntegrationProblem.conflict("PUBLICATION_UNKNOWN_UNRESOLVED");
        }
        return preview(context, connectionId, request.request(), id, budget);
    }

    private void verify(RepositoryContext context, UUID connectionId, UUID id, PublicationPlan plan, ConditionalPublicationConnector connector, InvocationBudget budget) {
        if (!digests.converged(plan)) {
            store.locked(context, connectionId, session -> {
                session.publications().incomplete(id);
                return null;
            });
            return;
        }
        var op = publication(context, connectionId, id);
        String revision = op.items().isEmpty() ? plan.remoteBefore().revision() : op.items().getLast().receipt().afterScopeRevision();
        try {
            var snapshot = boundedCall(() -> connector.readPublicationScope(plan.context(), plan.capabilities().scope(), revision), budget);
            var receipts = op.items().stream().map(PublicationItemOutcome::receipt).toList();
            var completion = new PublicationCompletion(1, id, plan.planFingerprint(), receipts, snapshot, op.localCheckpoint(), digests.semantic(plan.localTarget()));
            validator.validateCompletion(plan, completion);
            var baseline = new CommonBaseline(1, plan.capabilities().provider(), plan.capabilities().scope(), plan.context().profile(), plan.context().profileVersion(), plan.localTarget(), snapshot.document(), completion.commonSemanticFingerprint());
            store.locked(context, connectionId, session -> {
                session.publications().completePublication(id, completion, baseline);
                return null;
            });
        } catch (RuntimeException failure) {
            store.locked(context, connectionId, session -> {
                session.publications().requirePublicationReconciliation(id, "PUBLICATION_VERIFICATION_REQUIRED");
                return null;
            });
        }
    }

    private ConditionalPublicationConnector verified(RepositoryContext context, Connection connection, IntegrationContext authority, PublicationScope scope, PublicationMode mode) {
        local.authorize(context, true, connection);
        policy.requireAuthority(authority, mode);
        var value = connectors.require(connection.connectorId(), connection.profileVersion());
        if (!(value instanceof ConditionalPublicationConnector conditional)) {
            throw IntegrationProblem.conflict("PUBLICATION_GUARANTEES_UNVERIFIED");
        }
        var capabilities = conditional.publicationCapabilities(authority, scope);
        policy.requireVerified(authority, conditional, capabilities);
        if (!capabilities.scope().equals(scope)) {
            throw IntegrationProblem.conflict("PUBLICATION_SCOPE_CHANGED");
        }
        return conditional;
    }

    private Map<String, Artifact> selected(PublicationPreviewEnvelope envelope, PublicationReview review) {
        var result = new TreeMap<>(PublicationDigests.items(envelope.localDocument()));
        for (var c : envelope.changes()) {
            var resolution = review.resolutions().get(c.id());
            if (resolution == null) {
                continue;
            }
            Artifact value = switch(resolution) {
                case KEEP_LOCAL, SKIP ->
                    c.local();
                case TAKE_REMOTE ->
                    c.remote();
                case MERGE ->
                    c.merged();
            };
            if (value != null) {
                value = IntegrationMappingFields.remap(value, review.review().mappings().get(c.id()));
            }
            if (value == null) {
                result.remove(c.id());
            } else {
                result.put(c.id(), value);
            }
        }
        return result;
    }

    private List<Identity> exportMappings(Connection connection, IntegrationDomainAdapter.Snapshot current, WorkspaceDocument document, ExchangeDocument projection, List<Identity> existing) {
        var values = new TreeMap<String, Identity>();
        existing.forEach(i -> values.put(i.externalId(), i));
        var items = PublicationDigests.items(projection);
        local.domain.exportBindings(connection, current, document, items).forEach((key, b) -> {
            var artifact = items.get(key);
            if (artifact != null) {
                values.putIfAbsent(key, new Identity(key, b.businessIdentity(), b.requirementId(), null, digests.semantic(artifact), artifact, artifact, null, false));
            }
        });
        return List.copyOf(values.values());
    }

    private static IntegrationContext authority(RepositoryContext context, Connection c, InternalState state) {
        return new IntegrationContext(c.id(), c.authority(), c.externalScope(), state, context.username(), c.connectorId(), c.profileVersion());
    }

    private static void expect(InternalState expected, InternalState actual) {
        if (!Objects.equals(expected, actual)) {
            throw IntegrationProblem.conflict("PUBLICATION_LOCAL_MOVED");
        }
    }

    private static CommandMetadata metadata(UUID id, String rationale) {
        return new CommandMetadata(id, id, id, rationale);
    }

    private static CommandMetadata checkpointMetadata(UUID id, String rationale) {
        return new CommandMetadata(UUID.nameUUIDFromBytes((id + ":checkpoint").getBytes(StandardCharsets.UTF_8)), id, id, rationale);
    }

    /**
     * One external invocation, including internal delegation and local work.
     */
    private final class InvocationBudget {

        private final long startedNanos = System.nanoTime();

        private final Instant deadline = clock.instant().plusSeconds(INVOCATION_SECONDS);

        long remainingNanos() {
            long wallRemaining = Duration.between(clock.instant(), deadline).toNanos();
            long monotonicRemaining = TimeUnit.SECONDS.toNanos(INVOCATION_SECONDS) - (System.nanoTime() - startedNanos);
            return Math.max(0, Math.min(wallRemaining, monotonicRemaining));
        }

        boolean expired() {
            return remainingNanos() == 0;
        }
    }

    private <T> T boundedCall(Supplier<T> effect, InvocationBudget budget) {
        long remaining = budget.remainingNanos();
        if (remaining <= 0) {
            throw IntegrationProblem.conflict("PUBLICATION_DEADLINE");
        }
        var future = new CompletableFuture<T>();
        Thread.ofVirtual().start(() -> {
            try {
                if (budget.expired()) {
                    throw IntegrationProblem.conflict("PUBLICATION_DEADLINE");
                }
                future.complete(effect.get());
            } catch (Throwable failure) {
                future.completeExceptionally(failure);
            }
        });
        try {
            return future.get(budget.remainingNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw IntegrationProblem.conflict("PUBLICATION_DEADLINE");
        } catch (TimeoutException failure) {
            throw IntegrationProblem.conflict("PUBLICATION_DEADLINE");
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (failure.getCause() instanceof Error error) {
                throw error;
            }
            throw IntegrationProblem.conflict("PUBLICATION_EFFECT_UNAVAILABLE");
        }
    }
}
