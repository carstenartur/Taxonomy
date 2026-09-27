# Task 1 — rejected wording recovery

## Scope and behavior

Rejected statements retain their stable ID, wording, provenance and review state as evidence. Exact replay of their wording in generated statements or summaries is blocked at node parsing, and guarded again when complete synthesis or reconciliation publishes cached/step results. Active text omits rejected statements; source coverage does not count rejected statements. Human edits, originals and late-result fencing are unchanged.

## Test evidence

- Initial baseline attempt failed before tests: Maven build-cache extension resolution; controller adjusted the per-command proxy settings. Next attempt reached test compilation, which caught a regression fixture lambda capture error, corrected in the test-only checkpoint.
- `python3 .superpowers/sdd/reformulation-completion/run-maven.py -pl taxonomy-analysis -am test -Dtest=WalkUpReformulationTest,ReformulationResponseParserTest,FrozenReformulationEngineTest,CrossTaxonomyReconciliationTest,ReformulationCoverageValidatorTest -Dsurefire.failIfNoSpecifiedTests=false` before production edits: 32 tests, 3 failures, 17 errors. The 17 errors were Mockito inline self-attach denial in this sandbox; do not count them as behavior regressions. The controller configured an existing Mockito jar as a startup agent in the wrapper, without product changes.
- Safe no-mock RED confirmation with `-Dtest=WalkUpReformulationTest,ReformulationResponseParserTest,ReformulationCoverageValidatorTest`: 14 tests, 2 expected failures, 0 errors. Unchanged `WalkUpReformulationTest` baseline: 4/4 pass. The new parser and coverage cases failed for missing rejection/coverage checks.
- Mockito-enabled RED with `-Dtest=FrozenReformulationEngineTest,CrossTaxonomyReconciliationTest`: 18 tests, 2 expected failures, 0 errors. Complete synthesis published a new-ID replay as UNREVIEWED; reconciliation rendered a rejected statement and repeated it in a section summary.
- GREEN: pending focused run after implementation checkpoint.

## Changed files

`taxonomy-analysis/src/main/java/com/taxonomy/analysis/reformulation/RejectedWordingGuard.java`, `ReformulationResponseParser.java`, `FrozenReformulationEngine.java`, `CrossTaxonomyReconciler.java`, `ReformulationCoverageValidator.java`; corresponding focused test files `ReformulationResponseParserTest.java`, `FrozenReformulationEngineTest.java`, `CrossTaxonomyReconciliationTest.java`, `ReformulationCoverageValidatorTest.java`.

## Limitations

The check is literal exact-wording replay (including the same contiguous wording embedded in a summary), not semantic paraphrase detection. Original source remains immutable and may contain identical text independently of rejected model additions. Broad integrated testing belongs to the controller.
