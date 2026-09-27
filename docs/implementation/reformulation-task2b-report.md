# Task 2b — adopted lineage and portable evidence

Status: strict-decoder implementation in progress, not Task 2b acceptance.

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
  Existing v1 serialization stays unchanged. GREEN unverified.

## Next exact command

`python3 .superpowers/sdd/reformulation-completion/run-maven.py -pl taxonomy-app -am test -Dtest=ReformulationEvidenceRoundTripTest -Dsurefire.failIfNoSpecifiedTests=false`

Use this report for evolving RED/GREEN counts, decisions, limitations and the
next exact command. Root owns full-reactor CI and publication verification.
