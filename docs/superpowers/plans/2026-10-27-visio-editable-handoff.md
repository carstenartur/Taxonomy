# Editable Visio Handoff Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Deliver editable native Visio diagrams with trustworthy visible requirement context and a safe editing link.

**Architecture:** Keep the framework-free export module responsible for native shapes,
masters, attachment and page rendering. The authorized portfolio snapshot boundary
supplies historical requirement data and an explicit export context. Reuse the
existing requirement detail route and its authorization checks.

**Tech Stack:** Java 25, Maven Wrapper, existing Visio XML/XStream/POI, Spring Boot.

**Spec:** ../specs/2026-10-27-visio-editable-handoff.md

## Global Constraints

- Preserve exact saved graph, original IDs, immutable requirement version and authorization.
- No new dependencies, workflows, model calls, Microsoft stencil redistribution or public access routes.
- Existing page/text/shape/byte limits remain; only derived OPC part counts may change.
- Native hyperlinks contain literal validated HTTP(S) URLs, never tokens or formulas.
- No desktop certification claim without a real Microsoft Visio run.

## Review Focus

- Changed source text after snapshot creation: export the original version and its author/date.
- Host/header manipulation, credential URLs and ambiguous deployment paths: never create unsafe links.
- Missing public base or historical metadata: preserve honest visible absence without a broken link.
- Long Unicode requirement text and explanations: preserve all text within independent page budgets.
- Containers, parallel/reverse edges and save semantics: preserve identities and explicit endpoint dependencies.

## Task 1: Native attached objects (export renderer owner)

**Files:** VisioPageContentsConverter, VisioPackageBuilder, VisioOpcValidator,
VisioPackageValidator, VisioDiagramService, VisioPresentation, VisioHandoffProfile,
native master/connection helpers and the existing Visio contract/POI tests.

**Interfaces:** Consume existing VisioShape/VisioConnect properties and
`taxonomy.requirementUrl`; produce numeric embedded master references, explicit
connection-point dependencies, native container membership and literal hyperlinks.
Root owns the new VisioContextPages helper; call `VisioContextPages.append(doc)`
after mandatory evidence pages and before optional relationship details, then
`VisioContextPages.decorateDiagrams(doc)` after optional details. Root supplies
these helpers; this preserves trailing optional-page trimming at the byte limit.

- [x] Extend attachment tests to fail on fixed endpoint cells, invalid formula/Connect mapping and absent boundary points.
- [x] Implement documented point glue, suitable original masters and explicit container membership.
- [x] Validate all added OPC/master/connection references and unsafe hyperlink rejection.
- [x] Run export contracts and independent POI/schema loading; regenerate the intentional fixture only after integration.

## Task 2: Authorized requirement metadata and URL (portfolio owner)

**Files:** ArchitectureWorkbenchDtos/Service, VisioSnapshotMetadata,
ArchitectureSnapshotExportService, a small Visio export-context/link service,
application.properties/.env.example, and portfolio workbench/security tests.

**Interfaces:** Extend SnapshotProvenance compatibly with historical version author/date.
Supply document properties `taxonomy.requirementText`, `requirementKey`,
`requirementTitle`, `requirementVersionId`, `requirementVersionCreatedBy`,
`requirementVersionCreatedAt`, `generatorName`, `generatorVersion`, `exportedBy`,
`exportedAt`, `requirementUrl`, `requirementUrlStatus`, alongside existing snapshot,
project, workspace and branch properties. Optional values remain absent with a loss.
Use an explicit export context and fixed Clock in deterministic tests.

- [x] Add failing assertions for complete frozen text/author/date and current generator/export metadata.
- [x] Add trusted-base URL tests for valid context paths and rejected unsafe URI forms; retain exact scope-denial tests.
- [x] Implement only the allowlisted metadata/context integration; preserve existing route access controls.
- [x] Update the intentionally obsolete requirement-text omission assertion while retaining excluded-payload checks.
- [x] Run affected portfolio and endpoint tests; report exact test selections.

## Task 3: Visible requirement context and integration (root)

**Files:** New VisioContextPages and its tests, existing export/profile documentation,
fixture and checksums, targeted integration tests as needed.

**Interfaces:** Consume Task 2 document properties. Add presentation-only native
text shapes to existing diagram pages and full text context pages using the
existing evidence page budget. Their hyperlinks use Task 1 serialization.

- [x] Add failing tests for visible metadata, safe native link, full Unicode text pagination and missing metadata.
- [x] Implement compact editable context blocks and complete paginated source text with explicit continuation references.
- [x] Integrate the helper, verify package limits and update profile/DE/EN help.
- [x] Run export and portfolio suites, inspect a rendered representative artifact, and obtain independent review.
- [ ] Run the canonical Maven command when possible; publish one PR based on main with truthful validation limits.

## Task 4: Existing working-view export adapters

**Files:** ExportApiController, VisioExportExtension, ExportFacade,
RequirementArchitectureView/Service, shared export JavaScript and focused tests.

**Interfaces:** Keep server-owned generator/user/time in the core application's
VisioWorkingViewMetadata helper, with an explicit no-saved-requirement link status.
The unconditional adapter must not depend on optional portfolio/reporting services. An optional
`originalRequirementText` records the captured working source without asserting
historical authority. Never derive it from an independently edited textarea.

- [x] Add failing adapter assertions for original text, server-owned creation context and ignored spoofed URL/author options.
- [x] Retain source text when the working view is built; client export may supplement the captured last-analyzed text.
- [x] Integrate the existing three Visio routes without new analysis on current-view export.
- [x] Run affected app adapters/JavaScript tests and preserve absent-text honesty.

## Recorded implementation and verification

Completed native masters and fixed-side point attachment, reciprocal container membership,
visible source context on diagram pages and complete mandatory requirement pages,
historical saved-version author/date, actual generator/export actor/time, and trusted
requirement editing links. All four real analysis terminal assembly paths retain exact
original text, including partial, stopped and cancelled results. Accepted format aliases
use the resolved format descriptor. Independent source reviews closed all findings.

The following Maven-owned checks passed during implementation on 2026-10-27:

| Selection | Cases | Result |
| --- | ---: | --- |
| Saved snapshot/workbench/context and endpoint selection | 43 | Passed |
| Integrated export/source/analysis/tenant/workspace selection | 219 | Passed |
| Complete taxonomy-export suite | 568 | Passed |
| Existing current-diagram JavaScript contract owner | 56 | Passed |
| Export documentation contracts | 3 | Passed |
| Mandatory taxonomy-build module and selector boundaries | 224 | Passed |

Selections overlap and must not be summed into a unique test total. The 219-case
selection and historical command are retained in the ignored task report. The complete
export run uses `./mvnw -B -pl taxonomy-export -am test`. JavaScript/doc contracts use
`JavascriptContractTest,ArchitectureExportDocumentationContractTest` with
`-Dtaxonomy.javascript.sources=taxonomy-app/src/test/js/current-diagram-export.cjs`;
the mandatory module boundary checks remain enabled.

The canonical `./mvnw -B verify -Pci -DrunOnnxTests=true` attempt was blocked before
its tests by the pinned embedding-model download: the environment's network proxy
was unreachable. Repeated retries were stopped; this is not a passing full CI result.
Microsoft Visio desktop open/edit/move/resize/reroute/save/reopen acceptance remains
pending. POI and LibreOffice visual inspection are supplementary evidence only.

### Recovery before publication

The execution environment lost the local checkout before publication. The implementation
was restored from the retained edit payloads and original Git-blob manifest. All changed
production code and executable profile files match their previously tested bytes.
Remaining reconstructed material is documentation and a converter-test method/assertion
whose native connector-master behavior is rechecked by the complete export suite.
The synthetic fixture is regenerated and compared with the previously recorded checksums.
Publication and its exact resulting tree are recorded by the pull request; no merge or
release is part of this implementation task.
