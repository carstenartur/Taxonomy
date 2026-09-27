# Task 4 — real application acceptance and separate model-quality gate

Start after Tasks 1–3 are integrated and independently reviewed. This is package 8
completion, not a replacement for the existing acceptance suite.

## Deterministic application path

- Extend the existing semantic `ScenarioLlmPlayback` and civilian HTTP replacement.
  Dispatch reformulation responses by task, full source identity, node/child/edge
  identities and relevant answers, never by invocation order. Unknown or ambiguous
  calls must fail the test even when application retry/error handling catches them.
- Retain the sourced `civilian-flood.json` bindings and provenance. Add authored
  time-recording and cross-taxonomy fixtures, clearly labelled as test assumptions,
  including terminal-only/no-browser, weak-score numerical constraints and unmapped
  original content. Do not turn test choices into purported external requirements.
- Test the real path: authenticated requirement creation -> actual analysis ->
  persisted snapshot -> bottom-up NODE/RECONCILE calls -> shared/separate questions
  -> answer and deferral -> targeted REWORD -> comparison -> explicit adoption ->
  new analysis/new offer with inherited provenance -> all four historical exports
  -> explicit atomic checkpoint -> a second application process reading the same DB.
- Verify original/current pointer/architecture remain unchanged before adoption;
  inspect actual decisions and provenance after reanalysis/import/restart, not merely
  that an opaque archive exists. Exercise manual-edit preservation and stale result
  publication, duplicate commands and foreign scope using real application paths.
- Replace only remote LLM replies. Do not inject prepared architecture results,
  final database state, HTML, controller outcomes, rendered files or fake budgets.
  Keep production context limits. Authored remote scores may select bounded relevant
  catalogue fronts; inspect the resulting real analysis rather than constructing it.
- Check the existing desktop and 390px UI with a real browser: keyboard/focus,
  live status, unsaved draft retention, answer types/conditional questions, explicit
  adoption and download. Preserve genuine screenshots and failures as CI artifacts.

## Explicit test authority and documentation

- Add the new acceptance classes explicitly to `civilian-acceptance` and
  `.mvn/verification-suites.json`, plus artifact collection/positive test evidence
  where required. Keep Maven as test-selection authority; no competing script suite.
- Extend independent DOCX rendering checks for the new artifacts. Update German and
  English help, README short entry, architecture description and
  `docs/testing/requirement-reformulation.md` with actual integrated behavior.
- Verify positive JUnit class/count evidence, not a zero-test success permitted by
  `failIfNoSpecifiedTests=false`. Root owns the final full regression and CI gate.

## Separate real-model comparison

- Use the existing explicit `real-llm` exclusion boundary for opt-in remote tests.
  At the recovery base, this tag is configured but a concrete live evaluation class
  was not found; inspect current code and extend the existing boundary rather than
  adding a hidden network dependency to regular CI.
- Provide five authored cases: sparse, precise/negative/numerical, contradictory,
  multilevel and deliberately incomplete. Compare walk-up with a single complete
  prompt on identical source/context/provider/model settings.
- Record actual calls/retries, available token usage, duration and schema outcomes.
  Quality rubric separately covers source-condition loss, unmarked additions,
  concrete question usefulness, duplication/conflicts and readability. Preserve
  reviewable outputs and distinguish automated checks from human judgments.
- No blanket quality-improvement claim. Cost figures require a documented price
  basis. No provider configuration means `NOT RUN`, never substitute playback or
  fabricated outputs as real-language evidence. A human-reviewed quality gate stays
  explicitly open if no such review has occurred.
- Recovery preflight found no configured standard provider-key environment variables
  (presence only checked); no Docker/Chrome executable was found. LibreOffice and
  Poppler are available. GitHub's existing civilian browser lane passed on the Task 1
  checkpoint. Recheck capabilities when needed; use legitimate configured tools,
  never extract credentials or bypass a permission wall.

## Durability and reporting

One implementer, no subagents. Follow tests-first and the mandatory remote checkpoint
protocol before long tests / at most 10 minutes of editing. Persist report and exact
next command in `docs/implementation/reformulation-task4-report.md`. Independent
review precedes any merge consideration. Do not merge automatically. Report browser,
restart, parser, CI and actual-provider results separately, with explicit open gates.
