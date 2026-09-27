# Task 2b — adopted lineage and portable evidence

Status: RED test checkpoint, not implementation or acceptance. This checkpoint is
deliberately expected to fail and is not merge-ready.

## Current slice

- Added real portfolio import tests for a duplicate root, a checksum-valid payload
  with duplicate JSON keys, and a checksum-valid trailing JSON token. The tests
  require rejection before ordinary portfolio materialization changes the target.
- No production behavior changed yet. RED verification is the next action after
  remote persistence of this checkpoint.

## Next exact command

`python3 .superpowers/sdd/reformulation-completion/run-maven.py -pl taxonomy-app -am test -Dtest=ReformulationEvidenceRoundTripTest -Dsurefire.failIfNoSpecifiedTests=false`

Use this report for evolving RED/GREEN counts, decisions, limitations and the
next exact command. Root owns full-reactor CI and publication verification.
