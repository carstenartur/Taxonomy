# Screenshots & Selenium Test Reference

> **Read this when**: You are adding, modifying, or debugging documentation screenshot tests.

---

## Maven entry point and activation

Run from the repository root through the existing `screenshots` profile:

```bash
./mvnw -B verify -Pscreenshots -DgenerateScreenshots=true
```

The root POM selects `ScreenshotGeneratorIT`, `ArchitectureWorkbenchUiIT` and
`CompleteCopilotSessionIT` and enables Failsafe. The normal lifecycle packages the
application before the container tests and checks their results at `verify`.
Do not replace it with a direct `failsafe:integration-test` goal.

Pass the explicit JVM activation value `true`: `ScreenshotGeneratorIT` checks for
the presence of the `generateScreenshots` system property, while the two persisted
acceptances guard their PNG writes with `Boolean.getBoolean("generateScreenshots")`.
Omit the screenshot profile/property when captures are not requested; setting the
property to `false` does not disable `ScreenshotGeneratorIT`'s presence condition.

These three owners require Java 21, the Maven Wrapper's dependencies and Docker.
`CompleteCopilotSessionIT` also starts PostgreSQL in Docker. The checked-in POMs,
`ContainerTestUtils` and documentation workflow own the pinned runtime, Selenium
and model assets; initial provisioning needs their download access. All three
owners use `LLM_MOCK=true`; no provider key or live model call is required.

Review the generated `docs/images/` files before retaining selected illustrations.
Keep raw logs and complete capture evidence under `target/` or in CI artifacts.
A successful command does not establish that every intended image was rewritten:
check JUnit results, skipped tests and the actual output files.

All three Java owners and the document-template browser/report owner honor
`TAXONOMY_DOCS_IMAGE_DIR` when set. Use an absolute path to keep Maven module
working directories from changing the destination. The default remains
`docs/images/`; CI uses a fresh `target/generated-screenshots/` directory.

## Capture owners and evidence boundaries

The named `*-current-{de,en}.png` captures added on 25 October 2026 have a
[separate application capture record](../docs/images/ui-documentation-2026-10-25.json).
They use the recorded packaged application with current checkout resource
overlays, a real in-memory database and the deterministic example provider.
The recorded journey opens Graph through the node action dialog, enters the
database assistant's connection step, creates two editor elements through
preview/accept, and opens a snapshot persisted through the analysis API.
No page result or API response was substituted. The database image does not
establish an external connection, and the example provider does not establish
live-model quality. Image and overlaid-source hashes identify these captures;
the numbered Java-owner outputs below are separate runs.

All Java owners below are under `taxonomy-app/src/test/java/com/taxonomy/`.
Use the complete filename to identify ownership: numeric prefixes are shared.

| Owner | Output | Source boundary |
|---|---|---|
| `ScreenshotGeneratorIT` | `01`–`19`, `21`–`31`, `33`–`36`, `39`–`47`, `66-versions-timeline.png`, `69-decision-map-scored.png`, `70-swagger-ui.png`, `71-rich-version-timeline.png`, `72-rich-variants-browser.png`, `73-cherry-pick-before.png`, `74-cherry-pick-after.png`, `75-preferences-diagram-policy.png` | General UI illustrations using the real catalogue, deterministic mock model and authored fixtures persisted through the APIs. Search/graph/editor captures require actual rendered output; Git operations require successful API responses. Status badges retain the application's real state. These images do not establish live-provider quality. |
| `ArchitectureWorkbenchUiIT` | `71-copilot-architecture-workbench.png` | Creates and opens a real immutable analysis snapshot with deterministic mock scores; checks browser controls and SVG/PDF model exports from that snapshot. |
| `CompleteCopilotSessionIT` | `72-complete-copilot-run-result.png` | Packaged application, PostgreSQL and real persisted operations with deterministic mock model; checks cancellation, recovery, selected snapshot and result/export behavior. |
| `ScenarioArchitectureAcceptanceTest` with `ScenarioBrowserWalkthrough` | `73-scenario-requirement.png` through `77-scenario-mobile.png`, plus native-package/publication captures `78`–`85` | Real Spring application, catalogue, provider pipeline, persistence and browser; only remote model HTTP responses are replaced by authored Spring playback. These are not recorded live-model outputs. |

Keep persisted-workbench changes in `ArchitectureWorkbenchUiIT` and complete-run
result changes in `CompleteCopilotSessionIT`; do not assemble a second result page
in the general generator. `ContainerTestUtils` builds the application image from
the packaged JAR and its `plugins`/`features` directories, uses the pinned runtime
image and connects the Selenium container to `http://taxonomy.test:8080/`.
Credentials belong to disposable test setup, not to deployment instructions.
The shared container helper waits for `/actuator/health/readiness`; the general
generator additionally waits for authenticated `/api/status/startup` to report
`ready`. Its semantic and hybrid searches use the pinned local ONNX model
bundle provisioned by the screenshot profile, mounted read-only with downloads
disabled. Before opening the browser, it waits for `/api/embedding/status` to
report node semantic search ready with indexed nodes. Do not substitute AI
availability or administrative diagnostics for catalogue readiness.

Workspace proposal acceptance and catalogue graph traversal use distinct relation
scopes; a committed workspace relation does not establish catalogue graph output.

The separate scenario profile enables browser capture with no provider secrets:

```bash
# Docker/Selenium browser
./mvnw -B -ntp -pl taxonomy-app -am test -Pscenario-acceptance

# Supported local Chrome/ChromeDriver browser
./mvnw -B -ntp -pl taxonomy-app -am test -Ptest-local,scenario-acceptance
```

The local profile writes scenario images below
`taxonomy-app/target/scenario-acceptance/screenshots/`; it does not make the three
Docker-required screenshot ITs local. See the
[scenario guide](../docs/testing/scenario-acceptance.md),
[local-browser prerequisites](../docs/testing/docker-free-tests.md) and
`.github/workflows/scenario-acceptance.yml`. Preserve `run.json`, `browser.json`,
fixture identity, source revision and image hashes with the actual capture run.

## Existing images are not automatically current evidence

Check each retained image against its owning template and JavaScript before
reusing it. For example, older `01`, `03`, `41` and `45` illustrations show the
previous navigation/status layout. Current `index.html` uses File/Project menus,
Settings and an on-demand Status menu; `taxonomy-onboarding.js` moves Git, context
and workspace indicators into `#operationalContextDetails`. The retained `71`
workbench image predates the Print view and grouped Export controls, and the `72`
result image predates the shared decision-report dialog action. Capture replacements
from their owners; do not edit an old image's version text or present it as a new run.
The general generator now writes `14-navbar-settings.png`; a retained
`14-navbar-admin-lock.png` is not evidence for the current Settings menu.
A current test-owner path is a reproduction recipe, not proof of an older PNG's
source revision. Without matching capture metadata, describe the image as an
illustration and leave its exact source version unverified.

The following general-generator captures were retired because they built the
displayed workflow result directly in the DOM or relied on an unsupported relation
scope. Their retained PNGs are historical illustrations and are **not outputs of
the repaired generator**. Reintroduce a
capture only through a real operation and its existing UI/test owner.

| Retired filenames | Unsupported capture boundary |
|---|---|
| `20-architecture-view.png`, `38-architecture-view-detailed.png` | These captures depended on a retired catalogue relation-write route. The current Git-authoritative command writes a workspace projection that does not enrich catalogue propagation. Use the current persisted-workbench owner for new evidence. |
| `32-search-graph.png` | The default workspace projection can remain `NOT_BUILT`, which graph-semantic search rejects. The page does not invoke the exported provisioning hook; this owner has no verified real preparation for a supported empty projection. The old capture substituted authored matches. |
| `37-graph-with-accepted-relation.png` | Accepted workspace proposals do not establish catalogue Graph results. The old capture substituted an authored downstream result. The real proposal-acceptance capture `36` remains. |
| `48-compare-modal-branches.png`, `49-copy-back-button.png`, `50-read-only-mode-badge.png`, `51-context-bar-with-origin.png` | Invented branch options, context markup and read-only badges. |
| `52-merge-conflict-modal.png`, `53-merge-conflict-resolved.png`, `54-cherry-pick-conflict-modal.png` | Authored conflict outputs and fabricated successful resolution. |
| `55-sync-diverged-state.png`, `56-sync-resolve-modal.png`, `63-sync-tab-up-to-date.png`, `64-sync-tab-ahead.png`, `65-sync-tab-behind.png` | Invented synchronization state; resolve dialog opened without an actual diverged workflow. |
| `57-variant-delete-confirm.png`, `67-version-restore-confirm.png` | Hand-built variant list and restoration confirmation. |
| `58-merge-success-toast.png`, `59-cherry-pick-success-toast.png`, `60-merge-preview-modal.png`, `61-merge-preview-fast-forward.png`, `62-cherry-pick-preview-modal.png` | Invented operation success and preview results. |
| `68-diff-view.png` | Authored diff text, independent of a real comparison response. |

The genuine seeded version-history, variants and cherry-pick captures remain.
Their input DSL fixtures are unchanged. A rejected fixture API response, missing
search result, unavailable mode or failed analysis now fails its capture instead
of injecting an expected result or silently skipping it. Failure PNGs and page
HTML are written to `target/screenshot-failures/`, outside the publishable output.
Before creating its real proposal, capture `36` uses the relation panel's existing
Rebuild projection action when needed, then verifies a readable projection at the
current Git head.

The five images listed in the [historical screenshot appendix](../docs/qa/scenario-screenshots-history.md)
are not outputs of the current scenario owner. Their retained bytes do not match
the hashes in the [historical evidence manifest](../docs/qa/civilian-acceptance-evidence.json);
that manifest cannot currently establish their provenance. Recover the matching
source artifacts or remove unsupported image attribution before citing that run.
Do not replace manifest hashes merely to make the retained images appear verified.

## Adding a new screenshot

### Focused decision-export component illustrations

The six unnumbered `decision-export-{dialog,options,mobile}-{de,en}.png` images
belong to the existing `.github/scripts/decision-export-browser.mjs` contract.
It renders the production shared dialog with explicitly labelled example input,
then captures basic choices, expanded options and a narrow-screen view in both
languages. These images illustrate the component; they are not full-application,
persisted-snapshot or live-provider acceptance evidence. The image/source hashes
and capture environment are recorded in `docs/images/decision-export-screenshots.json`.

Regenerate from the repository root with the project's Playwright browser installed:

```bash
TAXONOMY_DOC_SCREENSHOTS="$PWD/docs/images" npm --prefix .github run verify:decision-export-dialog
```

The ordinary contract does not write documentation images unless that variable is
set. Inspect and commit all six images with the manifest and the relevant guide
changes. See [contextual exports](../docs/testing/contextual-exports.md) for the
illustrated guide links and environment overrides. This opt-in component capture
does not change the full-application screenshot owners described above or their
Maven/CI workflows.

### Full-application screenshots

1. **Choose the existing owner** from the table above. For a general illustration
   in `taxonomy-app/src/test/java/com/taxonomy/ScreenshotGeneratorIT.java`:
   - Annotate with `@Test` and `@Order(N)` where `N` continues the existing sequence.
   - Use the existing deterministic model setup. Live-provider acceptance belongs
     to its explicitly selected test path; screenshot generation must not consume
     shared remote-provider quota.
   - Use `saveScreenshot("NN-my-feature.png")` for a full-viewport screenshot or `saveElementScreenshot(element, "NN-my-feature.png")` for an element-scoped screenshot.
   - Use `wait(N).until(ExpectedConditions....)` instead of `Thread.sleep()` for synchronisation.
   - To show a Bootstrap modal, call `showModalAndWait("myModalId")`.
   - To close a Bootstrap modal without timing issues, call `closeModalAndWait("myModalId")`.

2. **Update the relevant localized guide** under `docs/en/` and `docs/de/`.
   Identify mock/example data or historical captures in the surrounding text.
   Image paths from those guides start with `../images/`; use the actual captured
   filename and alt text describing its state.

3. **Run the owning Maven profile** using the commands above. Inspect the resulting
   PNG, current labels, clipping, responsive layout and test report. Preserve the
   real captured state and disclose any fixture boundary; do not fabricate healthy
   readiness or successful operation evidence.

4. **Retain selected PNGs** in `docs/images/` alongside the documentation change
   and concise provenance. Keep full capture series and raw evidence in `target/`
   or CI artifacts. If prerequisites prevent regeneration, report the gap and
   remove or clearly qualify the stale illustration instead of claiming it passed.

## Automatic regeneration via GitHub Actions

The manual `.github/workflows/documentation-screenshots.yml` workflow accepts
trusted `main` invocations. It provisions Java, Docker/model assets and document
renderers, runs `install -Pscreenshots`, then runs the separate
`document-template-report-e2e` profile. A dependent publication job downloads the
artifact and commits changed PNGs to `main` after generation succeeds.

The workflow clears only the dedicated output directory before generation. All
four owners write there through `TAXONOMY_DOCS_IMAGE_DIR`, and the artifact includes
only its PNGs. Retained `docs/images/` assets stay available for documentation link
checks and application packaging. Failed generation blocks publication and uploads
the available failure captures and Failsafe reports separately. Check each owning
JUnit result and capture provenance before claiming a particular view was verified.

To trigger it manually from the GitHub UI: **Actions → Documentation Screenshots → Run workflow**.
Select a repair branch to verify generation before merging. Branch runs upload
capture artifacts only; the separate publication job still requires `main`.

## Current selectors (verify in the owning surface)

General selectors come from `taxonomy-app/src/main/resources/templates/index.html`
and its JavaScript; persisted-workbench selectors come from
`templates/architecture-workbench.html`. Visibility depends on the active tab,
role, menu and loaded data. Open the relevant menu before capturing its contents.
The previous `adminLockBtn`, `adminModal` and `adminPasswordInput` IDs are absent;
administration now follows authenticated role-based navigation.

| Element | ID / Selector |
|---|---|
| Taxonomy tree container | `#taxonomyTree` |
| Business requirement textarea | `#businessText` |
| Analyze button | `#analyzeBtn` |
| Status area | `#statusArea` |
| Interactive Mode checkbox | `#interactiveMode` |
| Architecture View checkbox | `#includeArchitectureView` |
| Export button group | `#exportGroup` |
| Graph Explorer panel | `#graphExplorerPanel` |
| Graph node code input | `#graphNodeInput` |
| Upstream button | `#graphUpstreamBtn` |
| Failure Impact button | `#graphFailureBtn` |
| Graph results area | `#graphResultsArea` |
| Architecture View panel | `#architectureViewPanel` |
| Persisted Copilot workbench | `#architectureWorkbench` |
| Copilot workbench search | `#architectureSearch` |
| Copilot workbench true fit | `#fitArchitecture` |
| Relation Proposals panel | `#proposalsPanel` |
| File / Project menus | `#fileMenuButton`, `#projectMenuButton` |
| Settings menu | `#applicationSettings` |
| Administration / server settings links | `#adminNavTab`, `#preferencesNavTab` |
| On-demand Status menu | `#operationalContextDetails` |
| LLM Diagnostics panel | `#diagnosticsPanel` |
| Prompt Template editor | `#promptEditor` |
| Leaf Justification modal | `#leafJustificationModal` |
| Propose Relations modal | `#proposeRelationsModal` |
| Left panel card | `#leftPanel > .card` |
| Right panel column | `#rightPanel` |
| Propose Relations buttons | `.proposal-btn` |
| Node toggle buttons | `.tax-toggle` |
| Leaf Justify buttons | `.tax-justify-btn` |
| Git Status Bar | `#gitStatusBar` |
| Context Navigation Bar | `#contextBar` |
| Workspace badge | `#workspaceUserBadge` |
| Versions Tab pane | `#tab-versions` |
| Versions Timeline | `#versionsTimeline` |
| Versions Undo button | `#versionsUndoBtn` |
| Versions Branch selector | `#versionsBranchSelect` |
| Save Version button | `#versionsSaveBtn` |
| Version Title input | `#versionTitle` |
| Version Description textarea | `#versionDescription` |
| Workbench Export menu | `#architectureExportOptions` |
| Workbench Print view | `#printArchitectureView` |
| Workbench SVG / PDF download | `#downloadArchitectureSvg`, `#downloadArchitecturePdf` |
| Complete-run operation | `#copilotOperation` |
| Selected result tab / detail | `#analyses-tab`, `#snapshotDetail` |
| Complete-run summary | `#snapshotResultOverview` |
