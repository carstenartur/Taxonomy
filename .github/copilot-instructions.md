# GitHub Copilot Instructions — Taxonomy Architecture Analyzer

## Project Overview

Spring Boot 4 / Java 21 web application. Taxonomy data loaded from an Excel workbook via Apache POI. Full-text and KNN search via Hibernate Search 8 + Lucene 9. LLM analysis via Google Gemini (default) or other configured provider. UI is a single Bootstrap 5 page rendered by Thymeleaf.

## Build & Test

```bash
./mvnw compile            # compile only
./mvnw test               # unit + Spring context tests (never requires Docker or an API key)
./mvnw verify             # unit tests + integration tests (requires Docker for container ITs)
```

Integration test classes follow the `**/*IT.java` naming pattern and are run by `maven-failsafe-plugin`.

### CI Command

The CI pipeline runs exactly:
```bash
./mvnw -q verify -DexcludedGroups="real-llm"
```
This is the **authoritative** build command. It runs **all** unit tests and **all** integration tests (including Testcontainers-based PostgreSQL, MSSQL, and Oracle ITs), excluding only LLM tests that require a real API key.

### Validation Strategy

During iterative development, `./mvnw test` is sufficient for quick feedback.

**Before completing your work** (final commit before opening the PR), run:
```bash
./mvnw verify -DexcludedGroups="real-llm"
```
if your changes could affect any of the following:
- REST controllers or API endpoints
- GUI (HTML, JavaScript, CSS, Thymeleaf templates)
- Application startup, configuration, or Spring context (`application.properties`, Spring beans)
- `pom.xml` or dependency changes
- Dockerfile or container setup

⚠️ **Do NOT** invent alternative test commands (e.g., adding `-pl`, extra `-DexcludedGroups`, or switching `verify` to `test`). If `./mvnw verify` fails due to Docker/Testcontainers issues in your environment, report the failure — do not silently fall back to a weaker command.

For changes that only touch internal logic, Javadoc, comments, or documentation files, `./mvnw test` is sufficient.

**When modifying CI/CD workflow files** (`.github/workflows/*.yml`): manually execute every new or changed shell command in your workspace before committing. This is a multi-module Maven project — commands using `-pl <module>` must also include `-am` (`--also-make`), because `./mvnw verify` does not install sibling modules into `~/.m2/repository`.

## Critical Rules

1. **Taxonomy codes follow `XX-XXXX` format** — two uppercase letters, hyphen, four digits (e.g., `CP-1023`, `CR-1047`). Not all numbers exist. Never invent codes; discover them from the live taxonomy via `GET /api/taxonomy`.
2. **The taxonomy has 8 roots**: `BP`, `BR`, `CP`, `CI`, `CO`, `CR`, `IP`, `UA` — approximately 2,500 nodes loaded from the real Excel workbook.
3. **Do not call LLM endpoints unnecessarily** — the Gemini free tier is rate-limited (15 RPM, 1500 RPD) and shared across all uses.
4. **Do not hardcode or log `GEMINI_API_KEY`** — it is a repository secret; keep it masked.

## Reference Files — Read Only When Relevant to Your Task

| File | When to read |
|---|---|
| `.github/copilot-ref-guardrails.md` | **Before any changes** — hard constraints, common mistakes to avoid |
| `.github/copilot-ref-architecture.md` | To understand modules, services, data model, and DSL architecture |
| `.github/copilot-ref-screenshots.md` | When adding, modifying, or debugging `ScreenshotGeneratorIT` tests |
| `.github/copilot-ref-llm.md` | When working with Gemini API, rate limits, or LLM integration |
| `.github/copilot-ref-lessons.md` | When hitting known bugs or working on JGit / DSL / Hibernate areas |
