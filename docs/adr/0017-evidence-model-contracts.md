# ADR 0017: Explicit model, evidence and exchange contracts

Status: accepted and implemented; integrated qualification tracked on the change PR, 2026-11-10.

## Context

The project has no production users. We can replace ambiguous internal and HTTP
contracts instead of maintaining two implementations. The lessons from EAM
Toolfamilie, AAroN, Archi/coArchi, SHACL and reproducible builds reveal concrete
weaknesses: allowed relations are interpreted as obligations, some graph reads
lack selected-context evidence, semantic identity includes mutable type fields,
and exchange verification does not express every supported editing operation.

## Decisions

1. One immutable, versioned, framework-free metamodel owns relation legality.
   Candidate generation, DSL validation and editor schemas consume that contract.
   Unrestricted, restricted and forbidden target domains are distinct values.
   External profiles identify their original source, fingerprint and supported
   subset; source drift must fail a reproducible verification.
2. Obligations are explicit, versioned rules, separate from legality. Rules name
   applicability, cardinality, severity and the evidence required to decide them.
   Missing evidence yields an inconclusive result. A score is neither acceptance
   nor evidence of a missing required relation. The default compatibility profile
   imposes no invented business obligations.
3. Semantic block identity is stable across mutable type/property edits. Known
   unordered membership collections merge with three-way set semantics; ordered
   or unknown repeated properties remain conservative. Final validation and
   delete/reference conflicts remain authoritative. Unknown extensions survive.
4. Graph queries consume an explicitly selected, authorized repository/branch
   snapshot. Responses identify the exact revision, query policy, evidence and
   limits. A missing path is a result within that scope, never proof of universal
   absence. Stale or unproved projections fail closed.
5. Reconciliation displays property changes and graphical before/local/incoming
   context from the same frozen preview that is accepted. Existing editor scene
   rendering, command journal and stale-preview checks remain authoritative.
6. Exchange support is declared per operation and profile. Regression fixtures
   distinguish semantic identity from diagram occurrences and retain geometry,
   visibility and source provenance. Unsupported operations are rejected or
   explicitly reported as losses, never silently accepted as faithful exchange.
7. Product verification resolves pinned tool artifacts by content hash, verifies
   local/cache/download bytes, and records the actual producer and profile.
   Offline availability is supported by a verified preseeded artifact. It is not
   claimed merely because a URL and checksum exist.
8. A small independent, versioned quality corpus covers clear requirements,
   legitimate alternatives, incomplete input, contradictions, changes to an
   existing model and cases requiring no extra elements. Deterministic evaluator
   tests prove the evaluation contract, not live-model quality. Live costs and
   human correction effort are reported only when actually measured.

## Constraints

- Java 25; Maven Wrapper owns compilation, tests and verification.
- Extend existing modules and boundaries; no parallel graph store, editor,
  validation engine, Python product tooling or duplicate CI selector.
- No compatibility adapters solely for obsolete contracts. Update all owned
  consumers together. Preserve actual identity, authorization and provenance.
- Use the real catalogue for application acceptance. Authored unit fixtures are
  labelled as such. No paid or shared remote model calls during this work.
- Do not weaken assertions, coverage, security gates or timeouts.
- Preserve transport, authentication and tooling ownership when integrating
  parallel work. PR 1440 entered through main commit `47d94664f`. PR 1442 now
  combines these model contracts with the API and authentication changes from
  PRs 1438 and 1441. Qualification applies to that combined source tree; results
  from separate branches do not automatically qualify the integration.

## Reviewed module boundaries

Architecture assessments continue to depend on the existing knowledge and
workspace modules. Controllers use the workspace-owned HTTP component
`ExplicitRepositoryReadSelection` for explicit, authorized source selection.
Services receive `RepositoryContext`, the existing
read-only `WorkspaceDslReadPort`, and detached relation snapshots. The bound
repository reader remains available for the final expected-head check, so an
assessment cannot silently switch to a newly resolved context.

This moves selected-model gap, graph and pattern answers away from global
catalogue entities and repositories. Impact enrichment reads requirement mappings
from the selected model. The exact dependency inventory records these removals
alongside the individually reviewed context, read-port and snapshot dependencies.
Its classifiers, assertions and module-direction checks remain authoritative.
The graph controller also reuses the shared request selector and its existing
exception superclass handler, removing redundant dependencies.

Portable backup composition consumes the existing knowledge contributor's
selection value. Exact catalogue membership decides whether a model identity or
type root contributes a catalogue dependency; catalogue-derived references remain
mandatory. This uses the existing captured backup boundary and lookup failure
handling.

## Sources

- [EAM Toolfamilie](https://github.com/Mitchel85/EAM-Toolfamilie), initially reviewed
  at `0ea85db53ce318c1f852c3edea345202505f2dca`; the shared ADMBw descriptor pins
  source revision `6b1baa03bb097d3aa9afc8bde4d56e275e8a0e93` and exact source hashes.
- [AAroN](https://github.com/schmitze87/AAroN).
- [Archi](https://github.com/archimatetool/archi) and
  [coArchi](https://github.com/archimatetool/archi-modelrepository-plugin).
- [SHACL cardinality constraints](https://www.w3.org/TR/shacl/#MinCountConstraintComponent).
- [W3C PROV data model](https://www.w3.org/TR/prov-dm/).
- [Reproducible builds: volatile inputs](https://reproducible-builds.org/docs/volatile-inputs/).
- [SLSA build provenance](https://slsa.dev/spec/v1.2/build-provenance).

## Verification and limits

Each behavior change starts with a failing regression and passes its Maven-owned
focused suite. Integrated qualification uses the existing canonical CI entry point
and required hosted gates, with the exact reviewed head and outcomes recorded in
the change PR. A focused test pass is not full qualification. Native licensed EA
execution and live model quality are separate evidence categories and remain
explicit if unavailable.
