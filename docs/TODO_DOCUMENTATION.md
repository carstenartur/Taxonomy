# NATO Taxonomy Analyser — Documentation TODO

This file tracks known gaps, weaknesses, and usability issues identified through a code review of the application.  Items are grouped by category.  Tick a box when the issue has been addressed.

---

## Screenshots Required

The User Guide contains placeholder markers (`📸 Screenshot`) that need to be replaced with actual screenshots.
To generate screenshots:
1. Run the application locally with sample data
2. Perform each action described in the guide
3. Take browser screenshots (recommended: 1200px wide)
4. Save as PNG in `docs/images/` directory
5. Replace placeholder markers with `![description](images/filename.png)`

### Screenshots needed:
- [ ] Full page layout (taxonomy tree + analysis panel)
- [ ] Left panel in List view (nodes, score bars, action buttons)
- [ ] Right panel default state (analysis card, match legend, status area)
- [ ] Business Requirement Analysis panel (empty state)
- [ ] Scored taxonomy tree (List view with green highlights)
- [ ] Interactive Mode expand in progress
- [ ] Tabs view with scores
- [ ] Sunburst visualization
- [ ] Tree visualization
- [ ] Decision Map visualization
- [ ] Match legend close-up
- [ ] Export button group (visible state)
- [ ] Architecture View panel populated
- [ ] Graph Explorer panel (node code entered, before running a query)
- [ ] Graph Explorer with upstream results
- [ ] Graph Explorer with failure impact results
- [ ] Relation Proposals panel (pending filter)
- [ ] Propose Relations modal
- [ ] Leaf Justification modal
- [ ] AI Status badge (green/red states)
- [ ] Admin Mode modal (password entry)
- [ ] LLM Diagnostics panel
- [ ] Prompt Template Editor
- [ ] Stale results warning (yellow border)

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

- [x] Document the **stale-results warning** behaviour: when the taxonomy or LLM changes after analysis, how does the UI signal that cached results may be outdated? → *Documented in [USER_GUIDE.md](USER_GUIDE.md) §6 "Stale Results Warning"*
- [x] Document **error handling** for LLM timeout and rate-limit errors (HTTP 429, connection refused) — what message does the user see and what action is recommended? → *Documented in [USER_GUIDE.md](USER_GUIDE.md) §4 "Error Handling During Analysis" and [API_REFERENCE.md](API_REFERENCE.md) §16 "Error Response Schema"*
- [x] Document the **AI Status badge** in the toolbar: colours, states, and what each state means → *Documented in [USER_GUIDE.md](USER_GUIDE.md) §12 "AI Status Indicator" with three states (green/red/yellow)*
- [x] Document **score colour coding**: explain the thresholds that map scores (0–100) to red / amber / green visual indicators → *Documented in [USER_GUIDE.md](USER_GUIDE.md) §4 "Understanding Scores and the Colour Legend" with detailed alpha/threshold table*
- [x] Document the **streaming progress indicator**: what each SSE phase name means in plain English → *Documented in [USER_GUIDE.md](USER_GUIDE.md) §4 "Streaming Progress Indicator"*
- [x] Document the **export button visibility rule**: export buttons only appear when analysis scores > 0 → *Documented in [USER_GUIDE.md](USER_GUIDE.md) §4 "Export Button Visibility" and §10 "When Export Buttons Appear"*

---

## Missing Configuration Documentation

- [x] Publish a **complete, canonical list of all environment variables** with their types, defaults, and effects (currently scattered across `application.properties`, service constructors, and README) → *Created [CONFIGURATION_REFERENCE.md](CONFIGURATION_REFERENCE.md)*
- [x] Write a **Docker deployment guide**: multi-stage build, required `-e` flags, volume mounts, health-check endpoint → *Created [DEPLOYMENT_GUIDE.md](DEPLOYMENT_GUIDE.md) §1 "Docker Deployment"*
- [x] Write a **Render.com deployment guide**: explain each field in `render.yaml`, how to set secret environment variables in the Render dashboard → *Created [DEPLOYMENT_GUIDE.md](DEPLOYMENT_GUIDE.md) §2 "Render.com Deployment"*
- [x] Document the **admin password configuration** (`ADMIN_PASSWORD`): setting it in Docker, Render, and local development; what happens if it is left blank → *Documented in [CONFIGURATION_REFERENCE.md](CONFIGURATION_REFERENCE.md) §Administration*
- [x] Document **embedding model pre-download**: step-by-step instructions for downloading `all-MiniLM-L6-v2` for air-gapped deployments, and how to set `JGIT_EMBEDDING_MODEL_DIR` → *Documented in [CONFIGURATION_REFERENCE.md](CONFIGURATION_REFERENCE.md) §Local Embedding Model*
- [x] Document **LLM provider priority order**: when `LLM_PROVIDER` is not set, document which provider is chosen when multiple API keys are present simultaneously → *Documented in [CONFIGURATION_REFERENCE.md](CONFIGURATION_REFERENCE.md) §LLM Provider Auto-Detection Priority Order*

---

## Usability Weaknesses (Identified from Code Review)

- [ ] **No onboarding / guided tour**: first-time users have no walkthrough explaining what the taxonomy is, how to write a requirement, or where to start
- [x] **No tooltip system**: taxonomy nodes, score bars, relation types, and dashboard metrics have no hover-text explaining what they represent → *Added title attributes to key UI elements (legend boxes, checkboxes, badges, buttons)*
- [x] **Analysis button disabled without AI key — error not prominent**: the button is disabled but the reason is not displayed until the user inspects the AI status badge; a visible inline message would help → *Added inline warning below Analyze button showing required env vars*
- [ ] **No bulk accept / reject for proposals**: reviewers must accept or reject proposals one at a time; a "Select all" + bulk action workflow is missing
- [ ] **Graph Explorer shows tables, not a visual graph**: upstream / downstream results are rendered as stat cards and data tables; a node-link force-directed graph (D3.js or similar) would greatly improve readability
- [ ] **No undo for accept / reject**: once a proposal is accepted or rejected there is no UI action to reverse the decision (API-level workaround only)
- [x] **Export buttons only visible when scores > 0**: if the user navigates away and returns, scores may be lost and export buttons disappear with no explanation → *Added hint text "📋 Analyze first to enable exports" when export buttons are hidden*
- [ ] **LLM communication log only visible in interactive mode**: the raw prompt/response log is surfaced in interactive mode but not in standard batch analysis; exposing it as a collapsible debug panel would aid troubleshooting
- [x] **No dark mode**: the application has no dark-theme CSS; users in low-light environments or with high-contrast needs have no alternative → *Added dark mode CSS + 🌙/☀️ toggle button with localStorage persistence*
- [ ] **No internationalisation (i18n)**: all UI text is hard-coded in English; there is no mechanism to add translations

---

## Missing API Documentation

- [x] **No OpenAPI / Swagger specification**: the project has no `springdoc-openapi` or Swagger 3 integration; adding it would auto-generate interactive API docs at `/swagger-ui.html` → *Added springdoc-openapi v3.0.2 with OpenAPI annotations on all controllers; interactive docs at `/swagger-ui.html`*
- [x] **No standalone REST API reference document**: the only API documentation is currently inline in this `USER_GUIDE.md`; a dedicated `docs/API_REFERENCE.md` or an OpenAPI spec file would be preferable → *[API_REFERENCE.md](API_REFERENCE.md) exists as a comprehensive standalone document*
- [ ] **Curl examples only in README**: the README contains a handful of curl examples but they are not kept in sync with the actual endpoints; they should be moved to a dedicated examples section or replaced by an OpenAPI spec
- [x] **No documented error response schema**: the API returns various HTTP 4xx / 5xx responses but the error body format (fields, codes) is not documented → *Documented in [API_REFERENCE.md](API_REFERENCE.md) §16 "Error Response Schema"*
- [ ] **No versioning strategy documented**: the API is currently unversioned (`/api/...`); future breaking changes may require a `/api/v2/...` strategy
- [ ] **No rate-limiting documentation**: it is unclear whether the application enforces any rate limits on LLM-backed endpoints, and if so, what the limits are
