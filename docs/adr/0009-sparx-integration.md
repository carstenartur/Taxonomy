# ADR 0009: Sparx integration uses the shared reconciliation authority

Status: accepted for implementation of #1075.

## Intent

Exchange architecture semantics with Enterprise Architect in both directions,
preserving identity, reviewed changes and a durable common baseline. The design
and acceptance criteria are supplied by issue #1075 and ADR 0006.

## Decisions

* Reuse `IntegrationContracts`, `LifecycleIntegrationConnector`, `IntegrationDiff`,
  `IntegrationStore`, the integration page and typed architecture operations.
  Do not add a second journal, domain model or vendor dependency.
* Keep the pure Sparx mapping and XML adapters in `taxonomy-export`, with runtime
  wiring in `taxonomy-interop`. These already own the corresponding contracts;
  another Maven module is unnecessary for this adapter.
* Use normalized EA GUIDs, deterministic UUID mappings scoped to a connection,
  and an immutable `sparx-xmi-2.1` profile version. Never match names.
* Packages use canonical specification objects and placement records. Their
  hierarchy is reviewed, persisted and round-tripped as exchange semantics;
  it is not coerced into architecture components or diagram views.
* Keep diagram/layout content outside the semantic comparison and output. Every
  excluded construct receives a machine-readable loss. Export is a semantic
  exchange file, not a command to replace the entire EA repository or its views.
* Native requirements use the existing project/requirement boundary. A missing
  project is an apply error, never silent evidence-only acceptance.
* Transport adapters share canonical exchange values and durable previews.
  Conditional live writes may only be advertised after the provider's exact
  version and retry contract is proven. A file download is not a remote success.
* The separate `sparx-oslc-am-2.0@1` read/pull profile reuses scoped HTTP, reviewed
  native changes and the durable journal. Only package/element properties and
  hierarchy are mapped; uncollected features are explicit losses. PCS session
  credentials are injected at the HTTP boundary and never become persisted URIs.
  A collection fingerprint is rechecked before apply but is not an atomic model
  version. Exhausted query pages are never authority to delete absent objects.
* Real EA/PCS compatibility remains unverified until product evidence exists.
  Contract fixtures must be labeled as fixtures, with no fabricated version claim.

## Verification

Exercise malformed XML, GUID alias collisions, metadata identity collisions,
profile mismatch, missing endpoints and parents, independent edits, conflicting
edits, incomplete listings, moves, re-import, local edits after import, native
export identity binding, and restart-safe preview/apply using the existing journal.
Run the repository's complete verification command and report environmental
failures separately from focused regression evidence.

## Conditional publication amendment — 2026-09-20

* `ConditionalPublicationConnector` supplements the old weak publish SPI. The new
  engine never invokes that weak method. Registry dispatch remains exact connector
  ID + immutable profile version. Only an explicitly server-registered verified
  provider contract permits writes: atomic scope compare-and-mutate, atomic absence
  for create, durable idempotency, durable receipt lookup and complete versioned reads.
* Reuse the relational integration journal, portfolio authority, typed editor and Git
  checkpoint ports. Plan acceptance and local staging share one transaction. Network
  and Git effects execute outside those transactions; test delegates observe real
  commits without production crash hooks.
* Freeze review, scope, payload, item identities, expected state and keys before effects.
  An unknown outcome retains the reservation and resumes the same request. Lookup-only
  recovery after movement cannot dispatch further mutations. Acknowledged effects are
  never downgraded. Reconciliation is a new linked review, not an edited old plan.
* OBSERVATION records a pull; COMMON proves full-scope convergence after verified
  receipts, completed local Git and unchanged local state. Old checkpoints remain
  observations. Skipped divergence, stale/rejected deletion or unknown effects cannot
  promote COMMON.
* Schema-1 `PublicationOperation` additively exposes the already persisted authorized
  review, scope, expected external revision and request fingerprint. Original context
  comes from the existing operation GET. Leases, credentials and raw dispatch requests
  remain private. Existing constructor callers retain source compatibility.
* The unchanged aggregate 16 MiB output budget includes preview, review, outcomes and
  completion. Before accepting effects, reserve known frozen identities plus bounded
  receipt/version fields and checkpoint overhead. Extreme combinations can therefore
  fail earlier with `PUBLICATION_PROJECTION_LIMIT`; never create an unreadable operation
  after effects. Other limits remain 10,000 scope items, 1,024 mutations, 1 MiB requests,
  100 attempts, 30-second invocation and 30-second claim leases.
* Optional overview scope inputs derive external authority from the authorized connection.
  Missing scope stays disabled, while unverified PCS retains its specific denial reason.
  Publication HTTP routes reject an explicit branch mismatch; existing absent-branch and
  legacy Pull/file behavior remain unchanged. One reviewed existing dependency count
  changes from 5 to 6 for that shared `RepositoryContext` boundary check.
* A neutral native projection registration and verified publication policy are separate
  server-owned authorities. The genuine HTTP provider is test-only and cannot impersonate
  Sparx. Real EA/PCS/MS Word/MS Visio and a compiled SBPI host plugin remain unexecuted.

See the [versioned contract evidence](../qa/conditional-publication-contract-v1.json),
[completion record](../qa/1075-completion-evidence.md) and
[decision register](../qa/1075-completion-decisions.md).
