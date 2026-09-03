# Funktionsvollständigkeits-Matrix

Diese Matrix verfolgt den Lieferstatus aller wesentlichen Produktfunktionen.
Eine Funktion gilt nur als abgeschlossen, wenn alle erforderlichen Spalten ✅ zeigen.

> Siehe [Definition of Done](DEVELOPER_GUIDE.md#definition-of-done--benutzer-sichtbare-funktionen)
> für die Produktregeln.
>
> Der Status der Architekturexporte wird durch die nachstehend dokumentierte
> Unterstützungsgrenze festgelegt. Dateierzeugung und breite Interoperabilität mit
> Drittwerkzeugen werden getrennt bewertet.

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
| ArchiMate-3.1-Exportteilmenge | ✅ | ✅ | ✅ Exportgrenze | ✅ #23, 33 | ✅ | ✅ | ⚠️ Experimentelle begrenzte Teilmenge; Mapping-/Verlustprofil und Interoperabilität mit unabhängigen Werkzeugen bleiben in #967 offen |
| Visio-2012-VSDX-Exportteilmenge | ✅ | ✅ | ✅ Exportgrenze | ✅ #23, 33 | ✅ | ✅ | ⚠️ Experimentelle begrenzte Teilmenge; Microsoft-Visio-Desktop-Zertifizierung und vollständige Übergabe-/Verlustnachweise bleiben in #965 offen |
| Volltextsuche | ✅ | ✅ | ✅ §11a | ✅ #29 | ✅ | ✅ | ✅ Vollständig |
| Semantische/Hybridsuche | ✅ | ✅ | ✅ §11b, §11c | ✅ #30, 31 | ✅ | ✅ | ✅ Vollständig |
| Graphexploration (Upstream/Downstream) | ✅ | ✅ | ✅ §8 | ✅ #11, 21, 37 | ✅ | ✅ | ✅ Vollständig |
| Ausfallauswirkungsanalyse | ✅ | ✅ | ✅ §8 | ✅ #22 | ✅ | ✅ | ✅ Vollständig |
| Lückenanalyse | ✅ | ✅ | ✅ §11e | ✅ #26, 27 | ✅ | ✅ | ✅ Vollständig |
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
| Dokumentimport (PDF/DOCX) | ✅ | ✅ | ✅ DOCUMENT_IMPORT | — | ✅ | ✅ | ✅ Vollständig |
| Quell-Provenienz-Tracking | ✅ | ✅ | ✅ DOCUMENT_IMPORT | — | ✅ | ✅ | ✅ Vollständig |
| KI-gestützte Anforderungsextraktion | ✅ | ✅ | ✅ DOCUMENT_IMPORT | — | ✅ | ✅ | ✅ Vollständig |
| Vorschriften-Architektur-Zuordnung | ✅ | ✅ | ✅ DOCUMENT_IMPORT | — | ✅ | ✅ | ✅ Vollständig |

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
| **ArchiMate 3.1** | **Experimentelle begrenzte ArchiMate-3.1-Teilmenge** | Repräsentative Ausgabe wird offline gegen den festgeschriebenen ArchiMate-3.1-XSD-Satz validiert; der Workbench-Download ist an den ausgewählten Snapshot gebunden | **Interoperabilität mit unabhängigen Werkzeugen ist nicht zertifiziert**; ein versioniertes Mapping- und Verlustprofil, stabile externe Identitäten/Eigenschaften und ein semantischer Roundtrip stehen aus |
| **Visio 2012 VSDX** | **Experimentelle begrenzte Visio-2012-Teilmenge** | Deterministische OPC-/VSDX-Paketstruktur, Beziehungs- und Content-Type-Prüfungen, masterlose Konnektorgeometrie, XMLBeans-Validierung und technisches Laden mit Apache POI; der Workbench-Download ist an den ausgewählten Snapshot gebunden | **Microsoft-Visio-Desktop-Zertifizierung ausstehend**: Öffnen, Bearbeiten, Speichern und erneutes Öffnen in einer dokumentierten Microsoft-Visio-Desktopversion sind nicht zertifiziert; ein vollständiges Übergabe- und Verlustmanifest steht ebenfalls aus |

### Produktweit erforderliche Formulierungen

Diese Beschreibungen sind einheitlich zu verwenden:

- **Experimentelle begrenzte ArchiMate-3.1-Teilmenge – Mapping- und Verlustmanifest ausstehend.**
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
