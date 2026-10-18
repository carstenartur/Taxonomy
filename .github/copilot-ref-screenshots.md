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

## Capture owners and evidence boundaries

All Java owners below are under `taxonomy-app/src/test/java/com/taxonomy/`.
Use the complete filename to identify ownership: numeric prefixes are shared.

| Owner | Output | Source boundary |
|---|---|---|
| `ScreenshotGeneratorIT` | `01`–`70`, plus `71-rich-version-timeline.png`, `72-rich-variants-browser.png`, `73-cherry-pick-before.png`, `74-cherry-pick-after.png`, `75-preferences-diagram-policy.png` | General UI illustrations using the real catalogue and mock model. Some search, Git, workspace and status states use injected example HTML or fallback data. These images do not prove live-provider quality, index readiness or successful persistence. |
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
`ready`. Do not substitute AI availability or administrative diagnostics for
catalogue readiness.

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

The older `73`–`77-civilian-*.png` names are historical, not outputs of the current
scenario owner. Their retained bytes do not match the hashes in
`docs/qa/civilian-acceptance-evidence.json`; that manifest cannot currently establish
their provenance. Recover the matching source artifacts or remove unsupported
image attribution before citing that run. Do not replace manifest hashes merely
to make the retained images appear verified.

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

The artifact staging step copies every `docs/images/*.png`, including existing
files. Artifact presence alone does not prove a particular image was regenerated;
check the owning JUnit result and capture provenance.

To trigger it manually from the GitHub UI: **Actions → Documentation Screenshots → Run workflow**.

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
