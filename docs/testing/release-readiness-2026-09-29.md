# Release-readiness corrections, 2026-09-29

Baseline: `73fdb2eca456bae1f4e7c99021de8af5dcd06033` (main).
Scope: the accepted release audit's root-scoring/provider/ONNX defects,
English/German documentation drift and failures reproduced during candidate
acceptance. This record is **not a release approval**.

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
- Pre-Hibernate HSQLDB upgrade preparation for populated 1.3 repository and
  portfolio tables, with exact parent/tenant validation. Historical central
  portfolio reads retain their recorded scope; mutations require a workspace.
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
| Full scenario acceptance on follow-up head `e8ec78a` | Workflow `36535134461` passed, including the real browser/playback scenario and independent Word/Visio export rendering |
| Historical central portfolio reads | Baseline rejects explicit-central GET/HEAD; the initial broad read exception failed the Copilot-status regression. The positive allowlist now passes 8/8, including rejection of mutating status reads and unknown routes |
| HSQLDB portfolio migration | 8/8 JUnit tests pass against the real HSQLDB driver: complete parent chain, immutable branch identity, inconsistent scope/parents, target-key collisions, distinct central branches, post-upgrade current-version constraint and pre-existing Hibernate constraints |
| Integration with main `2806480` / merged PR #1141 | 14/14 ONNX reference and Jackson XML constraint tests pass; the readiness/fingerprint fixes and main's security regression/evidence are both retained |
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
Its first run on `e8ec78a` exposed the preceding import scenario's retained Export
subtab. The assertion now navigates to Overview and its Layer View through the
actual UI before checking visible architecture text.

## Upgrade and restore acceptance

The original published 1.3.0 application JAR was verified against release asset
`500250134`: SHA-256
`f5c05641d32ddb22d34817a1b0c8630656e435fd6203ca4e1f5fd83e01569eb6`,
embedded commit `e2aba2c6dc15cd9c2a98b695a70c1cf6380baca1`, `git.dirty=false`.
It created a file-backed production HSQLDB with 2,572 taxonomy nodes and 37
relations. A relation created through the 1.3 API raised the count to 38. After
stopping the writer, its backup was restored into a separate directory and
started under 1.3; readiness, authenticated reads and the custom relation's
provenance were preserved. The backup SHA-256 was
`a647ede386fd713529f198a1dcd6207fd05c917ce8ced29b86eb203d47af591f`.

The first candidate failed on that populated database before the existing
application-runner migration could execute: Hibernate could not add required
`system_repository.version` / `taxonomy_relation.repository_id` columns. A second
fixture created a project, requirement and version through the old application's
API and exposed the same ordering problem for portfolio tenant columns.

The corrected local probe overlays compiled migration/interceptor classes on the
unaltered CI candidate artifact `11017925044` from workflow `36532240384`.
The underlying JAR has SHA-256
`915d2b0b4c319e37d13b599814a6de0e23ec3e33d600c1718fe9e011bfb7b654`,
embedded merge commit `c256728c7dc502f366bb8b0855fc32e24819806f` and
`git.dirty=false`. The overlay is explicitly a local diagnostic build, **not** a
new immutable CI candidate or release artifact.

That probe reaches readiness with all 38 relations, preserves the historical
project/requirement/version and serves central reads with an explicitly empty
workspace header. Central POST is rejected; a selected isolated workspace can
create a project and requirement. With the explicitly local `MOCK` provider, a
new analysis job and item complete successfully, persist a retrievable snapshot,
and return the same job/snapshot for the same idempotency key after restart.
After the final review corrections, the overlay SHA-256 is
`7e117147a36d6abf737d7cd91b015fbf71139451bd9e8231fd41eeb96cab1fd8`.
Historical project/version reads pass again, both mutating Copilot status routes
return 403 for central access, and another MOCK analysis persists a successful
job/item/snapshot before a successful restart. The exact current-version FK also
rejects a sibling requirement's version after migration.
No external-model quality, Docker volume restore or PostgreSQL upgrade is claimed
by this local HSQLDB acceptance.

The next CI candidate (`c0a8d3c`, JGit consumer workflow `36539231653`) exposed a
different restart case: an already-current Hibernate-created schema contains the
same unique key under a different constraint name. Adding it again fails in
HSQLDB. A new real-driver regression reproduces this failure, then passes when
the migration recognizes complete unique-column sets and foreign-key reference
mappings independently of their names. It also verifies that the existing FK
still rejects cross-tenant writes. This expands the migration suite from seven
to eight tests; the full consumer workflow must pass on the corrected head.

## Independent review and remaining gates

A separate read-only whole-change review found no additional introduced correctness
blocker after a backup failure-handling correction: the documentation now aborts
before archiving if the application cannot be stopped, and restarts the service
while retaining a failure result if archiving fails. The wrong-volume defect and
the failure paths were checked separately; no live Docker restore is claimed.

The follow-up review found and corrected three issues in the upgrade work: a broad
central GET exception included Copilot status routes that can schedule/finalize
work; the initial duplicate-key check rejected valid central keys across different
branches; and Hibernate retained a legacy current-version FK without its exact
requirement/scope columns. The explicit read allowlist, target-tenant collision
check and composite FK have dedicated regressions. Nullable snapshot/version
pointers are also validated before startup can expose the migrated records.

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
