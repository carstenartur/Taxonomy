# Framework-Import

Die Framework-Import-Pipeline ermöglicht es Ihnen, Architekturmodelle aus externen Frameworks in den Taxonomy Architecture Analyzer zu importieren. Importierte Elemente und Beziehungen werden in das kanonische Taxonomie-Datenmodell konvertiert und als Architecture-DSL-Dokumente mit vollständiger Versionskontrolle gespeichert.

## Inhaltsverzeichnis

- [Überblick](#überblick)
- [Unterstützte Frameworks](#unterstützte-frameworks)
- [Import-Workflow](#import-workflow)
- [UAF-/DoDAF-Import](#uaf-dodaf-import)
- [APQC Process Classification Framework](#apqc-process-classification-framework)
- [C4-/Structurizr-Import](#c4-structurizr-import)
- [Zuordnungsprofile](#zuordnungsprofile)
- [Vorschaumodus](#vorschaumodus)
- [REST-API-Endpunkte](#rest-api-endpunkte)
- [Fehlerbehebung](#fehlerbehebung)
- [Verwandte Dokumentation](#verwandte-dokumentation)

---

## Überblick

Die Import-Pipeline transformiert externe Architekturartefakte in die interne Darstellung der Taxonomie durch einen mehrstufigen Prozess:

```
Datei-Upload  →  ExternalParser  →  ExternalModelMapper (Profil)
              →  CanonicalArchitectureModel  →  DslSerializer
              →  DslMaterializeService  →  Datenbank (TaxonomyRelation / RelationHypothesis)
```

Jedes unterstützte Framework verfügt über einen **Parser**, der das native Dateiformat liest, und ein **Zuordnungsprofil**, das externe Elementtypen und Beziehungstypen den Taxonomie-Stammcodes und Beziehungstypen zuordnet.

---

## Unterstützte Frameworks

| Profil-ID | Framework | Dateiformat | Parser |
|---|---|---|---|
| `uaf` | UAF / DoDAF (XMI) | XML | `UafXmlParser` |
| `apqc` | APQC PCF | CSV | `ApqcCsvParser` |
| `apqc-excel` | APQC PCF | XLSX | `ApqcExcelParser` |
| `c4` | C4 / Structurizr | DSL | `StructurizrDslParser` |

Verwenden Sie `GET /api/import/profiles`, um alle verfügbaren Profile zur Laufzeit aufzulisten.

---

## Import-Workflow

1. **Profil auswählen** — Wählen Sie das Framework, das zu Ihrer Quelldatei passt.
2. **Vorschau** — Laden Sie die Datei zum Vorschau-Endpunkt hoch, um Statistiken (Elementanzahl, Beziehungsanzahl, zugeordnete Typen) zu sehen, ohne in die Datenbank zu schreiben.
3. **Importieren** — Bestätigen Sie und führen Sie den vollständigen Import durch. Die Pipeline parst die Datei, ordnet Elemente und Beziehungen zu, serialisiert sie als DSL und materialisiert das Ergebnis in die Datenbank.
4. **Überprüfen** — Importierte Beziehungen erscheinen im Graph Explorer, im Panel „Relation Proposals" und in der Architekturansicht. Importierte Elemente tragen ein `x-source-framework`-Erweiterungsattribut zur Nachverfolgbarkeit.

---

## UAF-/DoDAF-Import

**Dateiformat:** XMI / XML

Der UAF-Parser liest `<packagedElement>`- und `<ownedElement>`-Tags aus XMI-Exporten. Beziehungen werden aus `<ownedConnector>`- und `<connector>`-Tags mit `source`- und `target`-Attributen abgeleitet.

### Elementtyp-Zuordnung

| UAF-Typ | Taxonomie-Stammcode |
|---|---|
| Capability | CP (Capability) |
| OperationalActivity | BP (Business Process) |
| ServiceFunction | CR (Capability Requirement) |
| CapabilityConfiguration | CI (Configuration Item) |
| CommunicationsFunction | CO (Communications Service) |
| System, Platform | UA (User Application) |
| Performer, Organization, ResourcePerformer | BR (Business Role) |
| InformationElement | IP (Information Product) |

### Beziehungstyp-Zuordnung

| UAF-Beziehung | Taxonomie-Beziehung |
|---|---|
| Implements | REALIZES |
| Supports | SUPPORTS |
| Consumes | CONSUMES |
| Uses | USES |
| Provides | FULFILLS |
| IsAssignedTo | ASSIGNED_TO |
| DependsOn | DEPENDS_ON |
| Produces | PRODUCES |
| CommunicatesWith | COMMUNICATES_WITH |

**Sicherheit:** Der XML-Parser verfügt über aktivierten XXE-Schutz (XML External Entity), um Injection-Angriffe zu verhindern.

---

## APQC Process Classification Framework

**Dateiformate:** CSV oder XLSX (Excel)

Der APQC-Parser liest die Hierarchie des Process Classification Framework. Jede Zeile repräsentiert ein Prozesselement auf einer bestimmten Ebene. Eltern-Kind-Beziehungen werden automatisch aus der PCF-ID-Hierarchie abgeleitet (z. B. ist `1.1` ein Kind von `1.0`).

### Erwartete Spalten

| Spalte | Beschreibung |
|---|---|
| PCF ID | Eindeutiger Bezeichner (z. B. `1.0`, `1.1`, `1.1.1`) |
| Name | Prozessname |
| Level | Hierarchietiefe (1–5) |
| Description | Optionale Prozessbeschreibung |

Die Spaltenerkennung ist flexibel — der Parser gleicht Überschriften nach Schlüsselwort ab.

### Ebenen-Zuordnung

| APQC-Ebene | Taxonomie-Stammcode |
|---|---|
| Ebene 1 (Kategorie) | CP (Capability) |
| Ebene 2 (Prozessgruppe) | BP (Business Process) |
| Ebene 3 (Prozess) | CR (Capability Requirement) |
| Ebene 4 (Aktivität) | CI (Configuration Item) |
| Ebene 5 (Aufgabe) | BR (Business Role) |

### Beziehungstyp-Zuordnung

| APQC-Beziehung | Taxonomie-Beziehung |
|---|---|
| ParentChild | RELATED_TO |
| Enables | SUPPORTS |
| Consumes | CONSUMES |
| Produces | PRODUCES |

---

## C4-/Structurizr-Import

**Dateiformat:** Structurizr DSL (`.dsl`)

Der Structurizr-Parser verwendet Regex-basierte Extraktion für Element- und Beziehungsdefinitionen.

### Element-Syntax

```
identifier = elementType "name" ["description"] ["technology"]
```

### Unterstützte Elementtypen

| C4-Typ | Taxonomie-Stammcode |
|---|---|
| Person | BR (Business Role) |
| SoftwareSystem | SY (System) |
| Container | UA (User Application) |
| Component | CM (Component) |
| DeploymentNode | CO (Communications Service) |
| InfrastructureNode | CO (Communications Service) |
| ContainerInstance | UA (User Application) |

### Beziehungs-Syntax

```
source -> target "description" ["technology"]
```

Container-Verschachtelung wird automatisch verfolgt und erzeugt `CONTAINS`-Beziehungen. Beziehungstypen werden aus Beschreibungs-Schlüsselwörtern abgeleitet (z. B. „depends" → `DEPENDS_ON`, „delivers" → `FULFILLS`).

### Beziehungstyp-Zuordnung

| C4-Beziehung | Taxonomie-Beziehung |
|---|---|
| Uses | USES |
| Delivers | FULFILLS |
| InteractsWith | COMMUNICATES_WITH |
| DependsOn | DEPENDS_ON |
| Contains | CONTAINS |
| Realizes | REALIZES |
| Supports | SUPPORTS |

---

## Zuordnungsprofile

Jedes Framework wird durch ein **Zuordnungsprofil** unterstützt, das Folgendes definiert:

- `profileId()` — Eindeutiger Bezeichner für API-Aufrufe
- `displayName()` — Menschenlesbarer Anzeigename
- `mapElementType(externalType)` — Konvertiert einen externen Elementtyp in einen Taxonomie-Stammcode
- `mapRelationType(externalRelType)` — Konvertiert einen externen Beziehungstyp in einen kanonischen `RelationType`
- `supportedElementTypes()` — Listet alle externen Elementtypen auf, die das Profil verarbeiten kann
- `supportedRelationTypes()` — Listet alle externen Beziehungstypen auf, die das Profil verarbeiten kann

Ein **ArchiMate-Zuordnungsprofil** ist ebenfalls in der Codebasis für den zukünftigen ArchiMate-Modellimport verfügbar und ordnet 10 ArchiMate-Elementtypen und 11 Beziehungstypen zu.

---

## Vorschaumodus

Der Vorschaumodus (`POST /api/import/preview/{profileId}`) führt die vollständige Parse-und-Zuordnungs-Pipeline aus, **schreibt aber nicht in die Datenbank**. Die Antwort enthält:

- Anzahl der geparsten Elemente
- Anzahl der geparsten Beziehungen
- Zugeordnete Elementtypen mit Anzahlen
- Zugeordnete Beziehungstypen mit Anzahlen
- Warnungen für nicht zugeordnete Typen

Verwenden Sie die Vorschau, um Ihre Datei zu validieren, bevor Sie einen vollständigen Import durchführen.

---

## REST-API-Endpunkte

| Methode | Endpunkt | Beschreibung |
|---|---|---|
| `GET` | `/api/import/profiles` | Alle verfügbaren Import-Profile auflisten |
| `POST` | `/api/import/preview/{profileId}` | Import-Ergebnis in der Vorschau anzeigen (Trockenlauf, kein Datenbankschreiben) |
| `POST` | `/api/import/{profileId}` | Vollständigen Import in die Datenbank ausführen |

Der Import-Endpunkt akzeptiert einen `branch`-Abfrageparameter, um den DSL-Branch für den Import festzulegen (Standard ist der aktive Branch).

Alle Endpunkte erfordern Authentifizierung. Siehe [API-Referenz](API_REFERENCE.md) für vollständige Anfrage-/Antwortschemata.

---

## Fehlerbehebung

| Problem | Ursache | Lösung |
|---|---|---|
| Fehler „Unknown profile" | Profil-ID nicht erkannt | Prüfen Sie `GET /api/import/profiles` auf gültige IDs |
| Leeres Vorschauergebnis | Dateiformat-Nichtübereinstimmung | Stellen Sie sicher, dass die Datei dem erwarteten Format des Profils entspricht |
| Fehlende Elemente nach Import | Nicht zugeordnete externe Typen | Prüfen Sie die Vorschau-Warnungen auf nicht zugeordnete Typnamen |
| XML-Parse-Fehler | Fehlerhaftes XMI | Überprüfen Sie die XML-Struktur; prüfen Sie auf Kodierungsprobleme |
| CSV-Parse-Fehler | Falsches Trennzeichen oder Kodierung | Verwenden Sie kommagetrennte Werte mit UTF-8-Kodierung |

---

## Verwandte Dokumentation

- [Benutzerhandbuch](USER_GUIDE.md) — Vollständige Nutzungsanweisungen
- [Konzepte](CONCEPTS.md) — Taxonomie-Knotentypen, Beziehungstypen und das kanonische Modell
- [Architektur](ARCHITECTURE.md) — Systemarchitektur und Modulstruktur
- [API-Referenz](API_REFERENCE.md) — Vollständige REST-API-Dokumentation
- [Git-Integration](GIT_INTEGRATION.md) — Wie importierte DSL-Dokumente versioniert werden
