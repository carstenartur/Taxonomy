# UI view and control evidence inventory

Reviewed on 25 October 2026 against baseline `main` `5d4a0aaa` and the graph/documentation repair. This inventory accounts for the 23 page templates and four shared fragments under [`taxonomy-app/src/main/resources/templates`](../../taxonomy-app/src/main/resources/templates), with the main page split into useful control groups. It is a source and evidence map, not a claim that every button, role, browser or error path has passed acceptance.

**Status:** “Browser owner” identifies an existing scenario to extend; “contract owner” identifies non-browser assertions. Neither means that the complete suite was run during this documentation edit. A screenshot shows only its captured state. Historical numbered images have not been rebound to this revision. “No capture linked” is an explicit evidence gap, not a missing implementation. See the [DE feature matrix](../de/FEATURE_MATRIX.md), [EN feature matrix](../en/FEATURE_MATRIX.md) and [capture provenance rules](../../.github/copilot-ref-screenshots.md).

## Main application

All groups below are in [`index.html`](../../taxonomy-app/src/main/resources/templates/index.html). Source IDs identify the controls; the guides explain the user action and result.

| View / control group | Explanation | Existing test owner and scope | Capture / remaining evidence |
|---|---|---|---|
| Navigation; Analyze, Architecture, Graph, Versions, DSL, Settings and Help; keyboard tab movement | User Guide [EN](../en/USER_GUIDE.md#3-understanding-the-interface) / [DE](../de/USER_GUIDE.md#3-understanding-the-interface) | [CoreUiAcceptanceIT][core] (browser); [navigation keyboard contract][navigation] | Historical [#01](../images/01-full-page-layout.png); individual transitions require assertions |
| Analyze: requirement, scope, provider/language, analyze/cancel/continue, draft save/load | User Guide [EN](../en/USER_GUIDE.md#4-analyzing-a-business-requirement) / [DE](../de/USER_GUIDE.md#4-analyzing-a-business-requirement) | [primary session workflow][session] (browser); [AnalysisContinuationUiIT][continuation] | Historical [#15](../images/15-scored-taxonomy-tree.png); provider fixtures do not establish live-model quality |
| Catalogue: six views, expand/collapse, search/similar, node rationale and Graph action | User Guide [EN](../en/USER_GUIDE.md#5-exploring-the-taxonomy) / [DE](../de/USER_GUIDE.md#5-exploring-the-taxonomy) | [TaxonomyBrowseDecisionUiIT][browse]; [diagram keyboard workflow][diagram]; [UX state recovery][ux] | Historical [#35](../images/35-scored-bp-tree-expanded.png); graph entry has a dedicated regression owner. Graph-semantic search capture #32 is retired; no current supported-workspace search capture is linked |
| Analyze: gap, pattern and recommendation panels (`gapAnalyzeBtn`, `patternDetectBtn`, `recommendBtn`) | User Guide [EN](../en/USER_GUIDE.md#11d-architecture-gap-analysis) / [DE](../de/USER_GUIDE.md#11d-architecture-gap-analysis) | [CompleteCopilotSessionIT][copilot] covers the separate persisted full-run result, not every main-page panel button | No dedicated current capture linked; #26/27 are coverage dashboards |
| Architecture: impact map selection/focus/filter, relations/proposal review, export/import and decision-report dialog | User Guide [EN](../en/USER_GUIDE.md#7-architecture-view) / [DE](../de/USER_GUIDE.md#7-architecture-view) | [proposal][proposal], [relation][relation] and [import][import] browser workflows; [UX state recovery][ux] for export cancellation and result replacement | No current in-page architecture capture: #20/38 are retired seeded illustrations. Historical [#23](../images/23-export-buttons.png) shows export controls, not successful downloads. The persisted workbench has separate current captures below |
| Graph: entry from tree or impact details; code/hops; upstream/downstream/failure/enriched/requirement queries; graph/table; APQC filter | User Guide [EN](../en/USER_GUIDE.md#8-using-the-graph-explorer) / [DE](../de/USER_GUIDE.md#8-using-the-graph-explorer) | [primary basic workflow][basic] (`verifyGraphEntry`, real server); [UX state recovery][ux] and [native-browser component fixture][graph-browser] separately use controlled API responses | [Current DE entry/empty result](../images/graph-entry-current-de.png); real upstream response, focused selected code and visible Graph page. The scenario owns the transition assertions; the image shows its final state |
| Versions/context: history, comparison, undo/restore, variants, transfer, checkpoint, sync/publish and conflict dialogs | User Guide [EN](../en/USER_GUIDE.md#12-versions-tab) / [DE](../de/USER_GUIDE.md#12-versions-tab) | [version comparison workflow][version]; [account workflow][account] includes controlled sync-response fixtures | Historical [#41](../images/41-versions-tab-history.png), [#47](../images/47-variants-browser-tab.png), [#66](../images/66-versions-timeline.png); #48–65 and #67–68 are retired presentation fixtures, not completed operations |
| DSL: load, edit, parse, validate, format, commit, branch/merge and materialize | User Guide [EN](../en/USER_GUIDE.md#11g-architecture-dsl) / [DE](../de/USER_GUIDE.md#11g-architecture-dsl) | [version comparison workflow][version]; [browser sessions workflow][sessions] checks lazy editor creation and validation transport | Historical [#34](../images/34-dsl-editor-panel.png) / [#40](../images/40-dsl-editor-with-relations.png); source/read-only/draft safety has [separate evidence](dsl-read-draft-safety.md) |
| Admin: status/logs, quality, coverage record/refresh, connection diagnostics and prompt templates | User Guide [EN](../en/USER_GUIDE.md) / [DE](../de/USER_GUIDE.md); [operations](../en/OPERATIONS_GUIDE.md) | [CoreUiAcceptanceIT][core] checks access/navigation; [admin authorization][admin-auth] checks permissions; these are not exhaustive panel-action tests | Historical [#26](../images/26-coverage-dashboard-empty.png) / [#27](../images/27-coverage-dashboard-data.png) belong to Requirement Coverage only |
| Preferences: save/reset limits, analysis, relation-search and diagram settings | Preferences [EN](../en/PREFERENCES.md) / [DE](../de/PREFERENCES.md) | [preferences workflow][preferences] (browser) | No current capture linked here; the scenario owns draft preservation and server-setting interaction |
| Help, About/notices, locale/theme, onboarding and utility menus | User Guide [EN](../en/USER_GUIDE.md) / [DE](../de/USER_GUIDE.md) | [UI acceptance driver][ui]; [content-language browser test][language] | No current capture linked for every menu/state; database onboarding has its own row below |

## Standalone pages

| Template(s) / control group | Explanation | Existing test owner and scope | Capture / remaining evidence |
|---|---|---|---|
| [`projects.html`][projects-template]: project/requirement/solution/product creation, analyses, conflicts and snapshots | Portfolio [EN](../en/PROJECT_REQUIREMENT_PORTFOLIO.md) / [DE](../de/PROJECT_REQUIREMENT_PORTFOLIO.md) | [PortfolioUiAcceptanceIT][portfolio] (browser) | No current capture linked here |
| [`requirement-detail.html`][requirement-template]: text/source, versions, analyses, decisions, Copilot start/cancel/open result and linked architecture | Copilot [EN](../en/COPILOT_AUTOPILOT.md) / [DE](../de/COPILOT_AUTOPILOT.md) | [CompleteCopilotSessionIT][copilot]; [PortfolioUiAcceptanceIT][portfolio] | Complete-run capture belongs to the persisted terminal state; do not infer it from an injected result |
| [`architecture-workbench.html`][workbench-template]: selected immutable snapshot, search/focus/fit/details, print/export and editor link | Copilot [EN](../en/COPILOT_AUTOPILOT.md#persisted-architecture-workbench) / [DE](../de/COPILOT_AUTOPILOT.md) | [ArchitectureWorkbenchUiIT][workbench] (browser + downloaded artifacts) | [DE/EN application captures](#application-captures-for-the-guides); one persisted example snapshot |
| [`architecture-editor.html`][editor-template]: selection, element/relation/package/planning drafts, preview/accept, undo/redo, checkpoint and focus lens | Editor [EN](../en/ARCHITECTURE_EDITOR.md) / [DE](../de/ARCHITECTURE_EDITOR.md) | [editor browser acceptance][editor] plus [editor service test][editor-service] | [DE/EN application captures](#application-captures-for-the-guides); server-backed editing and isolated renderer fixtures are distinct evidence |
| [`portfolio-import.html`][portfolio-import-template]: parse, candidates, saved draft, review and import | Import [EN](../en/DOCUMENT_IMPORT.md) / [DE](../de/DOCUMENT_IMPORT.md) | [PortfolioUiAcceptanceIT][portfolio] (guided import browser path) | No current capture linked here |
| [`portfolio-matrices.html`][matrices-template]: three matrices, filters, detail and filtered CSV/JSON | Portfolio [EN](../en/PROJECT_REQUIREMENT_PORTFOLIO.md) / [DE](../de/PROJECT_REQUIREMENT_PORTFOLIO.md) | [PortfolioUiAcceptanceIT][portfolio]; [PortfolioMatrixReviewIT][matrix] | No current capture linked here |
| [`portfolio-reports.html`][reports-template]: scope, preview, print and report downloads | Portfolio [EN](../en/PROJECT_REQUIREMENT_PORTFOLIO.md) / [DE](../de/PROJECT_REQUIREMENT_PORTFOLIO.md) | [PortfolioUiAcceptanceIT][portfolio]; [UX state recovery][ux] for stale preview/cancellation | No current capture linked here |
| [`portfolio-versioning.html`][versioning-template]: load state, commit, preview/apply materialization and merge | Collaboration [EN](../en/PROJECT_PORTFOLIO_GIT_COLLABORATION.md) / [DE](../de/PROJECT_PORTFOLIO_GIT_COLLABORATION.md) | [PortfolioUiAcceptanceIT][portfolio]; [PortfolioVersioningRecoveryIT][portfolio-versioning] | No current capture linked here |
| [`integrations.html`][integrations-template]: connections, read/import/export preview, review/apply, publication/reconciliation | [Integration guide EN](../features/sparx-integration.md) / [DE](../features/sparx-integration-de.md) | [integration browser acceptance][integrations] | No current capture linked here; external-tool acceptance is separate from fixture-backed browser review |
| [`document-templates.html`][templates-template], [`document-template-detail.html`][template-detail-template], [`document-template-part-comparison.html`][part-template], [`document-template-local-edit.html`][local-edit-template], [`document-template-restore.html`][restore-template]: list/upload, history/diff, local edit and restore | Templates [EN](../en/DOCUMENT_TEMPLATES.md) / [DE](../de/DOCUMENT_TEMPLATES.md) | [template download][template-download], [local edit][template-edit], [restore/part comparison][template-restore] browser owners; [HTTP integration test][template-http] | Guide has separate capture provenance; no new acceptance asserted by this inventory |
| [`backup-jobs.html`][backup-template]: scope/options, create/refresh/page, status/download | [Backup workflow](../en/OPERATIONS_GUIDE.md#backup-and-recovery) / [DE](../de/OPERATIONS_GUIDE.md) | [BackupExportBrowserIT][backup] | No current capture linked here; backup/restore support boundary remains explicit |
| [`login.html`][login-template], [`change-password.html`][password-template]: sign-in and password validation/change | Security [EN](../en/SECURITY.md) / [DE](../de/SECURITY.md) | [CoreUiAcceptanceIT][core]; [account browser workflow][account] | No current capture linked here; successful sign-in does not cover every failure/SSO path |
| [`user-management.html`][users-template], [`user-management-form.html`][user-form-template], [`user-management-status.html`][user-status-template]: list, create/edit, reset and confirmation | Security [EN](../en/SECURITY.md) / [DE](../de/SECURITY.md) | [UserManagementPageTest][users] (MVC/authorization contract) | Dedicated browser mutation evidence not linked here |
| [`browser-sessions.html`][sessions-template]: local-instance session inventory | Security [EN](../en/SECURITY.md) / [DE](../de/SECURITY.md) | [browser sessions acceptance][sessions] | No current capture linked here; local-instance scope is not an installation-wide session census |
| [`webdav-credentials.html`][credentials-template]: create/show/copy/revoke application credentials | Templates [EN](../en/DOCUMENT_TEMPLATES.md#access-and-transport) / [DE](../de/DOCUMENT_TEMPLATES.md) | [credential controllers test][credentials] (HTTP/security contract) | Dedicated browser action evidence not linked here |

## Shared fragments

| Fragment / control group | Explanation | Existing test owner and scope | Capture / remaining evidence |
|---|---|---|---|
| [`database-setup.html`][database-fragment]: optional welcome, vendor/connection/TLS, test, configuration copy/download, close/dismiss | Database setup [EN](../en/DATABASE_SETUP.md#optional-database-setup-assistant) / [DE](../de/DATABASE_SETUP.md#optionaler-datenbankassistent) | [DatabaseSetupBrowserTest][database] (real login, persistence and configuration preview) | [DE/EN application captures](#application-captures-for-the-guides); connection-entry state does not prove external database connectivity or migration |
| [`requirement-reformulation.html`][reformulation-fragment]: proposals, questions, decisions, adoption, cancellation and reports | [Clarification workflow](../features/requirement-reformulation.md); portfolio [DE](../de/PROJECT_REQUIREMENT_PORTFOLIO.md) | [ReformulationBrowserTest][reformulation]; [scenario evidence](scenario-acceptance.md) | Scenario-bound evidence; no universal provider-quality claim |
| [`content-language-controls.html`][language-fragment]: AI-response and export-document language | User Guide [EN](../en/USER_GUIDE.md) / [DE](../de/USER_GUIDE.md) | [ContentLanguageBrowserTest][language] | No current capture linked here; UI language and generated-content language are distinct |
| [`user-management-layout.html`][user-layout-fragment]: shared account navigation/layout | Security [EN](../en/SECURITY.md) / [DE](../de/SECURITY.md) | [UserManagementPageTest][users] (rendering contract) | No dedicated browser/layout capture linked here |

## Application captures for the guides

The named captures below use the running application and example data. The `*-current-*` images have their original [capture record](../images/ui-documentation-2026-10-25.json); the dedicated focus-lens images have a [separate capture record](../images/architecture-editor-focus-capture.json). These records identify the source revision, runtime details and unmodified image digests. The images illustrate the recorded state and are separate from behavioral test results and from the historical numbered images.

| View | German | English | Evidence boundary |
|---|---|---|---|
| Graph entry | [Empty upstream result](../images/graph-entry-current-de.png) | German capture used with an explicit language caption | Actual primary-catalogue response after the node action; this example has no connected elements and does not display accepted workspace proposals |
| Database assistant | [Connection step](../images/database-setup-current-de.png) | [Connection step](../images/database-setup-current-en.png) | Configuration entry; no successful external connection or migration claimed |
| Architecture editor | [Workspace](../images/architecture-editor-current-de.png) | [Workspace](../images/architecture-editor-current-en.png) | Displayed example workspace; does not certify every editing, history or recovery action |
| Architecture editor focus lens | [Brennglas einschalten](../images/architecture-editor-focus-de.png) | [Enable focus lens](../images/architecture-editor-focus-en.png) | Visible checked enable control in a current editable workspace, with the AI option unchecked; this focused capture does not cover every editor control or establish model quality |
| Architecture workbench | [Snapshot](../images/architecture-workbench-current-de.png) | [Snapshot](../images/architecture-workbench-current-en.png) | Persisted example snapshot with `MOCK` provider; no live-provider quality or cross-format export acceptance claimed |

## Follow-up boundaries

Captures #20, #37 and #38 are retired because their generator relied on unsupported catalogue-relation seeding. The [Graph query service](../../taxonomy-architecture/src/main/java/com/taxonomy/architecture/service/ArchitectureGraphQueryServiceImpl.java) and [relevance propagation](../../taxonomy-knowledge/src/main/java/com/taxonomy/catalog/service/RelevancePropagationService.java) read the [catalogue relations in `PRIMARY_SCOPE`](../../taxonomy-knowledge/src/main/java/com/taxonomy/catalog/repository/TaxonomyRelationRepository.java), while accepted workspace decisions use [`relation_decision_projection`](../../taxonomy-knowledge/src/main/java/com/taxonomy/relations/model/RelationDecisionProjection.java). The legacy [`POST /api/relations`](../../taxonomy-knowledge/src/main/java/com/taxonomy/relations/controller/RelationApiController.java) returns HTTP 410. Accepting a workspace proposal therefore does not establish an edge in a catalogue Graph query. The current workbench captures show independently persisted analysis snapshots.

Capture #32 is separately retired: graph-semantic search requires a supported relation projection for the selected workspace, which the default screenshot fixture did not establish. A fresh workspace can return `409 NOT_BUILT`; global search readiness is insufficient. The repaired Graph entry and its real upstream response do not constitute graph-semantic search acceptance.

- Retain the Graph entry regression in its existing owners. Direct toolbar tests alone do not prove that a tree/impact-map action reveals the target page before rendering.
- Obtain current real-action captures for gap/pattern/recommendation panels and retired version/conflict/sync fixtures before marking their screenshot evidence complete. #26/27 must stay mapped to Requirement Coverage.
- A named browser owner covers its recorded assertions, not every dynamic control in that view. Expand that owner for uncovered permission, empty/error, retry, stale response and concurrent-change paths rather than creating a duplicate broad framework.
- Source inspection does not establish a passed test run. Record revision, owner, environment, scenario outcome and unmodified capture digest when adding acceptance evidence.

[core]: ../../taxonomy-app/src/test/java/com/taxonomy/CoreUiAcceptanceIT.java
[navigation]: ../../taxonomy-app/src/test/java/com/taxonomy/ui/TaxonomyMainNavigationKeyboardContractTest.java
[session]: ../../.github/scripts/ui-primary-session-workflow.mjs
[continuation]: ../../taxonomy-app/src/test/java/com/taxonomy/AnalysisContinuationUiIT.java
[browse]: ../../taxonomy-app/src/test/java/com/taxonomy/TaxonomyBrowseDecisionUiIT.java
[diagram]: ../../.github/scripts/ui-diagram-keyboard-workflow.mjs
[ux]: ../../taxonomy-app/src/test/js/ux-state-recovery.cjs
[copilot]: ../../taxonomy-app/src/test/java/com/taxonomy/CompleteCopilotSessionIT.java
[proposal]: ../../.github/scripts/ui-primary-proposal-workflows.mjs
[relation]: ../../.github/scripts/ui-primary-relation-workflows.mjs
[import]: ../../.github/scripts/ui-primary-import-workflows.mjs
[version]: ../../.github/scripts/ui-primary-version-workflow.mjs
[account]: ../../.github/scripts/ui-primary-account-workflows.mjs
[sessions]: ../../.github/scripts/browser-sessions-acceptance.mjs
[admin-auth]: ../../taxonomy-app/src/test/java/com/taxonomy/security/AdminAuthorizationRegressionTest.java
[preferences]: ../../.github/scripts/ui-primary-preferences-workflow.mjs
[ui]: ../../.github/scripts/ui-acceptance.mjs
[language]: ../../taxonomy-app/src/test/java/com/taxonomy/ContentLanguageBrowserTest.java
[portfolio]: ../../taxonomy-app/src/test/java/com/taxonomy/PortfolioUiAcceptanceIT.java
[workbench]: ../../taxonomy-app/src/test/java/com/taxonomy/ArchitectureWorkbenchUiIT.java
[editor]: ../../.github/scripts/architecture-editor-acceptance.mjs
[editor-service]: ../../taxonomy-workspace/src/test/java/com/taxonomy/editor/ArchitectureEditorServiceTest.java
[matrix]: ../../taxonomy-app/src/test/java/com/taxonomy/PortfolioMatrixReviewIT.java
[portfolio-versioning]: ../../taxonomy-app/src/test/java/com/taxonomy/PortfolioVersioningRecoveryIT.java
[integrations]: ../../.github/scripts/integration-acceptance.mjs
[backup]: ../../taxonomy-app/src/test/java/com/taxonomy/BackupExportBrowserIT.java
[users]: ../../taxonomy-app/src/test/java/com/taxonomy/security/UserManagementPageTest.java
[credentials]: ../../taxonomy-app/src/test/java/com/taxonomy/security/webdav/WebDavApplicationCredentialControllersTest.java
[database]: ../../taxonomy-app/src/test/java/com/taxonomy/ui/DatabaseSetupBrowserTest.java
[reformulation]: ../../taxonomy-app/src/test/java/com/taxonomy/portfolio/reformulation/ReformulationBrowserTest.java
[template-download]: ../../.github/scripts/document-template-report-download.mjs
[template-edit]: ../../.github/scripts/document-template-local-edit-acceptance.mjs
[template-restore]: ../../.github/scripts/document-template-restore-acceptance.mjs
[template-http]: ../../taxonomy-app/src/test/java/com/taxonomy/templates/DocumentTemplateHttpIT.java
[projects-template]: ../../taxonomy-app/src/main/resources/templates/projects.html
[requirement-template]: ../../taxonomy-app/src/main/resources/templates/requirement-detail.html
[workbench-template]: ../../taxonomy-app/src/main/resources/templates/architecture-workbench.html
[editor-template]: ../../taxonomy-app/src/main/resources/templates/architecture-editor.html
[portfolio-import-template]: ../../taxonomy-app/src/main/resources/templates/portfolio-import.html
[matrices-template]: ../../taxonomy-app/src/main/resources/templates/portfolio-matrices.html
[reports-template]: ../../taxonomy-app/src/main/resources/templates/portfolio-reports.html
[versioning-template]: ../../taxonomy-app/src/main/resources/templates/portfolio-versioning.html
[integrations-template]: ../../taxonomy-app/src/main/resources/templates/integrations.html
[templates-template]: ../../taxonomy-app/src/main/resources/templates/document-templates.html
[template-detail-template]: ../../taxonomy-app/src/main/resources/templates/document-template-detail.html
[part-template]: ../../taxonomy-app/src/main/resources/templates/document-template-part-comparison.html
[local-edit-template]: ../../taxonomy-app/src/main/resources/templates/document-template-local-edit.html
[restore-template]: ../../taxonomy-app/src/main/resources/templates/document-template-restore.html
[backup-template]: ../../taxonomy-app/src/main/resources/templates/backup-jobs.html
[login-template]: ../../taxonomy-app/src/main/resources/templates/login.html
[password-template]: ../../taxonomy-app/src/main/resources/templates/change-password.html
[users-template]: ../../taxonomy-app/src/main/resources/templates/user-management.html
[user-form-template]: ../../taxonomy-app/src/main/resources/templates/user-management-form.html
[user-status-template]: ../../taxonomy-app/src/main/resources/templates/user-management-status.html
[sessions-template]: ../../taxonomy-app/src/main/resources/templates/browser-sessions.html
[credentials-template]: ../../taxonomy-app/src/main/resources/templates/webdav-credentials.html
[database-fragment]: ../../taxonomy-app/src/main/resources/templates/fragments/database-setup.html
[reformulation-fragment]: ../../taxonomy-app/src/main/resources/templates/fragments/requirement-reformulation.html
[language-fragment]: ../../taxonomy-app/src/main/resources/templates/fragments/content-language-controls.html
[user-layout-fragment]: ../../taxonomy-app/src/main/resources/templates/fragments/user-management-layout.html

[graph-browser]: ../../.github/scripts/analysis-ux.browser.test.mjs

[basic]: ../../.github/scripts/ui-primary-basic-workflows.mjs
