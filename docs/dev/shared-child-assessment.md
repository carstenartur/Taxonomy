# Shared child assessment in the active relationship workflow

Issue #1111, package 2. This increment combines the bounded relationship search
from #1112 with the shared child-set validation from #1113. It does not introduce
another search engine, provider stack, data model or adoption path.

## One validation contract, different semantics

`ChildAssessmentContract` checks exact offered IDs, duplicate/null/foreign/missing
answers, non-null policy results, immutable output and stable offered ordering.
Map-based product scores and array-based relationship decisions use the same
contract. Array identities are validated before indexing, so duplicate answers
cannot disappear in a map. Duplicate offered identities fail before the active
relationship protocol sends a remote completion request.

`LlmResponseParser.parseChildAssessment` remains the consumer for independent
product scores. Product thresholds and the lack of parent normalization are
unchanged. `RelationSearchProtocol` now uses the same contract for contribution
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

## Boundaries that remain

Legacy category scoring still has its existing parent-score normalization and
missing-value compatibility. This is not claimed as a migration of all category
false-zero paths. Migrating that API, streaming outcomes and persisted score
semantics remains explicit work in #1111. This increment does not claim a
persistent shared cache, provider-retry/token budget, improved model recall or
standards conformance. The opt-in relation phase and its configured limits are
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

The repair is verified by tests-first execution and a fresh Java 21 Maven source
build on an isolated verification branch. Actual executed results and exact
commit identities are recorded in PR #1113. The full canonical source/coverage,
database, browser and security checks are still required before merge; targeted
tests or earlier-head evidence do not replace them. No canonical workflow,
coverage threshold, test exclusion or dependency-policy exception is changed.
