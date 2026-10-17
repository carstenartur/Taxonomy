package com.taxonomy.analysis.recovery;

import com.taxonomy.dto.*;
import com.taxonomy.workspace.service.WorkspaceContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import java.util.*;

/** Short transactions around checkpoints. Provider I/O is never executed under a database lock. */
@Service
public class AnalysisContinuationStore {
    private static final int MAX_CALLS = 4096;
    private static final int MAX_QUESTION_CHARACTERS = 2_000_000;
    private static final long MAX_RUN_CHARACTERS = 24_000_000;
    // Must exceed the existing evaluator's 30 minute active-work limit. No automatic replay on expiry.
    private static final long CLAIM_MILLIS = 35 * 60 * 1000L;
    private final EntityManager em;
    private final ObjectMapper mapper;
    public AnalysisContinuationStore(EntityManager em, ObjectMapper mapper) { this.em = em; this.mapper = mapper; }
    public record Claim(String id, String token, AnalysisResult completedResult, RelationSearchReport previousRelations,
                        AnalysisResult retainedAnalysis) {
        public Claim(String id, String token, AnalysisResult completedResult, RelationSearchReport previousRelations) {
            this(id, token, completedResult, previousRelations, null);
        }
        public Claim(String id, String token, AnalysisResult completedResult) { this(id, token, completedResult, null); }
    }
    public record Snapshot(AnalysisRecoveryView recovery, AnalysisResult result, AnalysisRequest request, String clusterOperationId) {
        public Snapshot(AnalysisRecoveryView recovery, AnalysisResult result, AnalysisRequest request) { this(recovery, result, request, null); }
    }

    /** Immutable admitted input; reading it neither resumes nor changes an existing run. */
    @Transactional(readOnly = true)
    public Optional<AnalysisRequest> originalRequest(String id, WorkspaceContext scope) {
        requireScope(scope);
        var run = em.find(AnalysisContinuationRun.class, requireId(id));
        if (run == null) return Optional.empty();
        authorize(run, scope);
        return Optional.of(mapper.readValue(run.requestJson, AnalysisRequest.class));
    }

    @Transactional
    public Claim begin(AnalysisRequest request, WorkspaceContext scope, String signature, List<TaxonomyNodeDto> tree) {
        requireScope(scope);
        String id = requireId(request.getContinuationId());
        var run = em.find(AnalysisContinuationRun.class, id, LockModeType.PESSIMISTIC_WRITE);
        String action = Objects.toString(request.getContinuationAction(), "START");
        if (run == null) {
            if (!"START".equals(action)) throw conflict("The continuation no longer exists");
            run = new AnalysisContinuationRun();
            run.id = id; run.username = scope.username(); run.workspaceId = scope.workspaceId();
            run.repositoryId = scope.repositoryId(); run.branchName = scope.currentBranch();
            run.inputHash = signature; run.requestJson = mapper.writeValueAsString(request);
            run.state = "NEW";
            AnalysisResult retained = null;
            if (request.getRelationSourceContinuationId() != null) {
                var source = relationSource(request, scope, LockModeType.PESSIMISTIC_WRITE);
                var duplicate = em.createQuery("select r.id from AnalysisContinuationRun r where r.inputHash=:hash and r.username=:owner and r.workspaceId=:workspace and r.state<>'CANCELLED'", String.class)
                        .setParameter("hash", signature).setParameter("owner", scope.username()).setParameter("workspace", scope.workspaceId())
                        .setMaxResults(1).getResultList();
                if (!duplicate.isEmpty()) throw conflict("This source revision already has a relations continuation: " + duplicate.getFirst());
                var original = mapper.readValue(source.requestJson, AnalysisRequest.class);
                retained = readResult(source);
                request.setRelationSourcePartial(!"SUCCESS".equals(retained.getStatus()) || retained.getAnalysisCoverage().hasOpenEvaluations());
                if (!Objects.equals(original.getBusinessText(), request.getBusinessText())
                        || !(Objects.equals(original.getProvider(), request.getProvider())
                            || Objects.equals(retained.getAnalysisProviderId(), request.getProvider()))
                        || original.getContinuationAuthority() != null
                        && !Objects.equals(original.getContinuationAuthority(), request.getContinuationAuthority())
                        || !Objects.equals(original.getMaxArchitectureNodes(), request.getMaxArchitectureNodes())
                        || !original.getAnalysisScope().taxonomyRoots().equals(request.getAnalysisScope().taxonomyRoots())
                        || !request.getAnalysisScope().includesRelations()
                        || !mapper.writeValueAsString(retained.getTree()).equals(mapper.writeValueAsString(tree)))
                    throw conflict("Relations extension differs from its retained source authority");
            }
            // Seed the original catalogue before any provider call. Cancellation can then
            // reconstruct typed evidence without a worker or a potentially changed catalogue.
            var seed = retained == null ? new AnalysisResult(Map.of(), tree) : retained;
            seed.setAnalysisScope(request.getAnalysisScope());
            seed.setAnalysisReasonLength(request.getAnalysisReasonLength());
            if (retained == null) seed.setStatus("IN_PROGRESS");
            seed.setProvider(request.getProvider());
            run.requestJson = mapper.writeValueAsString(request);
            writeResult(run, seed);
            em.persist(run);
        } else {
            authorize(run, scope);
            var savedRequest = mapper.readValue(run.requestJson, AnalysisRequest.class);
            if (!Objects.equals(savedRequest.getRelationSourceContinuationId(), request.getRelationSourceContinuationId())
                    || !Objects.equals(savedRequest.getRelationSourceVersion(), request.getRelationSourceVersion()))
                throw conflict("This continuation belongs to a different retained source revision");
            if (!run.inputHash.equals(signature)) throw conflict(
                    "Requirement, catalogue, prompt, provider or policy changed; the saved answers cannot be reused in this context");
            expire(run);
            if ("START".equals(action)) {
                if (run.resultJson != null && !"RUNNING".equals(run.state))
                    return new Claim(id, null, withRecovery(readResult(run), run));
                throw conflict("This operation already exists. Observe it instead of starting duplicate work");
            }
            if (!Set.of("PAUSED", "COMPLETED_WITH_GAPS", "STOPPED").contains(run.state))
                throw conflict("The operation is not awaiting a continuation decision");
            if (request.getContinuationVersion() == null || request.getContinuationVersion() != run.version)
                throw conflict("A newer operation revision exists; refresh before deciding");
            if ("CONTINUE".equals(action) && "STOPPED".equals(run.state)
                    && questions(run).stream().noneMatch(q -> "FAILED".equals(q.state))) {
                // No unresolved provider attempt is outstanding. Keep previously skipped areas open.
            } else {
                // Resolve and validate the complete selection before modifying any row.
                // One explicit decision authorizes only these questions at this exact revision.
                var keys = request.getContinuationQuestions();
                if (keys.isEmpty()) keys = request.getContinuationQuestion() == null
                        ? List.of() : List.of(request.getContinuationQuestion());
                if (keys.isEmpty() || keys.size() > MAX_CALLS || new HashSet<>(keys).size() != keys.size()
                        || keys.stream().anyMatch(key -> key == null || key.isBlank()))
                    throw conflict("Select unique open assessment identities");
                if (request.getContinuationQuestion() != null
                        && (keys.size() != 1 || !keys.getFirst().equals(request.getContinuationQuestion())))
                    throw conflict("Conflicting single and multiple assessment selections");
                if (!Set.of("RETRY", "SKIP").contains(action)) throw conflict("Unsupported continuation decision");
                var selected = new ArrayList<AnalysisQuestionCheckpoint>();
                for (var key : keys) {
                    var question = question(run, key);
                    if (question == null || !Set.of("FAILED", "SKIPPED", "UNASSESSED", "CLARIFICATION").contains(question.state)
                            || "SKIP".equals(action) && "SKIPPED".equals(question.state))
                        throw conflict("The selected assessment is not open for this decision");
                    selected.add(question);
                }
                selected.forEach(question -> question.state = "RETRY".equals(action) ? "READY" : "SKIPPED");
            }
        }
        run.state = "RUNNING"; run.claimToken = UUID.randomUUID().toString();
        run.claimUntil = System.currentTimeMillis() + CLAIM_MILLIS; run.updatedAt = System.currentTimeMillis();
        em.flush();
        var previous = readResult(run);
        var savedRequest = mapper.readValue(run.requestJson, AnalysisRequest.class);
        var previousRelations = previous == null ? null : previous.getRelationSearchReport();
        if (savedRequest.getRelationSourceContinuationId() != null && previous != null) resetRelationAttempt(previous, savedRequest);
        return new Claim(id, run.claimToken, null, previousRelations,
                savedRequest.getRelationSourceContinuationId() == null ? null : previous);
    }

    /** Resolves a new extension or its existing target without changing the source. */
    @Transactional(readOnly = true)
    public Snapshot relationInput(AnalysisRequest request, WorkspaceContext scope) {
        requireScope(scope);
        String id = requireId(request.getContinuationId());
        var existing = em.find(AnalysisContinuationRun.class, id);
        if (existing != null) {
            authorize(existing, scope);
            var saved = mapper.readValue(existing.requestJson, AnalysisRequest.class);
            if (!Objects.equals(saved.getRelationSourceContinuationId(), request.getRelationSourceContinuationId())
                    || !Objects.equals(saved.getRelationSourceVersion(), request.getRelationSourceVersion()))
                throw conflict("This continuation belongs to a different retained source revision");
            return new Snapshot(view(existing), readResult(existing), saved, existing.clusterOperationId);
        }
        var source = relationSource(request, scope, LockModeType.PESSIMISTIC_READ);
        return new Snapshot(view(source), readResult(source), mapper.readValue(source.requestJson, AnalysisRequest.class), source.clusterOperationId);
    }

    @Transactional(readOnly = true)
    public void verifySourceSignature(String id, WorkspaceContext scope, String signature) {
        var source = em.find(AnalysisContinuationRun.class, requireId(id));
        if (source == null) throw notFound();
        authorize(source, scope);
        if (!Objects.equals(signature, source.inputHash))
            throw conflict("Requirement, catalogue, provider or policy changed; saved node evidence cannot authorize relation calls");
    }

    private AnalysisContinuationRun relationSource(AnalysisRequest request, WorkspaceContext scope, LockModeType lock) {
        String sourceId = requireId(request.getRelationSourceContinuationId());
        if (sourceId.equals(request.getContinuationId())) throw conflict("A relations extension requires a new continuation identity");
        var source = em.find(AnalysisContinuationRun.class, sourceId, lock);
        if (source == null) throw notFound();
        authorize(source, scope);
        if (request.getRelationSourceVersion() == null || request.getRelationSourceVersion() != source.version)
            throw conflict("A newer source revision exists; refresh before extending relations");
        var saved = mapper.readValue(source.requestJson, AnalysisRequest.class);
        var result = readResult(source);
        if (!Set.of("COMPLETED", "COMPLETED_WITH_GAPS").contains(source.state)
                || saved.getAnalysisScope().includesRelations() || result == null || result.getTree() == null
                || result.getTree().isEmpty() || result.getAnalysisCoverage() == null
                || !Set.of("SUCCESS", "PARTIAL").contains(result.getStatus()) || result.getRelationSearchReport() != null
                || result.getWarnings().stream().anyMatch(w -> w.startsWith("RESTORED_HISTORICAL_SOURCE_EVIDENCE:")))
            throw conflict("Relations require a completed retained node-only analysis with original catalogue authority; imported or running evidence is unsupported");
        return source;
    }

    @Transactional(readOnly = true)
    public String boundOperation(String id) {
        return em.createQuery("select r.clusterOperationId from AnalysisContinuationRun r where r.id=:id", String.class)
                .setParameter("id", id).getResultList().stream().filter(Objects::nonNull).findFirst().orElse(null);
    }

    /** Joins cluster admission; a dispatch intent cannot exist without this frozen authority. */
    @Transactional
    public void attach(AnalysisContinuationBinding binding, com.taxonomy.analysis.usecase.AnalyzeRequirementCommand command,
            String operationId, String expectedPreviousOperation, String sourceJson, String catalogueHash,
            Collection<com.taxonomy.catalog.snapshot.RootCatalogueSnapshot> roots) {
        var run = em.find(AnalysisContinuationRun.class, binding.id(), LockModeType.PESSIMISTIC_WRITE);
        if (run == null) throw conflict("Continuation no longer exists");
        authorize(run, command.workspaceContext());
        if (!"RUNNING".equals(run.state) || !Objects.equals(run.claimToken, binding.token())
                || run.claimUntil < System.currentTimeMillis() || !Objects.equals(run.inputHash, binding.inputHash()))
            throw conflict("Continuation authority changed before distributed admission");
        if (!Objects.equals(run.clusterOperationId, expectedPreviousOperation))
            throw conflict("Another distributed admission changed the current operation binding");
        var request = mapper.readValue(run.requestJson, AnalysisRequest.class);
        if (!Objects.equals(request.getBusinessText(), command.businessText())
                || !Objects.equals(request.getProvider(), command.provider())
                || request.isIncludeArchitectureView() != command.includeArchitectureView()
                || !Objects.equals(request.getMaxArchitectureNodes(), command.maxArchitectureNodes())
                || !Objects.equals(request.getAnalysisScope(), command.analysisScope())
                || com.taxonomy.analysis.service.AnalysisReasonContext.effective(request.getAnalysisReasonLength())
                    != com.taxonomy.analysis.service.AnalysisReasonContext.effective(command.analysisReasonLength()))
            throw conflict("Distributed command differs from the saved requirement");
        var retained = readResult(run);
        if (request.getRelationSourceContinuationId() != null
                && (command.retainedAnalysis() == null
                || !sameNodeEvidence(retained, command.retainedAnalysis())))
            throw conflict("Distributed relations command changed the retained node evidence");
        if (request.getRelationSourceContinuationId() == null && command.retainedAnalysis() != null)
            throw conflict("Retained evidence requires an explicit relations source");
        if (!AnalysisContinuationCatalogue.matches(retained.getTree(), roots))
            throw conflict("Catalogue changed between continuation capture and distributed admission");
        if (run.clusterSourceJson != null && (!run.clusterSourceJson.equals(sourceJson) || !Objects.equals(run.clusterCatalogueHash, catalogueHash)))
            throw conflict("Frozen source or catalogue changed; start a new continuation");
        run.clusterOperationId = operationId; run.clusterSourceJson = sourceJson; run.clusterCatalogueHash = catalogueHash;
        // Queued and staged cluster work can outlive the finite admission window.
        // Operation state and delivery fencing revoke authority on completion, cancellation or supersession.
        run.claimUntil = Long.MAX_VALUE;
        em.flush();
    }

    @Transactional(readOnly = true)
    public boolean owns(Claim claim, String operationId) {
        return !em.createQuery("select r.id from AnalysisContinuationRun r where r.id=:id and r.claimToken=:token and r.clusterOperationId=:operation", String.class)
                .setParameter("id", claim.id()).setParameter("token", claim.token()).setParameter("operation", operationId).getResultList().isEmpty();
    }

    @Transactional
    public AnalysisCheckpointSession.Checkpoint prepare(Claim claim, AnalysisCheckpointSession.Question request) {
        return prepareQuestion(claim, request, true);
    }
    private AnalysisCheckpointSession.Checkpoint prepareQuestion(Claim claim, AnalysisCheckpointSession.Question request, boolean compatibleLocal) {
        var run = checkpointOwner(claim, true);
        var question = question(run.id(), request.key());
        if (question == null && compatibleLocal) {
            question = compatibleQuestion(run.id(), request);
            if (question != null) {
                request = new AnalysisCheckpointSession.Question(question.questionKey, request.inputHash(), request.provider(), request.nodes(), request.prompt());
            }
        }
        if (question != null) {
            if (!question.inputHash.equals(request.inputHash())) throw conflict("A saved question's input changed");
            if (!Set.of("READY", "SUPERSEDED").contains(question.state)) return checkpoint(question, request);
        } else {
            long count = em.createQuery("select count(q) from AnalysisQuestionCheckpoint q where q.run.id=:id", Long.class)
                    .setParameter("id", run.id()).getSingleResult();
            if (count >= MAX_CALLS) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Continuation checkpoint limit reached; completed answers remain stored");
            question = new AnalysisQuestionCheckpoint();
            question.id = run.id() + ":" + request.key();
            question.run = em.getReference(AnalysisContinuationRun.class, run.id());
            question.questionKey = request.key(); question.inputHash = request.inputHash();
            question.provider = request.provider(); question.nodeCodes = mapper.writeValueAsString(request.nodes());
            question.state = "READY";
            em.persist(question);
        }
        question.attempts++; question.startedAt = System.currentTimeMillis(); question.state = "ATTEMPT";
        String currentNode = String.join(", ", request.nodes());
        if (currentNode.length() > 320) currentNode = currentNode.substring(0, 317) + "...";
        long retainedPrompt = question.prompt == null ? 0 : question.prompt.length();
        long nextPayload = run.payloadCharacters() - retainedPrompt + request.prompt().length();
        String state = "RUNNING";
        if (request.prompt().length() > MAX_QUESTION_CHARACTERS || nextPayload > MAX_RUN_CHARACTERS) {
            question.state = "FAILED";
            question.error = "CHECKPOINT_INPUT_LIMIT: full prompt or total retained text exceeds the bounded checkpoint budget; "
                    + "it was not sent or silently shortened";
            state = "PAUSED"; nextPayload = run.payloadCharacters();
        } else question.prompt = request.prompt();
        updateCheckpointOwner(claim, run, state, nextPayload, currentNode);
        return checkpoint(question, request);
    }

    private AnalysisQuestionCheckpoint compatibleQuestion(String runId, AnalysisCheckpointSession.Question request) {
        var candidates = em.createQuery("select q from AnalysisQuestionCheckpoint q where q.run.id=:id and q.inputHash=:hash and q.provider=:provider", AnalysisQuestionCheckpoint.class)
                .setParameter("id", runId).setParameter("hash", request.inputHash()).setParameter("provider", request.provider()).setMaxResults(2).getResultList();
        if (candidates.size() > 1) throw conflict("Ambiguous saved question scope; use the original distributed context");
        return candidates.size() == 1 && Objects.equals(candidates.getFirst().nodeCodes, mapper.writeValueAsString(request.nodes()))
                && AnalysisCheckpointSession.isRelationQuestion(candidates.getFirst().questionKey) == AnalysisCheckpointSession.isRelationQuestion(request.key())
                ? candidates.getFirst() : null;
    }

    /** A physical question belongs to one captured delivery and one worker session. */
    public record QuestionOwner(String operationId, String taskId, int delivery, String owner, String namespace) {
        public QuestionOwner {
            Objects.requireNonNull(operationId); Objects.requireNonNull(taskId); Objects.requireNonNull(namespace);
            UUID.fromString(owner); if (delivery < 1) throw new IllegalArgumentException("Captured delivery required");
        }
    }

    @Transactional
    public AnalysisCheckpointSession.Checkpoint prepareDistributed(Claim claim, AnalysisCheckpointSession.Question input, QuestionOwner owner) {
        var run = checkpointOwner(claim, true);
        if (!Objects.equals(run.operationId(), owner.operationId())) throw conflict("Question operation binding was superseded");
        String scopedKey = AnalysisCheckpointSession.digest("distributed-question-v1", owner.namespace(), input.key(), input.inputHash());
        if (AnalysisCheckpointSession.isRelationQuestion(input.key())) scopedKey = "R" + scopedKey.substring(1);
        var request = new AnalysisCheckpointSession.Question(scopedKey, input.inputHash(), input.provider(), input.nodes(), input.prompt());
        var existing = question(run.id(), scopedKey);
        // Old in-process answers remain usable only for precisely the same question and input.
        if (existing == null) {
            var legacy = question(run.id(), input.key());
            if (legacy != null && Objects.equals(legacy.inputHash, input.inputHash())
                    && Objects.equals(legacy.provider, input.provider())
                    && Objects.equals(legacy.nodeCodes, mapper.writeValueAsString(input.nodes()))) {
                existing = legacy; request = input;
            }
        }
        if (existing != null && "ATTEMPT".equals(existing.state)) {
            existing.state = "FAILED";
            existing.error = "OUTCOME_UNCERTAIN: another delivery found a committed question START without an accepted outcome; explicit retry or leave-open is required";
            updateCheckpointOwner(claim, run, "PAUSED", run.payloadCharacters(), run.currentNode());
            return checkpoint(existing, request);
        }
        var admitted = prepareQuestion(claim, request, false);
        if ("ATTEMPT".equals(admitted.state())) {
            var question = question(run.id(), request.key());
            question.attemptOwner = owner.owner(); question.clusterOperationId = owner.operationId();
            question.clusterTaskId = owner.taskId(); question.clusterDelivery = owner.delivery();
            em.flush();
        }
        return admitted;
    }

    @Transactional
    public void finishDistributed(Claim claim, AnalysisCheckpointSession.Question request, String state, LlmCallDetail detail, QuestionOwner owner) {
        var run = checkpointOwner(claim, true);
        if (!Objects.equals(run.operationId(), owner.operationId())) throw conflict("Question operation binding was superseded");
        var question = question(claim.id(), request.key());
        if (question == null || !Objects.equals(question.attemptOwner, owner.owner())
                || !Objects.equals(question.clusterOperationId, owner.operationId())
                || !Objects.equals(question.clusterTaskId, owner.taskId())
                || !Objects.equals(question.clusterDelivery, owner.delivery())) throw conflict("Question belongs to another worker delivery");
        finish(claim, request, state, detail);
    }

    /** Redundant corrective questions retain raw evidence but no longer block the current assessment. */
    @Transactional
    public void supersedeEvidence(Claim claim, String kind, String provider,
            java.util.function.Predicate<AnalysisCheckpointSession.Question> covered, QuestionOwner owner) {
        var run = checkpointOwner(claim, true);
        if (owner != null && !Objects.equals(run.operationId(), owner.operationId()))
            throw conflict("Question operation binding was superseded");
        var pending = em.createQuery("select q from AnalysisQuestionCheckpoint q where q.run.id=:id and q.provider=:provider and q.state in ('READY','UNASSESSED','CLARIFICATION','FAILED','SKIPPED')", AnalysisQuestionCheckpoint.class)
                .setParameter("id", run.id()).setParameter("provider", provider).getResultList();
        boolean changed = false;
        for (var saved : pending) {
            if (!AnalysisCheckpointSession.isRelationQuestion(saved.questionKey) || saved.prompt == null) continue;
            var question = AnalysisCheckpointSession.evidenceQuestion(kind, saved.provider,
                    List.of(mapper.readValue(saved.nodeCodes, String[].class)), saved.prompt);
            if (!Objects.equals(saved.inputHash, question.inputHash()) || !covered.test(question)) continue;
            if (owner != null) {
                String scopedKey = "R" + AnalysisCheckpointSession.digest("distributed-question-v1", owner.namespace(),
                        question.key(), question.inputHash()).substring(1);
                if (!saved.questionKey.equals(scopedKey) && !saved.questionKey.equals(question.key())) continue;
            } else if (!saved.questionKey.equals(question.key())) {
                var compatible = compatibleQuestion(run.id(), question);
                if (compatible == null || !compatible.questionKey.equals(saved.questionKey)) continue;
            }
            saved.state = "SUPERSEDED"; changed = true;
        }
        if (changed) updateCheckpointOwner(claim, run, "RUNNING", run.payloadCharacters(), run.currentNode());
    }

    @Transactional
    public void finish(Claim claim, AnalysisCheckpointSession.Question request, String state, LlmCallDetail detail) {
        var run = checkpointOwner(claim, false);
        var question = question(run.id(), request.key());
        if (question == null || !"ATTEMPT".equals(question.state)) throw conflict("Question no longer owns this attempt");
        String json = mapper.writeValueAsString(detail);
        long oldLength = question.detailJson == null ? 0 : question.detailJson.length();
        if (json.length() > MAX_QUESTION_CHARACTERS || run.payloadCharacters() - oldLength + json.length() > MAX_RUN_CHARACTERS) {
            question.state = "FAILED"; question.error = "CHECKPOINT_STORAGE_LIMIT: response was not accepted; prior checkpoints remain intact";
            // Do not pretend a result was durably cached. Signal the pause outside this transaction.
            updateCheckpointOwner(claim, run, "PAUSED", run.payloadCharacters(), run.currentNode());
            return;
        }
        question.detailJson = json; question.state = state;
        question.error = bounded(detail.getError(), 2048);
        updateCheckpointOwner(claim, run, "FAILED".equals(state) ? "PAUSED" : "RUNNING",
                run.payloadCharacters() + json.length() - oldLength, run.currentNode());
    }

    @Transactional(readOnly = true)
    public String state(Claim claim) {
        // Called at every cooperative checkpoint, often several times per question.
        // Read only the current authority, never hydrate the frozen result/catalogue
        // or reuse an entity cached before another transaction cancelled the run.
        return em.createQuery("select r.state from AnalysisContinuationRun r where r.id=:id and r.claimToken=:token", String.class)
                .setParameter("id", claim.id()).setParameter("token", claim.token())
                .getResultList().stream().findFirst().orElse("CANCELLED");
    }

    @Transactional(readOnly = true)
    public String distributedState(Claim claim, String operationId) {
        return em.createQuery("select r.state from AnalysisContinuationRun r where r.id=:id and r.claimToken=:token and r.clusterOperationId=:operation and r.claimUntil>=:now", String.class)
                .setParameter("id", claim.id()).setParameter("token", claim.token()).setParameter("operation", operationId)
                .setParameter("now", System.currentTimeMillis()).getResultList().stream().findFirst().orElse("CANCELLED");
    }

    @Transactional(readOnly = true)
    public RelationSearchReport previousRelations(Claim claim) {
        var rows = em.createQuery("select r.resultJson from AnalysisContinuationRun r where r.id=:id and r.claimToken=:token", String.class)
                .setParameter("id", claim.id()).setParameter("token", claim.token()).getResultList();
        if (rows.isEmpty() || rows.getFirst() == null) return null;
        return mapper.readValue(rows.getFirst(), AnalysisResult.class).getRelationSearchReport();
    }

    @Transactional
    public AnalysisResult complete(Claim claim, AnalysisResult result, List<TaxonomyNodeDto> tree) {
        var run = em.find(AnalysisContinuationRun.class, claim.id(), LockModeType.PESSIMISTIC_WRITE);
        if (run == null || !Objects.equals(run.claimToken, claim.token())) throw conflict("Operation claim was superseded");
        var previous = readResult(run);
        if ("CANCELLED".equals(run.state) && previous != null && previous.getAnalysisCoverage() != null
                && "CANCELLED".equals(previous.getStatus())) {
            // The local search returns its accepted partial report after observing cancellation.
            // Only that report may enrich the canonical cancelled result; node evidence is immutable.
            retainRelationReport(run, previous, result.getRelationSearchReport());
            writeResult(run, previous); em.flush();
            previous.setRecovery(view(run));
            return previous;
        }
        if (previous != null && previous.getTree() != null) tree = previous.getTree();
        AnalysisRequest frozenRequest = mapper.readValue(run.requestJson, AnalysisRequest.class);
        boolean relationsOnly = frozenRequest.getRelationSourceContinuationId() != null;
        if (relationsOnly && previous != null && !sameNodeEvidence(previous, result))
            throw conflict("Relations work cannot replace retained node assessments or explanations");
        AnalysisScope analysisScope = frozenRequest.getAnalysisScope();
        result.setAnalysisScope(analysisScope);
        result.setAnalysisReasonLength(frozenRequest.getAnalysisReasonLength());
        if (previous != null && result.getRelationSearchReport() == null)
            result.setRelationSearchReport(previous.getRelationSearchReport());
        for (var question : questions(run)) if ("ATTEMPT".equals(question.state)) {
            question.state = "FAILED";
            question.error = "OUTCOME_UNCERTAIN: interrupted call; the provider may have processed it";
        }
        var open = openQuestions(run);
        if ("CANCELLED".equals(run.state)) result.setStatus("CANCELLED");
        else if (questions(run).stream().anyMatch(q -> "FAILED".equals(q.state))) run.state = "PAUSED";
        else if ("ERROR".equals(result.getStatus()) || cooperativeInterruption(result.getErrorMessage())
                || result.getRelationSearchReport() != null && cooperativeInterruption(result.getRelationSearchReport().stopReason())) {
            // Runtime guards use these typed-reason prefixes at the existing result boundary.
            // A prior skipped question must not disguise unfinished independent work as complete.
            run.state = "STOPPED";
        } else if (questions(run).stream().anyMatch(q -> "READY".equals(q.state))
                || relationBudgetStopped(result.getRelationSearchReport())) {
            run.state = "STOPPED";
        } else if (!open.isEmpty()) {
            run.state = "COMPLETED_WITH_GAPS"; result.setStatus("PARTIAL");
        } else run.state = "SUCCESS".equals(result.getStatus()) ? "COMPLETED" : "COMPLETED_WITH_GAPS";
        if (run.clusterOperationId != null) {
            var raw = new LinkedHashMap<>(result.getRawScores());
            var reasons = new LinkedHashMap<>(result.getReasons() == null ? Map.<String,String>of() : result.getReasons());
            for (var q : questions(run)) if ("SUCCESS".equals(q.state) && q.detailJson != null
                    && !AnalysisCheckpointSession.isRelationQuestion(q.questionKey)) {
                var detail = mapper.readValue(q.detailJson, LlmCallDetail.class);
                detail.getScores().forEach(raw::putIfAbsent);
                if (detail.getReasons() != null) detail.getReasons().forEach(reasons::putIfAbsent);
            }
            result.setRawScores(raw); result.setReasons(reasons);
        }
        if (!open.isEmpty() && !"CANCELLED".equals(result.getStatus()) && !"ERROR".equals(result.getStatus()))
            result.setStatus("PARTIAL");
        Map<String, String> missing = new LinkedHashMap<>();
        for (var question : open) if (!AnalysisCheckpointSession.isRelationQuestion(question.key()))
            for (String node : question.nodes()) missing.put(node,
                (question.skipped() ? "LEFT_OPEN:" : "FAILED:") + question.key());
        // This is evidence metadata, never a new official catalogue node or an invented score.
        result.setTree(tree);
        if (previous != null && result.getArchitectureView() == null && previous.getArchitectureView() != null) {
            // A cooperative pause skips projection. Keep the last accepted view until projection can run again.
            result.setArchitectureView(previous.getArchitectureView());
            result.getArchitectureView().setRelationSearchReport(result.getRelationSearchReport());
        }
        String interruption = Set.of("PAUSED", "STOPPED", "CANCELLED").contains(run.state)
                ? "INTERRUPTED:" + run.state : null;
        if (relationsOnly && previous != null && previous.getAnalysisCoverage() != null) {
            result.setAnalysisCoverage(previous.getAnalysisCoverage());
            if (!"CANCELLED".equals(result.getStatus()) && (frozenRequest.isRelationSourcePartial()
                    || previous.getAnalysisCoverage().hasOpenEvaluations())) {
                result.setStatus("PARTIAL");
                if ("COMPLETED".equals(run.state)) run.state = "COMPLETED_WITH_GAPS";
            }
        } else result.setAnalysisCoverage(AnalysisCoverage.derive(analysisScope.selectedTree(tree), result.getRawScores(), result.getScores(), missing, interruption));
        if (frozenRequest.isIncludeArchitectureView()) {
            result.setArchitectureView(com.taxonomy.analysis.usecase.PartialArchitectureProjection.complete(
                    result, result.getArchitectureView(), frozenRequest.getMaxArchitectureNodes()));
        }
        if (result.getArchitectureView() != null) {
            var view = result.getArchitectureView(); view.setAnalysisCoverage(result.getAnalysisCoverage());
            view.setAnalysisStatus(result.getStatus());
            if (!open.isEmpty()) {
                var notes = new ArrayList<>(view.getNotes() == null ? List.<String>of() : view.getNotes());
                notes.add("PARTIAL ANALYSIS / TEILANALYSE: " + open.size()
                        + " unassessed question(s); missing assessments are not negative findings. "
                        + String.join("; ", missing.keySet()));
                view.setNotes(notes);
            }
        }
        run.claimUntil = 0; run.updatedAt = System.currentTimeMillis(); em.flush();
        result.setRecovery(view(run));
        // Tree is retained: restoring against a later catalogue must not relabel old evidence.
        writeResult(run, result); em.flush();
        result.setRecovery(view(run));
        return result;
    }

    private static boolean relationBudgetStopped(RelationSearchReport report) {
        if (report == null) return false;
        var reasons = Set.of("CALL_BUDGET", "SOURCE_LIMIT", "WORK_LIMIT");
        return report.result().unfinished().stream().anyMatch(item -> reasons.contains(item.reason()))
                || report.warnings().stream().anyMatch(warning -> reasons.stream().anyMatch(warning::startsWith));
    }

    @Transactional
    public Snapshot read(String id, WorkspaceContext scope) {
        var run = scoped(id, scope); expire(run); em.flush();
        return new Snapshot(view(run), readResult(run), mapper.readValue(run.requestJson, AnalysisRequest.class), run.clusterOperationId);
    }
    @Transactional
    public Snapshot cancel(String id, WorkspaceContext scope) {
        return cancelLocked(scoped(id, scope), null, null);
    }
    /** Cluster cancellation supplies only its already committed aggregate relation evidence. */
    @Transactional
    public Snapshot cancel(String id, WorkspaceContext scope, RelationSearchReport report) {
        var run = scoped(id, scope);
        var result = readResult(run);
        if (result != null) { retainRelationReport(run, result, report); writeResult(run, result); }
        return cancelLocked(run, null, null);
    }
    private void retainRelationReport(AnalysisContinuationRun run, AnalysisResult result, RelationSearchReport report) {
        if (report == null) return;
        var request = mapper.readValue(run.requestJson, AnalysisRequest.class);
        if (!Objects.equals(report.originalSha256(), com.taxonomy.analysis.dag.RequirementReference.sha256(request.getBusinessText())))
            throw conflict("Relation evidence belongs to a different requirement");
        result.setRelationSearchReport(report);
        if (result.getArchitectureView() != null) result.getArchitectureView().setRelationSearchReport(report);
    }
    /** Retry only resets extension-attempt diagnostics; original node notes and gaps remain authoritative. */
    private void resetRelationAttempt(AnalysisResult result, AnalysisRequest request) {
        var report = result.getRelationSearchReport();
        var relationWarnings = new HashSet<String>();
        if (report != null) {
            relationWarnings.addAll(report.warnings());
            report.result().unfinished().stream().filter(item -> !item.question().isBlank())
                    .map(item -> item.sourceId() + ": " + item.question()).forEach(relationWarnings::add);
        }
        result.setWarnings(result.getWarnings().stream().filter(warning -> !relationAttemptMessage(warning)
                && !relationWarnings.contains(warning)).collect(java.util.stream.Collectors.toCollection(ArrayList::new)));
        if (relationAttemptMessage(result.getErrorMessage())
                || report != null && Objects.equals(result.getErrorMessage(), report.stopReason())) result.setErrorMessage(null);
        result.setStatus(request.isRelationSourcePartial() ? "PARTIAL" : "SUCCESS");
    }
    private boolean relationAttemptMessage(String message) {
        return message != null && (message.startsWith("RELATION_SEARCH_PARTIAL:") || message.startsWith("RELATION_EVALUATION_FAILED:"));
    }
    private boolean sameNodeEvidence(AnalysisResult expected, AnalysisResult offered) {
        return expected != null && offered != null && expected.getRawScores().equals(offered.getRawScores())
                && expected.getScores().equals(offered.getScores())
                && expected.getReasons().equals(offered.getReasons())
                && Objects.equals(expected.getScoreSemanticsFingerprintSha256(), offered.getScoreSemanticsFingerprintSha256())
                && mapper.writeValueAsString(expected.getTree()).equals(mapper.writeValueAsString(offered.getTree()));
    }

    /** Legacy in-flight rows can recover the tree only after its full semantic identity is verified. */
    @Transactional
    public Snapshot cancel(String id, WorkspaceContext scope, List<TaxonomyNodeDto> tree, String signature) {
        return cancelLocked(scoped(id, scope), tree, signature);
    }
    private Snapshot cancelLocked(AnalysisContinuationRun run, List<TaxonomyNodeDto> fallbackTree, String signature) {
        var result = readResult(run);
        if (!"COMPLETED".equals(run.state) && (!"CANCELLED".equals(run.state)
                || result == null || result.getAnalysisCoverage() == null)) {
            if (result == null || result.getTree() == null) {
                if (fallbackTree == null || !Objects.equals(signature, run.inputHash))
                    throw conflict("The original catalogue snapshot is unavailable; restore its matching context before deciding");
                result = new AnalysisResult(Map.of(), fallbackTree);
                result.setAnalysisReasonLength(mapper.readValue(run.requestJson, AnalysisRequest.class).getAnalysisReasonLength());
            }
            var raw = new LinkedHashMap<>(result.getRawScores());
            var reasons = new LinkedHashMap<>(result.getReasons() == null ? Map.<String,String>of() : result.getReasons());
            // The run lock serializes this snapshot with finish(). An accepted cancellation
            // includes every earlier committed success and admits no later provider answer.
            for (var question : questions(run)) {
                if ("SUCCESS".equals(question.state) && question.detailJson != null
                        && !AnalysisCheckpointSession.isRelationQuestion(question.questionKey)) {
                    var detail = mapper.readValue(question.detailJson, LlmCallDetail.class);
                    raw.putAll(detail.getScores());
                    if (detail.getReasons() != null) reasons.putAll(detail.getReasons());
                    if (detail.getProvider() != null) result.setProvider(detail.getProvider());
                }
            }
            result.setRawScores(raw); result.setReasons(reasons);
            result.setErrorMessage("CANCELLED: completed question evidence preserved; remaining assessments are unknown");
            run.state = "CANCELLED";
            // complete() remains inside this short transaction and marks unfinished attempts
            // uncertain, derives coverage from the frozen tree and persists the entire result.
            result = complete(new Claim(run.id, run.claimToken, null), result, result.getTree());
        }
        return new Snapshot(view(run), result, mapper.readValue(run.requestJson, AnalysisRequest.class), run.clusterOperationId);
    }
    @Transactional
    public LlmCallDetail detail(String id, String key, WorkspaceContext scope) {
        var run = scoped(id, scope);
        var question = question(run, key);
        if (question == null) throw notFound();
        var detail = question.detailJson == null ? new LlmCallDetail()
                : mapper.readValue(question.detailJson, LlmCallDetail.class);
        detail.setPrompt(question.prompt);
        if (detail.getError() == null) detail.setError(question.error);
        return detail;
    }
    @Transactional
    public void interrupted(Claim claim) {
        var run = em.find(AnalysisContinuationRun.class, claim.id(), LockModeType.PESSIMISTIC_WRITE);
        if (run != null && Objects.equals(run.claimToken, claim.token()) && "RUNNING".equals(run.state)) {
            run.state = "STOPPED"; run.claimUntil = 0; run.updatedAt = System.currentTimeMillis();
            markUncertain(run);
        }
    }
    private AnalysisContinuationRun scoped(String id, WorkspaceContext scope) {
        requireScope(scope);
        var run = em.find(AnalysisContinuationRun.class, requireId(id), LockModeType.PESSIMISTIC_WRITE);
        if (run == null) throw notFound(); authorize(run, scope); return run;
    }
    private record CheckpointOwner(String id, long version, long payloadCharacters, String currentNode, String operationId) { }
    private CheckpointOwner checkpointOwner(Claim claim, boolean requireUnexpired) {
        // The same row lock as cancellation/completion, but no result/request LOB hydration.
        var rows = em.createQuery("select r.version, r.payloadCharacters, r.currentNode, r.state, r.claimToken, r.claimUntil, r.clusterOperationId "
                        + "from AnalysisContinuationRun r where r.id=:id", Object[].class)
                .setParameter("id", claim.id()).setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList();
        if (rows.isEmpty()) throw conflict("Operation no longer owns its execution claim");
        Object[] row = rows.getFirst();
        if (!Objects.equals(row[4], claim.token()) || !"RUNNING".equals(row[3])
                || (requireUnexpired && System.currentTimeMillis() > ((Number) row[5]).longValue()))
            throw conflict("Operation no longer owns its execution claim");
        return new CheckpointOwner(claim.id(), ((Number) row[0]).longValue(),
                ((Number) row[1]).longValue(), (String) row[2], row.length > 6 ? (String) row[6] : null);
    }
    private void updateCheckpointOwner(Claim claim, CheckpointOwner run, String state, long payload, String currentNode) {
        // Commit the question and its bookkeeping atomically. Bulk updates explicitly advance
        // the same optimistic version used by decisions; the locked row and token stay bound.
        em.flush();
        int updated = em.createQuery("update AnalysisContinuationRun r set r.state=:state, r.payloadCharacters=:payload, "
                        + "r.currentNode=:node, r.updatedAt=:now, r.version=r.version+1 "
                        + "where r.id=:id and r.claimToken=:token and r.version=:version and r.state='RUNNING'")
                .setParameter("state", state).setParameter("payload", payload).setParameter("node", currentNode)
                .setParameter("now", System.currentTimeMillis()).setParameter("id", run.id())
                .setParameter("token", claim.token()).setParameter("version", run.version()).executeUpdate();
        if (updated != 1) throw conflict("Question claim was superseded");
    }
    private AnalysisQuestionCheckpoint question(AnalysisContinuationRun run, String key) {
        return question(run.id, key);
    }
    private AnalysisQuestionCheckpoint question(String runId, String key) {
        return key == null ? null : em.find(AnalysisQuestionCheckpoint.class, runId + ":" + key);
    }
    private AnalysisCheckpointSession.Checkpoint checkpoint(AnalysisQuestionCheckpoint q, AnalysisCheckpointSession.Question input) {
        return new AnalysisCheckpointSession.Checkpoint(new AnalysisCheckpointSession.Question(q.questionKey, input.inputHash(), input.provider(), input.nodes(), input.prompt()), q.state,
                q.detailJson == null ? null : mapper.readValue(q.detailJson, LlmCallDetail.class));
    }
    private List<AnalysisQuestionCheckpoint> questions(AnalysisContinuationRun run) {
        return em.createQuery("select q from AnalysisQuestionCheckpoint q where q.run.id=:id order by q.startedAt, q.questionKey", AnalysisQuestionCheckpoint.class)
                .setParameter("id", run.id).getResultList();
    }
    private List<AnalysisRecoveryView.OpenQuestion> openQuestions(AnalysisContinuationRun run) {
        return questions(run).stream().filter(q -> Set.of("FAILED", "SKIPPED", "UNASSESSED", "CLARIFICATION").contains(q.state))
                .map(q -> new AnalysisRecoveryView.OpenQuestion(q.questionKey,
                        List.of(mapper.readValue(q.nodeCodes, String[].class)), q.error, q.attempts,
                        "SKIPPED".equals(q.state), q.error != null && q.error.startsWith("OUTCOME_UNCERTAIN"))).toList();
    }
    private AnalysisRecoveryView view(AnalysisContinuationRun run) {
        var all = questions(run);
        int complete = (int) all.stream().filter(q -> "SUCCESS".equals(q.state)).count();
        return new AnalysisRecoveryView(run.id, run.version, run.state, complete,
                all.stream().mapToInt(q -> q.attempts).sum(), run.currentNode, openQuestions(run));
    }
    private void writeResult(AnalysisContinuationRun run, AnalysisResult result) {
        String json = mapper.writeValueAsString(result);
        if (json.length() > 12_000_000) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "Analysis result exceeds the durable result limit; individual answers remain stored");
        run.resultJson = json;
    }
    private AnalysisResult readResult(AnalysisContinuationRun run) {
        return run.resultJson == null ? null : withRecovery(mapper.readValue(run.resultJson, AnalysisResult.class), run);
    }
    private AnalysisResult withRecovery(AnalysisResult result, AnalysisContinuationRun run) {
        result.setRecovery(view(run)); return result;
    }
    private static boolean cooperativeInterruption(String message) {
        return message != null && (message.startsWith("MEMORY_PRESSURE:")
                || message.startsWith("TIME_LIMIT:") || message.startsWith("CANCELLED:"));
    }
    private void expire(AnalysisContinuationRun run) {
        if ("RUNNING".equals(run.state) && run.claimUntil < System.currentTimeMillis()) {
            run.claimToken = null; run.state = "STOPPED"; markUncertain(run);
        }
    }
    private void markUncertain(AnalysisContinuationRun run) {
        for (var q : questions(run)) if ("ATTEMPT".equals(q.state)) {
            q.state = "FAILED"; q.error = "OUTCOME_UNCERTAIN: interrupted attempt; the provider may have processed it. Explicit retry or leave-open is required";
            run.state = "PAUSED";
        }
    }
    private static void requireScope(WorkspaceContext scope) {
        if (scope == null || scope.username() == null || scope.username().isBlank()
                || scope.workspaceId() == null || scope.workspaceId().isBlank()
                || scope.currentBranch() == null || scope.currentBranch().isBlank())
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "An owned isolated workspace is required");
    }
    private static void authorize(AnalysisContinuationRun run, WorkspaceContext scope) {
        if (!run.username.equals(scope.username()) || !run.workspaceId.equals(scope.workspaceId())
                || !run.branchName.equals(scope.currentBranch()) || !run.repositoryId.equals(scope.repositoryId())) throw notFound();
    }
    private static String requireId(String id) {
        try { if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException(); return id; }
        catch (RuntimeException invalid) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A canonical continuation UUID is required"); }
    }
    private static String bounded(String text, int limit) { return text == null ? null : text.substring(0, Math.min(text.length(), limit)); }
    private static ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "Analysis continuation not found"); }
    private static ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
}
