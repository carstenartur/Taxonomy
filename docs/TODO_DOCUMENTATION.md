# NATO Taxonomy Analyser — Documentation Status

This file tracks known documentation gaps and improvement opportunities. Items marked with ✅ have been addressed.

---

## Remaining Documentation Tasks

### Screenshots / Visuals

- [ ] Screenshot of the Proposal Review queue (pending proposals list, accept/reject buttons)
- [ ] Screenshots of all search modes (full-text, semantic, hybrid, similar, graph)
- [ ] Screenshot of the export dialog and a sample Visio / ArchiMate output file
- [ ] Annotated diagram of the high-level system architecture (beyond Mermaid in ARCHITECTURE.md)

### Usability Documentation

- [ ] **No bulk accept/reject for proposals**: reviewers must accept or reject proposals one at a time
- [ ] **Graph Explorer shows tables, not a visual graph**: a node-link force-directed graph would improve readability
- [ ] **LLM communication log only visible in interactive mode**: exposing it as a collapsible debug panel would aid troubleshooting
- [ ] **No internationalisation (i18n)**: all UI text is hard-coded in English

### API Documentation

- [ ] **Curl examples**: examples in API_REFERENCE.md should be kept in sync with actual endpoints
- [ ] **No versioning strategy documented**: the API is currently unversioned (`/api/...`)
- [ ] **No rate-limiting documentation**: clarify whether the application enforces rate limits on LLM-backed endpoints

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

### API Documentation (completed)
- [x] OpenAPI / Swagger integration — springdoc-openapi at `/swagger-ui.html`
- [x] Standalone API reference — API_REFERENCE.md
- [x] Error response schema — API_REFERENCE.md §20

</details>
