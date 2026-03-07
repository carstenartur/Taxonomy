# NATO Taxonomy Analyser — Documentation TODO

This file tracks known gaps, weaknesses, and usability issues identified through a code review of the application.  Items are grouped by category.  Tick a box when the issue has been addressed.

---

## Missing Screenshots / Visuals

- [ ] Screenshot of the main UI (taxonomy tree, analysis results panel, score bars)
- [ ] Screenshot / screencast of interactive mode (Analyze Node, level-by-level expansion)
- [ ] Screenshot of the Architecture View panel (anchors, elements, relationships)
- [ ] Screenshot of the Graph Explorer panel (upstream / downstream / failure-impact result tables)
- [ ] Screenshot of the Proposal Review queue (pending proposals list, accept/reject buttons)
- [ ] Screenshots of all search modes (full-text, semantic, hybrid, similar, graph)
- [ ] Screenshot of the export dialog and a sample Visio / ArchiMate output file
- [ ] Screenshot of the Admin Panel (diagnostics page, prompt template editor)
- [ ] Annotated diagram of the high-level system architecture

---

## Missing UX Documentation

- [ ] Document the **stale-results warning** behaviour: when the taxonomy or LLM changes after analysis, how does the UI signal that cached results may be outdated?
- [ ] Document **error handling** for LLM timeout and rate-limit errors (HTTP 429, connection refused) — what message does the user see and what action is recommended?
- [ ] Document the **AI Status badge** in the toolbar: colours, states, and what each state means
- [ ] Document **score colour coding**: explain the thresholds that map scores (0–100) to red / amber / green visual indicators
- [ ] Document the **streaming progress indicator**: what each SSE phase name means in plain English
- [ ] Document the **export button visibility rule**: export buttons only appear when analysis scores > 0

---

## Missing Configuration Documentation

- [ ] Publish a **complete, canonical list of all environment variables** with their types, defaults, and effects (currently scattered across `application.properties`, service constructors, and README)
- [ ] Write a **Docker deployment guide**: multi-stage build, required `-e` flags, volume mounts, health-check endpoint
- [ ] Write a **Render.com deployment guide**: explain each field in `render.yaml`, how to set secret environment variables in the Render dashboard
- [ ] Document the **admin password configuration** (`ADMIN_PASSWORD`): setting it in Docker, Render, and local development; what happens if it is left blank
- [ ] Document **embedding model pre-download**: step-by-step instructions for downloading `all-MiniLM-L6-v2` for air-gapped deployments, and how to set `JGIT_EMBEDDING_MODEL_DIR`
- [ ] Document **LLM provider priority order**: when `LLM_PROVIDER` is not set, document which provider is chosen when multiple API keys are present simultaneously

---

## Usability Weaknesses (Identified from Code Review)

- [ ] **No onboarding / guided tour**: first-time users have no walkthrough explaining what the taxonomy is, how to write a requirement, or where to start
- [ ] **No tooltip system**: taxonomy nodes, score bars, relation types, and dashboard metrics have no hover-text explaining what they represent
- [ ] **Analysis button disabled without AI key — error not prominent**: the button is disabled but the reason is not displayed until the user inspects the AI status badge; a visible inline message would help
- [ ] **No bulk accept / reject for proposals**: reviewers must accept or reject proposals one at a time; a "Select all" + bulk action workflow is missing
- [ ] **Graph Explorer shows tables, not a visual graph**: upstream / downstream results are rendered as stat cards and data tables; a node-link force-directed graph (D3.js or similar) would greatly improve readability
- [ ] **No undo for accept / reject**: once a proposal is accepted or rejected there is no UI action to reverse the decision (API-level workaround only)
- [ ] **Export buttons only visible when scores > 0**: if the user navigates away and returns, scores may be lost and export buttons disappear with no explanation
- [ ] **LLM communication log only visible in interactive mode**: the raw prompt/response log is surfaced in interactive mode but not in standard batch analysis; exposing it as a collapsible debug panel would aid troubleshooting
- [ ] **No dark mode**: the application has no dark-theme CSS; users in low-light environments or with high-contrast needs have no alternative
- [ ] **No internationalisation (i18n)**: all UI text is hard-coded in English; there is no mechanism to add translations

---

## Missing API Documentation

- [ ] **No OpenAPI / Swagger specification**: the project has no `springdoc-openapi` or Swagger 3 integration; adding it would auto-generate interactive API docs at `/swagger-ui.html`
- [ ] **No standalone REST API reference document**: the only API documentation is currently inline in this `USER_GUIDE.md`; a dedicated `docs/API_REFERENCE.md` or an OpenAPI spec file would be preferable
- [ ] **Curl examples only in README**: the README contains a handful of curl examples but they are not kept in sync with the actual endpoints; they should be moved to a dedicated examples section or replaced by an OpenAPI spec
- [ ] **No documented error response schema**: the API returns various HTTP 4xx / 5xx responses but the error body format (fields, codes) is not documented
- [ ] **No versioning strategy documented**: the API is currently unversioned (`/api/...`); future breaking changes may require a `/api/v2/...` strategy
- [ ] **No rate-limiting documentation**: it is unclear whether the application enforces any rate limits on LLM-backed endpoints, and if so, what the limits are
