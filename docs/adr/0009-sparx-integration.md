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
