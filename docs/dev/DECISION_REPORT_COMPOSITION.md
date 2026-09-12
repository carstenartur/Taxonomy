# Decision-report composition ownership

This is the bounded D1 slice of issue #1043 under module extraction issue #628.
`DecisionRationaleReportController` belongs to
`com.taxonomy.composition.report` because each request combines three owners:

| Owner | Responsibility used by the controller |
|---|---|
| Architecture | Decision-report generation, score explanations and report rendering |
| Knowledge | The catalog fingerprint tree used to derive authoritative score semantics |
| Workspace | Current account/workspace, repository branch and version provenance |

The controller remains in `taxonomy-app`, inside the existing application
composition context. The package move does not change the context map, the
exception ledger, persistence ownership or any service implementation.

## Preserved HTTP and Java contracts

The existing routes remain:

| Method | Route |
|---|---|
| GET | `/api/decision-report/formats` |
| POST | `/api/decision-report/docx` |
| POST | `/api/decision-report/html` |
| POST | `/api/decision-report/json` |

The controller source changes only its package declaration. All endpoint and
OpenAPI annotations, request-record fields and constructors, controller
constructors, validation limits, raw-score derivation, locale handling, trusted
workspace provenance, renderer selection, response headers and error behavior
remain intact. The application's existing endpoint security policy still applies.

The two focused controller test classes move to the same composition package;
their only edits are the package declaration and nested request-record import.
`DecisionRationaleReportTests` continues to exercise the same HTTP routes through
the Spring application context.

## Executable ownership and coverage guards

`ArchitectureDecisionReportBoundaryTest` forbids every versioning HTTP adapter
from depending on architecture decision/report or catalog service packages. It
also requires the report controller to reside in `composition.report`. Both rules
fail on an empty class selection, so deleting or omitting the controller cannot
make the ownership check pass.

`.github/critical-coverage-policy.json` retains the existing `versioning/` changed
source prefix and the `versioning.controller` package floors. The new
`composition/report/` prefix receives changed-source checking, and the new package
inherits the same **0.81 line / 0.58 branch** floors. Global changed-source floors
and the empty exception list remain unchanged.

The clean D1 checkout removes all three report-related versioning-controller
package edges: architecture decision (6 class pairs), architecture report (1),
and catalog service (1). The same report/knowledge dependencies are now owned by
composition, which additionally exposes the three previously internal workspace
API references. Total class pairs change from 559 to 562; this is an ownership
transfer, with no additional runtime dependency. The measured replacement is
recorded in `.github/architecture-dependency-baseline.json`.

## Verification checkpoint

The new ownership rule failed against the original package with 24 report-related
dependency violations. After relocation, the ownership test and all nine moved
validation/score-semantics tests passed under Java 21/JUnit using a fresh, isolated
class output directory. A source comparison confirmed the controller has no
changes beyond its package and the tests have no changes beyond package/imports.

The clean Maven boundary run executes 21 tests: 20 pass, including all nine
Spring-backed report endpoint cases, and the sole initial failure prints the
reviewed dependency-baseline replacement. The strict cycle rule passes without
adding exceptions. After accepting that measured replacement, the fresh-checkout
`./mvnw verify -DexcludedGroups=real-llm` run passed, including all 3,028 application
tests, the strict dependency ratchet and the cycle rule. The pinned ONNX model was
available; no application tests were skipped. Report-controller coverage is
97.63% lines / 75.84% branches, above the inherited package and changed-source
floors; the remaining versioning controllers also retain their package floors.

This default local command skips application integration tests and the
post-reactor quality gate. The authoritative CI profile, database, security,
product and UI lanes still have to pass on the published head before integration.
