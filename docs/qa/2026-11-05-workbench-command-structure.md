# Workbench command structure — 5 November 2026

## Result

The header now has a conventional command row and separate navigation between work objects.

| Surface | Purpose |
|---|---|
| File / Edit / View / Tools / Help | Commands and architecture views, using one native menu controller and shared styling |
| Projects / Requirement / Architecture | Existing work areas; directly visible on phones as well |
| Contextual toolbar and requirement actions | Operations on the displayed requirement or architecture |
| System status and inline analysis status | Visible readiness/warnings and recovery without opening a command menu |

Version history and model source are under View. Graph Explorer, integrations,
appearance, language and authorized administration are under Tools. The
requirement detail page highlights Requirement and keeps Matrices with its own
actions instead of allocating another toolbar/title row.

Menu adapters invoke the existing feature controls and observe their disabled,
hidden and pressed state. Business operations, permission checks, source scope
and draft decisions remain owned by those features. Switching work areas must
respect the architecture editor's leave decision; rejected New Analysis cannot
reset the requirement draft.

## Removed paths

- Retired `mainNavTabs`, its parallel ARIA/keyboard controller, old scroll-tab styles and duplicate page-click dispatch.
- Removed the page-specific theme handler; shared appearance now applies to standalone pages too.
- Moved the old Settings/Status paths to their single intended homes and removed duplicate API bootstrap tags from page templates.
- Deleted the obsolete source-text keyboard test and structural assertions that demanded the old tablist. Retained navigation, role, recovery, context and draft behavior checks in native/browser tests.

## Verification

- Java 25 and the Maven Wrapper compile the application reactor.
- The complete Maven-owned JavaScript contract lane passes 1,936 cases.
- The focused Maven-owned native browser lane passes 92 cases across navigation,
  standalone chrome and database setup. It covers both languages, 320–1366 px,
  command availability and pressed state, keyboard traversal, mobile dialog
  return focus and unavailable local-management routes with retained context.
- 187 targeted Java/Thymeleaf/Selenium cases pass across shared navigation, document/admin
  pages, the actual snapshot export control, architecture source transitions,
  draft protection, mounted portfolio routing and the database assistant with a
  real application server. The final per-owner reports are recorded below.
- Independent review found three reproducible defects: dark selected-work-area
  contrast on standalone pages, lost scope/language on the disabled local-user
  management explanation, and mobile About/database-dialog focus return. All
  were reproduced, corrected and independently rechecked (12 focused native
  cases passed).

Browser fixture responses are controlled test data. They establish UI behavior,
not live authentication, external provider reachability or architecture quality.
The snapshots below use production Thymeleaf, controllers, translations and CSS,
with one fixture taxonomy root and an explicitly unavailable AI provider. The
headless capture environment does not contain all decorative emoji fonts.

## Inspected captures

Desktop command row, work navigation and closed system/context detail:

![Desktop commands and work areas](images/workbench-commands-desktop.png)

Mobile work areas remain directly accessible while commands are collapsed:

![Mobile work areas](images/workbench-workareas-mobile.png)

The same command categories and authorized Tools entries on a phone:

![Mobile Tools menu](images/workbench-tools-mobile.png)

View commands and the active catalogue view in dark appearance:

![Dark View menu](images/workbench-view-dark.png)

## Final Java test owners

| Test owner | Passing cases |
|---|---:|
| DatabaseSetupBrowserTest | 5 |
| TaxonomyResponsiveNavigationContractTest | 1 |
| TaskHierarchyStyleContractTest | 5 |
| SharedApplicationChromeBrowserIT | 53 |
| ArchitectureWorkspaceBrowserIT | 15 |
| PortfolioClientRoutingIT | 73 |
| DocumentTemplateNavigationBrowserIT | 18 |
| AnalysisSessionFrontendContractTest | 10 |
| SharedAdminNavigationBrowserIT | 7 |

The final application confirmation reruns the shared chrome, architecture workspace,
analysis commands and task-style owners after retiring the last old navigation
selectors. All 83 cases in that confirmation pass. The routing, document/admin,
readiness and server-backed database checks use their passing focused run.
