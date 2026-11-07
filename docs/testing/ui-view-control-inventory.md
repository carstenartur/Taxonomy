# UI view and control evidence inventory

Capture ownership updated on 6 November 2026 for the shared command menus and the single Maven screenshot profile. The original surface review was on 25 October against baseline `main` `5d4a0aaa`. This inventory maps the 23 page templates and the shared control groups under [`taxonomy-app/src/main/resources/templates`](../../taxonomy-app/src/main/resources/templates), with the main page split into useful control groups. It does not claim that every button, role, browser or error path has passed acceptance.

**Status:** “Browser owner” identifies an existing scenario to extend; “contract owner” identifies non-browser assertions. Neither means that the complete suite was run during this documentation edit. The capture recipes below are maintained; existing PNG freshness is established only by a matching actual capture manifest. A `current` filename alone does not establish freshness, and a screenshot shows only its captured state. “No capture linked” is an explicit evidence gap, not a missing implementation. See the [DE feature matrix](../de/FEATURE_MATRIX.md), [EN feature matrix](../en/FEATURE_MATRIX.md) and [capture provenance rules](../../.github/copilot-ref-screenshots.md).

Regenerate every retained DE/EN guide image through the existing JUnit/Failsafe entry point:

```bash
./mvnw -B verify -Pscreenshots
```

The profile activates the three application capture owners, [document-template report owner][template-capture] and final [documentation capture owner][capture-gate]. Maven provisions Node/Chromium; Java 25, Docker and document renderers remain prerequisites. Output defaults to `target/generated-screenshots/` through `taxonomy.docs.image.dir`, with an absolute `TAXONOMY_DOCS_IMAGE_DIR` override supported. The final owner runs the existing decision-export component driver, verifies every PNG referenced by the localized guides and every output PNG against this run's timestamp, PNG readability and dimensions, and writes `documentation-screenshots.json` with Git HEAD commit/tree, working-tree change flag and image SHA-256 hashes. Alphabetical Failsafe order places it after the document-template report owner; application captures run earlier in the reactor. The [7 November review](../qa/2026-11-07-documentation-screenshots.md) records the actual retained artifact and its verification; this does not imply that every browser owner in the inventory ran.

## Main application

The main page is [`index.html`](../../taxonomy-app/src/main/resources/templates/index.html); shared commands and work areas occupy one compact toolbar from [`application-chrome.html`][chrome-fragment], with system status and context below it. The compact score legend stays visible below the analysis controls; captures #10 and #17 include all five swatches. Source IDs identify the controls; the guides explain the user action and result.

| View / control group | Explanation | Existing test owner and scope | Capture / remaining evidence |
|---|---|---|---|
| Commands (File/Edit/View/Tools/Help); work navigation (Projects/Requirement/Architecture); keyboard and touch | User Guide [EN](../en/USER_GUIDE.md#3-understanding-the-interface) / [DE](../de/USER_GUIDE.md#3-understanding-the-interface) | [CoreUiAcceptanceIT][core] (browser); [native navigation checks][navigation]; [general capture owner][generator] | Refreshed [#01](../images/01-full-page-layout.png) and [Tools menu](../images/14-navbar-settings.png), bound to the common manifest; [earlier command verification](../qa/2026-11-05-workbench-command-structure.md) remains a separate record |
| Analyze: requirement, scope, provider/language, analyze/cancel/continue, draft save/load | User Guide [EN](../en/USER_GUIDE.md#4-analyzing-a-business-requirement) / [DE](../de/USER_GUIDE.md#4-analyzing-a-business-requirement) | [primary session workflow][session] (browser); [AnalysisContinuationUiIT][continuation]; [general capture owner][generator] | Retained [#15](../images/15-scored-taxonomy-tree.png); product-demo root clarification has a maintained capture recipe below. Provider fixtures do not establish live-model quality |
| Catalogue: six views, expand/collapse, search/similar, node rationale and Graph action | User Guide [EN](../en/USER_GUIDE.md#5-exploring-the-taxonomy) / [DE](../de/USER_GUIDE.md#5-exploring-the-taxonomy) | [TaxonomyBrowseDecisionUiIT][browse]; [diagram keyboard workflow][diagram]; [UX state recovery][ux]; [general capture owner][generator] | Retained [#35](../images/35-scored-bp-tree-expanded.png); diagram-control crops and Graph entry have maintained capture recipes below. Graph-semantic search capture #32 is retired; no supported-workspace search capture is linked |
| Analyze: gap, pattern and recommendation panels (`gapAnalyzeBtn`, `patternDetectBtn`, `recommendBtn`) | User Guide [EN](../en/USER_GUIDE.md#11d-architecture-gap-analysis) / [DE](../de/USER_GUIDE.md#11d-architecture-gap-analysis) | [CompleteCopilotSessionIT][copilot] covers the separate persisted full-run result, not every main-page panel button | No dedicated current capture linked; #26/27 are coverage dashboards |
| Architecture: impact map selection/focus/filter, relations/proposal review, export/import and decision-report dialog | User Guide [EN](../en/USER_GUIDE.md#7-architecture-view) / [DE](../de/USER_GUIDE.md#7-architecture-view) | [proposal][proposal], [relation][relation] and [import][import] browser workflows; [UX state recovery][ux] for export cancellation and result replacement; [dialog capture owner][capture-gate] | No supported in-page architecture capture: #20/38 are retired seeded illustrations and have been removed from active guides. Retained [#23](../images/23-export-buttons.png) shows controls, not successful downloads. Workbench and decision-dialog capture recipes appear below |
| Graph: entry from tree or impact details; code/hops; upstream/downstream/failure/enriched/requirement queries; graph/table; APQC filter | User Guide [EN](../en/USER_GUIDE.md#8-using-the-graph-explorer) / [DE](../de/USER_GUIDE.md#8-using-the-graph-explorer) | [primary basic workflow][basic] (`verifyGraphEntry`, real server); [general capture owner][generator]; [UX state recovery][ux] and [native-browser component fixture][graph-browser] separately use controlled API responses | [Retained DE entry/empty result](../images/graph-entry-current-de.png); the capture owner opens Graph through the node action, verifies the selected code/focus and retains the actual upstream response |
| Versions/context: history, comparison, undo/restore, variants, transfer, checkpoint, sync/publish and conflict dialogs | User Guide [EN](../en/USER_GUIDE.md#12-versions-tab) / [DE](../de/USER_GUIDE.md#12-versions-tab) | [version comparison workflow][version]; [account workflow][account] includes controlled sync-response fixtures; [general capture owner][generator] for genuine history/variant/cherry-pick illustrations | Retained [#41](../images/41-versions-tab-history.png), [#47](../images/47-variants-browser-tab.png), [#66](../images/66-versions-timeline.png); retired presentation fixtures #48–65 and #67–68 have been removed from active guides |
| DSL: load, edit, parse, validate, format, commit, branch/merge and materialize | User Guide [EN](../en/USER_GUIDE.md#11g-architecture-dsl) / [DE](../de/USER_GUIDE.md#11g-architecture-dsl) | [version comparison workflow][version]; [browser sessions workflow][sessions] checks lazy editor creation and validation transport; [general capture owner][generator] | Retained [#34](../images/34-dsl-editor-panel.png) / [#40](../images/40-dsl-editor-with-relations.png); source/read-only/draft safety has [separate evidence](dsl-read-draft-safety.md) |
| Admin: status/logs, quality, coverage record/refresh, connection diagnostics and prompt templates | User Guide [EN](../en/USER_GUIDE.md) / [DE](../de/USER_GUIDE.md); [operations](../en/OPERATIONS_GUIDE.md) | [CoreUiAcceptanceIT][core] checks access/navigation; [admin authorization][admin-auth] checks permissions; [general capture owner][generator] supplies selected illustrations, not exhaustive panel-action tests | Retained [#26](../images/26-coverage-dashboard-empty.png) / [#27](../images/27-coverage-dashboard-data.png) belong to Requirement Coverage only |
| Preferences: save/reset limits, analysis, relation-search and diagram settings | Preferences [EN](../en/PREFERENCES.md) / [DE](../de/PREFERENCES.md) | [preferences workflow][preferences] (browser) | No current capture linked here; the scenario owns draft preservation and server-setting interaction |
| Help, About/notices, locale/theme, onboarding and utility menus | User Guide [EN](../en/USER_GUIDE.md) / [DE](../de/USER_GUIDE.md) | [UI acceptance driver][ui]; [content-language browser test][language] | No current capture linked for every menu/state; database onboarding has its own row below |

## Standalone pages

| Template(s) / control group | Explanation | Existing test owner and scope | Capture / remaining evidence |
|---|---|---|---|
| [`projects.html`][projects-template]: project/requirement/solution/product creation, analyses, conflicts and snapshots | Portfolio [EN](../en/PROJECT_REQUIREMENT_PORTFOLIO.md) / [DE](../de/PROJECT_REQUIREMENT_PORTFOLIO.md) | [PortfolioUiAcceptanceIT][portfolio] (browser) | No current capture linked here |
| [`requirement-detail.html`][requirement-template]: text/source, versions, analyses, decisions, Copilot start/cancel/open result and linked architecture | Copilot [EN](../en/COPILOT_AUTOPILOT.md) / [DE](../de/COPILOT_AUTOPILOT.md) | [CompleteCopilotSessionIT][copilot]; [PortfolioUiAcceptanceIT][portfolio] | Complete-run capture belongs to the persisted terminal state; do not infer it from an injected result |
| [`architecture-workbench.html`][workbench-template]: selected immutable snapshot, search/focus/fit/details, print/export and editor link | Copilot [EN](../en/COPILOT_AUTOPILOT.md#persisted-architecture-workbench) / [DE](../de/COPILOT_AUTOPILOT.md) | [ArchitectureWorkbenchUiIT][workbench] (browser + downloaded artifacts) | [DE/EN application captures](#application-captures-for-the-guides); one persisted example snapshot |
| [`architecture-editor.html`][editor-template]: selection, element/relation/package/planning drafts, preview/accept, undo/redo, checkpoint and focus lens | Editor [EN](../en/ARCHITECTURE_EDITOR.md) / [DE](../de/ARCHITECTURE_EDITOR.md) | [editor browser acceptance][editor] plus [editor service test][editor-service]; [workbench capture owner][workbench] for guide illustrations | [DE/EN application captures](#application-captures-for-the-guides); server-backed editing and isolated renderer fixtures are distinct evidence |
| [`portfolio-import.html`][portfolio-import-template]: parse, candidates, saved draft, review and import | Import [EN](../en/DOCUMENT_IMPORT.md) / [DE](../de/DOCUMENT_IMPORT.md) | [PortfolioUiAcceptanceIT][portfolio] (guided import browser path) | No current capture linked here |
| [`portfolio-matrices.html`][matrices-template]: three matrices, filters, detail and filtered CSV/JSON | Portfolio [EN](../en/PROJECT_REQUIREMENT_PORTFOLIO.md) / [DE](../de/PROJECT_REQUIREMENT_PORTFOLIO.md) | [PortfolioUiAcceptanceIT][portfolio]; [PortfolioMatrixReviewIT][matrix] | No current capture linked here |
| [`portfolio-reports.html`][reports-template]: scope, preview, print and report downloads | Portfolio [EN](../en/PROJECT_REQUIREMENT_PORTFOLIO.md) / [DE](../de/PROJECT_REQUIREMENT_PORTFOLIO.md) | [PortfolioUiAcceptanceIT][portfolio]; [UX state recovery][ux] for stale preview/cancellation | No current capture linked here |
| [`portfolio-versioning.html`][versioning-template]: load state, commit, preview/apply materialization and merge | Collaboration [EN](../en/PROJECT_PORTFOLIO_GIT_COLLABORATION.md) / [DE](../de/PROJECT_PORTFOLIO_GIT_COLLABORATION.md) | [PortfolioUiAcceptanceIT][portfolio]; [PortfolioVersioningRecoveryIT][portfolio-versioning] | No current capture linked here |
| [`integrations.html`][integrations-template]: connections, read/import/export preview, review/apply, publication/reconciliation | [Integration guide EN](../features/sparx-integration.md) / [DE](../features/sparx-integration-de.md) | [integration browser acceptance][integrations] | No current capture linked here; external-tool acceptance is separate from fixture-backed browser review |
| [`document-templates.html`][templates-template], [`document-template-detail.html`][template-detail-template], [`document-template-part-comparison.html`][part-template], [`document-template-local-edit.html`][local-edit-template], [`document-template-restore.html`][restore-template]: list/upload, history/diff, local edit and restore | Templates [EN](../en/DOCUMENT_TEMPLATES.md) / [DE](../de/DOCUMENT_TEMPLATES.md) | [JUnit report/capture owner][template-capture] invokes [template download][template-download]; [local edit][template-edit], [restore/part comparison][template-restore] and [HTTP integration test][template-http] retain their scopes | Management-page and downloaded-report captures run in the screenshot profile; no new acceptance is asserted by this inventory |
| [`backup-jobs.html`][backup-template]: scope/options, create/refresh/page, status/download | [Backup workflow](../en/OPERATIONS_GUIDE.md#backup-and-recovery) / [DE](../de/OPERATIONS_GUIDE.md) | [BackupExportBrowserIT][backup] | No current capture linked here; backup/restore support boundary remains explicit |
| [`login.html`][login-template], [`change-password.html`][password-template]: sign-in and password validation/change | Security [EN](../en/SECURITY.md) / [DE](../de/SECURITY.md) | [DE/EN anonymous-login checks][local-login] inside [existing UI scenarios][ui], including `login-context-chromium` without JavaScript under `/taxonomy`; [anonymous-page axe audit][a11y]; [account browser workflow][account] | Keyboard labels/focus/skip link, generic failure, saved-request login, locale persistence and logout are asserted; no current capture linked here and SSO has separate coverage |
| [`user-management.html`][users-template], [`user-management-form.html`][user-form-template], [`user-management-status.html`][user-status-template]: list, create/edit, reset and confirmation | Security [EN](../en/SECURITY.md) / [DE](../de/SECURITY.md) | [UserManagementPageTest][users] (MVC/authorization contract) | Dedicated browser mutation evidence not linked here |
| [`browser-sessions.html`][sessions-template]: local-instance session inventory | Security [EN](../en/SECURITY.md) / [DE](../de/SECURITY.md) | [browser sessions acceptance][sessions] | No current capture linked here; local-instance scope is not an installation-wide session census |
| [`webdav-credentials.html`][credentials-template]: create/show/copy/revoke application credentials | Templates [EN](../en/DOCUMENT_TEMPLATES.md#access-and-transport) / [DE](../de/DOCUMENT_TEMPLATES.md) | [credential controllers test][credentials] (HTTP/security contract) | Dedicated browser action evidence not linked here |

## Shared fragments

| Fragment / control group | Explanation | Existing test owner and scope | Capture / remaining evidence |
|---|---|---|---|
| [`application-chrome.html`][chrome-fragment]: command menus, work areas, system status and selected-context disclosure | User Guide [EN](../en/USER_GUIDE.md#3-understanding-the-interface) / [DE](../de/USER_GUIDE.md#3-understanding-the-interface) | [native navigation checks][navigation]; [CoreUiAcceptanceIT][core]; application capture owners render the shared header | `#applicationFile`, `#applicationEdit`, `#applicationView`, `#applicationTools`, `#applicationHelp`; `#applicationWorkspaces`, `#applicationSystemStatus`, `#applicationContextDisclosure`. No separate Project, Settings or Status command menu |
| [`database-setup.html`][database-fragment]: optional welcome, vendor/connection/TLS, test, configuration copy/download, close/dismiss | Database setup [EN](../en/DATABASE_SETUP.md#optional-database-setup-assistant) / [DE](../de/DATABASE_SETUP.md#optionaler-datenbankassistent) | [DatabaseSetupBrowserTest][database] (real login, persistence and configuration preview); [general capture owner][generator] for guide illustrations | [DE/EN application captures](#application-captures-for-the-guides); connection-entry state does not prove external database connectivity or migration |
| [`requirement-reformulation.html`][reformulation-fragment]: proposals, questions, decisions, adoption, cancellation and reports | [Clarification workflow](../features/requirement-reformulation.md); portfolio [DE](../de/PROJECT_REQUIREMENT_PORTFOLIO.md) | [ReformulationBrowserTest][reformulation]; [scenario evidence](scenario-acceptance.md) | Scenario-bound evidence; no universal provider-quality claim |
| [`content-language-controls.html`][language-fragment]: AI-response and export-document language | User Guide [EN](../en/USER_GUIDE.md) / [DE](../de/USER_GUIDE.md) | [ContentLanguageBrowserTest][language] | No current capture linked here; UI language and generated-content language are distinct |
| [`user-management-layout.html`][user-layout-fragment]: shared account navigation/layout | Security [EN](../en/SECURITY.md) / [DE](../de/SECURITY.md) | [UserManagementPageTest][users] (rendering contract) | No dedicated browser/layout capture linked here |

## Application captures for the guides

The refreshed guide captures have the actual [Maven manifest](../images/documentation-screenshots.json), including the source commit/tree, dimensions and hashes, and a [visual review](../qa/2026-11-07-documentation-screenshots.md). The owner column identifies each maintained reproduction recipe and its evidence boundary. The original [October 25 application record](../images/ui-documentation-2026-10-25.json), [focus-lens record](../images/architecture-editor-focus-capture.json), [diagram-control record](../images/diagram-print-controls-2026-10-27.json) and [root-clarification record](../images/root-relevance-clarification-capture.json) remain unchanged historical records. They do not bind the replacement images to the old runs.

| View | German | English | Capture owner and evidence boundary |
|---|---|---|---|
| Graph entry | [Upstream result](../images/graph-entry-current-de.png) | German capture used with an explicit language caption | [ScreenshotGeneratorIT][generator]: actual primary-catalogue response after the node action, including an empty result; does not demonstrate accepted workspace proposals in catalogue Graph |
| Database assistant | [Connection step](../images/database-setup-current-de.png) | [Connection step](../images/database-setup-current-en.png) | [ScreenshotGeneratorIT][generator]: configuration entry; no successful external connection or migration claimed |
| Architecture editor | [Workspace](../images/architecture-editor-current-de.png) | [Workspace](../images/architecture-editor-current-en.png) | [ArchitectureWorkbenchUiIT][workbench]: two elements created through real preview/accept in an editable example workspace; does not certify every editing, history or recovery action |
| Architecture editor focus lens | [Lupe einschalten](../images/architecture-editor-focus-de.png) | [Enable focus lens](../images/architecture-editor-focus-en.png) | [ArchitectureWorkbenchUiIT][workbench]: checked enable control with the AI option unchecked; does not establish model quality |
| Architecture workbench | [Snapshot](../images/architecture-workbench-current-de.png) | [Snapshot](../images/architecture-workbench-current-en.png) | [ArchitectureWorkbenchUiIT][workbench]: persisted example snapshot with deterministic mock scores; export checks belong to the owner's assertions, not the image alone |
| Complete Copilot result | [Selected snapshot](../images/72-complete-copilot-run-result.png) | Same English application capture used in both guides | [CompleteCopilotSessionIT][copilot]: real persisted mock run; capture requires the snapshot heading, result overview and report action to fit the viewport and remain unobscured |
| Diagram print controls | [Controls](../images/diagram-print-controls-de.png) | [Controls](../images/diagram-print-controls-en.png) | [ScreenshotGeneratorIT][generator]: actual mock analysis in Decision view, positive filter, per-taxonomy print layout and A0; earlier recorded crops used a component fixture |
| Root clarification | [R0 clarification](../images/root-relevance-clarification-de.png) | [R0 clarification](../images/root-relevance-clarification-en.png) | [ScreenshotGeneratorIT][generator]: persisted authored product-demo R0, unchanged evidence exported/imported through production routes; unresolved CO has no numeric score and retained sibling scores remain visible |
| Decision-report dialog | [Basic](../images/decision-export-dialog-de.png), [options](../images/decision-export-options-de.png), [mobile](../images/decision-export-mobile-de.png) | [Basic](../images/decision-export-dialog-en.png), [options](../images/decision-export-options-en.png), [mobile](../images/decision-export-mobile-en.png) | [ScreenshotDocumentationIT][capture-gate] invokes [existing component contract][decision-dialog]: production dialog with labelled example input; not a persisted application session |
| Document templates and report | [Management](../images/document-template-management.png), [downloaded report](../images/decision-rationale-template-test-report.png) | Same retained illustrations used in both guides | [DocumentTemplateReportDownloadIT][template-capture]: real management page and rendering of actually downloaded DOCX; native Microsoft Word/WebDAV acceptance remains separate |

## Follow-up boundaries

Captures #20, #37 and #38 are retired because their generator relied on unsupported catalogue-relation seeding. The [Graph query service](../../taxonomy-architecture/src/main/java/com/taxonomy/architecture/service/ArchitectureGraphQueryServiceImpl.java) and [relevance propagation](../../taxonomy-knowledge/src/main/java/com/taxonomy/catalog/service/RelevancePropagationService.java) read the [catalogue relations in `PRIMARY_SCOPE`](../../taxonomy-knowledge/src/main/java/com/taxonomy/catalog/repository/TaxonomyRelationRepository.java), while accepted workspace decisions use [`relation_decision_projection`](../../taxonomy-knowledge/src/main/java/com/taxonomy/relations/model/RelationDecisionProjection.java). The legacy [`POST /api/relations`](../../taxonomy-knowledge/src/main/java/com/taxonomy/relations/controller/RelationApiController.java) returns HTTP 410. Accepting a workspace proposal therefore does not establish an edge in a catalogue Graph query. The workbench owner captures an independently persisted analysis snapshot.

Capture #32 is separately retired: graph-semantic search requires a supported relation projection for the selected workspace, which the default screenshot fixture did not establish. A fresh workspace can return `409 NOT_BUILT`; global search readiness is insufficient. The repaired Graph entry and its real upstream response do not constitute graph-semantic search acceptance.

- Retain the Graph entry regression in its existing owners. Direct toolbar tests alone do not prove that a tree/impact-map action reveals the target page before rendering.
- Obtain current real-action captures for gap/pattern/recommendation panels and retired version/conflict/sync fixtures before marking their screenshot evidence complete. #26/27 must stay mapped to Requirement Coverage.
- A named browser owner covers its recorded assertions, not every dynamic control in that view. Expand that owner for uncovered permission, empty/error, retry, stale response and concurrent-change paths rather than creating a duplicate broad framework.
- Source inspection does not establish a passed test run. Record revision, owner, environment, scenario outcome and unmodified capture digest when adding acceptance evidence.

[generator]: ../../taxonomy-app/src/test/java/com/taxonomy/ScreenshotGeneratorIT.java
[template-capture]: ../../taxonomy-build/src/test/java/com/taxonomy/build/DocumentTemplateReportDownloadIT.java
[capture-gate]: ../../taxonomy-build/src/test/java/com/taxonomy/build/ScreenshotDocumentationIT.java
[decision-dialog]: ../../.github/scripts/decision-export-browser.mjs
[chrome-fragment]: ../../taxonomy-app/src/main/resources/templates/fragments/application-chrome.html

[core]: ../../taxonomy-app/src/test/java/com/taxonomy/CoreUiAcceptanceIT.java
[navigation]: ../../.github/scripts/analysis-navigation.browser.test.mjs
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
[local-login]: ../../.github/scripts/ui-local-login.mjs
[a11y]: ../../.github/scripts/accessibility-audit.mjs
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
