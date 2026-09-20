# Conditional publication and recovery implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete the shared reviewed Push/Synchronize contract, durable item outcomes and restart recovery while rejecting unverified PCS writes.

**Architecture:** A directed three-way planner consumes immutable local/remote/common snapshots. The existing scoped integration journal records frozen plans, per-item requests, attempts and verified receipts; HTTP and Git execute outside relational transactions. Verified full-scope convergence alone advances a COMMON checkpoint, distinct from inbound observation.

**Tech Stack:** Java 21 target (local verification JDK 25), Spring, JPA, existing workspace/portfolio integration ports, Java HTTP contract provider, JUnit, cross-JVM persistence fixtures and browser acceptance.

**Spec:** `docs/superpowers/specs/2026-09-20-conditional-publication.md`

## Global Constraints

- Preserve the repository's Java 21 language/API target; local JDK 25 is only verification infrastructure.
- Reuse the generic connector registry, integration relational journal, typed local semantic operations and existing Git checkpoint port. No separate Sparx synchronization authority.
- Lookup adapters by exact `(connectorId, profileVersion)`. Writes require a server-registered verified contract plus matching provider/scope/capabilities; a self-reported bit or request flag cannot enable them.
- Required guarantees: atomic scope compare-and-mutate, atomic create absence, durable idempotency, durable receipt lookup and complete versioned scope read. Old `publish(review,request)` is never used by the new engine.
- Freeze review, scope, payload, identities and expected source state before effects. Sequential per-item scope CAS derives each next token only from the previous validated receipt.
- HTTP and Git execute outside database/workspace lock transactions. An acknowledged item is never downgraded; unknown effects retain reservations and are recovered using the identical key/request.
- OBSERVATION and COMMON checkpoints remain distinct. COMMON requires exact full-scope convergence, all receipts, local Git completion and unchanged final local state. No checkpoint for partial publication, failed deletion, skipped divergence or unknown effect.
- Scope and authorization include organization, repository, workspace, branch, actor, project and immutable provider configuration. All diagnostics and durable evidence exclude credentials.
- Bounds: 10,000 scope items; 1,024 mutations; 16 MiB preview/plan/response; 1 MiB item request; 100 attempts per item; 30-second invocation deadline; 30-second claim lease. Provider limits may be stricter.
- Only one Maven reactor at a time; use `/workspace/scratch/dae1667028d4/verify-repo.py`. No new Python file in the repository.
- The genuine HTTP provider is test-only `taxonomy-publication-contract-v1`, not PCS. Sparx writes stay disabled and real EA/PCS evidence stays `NOT_EXECUTED`.

## Review Focus

- Response loss after provider commit, provider restart and overlapping retry produce one mutation and one monotonic terminal receipt, never a replacement key.
- Accepted pull with distinct local/external field baselines cannot erase pending local outbound changes or masquerade as a common baseline.
- Model movement while HTTP is in flight preserves remote receipts, stops new dispatch and prevents common finalization without overwriting newer local state.
- Partial effects followed by stale/unknown outcome remain recoverable across process restart and cannot be cancelled into an apparently clean new operation.
- UI restoration and direct API requests enforce provider/authority/branch gates and show partial/unknown outcomes without routing publication through file delivery.

### Task 1: Immutable publication contract, directed planning and receipt validation

**Files:**
- Create: `taxonomy-extension-api/src/main/java/com/taxonomy/extension/api/integration/PublicationContracts.java`, `ConditionalPublicationConnector.java`.
- Modify: sibling `LifecycleIntegrationConnector.java` only for documenting/deprecating the weak publication hook while preserving source compatibility.
- Create: `taxonomy-interop/src/main/java/com/taxonomy/interop/publication/PublicationDiff.java`, `PublicationPlanner.java`, `PublicationReceiptValidator.java`, `PublicationPolicy.java` and cohesive canonical digest/record helpers in the same package.
- Modify: `taxonomy-interop/src/main/java/com/taxonomy/interop/IntegrationDiff.java` or `ExchangeItems.java` only when extracting genuinely shared comparison primitives.
- Test: interop `publication/PublicationDiffTest.java`, `PublicationPlannerTest.java`, `PublicationReceiptValidatorTest.java`, `PublicationPolicyTest.java`, existing `IntegrationJsonTest.java`.

**Interfaces:**
- Consumes existing immutable `IntegrationContext`, `InternalState`, `ExchangeDocument`, flattened `Artifact`, `MappingLoss`, `ReviewedChangeSet` including explicit endpoint mapping and exact profile identity.
- Produces all public DTOs and `ConditionalPublicationConnector` signatures from the spec, including `publicationCapabilities`, `readPublicationScope`, `publishItem` and `lookupPublicationReceipt`.
- Produces pure `PublicationDiff.compare(CommonBaseline baseline, ExchangeDocument local, ScopeSnapshot remote)` returning ordered `List<PublicationChange>`; nullable baseline means conservative bootstrap.
- Produces pure `PublicationPlanner.plan(PublicationPreviewEnvelope preview, PublicationReview review)` returning immutable `PublicationPlan`; `void PublicationReceiptValidator.validate(PublicationItemRequest request, PublicationReceipt receipt)` verifies identity, version, digest and result semantics without side effects.
- Produces versioned internal `CommonBaseline`, `PublicationPreviewEnvelope`, `StagedBinding` and sanitized public operation DTOs from the spec. Provider verification policy exposes supported availability and refuses missing/unregistered contracts before effects.

- [ ] **Step 1: Add focused RED tests.** Use small canonical field fixtures that distinguish base-local from base-remote; cover independent name/description merge, conflict, endpoint/direction/move conflict, local-only addition, remote-only Push refusal, deletion/update, no-base absence and observation checkpoint exclusion.

```java
var changes = diff.compare(commonBaseline, localDocument, remoteSnapshot);
assertThat(changes.getFirst().conflicts()).isEmpty();
assertThat(changes.getFirst().merged().title()).isEqualTo("Local title");
assertThat(changes.getFirst().merged().text()).isEqualTo("Remote description");
assertThatThrownBy(() -> validator.validate(request, receiptWithDifferentDigest))
    .isInstanceOf(IntegrationProblem.class);
```

Test exact required resolution coverage, unsupported losses/remaps, dependency ordering, deletion rationale/authoritative scope, full empty-plan equality and skipped divergence. Serialize/deserialize every durable value and assert deterministic fingerprints. Test mutation/byte/attempt limits at their boundary and missing verified guarantees.

- [ ] **Step 2: Run RED.** Run `python /workspace/scratch/dae1667028d4/verify-repo.py -pl taxonomy-interop -am test -Dtest=PublicationDiffTest,PublicationPlannerTest,PublicationReceiptValidatorTest,PublicationPolicyTest,IntegrationJsonTest -Dsurefire.failIfNoSpecifiedTests=false -DexcludedGroups=real-llm`; record meaningful missing-behavior evidence.

- [ ] **Step 3: Implement immutable planning and validation.** Copy the public contract signatures from the spec, validate bounded values and defensively copy collections. Keep directed `MERGE`, `KEEP_LOCAL`, `TAKE_REMOTE`, `SKIP` choices separate from inbound decisions. Plan the complete local and remote targets against the last actual COMMON pair; never reverse-call inbound diff or use accepted observation as convergence. Validate dependency closure before any write and order mutations deterministically. Frozen resource IDs/key/intent fingerprints are independent of display names.

```java
if (!capabilities.guarantees().containsAll(REQUIRED_GUARANTEES)
        || !verifiedContracts.matches(context, capabilities)) {
    throw IntegrationProblem.conflict("PUBLICATION_GUARANTEES_UNVERIFIED");
}
```

Receipt validation binds schema/contract, provider/scope, operation/item/key, plan/request digest, exact before token and resulting resource semantics. DELETE must prove absence. A raw success status without this envelope is never acknowledgment. Implement canonical equivalence over in-scope semantic fields while excluding transport timestamps/source/version metadata; preserve mapped properties, containment and relation direction/endpoints.

- [ ] **Step 4: Run GREEN and self-review.** Repeat focused tests and bounds/security JSON checks. Inspect all immutable value constructors and profile/provider binding. Commit; record exact test counts and describe APIs consumed by Task 2. No disconnected DTO-only completion claim.

### Task 2: Durable execution, staged local apply and genuine HTTP contract flow

**Files:**
- Create: `taxonomy-interop/src/main/java/com/taxonomy/interop/publication/IntegrationPublicationService.java`, cohesive publication journal execution helper(s), and publication/item/attempt JPA entities under existing `persistence/`.
- Modify: `taxonomy-interop/src/main/java/com/taxonomy/interop/persistence/IntegrationStore.java`, sibling integration service/domain adapter and persistence connection/checkpoint entities; reuse existing workspace and portfolio ports for staged local apply/checkpoint.
- Create: `taxonomy-app/src/main/resources/db/migration/taxonomy/postgresql/V21__conditional_integration_publication.sql` (use next free version if necessary; never rewrite V20).
- Create test-only: `taxonomy-app/src/test/java/com/taxonomy/interop/publication/PublicationContractProvider.java`, `PublicationContractConnector.java`, `IntegrationPublicationFlowTest.java`, `IntegrationPublicationPartialTest.java`, `IntegrationPublicationConcurrencyTest.java`.
- Extend: shared persistence fixture entity lists, `IntegrationJournalTest`, `IntegrationRestartTest`, `IntegrationServiceCheckpointConflictTest` and schema/migration ownership tests.

**Interfaces:**
- Consumes Task 1 `PublicationPlan`, `PublicationPreviewEnvelope`, `PublicationReview`, `PublicationItemRequest`, validated receipts and provider policy; semantic completion's typed packages/requirement mappings.
- Produces `IntegrationPublicationService.previewPublication(RepositoryContext,UUID,PublicationPreviewRequest)`, `publish(RepositoryContext,UUID,PublicationReview)`, `publication(RepositoryContext,UUID,UUID)`, `retryPublication(RepositoryContext,UUID,UUID)`, `previewPublicationReconciliation(RepositoryContext,UUID,UUID,ReconciliationPreviewRequest)` using the existing repository context type. Return the sanitized `PublicationOperation` from the spec.
- Produces store session methods in the spec for preview/reservation, staged local state, claim, receipt/unknown recovery, verification/common completion and linked reconciliation; all operate under the existing scoped connection transaction authority.
- Produces durable observation/common pointers, staged identity promotion, immutable per-item requests/attempts and explicit allowed-action projection.

- [ ] **Step 1: Add RED journal and real HTTP tests.** Implement a test provider over actual HTTP with atomic persisted scope state and key/receipt records. Independently test provider CAS, create absence and same-key/same-digest replay; altered digest must not mutate. Drive the real service/store/editor with this connector. Cover item-one applied/item-two stale, commit-then-close response loss, late success racing timeout, concurrent retry and model movement during HTTP.

```java
assertThat(provider.mutationCount(resourceId)).isEqualTo(1);
assertThat(operation.items().getFirst().state()).isEqualTo(ItemState.ACKNOWLEDGED);
assertThat(operation.phase()).isNotEqualTo(PublicationPhase.COMPLETED);
assertThat(connection.commonCheckpointId()).isEqualTo(previousCommonCheckpointId);
assertThat(store.events(operationId)).anyMatch(event -> event.type().contains("UNKNOWN"));
```

Use latches/barriers and injected clocks, not sleeps, for deterministic race points. Verify unsupported PCS/direct adapter publication is refused before any HTTP write and checkpoint promotion rolls back with the local transaction.

- [ ] **Step 2: Run RED.** Run focused app tests via `python /workspace/scratch/dae1667028d4/verify-repo.py -pl taxonomy-app -am test -Dtest=IntegrationPublicationFlowTest,IntegrationPublicationPartialTest,IntegrationPublicationConcurrencyTest,IntegrationJournalTest -Dsurefire.failIfNoSpecifiedTests=false -DexcludedGroups=real-llm`; preserve exact behavioral failures.

- [ ] **Step 3: Implement persistent phases and execution.** Add forward-only migration/entities with scoped keys and operation/item uniqueness. Existing checkpoints migrate to OBSERVATION. Persist initial fetch request before HTTP, freeze richer preview after read and exact-state recheck. At review acceptance reserve connection and freeze plan/review/items. Sync applies typed local changes and stages bindings transactionally, completes separate deterministic Git intent, and only then schedules remote items. Push requires an exact local checkpoint. Do not promote staged bindings/common pointer early.

Execution follows short transactions around remote effects:

```java
PublicationClaim claim = journal.claimPublicationAttempt(context, connectionId, operationId, clock.instant());
PublicationReceipt receipt;
try {
    receipt = connector.publishItem(contextFor(claim), claim.frozenRequest());
} catch (RuntimeException failure) {
    journal.recordPublicationUnknown(claim, safeFailureCode(failure));
    return publication(context, connectionId, operationId);
}
receiptValidator.validate(claim.frozenRequest(), receipt);
journal.recordPublicationReceipt(claim, receipt);
```

The validator is a pure void guard. A validation failure also records UNKNOWN because the remote effect may have happened; do not lose it as an ordinary request error. On unknown outcomes use read-only lookup first; same-request resubmission is allowed only under verified durable key semantics. Use lease/attempt epochs and monotonic receipt sequence to retain late acknowledgment without allowing a stale worker to downgrade it. Recheck source state before each new dispatch, retain effects on movement, and require reconciliation. Verify all receipts and final exact complete scope before atomic COMMON promotion; store gates enforce this independently of the service.

- [ ] **Step 4: Run GREEN with fault injection.** Demonstrate real Push create/update/delete, Sync local typed merge plus publication, no common completion after skipped divergence, stale refusal, unknown recovery, changed review rejection, actor/scope/branch isolation, rollback and legacy inbound/file regressions. Test provider state reload as well as client journal reload. Record HTTP request counts, exact replay keys, semantic operation/Git counts and scope revisions as assertions without logging secrets.

- [ ] **Step 5: Self-review and commit.** Inspect migration/entity constraints, transaction boundaries and late-receipt paths. Run focused tests and `git diff --check`, commit, and expose service/DTO interfaces for Task 3. Controller retains the full cross-JVM/browser acceptance gate.

### Task 3: Application API/UI, process-restart acceptance and documentation

**Files:**
- Modify: `taxonomy-interop/src/main/java/com/taxonomy/interop/controller/IntegrationController.java`, sibling integration service delegation/overview and related API tests.
- Modify: `taxonomy-app/src/main/resources/templates/integrations.html`, `static/js/integrations.js`, `static/js/api/integration-api.js`, `static/css/integrations.css`, `i18n/messages_integrations.properties`, `messages_integrations_de.properties`.
- Create/extend: app `IntegrationPublicationRestartTest`, `IntegrationPublicationSecurityTest`, publication API tests and current browser/civilian acceptance harness; test provider process launcher and persisted fixture control.
- Docs: `docs/adr/0009-sparx-integration.md`, `docs/features/sparx-integration.md`, `sparx-integration-de.md`, `docs/qa/sparx-implementation-validation.md`, completion review and versioned contract acceptance evidence. Keep real-product evidence unexecuted.

**Interfaces:**
- Consumes Task 2 service and sanitized `PublicationOperation` including phases/item outcomes/allowedActions/common vs observation checkpoint.
- Produces POST `/api/integrations/{connection}/publication-previews`, POST `/publish`, GET `/operations/{operation}/publication`, POST `/operations/{operation}/reconciliation-previews`; existing `/retry` dispatches by frozen direction/phase. Match the existing controller path-variable spelling/base exactly.
- Produces accessible separate Pull, file export, Push and Synchronize controls with explicit capability/authority gating, directed review choices and durable URL/reload restoration.

- [ ] **Step 1: Add RED API/browser and process-restart tests.** Use persisted HSQL fixture/JVM launcher patterns from existing `IntegrationRestartTest`; terminate/restart at plan commit, dispatch intent, remote commit before response, response before journal receipt, partial result, local apply before Git, Git before acknowledgment, all receipts before finalization and completed replay. Restart provider with durable key state. Assert resource/semantic/Git identity uniqueness and no premature common pointer. Cover DE/EN keyboard decisions, reload unknown/partial, no `/files` route for publication and direct unauthorized/moved-branch requests.

```java
assertThat(afterRestart.operationId()).isEqualTo(beforeRestart.operationId());
assertThat(afterRestart.requestFingerprint()).isEqualTo(beforeRestart.requestFingerprint());
assertThat(provider.createdResourceCount()).isEqualTo(1);
assertThat(afterRestart.commonCheckpointId()).isEqualTo(expectedVerifiedCheckpoint);
```

Security cases search persisted operation/events/attempts and captured logs for fixture secrets, malicious URI/redirect/reflection/oversized response and wrong provider binding. Generic 401/429/500 responses without verified no-effect envelope remain unknown.

- [ ] **Step 2: Run RED.** Execute focused API/restart/security tests via the runtime wrapper and the established app reactor selection. Browser tests run in the existing real browser GitHub workflow when local browser isolation is unavailable; do not bypass prior sandbox denials.

- [ ] **Step 3: Wire API and accessible UI.** Use existing authentication/error handling and server-derived allowed actions. Render actual LOCAL/BASE/REMOTE/merged fields and directions, exact review coverage, explicit deletion rationale, acknowledged/unknown/stale/unattempted counts. Retry reuses the frozen operation; reconciliation is a linked new preview only when unknown dispatches are resolved. Distinguish observation, local Git and COMMON checkpoints. Keep unverified PCS write controls disabled with a concrete reason and reject crafted direct requests.

- [ ] **Step 4: Run GREEN and document genuine evidence.** Run restart/security/API suites and current civilian acceptance through application routes with the test-only HTTP provider installed only in test configuration. Generate real screenshots/controls manifest for publication review, partial outcome and recovery; document every added control EN/DE. Update completion review with exact implemented/tested scope and real-product exclusions, never marking synthetic fixtures as EA compatibility.

- [ ] **Step 5: Self-review and commit.** Verify no false success badges, untranslated generated labels, lost frozen state or bypass routes. Run affected tests and `git diff --check`; commit and report exact evidence/remaining external browser execution gate. Controller then runs final mandatory verification, all-page Word render, whole-branch review, CI and authorized merge.

## Preflight resolutions

Task 1 owns immutable public/directed planning interfaces; Task 2 consumes those and adds persistence/execution; Task 3 consumes sanitized state without exposing leases/raw requests. Shared `IntegrationService` and checkpoint DTO changes occur sequentially. The validator is a pure void guard; a rejected receipt becomes a durable UNKNOWN outcome. API paths extend the actual existing `/api/integrations/{connection}` controller surface. User already authorized completing the feasible contract work and merging; this plan does not request another permission pause.
