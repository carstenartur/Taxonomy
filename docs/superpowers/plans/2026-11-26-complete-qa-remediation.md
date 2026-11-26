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

- [ ] Read every assigned matrix entry and trace current server/DOM contracts.
- [ ] Reproduce remaining failures with focused behavior tests; retain already-correct behavior.
- [ ] Correct solution proposals, action status, version labels, form drafts, intake/reformulation, inventory display, mobile adoption and proposal loading.
- [ ] Update SharedApplicationChromeBrowserIT snapshot routes to the actual production contract and run the affected browser case.
- [ ] Run affected UI/Maven suites and independent review; deliver commit-ready paths and per-finding evidence.

### 2. Restore report and diagram display corrections

Files: taxonomy-export, taxonomy-reporting, export integration in taxonomy-app; existing renderer/service tests.

- [ ] Reproduce VIS-01..05 and remaining ADD-REPORT/ADD-CANONICAL findings.
- [ ] Supply explicit snapshot/canonical display context to exports; visible SVG status/warnings/provenance without changing graph semantics.
- [ ] Fix table widths, placeholder/TOC duplication, known system diagnostics, canonical title aliases, English instruction and three-digit page footer.
- [ ] Run focused Java regressions; render representative German/English artifacts and inspect layout; independent review.

### 3. Native identity and product-download integrity

Files: plugins/taxonomy-sparx-plugin, .mvn/product-artifacts.json and the existing artifact resolver/tests if required.

- [ ] Reproduce ADD-NATIVE-01 GUID capture and placement no-op; implement preservation or explicit rejection and test real SQLite files.
- [ ] Diagnose the pinned Archi HTTP404 using official release/artifact sources. Restore a verifiable supported artifact without dropping the product probe.
- [ ] Run native and artifact resolution tests; independently review both changes.

### 4. Help links, documentation and screenshot provenance

Files: documentation controller/link resolver, docs/DE+EN, screenshot manifests/assets, existing help tests.

- [ ] Reproduce ADD-HELP-ANCHORS, ADD-HELP-LINKS and ADD-PROVENANCE-01 against retained evidence and current files.
- [ ] Correct resource-relative links and context paths; add semantic anchors/sections; update relevant user guides.
- [ ] Produce or restore correctly attributed toolbar captures; preserve historical records honestly.
- [ ] Validate registered/nested help pages, fragments and assets; open contextual help from GUI; independent review.

### 5. Integration, PRs and main

- [ ] Check all 15 original plus29 additional product entries against new evidence; no open entry may be hidden by a broad green count.
- [ ] Repair and verify currently open dependency PRs1601..1606 with exact-head checks and appropriate semantic review.
- [ ] Publish reviewed changes, run required CI, resolve new failures without weakening gates.
- [ ] Merge authorized PRs only after successful required checks, preserving exact tested head.
- [ ] Verify final main/ref, merge reachability and main CI; record remaining external acceptance boundaries accurately.

## Finding coverage ledger

Status starts as pending verification, including reconstructed fixes. Test harness corrections from the old archive are implementation notes, not29 additional product defects.

| ID | Product finding | Owner | Status |
| --- | --- | --- | --- |
| UX-01 | Bestätigen und Ablehnen von Snapshot-Zuordnungen brechen ab | GUI | Pending verification |
| UX-02 | Abdeckungsformulare für Lösungen und Produkte sind blockiert | GUI | Pending verification |
| UX-03 | Klick auf Produktkandidaten-Feld löst ungewollten Schreibaufruf aus | GUI | Pending verification |
| UX-04 | Anforderung aus Leerzeichen wird ohne Rückmeldung verworfen | GUI | Pending verification |
| UX-05 | Alle analysieren im leeren Projekt bleibt ohne sichtbaren Fehler | GUI | Pending verification |
| UX-06 | Verspätete Schlüsselabfrage überschreibt die aktuelle Nutzereingabe | GUI | Pending verification |
| QEA-1 | UPDATE_COPY verbindet einen unselektierten verwaisten Konnektor mit einem neu angelegten Objekt | Native | Pending verification |
| QEA-2 | ADMBw-Export ordnet ein unselektiertes Objekt durch Wiederverwendung einer verwaisten Package_ID neu zu | Native | Pending verification |
| QEA-3 | Änderung eines nicht unterstützten nativen Felds wird ohne Ablehnung verworfen | Native | Pending verification |
| QEA-4 | Memo-codierte Quellenangabe liefert keine verwertbare ServiceSpecification-Evidenz | Native | Pending verification |
| VIS-01 | The standalone rendered snapshot SVG does not visibly disclose PARTIAL / TEILERGEBNIS, the exhausted relation-search budget, remaining unassessed/unresolved work or the CI-1151 open question. PARTIAL exists only in SVG title metadata. | Reports | Pending verification |
| VIS-02 | The snapshot SVG canvas lacks the snapshot identifier/provider/commit header shown in the PDF. The canonical SVG visibly includes its source view but lacks the repository/workspace/branch and checkpoint shown in its PDF. | Reports | Pending verification |
| VIS-03 | Narrow table headers split Vaterknoten as Vaterknote/n and Kennung as Kennun/g. Some compact classification labels also break within German words. | Reports | Pending verification |
| VIS-04 | Diagnostic warning identifiers and several explanatory warning sentences remain English inside otherwise German reports. | Reports | Pending verification |
| VIS-05 | The cover retains Organisation · Organisationseinheit placeholder text. The table of contents uses two identical Erzeugung und Versionsnachweis labels for nested and report-wide provenance sections. | Reports | Pending verification |
| ADD-NATIVE-01 | GUID-Einfang und erfolgreicher Placement-No-op | Native | Pending verification |
| ADD-UI-01 | Falscher Katalog-Coverage-Endpunkt und äußere DTO-Ebene | GUI | Pending verification |
| ADD-PRODUCT-01 | Gültiger 42%-Kandidaten-POST scheitert an primitiver fehlender confidence | GUI | Pending verification |
| ADD-PRODUCT-02 | Kandidatenreview-PATCH fehlt | GUI | Pending verification |
| ADD-PRODUCT-03 | Teilupdates löschen Evidenz/Status; Inventory-Sonderpfad muss gezielt ersetzen | GUI | Pending verification |
| ADD-HTTP-01 | Snapshotlistenbutton ruft nicht vorhandenen analyses-Pfad auf | GUI | Pending verification |
| ADD-HTTP-02 | Lösungsvorschlag ruft falschen propose-Pfad auf | GUI | Pending verification |
| ADD-HTTP-03 | Maßnahmen-Save sendet ungültigen Status DECIDED | GUI | Pending verification |
| ADD-DTO-01 | Snapshotdetail, Mappingherkunft und Score-Diff lesen falsche Formen | GUI | Pending verification |
| ADD-INTAKE-01 | Intake-Label, Reviewposition, Fokus und Hilfe wirken widersprüchlich | GUI | Pending verification |
| ADD-INTAKE-02 | Retrylabel/aria wird durch neuen Entwurf überschrieben | GUI | Pending verification |
| ADD-COPILOT-01 | Englische Autopilot-Instruktion und unzutreffende manuelle Erklärung | GUI | Pending verification |
| ADD-PRODUCT-UI | Unbeschriftete Felder, nach Produkterstellung veraltete Optionen/Tooltip | GUI | Pending verification |
| ADD-DETAIL-UI | Reformulation-Leerzustand/fehlender Snapshot und mobile Tabs | GUI | Pending verification |
| ADD-INVENTORY-UI | Große optionale Konfiguration und scheinbar aktives Öffnen ohne Auswahl | GUI | Pending verification |
| ADD-SNAPSHOT-L10N | Unlokalisierte Vergleichslabels/Flags und fehlender Vorgänger ohne Erklärung | GUI | Pending verification |
| ADD-HELP-ANCHORS | 14 fehlende DE/EN-Hilfeanker | Help | Pending verification |
| ADD-HELP-LINKS | Relative Repository-/Markdownziele und Kontextpfad bei Bild-Herkunftslinks | Help | Pending verification |
| ADD-PROVENANCE-01 | Zwei Toolbar-PNGs widersprechen historischen aktiven Manifesthashes | Help | Pending verification |
| ADD-ADOPTION-UI | NOT_SYNTHESIZED roh im sichtbaren Übernahmedialog | GUI | Pending verification |
| ADD-ANALYSIS-TARGET | Uneindeutiges Aktionsziel am Button zur Versionsanalyse | GUI | Pending verification |
| ADD-REFORMULATION-STATUS | Bekannte Statuscodes im Reformulationsbereich bleiben roh | GUI | Pending verification |
| ADD-ADOPTION-MOBILE | Mobiler Schließenknopf ragt trotz erfolgreichem UI-Kommando aus dem Dialog | GUI | Pending verification |
| ADD-PROPOSALS-LOADING | Vorschlagsliste bleibt nach Kontextabbruch im Ladestatus | GUI | Pending verification |
| ADD-INVENTORY-CLARIFICATION | Vier feste Evaluatorhinweise erscheinen im deutschen Inventar weiterhin englisch | GUI | Pending verification |
| ADD-REPORT-DIAGNOSTICS | Zwei bekannte Systemdiagnoseformate fehlen in der deutschen Berichtsanzeige | Reports | Pending verification |
| ADD-REPORT-FOOTER | Dreistellige Seitenzähler umbrechen im Bootstrap-Footer | Reports | Pending verification |
| ADD-CANONICAL-TITLE | Bekannter Diagramm-Titelalias wird roh im Export gedruckt | Reports | Pending verification |
| ADD-REPORT-EN-INSTRUCTION | Feste Vorlagen-Bearbeitungsanleitung bleibt im englischen Bericht deutsch | Reports | Pending verification |
