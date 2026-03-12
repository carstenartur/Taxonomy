# Taxonomy Architecture Analyzer — Documentation Status

This file tracks known documentation gaps and improvement opportunities. Items marked with ✅ have been addressed.

---

## Remaining Documentation Tasks

### Screenshots / Visuals

- [ ] Screenshot of the Proposal Review queue (pending proposals list, accept/reject buttons)
- [ ] Screenshots of all search modes (full-text, semantic, hybrid, similar, graph)
- [ ] Screenshot of the export dialog and a sample Visio / ArchiMate output file
- [ ] Annotated diagram of the high-level system architecture (beyond Mermaid in ARCHITECTURE.md)
- [ ] Screenshot of the DSL Editor panel

### Usability Documentation

- [ ] **Graph Explorer shows tables, not a visual graph**: a node-link force-directed graph would improve readability
- [ ] **LLM communication log only visible in interactive mode**: exposing it as a collapsible debug panel would aid troubleshooting
- [ ] **No internationalisation (i18n)**: all UI text is hard-coded in English

### API Documentation

- [ ] **Curl examples**: examples in API_REFERENCE.md should be kept in sync with actual endpoints
- [ ] **No versioning strategy documented**: the API is currently unversioned (`/api/...`)
- [x] **Rate-limiting documentation**: Rate limiting is now documented in CONFIGURATION_REFERENCE.md and ARCHITECTURE.md

---

## Completed Items

<details>
<summary>Click to expand completed items</summary>

### Screenshots (completed)
- [x] Full page layout, left panel, right panel, analysis panel, all view modes
- [x] Scored taxonomy tree, interactive mode, match legend
- [x] Architecture view, graph explorer, relation proposals, leaf justification
- [x] Admin modal, LLM diagnostics, prompt template editor, stale results warning

### UX Documentation (completed)
- [x] Stale-results warning behaviour — documented in USER_GUIDE.md §6
- [x] Error handling for LLM timeout/rate-limit — documented in USER_GUIDE.md §4 and API_REFERENCE.md §16
- [x] AI Status badge states — documented in USER_GUIDE.md §12
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
- [x] All 14 controllers listed in architecture diagram — ARCHITECTURE.md §High-Level Architecture

### Conceptual Documentation (completed)
- [x] Relation types — CONCEPTS.md §Relation Type
- [x] Architecture DSL — CONCEPTS.md §Architecture DSL
- [x] Relation hypothesis lifecycle — CONCEPTS.md §Relation Hypothesis
- [x] Relation evidence — CONCEPTS.md §Relation Evidence
- [x] Explanation traces — CONCEPTS.md §Explanation Trace
- [x] Canonical architecture model — CONCEPTS.md §Canonical Architecture Model

</details>
