# Complete evidence in grouped requirement reformulation

This package-5 correction preserves the actual formulation supplied by every
child group and the complete discovery context of every open question. It neither
adopts a proposal nor changes original requirements, active architecture or human
edits.

## Two reproduced losses

On `c29def6`, parent aggregation replaced each group's `statementProposals` with
an empty array and kept only IDs. Conditions, source links and the actual new
wording were no longer visible in the parent call, although persisted separately.
The aggregate now includes the complete group statements, alongside summaries
and preserved references. It still does not re-expand every deeper source
statement: the complete original and the children's newly formulated contributions
are the inputs of the next abstraction level. Storage and preservation validation
continue to retain the underlying detail evidence.

The old aggregate prompt also replaced every discovery context longer than 512
characters with a pointer to storage. This affected unique context as well as
duplicates. The remote model had no tool for following that pointer.

There is no length-based omission now. Identical repeated context strings longer
than 128 characters are factored into `discoveryContextTable` **inside the same
request**. A `contextRef` resolves only into that table. Unique and short context
strings remain complete and inline. Current discoveries and pre-merge origins
are treated identically. The prompt explains the representation and identifies
the strings as untrusted data, not instructions. References are prompt-local,
not new persistent question IDs. Domain objects are never mutated.

## Budgets and honest partial results

Final-prompt budgets and the limit of 16 groups plus aggregation are unchanged.
The original requirement, question contracts, conditions and directed boundaries
are not truncated to make a request fit. A union of unique discovery contexts can
still exceed the provider's limit. In that case the complete groups already
produced remain available and aggregation fails explicitly with
`INPUT_TOO_LARGE_FOR_PROVIDER`; no oversized parent request is sent and no
complete proposal is published.

The old large-terminal fixture could succeed only because its two distinct
discovery contexts were cut. Its contract now checks the explicit limit, both
retained group results, all six terminal descriptions and the absence of a final
request. No budget was raised. The normal wide-child case still succeeds with
two groups and one parent call, now including the actual group wording.

## Cache compatibility

`ReformulationPromptBuilder.INPUT_ENCODING_VERSION` is included in every
checkpoint fingerprint. This prevents an outer NODE/REWORD checkpoint produced
by the old lossy representation from bypassing the corrected encoder. Existing
records remain immutable and historically readable. The first new run after the
upgrade may recompute previously cached work; subsequent identical v2 inputs
reuse their own validated results. An upgrade does not start a run or adopt
wording by itself.

## Reproductions and regression coverage

`GroupedEvidenceFidelityTest` delegates to six executable plain-Java checks:
complete group statement fields; unique long context; repeated in-request
contexts without conflating distinct strings; merged origins and repair input;
the actual gateway/budget/parser group flow; and the irreducible-context case.
All six failed as intended against the predecessor implementation and passed
with the correction. The gateway uses authored loopback responses, not a cloud
model; these tests establish data preservation, not model semantic quality.

`ReformulationEncodingTest` starts a fresh actual application process, discovers
the real BP catalogue root and persists an explicitly authored snapshot. It
stores an exact predecessor-format checkpoint, proves it is not reused by the
new encoder, proves the next identical call is cached, and verifies that the
old record, saved offer and active requirement version/count are unchanged.
The original-format implementation failed this check by returning the old
result. The changed implementation passed. The JUnit wrapper forwards an
existing JaCoCo agent; it does not fabricate coverage.

The existing bounded-parent checks and actual serial/parallel gateway fixture
also pass locally. The latter retains equal documents, four calls per run and
maximum concurrency two. Test processes use Java 21 and the exact c29def6 CI
runtime tree `352d917a1c8dd05f13a3f00d8a8e708be2c10bb6`.

## Measured dependency inventory

Workflow `35654592934`, artifact `10664980002` on c29def6 contains 2,391 tests:
2,390 passed, one dependency-ratchet failure, zero errors/skips. The only
inventory difference is
`composition.reformulation -> portfolio.reformulation: 31 -> 32`.
The already introduced `ReformulationExecutionService.LocalExecution` now holds
`ReformulationRecoveryService.Claim`; source and bytecode confirm that exact
additional class pair. The baseline changes only this measured count. No rule,
direction, threshold or Maven dependency changes.

## Verification limits

Local Java compilation, executable checks, `git diff --check`, and exact-source
comparison are available. The canonical `./mvnw verify -DexcludedGroups='real-llm'`
and the updated supplemental Maven command were attempted but failed before
compilation while downloading Maven 3.9.16. The supplemental workflow retains
all existing suites, adds the fidelity and encoding checks, and requires positive
JUnit counts. Its new-head reports and full CI remain the authoritative Maven,
coverage, architecture and database evidence. No independent review or live-model
quality result is implied by local checks.
