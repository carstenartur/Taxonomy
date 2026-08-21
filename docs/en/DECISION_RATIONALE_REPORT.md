# Hierarchical Decision Rationale Report

The **Hierarchical Decision Rationale Report** documents how a requirement was routed through the taxonomy hierarchy and why the analysis ended at one or more concrete leaf nodes. It is a separate evidence artifact from the architecture report: the architecture report explains the resulting architecture view, while this report explains the hierarchy decisions that produced the classified leaf candidates.

## What the report contains

The report is available as DOCX, standalone HTML, and structured JSON. The DOCX and HTML variants contain:

- a professional title page with the requirement, document status, Taxonomy application version, build commit, taxonomy catalogue version, catalogue file SHA-256, loaded-data SHA-256, analysis-snapshot SHA-256, repository/workspace/branch/commit context, AI provider, generation timestamp, and generating account;
- an executive summary naming the highest-rated actual leaf node and showing the complete root-to-leaf decision path with a short reason for every step;
- one chapter for every actual parent node that has at least one directly scored child above 0%;
- a deterministic diagram in every chapter showing the parent and all direct children, including absolute score, local share of the parent score, rank, path disposition, and leading-sibling marker;
- a comparison table containing original AI reasons where available and clearly marked deterministic fallback text otherwise;
- a ranked list of positively scored actual leaf nodes, warnings, completeness information, and evidence metadata;
- a footer on every page with generation time, generating account, Taxonomy version, taxonomy data version, and `Page X of Y` fields.

## Score semantics

The values are **relevance and allocation scores**, not statistical probabilities.

- Taxonomy root areas are scored independently on a 0–100 scale. Their values do not need to sum to 100.
- Below a parent node, the absolute child score is the relevance value carried to that child.
- The local share is calculated as `child score / parent score × 100` and explains the decision within the sibling group.
- A present score of `0` means that the node was evaluated and rejected for this requirement.
- A missing score means that the node was not evaluated. Missing is never rendered as 0%.

## Completeness and status

A report is final only when all root areas were evaluated and every positive inner node has a score for each of its direct children. A positive parent whose children are missing, or whose analysis ended before reaching a real leaf, produces a clearly marked draft report. Positive parents whose evaluated children all scored zero are reported as unresolved classifications.

Possible statuses are:

- `FINAL`
- `FINAL_WITH_WARNINGS`
- `DRAFT_INCOMPLETE`
- `NO_RESULT`

## Traceability and reproducibility

Report generation does not ask an AI to reinterpret or change the completed analysis. The report reuses the original reasons returned during scoring, preserves all scores and hierarchy links, and identifies deterministic fallback descriptions as such. When a report is generated from an immutable project-analysis snapshot, the complete
taxonomy hierarchy serialized inside that snapshot is used. Current catalogue rows are
never combined silently with historical scores. Three independent SHA-256 fingerprints
are recorded:

1. the configured catalogue resource for ad-hoc reports, or an explicit note that the
   historical source file was not persisted separately in the snapshot;
2. the current or frozen taxonomy hierarchy actually used by the report;
3. the concrete analysis snapshot used to build the report.

The taxonomy fingerprint and prompt fingerprint recorded in the snapshot are shown as
separate evidence. A mismatch between the frozen hierarchy and the recorded taxonomy
fingerprint produces an explicit review warning.

The analysis fingerprint covers the requirement, provider, status, sorted scores, sorted reasons, and discrepancies. The same taxonomy and analysis snapshot therefore yield the same decision content; only generation metadata such as timestamp and account changes.

## Extension architecture

The feature is integrated as the report family `decision-rationale` through the existing
Spring-free `ReportRendererExtension` SPI. The report-renderer registry now addresses
renderers by the pair `(reportTypeId, formatId)`, so the existing architecture formats
remain `architecture/markdown`, `architecture/html`, `architecture/docx`, and
`architecture/json`, while this family registers:

- `decision-rationale/docx`
- `decision-rationale/html`
- `decision-rationale/json`

The registered extension IDs exposed by `/api/extensions` are
`decision-rationale:docx`, `decision-rationale:html`, and
`decision-rationale:json`. The controller contains no format-specific rendering logic;
it creates the trusted report model and delegates to the registry. A future format can
therefore be added as one additional renderer extension without changing the controller.

This is a classpath extension point, consistent with Taxonomy's existing extension
architecture. Taxonomy does not currently hot-load arbitrary external JARs at runtime.

## REST API

```http
GET  /api/decision-report/formats
POST /api/decision-report/docx
POST /api/decision-report/html
POST /api/decision-report/json
Content-Type: application/json
```

Example request:

```json
{
  "businessText": "Provide a secure integrated communication capability for operational teams.",
  "scores": {
    "CO": 90,
    "CO-1000": 70,
    "CO-1010": 55
  },
  "reasons": {
    "CO": "The requirement directly concerns communication services.",
    "CO-1000": "This category best captures the required service family.",
    "CO-1010": "This concrete service is the closest match to the stated need."
  },
  "provider": "GEMINI",
  "analysisStatus": "SUCCESS",
  "discrepancies": [],
  "language": "en"
}
```

The server derives the generating account and authoritative repository/workspace/branch/commit context from the authenticated request. Client-supplied identity or footer metadata is not accepted.

The response also contains these verification headers:

- `X-Taxonomy-Data-SHA256`
- `X-Taxonomy-Analysis-SHA256`


### Preferred path for fully processed requirements

For requirements managed in the project portfolio, generate the report directly from the
immutable analysis snapshot:

```http
GET /api/projects/{projectId}/snapshots/{snapshotId}/decision-report/formats
GET /api/projects/{projectId}/snapshots/{snapshotId}/decision-report/docx?language=en
GET /api/projects/{projectId}/snapshots/{snapshotId}/decision-report/html?language=en
GET /api/projects/{projectId}/snapshots/{snapshotId}/decision-report/json?language=en
```

This is preferred over the ad-hoc endpoint. The server loads the tenant-scoped stored
requirement text and version, original provider and model, scores, reasons, taxonomy and
prompt fingerprints, analysis author and timestamp, repository/workspace/branch/commit
provenance, and the complete hierarchy frozen in the analysis payload. A browser cannot
override this evidence. Snapshots that predate frozen hierarchy evidence are rejected with
a conflict instead of being rendered as if they were reproducible.

The requirement detail page exposes direct DOCX, HTML, and JSON download actions for the
selected snapshot. It uses the central `TaxonomyPortfolioApi` client instead of constructing
a separate project API path in the page module.

## User interface

After an analysis, the Export area offers:

- **Decision Report (.docx)**
- **Decision Report (.html)**
- **Decision Report (.json)**

For an ad-hoc export, the browser submits the score and reason maps held by the current analysis-session state. The report therefore states explicitly that it is not bound to an immutable project-analysis snapshot. Export remains available for imported saved analyses; missing provider or status metadata is then shown explicitly rather than invented.

## Operational configuration

```properties
taxonomy.catalogue.resource=classpath:data/C3_Taxonomy_Catalogue_25AUG2025.xlsx
taxonomy.report.time-zone=Europe/Berlin
git.commit.id=${GIT_COMMIT:${GITHUB_SHA:unknown}}
```

For released builds, `BuildProperties` supplies the application version and `GitProperties` or `git.commit.id` supplies the build commit. Unknown metadata is displayed as `unknown`; it is never silently replaced with a misleading value.
