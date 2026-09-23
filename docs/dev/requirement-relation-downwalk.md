# Requirement-scoped relationship discovery

Implements the bounded relationship-search part of issue #1111, package 2. It does
not complete the project's independent model identity, adoption, or exchange packages.

## Enable explicitly

The initial delivery is **opt-in**. Existing installations keep their current
score-based analysis and do not incur additional remote evaluations until enabled:

```properties
taxonomy.analysis.relations.hierarchical.enabled=true
taxonomy.analysis.relations.hierarchical.max-calls=24
taxonomy.analysis.relations.hierarchical.max-depth=8
taxonomy.analysis.relations.hierarchical.batch-size=10
taxonomy.analysis.relations.hierarchical.max-work-items=512
taxonomy.analysis.relations.hierarchical.max-sources=32
```

These are server properties, not yet a new Preferences editor. The existing
analysis endpoints and selected provider are reused. No additional credentials,
microservice, database, or search engine is required. Existing `llm.mock=true`
returns an empty array from the raw-completion API; it is **not** a realistic
relation fixture and the enabled mode correctly reports an invalid response.
The test fixtures below replace the remote boundary with protocol-aware replies.

`max-calls` covers contribution extraction, navigation and separate verification
**evaluation attempts** in this additional phase. It does not include the earlier
node-scoring phase or turn a provider's internal HTTP retries into separate model
evaluations. The existing prompt budget is checked for every prepared request;
there is no new cumulative token/dollar budget or calibrated cost prediction.
Zero calls is supported and leaves work explicitly unassessed. Other bounds are
validated on startup. The application run's cancellation/memory/time checkpoints
remain active and partial evidence survives a stop.

## Flow and guarantees

1. Positive **effective** scores order concrete source candidates; catalogue roots
   are navigation containers, not components. Unhandled sources are reported when
   source limits, missing IDs or root-only analysis prevent extraction.
2. Sibling source candidates are assessed together. Each gets explicitly quoted,
   requirement-scoped contributions, a negative assessment or an open question.
   Multiple contributions and conditions are not merged merely because codes match.
3. The existing `RelationCompatibilityMatrix` routes each contribution through
   permitted relation types and directions. Incoming and same-category routes are
   considered; unrestricted `RELATED_TO` is not generated automatically.
4. Navigation offers sibling candidates together, including unscored targets.
   `DESCEND` means inspect the subtree, **not** create an edge to its root. A concrete
   non-leaf can be a match when further detail would invent a design choice.
5. A match receives its own verification evaluation. Only `VERIFIED` results become
   evidence edges, still **unaccepted proposals**, not architecture facts. Required,
   optional and alternative outcomes retain their original quotes and conditions.
6. The normal analysis result carries `relationSearchReport`. The architecture facade
   projects verified required relationships using existing view DTOs and invariants.
   It does not run legacy seed propagation, score-product inference, or cartesian
   impact generation. Optional/alternative branches remain in the evidence report.

Source, navigation and verification schemas are strict JSON objects with complete
candidate coverage. Unknown/duplicate IDs, duplicate JSON keys, trailing JSON,
missing fields, invalid enums, invented quotes and free text are errors, not zero
scores. Logs use the existing scoped call observer, retaining prompts/responses
under its existing memory and authorization rules.

## Evidence, views and persistence

The immutable DTOs live in `taxonomy-domain`; orchestration and provider/catalogue
adapters live in `taxonomy-analysis`. The report records an SHA-256 of the original,
source assessments, typed claims, decisions, pending work, evaluation counts, total
duration and the declared pruning policy. Existing project snapshots serialize this
report alongside their existing requirement-version and tenant provenance.

The active requirement/architecture is not overwritten. Scoped relations are **not**
inserted as globally valid catalogue hypotheses. There is no new global cache of
requirements. Cache/deduplication is local to the particular original, contribution,
conditions, direction and candidate batch. Explicit adoption remains separate work
in the broader architecture-project plan.

The architecture panel provides an expandable evidence section with budget,
questions, diagnostics, options, conditions and original quotes. All provider text
is escaped. The browser renders a bounded preview with an explicit omission count;
the full report stays in the JSON snapshot. No adoption action is implied by opening
that panel. Snapshot confidence is shown as unavailable, not fabricated from scores.

Node limits affect the view, not the full evidence. Both endpoints must fit before
an edge is shown. Multiple scoped claims with the same endpoint/type signature share
one queryable view relationship while its evidence list and full report retain every
scope. Queryable summary columns remain bounded; complete evidence is not truncated.
The legacy portfolio mapping index retains its coarse `PROPAGATED` category; precise
`RELATION_EVIDENCE` origin remains in the immutable analysis payload.

Diagram curation may omit a verified edge but may not reroute it to other endpoints
or change its type. This does **not** yet guarantee lossless export of every condition
or provenance field to every external format. Those exchange/identity contracts are
separate packages of #1111; no ArchiMate/Sparx conformance certification is claimed.

## Boundedness is not completeness

The root compatibility profile is the current application profile, not a full
ArchiMate metamodel. Source limits, maximum work, depth and model evaluations can all
leave explicit unfinished work. Roots are admitted lazily so they cannot consume
all work slots before any promising path reaches independent verification.

A semantic rejection prunes under a heuristic policy. `searchExhausted` means no work
remains under that policy, **not** that every necessary architectural dependency was
proved found. Direct-descendant recall audits, adaptive scheduling, learned caches,
full project-instance identity and controlled adoption are not claimed implemented.
No reduction in cost or increase in real-model architectural quality is claimed
without a measured comparison against independent expert-reviewed cases.

## Verification entry points

Normal Maven/JUnit discovery includes:

- `RelationSearchEngineTest`: bounded navigation, budgets, deduplication, typed
  evidence, cycles, cancellation and lazy admission.
- `RelationSearchIntegrationTest`: strict real protocol parsing, read/write
  distinction under identical scores, extraction/routing/downwalk/verification and
  the real analysis use case with catalogue/remote boundaries replaced.
- `EvidenceRelationProjectionTest`: exact evidence projection, conditional choices,
  required endpoints, snapshot column bounds and multiple scopes per signature.
- `ScopedEvidenceDiagramTest`: policies cannot invent a different verified edge.
- `RelationEvidenceMappingTest`: existing snapshot index supports the evidence origin
  without translating relevance into confidence.

The dependency-free/executable `*Contract` entry points exercise the same production
code, not alternative implementations. They also support focused local verification
against a commit-bound runtime when a full Maven dependency download is unavailable.
That verification is not a substitute for the complete current-head reactor suite.

Browser contract tests are `.github/scripts/relation-search-report.test.mjs` and
`.github/scripts/relation-search-confidence.test.mjs`, within the existing Node test
suite. They check budget/questions, escaped evidence, bounded previews, no accidental
adoption controls and unavailable-vs-legacy confidence presentation.

## Reviewed module dependencies

The hierarchical adapter reuses the existing analysis-to-knowledge direction; it introduces no reverse dependency or new Maven module. `RequirementRelationSearchService` and its scalar catalogue adapter each reference `TaxonomyService` and `TaxonomyNode` (two class pairs per package). `RequirementRelationSearch` and `RequirementRelationSearchService` each reference the existing `RelationCompatibilityMatrix` (two class pairs). These six concrete class pairs are recorded in the architecture dependency baseline. The dependency ratchet remains exact: no wildcard exemptions or general count increases are allowed. Bytecode inspection with `jdeps -verbose:class` confirms these pairs; the regular ArchUnit gate is the authoritative merged-source check.
