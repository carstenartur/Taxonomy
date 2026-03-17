# Taxonomy Architecture Analyzer — Documentation Status

This file tracks known documentation gaps and improvement opportunities. Items marked with ✅ have been addressed.

---

## Remaining Documentation Tasks

### Screenshots / Visuals

- [x] Screenshot of the Proposal Review queue (pending proposals list, accept/reject buttons) — added to ScreenshotGeneratorIT (test 28)
- [x] Screenshots of all search modes (full-text, semantic, hybrid, similar, graph) — added to ScreenshotGeneratorIT (tests 29–32)
- [x] Screenshot of the export dialog and a sample Visio / ArchiMate output file — added to ScreenshotGeneratorIT (test 33)
- [x] Annotated diagram of the high-level system architecture (beyond Mermaid in ARCHITECTURE.md) — added detailed data-flow and request-lifecycle diagrams to ARCHITECTURE.md §Detailed Architecture Diagrams
- [x] Screenshot of the DSL Editor panel — added to ScreenshotGeneratorIT (test 34)
- [x] Hero image — fully expanded scored BP tree — added to ScreenshotGeneratorIT (test 35)
- [x] Proposal accepted lifecycle screenshot — added to ScreenshotGeneratorIT (test 36)
- [x] Graph Explorer with accepted relation — added to ScreenshotGeneratorIT (test 37)
- [x] Detailed architecture view with accepted proposals — added to ScreenshotGeneratorIT (test 38)
- [x] Scored sunburst chart with heat-mapped colours — added to ScreenshotGeneratorIT (test 39)
- [x] DSL Editor with relation blocks — added to ScreenshotGeneratorIT (test 40)

### Usability Documentation

- [x] **Graph Explorer shows tables, not a visual graph**: D3 force-directed graph implemented in `taxonomy-graph.js` with Graph/Table toggle (since initial implementation). Tables are preserved as an alternative view.
- [x] **LLM communication log only visible in interactive mode**: Moved from admin-only tab to a collapsible debug panel on the Analyze tab, visible to all users without admin mode.
- [x] **No internationalisation (i18n)**: all UI text is hard-coded in English. Recommended approach documented; implementation tracked separately. See §Internationalisation Roadmap below.

### API Documentation

- [x] **Curl examples**: comprehensive curl examples added to API_REFERENCE.md for all major endpoint categories
- [x] **No versioning strategy documented**: API versioning strategy documented in API_REFERENCE.md §API Versioning
- [x] **Rate-limiting documentation**: Rate limiting is now documented in CONFIGURATION_REFERENCE.md and ARCHITECTURE.md

### Documentation Structure

- [x] **USER_GUIDE.md too large (64 KB)**: Extracted topics into dedicated guides with cross-references:
  - [x] Framework Import → FRAMEWORK_IMPORT.md
  - [x] Git Integration → GIT_INTEGRATION.md
  - [x] Preferences → PREFERENCES.md
  - [x] AI Providers → AI_PROVIDERS.md
- [x] **Database setup files consolidated**: PostgreSQL, MSSQL, and Oracle setup guides merged into DATABASE_SETUP.md
- [x] **ORACLE-SETUP missing from Help tab**: Consolidated into DATABASE_SETUP.md and registered in HelpController
- [x] **HelpController DOCS list updated**: New documentation files registered (FRAMEWORK_IMPORT, GIT_INTEGRATION, PREFERENCES, AI_PROVIDERS, DATABASE_SETUP)
- [x] **Architecture documentation extended**: Git state, ViewContext/Action Guards, Framework Mapping Layer added to ARCHITECTURE.md
- [x] **Concepts extended**: Framework Mapping, Quality Analysis, Gap Analysis added to CONCEPTS.md
- [x] **Security extended**: Environment variables summary table, password change flow added to SECURITY.md
- [x] **Configuration Reference extended**: Runtime preferences table, framework import config added to CONFIGURATION_REFERENCE.md
- [x] **Deployment Guide extended**: Database backends section with migration path added to DEPLOYMENT_GUIDE.md

---

## Internationalisation (i18n) — Implemented ✅

Full i18n support has been implemented:

1. ✅ **Backend**: `I18nConfig` with `MessageSource`, `messages.properties` (English, 359 keys), `messages_de.properties` (German, 359 keys).
2. ✅ **Templates**: Thymeleaf `#{…}` expressions used throughout `index.html`.
3. ✅ **JavaScript**: `taxonomy-i18n.js` module loads translations from `/api/i18n/{locale}` and exposes `TaxonomyI18n.t('key')`.
4. ✅ **Locale detection**: `WebMvcConfig` with `CookieLocaleResolver` + `LocaleChangeInterceptor` (`?lang=` parameter). UI language selector in navbar with `localStorage` persistence.
5. ✅ **LLM prompts**: Prompt templates are customisable via the Prompt Template Editor — they can be translated per deployment without code changes.
6. ✅ **Documentation**: `docs/en/` (33 files) and `docs/de/` (33 files) with full parity. `HelpController` resolves docs by locale.

---

## Completed Items

<details>
<summary>Click to expand completed items</summary>

### Screenshots (completed)
- [x] Full page layout, left panel, right panel, analysis panel, all view modes
- [x] Scored taxonomy tree, interactive mode, match legend
- [x] Architecture view, graph explorer, relation proposals, leaf justification
- [x] Admin modal, LLM diagnostics, prompt template editor, stale results warning
- [x] Fully expanded scored tree, accepted proposal, graph with accepted relation
- [x] Detailed architecture view, scored sunburst, DSL with relations

### UX Documentation (completed)
- [x] Stale-results warning behaviour — documented in USER_GUIDE.md §6
- [x] Error handling for LLM timeout/rate-limit — documented in USER_GUIDE.md §4 and API_REFERENCE.md §16
- [x] AI Status badge states — documented in USER_GUIDE.md §14
- [x] Score colour coding thresholds — documented in USER_GUIDE.md §4
- [x] Streaming progress indicator phases — documented in USER_GUIDE.md §4
- [x] Export button visibility rule — documented in USER_GUIDE.md §4 and §10
- [x] Tooltip system for UI elements
- [x] Inline warning for disabled Analyze button
- [x] Dark mode CSS + toggle button

### Configuration Documentation (completed)
- [x] Complete environment variable reference — CONFIGURATION_REFERENCE.md
- [x] Docker deployment guide — DEPLOYMENT_GUIDE.md §1
- [x] Render.com deployment guide — DEPLOYMENT_GUIDE.md §2
- [x] Admin password configuration — CONFIGURATION_REFERENCE.md §Administration
- [x] Embedding model pre-download — CONFIGURATION_REFERENCE.md §Local Embedding Model
- [x] LLM provider priority order — CONFIGURATION_REFERENCE.md §LLM Provider Auto-Detection
- [x] Rate limiting configuration — CONFIGURATION_REFERENCE.md §Rate Limiting
- [x] Async initialization — CONFIGURATION_REFERENCE.md §Server Configuration (TAXONOMY_INIT_ASYNC)
- [x] SpringDoc enable/disable — CONFIGURATION_REFERENCE.md §OpenAPI / Swagger UI (TAXONOMY_SPRINGDOC_ENABLED)
- [x] Runtime preferences — CONFIGURATION_REFERENCE.md §Runtime Preferences and PREFERENCES.md
- [x] Framework import config — CONFIGURATION_REFERENCE.md §Framework Import Configuration

### API Documentation (completed)
- [x] OpenAPI / Swagger integration — springdoc-openapi at `/swagger-ui.html`
- [x] Standalone API reference — API_REFERENCE.md
- [x] Error response schema — API_REFERENCE.md §Error Response Schema
- [x] DSL API endpoints — API_REFERENCE.md §21
- [x] Report export endpoints — API_REFERENCE.md §20
- [x] Explanation trace endpoints — API_REFERENCE.md §19

### Architecture Documentation (completed)
- [x] Module architecture (4 modules) — ARCHITECTURE.md §Module Architecture
- [x] DSL storage architecture (JGit/Hibernate) — ARCHITECTURE.md §DSL Storage Architecture
- [x] Rate limiting — ARCHITECTURE.md §Rate Limiting
- [x] All 15 controllers listed in architecture diagram — ARCHITECTURE.md §High-Level Architecture
- [x] Git state tracking — ARCHITECTURE.md §Git State as First-Class Concept
- [x] ViewContext and Action Guards — ARCHITECTURE.md §ViewContext and Action Guards
- [x] Framework Mapping Layer — ARCHITECTURE.md §Framework Mapping Layer

### Conceptual Documentation (completed)
- [x] Relation types — CONCEPTS.md §Relation Type
- [x] Architecture DSL — CONCEPTS.md §Architecture DSL
- [x] Relation hypothesis lifecycle — CONCEPTS.md §Relation Hypothesis
- [x] Relation evidence — CONCEPTS.md §Relation Evidence
- [x] Explanation traces — CONCEPTS.md §Explanation Trace
- [x] Canonical architecture model — CONCEPTS.md §Canonical Architecture Model
- [x] Framework mappings — CONCEPTS.md §Framework Mapping
- [x] Quality and gap analysis — CONCEPTS.md §Quality Analysis and §Gap Analysis

### Security Documentation (completed)
- [x] Login rate limiting — SECURITY.md §Brute-Force Protection
- [x] Security headers — SECURITY.md §Security Headers
- [x] User management API — SECURITY.md §Managing Users
- [x] Audit logging — SECURITY.md §Audit Logging
- [x] Environment variables summary — SECURITY.md §Security Environment Variables Summary
- [x] Password change flow — SECURITY.md §Password Change Flow

</details>
