# Project Portfolio API

This reference is for integrations, automation, testing and custom clients. End users should use the GUI-first [Project Portfolio Guide](PROJECT_REQUIREMENT_PORTFOLIO.md).

## Authentication and write protection

All endpoints require an authenticated user. In the local form-login profile, browser sessions require CSRF tokens for writes. Explicit HTTP Basic or Bearer clients are treated as stateless API clients.

Roles:

- `USER`, `ARCHITECT`, `ADMIN`: read portfolio resources and start permitted analyses;
- `ARCHITECT`, `ADMIN`: mutate projects, requirements, solutions, products, reviews, conflicts and Git state;
- `ADMIN`: administrative APIs outside the portfolio.

Unknown API write methods are denied by default. Workspace-bound operations fail closed when no isolated workspace can be resolved, unless an explicitly configured legacy shared mode is active.

## Error contract

Portfolio validation, not-found and conflict errors use RFC 9457-style `ProblemDetail` JSON. Clients should primarily inspect `status`, `title` and `detail` rather than parsing human-readable text.

## Projects

```text
POST   /api/projects
GET    /api/projects
GET    /api/projects/{projectId}
PATCH  /api/projects/{projectId}
```

Create a project:

```bash
curl -u architect:password \
  -X POST http://localhost:8080/api/projects \
  -H 'Content-Type: application/json' \
  -d '{
    "projectKey": "P-001",
    "title": "Secure collaboration platform",
    "description": "Architecture project for the target platform",
    "status": "ACTIVE"
  }'
```

## Requirements and immutable versions

```text
POST   /api/projects/{projectId}/requirements
GET    /api/projects/{projectId}/requirements
GET    /api/projects/{projectId}/requirements/{requirementId}
PATCH  /api/projects/{projectId}/requirements/{requirementId}
POST   /api/projects/{projectId}/requirements/{requirementId}/versions
GET    /api/projects/{projectId}/requirements/{requirementId}/versions
```

Create one requirement:

```bash
curl -u architect:password \
  -X POST http://localhost:8080/api/projects/1/requirements \
  -H 'Content-Type: application/json' \
  -d '{
    "requirementKey": "REQ-001",
    "title": "Encrypted communication",
    "text": "The platform shall encrypt all user communication.",
    "status": "DRAFT",
    "priority": 80,
    "criticality": "HIGH",
    "requirementType": "SECURITY",
    "reviewStatus": "PROPOSED",
    "changeReason": "Initial version",
    "source": {
      "sourceArtifactId": 41,
      "sourceVersionId": 42,
      "sourceFragmentIds": [101, 102],
      "sectionReference": "Section 5.2",
      "pageNumber": 17,
      "originalText": "All communication shall be encrypted."
    }
  }'
```

Create a new immutable text version:

```bash
curl -u architect:password \
  -X POST http://localhost:8080/api/projects/1/requirements/11/versions \
  -H 'Content-Type: application/json' \
  -d '{
    "text": "The platform shall use approved end-to-end encryption for all user communication.",
    "changeReason": "Clarified the encryption requirement",
    "source": {
      "sectionReference": "Section 5.2.1",
      "pageNumber": 18,
      "originalText": "Approved end-to-end encryption is mandatory."
    }
  }'
```

## Document parsing and extraction

The guided GUI uses the existing provenance endpoints:

```text
POST /api/documents/upload       multipart/form-data
POST /api/documents/extract-ai   multipart/form-data
```

Upload and parse a source:

```bash
curl -u architect:password \
  -X POST http://localhost:8080/api/documents/upload \
  -F 'file=@requirements.pdf' \
  -F 'title=Platform requirements' \
  -F 'sourceType=REGULATION'
```

The response contains source artifact/version identifiers, deterministic candidates, page information and warnings.

## Atomic reviewed import

The mixed reviewed-import endpoint applies new requirements and new versions in one transaction:

```text
POST /api/projects/{projectId}/requirements/import-review
```

```bash
curl -u architect:password \
  -X POST http://localhost:8080/api/projects/1/requirements/import-review \
  -H 'Content-Type: application/json' \
  -d '{
    "analyzeAfterImport": true,
    "maxArchitectureNodes": 25,
    "idempotencyKey": "source-42-reviewed-2026-08-04",
    "items": [
      {
        "decision": "NEW_REQUIREMENT",
        "requirementKey": "REQ-002",
        "title": "Audit history",
        "text": "The platform shall retain an auditable decision history.",
        "requirementType": "FUNCTIONAL",
        "priority": 70,
        "criticality": "HIGH",
        "source": {
          "sourceArtifactId": 41,
          "sourceVersionId": 42,
          "sectionReference": "Section 7",
          "pageNumber": 22,
          "originalText": "An auditable history shall be retained."
        }
      },
      {
        "decision": "NEW_VERSION",
        "targetRequirementId": 11,
        "title": "Encrypted communication",
        "text": "The platform shall use approved end-to-end encryption.",
        "requirementType": "SECURITY",
        "priority": 80,
        "criticality": "HIGH",
        "source": {
          "sourceArtifactId": 41,
          "sourceVersionId": 42,
          "sectionReference": "Section 5.2.1",
          "pageNumber": 18,
          "originalText": "Approved end-to-end encryption is mandatory."
        }
      }
    ]
  }'
```

When analysis is requested, the endpoint returns `202 Accepted` and a canonical `Location` header for the persisted job. Otherwise it returns `201 Created`.

## Analysis jobs

```text
POST   /api/projects/{projectId}/analyses
POST   /api/projects/{projectId}/requirements/{requirementId}/analyses
GET    /api/projects/{projectId}/analysis-jobs
GET    /api/projects/{projectId}/analysis-jobs/{jobId}
POST   /api/projects/{projectId}/analysis-jobs/{jobId}/retry-failed
```

Analyse all current requirements separately:

```bash
curl -i -u analyst:password \
  -X POST http://localhost:8080/api/projects/1/analyses \
  -H 'Content-Type: application/json' \
  -d '{
    "all": true,
    "provider": "GEMINI",
    "maxArchitectureNodes": 25,
    "idempotencyKey": "P-001-baseline-2026-08-04"
  }'
```

Typical response:

```text
HTTP/1.1 202 Accepted
Location: /api/projects/1/analysis-jobs/550e8400-e29b-41d4-a716-446655440000
```

Poll the job:

```bash
curl -u analyst:password \
  http://localhost:8080/api/projects/1/analysis-jobs/550e8400-e29b-41d4-a716-446655440000
```

Job/item states:

```text
PENDING, RUNNING, SUCCESS, PARTIAL, FAILED, CANCELLED
```

Retry only failed or recoverable items:

```bash
curl -u analyst:password \
  -X POST http://localhost:8080/api/projects/1/analysis-jobs/550e8400-e29b-41d4-a716-446655440000/retry-failed \
  -H 'Content-Type: application/json' \
  -d '{}'
```

## Snapshots, mappings and review

```text
GET    /api/projects/{projectId}/requirements/{requirementId}/snapshots
GET    /api/projects/{projectId}/snapshots/{snapshotId}
GET    /api/projects/{projectId}/snapshots/diff?older=...&newer=...
PATCH  /api/projects/{projectId}/analysis-mappings/elements/{mappingId}
PATCH  /api/projects/{projectId}/analysis-mappings/relations/{mappingId}
```

Review one element mapping with actual evidence:

```bash
curl -u architect:password \
  -X PATCH http://localhost:8080/api/projects/1/analysis-mappings/elements/42 \
  -H 'Content-Type: application/json' \
  -d '{
    "reviewStatus": "CONFIRMED",
    "actionStatus": "REUSE",
    "actionEvidence": "Service catalogue entry SOL-004 was verified against the requirement.",
    "comment": "Reviewed by the project architect."
  }'
```

Action states:

```text
UNDECIDED
SATISFIED_AS_IS
REUSE
CHANGE
CREATE
PROCURE
ORGANIZATIONAL
RETIRE_OR_REPLACE
```

## Solutions

```text
POST   /api/solutions
GET    /api/solutions
GET    /api/solutions/{solutionId}
PATCH  /api/solutions/{solutionId}
POST   /api/solutions/{solutionId}/taxonomy-coverage

POST   /api/projects/{projectId}/solutions
GET    /api/projects/{projectId}/solutions
PATCH  /api/projects/{projectId}/solutions/{projectSolutionId}
POST   /api/projects/{projectId}/solutions/{projectSolutionId}/requirements
POST   /api/projects/{projectId}/solutions/propose-from-taxonomy
```

Create and add a solution:

```bash
curl -u architect:password \
  -X POST http://localhost:8080/api/solutions \
  -H 'Content-Type: application/json' \
  -d '{
    "solutionKey": "SOL-001",
    "title": "Secure communication service",
    "description": "Reusable encrypted communication capability",
    "solutionType": "SERVICE",
    "operatingModel": "PRIVATE_CLOUD",
    "lifecycleStatus": "PLANNED",
    "maturityLevel": 2,
    "extensionAttributes": {}
  }'
```

```bash
curl -u architect:password \
  -X POST http://localhost:8080/api/projects/1/solutions \
  -H 'Content-Type: application/json' \
  -d '{
    "solutionId": 21,
    "status": "PROPOSED",
    "actionStatus": "REUSE",
    "priority": 70,
    "rationale": "Candidate for REQ-001 and REQ-002"
  }'
```

Add confirmed taxonomy coverage:

```bash
curl -u architect:password \
  -X POST http://localhost:8080/api/solutions/21/taxonomy-coverage \
  -H 'Content-Type: application/json' \
  -d '{
    "nodeCode": "CR-1047",
    "coveragePercent": 90,
    "evidence": "Verified solution architecture description, version 3.1",
    "reviewStatus": "CONFIRMED"
  }'
```

Link the project solution to one exact requirement snapshot:

```bash
curl -u architect:password \
  -X POST http://localhost:8080/api/projects/1/solutions/31/requirements \
  -H 'Content-Type: application/json' \
  -d '{
    "requirementId": 11,
    "snapshotId": "snapshot-uuid",
    "coveragePercent": 85,
    "role": "USES",
    "reviewStatus": "CONFIRMED",
    "evidence": "Snapshot and solution capability were reviewed together."
  }'
```

## Products and candidates

```text
POST   /api/products
GET    /api/products
GET    /api/products/{productId}
PATCH  /api/products/{productId}
POST   /api/products/{productId}/taxonomy-coverage

POST   /api/projects/{projectId}/solutions/{projectSolutionId}/products
GET    /api/projects/{projectId}/solutions/{projectSolutionId}/products
```

Create a sourced product:

```bash
curl -u architect:password \
  -X POST http://localhost:8080/api/products \
  -H 'Content-Type: application/json' \
  -d '{
    "productKey": "PRD-001",
    "manufacturer": "Example Vendor",
    "productFamily": "Secure Collaboration",
    "productName": "Collaboration Server",
    "editionVersion": "4.2",
    "productStatus": "CANDIDATE",
    "operatingModel": "PRIVATE_CLOUD",
    "sourceReference": "Vendor architecture guide 4.2, chapter 6",
    "verifiedAt": "2026-08-04T08:00:00Z"
  }'
```

Add or update a candidate:

```bash
curl -u architect:password \
  -X POST http://localhost:8080/api/projects/1/solutions/31/products \
  -H 'Content-Type: application/json' \
  -d '{
    "productId": 51,
    "coveragePercent": 85,
    "hardExclusions": null,
    "strengths": "Strong identity and encryption integration",
    "weaknesses": "Additional migration work",
    "openEvidence": "Performance benchmark remains open",
    "confidence": 0.82,
    "reviewStatus": "CONFIRMED",
    "selectionStatus": "SHORTLISTED"
  }'
```

A product may be `SELECTED` only with `reviewStatus=CONFIRMED` and without a hard exclusion.

## Conflict registry

```text
POST   /api/projects/{projectId}/conflicts/detect
GET    /api/projects/{projectId}/conflicts
PATCH  /api/projects/{projectId}/conflicts/{conflictId}
```

Resolve a reviewed conflict:

```bash
curl -u architect:password \
  -X PATCH http://localhost:8080/api/projects/1/conflicts/71 \
  -H 'Content-Type: application/json' \
  -d '{
    "status": "RESOLVED",
    "resolutionNote": "The approved private cloud profile satisfies both requirements."
  }'
```

Conflict states:

```text
PROPOSED, CONFIRMED, REJECTED, RESOLVED
```

## Consolidated portfolio and matrices

```text
GET /api/projects/{projectId}/portfolio
```

The response contains project data, requirements, aggregated taxonomy nodes, solutions, conflicts, metrics and these matrix objects:

- `requirementTaxonomyMatrix`
- `requirementSolutionMatrix`
- `solutionProductMatrix`

There are no separate `/matrices/...` endpoints. Each matrix contains `rows`, `columns` and a nested `values` map.

## Git projection and collaboration

```text
GET    /api/projects/git/export
POST   /api/projects/git/commit
GET    /api/projects/git/materialize-preview?branch=...
POST   /api/projects/git/materialize
POST   /api/projects/git/merge?source=...&target=...
```

Export the current workspace portfolio as canonical TaxDSL:

```bash
curl -u architect:password \
  http://localhost:8080/api/projects/git/export
```

Commit the reviewed projection:

```bash
curl -u architect:password \
  -X POST http://localhost:8080/api/projects/git/commit \
  -H 'Content-Type: application/json' \
  -d '{
    "branch": "draft",
    "message": "Reviewed portfolio after REQ-001 analysis"
  }'
```

Preview materialisation before applying it:

```bash
curl -u architect:password \
  'http://localhost:8080/api/projects/git/materialize-preview?branch=draft'
```

The preview contains the exact target HEAD, current and target fingerprints, line counts and bounded added/removed previews. Clients should repeat the preview immediately before applying and reject a changed `targetHead`.

Merge distinct branches:

```bash
curl -u architect:password \
  -X POST 'http://localhost:8080/api/projects/git/merge?source=feature-a&target=draft' \
  -H 'Content-Type: application/json' \
  -d '{"message":"Merge reviewed feature portfolio"}'
```

## Reports

```text
GET /api/projects/{projectId}/reports/{format}
```

Formats:

```text
markdown, html, docx, json, csv
```

Optional query parameters:

- `requirementId`: restrict the report to one requirement;
- `matrix`: for CSV, one of `taxonomy`, `solutions`, `products`.

Examples:

```bash
curl -u reader:password \
  -o P-001-report.docx \
  http://localhost:8080/api/projects/1/reports/docx
```

```bash
curl -u reader:password \
  -o REQ-001-report.json \
  'http://localhost:8080/api/projects/1/reports/json?requirementId=11'
```

```bash
curl -u reader:password \
  -o P-001-solution-product.csv \
  'http://localhost:8080/api/projects/1/reports/csv?matrix=products'
```

## Limits and operational configuration

| Property | Default | Purpose |
|---|---:|---|
| `taxonomy.portfolio.max-analysis-batch` | `100` | Maximum requirements in one analysis job |
| `taxonomy.portfolio.max-import-requirements` | `100` | Maximum reviewed items in one import |
| `taxonomy.portfolio.max-import-characters` | `500000` | Maximum combined reviewed text payload |
| `taxonomy.portfolio.analysis-claim-timeout-seconds` | `900` | Time before a running claim is recoverable |
| `taxonomy.portfolio.max-active-jobs-per-workspace` | implementation configuration | Bounds active job pressure per workspace |

See [Portfolio Operations](PROJECT_PORTFOLIO_OPERATIONS.md) for migration, backup, recovery and production deployment.
