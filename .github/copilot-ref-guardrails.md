# Guardrails — Read Before Making Any Changes

> **Read this when**: Before making any changes — read this first to avoid common mistakes.

---

## Taxonomy Codes — Never Invent Them

- **Real codes follow `XX-XXXX` format**: two uppercase letters, a hyphen, and four digits (e.g., `CP-1023`, `CR-1047`, `BP-1327`).
- **Not all four-digit numbers exist.** The codes are defined in the C3 Taxonomy Catalogue Excel workbook, not generated sequentially.
- **Never invent or guess codes** such as `CP-3`, `CR-5`, `CO-2`. They don't exist and will cause `400 Bad Request` from the API.
- **Always discover codes at runtime**: query `GET /api/taxonomy` and walk the returned tree to find real, existing codes.
- The 8 root codes are: `BP`, `BR`, `CP`, `CI`, `CO`, `CR`, `IP`, `UA` — these roots have no `-XXXX` suffix.

## Never Fake Taxonomy Data

- The taxonomy is loaded from the real Excel workbook on application startup. There is no need to mock it.
- Do not create fake in-memory taxonomies, stub node codes, or bypass the `TaxonomyService`.

## LLM Endpoints — Do Not Call in Tests

- **Do not call LLM endpoints** (`/api/analyze`, `/api/analyze-stream`, `/api/analyze-node`, `/api/justify-leaf`) in unit tests or exploratory scripts.
- These endpoints consume the shared free-tier Gemini quota (15 RPM, 1500 RPD).
- If you need LLM integration tests, use the existing `DiagnosticsWithApiKeyContainerIT` (at most 1–2 calls per run).

## Do Not Hardcode or Log GEMINI_API_KEY

- The key is a repository secret. It must never appear in source code, test output, or log files.
- The secret is masked in CI logs; keep it that way.

## ScreenshotGeneratorIT Is Opt-In Only

- The screenshot generator only runs when `-DgenerateScreenshots=true` is passed to Maven failsafe.
- It must **not** run as part of the normal `mvn verify` cycle.
- Add `Assumptions.assumeTrue(System.getProperty("generateScreenshots") != null)` guard if adding new screenshot test classes.

## Do Not Use `/api/diagnostics` for Health Checks

- `/api/diagnostics` returns **HTTP 401** when `ADMIN_PASSWORD` is configured (which it always is in container tests).
- Use `/api/ai-status` as the health-check endpoint instead — it is always public.

## Do Not Add Unnecessary Timeouts

- Extending test timeouts masks bugs. If a test is timing out, find and fix the root cause.
- Silent JavaScript promise failures (missing `.catch()`) are a common cause of Selenium timeouts.

## Use `mvn verify` as Final Validation When Needed

- Running only `mvn test` misses ALL integration tests (`*IT.java`).
- These ITs start the real application in Docker via Testcontainers and test against HSQLDB, PostgreSQL, Oracle, and MSSQL.
- Before finishing your work, run `mvn verify -DexcludedGroups="real-llm"` if your changes could affect controllers, GUI, startup config, pom.xml, or Dockerfiles.
- You do NOT need to run `mvn verify` for every small iteration — only as a final check before pushing.
- ⚠️ **Do NOT** weaken the test command by adding `-pl`, extra exclusion tags (`db-postgres`, `db-oracle`, `db-mssql`), or downgrading from `verify` to `test`. The CI runs `mvn verify -DexcludedGroups="real-llm"` — your local validation must match. If Docker/Testcontainers fail in your environment, report it rather than silently running fewer tests.

## Multi-Module Maven — Always Use `-am` With `-pl`

- `mvn verify` does NOT run `install` — sibling modules are NOT in `~/.m2/repository`.
- Any command using `-pl <module>` MUST also include `-am` (`--also-make`) or sibling dependencies will fail to resolve.
- When adding Maven commands to CI workflow files, always test them manually in your workspace first.
