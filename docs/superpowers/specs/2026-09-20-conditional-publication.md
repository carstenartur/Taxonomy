# Conditional publication and recovery: implementation inventory

Read-only design against `Taxonomy-completion` at `0af596f`, 2026-09-20. No code changes or Maven execution. Scope: the feasible shared contract/core/UI/test slice of #1075, without inventing a PCS write protocol.

## Binding implementation clarifications

This design is the implementation contract for the feasible shared engine. The existing generic integration registry, relational journal, typed operations, exact workspace checks and Git checkpoint port are the only production authorities. Helper classes may be factored within those owners, but no parallel Sparx synchronization engine is introduced.

- Publication consumes exact `(connectorId, profileVersion)` lookup from the semantic completion slice. A verified adapter registration is server-owned code/configuration, never a request flag, connector capability bit alone or remote self-declaration. The contract provider is test-only and is not a PCS implementation.
- Preserve existing `LifecycleIntegrationConnector.publish` for source compatibility but do not invoke it from the new engine; it lacks the receipt/recovery guarantees.
- New DTO constructors and entry points enforce finite budgets before any allocation or remote effect: at most 10,000 scope items, 1,024 publication mutations, 16 MiB canonical preview/plan or response, 1 MiB per item request, 100 attempts per item, a 30-second orchestration deadline per invocation and a 30-second claim lease. A timeout returns durable progress; it never erases an unknown result or retries under a new identity. Provider ceilings may be stricter. Inject a clock for deterministic tests.
- Optional `PublicationItemIntent.target` is null only for DELETE. Every fingerprint covers canonical bounded structured values and excludes credentials and transient server display text. A digest is evidence, never a substitute for a strong provider mutation token.
- A reviewed SKIP of divergent scope content cannot create a COMMON checkpoint. It yields explicit incomplete synchronization and may enter a linked successor review once all uncertain dispatches are resolved. An empty mutation set can establish COMMON only after exact complete scope equality is verified.
- The no-base bootstrap treats one-sided absence as a reviewed addition, never as proof of deletion. Existing OBSERVATION checkpoints never become COMMON by migration.
- Version changes, key reuse with another request, unknown/expired receipts, moved workspace or different actor/branch/provider configuration fail safely. Already acknowledged effects stay visible and immutable.
- Actual EA/PCS writes and compatibility remain unavailable; no option enables them without a verified adapter contract. Genuine HTTP contract and process-restart evidence completes this feasible slice.

## Decisions

1. Add a transport-neutral conditional publication SPI with typed, versioned scope snapshots, frozen item requests, durable receipts and read-only receipt lookup. Do not call the existing weak `publish(ReviewedChangeSet, OutboundRequest)` from the new engine.
2. Execute items sequentially, each atomically conditional on the exact prior scope revision. Freeze item semantics at review; persist the exact execution request, including its predecessor revision, before sending. Creates require provider-enforced idempotency and atomic absence checking. A preflight GET followed by an unconditional write is insufficient.
3. Persist review/plan, item intent, execution request, attempts and validated receipts in the relational journal. HTTP and Git run outside the relational transaction. No transient executor state is authoritative.
4. Treat timeouts, response loss and crashes after dispatch as UNKNOWN, never FAILED_WITHOUT_EFFECT. Recover with the same key. Unknown outcomes prevent cancellation, replacement operations and synchronization checkpoints.
5. Separate existing pull/observation checkpoints from genuinely common checkpoints. Existing identity `external` and `internal` values are intentionally distinct; accepted pulls, rejected fields and delivered files do not prove convergence.
6. Push requires remote-only differences to be explicitly reconciled. Synchronize stages its reviewed local merge through existing typed commands, completes its local Git intent, then publishes the reviewed remote target. Neither phase can prematurely advance the common checkpoint.
7. `SparxOslcAmConnector` stays read/pull-only. No PCS writes, no configuration switch that upgrades self-reported capabilities, and no compatibility claim from the HTTP test provider.

## Existing implementation and consequential gaps

* `LifecycleIntegrationConnector` has `publish(review, request)` returning `PublishResult`, but no execution engine. `PublishItem` has resource ID, versions, fingerprint, broad `OperationStatus` and failure code; it lacks intent identity, request digest, provider/scope binding, unknown outcome, durable receipt lookup and complete coverage evidence.
* `IntegrationStore` already owns a scoped connection pessimistic lock, immutable review fingerprint, active operation reservation, revision checks, durable events and operation snapshots. Its lock is a good local authority; it is not a remote atomicity guarantee. A transaction must not be held through HTTP.
* `Session.beginReview()` reserves the connection; `applied()` increments its revision. `complete(..., true)` always creates the integration checkpoint and clears the reservation. New publication completion must not reuse that boolean boundary unchecked.
* `IntegrationService.apply()` persists selected mappings immediately, with `external = change.after()` and `internal = merged selected value`. `finish()` unconditionally treats a completed incoming Git checkpoint as synchronized. Preserve existing inbound behavior as an observation checkpoint; do not label it a common BASE.
* `IntegrationDiff.compare()` correctly compares local changes against `Identity.internal` and external changes against `Identity.external`, handles incomplete listing omissions and identity reuse. It is inbound-oriented: its key set omits local-only unmapped exports and it classifies only remote deltas. Swapping its arguments or reusing `select()` for Push is unsafe.
* `IntegrationDomainAdapter.snapshot()` projects mapped objects; `exportDocument()` adds native objects and stable export identities. Publication must use both. Many package/extension values are evidence rather than editable domain fields; unsupported transformations must stay visible.
* Existing API distinguishes INBOUND apply and OUTBOUND file delivery. `integrations.js` dispatches every OUTBOUND review to `/files`; remote publication needs distinct `PUSH` and `SYNCHRONIZE` directions and routes.
* `IntegrationRestartTest` crosses JVM boundaries for fetch, preview, ORM apply, Git completion, acknowledgment and frozen file delivery. `IntegrationJournalTest` covers transaction rollback and connection locking. Neither exercises HTTP publication, response loss, durable per-item receipts or duplicate remote creation.

## Proposed public types and SPI

Keep current file/pull DTOs and old SPI method source-compatible. Introduce `ConditionalPublicationConnector extends LifecycleIntegrationConnector` in `taxonomy-extension-api/.../integration/`. Keep new values in `PublicationContracts.java`, avoiding an ever larger general DTO file. These signatures define the implementation boundary; constructors must defensively copy, bound and validate values.

```java
enum PublicationMode { PUSH, SYNCHRONIZE }
enum MutationKind { CREATE, UPDATE, DELETE }
enum ItemState {
    READY, IN_FLIGHT, UNKNOWN, ACKNOWLEDGED,
    REJECTED_STALE, REJECTED_PERMANENT, RETRYABLE_NO_EFFECT, BLOCKED
}
enum ReceiptState { APPLIED, REJECTED_STALE, REJECTED_PERMANENT, RETRYABLE_NO_EFFECT }
enum LookupState { FOUND, IN_PROGRESS, NOT_FOUND, UNAVAILABLE, EXPIRED }
enum Guarantee {
    ATOMIC_SCOPE_COMPARE_AND_MUTATE,
    ATOMIC_RESOURCE_ABSENCE,
    DURABLE_IDEMPOTENCY,
    DURABLE_RECEIPT_LOOKUP,
    COMPLETE_VERSIONED_SCOPE_READ
}
record PublicationScope(
    ExternalScope externalScope, String rootResource, String selectorFingerprint) {}
record ProviderIdentity(String providerId, String repositoryId, String configurationId) {}
record PublicationCapabilities(
    int schemaVersion, String contractVersion, ProviderIdentity provider,
    PublicationScope scope, Set<Guarantee> guarantees,
    Set<MutationKind> mutations, Set<ArtifactKind> artifactKinds,
    String verificationReference, String capabilityFingerprint,
    int maxItems, int maxRequestBytes, long receiptRetentionSeconds) {}
record ScopeSnapshot(
    int schemaVersion, ProviderIdentity provider, PublicationScope scope,
    String revision, String semanticFingerprint, boolean complete,
    ExchangeDocument document, Map<String, ResourceState> resources) {}
record ResourceState(String resourceId, boolean exists,
                     String version, String semanticFingerprint) {}
record PublicationPreviewRequest(
    UUID operationId, InternalState expected, PublicationMode mode,
    PublicationScope scope, String expectedExternalRevision) {}
record PublicationItemIntent(
    String itemId, String idempotencyKey, MutationKind mutation,
    String resourceId, ResourceState expectedResource,
    Artifact target, Set<String> dependencies, String intentFingerprint) {}
record PublicationPlan(
    int schemaVersion, UUID operationId, IntegrationContext context,
    PublicationMode mode, UUID commonCheckpointId, long connectionRevision,
    String requestFingerprint, String reviewFingerprint,
    PublicationCapabilities capabilities, ScopeSnapshot remoteBefore,
    ExchangeDocument localBefore, ExchangeDocument localTarget,
    ExchangeDocument remoteTarget, List<PublicationItemIntent> items,
    List<MappingLoss> losses, String planFingerprint) {}
record PublicationItemRequest(
    int schemaVersion, UUID operationId, String planFingerprint,
    ProviderIdentity provider, PublicationScope scope,
    PublicationItemIntent item, String expectedScopeRevision,
    String requestFingerprint) {}
record PublicationReceipt(
    int schemaVersion, String contractVersion,
    ProviderIdentity provider, PublicationScope scope,
    UUID operationId, String itemId, String idempotencyKey,
    String planFingerprint, String requestFingerprint,
    ReceiptState state, long receiptSequence, boolean terminal,
    String beforeScopeRevision, String afterScopeRevision,
    ResourceState resultingResource, String failureCode, Instant recordedAt) {}
record PublicationReceiptQuery(
    ProviderIdentity provider, PublicationScope scope, UUID operationId,
    String itemId, String idempotencyKey, String requestFingerprint) {}
record PublicationReceiptLookup(
    LookupState state, PublicationReceipt receipt) {}
record PublicationCompletion(
    int schemaVersion, UUID operationId, String planFingerprint,
    List<PublicationReceipt> receipts, ScopeSnapshot remoteAfter,
    InternalState localAfter, String commonSemanticFingerprint) {}

interface ConditionalPublicationConnector extends LifecycleIntegrationConnector {
    PublicationCapabilities publicationCapabilities(IntegrationContext context,
                                                    PublicationScope scope);
    ScopeSnapshot readPublicationScope(IntegrationContext context,
                                      PublicationScope scope,
                                      String expectedRevision);
    PublicationReceipt publishItem(IntegrationContext context,
                                   PublicationItemRequest request);
    PublicationReceiptLookup lookupPublicationReceipt(IntegrationContext context,
                                                      PublicationReceiptQuery query);
}
```

`Artifact` here is the existing flattened artifact form; relationships/placements retain their identity/endpoints through `ExchangeItems`. A connector may map one item to several native fields only if its single item operation is atomic. If not, that item kind is unsupported. The first contract provider uses client-assigned resource identities. Server-assigned identity remapping and changing payloads after create responses are deliberately excluded from v1; stable create identity is frozen before review, satisfying the native deterministic export IDs.

`PublicationCapabilities` is a declaration, not proof. Enable writes only where the server-configured adapter/version has an explicitly registered verified contract profile, and returned provider identity/scope/capability digest agree with it. Capability/schema version mismatch or missing guarantees yields `PUBLICATION_GUARANTEES_UNVERIFIED` before review acceptance or network writes. A connector implementing only the old publish method remains ineligible. Add `publicationAvailability` to connection Overview with supported modes/mutations and bounded reason codes; descriptor `CONDITIONAL_PUBLISH` alone never enables the action.

Do not put credential references, endpoints with credentials, headers or raw response bodies in any of these types. `rootResource` is a validated credential-free resource identifier under configured endpoint policy. Provider identity must not derive solely from a redirectable URI.

### Required provider semantics

* `publishItem` atomically checks the exact whole selected-scope revision and resource state, applies all fields of the item and records its durable receipt. Another writer anywhere inside the declared scope invalidates the next request. Version tokens must be strong opaque mutation tokens, not content hashes guessed by the client or weak ETags.
* An already terminal idempotency key and identical request digest returns the same durable result, including after provider restart. Same key with different digest is rejected without mutation. APPLIED, REJECTED_STALE and REJECTED_PERMANENT are terminal. RETRYABLE_NO_EFFECT is a nonterminal attempt receipt: the key remains bound to the immutable request and may subsequently advance to a terminal receipt under the same key. Lookup returns the latest monotonically increasing `receiptSequence`; a delayed no-effect receipt never replaces a terminal acknowledgment. Namespace includes verified provider/repository/configuration and operation/item identity; do not derive keys from titles or secrets.
* For CREATE, atomic absence and stable identity reservation are required. For DELETE, a receipt positively proves deletion; a later missing object alone does not prove this request performed it.
* `NOT_FOUND` is not permission to create a new key. The same request may be resubmitted only under the verified durable-idempotency guarantee. An overlapping original request and retry must linearize to one mutation. Expired/forgotten history cannot certify no effect: return EXPIRED and require reconciliation, never retry with a new identity.
* Return APPLIED only with an exact before/after scope revision, matching request digest, resulting resource version and canonical semantic digest (or explicit absence for DELETE). A 2xx with malformed/incomplete/mismatched receipt is UNKNOWN.
* Explicit no-effect rejections and transient no-effect results are meaningful only if this guarantee covers them. A generic HTTP 500/401/429 response is not by itself proof that no mutation happened. Transport converts only contract-proven rejection envelopes into no-effect results; everything ambiguous becomes UNKNOWN.
* No multi-resource atomic batch is claimed. Each acknowledgment advances the expected-scope token for the next item; this permits honest partial publication.

## Preview and review semantics

Add `PublicationPlanner` and `PublicationDiff`; do not overload inbound `compare()` with hidden direction rules. Planner uses complete remote scope and full projected local export. Require immutable mapping-profile version and exact repository/workspace/branch/project state. Freeze original request first; fetch read-only remote data outside the DB transaction; install the preview only after rechecking internal state and connection revision under lock.

Store an immutable preview envelope containing the remote snapshot, full local projection, selected scope and selected common baseline. Hash this envelope for the review fingerprint; keep the original request fingerprint separately for operation-ID replay. This avoids using a fetch request fingerprint as the sole binding for a richer later preview. Replays with same operation ID and same normalized request return the frozen existing preview before checking today's model; changed scope/mode/actor/payload fails `OPERATION_ID_REUSED`.

`PublicationDiff` returns item changes and directional field sets:

```java
record PublicationChange(
    String id, String externalId, Artifact baseLocal, Artifact baseRemote,
    Artifact local, Artifact remote, Artifact merged,
    Set<String> localFields, Set<String> remoteFields,
    List<String> conflicts, Set<String> dependencies) {}
record PublicationReview(
    ReviewedChangeSet review, Map<String, PublicationResolution> resolutions) {}
enum PublicationResolution { MERGE, KEEP_LOCAL, TAKE_REMOTE, SKIP }
```

Prefer the dedicated resolution map over extending old `Decision` with contradictory direction semantics. The legacy review supplies rationale and mapping overrides; require exactly one resolution for every changed publication item, with no unknown IDs or conflicting parallel `decisions`. UI must produce an empty legacy decisions map for publication reviews. Freeze the whole `PublicationReview` digest, including remaps. `KEEP_LOCAL` is an explicit conflict resolution and may authorize replacing conflicting remote fields only against the frozen exact remote token. `TAKE_REMOTE` can mutate local only in SYNCHRONIZE; Push rejects that resolution with `RECONCILIATION_REQUIRED` and offers a pull/sync preview. `MERGE` is allowed only if field conflict analysis succeeds.

Three-way comparison uses the last COMMON checkpoint's immutable paired canonical projections. Existing observation baselines remain useful inbound evidence but cannot erase pending outbound local deltas. For each field, compare LOCAL to BASE_LOCAL and REMOTE to BASE_REMOTE; merge independent changes, conflict on different same-field changes. Canonical semantic comparison excludes source text, generated timestamps and provider versions; mapped properties, extension content declared in scope, containment, relationship endpoints/type/direction remain included.

With no common checkpoint, bootstrap conservatively: show equal content as converged, absent-on-one-side as an explicit reviewed addition (not deletion), and differing content on both sides as a conflict. Never infer deletes from a missing initial base. A new common checkpoint can be established only after full declared-scope convergence has been verified.

Use stable identity, including historical tombstones, never names. Preserve unselected/unmanaged scope objects on the remote side. Local deletion requires a prior common mapped object, complete authoritative remote evidence, explicit resolution/rationale and exact state. No deletion from unsupported items, partial paging, failed reads or filtered results. Package/relationship dependency closure is validated before any local or external write; order parents/elements before dependent relations/placements and deletions in reverse dependency order. Cycles that cannot be represented atomically fail validation rather than invent an ordering.

Push may safely publish local-only changes when REMOTE equals BASE_REMOTE or when both sides already agree. If remote-only changes require changing local content, stop for explicit Synchronize/Pull. Do not silently fill a full outbound resource from local and overwrite those remote fields. Synchronize previews the combined merged target and both sets of effects. Unmapped/unsupported accepted fields reject the plan unless an explicit supported remap resolves the loss.

## Durable execution and concurrency

Add operation-level publication phase, distinct from broad `OperationStatus`:

```java
enum PublicationPhase {
    PREVIEWED, LOCAL_APPLY_PENDING, LOCAL_CHECKPOINT_PENDING,
    PUBLISH_PENDING, RECOVERY_REQUIRED, VERIFY_PENDING,
    COMPLETED, PARTIAL, RECONCILIATION_REQUIRED, CANCELLED
}
```

Expose phase, acknowledged/remaining/unknown counts and `allowedActions` in publication operation responses. Keep legacy operation status in sync for history: in-progress publication maps to APPLYING, recoverable partial effects map to PARTIAL, terminal reconciliation to CONFLICT. Consumers must use phase/action gates, not infer retryability solely from legacy status.

1. **Accept:** authorized original actor, same exact context/branch/project/profile/scope/provider, same connection revision, same preview and review. Under connection + workspace/project locks, reserve the operation, persist immutable plan and every intended item. Competing reviews of the same baseline: exactly one wins. Same reviewed operation repeated returns recorded state; changed review fails `REVIEW_ID_REUSED`.
2. **Local phase:** Push makes no local semantic changes. Synchronize reuses the existing `select`-independent validated command construction, portfolio updates and `editor.acceptIntegration` path with deterministic operation/command/checkpoint IDs. Its relational transaction persists local operation history, result state, pending Git intent and staged identity bindings. Do not call existing `Session.mapping` or common `complete` as part of this staged phase. If command execution fails, roll back both local domain change and stage. No HTTP in this transaction.
3. **Git:** resume deterministic `editor.checkpoint` intent just as today, without reapplying commands. Freeze resulting `InternalState`. A changed Git head yields RECONCILIATION_REQUIRED, preserves accepted local state, and sends no remaining remote mutations. Refactor existing `finish` into local-checkpoint completion plus separate integration finalization; the publication route calls only the former until acknowledgments are verified. For Push, require an existing exact checkpoint or explicitly create its frozen normal checkpoint intent before publishing; an uncheckpointed head must not be called synchronized.
4. **Claim next item:** under connection lock, verify phase, current local state and dependency acknowledgments. Persist exact `PublicationItemRequest`, request digest and lease/attempt token; transition READY to IN_FLIGHT and commit. First item uses frozen remote-before revision; subsequent items use the preceding APPLIED receipt's after revision. This derived precondition is never recomputed from a fresh read. Frozen intent may not change.
5. **Dispatch:** release DB/workspace locks and invoke connector. Reauthorize and revalidate scoped configuration/capability binding for each attempt. Exactly one logical in-flight item per connection. A durable lease reduces duplicate sends across concurrent retry requests; provider idempotency is still required for expired leases and slow original calls. Never rely on `synchronized`, process memory or database lock held across HTTP for correctness.
6. **Record:** a short transaction validates and persists the entire receipt and item result. Lease/attempt compare-and-set rejects an obsolete worker's state transition; a valid late receipt for the same immutable request can still be adopted monotonically under lock. An acknowledgment cannot be downgraded by a timeout or competing callback. Receipt insertion and item transition are atomic.
7. **Pause on uncertainty:** dispatch exception, interrupted process or lease expiry means UNKNOWN. Persist any earlier APPLIED receipts; do not clear reservation. Retry first uses read-only receipt lookup for unknown items, then returns the same completed receipt or resubmits the same frozen request only if safely allowed. No new item dispatch while an earlier unknown outcome exists.
8. **Movement:** recheck local exact state before each new dispatch and at finalization. Model movement while an HTTP write is in flight cannot be atomically prevented across two independent systems. Record any acknowledged effect honestly, stop further writes, recover unresolved outcomes read-only and require reconciliation. Do not pretend a preflight local check closes this distributed race. Never overwrite moved local state, modify the original payload or repoint its expected token to current remote state.
9. **Remote stale/partial:** scope CAS rejection stops the chain. Earlier ACKNOWLEDGED items stay acknowledged; next expected token is never refreshed in-place. Same-field conflicts, identity reuse and changed profile produce an explicit successor review. Transient proven-no-effect rejection may retry the identical request under the same key and contract; permanent recorded terminal rejections require a new reviewed operation.
10. **Verify:** after all planned mutations are ACKNOWLEDGED, perform a complete exact-revision scope read using the final receipt revision. Validate provider/scope, coverage, every planned resulting identity/version/digest, and semantic equivalence to the reviewed target. This is not the CAS write guarantee; it establishes final checkpoint evidence. Missing/duplicate/foreign receipts, absent item, truncated response, unexpected normalization or remote change prohibits common finalization.
11. **Finalize:** under connection + workspace/project locks, recheck exact resulting local state, active operation and frozen revision/profile. Atomically promote staged bindings, persist `PublicationCompletion`, create COMMON checkpoint if the entire declared synchronization scope converges, advance common pointer and release reservation. No checkpoint on subset receipts, UNKNOWN, failed delete or Git conflict. A remote edit immediately after a verified snapshot is a later change detected from the captured version next time; cross-system atomic transactions are not claimed.

For skipped/rejected divergent items, all selected publication writes may succeed but the operation is still PARTIAL relative to the declared synchronization scope. Show “selected changes published; synchronization incomplete,” retain the prior COMMON checkpoint and immutable receipts. Once all in-flight/unknown outcomes are resolved, it may release its reservation through an explicit recorded reconciliation transition. Stable identity reservations/acknowledged bindings are retained separately; they do not move the common baseline. Do not silently narrow scope to make a success badge possible.

Cancellation is allowed before accepted effects; after any local apply or dispatch it means stop scheduling and reconcile, never rollback or erase history. Provide `reconcilePublication` to resolve an operation only after unknown attempts have authoritative terminal outcomes (or a provider-specific verified fence proves they cannot later commit). A current GET that happens to match/miss an object is not sufficient to release an UNKNOWN creation. Unknown with expired receipts can remain blocked pending external recovery; this limitation is necessary and must be visible.

A current RETRYABLE_NO_EFFECT display state is not by itself unresolved or resolved.
After local movement, that item is resolved if every persisted SEND for the item
has its own completed, validated no-effect receipt; explicit reconciliation requires
all items to be resolved. A timeout's ended timestamp is not
such proof. A nonterminal LOOKUP receipt belongs only to that lookup; it never
completes an older possibly committing SEND. Retain lookup-only recovery while
that uncertainty remains, including when the item displays RETRYABLE_NO_EFFECT.
A repeated nonterminal FOUND ends the invocation and releases only its own lease;
it cannot trigger another SEND or spin through the attempt budget. Terminal
receipts remain authoritative across leases, and COMMON still requires full
convergence against unchanged local state.


A reconciliation preview has a new operation ID and `predecessorOperationId`, uses current full local and remote states plus prior common checkpoint and predecessor receipts, and records explicit field choices. Do not clone an old payload under a new key. Existing local semantic operations and their operation IDs remain immutable; successor operations apply only newly reviewed deltas. Clearing the old active reservation and reserving successor must be atomic and require no unresolved dispatches.

## Persistence additions

Add `V21__conditional_integration_publication.sql` (next free migration at inspected commit; renumber if concurrent slices reserve V21). PostgreSQL V20 is the latest current integration schema. Do not edit V20. Match entity `LONG32VARCHAR` conventions and ensure migration/schema ownership compatibility with other supported database paths.

* `interop_publication`: ID/operation ID (one-to-one), scope ID, connection ID, schema version, request fingerprint, preview envelope JSON, plan JSON, phase, predecessor operation ID, common checkpoint ID, final local state JSON, staged bindings JSON, completion JSON, failure code, lease owner/until/epoch, timestamps, row version. Preview row exists before accepted plan; plan immutable after review.
* `interop_publish_item`: deterministic ID (SHA-256 operation+item key), scope/connection/operation ID, item key, ordinal, idempotency key, intent JSON/digest, exact request JSON/digest (nullable before first dispatch), state, result receipt JSON (nullable), attempt count, failure code, timestamps, row version. Unique operation/item key and operation/ordinal. Bound identity fields; use digest index where long external identifiers exceed DB index limits.
* `interop_publish_attempt`: attempt UUID, scope/connection/operation/item IDs, lease epoch, kind SEND/LOOKUP, start/end time, sanitized outcome code, validated receipt JSON or receipt digest. Retain useful monotonic attempt history, bounded by a deliberate quota; refuse additional attempts rather than silently discard durable evidence.
* `interop_connection.common_checkpoint_id` nullable. Keep existing `checkpoint_id` for pull observation compatibility; expose both distinctly.
* `interop_checkpoint.kind` default `OBSERVATION`, plus `baseline_json` and `publication_completion_json` nullable. COMMON baseline contains frozen local+remote canonical projections and exact scope/profile/provider; only verified new completion can set COMMON. Existing rows migrate to OBSERVATION even if their historical method parameter was named synchronizedState. No backfill guesses that those rows represent convergence.

Validated receipt failure codes keep the existing wire bound of 100 safe uppercase
characters in complete receipt JSON. Item, operation and event scalar diagnostics
remain bounded to 64 characters: only an overlong receipt code maps to its existing
receipt-state name. Do not truncate or mutate receipt evidence, or relax internal
safe-code validation.

Use scoped lookups for every publication, item, attempt and common checkpoint. Validate connection ownership even for globally unique operation IDs. Context contains exact branch but connection scope uses existing repository/workspace scope key: explicitly compare requested branch to frozen operation branch before retry/checkpoint/reconcile; the workspace lookup alone is insufficient. Treat repository organization, actor, project and immutable remote configuration identity as part of authorization, not just request fingerprint material.

Suggested `IntegrationStore.Session` additions (all short transactions):

```java
PublicationOperation publication(UUID operationId);
PublicationOperation publicationPreview(UUID operationId, PublicationPreviewEnvelope preview);
void beginPublication(PublicationReview review, PublicationPlan plan);
void stagePublicationLocal(UUID operationId, InternalState result,
                           ExchangeDocument document, List<StagedBinding> bindings);
void publicationLocalCheckpointed(UUID operationId, InternalState result);
PublicationClaim claimPublicationAttempt(UUID operationId, Instant now);
void recordPublicationReceipt(PublicationClaim claim, PublicationReceipt receipt);
void recordPublicationUnknown(PublicationClaim claim, String safeCode);
void recordPublicationLookup(PublicationClaim claim, PublicationReceiptLookup result);
void requirePublicationReconciliation(UUID operationId, String safeCode);
void completePublication(UUID operationId, PublicationCompletion completion,
                         CommonBaseline baseline);
void supersedePublication(UUID operationId, UUID successorId, String rationale);
```

`completePublication` must enforce all gates itself, not merely trust a service boolean. Existing generic `complete` must reject PUSH/SYNCHRONIZE directions. Finalization is idempotent; repeat calls return the same checkpoint/completion. Use a separate projection DTO for API reads so internal request/lease state is not accidentally leaked.

The internal helper values named above are records: `CommonBaseline(schemaVersion, provider, scope, profile, profileVersion, localDocument, remoteDocument, semanticFingerprint)`, `StagedBinding(externalId, businessIdentity, requirementId, externalArtifact, internalArtifact, removed)`, `PublicationPreviewEnvelope(schemaVersion, request, context, connectionRevision, commonCheckpointId, commonBaseline, localDocument, remoteSnapshot, changes, losses, previewFingerprint)`, and `PublicationClaim(operationId, itemId, attemptId, leaseEpoch, attemptKind, frozenRequest)`. `PublicationOperation` is the sanitized projection of the operation plus phase, plan/review fingerprints, public preview, item outcomes, completion and allowed actions. After staged local apply, internal snapshots for validation must overlay staged bindings on existing mappings; otherwise newly imported objects cannot be projected under their frozen external identities until promotion. Persist the binding values rather than regenerate them after restart.

## APIs and UI inventory

Add to `IntegrationService` or a dedicated injected `IntegrationPublicationService`, with existing service delegating so authorization remains consistent:

* `previewPublication(context, connectionId, PublicationPreviewRequest)`
* `publish(context, connectionId, PublicationReview)`
* `publication(context, connectionId, operationId)`
* `retryPublication(context, connectionId, operationId)`
* `previewPublicationReconciliation(context, connectionId, operationId, ReconciliationPreviewRequest)`

Controller routes: POST `/publication-previews`; POST `/publish`; GET `/operations/{operation}/publication`; existing POST `/operations/{operation}/retry` dispatches by frozen direction/phase; POST `/operations/{operation}/reconciliation-previews` creates the explicitly linked successor preview. Existing `/files` remains file delivery only, `/apply` remains inbound only. Return 409 for stale/unverified/state conflicts, 428 for absent exact state, bounded 422 for invalid selection/dependency/loss. After partial effect, response body/operation GET must expose durable partial state even if the triggering HTTP request fails.

Update `integrations.html`, `integrations.js`, `integration-api.js`, `integrations.css`, and both `messages_integrations*.properties`:

* Separate Pull, file export, Push and Synchronize controls. Enable write modes only from server-derived verified publication availability and permitted authority (`PUBLISH_TARGET` or `BIDIRECTIONAL` for Push; `BIDIRECTIONAL` for Sync). Keep PCS notice and disabled write explanation.
* Publication preview shows LOCAL/BASE/REMOTE, merged result, inbound/outbound effect direction, dependencies, loss/conflicts/deletions and explicit resolution choices. Do not reuse the existing label “Take external” for an outbound overwrite.
* Item outcomes show acknowledged, unknown, stale, not attempted and retryable no-effect separately; show exact local checkpoint and last COMMON checkpoint separately. No success while PARTIAL/UNKNOWN or local Git pending.
* Use server `allowedActions`; clicking Retry never generates a new operation ID or refreshes old expected versions. Freeze pending request/review in UI until durable result is loaded. Existing URL operation restoration is reused; after reload, operation GET restores all phase/item/review state.
* Accessible live status region, keyboard operable controls, pagination/filtering (current 40-row rendering can be reused), direction-aware decisions and DE/EN translations.

## Concrete implementation/test file inventory

### Core/API files

* Modify `taxonomy-extension-api/src/main/java/com/taxonomy/extension/api/integration/LifecycleIntegrationConnector.java`: deprecate weak publication hook for engine use; explanatory contract. Preserve its default unsupported behavior.
* Add sibling `PublicationContracts.java`, `ConditionalPublicationConnector.java`.
* Add `taxonomy-interop/src/main/java/com/taxonomy/interop/publication/PublicationPlanner.java`, `PublicationDiff.java`, `IntegrationPublicationService.java`, `PublicationReceiptValidator.java`, `PublicationPolicy.java`.
* Modify `IntegrationService.java`: publication delegation, common/observation overview, extract reusable inbound local apply/checkpoint stage without unconditional mapping/common completion.
* Modify `IntegrationDomainAdapter.java`: staged stable bindings and full projected publication snapshot; expose common semantic projection explicitly; no Sparx-specific new types in domain APIs.
* Reuse `IntegrationDiff.changed`, `ExchangeItems` fields/flatten/expand and existing safe merge primitives; add directed diff tests rather than reverse-call hacks. Modify `IntegrationDiff.java` only where genuinely shared validation is extracted.
* Modify `IntegrationStore.java`, `IntegrationConnectionEntity.java`, `IntegrationCheckpointEntity.java`; add publication/item/attempt entities in same persistence package. Extend `IntegrationJsonTest` for versioned records and deterministic digest round trips. Do not overload generic operation document metadata with opaque publication state.
* Modify `IntegrationController.java`, the six UI/i18n files above and `V21` migration. Update applicable entity lists in persistence/restart test fixtures and schema migration assertions.
* Keep `SparxOslcAmConnector.java` read-only; add negative tests proving even crafted direct API publication requests cannot bypass this.

### Genuine HTTP contract provider, no proprietary API

Add a test connector and standalone test-provider server under `taxonomy-app/src/test/java/com/taxonomy/interop/publication/`, implementing the above SPI over a deliberately named `taxonomy-publication-contract-v1` protocol. Use actual HTTP sockets (JDK HttpServer or existing fixture style); the application engine uses a real HTTP client. Its scope revision, resources and key/receipt records must persist across provider restart, using a small test database or atomic on-disk fixture. Provider mutation+idempotency receipt storage must be atomic. This tests the contract and recovery, not Sparx conformance.

The provider exposes documented test-only scope GET, item mutation and receipt GET routes. It can reject unsupported capabilities, mutate another writer's resource between requests, fail a specific item, close the connection after durable commit, return malformed/duplicate/foreign receipts and restart with preserved keys. It must not be annotated as a production Spring connector or appear in normal profile listings.

Client transport should reuse/extract `OslcTransport`'s configured host/path/DNS/redirect/deadline policy into a neutral helper if needed. Do not add invented PCS write methods to `OslcTransport`. Provider credential resolution remains runtime-only; test-mode insecure localhost settings are explicit and absent from production defaults. Independent provider-side CAS tests are necessary: a stub returning success regardless of token proves nothing.

### Required tests

1. `taxonomy-interop/.../PublicationDiffTest`: full local additions, bootstrap without base, independent merge, same-field conflict, different hierarchy moves, connector endpoint/type/direction conflict, deletion-vs-update, remote-only Push refusal, profile/identity reuse, unsupported loss/dependency closure, complete-scope delete gate, observation checkpoint not erasing local outbound changes.
2. `PublicationReceiptValidatorTest`: digest/provider/scope/operation/item/key/version mismatches, missing versions, foreign resource IDs, APPLIED delete requiring absence, unsupported receipt schema, weak token, duplicate receipts, incomplete coverage, unexpected normalized semantics, valid out-of-order callbacks handled by immutable request identity.
3. `taxonomy-app/.../IntegrationPublicationFlowTest`: actual service + durable store + real HTTP provider; Push create/update/delete, reviewed Sync typed inbound operations then publish; no semantic duplication; exact frozen retries after current model changes; provider unverified/missing guarantees and PCS direct publish rejected with zero HTTP writes.
4. `IntegrationPublicationPartialTest`: item one APPLIED then item two stale/no-effect/timeout; item one never resent with a new key; item three not sent; no COMMON pointer or premature promoted baselines; selected successes with skipped divergence remain PARTIAL; failed delete not synchronized.
5. `IntegrationPublicationConcurrencyTest`: two same-operation retry workers, two different reviews on one connection, lease expiration while original request still running, late receipt and timeout race, duplicate key different digest; one provider mutation and monotonic receipt. Check model movement before dispatch, during HTTP, before local Git completion and finalization; conflict never silently refreshes expected token.
6. `IntegrationPublicationRestartTest`: fork separate application JVMs against persistent DB and durable HTTP provider. Stop after plan commit, intent commit before send, provider commit before response, response before local receipt commit, partial receipt commit, local apply before Git, Git before acknowledgment, all receipts before common finalization and after finalization. Assert no duplicate resource/semantic operation/Git commit, frozen operation/request identity and exact receipt/checkpoint recovery. Restart provider as well as client for idempotency persistence proof.
7. `IntegrationPublicationSecurityTest`: cross-tenant/connection/workspace/branch/actor/project/provider scope failures, redirects, malicious resource path/host/DNS result, oversized response/deadline, credentials reflected in receipt/version/error/header/body. Search stored operation/events/attempts, canonical evidence and captured logs for fixture secret; output only safe codes.
8. Extend `IntegrationJournalTest`, `IntegrationRestartTest`, `IntegrationServiceCheckpointConflictTest`: existing file and inbound recovery regression, observation/common separation, staged mappings rollback, complete() direction restriction. Extend schema/entity tests with new tables/columns/indexes and migration replay. Verify supported database locking paths according to existing repository gates; do not claim Oracle/SQL Server/PostgreSQL execution from HSQL tests.
9. Add browser/UI integration coverage using repository's available browser test harness (none specifically named for integrations was found during this inventory): keyboard review, DE/EN labels, capabilities/authority gating, frozen retry after reload, per-item partial/unknown display, no accidental `/files` routing for PUSH/SYNCHRONIZE, and no misleading common checkpoint.

## Documentation and acceptance boundary

Update `docs/adr/0009-sparx-integration.md`, `docs/features/sparx-integration.md`, `docs/features/sparx-integration-de.md`, and `docs/qa/sparx-implementation-validation.md` with the generic conditional contract, honest observation/common distinction, recovery/reservation behavior, contract-provider evidence and unavailable PCS write reasons. Update the completion review's feasible-work row only after implementation and test evidence exist. Keep `docs/qa/sparx-compatibility.json` real-product execution status `NOT_EXECUTED`.

Completion means the actual APIs/UI drive a durable contract engine against a real HTTP contract provider, and fail closed for existing unverified providers. Merely adding DTOs, a disconnected state machine, synthetic receipts or in-memory unit stubs does not finish this slice. Real EA/PCS compatibility and any eventual PCS write adapter remain separate work requiring evidence of the specified guarantees.
