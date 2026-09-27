# Requirement reformulation acceptance

## Deterministic application gate

Run the Maven-owned selection with Java 21 and Docker:

```sh
./mvnw -B -ntp -pl taxonomy-app -am test -Pcivilian-acceptance
```

The profile and `.mvn/verification-suites.json` explicitly select the existing
civilian scenario and browser contracts plus `ReformulationCivilianAcceptanceTest`,
`ReformulationCivilianBrowserTest` and `ReformulationAuthoredScenarioTest`.
The CI job requires positive named JUnit results; a successful reactor with zero
selected tests is insufficient. The ordinary full CI, coverage, database, security,
UI and transport gates remain required independently.

Only the outbound provider exchange is replaced. Semantic playback validates the
full source and node/child/directed-edge/answer scope. Unknown requests remain fatal
even if the application catches the provider error. Authored scores select a bounded
BP/IP catalogue front under the production analysis budgets. They do not claim that
other catalogue fronts are irrelevant or that an actual model would choose them.
The sourced flood corpus remains unchanged for the existing broad acceptance test.
Additional authored time-recording and cross-taxonomy cases retain terminal-only,
no-browser, numerical and unmapped original constraints through actual analysis.

The authenticated application path creates a requirement, runs analysis, reads its
persisted snapshot and creates the offer. It checks bottom-up and reconciliation
calls, shared versus separate questions, answer and deferral, targeted rewording,
manual-draft protection, stale revisions, comparison, explicit adoption and replay,
four historical exports, real reanalysis and inherited decisions. It materializes an
explicit checkpoint and rejects a foreign-workspace read. A second application
process reads the same file database and verifies history without provider calls.
Source text, active version and architecture must remain unchanged before adoption.

The browser class uses the same HTTP-created offer at desktop and an exact 390px
viewport. It exercises keyboard answers/focus, live status, unsaved draft retention,
download and explicit adoption. The existing `ReformulationBrowserTest` additionally
covers all answer kinds, conditional questions, separate deferral and late results.
No HTML, final database state or prepared analysis result is inserted into this path.

Evidence is under `taxonomy-app/target/reformulation-civilian-acceptance/`: prompts,
calls, fatal mismatches, jobs, snapshots, revisions, receipts, exports, child-process
logs, screenshots and restart identity. CI excludes the temporary database itself.
The document gate independently renders the actual DOCX exports with LibreOffice,
compares their content with paired historical JSON, rejects empty body pages and
checks measured page limits. It preserves PDFs, page images and hashes. Invoke after
compiling `taxonomy-tooling`:

```sh
java -cp taxonomy-tooling/target/classes com.taxonomy.tooling.TaxonomyTooling \
  check-civilian-documents --reformulation-only \
  --artifacts taxonomy-app/target/reformulation-civilian-acceptance
```

Local focused successes and current-head CI are recorded separately in
[the task report](../implementation/reformulation-task4-report.md). A pending or
missing gate is not a pass.

## Separate real-provider and human-quality gate

Configure a supported generative provider using the application's existing provider
settings, then opt in explicitly:

```sh
./mvnw -B -ntp -pl taxonomy-app -am test -Preformulation-real-llm
```

`ReformulationRealModelComparisonTest` is tagged `real-llm` and excluded from normal
CI. It never installs playback. The five authored cases are sparse, precise with
negative/numerical constraints, contradictory, multilevel and intentionally
incomplete. Both strategies use identical source, frozen catalogue context, provider
and model: production walk-up plus reconciliation versus a single complete prompt.
The fixed front is an explicit evaluation assumption, not an analysis-quality result.

`taxonomy-app/target/reformulation-model-comparison/` records source/context hashes,
actual HTTP attempts/retries, available provider usage, duration, validated output or
execution failure. Literal presence diagnostics are not semantic quality judgments.
Without a configured generative provider the manifest says `NOT_RUN` and JUnit skips
the opt-in case. Playback must never substitute for that evidence. No price or cost
improvement is inferred without a documented price basis.

For each case, a human must assess both outputs in `human-review.json`: source-condition
loss, unmarked additions, usefulness of concrete questions, duplication/conflicts and
readability. Scores range from 0 (unacceptable) to 4 (fully satisfactory); enter cited
output passages, reviewer and date. Retain contradictions for explicit decisions
rather than rewarding an invented resolution. `NOT_REVIEWED` remains open until
this review is performed, even when both outputs are structurally valid.

No provider was configured for the recorded local run. Live-model quality and any
claim of superiority therefore remain unverified.
