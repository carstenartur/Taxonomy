# Task 2b — adopted lineage and portable evidence

Status: fix round 1 focused verification green; scoped re-review and root's
integrated gate pending. No product acceptance or real-provider quality claim.

Fix round 2 (scoped re-review head `0f8a16320ec2762a063d3c3207ea2f5c3cd169cc`):
findings 1 and 3 closed; finding 2 remains open for a reachable generic
`local` statement-edit scope. RED candidate adds B-only local edit question
and answer to the A/B frozen projection, asserting they do not leak into A
while B and a genuinely unmappable retired edit remain visible. Unexecuted at
this checkpoint. Next exact command:
`python3 .superpowers/sdd/reformulation-completion/run-maven.py -pl taxonomy-analysis -am test '-Dtest=AdoptedLineagePromptTest#branchCallsExcludeUnrelatedInheritedDecisionsButKeepGlobalAndUnmapped' -Dsurefire.failIfNoSpecifiedTests=false`

Fix round 1 (review head `7cdaeec40a511b3fe73d070693ceabb9f1be6a4d`):
three Important findings accepted. New tests first cover a fresh adopted offer's
historical rejected wording and branch-local/global/unmappable prompt selection.
Focused RED: 15 analysis tests, two expected failures (unrelated branch context
leaked; historical rejected summary accepted), zero errors, build failed;
`/tmp/task2b-review1-red.log`. Candidate per-call selector/guard and v4 input
encoding are being checked. Separately,
root's database CI had 343 portfolio tests with one existing boundary-fixture
failure: its fully mocked mapper returned null from new `rebuild()` before the
intended typed write failure; a real mapper spy now faults only serialization.
First focused candidate GREEN: AdoptedLineagePromptTest 5/5,
ReformulationResponseParserTest 10/10, PortfolioJsonCodecBoundaryTest 2/2,
zero failures/errors/skips, build success (`/tmp/task2b-review1-green1.log`).
An additional bounded Spring test now drives actual Copilot analysis after
adoption, selecting its persisted snapshot for a new offer and checkpoint;
only outbound RestTemplate model replies are replaced. It passed **1/1**,
zero failures/errors/skips, build success in 25.92s
(`/tmp/task2b-review1-integration-red.log`; filename is historical, this was
GREEN-only coverage, not a RED). The local embedding model was unavailable,
so the analysis logged its existing zero-vector fallback; the real Copilot
operation nonetheless completed SUCCESS with a newly selected persisted
snapshot. This does not establish real-provider semantic quality.
Selector regression extension: frozen catalogue distinguishes the unrelated
known B branch from an obsolete `RETIRED` mapping that must remain visible;
a boundary question and dependent statement in a distinct ancestor entry must
travel together only for the boundary call. These cases and an engine/layout
postprocessing rejection case passed in the final covering run.
These refinements were added alongside their candidate fix; they are
additional covering regression tests, not a separately observed RED. The
observed review-fix RED remains the earlier 2/15 analysis result. Final
covering GREEN (`/tmp/task2b-review1-final.log`): AdoptedLineagePromptTest
6/6, ReformulationResponseParserTest 10/10, PortfolioJsonCodecBoundaryTest
2/2, ArchitectureContextDependencyRatchetTest 22/22 and
AdoptedLineageRealReanalysisTest 1/1: **41/41**, zero failures/errors/skips,
build success. The embedding fallback described above recurred in this run;
it was not a test failure. Exact command:
`python3 .superpowers/sdd/reformulation-completion/run-maven.py -pl taxonomy-app -am test '-Dtest=AdoptedLineagePromptTest,ReformulationResponseParserTest,AdoptedLineageRealReanalysisTest,PortfolioJsonCodecBoundaryTest,ArchitectureContextDependencyRatchetTest' -Dsurefire.failIfNoSpecifiedTests=false`
Fix diff relative to `03e4208bbd8086c1649240ccb37e2cc2b0d6b76b`: the immutable
archive stays unchanged. Per-node prompts select applicable historical
statements/questions/answers across ancestor entries by frozen node, boundary,
scope and dependency closure; unmappable records stay visible. Parser and
engine/reconciliation rendering guard applicable rejected wording, with v4
input encoding invalidating earlier v3 results. The integration uses real
Copilot analysis orchestration/persistence after adoption but a deterministic
HTTP model reply, not a real model or browser. No full DB/full-reactor rerun
by the implementer; root owns those gates and scoped re-review.

## Verified outcome to date

- Frozen exact local/imported adopted-source evidence, nonrecursive v2 hash-linked
  closure, v1 byte/hash preservation, strict portable JSON/source/target binding,
  and prompt-safe concrete inherited statement/question/answer/review context.
  ADOPTED_SOURCE is distinct from ORIGINAL and both are protected source kinds.
- Before the final trust-boundary correction, eight selected analysis/portfolio/
  architecture classes ran **88/88** positive tests. Afterwards the affected
  Spring/codec/guard slice ran **36/36** (5 codec boundary, 14 guard, 17 round
  trip), zero failures/errors/skips, build success. These are two distinct
  checkpoints, not an inflated combined suite count.
- Final narrow DSL-property correction ran `ReformulationEvidenceRoundTripTest`
  **18/18** and `ReformulationEvidenceCodecBoundaryTest` **5/5**, zero
  failures/errors/skips, build success. This was after the 88-test combined run
  and the 36-test source/schema correction run; each count belongs to its own
  checkpoint and is not a single cumulative suite.
- Remaining: independent scoped review and root's integrated/full-reactor
  checks. No merge or product acceptance claim.
  The service/DB fixtures use persisted analysis snapshots but not the later
  real-analysis civilian browser path. UI labels/action hiding are static code;
  no browser or real provider was used in this task.

## Current slice

- Added real portfolio import tests for a duplicate root, a checksum-valid payload
  with duplicate JSON keys, and a checksum-valid trailing JSON token. The tests
  require rejection before ordinary portfolio materialization changes the target.
- RED run: `ReformulationEvidenceRoundTripTest`, six cases, two expected assertion
  failures (duplicate root and duplicate JSON key were accepted), zero errors.
  The checksum-valid trailing-token case already rejected; this is compatibility
  evidence, not a newly failing RED. The first WIP remote checkpoint was verified
  at `2a9ca999e1d774572ffbe9c1e4c3ab29ab889b90`.
- Strict parsing GREEN: same `ReformulationEvidenceRoundTripTest` command,
  **6/6 positive cases**, zero failures/errors/skips, build success. Global JSON
  decoding remains unchanged; only explicit evidence decoding is strict.
- Next RED slice added local adoption → reanalysis → offer and imported adoption
  → new offer tests. They assert frozen exact evidence hash and concrete wording,
  origin, question, actor and rationale in inherited context, including portable
  evidence binding to a separately materialized workspace.
- RED run: eight cases, two expected failures for missing `adoptedLineage` in
  local/imported new offers, zero errors. The test uses real service/DB/adoption
  and materialization paths with a fixture-persisted analysis snapshot; it is
  not the later real-analysis civilian E2E.
- Candidate GREEN implementation freezes only exact selected-version local
  receipts and matching imported evidence. Baseline archive bytes and separate
  prompt-safe historical decision projection are distinct; old source spans and
  old complete document text remain outside prompt context. Covering GREEN:
  `ReformulationEvidenceRoundTripTest` **8/8**, zero failures/errors/skips,
  build success.
- Next RED: second explicit adoption from an offer based on adopted text must
  export v2 root linking the v1 evidence by hash (no recursive payload), round
  trip into another workspace, and reject a missing referenced ancestor before
  any materialization. These are service/DB paths with fixture snapshots.
- RED run: 10 cases, 2 expected assertion failures: second root remained v1,
  and missing ancestor was accepted. Zero errors; eight earlier cases passed.
- Candidate v2 adds a nested v1-compatible adoption payload plus direct hash
  references, snapshots exact source ancestry from the selected offer, emits
  immutable ancestor blocks separately at the existing checkpoint, and checks
  closure/hash/business/source-version/text binding before materialization.
  Existing v1 serialization stays unchanged. GREEN: `ReformulationEvidenceRoundTripTest`
  10/10 and `ReformulationPositiveReviewGuardTest` 14/14, **24 positive tests**, zero
  failures/errors/skips, build success.
- Next RED analysis slice requires concrete inherited answers/wordings/review in
  NODE, grouped/aggregate, REWORD and RECONCILE prompt data while excluding raw
  archived payload; adopted source remains protected and distinctly attributed
  through engine/reconciliation; model cannot mint adopted-source provenance.
- RED analysis run: `AdoptedLineagePromptTest` 3/3 expected failures and
  `ReformulationResponseParserTest` 1/8 expected failure (four total failures,
  zero errors); the parser failure exposed the not-yet-declared provenance,
  while engine and both prompt builders omitted the required semantics.
- Candidate implementation adds ADOPTED_SOURCE as distinct protected source,
  rejects model forging, carries prompt-safe inherited JSON through all node and
  reconcile calls, bumps checkpoint input encoding, and maps source semantics
  through reconciliation/coverage/UI. GREEN unverified.
- Added exact-span parser regression: even though the selected adopted text is
  byte-for-byte the baseline original, a model must not claim ORIGINAL for it.
  Runtime helper briefly lost its external Maven path; root restored the same
  Maven 3.9.16 under this task's toolchain using the repository wrapper; no
  product/test loss and no meaningful RED from the FileNotFoundError. RED not
  yet run for this test.
- RED now verified: that exact-span claim was accepted, one assertion failure,
  zero errors. Parser candidate explicitly disallows model ORIGINAL for an
  adopted baseline; ordinary source ORIGINAL remains valid. GREEN unverified.
- Focused analysis GREEN after that fix: `AdoptedLineagePromptTest` 3/3,
  `CrossTaxonomyReconciliationTest` 16/16, `ReformulationResponseParserTest`
  9/9, `FrozenReformulationEngineTest` 4/4: **32 positive tests**, zero
  failures/errors/skips, build success.
- Boundary slice adds lowercase portable p/r → physical P/R exact-byte
  preservation, historical full-source statement archived but excluded from
  concrete prompt context, and an inherited-context small-budget failure. The
  full-source exclusion was intended RED; the other two assert compatibility
  and fail-closed behavior. First run: `AdoptedLineagePromptTest` 4/4 and
  `ReformulationEvidenceRoundTripTest` 11/12. The one assertion failed before
  reaching projection because JSON escaped the archived newline; it is a test
  fixture error, not a meaningful product RED. Lowercase identity and budget passed.
- Candidate fix omits only verbatim historical full-source statements from
  prompt context, while retaining them byte-for-byte in the frozen archive;
  all applicable non-source statement wording/review and human decisions remain.
  The test was corrected to decode archived statement wording and check its ID
  absent from the projection; the later 15/15 run verifies this behavior. No
  separate valid RED is claimed for this exclusion.
- Final batched cases added: imported v2 blocking question still blocks positive
  review, checksum-valid v2 wrong ancestor version and duplicate JSON key reject
  before materialization, and backend ADOPTED_SOURCE cannot be rejected or
  edited. UI label/hiding of source actions is a static implementation boundary;
  real browser inspection remains Task 4. RED run: `AdoptedLineagePromptTest`
  4/4, `ReformulationEvidenceRoundTripTest` 14/15; sole expected failure was
  ADOPTED_SOURCE `EDIT` succeeding despite protected-source semantics. Imported
  v2 guard, malformed ancestor/duplicate keys, archive projection, lowercase
  identity, and budget tests passed in this run. Zero errors.
- Candidate fix denies both EDIT and REJECT for either source provenance in the
  service, and hides both controls in the DE/EN UI. Covering combined focused
  GREEN: analysis 33/33; portfolio codec boundary 4/4; architecture ratchet
  22/22; positive review guard 14/14; round trip 15/15. **88 positive tests**,
  zero failures/errors/skips, build success.
- Self-review found an additional trust-boundary gap: v1 payload target text is
  bound to its physical version, but checksum-valid historical `originalText`
  had not been compared with the physical source version. RED verified:
  17 RoundTrip cases, two expected failures (source mismatch and
  unexpected JSON field accepted), zero errors. The preceding 88/88 is
  pre-correction evidence.
- Public decoder check added for an unknown stored schema: the positive-review
  guard delegates to this decoder, so the decoder must reject v99 rather than
  parse it as v1. RED verified: one test, one expected failure, zero errors.
- Candidate narrow fix enables unknown-property rejection only for evidence
  decoding, validates exact physical source-version text/hash on import and
  local receipt projection, and rejects unsupported schema at the shared
  payload decoder. Affected GREEN: codec boundary 5/5, guard 14/14, RoundTrip
  17/17, **36 tests**, zero failures/errors/skips, build success. No global JSON
  codec default changes.
- Final self-review noted `BlockAst.property()` takes the first matching DSL
  property. A duplicate or unknown property on an evidence block must fail
  closed even if its first payload and hash are valid. RED: one real-import
  test, one expected failure (duplicate property accepted), zero errors. The
  codec now requires exactly four distinct evidence properties and no child or
  extension content. Covering GREEN: RoundTrip 18/18 and codec boundary 5/5,
  **23 tests**, zero failures/errors/skips, build success.

## Focused verification invocations

These are separate runs in chronological order; `/tmp` logs are transient and
are not committed artifacts. The reusable runner supplies Java 21, Maven and
the Mockito agent without changing the project build.

- Pre-correction combined 88/88 (`/tmp/task2b-final-focused.log`):
  `python3 .superpowers/sdd/reformulation-completion/run-maven.py -pl taxonomy-app -am test '-Dtest=AdoptedLineagePromptTest,ReformulationEvidenceRoundTripTest,ReformulationPositiveReviewGuardTest,ReformulationEvidenceCodecBoundaryTest,ReformulationResponseParserTest,FrozenReformulationEngineTest,CrossTaxonomyReconciliationTest,ArchitectureContextDependencyRatchetTest' -Dsurefire.failIfNoSpecifiedTests=false`
- Source/schema correction 36/36 (`/tmp/task2b-source-green.log`):
  `python3 .superpowers/sdd/reformulation-completion/run-maven.py -pl taxonomy-app -am test '-Dtest=ReformulationEvidenceRoundTripTest,ReformulationEvidenceCodecBoundaryTest,ReformulationPositiveReviewGuardTest' -Dsurefire.failIfNoSpecifiedTests=false`
- Final DSL-property RED, one expected assertion failure
  (`/tmp/task2b-dsl-red.log`):
  `python3 .superpowers/sdd/reformulation-completion/run-maven.py -pl taxonomy-app -am test '-Dtest=ReformulationEvidenceRoundTripTest#duplicateOrUnknownEvidenceDslPropertyRejectsBeforeMaterialization' -Dsurefire.failIfNoSpecifiedTests=false`
- Final DSL-property GREEN 23/23 (`/tmp/task2b-dsl-green.log`):
  `python3 .superpowers/sdd/reformulation-completion/run-maven.py -pl taxonomy-app -am test '-Dtest=ReformulationEvidenceRoundTripTest,ReformulationEvidenceCodecBoundaryTest' -Dsurefire.failIfNoSpecifiedTests=false`

## Scope self-review and limits

- v1 payload serialization remains the existing `Payload` path; imported v1
  bytes and hashes survive round trip. v2 wraps an adoption payload with direct
  ancestor hashes, and the existing portfolio checkpoint emits each ancestor as
  a distinct immutable block. Import checks exact root uniqueness, JSON and DSL
  schema, source/target version text/hash, closure, cycles and business identity
  before materialization. Portable p/r can bind physical P/R without rewriting
  evidence bytes.
- The selected adopted original is `ADOPTED_SOURCE`, not a new user approval.
  The model cannot mint it or relabel exact adopted text `ORIGINAL`; engine,
  coverage, reconciliation, service actions and DE/EN UI source labels share
  protected-source semantics. Existing review metadata is retained as history,
  not promoted to current review.
- Frozen prompt projection includes historical hashes, applicable statement
  wording/origin/review, question/source-resolution values and rationales, and
  human answer events with actor, supersession, values and rationale. Raw
  portable archive, obsolete spans and old whole-source statement text remain
  outside NODE, grouping/aggregate, REWORD and RECONCILE prompts. The existing
  final-prompt budget path fails visibly when inherited context will not fit;
  input encoding v4 invalidates old lossy and unscoped checkpoint entries.
- Service/DB tests cover local and imported adoption paths and a second
  generation using persisted fixture snapshots. A separate bounded test now
  drives actual post-adoption Copilot reanalysis to a persisted snapshot and
  subsequent offer/checkpoint, replacing outbound HTTP model replies only.
  It is not the full civilian/browser path, which remains Task 4. No
  real-provider test, full-reactor run, DOCX work, or independent review was
  performed by this implementer. The unchanged architecture dependency
  ratchet passed 22/22 in the fix covering run; no dependency baseline was
  increased.

## Next exact action

Independent scoped re-review of `git diff 03e4208bbd8086c1649240ccb37e2cc2b0d6b76b..HEAD`
against the three accepted Important findings in
`docs/implementation/reformulation-task2b-review.md`, then root's integrated
gate. Do not repeat Tasks 1/2a or infer real-language quality from transport
playback.

Use this report for evolving RED/GREEN counts, decisions, limitations and the
next exact command. Root owns full-reactor CI and publication verification.
