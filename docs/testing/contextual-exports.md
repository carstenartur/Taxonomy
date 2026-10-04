# Contextual decision exports

## Bedienung

„Entscheidungsbericht …“ öffnet denselben Dialog im Exportbereich, am gespeicherten Anforderungssnapshot und in der Architekturansicht. Die drei bisherigen Formatknöpfe werden durch eine Aktion ersetzt. Aus der einzelnen Baumansicht ist deren Taxonomie vorausgewählt; gespeicherte Berichte verwenden die Auswahlmöglichkeiten des jeweiligen Snapshots.

- **Kompakt:** kurze Zusammenfassung mit wesentlichen gespeicherten Begründungen je ausgewählter Taxonomie und vollständiger bewerteter Entscheidungsbaum, ohne Deckblatt und Kapitelanhang.
- **Standard:** zusätzlich Entscheidungsbegründungen und verfügbare gespeicherte Architektur.
- **Vollständig:** einschließlich Deckblatt und ausführlichen Nachweisen; entspricht grundsätzlich dem bisherigen Berichtsaufbau.
- **Weitere Optionen:** Abschnitte, Inhaltsverzeichnis (kein/kurz/ausführlich) und Baumdarstellung. Die Auswahl kann eine oder mehrere Teil-Taxonomien enthalten.

„Automatisch“ setzt kleine Bäume auf eine A4-Querformatseite, nutzt bei Bedarf A3 und verteilt größere Bäume auf lesbare Folgeseiten. Feste A4-/A3-Einzelseiten gelten je Taxonomie und melden einen Fehler, wenn der Baum nicht vollständig lesbar hineinpasst. Es werden keine Knoten abgeschnitten. Fehlende Server-Schriftzeichen führen zu einem Hinweis auf die Tabellenansicht. Word behält die validierte Dokumentvorlage, Kopf-/Fußzeilen und Vorlagenherkunft auch ohne Deckblatt.

Die Darstellungsauswahl ändert die Analyse nicht. Ein erfolgreicher Ausschnitt verdeckt keine offenen Bewertungen in der Quelle. Ursprünglicher Analyseumfang, Berichtsauswahl, Status, Snapshot und Quellfingerabdruck bleiben sichtbar. Bei alten Analysen wird ein nicht aufgezeichneter Umfang ausdrücklich benannt. Architekturbeziehungen über die Auswahlgrenze behalten ihre Endpunkte als gekennzeichneten Kontext. Reine Taxonomieanalysen ergänzen keine Architekturbeziehungen.

## Illustrated user guides and screenshot provenance

The user guides explain the compact Word workflow, taxonomy selection, contents,
landscape page options, other formats and retry behavior in
[German](../de/USER_GUIDE.md#configurable-decision-report) and
[English](../en/USER_GUIDE.md#configurable-decision-report). Each guide embeds three
current dialog screenshots: basic choices, expanded options and a narrow-screen
view. Existing general export illustrations are retained and labelled as earlier
overviews.

The six `docs/images/decision-export-*.png` images are captured by the existing
native-browser contract in `.github/scripts/decision-export-browser.mjs`. It loads
the production dialog JavaScript and supplies explicitly labelled example input;
it does not recreate the dialog markup. These are **component illustrations**,
not full-application, persisted-snapshot or live-provider acceptance evidence.
The [capture manifest](../images/decision-export-screenshots.json) records the
source commit, source and image hashes, browser version, language and viewport.

To regenerate from the repository root after installing the project's Playwright
browser dependencies:

```bash
TAXONOMY_DOC_SCREENSHOTS="$PWD/docs/images" npm --prefix .github run verify:decision-export-dialog
```

The optional `TAXONOMY_CHROME` and `TAXONOMY_PLAYWRIGHT_MODULE` variables select an
existing browser executable and Playwright module. The command first runs the
desktop/mobile behavior contract, then captures both languages and the manifest.
Inspect all six images before committing; the generator checks that each capture
contains the complete dialog. Without `TAXONOMY_DOC_SCREENSHOTS`, the command only
runs its existing behavior checks. Full-application screenshot owners and their
CI gates remain unchanged; see the [screenshot reference](../../.github/copilot-ref-screenshots.md).

## Format contracts

| Export | Selection and evidence |
| --- | --- |
| Decision DOCX | Selected frozen hierarchy, profile sections, short/full/no TOC, readable tree figures or table; optional saved graph context. |
| Decision HTML | Same selected evidence and section choices, inline SVG trees or table, navigable contents; optional architecture element/relation evidence. Print page sizes depend on the browser. |
| Decision JSON | Complete structured evidence for the selected taxonomies, original source scope/coverage, explicit report selection. Layout controls are hidden. |
| Score CSV | Recorded analysis roots and mode added as `AnalysisTaxonomies` and `AnalysisMode`; legacy unrecorded scope remains blank. Assessment/open-state columns retain their existing meaning. |
| Score/exchange JSON | Existing source-scope, score-semantics and coverage contract retained. |
| Architecture SVG/PDF/Visio/ArchiMate | Existing model-based export contract retained. A report selection does not silently alter separate interchange exports. |

Saved options: `GET /api/projects/{projectId}/snapshots/{snapshotId}/decision-report/options?language=de` returns source-bound roots and recorded scope. Export routes accept `profile`, repeated `taxonomyRoots`, `contents`, `treeLayout`, repeated `sections`. Ad-hoc POST accepts `analysisScope` and `exportOptions`. Existing routes without options retain FULL defaults. Invalid roots, explicit empty selections and enum values are rejected; omit `taxonomyRoots` to include the analysis roots. Selection never changes the analysis fingerprint; the selected architecture has its own graph digest and records the source graph digest in its scope notice. A valid taxonomy selection with no saved architecture remains exportable. HTML/JSON validate graph evidence without imposing Word's pagination ceilings. JSON retains recorded reasons independently of ranked leaves and decision chapters, including zero-only roots.

## Verification

- Scoped evidence regressions cover analysis versus presentation scope, contradictory scores, frozen source identity, ancestry rather than code prefixes, zero roots, missing alternatives, and repeated score adaptation.
- DOCX/HTML tests cover compact composition, short/no contents, complete tree coverage, fixed-page refusal, portrait/landscape transitions, validated template cover omission and template provenance.
- Snapshot/HTTP tests cover stored scope/coverage, typed query binding for all three formats, invalid enum rejection, compact graph omission and crossing relation context.
- Shared-dialog Node tests cover selection/query encoding, response type/provenance validation and failed downloads. `npm --prefix .github run verify:decision-export-dialog` runs the native-browser desktop/390px behavior contract (requires the pinned Playwright browser installation).
- Existing Selenium copilot/workbench journeys now operate the shared dialog. Their full application journeys require the project's integration environment; the isolated dialog contract does not replace those suites.

### Local evidence, 2026-10-03

- Focused Maven reactor: **111 tests in 24 classes, zero failures/errors/skips**, covering the report, snapshot, HTTP, workbench and architecture boundaries. Invalid JSON/query options were also exercised with the application's real exception advice. Regressions verify that absent optional architecture remains exportable and deterministic score descriptions never become recorded AI reasons.
- `npm --prefix .github run verify:ui-contracts`: **739 reported tests across 34 groups, zero failures**; the remaining script-only checks also completed successfully.
- Native Chromium dialog contract passed at 1280px and 390px, including selection, failed download/retry, format-specific controls and focus restoration.
- Illustrated guide refresh: the native contract generated all six DE/EN component captures. Both guides rendered with the application's Flexmark configuration; all prior headings and illustrations were retained. The new sections loaded their three images without horizontal overflow at 1280px and 390px using the help-content stylesheet. This is a documentation render check, not a full-application help-page acceptance.
- LibreOffice fixtures cover compact reports with and without the validated template, A3 expansion, readable continuation pages and portrait/landscape/portrait transitions. These are authored deterministic fixtures, not provider inference results.
- The full CI matrix, external databases and full-application Selenium journeys were **not run locally**. Their normal CI gates remain required before merge.

The focused Java selection was `./mvnw -pl taxonomy-app -am test -Dtest=DecisionCompactReportTest,DecisionRationale*Test,SnapshotWordReportServiceTest,DecisionExportScopeTest,DecisionReportOptions*HttpTest,ArchitectureDecisionReportBoundaryTest,DecisionTreeOverviewTest,ArchitectureFigurePlannerTest,ArchitectureReport*Test,ArchitectureWorkbenchServiceTest -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.build.cache.enabled=false` (quote the test selection in shells that expand globs). Word fixture rendering is reproducible with `-Ddecision.export.fixtures=/absolute/output/directory` on `DecisionCompactReportTest`; inspect the generated DOCX files with LibreOffice. No fixture output belongs in source control.
