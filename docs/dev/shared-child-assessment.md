# Shared child assessment — initial implementation increment

Part of issue #1111, package 2. Design and remaining work are recorded in
issue comments 5791176190 and 5791749713. Baseline:
`f40f58a259dbde09877476d4736f22ec5662523d`.

## Shared mechanism, different meanings

`ChildAssessmentContract` validates a complete set of offered IDs before invoking
an interpretation policy. It rejects blank/duplicate candidate IDs, missing or
foreign answers, null values and null policy results. Output retains offered
order and is immutable. It has no percentage, relationship or provider semantics.

`LlmResponseParser.parseChildAssessment` combines the existing JSON extraction
with that contract. The existing independent-product parser and the new relation
policy both use it. Duplicate JSON keys, including duplicate decision fields,
are rejected by the shared Jackson reader rather than silently taking the last
value. Product thresholds and independent scores are unchanged.

Legacy category scoring is deliberately NOT migrated to strict completeness by
this increment. Its parent normalization and missing-value compatibility remain
unchanged, protected by regression tests. Moving that path from missing=zero to
an explicit incomplete outcome requires its own end-to-end migration within
package 2. This increment must not be described as fixing all false-zero paths.

`AnalysisRunControl.call` now delegates to one typed execution mechanism. Existing
score calls keep their signature, observer, cancellation, memory/deadline checks
and partial-score behavior. A typed caller supplies its diagnostic `LlmCallDetail`
instead of inventing fake numeric scores to fit that return type.

`RelationChildAssessmentService` evaluates ONE supplied, catalog-backed candidate
batch using the existing `LlmService.callLlmRaw`, provider stack and run control.
There is no new HTTP client, provider selection, global cache or remote-call
transaction. The caller's batch is snapshotted before the call and at most 40
candidates are accepted. The existing directed compatibility matrix rejects
incompatible candidates without an LLM call. Incoming directions are checked
with reversed endpoints; self-relations are excluded.

The relation context carries the unchanged original text/version, source
contribution, original quotations, relation type and direction. `NAVIGATE` may
return `DESCEND`, `ACCEPT`, `REJECT` or `UNRESOLVED`. `VERIFY` prohibits `DESCEND`.
A navigation acceptance proposes an endpoint; it is NOT a verified model edge.
Positive decisions need a contribution and exact quotations from the original.
Unresolved decisions need a question. Required, alternative and optional
contributions remain distinct; alternatives need a group ID. No score products,
percentage redistribution, forced leaf choice or taxonomy-as-composition rule
is introduced. Exact quotation validation checks textual provenance, not the
semantic truth of a model's explanation.

Results retain context, typed decisions, prompt, raw response, provider, duration
and error status. Malformed responses produce failure and an empty decision map,
not invented rejections. `complete()` means only that this offered batch was
assessed successfully; an unresolved decision is still unresolved. The
`providerCallAttempted` flag records a logical raw-call attempt, not a physical
HTTP-request count, retry count, billable-token count or proof of delivery.
Cooperative stops retain the existing run-control diagnostic behavior and are
re-thrown, not converted to negative relationships.

## Not yet delivered

The new relation service is not wired into `AnalyzeRequirementUseCase` or the
existing `AnalysisRelationGenerator`. The active relationship generator is
unchanged. The complete budgeted work queue, catalog traversal, contribution
extraction, semantic-context deduplication, final relationship verification
orchestration, persistent proposals/questions, UI/architecture handoff and
read-only-versus-editing end-to-end acceptance remain open in package 2.

A per-batch candidate limit is not a whole-analysis token/call budget. No cost,
recall, precision, standards-conformance or live-model quality gain is claimed.
Current root-based compatibility also does not replace the semantic metamodel
and multi-catalog identity work in the other packages of #1111.

## Verification

The dependency-free production contract and relation policy were compiled with
Java 21 and exercised by 14 and 20 executable checks respectively. The child-set
checks first failed on the old identity behavior. Removing quotation validation
from a private copy of the relation policy also fails its invalid-evidence check.
The published source is restored and all 34 checks pass after that mutation.

JUnit entry points wrap both executable suites. Further JUnit/Mockito tests cover
shared parser use, preserved category/product behavior, typed/legacy run-control
observation, cancellation after a typed response, actual parser/matrix use with a
mocked existing LLM boundary, incoming relations, malformed responses, batch
limits, duplicates, and a caller mutating its list during the provider call.

The local environment has no Maven executable and no complete reactor checkout.
Only the two dependency-free production classes and their executable checks were
compiled locally. Framework-dependent production classes and JUnit tests still
require the ordinary source-built CI; no stubs or substitute runtime were used to
claim a full application build. No workflow, test selector, coverage threshold,
dependency inventory or production configuration is relaxed by this increment.

Run the focused integration tests in a full checkout:

```sh
mvn -B -pl taxonomy-analysis -am \
  -Dtest=ChildAssessmentContractTest,RelationAssessmentTest,SharedChildAssessmentParserTest,AnalysisRunControlTypedTest,RelationChildAssessmentServiceTest,LlmResponseParserTest,LlmResponseRootTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Then run the repository's normal full verification, coverage and architecture
boundary checks. This increment neither closes #1111 nor certifies the complete
relationship-synthesis workflow.
