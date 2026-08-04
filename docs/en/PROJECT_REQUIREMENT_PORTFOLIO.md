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

## 1. Create or select a project

Open `/projects`.

Choose **New project** and enter a unique project key, title and optional description. The project list remains available beside the working area so that you can switch projects without losing the current browser session.

The main workspace then shows requirements, taxonomy coverage, solutions, products, conflicts, snapshots and consolidated metrics.

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

## 8. Use the interactive matrices

Open `/projects/{projectId}/matrices`.

The workspace contains:

- requirement-to-taxonomy coverage;
- requirement-to-solution coverage;
- solution-to-product coverage.

Search, filter and sort the visible relationships. Select a non-empty cell to open its drill-down with origin, coverage, snapshot, review state, evidence and links to the corresponding requirement, taxonomy node, solution or product.

Empty cells mean that no relationship is stored. They do not mean that a relationship was evaluated at zero.

## 9. Commit and merge reviewed portfolio states

Open `/projects/{projectId}/versioning`.

The page shows the current branch, HEAD commit, portfolio counts and a technical TaxDSL preview.

You can:

1. commit the reviewed database projection to a selected branch;
2. preview how one branch HEAD would be materialized back into the portfolio;
3. inspect added and removed lines before applying the materialization;
4. apply only the exact HEAD that was reviewed;
5. merge two different branches through the semantic Git merge service.

If the target branch changes after the preview, materialization is rejected without changing portfolio data. This prevents a stale review from overwriting newer work.

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

## Roles and accessibility

Read-only users can inspect portfolio information but cannot perform architecture mutations. Disabled controls explain the missing capability. Architect and administrator roles can perform the reviewed write operations allowed by the security configuration.

The main workflows use native controls, keyboard-accessible dialogs, focusable error messages, responsive alternatives for matrices and layouts designed for narrow viewports and increased text size.

## Related documentation

- [PROJECT_PORTFOLIO_FEATURE_MATRIX.md](PROJECT_PORTFOLIO_FEATURE_MATRIX.md) — verified GUI, API and operational coverage
- [PROJECT_PORTFOLIO_API.md](PROJECT_PORTFOLIO_API.md) — REST contracts and automation examples
- [PROJECT_PORTFOLIO_GIT_COLLABORATION.md](PROJECT_PORTFOLIO_GIT_COLLABORATION.md) — collaboration and branch semantics
- [PROJECT_PORTFOLIO_OPERATIONS.md](PROJECT_PORTFOLIO_OPERATIONS.md) — operation, recovery and diagnostics
