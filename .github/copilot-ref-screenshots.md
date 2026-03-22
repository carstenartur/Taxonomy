# Screenshots & Selenium Test Reference

> **Read this when**: You are adding, modifying, or debugging screenshot tests in `ScreenshotGeneratorIT.java`.

---

## How screenshots are generated

Screenshots are captured by `src/test/java/com/taxonomy/ScreenshotGeneratorIT.java`.  
The test class is **opt-in** — it only runs when the `generateScreenshots` system property is set:

```bash
# Step 1 — build the application JAR (required by the container tests)
mvn package -DskipTests

# Step 2 — run the screenshot generator (screenshots 1–14, no LLM key needed)
mvn failsafe:integration-test -DgenerateScreenshots=true -Dit.test=ScreenshotGeneratorIT

# Step 2 (alternative) — run with LLM key to also capture screenshots 15–26
GEMINI_API_KEY=<your-key> mvn failsafe:integration-test \
    -DgenerateScreenshots=true -Dit.test=ScreenshotGeneratorIT
```

Generated PNG files are written to `docs/images/` and must be committed to the repository.

## How the test works

- A `GenericContainer` runs the application JAR in an `eclipse-temurin:17-jre-alpine` Docker image.
- A `BrowserWebDriverContainer` runs headless Chrome (Selenium) in a sibling container on the same Docker network.
- Both containers communicate over the shared `Network` using the alias `app` (i.e., `http://app:8080/`).
- The app container is configured with `ADMIN_PASSWORD=testpassword123` so admin-only panels can be unlocked in tests.
- `GEMINI_API_KEY` is forwarded from the host environment to the app container **only when it is present**.
- The health-check wait strategy uses `/api/ai-status` (a public endpoint). Do **not** use `/api/diagnostics` — that endpoint returns HTTP 401 when `ADMIN_PASSWORD` is configured.

## Screenshot numbering convention

| Range | Requires LLM? | Description |
|---|---|---|
| `01` – `14` | No | Basic UI — page layout, all 5 view modes, panels, modals |
| `15` – `40` | Yes (mock mode) | Scored states, architecture views, search, DSL editor, proposals |
| `41` – `44` | No | Versions Tab, Git Status Bar, Context Bar |
| `45` – `51` | No | Workspace UI: User Badge, Variant Modal, Variants Browser, Compare Modal, Copy Back, Read-Only, Context Bar with Origin |
| `52` – `68` | No | Extended workspace: Sync Tab states, Transfer, Merge, History Search, Diff View |
| `69`         | Yes (mock mode) | Scored Decision Map (populated with analysis results) |

LLM-dependent tests are skipped gracefully with `Assumptions.assumeTrue(System.getenv("GEMINI_API_KEY") != null)` when no key is present.

## Adding a new screenshot

1. **Add a test method** to `ScreenshotGeneratorIT.java`:
   - Annotate with `@Test` and `@Order(N)` where `N` continues the existing sequence.
   - If the screenshot requires a live LLM, add `Assumptions.assumeTrue(System.getenv("GEMINI_API_KEY") != null, "Skipping: GEMINI_API_KEY not set");` as the first line.
   - Use `saveScreenshot("NN-my-feature.png")` for a full-viewport screenshot or `saveElementScreenshot(element, "NN-my-feature.png")` for an element-scoped screenshot.
   - Use `wait(N).until(ExpectedConditions....)` instead of `Thread.sleep()` for synchronisation.
   - To show a Bootstrap modal, call `showModalAndWait("myModalId")`.
   - To close a Bootstrap modal without timing issues, call `closeModalAndWait("myModalId")`.

2. **Add the image reference** to `docs/USER_GUIDE.md` at the appropriate location:
   ```markdown
   ![Alt text](images/NN-my-feature.png)
   ```

3. **Run the generator** locally (`mvn package -DskipTests && mvn failsafe:integration-test -DgenerateScreenshots=true -Dit.test=ScreenshotGeneratorIT`) to produce the PNG file.

4. **Commit the PNG** to `docs/images/` alongside the test and documentation changes.

## Automatic regeneration via GitHub Actions

The workflow `.github/workflows/generate-screenshots.yml` runs automatically on:
- Manual trigger (`workflow_dispatch`)
- Push to `main` that changes files under `src/main/resources/templates/**` or `src/main/resources/static/**`
- Push to `main` that changes `src/test/java/**/ScreenshotGeneratorIT.java`

It builds the JAR, runs the screenshot generator (with `GEMINI_API_KEY` from repository secrets), and auto-commits any changed PNGs back to the branch with the message `docs: auto-generate UI screenshots [skip ci]`.

To trigger it manually from the GitHub UI: **Actions → Generate Documentation Screenshots → Run workflow**.

## Key Element IDs (for Selenium tests)

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
| Relation Proposals panel | `#proposalsPanel` |
| Admin lock button | `#adminLockBtn` |
| Admin modal | `#adminModal` |
| Admin password input | `#adminPasswordInput` |
| LLM Diagnostics panel | `#diagnosticsPanel` |
| Prompt Template editor | `#promptEditor` |
| Leaf Justification modal | `#leafJustificationModal` |
| Propose Relations modal | `#proposeRelationsModal` |
| Left panel card | `.col-lg-7 .card` |
| Right panel column | `.col-lg-5` |
| Propose Relations buttons | `.proposal-btn` |
| Node toggle buttons | `.tax-toggle` |
| Leaf Justify buttons | `.tax-justify-btn` |
| Match Legend card | `//div[contains(@class,'card')]//small[contains(text(),'Match legend')]/ancestor::div[contains(@class,'card')]` (XPath) |
| Git Status Bar | `#gitStatusBar` |
| Context Navigation Bar | `#contextBar` |
| Versions Tab pane | `#tab-versions` |
| Versions Timeline | `#versionsTimeline` |
| Versions Undo button | `#versionsUndoBtn` |
| Versions Branch selector | `#versionsBranchSelect` |
| Save Version button | `#versionsSaveBtn` |
| Version Title input | `#versionTitle` |
| Version Description textarea | `#versionDescription` |
