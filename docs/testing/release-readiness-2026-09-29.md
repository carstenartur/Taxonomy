# Release-readiness corrections, 2026-09-29

Baseline: `73fdb2eca456bae1f4e7c99021de8af5dcd06033` (main).
Scope: the accepted release audit's root-scoring/provider/ONNX defects and
English/German documentation drift. This record is **not a release approval**.

## Changes

- Typed independent root relevance, distinct prompt and checkpoint identity across
  normal, streaming, mock and embedding paths. Child-budget allocation is preserved.
- An embedding-only provider leaves requirement relationships explicitly unassessed
  and the analysis partial, without unsupported text-generation calls or score-only
  fallback. The existing JSON snapshot boundary retains that evidence.
- ONNX reference/provisioning, normalized CLS pooling and ANN exploration work from
  PR #1141 commits `65b8cea`, `1289faa`, `979c0ed` and `41ae713`, integrated on the
  newer baseline without reverting guided setup or the Jackson 3.1.6 security patch.
- Reference readiness now accepts a usable node index while relationship indexing
  is running or partial. Reports use `TaxonomyDataFingerprint.sha256`, including
  hierarchy levels, parent/role semantics and version framing.
- Bilingual operating, backup, database, API, workspace, preferences, analysis and
  verification documentation; expanded 1.4.0 release summaries.
- A [language policy proposal](../dev/ANALYSIS_LANGUAGE_POLICY.md). Language settings
  have not been implemented or advertised as shipped functionality.

## Executed evidence

| Check | Observed result |
|---|---|
| Changed production Java sources | Compiled with `javac --release 21` on Temurin 25.0.2 against authentic baseline runtime libraries |
| Root flow regression, old implementation | 0/7; reproduced positive root relevance becoming 100 in normal, streaming, embedding and checkpoint flows |
| Root flow regression, corrected implementation | 7/7, including 0/20/100, a single child and checkpoint replay |
| Unsupported-provider use-case contract | New regression fails against baseline; corrected use-case contracts 10/10 |
| ONNX reference review regressions | Three failures against old readiness/hash behavior; corrected reference helper tests 11/11 |
| Combined focused JUnit selection | 131 tests passed, zero failures, errors, aborts or skips |
| Rendered root prompt / scenario playback | New test reproduced `Missing requirement` twice before correction; 6/6 now pass across the authored corpora, full-source validation, adopted text and the existing child prompt |
| Root flow after the prompt-format correction | 7/7 passed again; independent relevance and child budgets remain distinct |
| Preferences / working-draft component regression | 4/4 passed for 50→150, pending autosave, failed Preferences save and pending initial restore; wired into the Maven-owned UI contract command |
| Native ONNX pooling | Real native tensor/translator check passed; included in the 131 tests |
| Release notes/delivery/image and Maven cache contracts | Passed |
| Changed Markdown local file links | 370 checked across 43 Markdown files before adding this evidence record; no missing targets |
| Diff whitespace | `git diff --check` passed |

The focused JUnit selection comprises `RootScoringFlowTest`, `LlmResponseParserTest`,
`RelationSearchIntegrationTest`, `OnnxReferenceCasesTest`,
`OnnxReferenceEvaluationTest`, `LocalEmbeddingSearchBudgetTest`,
`LocalEmbeddingModelCriteriaTest` and `OnnxProfileWiringTest`.

Dependencies were extracted from the clean main application artifact
`11013808535`, workflow `36524688342`, with ZIP SHA-256
`5fd5367d8b0bae820748bc29a6da115892ee4917fd92dfc01e864c4713910af2`.
Its embedded source identity is the baseline above and `git.dirty=false`.
Changed classes and the new prompt resource took precedence over baseline classes.
JUnit 6.1.3 came from the installed dependency cache. This deliberately bounded
compile/test harness is not Maven dependency resolution, a full reactor build or
new end-to-end real-model retrieval evidence.

The first candidate scenario workflow (`36532240413`, head `09e9270`) exposed a
strict playback contract mismatch: the new root template did not have the source
delimiter/key marker consumed by the scenario transport, which also required
child-budget wording. The follow-up retains independent-root scoring, aligns the
template's field boundaries and recognizes the root task explicitly in the test
transport. Unknown source text, roots and response scopes still fail. The new
`ScenarioRootPromptPlaybackTest` uses the production template rather than a copied
prompt. Its local result is not a substitute for rerunning the full scenario lane.

The Preferences component test executes the actual inline Preferences script and
working-draft lifecycle with controlled HTTP responses. It is not browser evidence.
The existing ADMIN browser workflow now uses the exact 50→150 transition and
asserts both the visible architecture and the authoritative saved draft after
autosave, navigation and reload. It still seeds an explicit completed-state fixture;
the separate scenario lane exercises actual analysis completion. The strengthened
browser workflow must pass before its coverage is counted as executed evidence.

## Independent review and remaining gates

A separate read-only whole-change review found no additional introduced correctness
blocker after a backup failure-handling correction: the documentation now aborts
before archiving if the application cannot be stopped, and restarts the service
while retaining a failure result if archiving fails. The wrong-volume defect and
the failure paths were checked separately; no live Docker restore is claimed.

Before merge/publication, complete the required checks on the actual branch/release
candidate: Java 21 Maven reactor, browser/UI, ONNX reference workflow, database,
security, CodeQL and immutable artifact/image gates. Local Maven dependency
resolution was unavailable, and Docker/Helm were not installed in this workspace.
Also retain upgrade/restore acceptance, the Preferences-save/reload regression
from issue #1100 and a complete representative user workflow. Static inspection
did not establish a current preference data-loss defect.

Old saved results are unchanged. The new root prompt changes the existing recovery
policy fingerprint; a paused run created under the previous scoring contract may
require a new analysis instead of checkpoint replay. Rebuild older mean-pooled
embedding vectors through the controlled LOCAL_ONNX initializer. German reference
misses remain visible; neither these corrections nor an English-model test pass
establish equivalent German retrieval quality.

Existing export limits remain: legacy text-to-diagram endpoints use their earlier
score-based pipeline, and generic rationale reports retain a partial status without
every detailed relation-search reason. Current/persisted workbench exports reject
an empty architecture; JSON snapshots retain the structured unsupported-provider
report. These are not new certified interchange or translation capabilities.
