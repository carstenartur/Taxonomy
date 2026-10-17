# Analysis Reason Length Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let an administrator choose the depth of newly generated analysis justifications and preserve that choice throughout each analysis and its saved evidence.

**Architecture:** Use a shared domain enum and the existing preferences-to-analysis port. Capture the selected value at operation or portfolio-job admission, use the frozen value for local and distributed computation and recovery, and carry explicit provenance through result storage and browser save/load. Append bounded instructions at prompt construction while retaining existing scoring contracts and provider limits.

**Tech Stack:** Existing Java 21, Maven, Spring, Jackson, JGit, JPA, Thymeleaf and modular JavaScript; existing JUnit and Maven-owned browser contracts.

**Spec:** The accepted user request of 2026-10-17 and the bounded requirements below. Implementation and eventual merge are already authorized in the conversation.

## Accepted requirements

- Verify whether the merged Word report really contains a connected decision tree and describe its actual layout accurately.
- Add **Begründungsumfang / Explanation Length** to the existing LLM preferences card.
- `analysis.reason-length`: `short` requests one concise sentence; `standard` requests one or two sentences; `detailed` requests three to five substantive sentences per decision.
- Detailed reasons explain requirement relevance and decisive criteria, including material uncertainty when applicable. They must not invent requirement facts or pad the text.
- Preserve existing texts. The preference affects new generation, including the original choice when an admitted operation is resumed.

## Global Constraints

- Default: `standard`. Deployment property: `taxonomy.analysis.reason-length`; environment variable: `TAXONOMY_ANALYSIS_REASON_LENGTH`.
- Enum: `com.taxonomy.dto.AnalysisReasonLength`, values `SHORT`, `STANDARD`, `DETAILED`, lower-case `value()` and `fromValue(String)` for configuration/execution fallback. A separate strict `fromRecordedValue(String)` JSON factory rejects blank, whitespace and unknown recorded values; absent/null historical metadata remains null.
- Serialized result metadata: `analysisReasonLength`; absent historical/manual provenance remains absent. Old execution commands use `STANDARD` when no selection was captured.
- Preferences remain system-wide and ADMIN-controlled. Invalid updates return HTTP `400` before any persistence or partial mutation.
- Keep optional-module boundaries: core preferences must not depend on the analysis module.
- No real provider calls, fabricated catalogue identifiers, changed score semantics, weakened tests, relaxed security or coverage gates, or additional test ecosystem.
- Focused Maven selections use `-am` unless exact sibling artifacts were explicitly installed. Serialize builds in the shared worktree.
- Final CI is the existing canonical `./mvnw -B verify -Pci -DrunOnnxTests=true` workflow plus required existing checks. Local focused verification does not replace it.

## Review Focus

1. Old preferences files without the key: effective default visible, no read-induced Git commit.
2. Invalid/null/non-string updates mixed with valid changes: HTTP `400`, unchanged Git HEAD and cache.
3. Preferences changed while a job is queued, executing or being recovered: every continuation retains the admitted mode; checkpoint identity binds it.
4. Root/category/product prompts, custom templates and dispatch boundaries: consistent instructions, no lost scope or silent output-limit expansion.
5. Historical/manual/mixed browser evidence and backup data: do not manufacture captured provenance; preserve it for genuinely new complete runs.

## Task 1: Preference, value contract and control

**Owner:** `reason_preferences`.

**Files:** Domain enum and its test; `PreferencesService`, `PreferencesController`, preferences and application-configuration backup contributors; `index.html`, both message bundles and `application.properties`; existing preferences API/service/UI and backup tests.

**Interfaces:** Produces the enum contract above and the validated runtime key. The analysis adapter consumes the existing preferences service; no reverse dependency is introduced.

- [x] Add and run the missing-enum regression before implementation; implement the enum and verify its three-value contract.
- [x] Exercise actual API save/reload, legacy defaults, reset and atomic invalid-update rejection through `PreferencesReasonLengthApiTest`.
- [x] Add the select to the existing LLM card and cover save/reset/newer edits in the Maven-owned preferences workspace contract.
- [x] Preserve the effective value in existing backup export records and test missing-field compatibility without inventing a restore API.

## Task 2: Frozen analysis setting and prompt behavior

**Owner:** `reason_analysis_pipeline`.

**Files:** `AnalysisRuntimeSettings` and its composition adapter; analysis commands, requests/results, admission/controllers, local and frozen computations, follow-up bindings, continuation/recovery and prompt preparation; focused tests under `taxonomy-analysis`.

**Interfaces:** Consumes `AnalysisReasonLength` and `analysis.reason-length`. Produces explicit `analysisReasonLength` on newly executed results and immutable commands; historical `AnalysisResult`/`SavedAnalysis` metadata is nullable.

- [x] Pin prompt expectations for all three modes, root/category/product calls, custom templates and unchanged decimal-score contracts before implementation.
- [x] Capture once at admission; carry the mode through request, command, actual dispatch, frozen worker and follow-up execution.
- [x] Bind recovery policy and prepared-prompt identity to the captured mode; verify recovery after a preferences change and no context leakage between operations.
- [x] Verify explicit result metadata for new standard/detailed runs and absence of invented provenance on legacy deserialization.

## Task 3: Saved evidence, portfolio admission and documentation

**Owner:** `reason_saved_evidence`; root owns documentation and integration review.

**Files:** Browser analysis state/drafts, scoring, recovery, browse snapshots and score exchange; export/import adapters; portfolio job storage and compatible migration; affected backup/export conversions; bilingual Preferences, User Guide and Configuration Reference.

**Interfaces:** Consumes and preserves the `analysisReasonLength` DTO property. Queued jobs capture the configured value at admission and supply it to the analysis command on execution/retry.

- [x] Pin save/load and legacy/manual behavior in `SavedScopeExchangeTest` and existing browser session contracts.
- [x] Preserve mode in queued/retried jobs and backup data; exercise the existing JPA/portfolio tests and portable exports.
- [ ] Pass the existing PostgreSQL migration/compatibility gate in required CI on the published head.
- [x] Carry explicitly recorded mode into report source metadata; leave absent legacy metadata absent.
- [x] Verify actual merged Word page: connected vertical, indented trees per taxonomy with scores/bars and native reason references; no claim of identical horizontal browser layout.
- [x] Update bilingual help with the three choices, admission semantics and distinction between generation depth and report content profile.

## Integration and publication

- [x] Reconcile task reports and run one focused integrated Maven selection including documentation links and relevant UI behavior.
- [x] Obtain independent spec/quality review of each task and the whole diff; fix concrete findings with covering tests.
- [ ] Publish the reviewed branch/PR with concrete scope and verification boundaries, then verify required CI on its current head.
- [ ] Merge only after required checks pass, using the expected PR head; verify the resulting main commit.

## Verification record

Keep detailed commands, test reports, screenshots and native output in `target/` or CI artifacts. Update a concise completion note with actual results; do not mark pending checks as passed or present earlier native output as a fresh render of this branch.

### Review refinements

- Initial whole-branch review identified five concrete preservation defects: the cluster restore comparator omitted the captured mode; individual manual reason edits retained automatic provenance; blank JSON metadata became recorded Standard; terminal-only cluster streams lost retained evidence metadata; and the authorized recovery-input projection omitted the mode. Each correction is paired with a regression at its owning boundary.
- Keep execution fallback and recorded provenance separate. `fromValue(null/blank)` still supports the intended Standard configuration/execution fallback, while the JSON creator is strict. This refines the initial shared factory design; it does not change a pre-existing serialized field contract because this field is introduced by this feature.
- Cluster restoration compares the exact nullable captured value before persistence. A legacy null is not interchangeable with an explicitly recorded Standard value at this provenance boundary.

### Local completion evidence — 2026-10-17

- Combined saved-evidence verification: **133 unit/MVC cases plus 26 native Chrome cases**, zero failures, errors or skips. This includes the actual draft PUT/database/GET roundtrip, a persisted failed portfolio item followed by `retryFailed` after preferences changes, saved score exchange, DOCX source notices, and both production pencil and terminal streaming handlers.
- Remaining integrated verification: **266 cases**, zero failures, errors or skips. This includes 201 analysis/prompt/continuation/restore/observation cases, 60 preferences/configuration-backup/document-link cases, and five portable portfolio-export cases. Maven completed `verify -Ptest-local` with exit code 0.
- The final focused selections therefore executed **425 cases**. The existing Maven-owned preferences JavaScript contract also passed its 26 cases earlier in this implementation; it is separate evidence, not a second count of a later run.
- The independent whole-branch and scoped code reviews closed all five findings. Source review passed for specification and quality. The final CI and merge status are tracked by the pull request rather than inferred from these local selections.
- Logs, copied JUnit XMLs and per-suite summaries are retained in `target/reason-length-review/`. No live model/provider calls were used to establish this evidence. Generated-model sentence/word compliance is a prompt target, not a measured guarantee from deterministic test responses.

### Required CI review refinements

- PostgreSQL V39 applied successfully in the first CI run. Both exact migration-history expectations now include V39; the existing migration owner also verifies a real V38-to-V39 upgrade, preservation of every legacy job field, nullable provenance and the permitted recorded values.
- The separately versioned HSQLDB restore schema now includes V9 for the same nullable field. The unchanged fresh-target validation first reproduced the missing-column failure; all five restore cases then passed with the additive migration and exact version allowlist. The existing native reformulation browser scenario also passed locally, including its original keyboard-save assertion, download, adoption and restart. Its earlier container timeout still requires fresh CI evidence.
- Independent architecture review accepted the additional `PortfolioAnalysisPersistenceService` to `AnalysisRuntimeSettings` dependency within the existing portfolio-to-analysis direction. Admission reads this existing read-only port after scope validation and idempotent job reuse so the recorded setting is captured with the new job. The preferences adapter remains in application composition. The baseline records precisely this one class edge, from seven to eight; a focused architecture assertion pins this owner's analysis-service dependencies to the runtime-settings port and existing provider configuration.
- The runtime-settings contract now checks both exact public read methods, the sole abstract `getInt` method and the default `getString` fallback. It retains the separation between the optional analysis adapter and core `PreferencesService`, and excludes private coverage instrumentation from the public API contract.
