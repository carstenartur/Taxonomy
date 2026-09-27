# Task 2b — adopted lineage and portable evidence

Status: focused Task 2b verification green through source/schema correction;
independent review and root's integrated gate pending. No product acceptance or
real-provider quality claim.

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
- Remaining: one narrow DSL property-schema regression is being verified;
  independent scoped review and root's integrated/full-reactor checks follow.
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
  closed even if its first payload and hash are valid; the narrow test is added
  but not yet run.

## Next exact command

`python3 .superpowers/sdd/reformulation-completion/run-maven.py -pl taxonomy-app -am test -Dtest=ReformulationEvidenceRoundTripTest#duplicateOrUnknownEvidenceDslPropertyRejectsBeforeMaterialization -Dsurefire.failIfNoSpecifiedTests=false`

Use this report for evolving RED/GREEN counts, decisions, limitations and the
next exact command. Root owns full-reactor CI and publication verification.
