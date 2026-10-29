# Requirement reformulation acceptance

## Deterministic application gate

Run the Maven-owned selection with Java 25 and Docker:

```sh
./mvnw -B -ntp -pl taxonomy-app -am test -Pscenario-acceptance
```

The profile and `.mvn/verification-suites.json` explicitly select the existing
scenario and browser contracts plus `ReformulationScenarioAcceptanceTest`,
`ReformulationScenarioBrowserTest` and `ReformulationAuthoredScenarioTest`.
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

Evidence is under `taxonomy-app/target/reformulation-scenario-acceptance/`: prompts,
calls, fatal mismatches, jobs, snapshots, revisions, receipts, exports, child-process
logs, screenshots and restart identity. CI excludes the temporary database itself.
The document gate independently renders the actual DOCX exports with LibreOffice,
compares their content with paired historical JSON, rejects empty body pages and
checks measured page limits. It preserves PDFs, page images and hashes. Invoke after
compiling `taxonomy-tooling`:

```sh
java -cp taxonomy-tooling/target/classes com.taxonomy.tooling.TaxonomyTooling \
  check-scenario-documents --reformulation-only \
  --artifacts taxonomy-app/target/reformulation-scenario-acceptance
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

Each invocation creates a fresh UUID directory under
`taxonomy-app/target/reformulation-model-comparison/`. Use the run directory printed
by the test; files from an earlier run cannot satisfy the current run. The manifest
records the source commit, exact case inventory and corpus hash, source/context/input
hashes, output and artifact hashes, actual HTTP attempts/retries, available provider
usage, durations and explicit per-case outcomes. A live evaluation requires a clean,
committed source tree so that the recorded commit identifies the code evaluated.
The fixed front remains an explicit evaluation assumption.

Execution states are deliberately separate:

| State | Meaning |
|---|---|
| `NOT_RUN` | No configured generative provider; the opt-in JUnit test is skipped. |
| `RUNNING` | Execution has not recorded a terminal result; interruption is not complete evidence. |
| `INCOMPLETE_EXECUTION` | A case, baseline, artifact write or run finalization did not finish. |
| `SCHEMA_FAILURE` | All pairs were recorded, but at least one result failed schema or real-HTTP evidence checks. |
| `OUTPUTS_REQUIRE_HUMAN_REVIEW` | Exactly five complete, persisted, validated real-HTTP pairs are available; semantic quality is still unreviewed. |

Literal presence diagnostics do not judge semantic quality. Playback cannot substitute
for live evidence. No price or cost improvement is inferred without a documented
price basis. `summary.md` shows the recorded execution observations without assigning
a model-quality verdict.

For each complete case, a human edits its generated `human-review.json`. Keep the
`runId`, `case`, `walkUpOutputSha256` and `singlePromptOutputSha256` bindings unchanged.
Set `status` to `REVIEWED`, provide the actual reviewer and an ISO UTC timestamp such
as `2026-10-29T12:00:00Z`, and complete all five criteria: source-condition loss,
unmarked additions, usefulness of concrete questions, duplication/conflicts and
readability. Scores are integers from 0 (unacceptable) to 4 (fully satisfactory).
Each criterion requires a written `evidence` rationale and verbatim citations from
**both** recorded outputs. A citation has this shape:

```json
{"variant": "walk-up", "passage": "verbatim text from an output field"}
```

Use `single-complete-prompt` for the second strategy's citation. Citation text must
occur inside a textual field of that strategy's exact recorded `output`; transport
metadata and invented passages cannot satisfy the citation check. Retain contradictions
for explicit decisions rather than rewarding an invented resolution. Reviewers may
record multiple citations per strategy and should explain omissions or unsupported
additions in the rationale. The tool checks completeness and binding; it cannot prove
that the assessment was independent or semantically correct.

Validate and retain the completed review with the **same existing Maven profile**,
selecting its provider-free JUnit class explicitly. Use an absolute path to one run:

```sh
./mvnw -B -ntp -pl taxonomy-app -am test -Preformulation-real-llm \
  -Dtest=ReformulationHumanReviewTest \
  -Dreformulation.review.run=/absolute/path/to/taxonomy-app/target/reformulation-model-comparison/RUN_UUID
```

This selection does not initialize Spring or invoke a provider. It rejects absent,
incomplete, different-corpus or modified-output evidence, mismatched review bindings,
missing criteria, scores outside 0–4, invalid dates and absent or incorrect citations.
On success it writes `human-review-validation.json`, updates `summary.md` and records
`humanQualityGate: REVIEW_RECORDED`. The execution state remains
`OUTPUTS_REQUIRE_HUMAN_REVIEW` as the name of the completed-output stage; the separate
human gate states whether a complete review was recorded. This is a record of the
human assessment, not an automatic semantic pass or a claim that one strategy wins.
A failed revalidation resets the human gate and removes the previous validation and
archive so changed reviews cannot retain a stale success marker.

The command also creates `RUN_UUID-reviewed.zip` beside the run directory. Retain this
archive as the review artifact (for example, in the existing CI artifact retention or
an authorized evidence store); it contains the corpus, inputs, outputs, manifest,
review files, validation and readable summary. Retention uses an exact artifact inventory:
unrelated files, noncanonical names and symbolic links (including links in case or
run-directory paths) are rejected. The archive contains the same frozen bytes that
were validated. Review artifacts are staged before publication; a failed ZIP or
final publication resets the human gate and removes successful summary/validation/
archive artifacts. Keep raw execution evidence under
`target/` or in artifact storage. Commit only concise, manually written findings and
reproduction commands when updating the project report; do not make the historical
status file green merely because evidence-validation tests pass.

No provider was configured for the historical recorded local run. The checked-in
`reformulation-model-comparison-status.json` therefore remains `NOT_RUN` and
`NOT_REVIEWED`. Live-model quality and any claim of superiority remain unverified.
