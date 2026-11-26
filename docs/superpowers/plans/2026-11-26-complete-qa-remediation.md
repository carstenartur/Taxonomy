# Complete QA remediation and merge implementation plan

> **For agentic workers:** Use superpowers:subagent-driven-development. Complete scoped fixes, independent review and authoritative CI before merge.

**Goal:** Restore all identified product corrections from the retained QA matrix, repair current CI, and merge the authorized Taxonomy PRs.

**Architecture:** Preserve existing module and Maven-owned test boundaries. Fix real production contracts and use a shared export display context where visible report provenance is needed; keep original model evidence immutable. Parallel workers own separate source areas; the coordinator owns integration, help, publication and merges.

**Tech Stack:** Java 25, Maven, Spring Boot, Thymeleaf, JavaScript, SQLite native QEA, DOCX/SVG/PDF.

**Spec:** User's full QA completion report and retained findings-evidence-matrix.json (baseline 89c32b5, old local source e4258d4). The table below preserves the product finding identifiers; old results are not evidence for reconstructed code.

## Global constraints

- User explicitly authorizes all identified fixes, publication and PR merges; no additional permission pause is needed.
- Continue on the existing fix/qa-export-workflow-20261126 branch; do not duplicate the checkout or overwrite others' changes.
- Preserve strict compiler/architecture policies and current test ownership; do not relax gates, assertions, timeouts or supported product coverage.
- Keep original requirement text, model explanations and stored historical evidence unchanged; localize known generated display strings only.
- Use StandardCharsets, CPU-derived concurrency and current Java 25/toolchain declarations.
- Retain selected concise evidence in Git, not raw build logs; publish tested checkpoints incrementally.
- Do not assert actual vLLM inference, Enterprise Architect execution or visual acceptance without direct evidence.

## Review focus

- Frozen snapshot versus current requirement and tab context: obsolete replies and actions must not replace current input or source identity.
- Repeated or unauthorized actions: exactly one intended mutation; retained evidence and exclusions survive partial status changes.
- Native references including GUIDs and placement: unchanged rows must retain their original targets or explicit unresolved state.
- Long German/English reports and narrow screens: visible partial status, provenance, three-digit footer counts and reachable modal controls.
- Help under /taxonomy, nested relative Markdown and fragments: correct destination, preserved query/fragment, current assets and truthful capture provenance.

## Tasks

### 1. Complete GUI and browser contracts

Files: taxonomy-app static/js/{api,portfolio,shared,core}, templates, related CSS/i18n, existing Maven-owned UI/resource tests; portfolio controller/service only if required by a missing contract.

- [x] Read every assigned matrix entry and trace current server/DOM contracts.
- [x] Reproduce remaining failures with focused behavior tests; retain already-correct behavior.
- [x] Correct solution proposals, action status, version labels, form drafts, intake/reformulation, inventory display, mobile adoption and proposal loading.
- [ ] Update SharedApplicationChromeBrowserIT snapshot routes to the actual production contract and run the affected browser case.
- [x] Run affected UI/Maven suites and independent review; deliver commit-ready paths and per-finding evidence.

### 2. Restore report and diagram display corrections

Files: taxonomy-export, taxonomy-reporting, export integration in taxonomy-app; existing renderer/service tests.

- [x] Reproduce VIS-01..05 and remaining ADD-REPORT/ADD-CANONICAL findings.
- [x] Supply explicit snapshot/canonical display context to exports; visible SVG status/warnings/provenance without changing graph semantics.
- [x] Fix table widths, placeholder/TOC duplication, known system diagnostics, canonical title aliases, English instruction and three-digit page footer.
- [x] Run focused Java regressions; render representative German/English artifacts and inspect layout; independent review.

### 3. Native identity and product-download integrity

Files: plugins/taxonomy-sparx-plugin, .mvn/product-artifacts.json and the existing artifact resolver/tests if required.

- [x] Reproduce ADD-NATIVE-01 GUID capture and placement no-op; implement preservation or explicit rejection and test real SQLite files.
- [x] Diagnose the pinned Archi HTTP404 using official release/artifact sources. Restore a verifiable supported artifact without dropping the product probe.
- [x] Run native and artifact resolution tests; independently review both changes.

### 4. Help links, documentation and screenshot provenance

Files: documentation controller/link resolver, docs/DE+EN, screenshot manifests/assets, existing help tests.

- [x] Reproduce ADD-HELP-ANCHORS, ADD-HELP-LINKS and ADD-PROVENANCE-01 against retained evidence and current files.
- [x] Correct resource-relative links and context paths; add semantic anchors/sections; update relevant user guides.
- [x] Produce or restore correctly attributed toolbar captures; preserve historical records honestly.
- [ ] Validate registered/nested help pages, fragments and assets; open contextual help from GUI; independent review.

### 5. Integration, PRs and main

- [x] Check all 15 original plus 29 additional product entries against fresh scoped evidence; preserve required browser/CI acceptance as explicit gates.
- [ ] Repair and verify currently open dependency PRs1601..1606 with exact-head checks and appropriate semantic review.
- [ ] Publish reviewed changes, run required CI, resolve new failures without weakening gates.
- [ ] Merge authorized PRs only after successful required checks, preserving exact tested head.
- [ ] Verify final main/ref, merge reachability and main CI; record remaining external acceptance boundaries accurately.

## Finding coverage ledger

The ledger records fresh evidence for the reconstructed source. Test harness corrections from the old archive are implementation notes, not 29 additional product defects. Fresh implementation evidence is summarized in the [completion record](../../qa/2026-11-26-qa-completion.md). Required integrated CI, browser acceptance and merge state are recorded in [PR1607](https://github.com/carstenartur/Taxonomy/pull/1607). Checkboxes below record this implementation checkpoint; the linked PR is authoritative for subsequent CI and merges.

| ID | Product finding | Owner | Status |
| --- | --- | --- | --- |
| UX-01 | Bestätigen und Ablehnen von Snapshot-Zuordnungen brechen ab | GUI | Implemented; focused 753-case run and independent review; browser gate in PR1607 |
| UX-02 | Abdeckungsformulare für Lösungen und Produkte sind blockiert | GUI | Implemented; focused 753-case run and independent review; browser gate in PR1607 |
| UX-03 | Klick auf Produktkandidaten-Feld löst ungewollten Schreibaufruf aus | GUI | Implemented; focused 753-case run and independent review; browser gate in PR1607 |
| UX-04 | Anforderung aus Leerzeichen wird ohne Rückmeldung verworfen | GUI | Implemented; focused 753-case run and independent review; browser gate in PR1607 |
| UX-05 | Alle analysieren im leeren Projekt bleibt ohne sichtbaren Fehler | GUI | Implemented; focused 753-case run and independent review; browser gate in PR1607 |
| UX-06 | Verspätete Schlüsselabfrage überschreibt die aktuelle Nutzereingabe | GUI | Implemented; focused 753-case run and independent review; browser gate in PR1607 |
| QEA-1 | UPDATE_COPY verbindet einen unselektierten verwaisten Konnektor mit einem neu angelegten Objekt | Native | Verified: full 176-case native suite; independent review |
| QEA-2 | ADMBw-Export ordnet ein unselektiertes Objekt durch Wiederverwendung einer verwaisten Package_ID neu zu | Native | Verified: full 176-case native suite; independent review |
| QEA-3 | Änderung eines nicht unterstützten nativen Felds wird ohne Ablehnung verworfen | Native | Verified: full 176-case native suite; independent review |
| QEA-4 | Memo-codierte Quellenangabe liefert keine verwertbare ServiceSpecification-Evidenz | Native | Verified: full 176-case native suite; independent review |
| VIS-01 | The standalone rendered snapshot SVG does not visibly disclose PARTIAL / TEILERGEBNIS, the exhausted relation-search budget, remaining unassessed/unresolved work or the CI-1151 open question. PARTIAL exists only in SVG title metadata. | Reports | Verified: focused tests and fresh DE/EN rendering; independent review |
| VIS-02 | The snapshot SVG canvas lacks the snapshot identifier/provider/commit header shown in the PDF. The canonical SVG visibly includes its source view but lacks the repository/workspace/branch and checkpoint shown in its PDF. | Reports | Verified: focused tests and fresh DE/EN rendering; independent review |
| VIS-03 | Narrow table headers split Vaterknoten as Vaterknote/n and Kennung as Kennun/g. Some compact classification labels also break within German words. | Reports | Verified: focused tests and fresh DE/EN rendering; independent review |
| VIS-04 | Diagnostic warning identifiers and several explanatory warning sentences remain English inside otherwise German reports. | Reports | Verified: focused tests and fresh DE/EN rendering; independent review |
| VIS-05 | The cover retains Organisation · Organisationseinheit placeholder text. The table of contents uses two identical Erzeugung und Versionsnachweis labels for nested and report-wide provenance sections. | Reports | Verified: focused tests and fresh DE/EN rendering; independent review |
| ADD-NATIVE-01 | GUID-Einfang und erfolgreicher Placement-No-op | Native | Verified: full 176-case native suite; independent review |
| ADD-UI-01 | Falscher Katalog-Coverage-Endpunkt und äußere DTO-Ebene | GUI | Implemented; focused 753-case run and independent review; browser gate in PR1607 |
| ADD-PRODUCT-01 | Gültiger 42%-Kandidaten-POST scheitert an primitiver fehlender confidence | GUI | Implemented; focused 753-case run and independent review; browser gate in PR1607 |
| ADD-PRODUCT-02 | Kandidatenreview-PATCH fehlt | GUI | Implemented; focused 753-case run and independent review; browser gate in PR1607 |
| ADD-PRODUCT-03 | Teilupdates löschen Evidenz/Status; Inventory-Sonderpfad muss gezielt ersetzen | GUI | Implemented; focused 753-case run and independent review; browser gate in PR1607 |
| ADD-HTTP-01 | Snapshotlistenbutton ruft nicht vorhandenen analyses-Pfad auf | GUI | Implemented; focused 753-case run and independent review; browser gate in PR1607 |
| ADD-HTTP-02 | Lösungsvorschlag ruft falschen propose-Pfad auf | GUI | Implemented; focused 753-case run and independent review; browser gate in PR1607 |
| ADD-HTTP-03 | Maßnahmen-Save sendet ungültigen Status DECIDED | GUI | Implemented; focused 753-case run and independent review; browser gate in PR1607 |
| ADD-DTO-01 | Snapshotdetail, Mappingherkunft und Score-Diff lesen falsche Formen | GUI | Implemented; focused 753-case run and independent review; browser gate in PR1607 |
| ADD-INTAKE-01 | Intake-Label, Reviewposition, Fokus und Hilfe wirken widersprüchlich | GUI | Implemented; focused 753-case run and independent review; browser gate in PR1607 |
| ADD-INTAKE-02 | Retrylabel/aria wird durch neuen Entwurf überschrieben | GUI | Implemented; focused 753-case run and independent review; browser gate in PR1607 |
| ADD-COPILOT-01 | Englische Autopilot-Instruktion und unzutreffende manuelle Erklärung | GUI | Implemented; focused 753-case run and independent review; browser gate in PR1607 |
| ADD-PRODUCT-UI | Unbeschriftete Felder, nach Produkterstellung veraltete Optionen/Tooltip | GUI | Implemented; focused 753-case run and independent review; browser gate in PR1607 |
| ADD-DETAIL-UI | Reformulation-Leerzustand/fehlender Snapshot und mobile Tabs | GUI | Implemented; focused 753-case run and independent review; browser gate in PR1607 |
| ADD-INVENTORY-UI | Große optionale Konfiguration und scheinbar aktives Öffnen ohne Auswahl | GUI | Implemented; focused 753-case run and independent review; browser gate in PR1607 |
| ADD-SNAPSHOT-L10N | Unlokalisierte Vergleichslabels/Flags und fehlender Vorgänger ohne Erklärung | GUI | Implemented; focused 753-case run and independent review; browser gate in PR1607 |
| ADD-HELP-ANCHORS | 14 fehlende DE/EN-Hilfeanker | Help | Verified: document/asset checks and independent review; GUI gate in PR1607 |
| ADD-HELP-LINKS | Relative Repository-/Markdownziele und Kontextpfad bei Bild-Herkunftslinks | Help | Verified: document/asset checks and independent review; GUI gate in PR1607 |
| ADD-PROVENANCE-01 | Zwei Toolbar-PNGs widersprechen historischen aktiven Manifesthashes | Help | Verified: document/asset checks and independent review; GUI gate in PR1607 |
| ADD-ADOPTION-UI | NOT_SYNTHESIZED roh im sichtbaren Übernahmedialog | GUI | Implemented; focused 753-case run and independent review; browser gate in PR1607 |
| ADD-ANALYSIS-TARGET | Uneindeutiges Aktionsziel am Button zur Versionsanalyse | GUI | Implemented; focused 753-case run and independent review; browser gate in PR1607 |
| ADD-REFORMULATION-STATUS | Bekannte Statuscodes im Reformulationsbereich bleiben roh | GUI | Implemented; focused 753-case run and independent review; browser gate in PR1607 |
| ADD-ADOPTION-MOBILE | Mobiler Schließenknopf ragt trotz erfolgreichem UI-Kommando aus dem Dialog | GUI | Implemented; focused 753-case run and independent review; browser gate in PR1607 |
| ADD-PROPOSALS-LOADING | Vorschlagsliste bleibt nach Kontextabbruch im Ladestatus | GUI | Implemented; focused 753-case run and independent review; browser gate in PR1607 |
| ADD-INVENTORY-CLARIFICATION | Vier feste Evaluatorhinweise erscheinen im deutschen Inventar weiterhin englisch | GUI | Implemented; focused 753-case run and independent review; browser gate in PR1607 |
| ADD-REPORT-DIAGNOSTICS | Zwei bekannte Systemdiagnoseformate fehlen in der deutschen Berichtsanzeige | Reports | Verified: focused tests and fresh DE/EN rendering; independent review |
| ADD-REPORT-FOOTER | Dreistellige Seitenzähler umbrechen im Bootstrap-Footer | Reports | Verified: focused tests and fresh DE/EN rendering; independent review |
| ADD-CANONICAL-TITLE | Bekannter Diagramm-Titelalias wird roh im Export gedruckt | Reports | Verified: focused tests and fresh DE/EN rendering; independent review |
| ADD-REPORT-EN-INSTRUCTION | Feste Vorlagen-Bearbeitungsanleitung bleibt im englischen Bericht deutsch | Reports | Verified: focused tests and fresh DE/EN rendering; independent review |

### Additional findings from the integrated capture review

These two findings were observed after the original 44-finding reconstruction, in the real documentation capture of the earlier `8b6a86c2` source. They are tracked separately from the retained matrix. The [completion record](../../qa/2026-11-26-qa-completion.md) records their reproduction and corrections; PR 1607 remains authoritative for fresh CI, browser acceptance and merges.

| ID | Product finding | Owner | Status |
| --- | --- | --- | --- |
| REVIEW-UI-01 | Evaluated incomplete-only pattern coverage is labelled unevaluated; overview and detail aggregate disagree | GUI | Corrected; 222 final JS cases and 8 app contracts pass; independent source review; fresh browser/capture gate in PR 1607 |
| REVIEW-UI-02 | Local accepted proposals expose and automatically call central-only branch adoption | GUI | Corrected; explicit central provenance at table, binding and direct-preview boundaries; existing central safeguards retained; fresh browser/capture gate in PR 1607 |
