# Shared child assessment in the active relationship workflow

Issue #1111, package 2. This increment combines the bounded relationship search
from #1112 with the shared child-set validation from #1113. It does not introduce
another search engine, provider stack, data model or adoption path.

## One validation contract, different semantics

`ChildAssessmentContract` checks exact offered IDs, duplicate/null/foreign/missing
answers, non-null policy results, immutable output and stable offered ordering.
Map-based category/product scores and array-based relationship decisions use the same
contract. Array identities are validated before indexing, so duplicate answers
cannot disappear in a map. Duplicate offered identities fail before the active
relationship protocol sends a remote completion request.

`LlmResponseParser.parseChildAssessment` is the common boundary for category
relevance and independent product scores. Complete category batches retain their
existing parent-budget normalization. Product thresholds and the lack of parent
normalization are unchanged. `RelationSearchProtocol` now uses the same contract for contribution
extraction and NAVIGATE/VERIFY decisions, in the real `AnalyzeRequirementUseCase`
path. Original text, quotes, roles, conditions, alternatives and endpoint identities
remain governed by the existing `RelationSearchModel` and search validation.
Provider response order no longer selects which source/candidate consumes a
limited budget first. Contributions are ordered by text, original quote and
condition; distinct conditions remain distinct rather than being merged.

The temporary, unused `RelationAssessment` / `RelationChildAssessmentService`
variant is removed along with fixtures tied exclusively to that duplicate API.
Its role is served by the real bounded search protocol, engine, session and
use-case contracts, including read-only versus editing, non-leaf stopping,
explicit questions, alternatives, invalid evidence, incoming direction and limits.
The shared candidate/parser/run-control tests remain. The active catalogue
compatibility dependency stays in the existing relation-search adapter instead
of growing a second service-to-knowledge edge.

`AnalysisRunControl.call` retains its typed overload and the existing score-call
signature. Both the score path and the integrated relationship completion path
continue through the same observation, cooperative stop and resource checks.
No live model, automatic acceptance or extra model round trip is introduced by
this reuse correction.

## Missing category assessment is not a zero score

Every offered category must have an explicit, finite, integral score in 0..100.
A missing ID, foreign ID, null, missing score property, nonnumeric value, duplicate
JSON key or out-of-range score invalidates that entire batch **before** parent
normalization. A complete batch of explicit zeros is valid negative evidence;
it is different from a batch which could not be assessed. Both existing numeric
and score/reason-object response formats remain supported.

The active detailed category call returns an empty score map plus its error on
parse, transport, timeout, missing-credential or unavailable-local-model failure.
Prompt and raw-response diagnostics remain available when a reply was received.
Automatic and streaming analysis retain successful earlier batches, emit the
existing warnings/PARTIAL status, and do not insert zero placeholders for failed
roots or descendants. JSON snapshots preserve the missing score entries. The
interactive UI applies any valid completed entries but leaves an errored parent
unevaluated and retryable; a successful explicit-zero batch stays evaluated.

Duplicate catalogue candidates are rejected before normalization can hide them:
duplicate roots spend no model call, and duplicate children spend no child-batch
call. Identical *search contexts* are still deduplicated; that is a different
operation from silently accepting an invalid candidate set.

## Boundaries that remain

This migration covers the active detailed category workflow and its consumers.
Legacy score-only provider parsing helpers and product failure placeholders are
not redesigned here. Category parent-budget semantics and product thresholds
are unchanged; absence in the analysis score maps is authoritative, not the
legacy numeric defaults of unrelated architecture DTO fields. A restart-safe
resume queue for incomplete work is not introduced by this change. This increment
does not claim a persistent shared cache, provider-retry/token budget, improved
model recall or standards conformance. The opt-in relation phase and its configured limits are
unchanged; see `requirement-relation-downwalk.md` and both configuration references.

A separate verification model call is not independent human review or formal
proof. A syntactically valid original quote establishes provenance, not that the
model's interpretation is true. Proposals remain unaccepted and the original
requirement and active architecture are not overwritten.

## Regression evidence

Eight protocol regressions protect offered source/candidate order, canonical
contribution ordering with retained conditions, duplicate offered IDs without
remote calls, and missing/foreign/duplicate navigation answers. They are added
to the existing ordinary JUnit integration entry point rather than a new test
selector. The original fourteen protocol cases and existing engine, session,
use-case, product/parser and typed run-control suites remain in normal CI.

Additional ordinary JUnit regressions exercise malformed category responses,
explicit-zero versus missing outcomes, the detailed service, automatic/streaming
completion and JSON snapshots. Engine-level tests exercise duplicate roots and
children through the real search. The existing product-score-streaming UI suite
also covers failed/partial interactive retries and successful explicit zeros.

The repair is verified by tests-first execution and Java 21 Maven source builds. Actual executed results and exact
commit identities are recorded in PR #1113. The full canonical source/coverage,
database, browser and security checks are still required before merge; targeted
tests or earlier-head evidence do not replace them. No canonical workflow,
coverage threshold, test exclusion or dependency-policy exception is changed.


## Projection preserves score provenance and choice evidence

The active evidence projection receives `AnalysisResult.getScoreDetails()` alongside
effective relevance. A product with raw suitability 80 and parent relevance 40
therefore keeps direct score 80 and effective relevance 32; it is not reclassified
as a directly assessed 32. `RequirementElementView.scoreDetail` reuses the existing
immutable `AnalysisScoreDetail`. Null means no assessment was supplied, whereas a
non-null detail with score zero means an explicit negative assessment. Legacy
numeric layout/index defaults remain for compatibility and are not score evidence.
The untyped projection overload cannot establish raw-score provenance.

The snapshot mapping table reads raw and effective scores from the authoritative
analysis details and shows an em dash for unassessed scoped nodes, never an index
placeholder zero. Analysis and architecture-view JSON round trips retain these
details. Other exchange formats still follow their documented support boundaries.

All verified evidence is grouped by the actual oriented source/target/type
signature before deciding which required graph edges to display. A group containing
a required claim retains its distinct optional and alternative evidence, including
conditions and choice groups, irrespective of their arrival order. A pure-choice
group creates no required edge or endpoint. The full report is unchanged by
grouping, node limits or neutral diagram projection; no choice is automatically
adopted into the active architecture.

Exact JSON decimals are checked before integer conversion: precision-boundary
fractions such as `0.999999999999999999` are invalid, while exactly integral `1.0`
or `1e0` remain accepted. The supplied catalogue root set is copied and validated
before contribution extraction, avoiding paid calls for malformed roots. These
checks use the existing parser, child contract and report/stop paths, with no extra
provider call, service, global cache or transaction spanning a model request.

Regression entry points: `EvidenceProjectionContinuationTest` exercises the real
use case, score derivation, facade, projection, snapshot JSON and neutral diagram;
`ReviewBoundaryContinuationTest` exercises exact decimals and catalogue preflight.
The existing relationship confidence UI suite also covers authoritative raw vs.
effective scores, explicit zero and absent assessment.
