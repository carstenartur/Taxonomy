# Task 1 — rejected wording recovery

## Scope and behavior

Rejected statements retain their stable ID, wording, provenance and review state as evidence. Exact replay of their wording in generated statements or summaries is blocked at node parsing, and guarded again when complete synthesis or reconciliation publishes cached/step results. Active text omits rejected statements; source coverage does not count rejected statements. Human edits, originals and late-result fencing are unchanged.

## Test evidence

- Initial baseline attempt failed before tests: Maven build-cache extension resolution; controller adjusted the per-command proxy settings. Next attempt reached test compilation, which caught a regression fixture lambda capture error, corrected in the test-only checkpoint.
- `python3 .superpowers/sdd/reformulation-completion/run-maven.py -pl taxonomy-analysis -am test -Dtest=WalkUpReformulationTest,ReformulationResponseParserTest,FrozenReformulationEngineTest,CrossTaxonomyReconciliationTest,ReformulationCoverageValidatorTest -Dsurefire.failIfNoSpecifiedTests=false` before production edits: 32 tests, 3 failures, 17 errors. The 17 errors were Mockito inline self-attach denial in this sandbox; do not count them as behavior regressions. The controller configured an existing Mockito jar as a startup agent in the wrapper, without product changes.
- Safe no-mock RED confirmation with `-Dtest=WalkUpReformulationTest,ReformulationResponseParserTest,ReformulationCoverageValidatorTest`: 14 tests, 2 expected failures, 0 errors. Unchanged `WalkUpReformulationTest` baseline: 4/4 pass. The new parser and coverage cases failed for missing rejection/coverage checks.
- Mockito-enabled RED with `-Dtest=FrozenReformulationEngineTest,CrossTaxonomyReconciliationTest`: 18 tests, 2 expected failures, 0 errors. Complete synthesis published a new-ID replay as UNREVIEWED; reconciliation rendered a rejected statement and repeated it in a section summary.
- Focused GREEN with the same five-class command above after implementation: 32 tests, 0 failures, 0 errors, 0 skipped; Maven exit 0. An existing Mockito startup-agent warning and Java class-sharing warning remain environmental, not test failures.
- Broader taxonomy-analysis unit selection: `test_names=$(rg --files taxonomy-analysis/src/test/java | rg '/[^/]+Tests?\.java$' | sed -E 's#.*/([^/]+)\.java#\1#' | paste -sd, -); python3 .superpowers/sdd/reformulation-completion/run-maven.py -pl taxonomy-analysis -am test "-Dtest=$test_names" -DexcludedGroups=real-llm -Dsurefire.failIfNoSpecifiedTests=false`: 588 tests, 0 failures, 0 errors, 0 skipped; Maven exit 0. This selects explicit `*Test`/`*Tests` classes in the module and compiles upstream dependencies; it is not the full reactor/integration gate.

## Changed files

`taxonomy-analysis/src/main/java/com/taxonomy/analysis/reformulation/RejectedWordingGuard.java`, `ReformulationResponseParser.java`, `FrozenReformulationEngine.java`, `CrossTaxonomyReconciler.java`, `ReformulationCoverageValidator.java`; corresponding focused test files `ReformulationResponseParserTest.java`, `FrozenReformulationEngineTest.java`, `CrossTaxonomyReconciliationTest.java`, `ReformulationCoverageValidatorTest.java`.

## Limitations

The check is literal exact-wording replay (including the same contiguous wording embedded in a summary), not semantic paraphrase detection. Original source remains immutable and may contain identical text independently of rejected model additions. Broad integrated testing belongs to the controller.

## Self-review

The parser rejects replay before returning a node result, allowing its existing bounded repair/failure path. The publication guard additionally handles checkpointed or injected node results, marks new-ID replay evidence REJECTED, and withholds contaminated summaries with an empty value plus conflict finding. Original rejected records are not rewritten; reconciliation and affected synthesis filter only active rendering. No provider, persistence, version, scope or architecture changes were made. The focused suite cannot prove semantic-equivalence detection, which is intentionally out of scope.

## Independent review correction, round 1

The reviewer identified two edge cases: a fixed withheld-summary sentence could itself contain a short rejected phrase, and affected synthesis could replace an existing HUMAN rejected record on a same-ID replay. The first correction uses an empty summary plus the existing explicit conflict finding; the second retains the exact prior statement in both the document and node result.

- RED before correction: `python3 .superpowers/sdd/reformulation-completion/run-maven.py -pl taxonomy-analysis -am test -Dtest=FrozenReformulationEngineTest,CrossTaxonomyReconciliationTest -Dsurefire.failIfNoSpecifiedTests=false`: 20 tests, 2 expected failures, 0 errors. The collision case yielded `Summary withheld: rejected wording requires review.` for rejected `requires review`; the same-ID case returned MODEL editing origin in place of HUMAN.
- Corrected focused GREEN with the same five-class command used above: 34 tests, 0 failures, 0 errors, 0 skipped; Maven exit 0. Both new review regressions pass. The prior 588-test module run preceded this two-line correction and was deliberately not repeated; controller owns the final integrated gate.
