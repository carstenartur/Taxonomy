# UI-Lückenanalyse

Implementierungsnotizen zu den aufgeführten GUI-Dialogen. Die Phasenangaben beschreiben frühere Lieferarbeit, keine aktuelle Abnahme jeder Ansicht oder jedes Bedienelements. Das [Ansichts- und Bedienelementinventar](../testing/ui-view-control-inventory.md) enthält aktuelle Templates, Dokumentation, Testverantwortliche und fehlende Nachweise.

## Implementierte Dialoge

| Dialog | Status | Phase | Dateien |
|---|---|---|---|
| Merge-Vorschau-Modal | ✅ Implementiert | 1.6 | `index.html`, `taxonomy-action-guards.js` |
| Cherry-Pick-Vorschau-Modal | ✅ Implementiert | 1.6 | `index.html`, `taxonomy-action-guards.js` |
| Merge-Konfliktlösungs-Modal | ✅ Implementiert | 1.1 | `index.html`, `taxonomy-merge-resolution.js` |
| Cherry-Pick-Konfliktdialog | ✅ Implementiert | 1.2 | `index.html`, `taxonomy-merge-resolution.js` |
| Sync-Divergenz-Auflösungs-Modal | ✅ Implementiert | 1.3 | `index.html`, `taxonomy-merge-resolution.js` |
| Branch-Löschung (mit Bestätigung) | ✅ Implementiert | 1.4 | `taxonomy-variants.js` |
| Operationsergebnis-Toasts | ✅ Implementiert | 1.5 | `taxonomy-operation-result.js` |
| Varianten-Erstellungs-Modal | ✅ Implementiert | Vorbestehend | `index.html`, `taxonomy-context-bar.js` |
| Kontext-Vergleichs-Modal | ✅ Implementiert | Vorbestehend | `index.html`, `taxonomy-context-compare.js` |
| Kontext-Transfer-Modal | ✅ Implementiert | Vorbestehend | `index.html`, `taxonomy-context-transfer.js` |

## Backend-Endpunkte

| Endpunkt | Status | Beschreibung |
|---|---|---|
| `GET /api/dsl/merge/preview` | ✅ Vorbestehend | Merge-Ergebnis in der Vorschau anzeigen |
| `GET /api/dsl/cherry-pick/preview` | ✅ Vorbestehend | Cherry-Pick-Ergebnis in der Vorschau anzeigen |
| `GET /api/dsl/merge/conflicts` | ✅ Implementiert | Details zu Merge-Konflikten |
| `POST /api/dsl/merge/resolve` | ✅ Implementiert | Aufgelösten Merge-Inhalt committen |
| `GET /api/dsl/cherry-pick/conflicts` | ✅ Implementiert | Details zu Cherry-Pick-Konflikten |
| `POST /api/dsl/cherry-pick/resolve` | ✅ Implementiert | Aufgelösten Cherry-Pick-Inhalt committen |
| `DELETE /api/dsl/branch` | ✅ Implementiert | Branch löschen (mit Schutz) |
| `POST /api/workspace/resolve-diverged` | ✅ Implementiert | Divergierten Sync-Status auflösen |

## JavaScript-Module

| Modul | Zeilen | Zweck |
|---|---|---|
| `taxonomy-merge-resolution.js` | Neu | Konfliktlösungs-UI, Auflösung divergierter Zustände |
| `taxonomy-operation-result.js` | Neu | Toast-Benachrichtigungssystem für Git-Operationen |
| `taxonomy-action-guards.js` | Aktualisiert | Bootstrap-Modale ersetzen `alert()`/`confirm()` |
| `taxonomy-variants.js` | Aktualisiert | Lösch-Button für nicht geschützte Branches hinzugefügt |
| `taxonomy-workspace-sync.js` | Aktualisiert | Auflösen-Button für DIVERGED-Status, Toast-Benachrichtigungen |
| `taxonomy-dsl-editor.js` | Aktualisiert | Toast-Benachrichtigungen für Merge-/Cherry-Pick-Ergebnisse |

---

## Nachweisprüfung (25. Oktober 2026)

Die frühere Aussage, alle Lücken seien geschlossen, ging zu weit. Implementierte Dialoge, eine Screenshot-Datei und ein erfolgreicher Test eines anderen Einstiegswegs sind unterschiedliche Nachweise. Die [Funktionsmatrix](FEATURE_MATRIX.md) benennt diese Grenzen; das [Ansichts- und Bedienelementinventar](../testing/ui-view-control-inventory.md) hält offene Nacharbeiten fest.

### Funktionen mit GUI-Einstieg

| Funktion | GUI-Element | Benutzerhandbuch | Screenshot-Nachweis |
|---|---|---|---|
| Blatt-Begründung | `leafJustificationModal` | §6 | Historisches Bild #18 |
| Anforderungsabdeckung | `coverageDashboard` + `coverageRecordBtn` | §11c | Historische Bilder #26, 27 zeigen dieses Dashboard |
| Lückenanalyse | `gapAnalysisPanel` + `gapAnalyzeBtn` | §11d | Keine eigene aktuelle Aufnahme verknüpft; #26/27 zeigen keine Lückenanalyse |
| Empfehlungen | `recommendationPanel` + `recommendBtn` | §11e | Keine eigene aktuelle Aufnahme verknüpft |
| Mustererkennung | `patternDetectionPanel` + `patternDetectBtn` | §11f | Keine eigene aktuelle Aufnahme verknüpft |
| Berichte (MD/HTML/DOCX) | Schaltflächen `exportReportMd/Html/Docx` | §10a | Historisches Bild #23 zeigt Exportaktionen, keine erfolgreichen Downloads |
| Anforderungs-Auswirkungsanalyse | `requirementImpactBtn` im Register Graph | §11b | Keine eigene aktuelle Aufnahme verknüpft; Verhaltenstest in `ux-state-recovery.cjs` |
| Graph-Einstieg aus Baum/Auswirkungsansicht | Knotenaktion oder Elementdetails → Graph | §8 | [Aktuelle DE-Aufnahme](../images/graph-entry-current-de.png); Serverprüfung: `verifyGraphEntry` in `ui-primary-basic-workflows.mjs`; Komponentenregressionen: `ux-state-recovery.cjs` / `analysis-ux.browser.test.mjs` |

### Dokumentation und Bildnachweise

Workspace, Historie, Branch-Vergleich, Transfer, Varianten, DSL und Merge sind im [Benutzerhandbuch](USER_GUIDE.md#12-versions-tab) erklärt. Damit ist nicht jede visuelle oder funktionale Lücke geschlossen:

- #20, #37 und #38 verwendeten einen nicht mehr unterstützten Schreibweg für Katalog-Testbeziehungen und sind zurückgezogen. Angenommene Workspace-Vorschläge gehören zur Workspace-Relationsprojektion; Katalog-Graphabfragen lesen einen anderen Umfang. Die aktuellen Workbench-Aufnahmen bleiben Nachweise ihrer ausgewählten persistierten Beispiel-Snapshots.
- #32 ist eine historische Darstellung der graph-semantischen Suche. Der Test stellte die erforderliche Workspace-Relationsprojektion nicht sicher; ein neuer Workspace kann `409 NOT_BUILT` liefern. Die aktuelle Graph-Einstiegsaufnahme prüft eine andere Operation und schließt diese Such-Nachweislücke nicht.
- #48–65 und #67–68 verwendeten eingesetzten Darstellungszustand. Ihre Generatoren sind zurückgezogen; erhaltene Bilder sind historische Beispiele und kein Beleg für erfolgreiche Merge-, Transfer-, Sync-, Lösch- oder Restore-Aktionen.
- #41–47 und #66 bleiben historische Darstellungen von Historie und Workspace. Eine vorhandene Datei bindet sich nicht automatisch an den aktuellen Quellstand.
- Datenbankassistent, Architektureditor und persistierte Workbench haben eigene Anleitungen und Testverantwortliche. Herkunft und Umfang ihrer Aufnahmen stehen im [Inventar](../testing/ui-view-control-inventory.md).
- Leer-/Fehlerzustände, Berechtigungen, Tastaturbedienung und gleichzeitige Änderungen benötigen Verhaltensprüfungen. Das statische Zählen von Schaltflächen oder Screenshots ersetzt sie nicht.

Beachten Sie beim Erneuern von Bildern die [Regeln zur Screenshot-Herkunft](../../.github/copilot-ref-screenshots.md). Fehlende Nachweise bleiben ausdrücklich offen, bis die betreffende Anwendungsaktion beobachtet und aufgezeichnet wurde.
