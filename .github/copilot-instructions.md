# GitHub Copilot Instructions — Taxonomy Architecture Analyzer

## Project Overview

Spring Boot 4 / Java 17 web application. Taxonomy data loaded from an Excel workbook via Apache POI. Full-text and KNN search via Hibernate Search 8 + Lucene 9. LLM analysis via Google Gemini (default) or other configured provider. UI is a single Bootstrap 5 page rendered by Thymeleaf.

## Build & Test

```bash
mvn compile            # compile only
mvn test               # unit + Spring context tests (never requires Docker or an API key)
mvn verify             # unit tests + integration tests (requires Docker for container ITs)
```

Integration test classes follow the `**/*IT.java` naming pattern and are run by `maven-failsafe-plugin`.

## Documentation Screenshots

### How screenshots are generated

Screenshots are captured by `src/test/java/com/nato/taxonomy/ScreenshotGeneratorIT.java`.  
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

### How the test works

- A `GenericContainer` runs the application JAR in an `eclipse-temurin:17-jre-alpine` Docker image.
- A `BrowserWebDriverContainer` runs headless Chrome (Selenium) in a sibling container on the same Docker network.
- Both containers communicate over the shared `Network` using the alias `app` (i.e., `http://app:8080/`).
- The app container is configured with `ADMIN_PASSWORD=testpassword123` so admin-only panels can be unlocked in tests.
- `GEMINI_API_KEY` is forwarded from the host environment to the app container **only when it is present**.
- The health-check wait strategy uses `/api/ai-status` (a public endpoint). Do **not** use `/api/diagnostics` — that endpoint returns HTTP 401 when `ADMIN_PASSWORD` is configured.

### Screenshot numbering convention

| Range | Requires LLM? | Description |
|---|---|---|
| `01` – `14` | No | Basic UI — page layout, all 5 view modes, panels, modals |
| `15` – `26` | Yes (`GEMINI_API_KEY`) | LLM-dependent states — scored tree, architecture view, admin panels, etc. |

LLM-dependent tests are skipped gracefully with `Assumptions.assumeTrue(System.getenv("GEMINI_API_KEY") != null)` when no key is present.

### Adding a new screenshot

1. **Add a test method** to `ScreenshotGeneratorIT.java`:
   - Annotate with `@Test` and `@Order(N)` where `N` continues the existing sequence.
   - If the screenshot requires a live LLM, add `Assumptions.assumeTrue(System.getenv("GEMINI_API_KEY") != null, "Skipping: GEMINI_API_KEY not set");` as the first line.
   - Use `saveScreenshot("NN-my-feature.png")` for a full-viewport screenshot or `saveElementScreenshot(element, "NN-my-feature.png")` for an element-scoped screenshot.
   - Use `wait(N).until(ExpectedConditions....)` instead of `Thread.sleep()` for synchronisation.
   - To close a Bootstrap modal without timing issues, call `closeModalViaDOM("myModalId")`.

2. **Add the image reference** to `docs/USER_GUIDE.md` at the appropriate location:
   ```markdown
   ![Alt text](images/NN-my-feature.png)
   ```

3. **Run the generator** locally (`mvn package -DskipTests && mvn failsafe:integration-test -DgenerateScreenshots=true -Dit.test=ScreenshotGeneratorIT`) to produce the PNG file.

4. **Commit the PNG** to `docs/images/` alongside the test and documentation changes.

### Automatic regeneration via GitHub Actions

The workflow `.github/workflows/generate-screenshots.yml` runs automatically on:
- Manual trigger (`workflow_dispatch`)
- Push to `main` that changes files under `src/main/resources/templates/**` or `src/main/resources/static/**`
- Push to `main` that changes `src/test/java/**/ScreenshotGeneratorIT.java`

It builds the JAR, runs the screenshot generator (with `GEMINI_API_KEY` from repository secrets), and auto-commits any changed PNGs back to the branch with the message `docs: auto-generate UI screenshots [skip ci]`.

To trigger it manually from the GitHub UI: **Actions → Generate Documentation Screenshots → Run workflow**.

## GEMINI_API_KEY — Availability and Rate-Limit Guidance

`GEMINI_API_KEY` is configured as a **repository secret** and is injected into the `Generate Documentation Screenshots` workflow automatically. It is also available to Copilot agents running in GitHub Actions via `secrets.GEMINI_API_KEY`.

### Free-tier limits (Gemini 2.0 Flash / Gemini 1.5 Flash)

The key uses the **Google AI Studio free tier**, which has hard rate limits:

| Limit | Value |
|---|---|
| Requests per minute (RPM) | 15 |
| Requests per day (RPD) | 1 500 |
| Tokens per minute (TPM) | 1 000 000 |

> These limits are shared across **all** uses of the key — the running application, CI workflows, and any agent-triggered calls all draw from the same quota.

### Rules for agents

- **Do not call the LLM unnecessarily.** Never invoke `/api/analyze`, `/api/analyze-stream`, `/api/analyze-node`, `/api/justify-leaf`, or any other LLM-backed endpoint in unit tests or exploratory scripts.
- **Prefer screenshots 1–14 for local iteration.** They require no API key and run in under 2 minutes. Only add LLM-dependent tests (15+) when the screenshot genuinely requires a scored/analysed UI state.
- **Do not trigger the `generate-screenshots.yml` workflow repeatedly** in quick succession. Each full run with the key uses up to ~12 LLM calls (one per analysis request). Wait for a run to finish before triggering another.
- **Do not hardcode or log the key.** The secret is masked in CI logs; keep it that way.
- If a task only needs to verify that the LLM integration compiles or that the API wiring is correct, use the existing `DiagnosticsWithApiKeyContainerIT` tests, which make at most 1–2 LLM calls per run.

## Gemini API Rate Limits

The Gemini Free Tier has a rate limit of approximately 15 requests per minute. This affects:
- `ScreenshotGeneratorIT.java`: Tests 15–26 make LLM calls and need `rateLimitDelay()` (10s pause) between them.
- The `generate-screenshots.yml` workflow: Must account for total runtime of ~3–4 minutes for LLM-dependent screenshots.
- Any new test that triggers LLM analysis must include a rate-limit delay.

The `LlmService` throws `LlmRateLimitException` on HTTP 429 or `RESOURCE_EXHAUSTED`. The screenshot tests do NOT have automatic retry — they rely on delays to stay within limits, plus `failsafe.rerunFailingTestsCount=1` in the workflow as a safety net.

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

## Lessons Learned

> **This section is a living document.** Copilot agents **must read it before starting work** and **must append new entries** when they discover non-obvious pitfalls, architectural constraints, or debugging insights that would save future agents significant time. Each entry should be concise, actionable, and include the date.

### JGit DfsBlockCache is a JVM-global singleton — pack names must be unique (2026-03-12)

**Problem:** `DfsBlockCache` is a **static singleton** shared across the entire JVM. It caches pack index data keyed by `(DfsRepositoryDescription name + pack file name)`. When multiple Spring test contexts create `HibernateObjDatabase` instances in the same JVM (which happens because `@SpringBootTest` caches and reuses contexts), a **static** `PACK_ID_COUNTER` generates identical pack names (`pack-1-INSERT`, `pack-2-INSERT`, …). The second context writes *different* pack data to the database under the same name, but `DfsBlockCache` serves the *stale* cached index from the first context. This causes `RefUpdate` to return `REJECTED_MISSING_OBJECT` because the cached index doesn't contain the new commit's object ID, which then corrupts the reftable state (`Invalid reftable file`).

**Fix:** The `packIdCounter` in `HibernateObjDatabase` is **per-instance** (not static), initialized with `System.nanoTime() & 0x7FFF_FFFF` to guarantee unique pack names across contexts.

**Key takeaway:** Any `DfsObjDatabase` implementation that can be instantiated multiple times in the same JVM **must** generate globally unique pack names to avoid `DfsBlockCache` collisions.

### HibernateRepository constructor must not call clearAll()/close() (2026-03-12)

**Problem:** Calling `objdb.clearAll()` + `refdb.close()` in the `HibernateRepository` constructor was intended to clean stale data on startup. However, `refdb.close()` leaves the `DfsReftableDatabase` in a state that cannot recover — subsequent reftable operations fail with `Invalid reftable file`.

**Fix:** Removed both calls. With `ddl-auto=create` in tests, tables are already recreated fresh. In production, persistent data is expected and should not be cleared.

### Spring test context caching + ddl-auto=create can cause subtle state issues (2026-03-12)

**Problem:** Spring Boot tests with `@SpringBootTest` cache application contexts for reuse across test classes. Combined with `spring.jpa.hibernate.ddl-auto=create` (which drops and recreates tables on each *new* context), this means:
- Context A creates tables and writes data
- Context B (different config) creates *new* tables, wiping Context A's data
- Context A is reused later — its beans still hold stale references to data that no longer exists

**Key takeaway:** Beans that maintain internal state tied to database contents (like JGit's `DfsBlockCache`) must handle table recreation gracefully. Use unique identifiers, avoid static mutable state, or use `@DirtiesContext` as a last resort.

### HSQLDB in-memory + Hibernate SessionFactory for JGit (2026-03-12)

The `DslGitRepository` uses `HibernateRepository` backed by the same `SessionFactory` that Spring Boot manages. JGit pack data is stored in `git_packs` (via `GitPackEntity`) and reftable data is stored as pack extensions. The `HibernatePackOutputStream.flush()` opens **its own Hibernate session** and commits independently — this is safe because HSQLDB's `READ_COMMITTED` isolation ensures new sessions can read committed data immediately.

### DSL architecture — what to keep and what not to reinvent (2026-03-12)

| Component | Status | Notes |
|-----------|--------|-------|
| DSL Parser / Serializer / AST | ✅ Keep | Solid, Spring-independent |
| Validator | ✅ Keep | Useful semantic checks |
| AstToModelMapper / ModelToAstMapper | ✅ Keep | Bidirectional transformation |
| JGit commit/read/history | ✅ Keep | Blob→Tree→Commit→RefUpdate pattern is correct |
| `ModelDiffer` | ⚠️ Supplement | JGit `DiffFormatter` provides native text diffs; `ModelDiffer` is useful for semantic/structural comparison |
| `HibernateRepository` | ✅ Required | Must use database storage (not `InMemoryRepository`) for Hibernate Search compatibility |
| Cherry-pick / Merge | ✅ Added | Use JGit's `CherryPickCommand` / `MergeCommand` — do not reimplement |
| Dual JPA+JGit persistence | ❌ Removed | JGit in DB is the single source of truth; do not also save `ArchitectureDslDocument` on commit |
