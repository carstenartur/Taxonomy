# Project Requirement, Solution and Product Portfolio

## What this workspace is for

The project portfolio turns a collection of documents and business requirements into a traceable architecture and solution portfolio. Requirements remain separate throughout analysis, review and reporting. The application never silently concatenates independent requirement texts.

Use the portfolio when you need to:

- import and review several requirements for one project;
- keep immutable requirement versions and source provenance;
- analyse requirements independently and track background jobs;
- review taxonomy mappings and architecture impact;
- connect requirements to reusable solutions and sourced products;
- identify and resolve requirement conflicts;
- inspect consolidated matrices;
- commit, compare, materialize and merge portfolio states through Git;
- create human-readable and machine-readable reports.

All end-user steps described below are available through the web interface. Technical integration examples are documented separately in [PROJECT_PORTFOLIO_API.md](PROJECT_PORTFOLIO_API.md).

<a id="architecture-lifecycle"></a>
## Continue one architecture across projects

An architecture has a stable repository identity. Completing a project does not
complete, delete or replace that architecture. A later project can contribute new
requirements, revise an existing requirement or reuse it in the same architecture.
The original project-qualified requirement identity remains stable. The later
project is recorded as a contributor; it does not silently become the requirement's
original project. A similarly worded requirement is not automatically the same one.

The shared navigation connects analysis, projects, requirement detail, the saved
architecture workbench, the architecture editor and versioning. The context keeps
the architecture, project, requirement and selected model stand together. The
project overview and analysis initially show a collapsed summary on all screen sizes.
Architecture title, current selection, selected stand and read-only or problem indicators
remain visible. Open that line to access context details and lifecycle actions. Readable names
and business keys appear first; expand **Technical details** within the context for
repository, workspace, full commit, snapshot and requirement-version identifiers when available.
The existing help links open the relevant section of this guide or the
[editor guide](ARCHITECTURE_EDITOR.md).

Expand **Requirements, realization and history** to compare **Text version in selected stand**
with **Reviewed text version**. Three records have different meanings:

| Record | Meaning |
|---|---|
| Current requirement text | The latest immutable text version in the portfolio. A text edit creates a new version. |
| Selected architecture stand | The current workspace, an earlier semantic revision or a Git checkpoint; its requirement text belongs to that stand. |
| Reviewed realization | A person's assessment of one exact text version and hash against recorded architecture dependencies. |

The current requirement header can show version 2 while an older selected model
contains version 1. The table describes that older model; it does not relabel the
current portfolio version. An analysis snapshot also retains its own source version.
Changing text, generating an analysis or completing a project does not prove that
the architecture implements the new text.

| State | Interpretation |
|---|---|
| `OPEN` | No applicable partial or complete realization review is recorded. |
| `PARTIAL` | A person reviewed part of the exact requirement against selected evidence. |
| `COMPLETE` | A person recorded complete realization for the reviewed version and dependencies. |
| `REVIEW_REQUIRED` | The requirement or a recorded dependency changed after review; the earlier reviewed version remains visible. |
| `INVALID_EVIDENCE` | The recorded evidence cannot support a valid assessment and must be corrected. |

Choose the contribution project, status, architecture dependencies and rationale,
then preview and explicitly accept the review. Partial and complete assessments
require evidence. A relevance score is not an implementation percentage. See
[Review requirement realization](ARCHITECTURE_EDITOR.md#requirement-realization).

Selecting an earlier workspace revision or a reachable Git version makes the
model read-only. Historical stands use their stored source without overlaying
today's portfolio. An explicitly selected current revision remains the current
working stand. The editor, lifecycle panel and context header share the selection,
including browser Back/Forward. **Show current working state** returns to the live
model; **Refresh** reloads the selected stand. A missing or inaccessible stand
produces an error instead of silently showing current data.

An accepted contribution and a completed Git checkpoint are separate outcomes.
`CHECKPOINT_PENDING` means the change is retained but its Git write still needs to
finish. `RECONCILIATION_REQUIRED` requires comparing the local and incoming stands.
Keep the original command identity for an exact retry; changing the input requires
a fresh review. See [Reconcile versions](ARCHITECTURE_EDITOR.md#editor-version-reconciliation).

<a id="requirement-history"></a>
## Follow requirement and realization history

Open the history from **Requirements, realization and history**. It searches actual reachable
Git commits for the selected architecture and branch using native JGit and the
database-backed Git repository. It is separate from the editor's operation journal
and from the list of immutable text versions. No local Git command is required.

Enter text in **Search the Git log** and choose **Search**, or use a requirement
row's history action to narrow the result to its original project and requirement
identity. A follow-up project's contribution does not change that original filter.
Entries identify
requirement, metadata, dependency and realization changes, additions, removals and
merges where relevant. The commit subject keeps the list readable; expand **Commit details** for the full message, commit identity, actual parents, author, time
and available signature evidence. Parent links describe actual ancestry, not an
invented sequence of requirement versions.

The result identifies its Git source and captured HEAD. Pages have a bounded scan;
partial coverage or an empty bounded page does not prove that no older matching
change exists. Continue while another page is offered. If the branch advances,
restart the search against its new HEAD. A storage or authorization error is not
an empty history, and inaccessible branches or other users' private workspaces are
not searched. Opening a historical result selects its read-only model stand.

<a id="requirement-intake"></a>
## Review incoming text before saving

Use the intake review when saving a new requirement or promoting an analysis into
a project. The review can suggest a duplicate, change, addition or conflict against
the candidates it was permitted to inspect. Review the quoted evidence and source
before making one explicit choice:

| Choice | Result |
|---|---|
| `NEW` | Create a requirement with its own identity in the selected or new project. |
| `CHANGE` | Create a new immutable text version of the selected existing requirement, retaining its original identity. |
| `LINK` | Reuse the selected requirement and record the contribution without replacing its stored text. |

A follow-up project can contribute through `CHANGE` or `LINK`; verify the original
requirement and the contributing project separately. A candidate in another
architecture is evidence to inspect, not permission to mutate that architecture.
The offered actions respect the candidate's scope.

Retrieval and provider assessment are bounded. Inspect the displayed coverage and
provider status; an unassessed candidate, unavailable provider, timeout or failed
response is not a finding that no duplicate or conflict exists. Exact text equality
can be reported without proving semantic equivalence. Manual review and an explicit
choice remain available when AI review is unavailable. Local embeddings and mock
providers do not establish real generative assessment quality.

Changing text or context invalidates the previous review. `CHANGE` and `LINK` bind
to the selected current version and hash; if that version changes, reload, compare
and decide again. Nothing is created, linked or overwritten by merely opening the
review. See the [intake and publication API](PROJECT_PORTFOLIO_API.md#architecture-lifecycle-api).

<a id="requirement-clarification-workflow"></a>
## Clarify a requirement using its architecture context

For a new incoming text, start with the [intake decision](#requirement-intake).
For a later project, retain the [continuing architecture](#architecture-lifecycle).

Use the requirement detail workspace when the initial wording leaves decisions open.
A saved reformulation offer is separate from the source requirement and architecture.
Creating an offer, answering a question or editing its draft does not approve or
replace the active requirement.

| Step | What you do | What remains traceable |
|---|---|---|
| Select the source | Open the requirement, choose a saved version and its analysis snapshot | Exact original text, source version and analysed architecture |
| Create an offer | Request a separate wording proposal; inspect its synthesis status | A stored initial draft is not a completed or approved result |
| Resolve questions | Inspect architecture references; answer, defer or mark a question not applicable | Typed decisions, evidence and unresolved conflicts remain distinct |
| Review the proposal | Edit wording or statements, inspect additions and save the intended revision | Manual edits are protected from late model responses |
| Adopt explicitly | Review the saved adoption preview, warnings and final text; confirm with a rationale | A new requirement draft and its adoption receipt; earlier versions remain |
| Analyse separately | Request another analysis when appropriate | Adopted decisions retain their origins; the previous snapshot is not rewritten |

Formulation first works bottom-up within the selected taxonomy evidence. A bounded
cross-taxonomy review then reconciles boundaries and shared constraints. This is not
just a generic spelling rewrite: questions can refer to the elements and directed
relationships of the frozen analysis. Neither a completed run nor an empty question
list establishes approval or semantic completeness.

**Illustrative example, not a recorded model result:** “Employees must record working
time on mobile devices.” Review might raise whether offline capture is required,
who may correct entries and which system receives them. A human decision such as
“offline capture is required” should remain identifiable in the saved proposal and
subsequent adopted version. These exact questions are not guaranteed model output.

Export an exact saved proposal revision or adoption receipt as JSON, Markdown, HTML
or DOCX. Unsaved browser text is excluded. Historical exports use their recorded
source and architecture evidence, not today's catalogue. Adoption marks analysis as
needing refresh; it does not automatically analyse or alter the architecture.

See the [detailed reformulation contract](../features/requirement-reformulation.md)
and [verification limits](../testing/requirement-reformulation.md). Technical playback,
recovery and export tests do not establish general live-model language quality.

<a id="analysis-scope-and-progress"></a>
## Select analysis scope and understand incomplete work

The automatic analysis and Copilot controls let you select taxonomy roots and choose
full analysis or **taxonomies only**. A restricted root selection limits scoring and
relationship sources. In full mode, compatible relationship targets in other
sub-taxonomies can still be reached. Taxonomies-only mode does not run relationship
search or the architecture derivation that depends on it.

The result retains the scope actually used; changing the next run's settings does
not reinterpret an older result. Unselected or unvisited nodes do not become
negative findings. Restricted runs must not be read as a global gap assessment.

Progress distinguishes directly assessed nodes, descendants excluded by a valid
zero assessment and open nodes. Relationship progress records the planned
source/target-taxonomy work, completed and unresolved tasks and newly made provider
calls separately. A budget stop leaves unfinished work visible. Compatible
continuation reuses validated answers instead of paying for successful calls again;
changed input or recovery policy may require a new run.

A completed work plan is not proof that every possible relationship exists in the
result. Relevance weights are not calibrated probabilities or percentages of
requirement fulfilment. Local ONNX provides embeddings for search/scoring, not the
generative relationship or wording capabilities of a configured language model.

## Portfolio routes

| Workspace | Route |
|---|---|
| Project portfolio | `/projects` |
| Guided document import | `/projects/{projectId}/import` |
| Requirement detail | `/projects/{projectId}/requirements/{requirementId}` |
| Interactive matrices | `/projects/{projectId}/matrices` |
| Versioning and collaboration | `/projects/{projectId}/versioning` |
| Reports and exports | `/projects/{projectId}/reports` |

The placeholders are replaced automatically when you navigate from a selected project or requirement.

<a id="portfolio-projects"></a>
## 1. Create or select a project

For a follow-up project, retain the existing architecture and use the
[lifecycle workflow](#architecture-lifecycle) to create, change or link requirements.

Open `/projects`.

Choose **New project** and enter a unique project key, title and optional description. The project list remains available beside the working area so that you can switch projects without losing the current browser session.

The main workspace then shows requirements, taxonomy coverage, solutions, products, conflicts, snapshots and consolidated metrics.

<a id="portfolio-import"></a>
## 2. Import and review requirements

Open the selected project's **Import** action or navigate to `/projects/{projectId}/import`.

1. Upload a supported PDF or DOCX document.
2. Review every extracted candidate separately.
3. Edit the candidate key, title, type, priority, criticality and text where necessary.
4. Choose one explicit decision per candidate:
   - create a new requirement;
   - create a new version of an existing requirement;
   - merge it deliberately with another reviewed candidate;
   - discard it.
5. Review the summary before confirming the import.
6. Optionally start an independent analysis job for the retained requirements.

Source artifact, source version, fragment, section, page and original text remain attached to the resulting requirement version where available. Duplicate keys and incomplete decisions are rejected before anything is persisted.

The mixed import is atomic: either all reviewed decisions are accepted, or none of them changes the project.

## 3. Add or version requirements manually

Use **New requirement** in `/projects` for manual capture.

A requirement has a stable identity and an immutable current text version. Open its detail workspace at `/projects/{projectId}/requirements/{requirementId}` to:

- read the current business text and source;
- inspect all earlier versions;
- create a new version with a reason;
- compare analysis snapshots;
- inspect taxonomy mappings and architecture elements;
- review decisions, solution links and product candidates.

Changing the text creates a new version; it does not overwrite history.

## 4. Analyse requirements without blocking the page

Use **Analyze** for one requirement or **Analyze all** for the selected project.

The request is accepted as a persisted background job. The page remains usable while processing continues. The job centre shows:

- queued, running, successful, partial, failed or cancelled state;
- progress per requirement;
- attempts and result information;
- failed items that can be retried without repeating successful items.

Active and completed jobs are restored after a browser reload. A successful or partial item creates one immutable snapshot for exactly one requirement version.

## 5. Review mappings and architecture impact

Open a requirement detail workspace and select an analysis snapshot.

The snapshot shows the generated taxonomy mappings, architecture view, evidence, provider/model information and reproducibility fingerprints. Generated output and human review remain visibly separate.

For each relevant mapping, record the reviewed action where appropriate, for example:

- satisfied as-is;
- reuse;
- change;
- create;
- procure;
- organizational measure;
- retire or replace;
- undecided.

A confirmed decision requires real reviewer evidence or rationale. The application does not invent human evidence.

## 6. Review solutions and products

In the **Solutions** tab you can create reusable solution definitions and assign them to the project. The taxonomy picker searches by code and title and shows hierarchy context, so technical node identifiers do not need to be entered from memory.

After reviewing taxonomy coverage, connect solutions to the requirements they cover. Proposed links remain proposals until a reviewer confirms them.

In the **Products** tab, create sourced manufacturer/product/version entries. Product claims retain their source and verification date. Add products as candidates to a solution, compare alternatives and explicitly shortlist or select them. Hard exclusions prevent selection.

## 7. Detect and resolve conflicts

Choose **Detect conflicts** in the project workspace.

Each conflict card identifies both requirements and shows the conflict type, evidence and confidence. Open the guided decision dialog to confirm, reject, defer or resolve the hypothesis. A resolution requires a documented rationale and remains auditable.

Conflict detection supports professional requirements engineering; it does not replace human approval.

<a id="portfolio-matrices"></a>
## 8. Use the interactive matrices

Open `/projects/{projectId}/matrices`.

The workspace contains:

- requirement-to-taxonomy coverage;
- requirement-to-solution coverage;
- solution-to-product coverage.

Search, filter and sort the visible relationships. Select a non-empty cell to open its drill-down with origin, coverage, snapshot, review state, evidence and links to the corresponding requirement, taxonomy node, solution or product.

Empty cells mean that no relationship is stored. They do not mean that a relationship was evaluated at zero.

<a id="portfolio-versioning"></a>
## 9. Commit and merge reviewed portfolio states

For requirement changes and actual Git ancestry, use
[requirement history](#requirement-history). For a changed HEAD with accepted local
work, follow [explicit reconciliation](ARCHITECTURE_EDITOR.md#editor-version-reconciliation).

Open `/projects/{projectId}/versioning`.

The page shows the current branch, HEAD commit, portfolio counts and a technical TaxDSL preview.

You can:

1. commit the reviewed database projection to a selected branch;
2. preview how one branch HEAD would be materialized back into the portfolio;
3. inspect added and removed lines before applying the materialization;
4. apply only the exact HEAD that was reviewed;
5. merge two different branches through the semantic Git merge service.

If the target branch changes after the preview, materialization is rejected without changing portfolio data. This prevents a stale review from overwriting newer work.

<a id="portfolio-reports"></a>
## 10. Create reports and exports

Open `/projects/{projectId}/reports`.

Choose a project-wide report or narrow the report to one requirement. The preview and every export use the same current portfolio baseline.

Available formats are:

- HTML preview and download;
- Markdown;
- DOCX;
- JSON;
- CSV for the selected matrix.

Reports include requirement texts, source information, taxonomy coverage, solution and product decisions, conflicts and the reproducibility baseline of the relevant snapshots.

## Product demo without AI (QA reference)

Typing `produktdemo` (case and surrounding whitespace are ignored) into the analysis field and pressing the normal **Analyze** button does not start an AI analysis. It creates an isolated, labelled demo project instead (key `DEMO-…`, title `DEMO · <scenario title>` in the scenario's language). The typed draft and all other projects and requirements stay unchanged. Scenario: "Wir benötigen unternehmensweit ein System zur Arbeitszeiterfassung der Mitarbeiter." (company-wide working-time recording).

- **Only the AI reply is replaced.** Analysis, reformulation, answers, adoption, reference comparison and exports use the normal product paths. The external AI reply comes from a versioned scenario replay (provider `DEMO.TIME-TRACKING-V1.R0`/`R1`). A missing reply fails with `MISSING_REPLY`; there is no live-provider fallback.
- **Catalogue check.** At start, every code used by the scenario is verified against the loaded catalogue; a mismatch prevents the start.
- **Banner and stations.** The requirement page permanently shows the demo banner, the active scenario provider, the stations R0 → A0 → questions F-01…F-05 → answers/rejection of the GPS addition → adoption → R1 → A1 → reference evaluation, and the visible demo answers.
- **Flow.** Analyse R0, start the reformulation, answer the questions with the displayed demo answers and reject the GPS addition. If regeneration yields a protected candidate, use **Load candidate into editor**, save the draft, then adopt it with preview and confirmation. Afterwards analyse R1 and choose **Show reference evaluation**.
- **Admission.** Demo providers are admitted only for the demo requirement of the demo run; normal projects reject them (`PROVIDER_REQUIRES_PROJECT_BINDING`), and a demo project rejects any other provider (`DEMO_PROVIDER_REJECTED`). Changed scenario versions (`DEMO_SCENARIO_CHANGED`), unplanned iterations (`DEMO_ITERATION_UNAVAILABLE`), other requirements (`DEMO_REQUIREMENT_MISMATCH`) and closed runs (`DEMO_RUN_CLOSED`) are rejected; a retry whose admitted provider changed fails with `PROVIDER_BINDING_CHANGED`. The codes are returned in the problem-detail property `code`. Admission and closing lock the same demo-run row, so no replay analysis is admitted after a close commits.
- **Architecture.** The analysis context offers run-bound scenario replays through one provider strategy (`ScenarioReplayProviders`); the portfolio context only knows the generic port `ProjectProviderBinding`. The demo itself (run record, binding adapter, service and REST API) is composed in the application module.
- **Reference evaluation.** `GET /api/product-demo/runs/{id}/comparison` evaluates A0/A1 independently against the scenario reference; unexecuted steps appear as `NOT_EXECUTED`, never as negative findings.
- **API.** `GET /api/product-demo/scenarios`, `POST /api/product-demo/runs`, `GET /api/product-demo/runs[?projectId=…]`, `GET /api/product-demo/runs/{id}`, `POST /api/product-demo/runs/{id}/close`. Reads require the `USER`, `ARCHITECT` or `ADMIN` role; starting and closing require `ARCHITECT` or `ADMIN`.

## Roles and accessibility

Read-only users can inspect portfolio information but cannot perform architecture mutations. Disabled controls explain the missing capability. Architect and administrator roles can perform the reviewed write operations allowed by the security configuration.

The main workflows use native controls, keyboard-accessible dialogs, focusable error messages, responsive alternatives for matrices and layouts designed for narrow viewports and increased text size.

## Related documentation

- [PROJECT_PORTFOLIO_FEATURE_MATRIX.md](PROJECT_PORTFOLIO_FEATURE_MATRIX.md) — verified GUI, API and operational coverage
- [PROJECT_PORTFOLIO_API.md](PROJECT_PORTFOLIO_API.md) — REST contracts and automation examples
- [PROJECT_PORTFOLIO_GIT_COLLABORATION.md](PROJECT_PORTFOLIO_GIT_COLLABORATION.md) — collaboration and branch semantics
- [PROJECT_PORTFOLIO_OPERATIONS.md](PROJECT_PORTFOLIO_OPERATIONS.md) — operation, recovery and diagnostics
