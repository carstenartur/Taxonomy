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
- The trust-boundary JSON decoder now enables duplicate-key and trailing-token
  rejection; exact repeated roots are rejected before materialization. Covering
  GREEN has not yet been run.

## Next exact command

`python3 .superpowers/sdd/reformulation-completion/run-maven.py -pl taxonomy-app -am test -Dtest=ReformulationEvidenceRoundTripTest -Dsurefire.failIfNoSpecifiedTests=false`

Use this report for evolving RED/GREEN counts, decisions, limitations and the
next exact command. Root owns full-reactor CI and publication verification.
