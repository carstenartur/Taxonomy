# Funktionsvollständigkeits-Matrix

Diese Matrix verfolgt den Lieferstatus aller wesentlichen Produktfunktionen.
Eine Funktion gilt nur als abgeschlossen, wenn alle erforderlichen Spalten ✅ zeigen.

> Siehe [Definition of Done](DEVELOPER_GUIDE.md#definition-of-done--benutzer-sichtbare-funktionen)
> für die Produktregeln.
>
> Der Status der Architekturexporte wird durch die nachstehend dokumentierte
> Unterstützungsgrenze festgelegt. Dateierzeugung und breite Interoperabilität mit
> Drittwerkzeugen werden getrennt bewertet.

## Zusammenhängende Abläufe und ihre Grenzen

Die folgende Übersicht trennt geliefertes Verhalten, Qualitätsaussagen und noch
offene Integration. Technische Tests oder ältere Screenshots sind keine neu
aufgezeichnete Abnahme der aktuellen Anwendung.

| Bereich | Geliefertes Verhalten / aktueller Stand | Grenze |
|---|---|---|
| Architekturgestützte Anforderungsklärung | Gespeicherte Vorschläge, typisierte Fragen, menschliche Entscheidungen, Übernahmeprüfung, vererbte Entscheidungen und historische Exporte | Ausdrückliche Übernahme, Freigabe und Reanalyse sind getrennt; Playback-Tests belegen keine semantische Qualität realer Modelle. |
| Auswählbare Analyse und Fortsetzung | Ausgewählte Wurzeln, FULL/TAXONOMIES_ONLY, gespeicherte Arbeitspläne und Wiederverwendung validierter Antworten | Umfang und Fehler bleiben sichtbar; ein abgeschlossener Plan beweist keine universelle Abdeckung. |
| Semantische Bearbeitung und Git-Checkpoints | Dauerhafte Operationen mit Begründung/Gegenoperation und getrennte stabile Git-Versionen | Editor-Undo/Redo und das Zurücksetzen eines Branch-Heads sind unterschiedliche Aktionen. |
| Lokales ONNX | Embedding-Suche/-Bewertung | Kein generatives Relations- oder Neuformulierungsmodell; DE/EN-Oberflächen belegen keine gleichwertige Suchqualität. |
| Planungsprofile | Versionierter geplanter Betriebsbeginn und Norm-/Vorgabenverweise | Ein Datum ist keine Betriebsverfügbarkeit; ein Verweis ist kein Erfüllungsnachweis. |
| Portable Sicherung und externe Weiterbearbeitung | Unterstützende Capture-/Archivadapter und begrenzte Stand-Bundles | Produktive Erfassung, vollständige Historie/Wiederherstellung und geprüfte Rückübernahme sind kein abgeschlossener Endbenutzerablauf. |

Siehe [Anforderungsklärung](PROJECT_REQUIREMENT_PORTFOLIO.md#requirement-clarification-workflow),
[Umfang und Fortschritt](PROJECT_REQUIREMENT_PORTFOLIO.md#analysis-scope-and-progress) sowie
[Editor-Anleitung](ARCHITECTURE_EDITOR.md).

Ein fehlender Screenshot-Verweis bedeutet, dass diese Matrix diesen Teil ihres
Vollständigkeitsnachweises nicht bereitstellt. Er darf nicht als abgehakt gelten.
Das folgende Oberflächeninventar ist kein vergleichender Qualitätsbenchmark.

## Endbenutzer-Funktionen (GUI-first)

| Funktion | GUI | REST | Benutzerhandbuch | Screenshot | Hilfe/Tooltip | DE/EN i18n | Status |
|---|:---:|:---:|:---:|:---:|:---:|:---:|---|
| Anforderungsanalyse | ✅ | ✅ | ✅ §4 | ✅ #15–17 | ✅ | ✅ | ✅ Vollständig |
| Bewerteter Baum-Exploration | ✅ | ✅ | ✅ §5 | ✅ #15, 35 | ✅ | ✅ | ✅ Vollständig |
| Ansichtsmodi (6 Modi) | ✅ | ✅ | ✅ §5 | ✅ #5–9, 39, 69 | ✅ | ✅ | ✅ Vollständig |
| Architekturansicht | ✅ | ✅ | ✅ §7 | ✅ #20, 38 | ✅ | ✅ | ✅ Vollständig |
| Relationsvorschläge (annehmen/ablehnen) | ✅ | ✅ | ✅ §9 | ✅ #12, 13, 36 | ✅ | ✅ | ✅ Vollständig |
| Snapshot-gebundene Browser-/SVG-/Vektor-PDF-Ansichten | ✅ | ✅ | ✅ Exportgrenze | ✅ #20, 23 | ✅ | ✅ | ✅ Unterstützte menschenlesbare Ansichten des ausgewählten persistierten Snapshots |
| Mermaid-/JSON-Architekturprojektionen | ✅ | ✅ | ✅ Exportgrenze | ✅ #23, 33 | ✅ | ✅ | ⚠️ Migration in die gemeinsame Snapshot-gebundene Artefakthülle, Autoritäts-Header und Verlustmanifest bleibt in #966 offen |
| ArchiMate-3.1-Exportteilmenge | ✅ | ✅ | ✅ Exportgrenze | ✅ #23, 33 | ✅ | ✅ | ⚠️ Experimentelle begrenzte Teilmenge; versioniertes Mapping-/Verlustprofil umgesetzt; Abnahme durch unabhängige Werkzeuge bleibt in #967 offen |
| Visio-2012-VSDX-Exportteilmenge | ✅ | ✅ | ✅ Exportgrenze | ✅ #23, 33 | ✅ | ✅ | ⚠️ Experimentelle begrenzte Teilmenge; typisierte Identitäten, freigegebene Metadaten und versionierte Verlustmanifeste sind umgesetzt; Microsoft-Visio-Desktop-Zertifizierung bleibt in #965 offen |
| Volltextsuche | ✅ | ✅ | ✅ §11 | ✅ #29 | ✅ | ✅ | ✅ Vollständig |
| Semantische/Hybridsuche | ✅ | ✅ | ✅ §11 | ✅ #30, 31 | ✅ | ✅ | ✅ Vollständig |
| Graphexploration (Upstream/Downstream) | ✅ | ✅ | ✅ §8 | ✅ #11, 21, 37 | ✅ | ✅ | ✅ Vollständig |
| Ausfallauswirkungsanalyse | ✅ | ✅ | ✅ §8 | ✅ #22 | ✅ | ✅ | ✅ Vollständig |
| Lückenanalyse | ✅ | ✅ | ✅ §11d | ✅ #26, 27 | ✅ | ✅ | ✅ Vollständig |
| Mustererkennung | ✅ | ✅ | ✅ §11f | ✅ | ✅ | ✅ | ✅ Vollständig |
| Empfehlungen (Copilot) | ✅ | ✅ | ✅ §4 | ✅ | ✅ | ✅ | ✅ Vollständig |
| Berichte (MD/HTML/DOCX) | ✅ | ✅ | ✅ §10a | ✅ #23 | ✅ | ✅ | ✅ Vollständig |
| Workspace-Verwaltung | ✅ | ✅ | ✅ §13 | ✅ #43–45 | ✅ | ✅ | ✅ Vollständig |
| Branch-Vergleich | ✅ | ✅ | ✅ §12 | ✅ #48 | ✅ | ✅ | ✅ Vollständig |
| Kontexttransfer (zurückkopieren) | ✅ | ✅ | ✅ §12 | ✅ #49 | ✅ | ✅ | ✅ Vollständig |
| Varianten-Erstellung | ✅ | ✅ | ✅ §12 | ✅ #46, 47 | ✅ | ✅ | ✅ Vollständig |
| Merge (mit Konfliktlösung) | ✅ | ✅ | ✅ §12 | ✅ #52, 53, 58, 60, 61 | ✅ | ✅ | ✅ Vollständig |
| Cherry-Pick (mit Konfliktlösung) | ✅ | ✅ | ✅ §12 | ✅ #54, 59, 62 | ✅ | ✅ | ✅ Vollständig |
| Branch löschen | ✅ | ✅ | ✅ §12 | ✅ #57 | ✅ | ✅ | ✅ Vollständig |
| DSL-Editor (Syntax-Highlighting, Autovervollständigung) | ✅ | ✅ | ✅ §11g | ✅ #34, 40 | ✅ | ✅ | ✅ Vollständig |
| Versionsverlauf (Commits) | ✅ | ✅ | ✅ §12 | ✅ #41, 66, 67, 68 | ✅ | ✅ | ✅ Vollständig |
| Sync vom Shared / Veröffentlichen | ✅ | ✅ | ✅ §12 | ✅ #55, 56, 63–65 | ✅ | ✅ | ✅ Vollständig |
| Blattknoten-Begründung | ✅ | ✅ | ✅ §6 | ✅ #18 | ✅ | ✅ | ✅ Vollständig |
| Dokumentimport (PDF/DOCX) | ✅ | ✅ | ✅ DOCUMENT_IMPORT | — | ✅ | ✅ | ⚠️ Implementiert; Screenshot-Nachweis hier nicht verknüpft |
| Quell-Provenienz-Tracking | ✅ | ✅ | ✅ DOCUMENT_IMPORT | — | ✅ | ✅ | ⚠️ Implementiert; Screenshot-Nachweis hier nicht verknüpft |
| KI-gestützte Anforderungsextraktion | ✅ | ✅ | ✅ DOCUMENT_IMPORT | — | ✅ | ✅ | ⚠️ Implementiert; Screenshot-Nachweis hier nicht verknüpft |
| Vorschriften-Architektur-Zuordnung | ✅ | ✅ | ✅ DOCUMENT_IMPORT | — | ✅ | ✅ | ⚠️ Implementiert; Screenshot-Nachweis hier nicht verknüpft |

## Unterstützungsgrenze der Architekturexporte

Diese Grenze ist bewusst enger als die Menge der Dateiendungen, die die Anwendung
erzeugen kann.

### Exportautorität

Die schreibgeschützte Architektur-Workbench serialisiert den ausdrücklich
ausgewählten persistierten Architektur-Snapshot. Vor der Serialisierung wird der
Snapshot für das angeforderte Projekt, den Workspace, Branch und Commit
autorisiert. Das Herunterladen eines vorhandenen Snapshots ruft das LLM nicht auf,
wählt kein neueres Ergebnis und baut den Graphen nicht mit aktuellen Einstellungen
neu auf.

Browseransicht und Snapshot-gebundene Downloadpfade beschreiben damit dasselbe
geprüfte Ergebnis. Exportformate können dennoch Semantik auslassen oder
transformieren.

### Nachweise je Format

| Ausgabe | Unterstützungsgrenze in 1.4.0 | Bereits vorhandene Nachweise | Nicht beanspruchte Nachweise |
|---|---|---|---|
| Browseransicht, SVG und Vektor-PDF | Unterstützte menschenlesbare Ansichten des ausgewählten persistierten Snapshots | Eine neutrale serverseitige Diagrammszene und Snapshot-gebundenes Rendering | Allgemeiner Modellaustausch oder Bearbeitbarkeit in einem externen Architekturwerkzeug |
| Mermaid- und JSON-Architekturprojektionen | Verfügbare zweckgebundene Text-/Datenprojektionen | Vorhandene begrenzte Serialisierer | Die Migration in die gemeinsame Snapshot-gebundene Artefakthülle, exakte Autoritäts-Header und ein vollständiges formatübergreifendes Verlustmanifest bleiben in #966 offen; ohne Endpunktnachweis darf keine Snapshot-Gleichheit angenommen werden |
| **ArchiMate 3.1** | **Experimentelle begrenzte ArchiMate-3.1-Teilmenge** | Jede erzeugte Datei wird offline gegen den festgeschriebenen ArchiMate-3.1-XSD-Satz validiert; das Workbench-Paket enthält den ausgewählten Snapshot, das Mapping-Profil und ein Verlustmanifest | **Interoperabilität mit unabhängigen Werkzeugen ist nicht zertifiziert**; das deklarierte Taxonomy-Profil erhält stabile Identitäten, typisierte Eigenschaften und Ansichten im semantischen Roundtrip; Abnahmen in Archi und einem unabhängigen Werkzeug stehen aus |
| **Visio 2012 VSDX** | **Experimentelle begrenzte Visio-2012-Teilmenge** | Deterministische OPC-/VSDX-Paketstruktur, Beziehungs- und Content-Type-Prüfungen, masterlose Konnektorgeometrie, XMLBeans-Validierung und technisches Laden und Rendern mit Apache POI; der Workbench-Download ist an den ausgewählten Snapshot gebunden | **Microsoft-Visio-Desktop-Zertifizierung ausstehend**: Öffnen, Bearbeiten, Speichern und erneutes Öffnen in einer dokumentierten Microsoft-Visio-Desktopversion sind nicht zertifiziert; das versionierte Übergabe-/Verlustmanifest und stabile typisierte Shape-Daten sind enthalten |

### Produktweit erforderliche Formulierungen

Diese Beschreibungen sind einheitlich zu verwenden:

- **Experimentelle begrenzte ArchiMate-3.1-Teilmenge – Mapping-Profil und Verlustmanifest enthalten; Fremdwerkzeug-Abnahme ausstehend.**
- **Experimentelle begrenzte Visio-2012-Teilmenge – Microsoft-Visio-Desktop-Zertifizierung ausstehend.**
- **Snapshot-gebundener Export – der Download ruft das LLM nicht auf.**

Bis #965 und #967 abgeschlossen sind, dürfen öffentliche Dokumentation und UI
keine allgemeine Visio-2013+-Kompatibilität, keinen produktionsreifen editierbaren
VSDX-Handoff, keine allgemeine ArchiMate-Standardzertifizierung, keinen verlustfreien
semantischen Roundtrip und keine Importzusage für benannte Drittwerkzeuge behaupten.
#964 verantwortet den Dokumentationsabgleich auf dem eingefrorenen SHA; #966
verantwortet die gemeinsame Artefakthülle und Autoritätsnachweise.

## Admin-/Automatisierungsfunktionen (API-first — keine GUI erforderlich)

| Funktion | REST | API-Doku | Status |
|---|:---:|:---:|---|
| Benutzerverwaltung (CRUD) | ✅ | ✅ | ✅ Vollständig |
| LLM-Diagnose | ✅ | ✅ | ✅ Vollständig |
| Embedding-Status | ✅ | ✅ | ✅ Vollständig |
| Start-Status | ✅ | ✅ | ✅ Vollständig |
| Workspace-Eviction (Admin) | ✅ | ✅ | ✅ Vollständig |

## Legende

| Symbol | Bedeutung |
|---|---|
| ✅ | Vollständig implementiert und dokumentiert |
| ⚠️ | Teilweise — Verifizierung oder Vervollständigung erforderlich |
| 🔴 | Signifikante Lücke — GUI existiert möglicherweise, aber Doku/Hilfe/Screenshot fehlt, oder nur REST |
| ❓ | Unbekannt — Audit erforderlich |

## Screenshot-Index

Screenshots werden automatisch von `ScreenshotGeneratorIT` generiert und in `docs/images/` gespeichert.
Referenzformat: `#NN` = `docs/images/NN-*.png`. Benutzerhandbuch-Abschnittsreferenzen: `§N` = USER_GUIDE.md Abschnitt N.
