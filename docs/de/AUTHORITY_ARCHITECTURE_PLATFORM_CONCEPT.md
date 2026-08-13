# Arbeitskonzept: Föderierte Behördenarchitektur- und Kollaborationsplattform

**Status:** Arbeitsstand zur gemeinsamen Planung, noch keine verbindliche Architekturentscheidung  
**Stand:** 12. August 2026  
**Zweck:** Vollständiger werdende Planungsgrundlage vor der Zerlegung in Epics und Einzel-Issues  
**Geltungsbereich:** Taxonomy Architecture Analyzer

> Dieses Dokument ist bewusst breiter als ein einzelnes Feature-Issue. Es sammelt das fachliche und technische Zielbild, offene Entscheidungen, notwendige Machbarkeitsnachweise und Abgrenzungen. Erst wenn die wesentlichen Querschnittsentscheidungen ausreichend geklärt sind, wird der Inhalt in umsetzbare Issues mit Abhängigkeiten und Abnahmekriterien zerlegt.

---

## 1. Warum dieses Dokument nötig ist

Taxonomy entwickelt sich von einem Werkzeug zur Analyse einer einzelnen Referenztaxonomie zu einer Plattform, die Anforderungen, Referenzwissen, Organisations- und Architekturdaten, Lösungsentscheidungen sowie deren Entwicklung über lange Zeiträume zusammenführen kann.

Die bisherigen Erweiterungen zeigen, dass die nächsten Schritte nicht unabhängig voneinander geplant werden können. Insbesondere greifen folgende Themen ineinander:

- mehrere zentrale Architektur-Repositories und persönliche Arbeitsbereiche;
- behördenweite, ressortweite und föderierte Architekturmodelle;
- generalisierte Import- und Synchronisationsmechanismen;
- Anforderungs-, Projekt-, Produkt- und Lösungsportfolios;
- eine echte strukturierte und grafische Editorplattform;
- kollaborative Bearbeitung mit persönlichem oder gemeinsamem Undo/Redo;
- semantische Diffs, Konflikte, Reviews, Freigaben und Baseline-Integration;
- Skalierung auf sehr große, paketierte und nur ausschnittsweise geladene Modelle;
- Mandantentrennung, Berechtigungen, Schutzbedarf und Auditierbarkeit.

Eine sofortige Aufteilung in viele Einzel-Issues würde das Risiko erhöhen, lokale Lösungen zu implementieren, bevor die gemeinsamen Invarianten feststehen. Dieses Konzeptpapier dient daher als versionierter Planungsanker.

### 1.1 Statusbegriffe

In diesem Dokument werden vier Reifegrade verwendet:

| Kennzeichnung | Bedeutung |
|---|---|
| **Fundament vorhanden** | Im aktuellen Repository bereits erkennbar oder implementiert |
| **Vorgeschlagene Richtung** | Derzeit bevorzugtes Zielbild, aber noch nicht als ADR festgeschrieben |
| **Offene Entscheidung** | Vor der Issue-Zerlegung oder spätestens vor der Implementierung zu entscheiden |
| **Spike erforderlich** | Durch einen begrenzten technischen Machbarkeitsnachweis zu klären |

Dieses Dokument ersetzt keine späteren ADRs. Es benennt vielmehr die Entscheidungen, für die ADRs oder Spikes notwendig werden.

---

## 2. Bestehendes Fundament und erkannte Lücken

### 2.1 Bereits vorhandene Grundlagen

Taxonomy verfügt bereits über wichtige Bausteine:

- eine textuelle, deterministisch verarbeitbare Architektur-DSL;
- JGit-basierte Versionierung mit Branches, History, Diff, Merge, Cherry-Pick und Revert;
- logisch getrennte Repository- und Workspace-Ansätze auf Basis von `jgit-storage-hibernate`;
- ein kanonisches Architekturmodell mit Elementen, Beziehungen, Anforderungen, Zuordnungen, Sichten, Nachweisen und Quellen;
- Hibernate- und Hibernate-Search-Projektionen;
- einen CodeMirror-basierten DSL-Editor mit Validierung, Formatierung, Commit und Materialisierung;
- Framework-Importprofile mit Parser-, Mapping- und Materialisierungsgrenzen;
- Anforderungen, Analyseergebnisse, Beziehungshypothesen und menschliche Bestätigung;
- semantische Vergleichs- und Konfliktfunktionen in mehreren Git-Workflows;
- dokumentierte Ansätze für mehrere zentrale Repositories und persönliche Arbeitskopien in [Issue #609](https://github.com/carstenartur/Taxonomy/issues/609).

Relevante Implementierungsstellen sind insbesondere:

- [`taxonomy-dsl-editor.js`](../../taxonomy-app/src/main/resources/static/js/shared/taxonomy-dsl-editor.js)
- [`CanonicalArchitectureModel.java`](../../taxonomy-dsl/src/main/java/com/taxonomy/dsl/model/CanonicalArchitectureModel.java)
- [`ExternalParser.java`](../../taxonomy-app/src/main/java/com/taxonomy/catalog/service/importer/ExternalParser.java)
- [`FrameworkImportService.java`](../../taxonomy-app/src/main/java/com/taxonomy/catalog/service/importer/FrameworkImportService.java)
- [`ExternalModelMapper.java`](../../taxonomy-dsl/src/main/java/com/taxonomy/dsl/mapping/ExternalModelMapper.java)
- [Repository-Topologie](REPOSITORY_TOPOLOGY.md)

### 2.2 Wesentliche Lücken

Der aktuelle DSL-Editor bearbeitet im Kern vollständigen Text und erzeugt daraus Commits oder Materialisierungen. Er ist damit ein wertvolles Expertenwerkzeug, aber noch keine allgemeine Architektur-Editorplattform.

Es fehlen insbesondere:

- ein einheitliches semantisches Befehlsmodell für Änderungen aus Text-, Baum-, Tabellen-, Matrix- und Graphansichten;
- ein dauerhafter, serverseitiger Operationsverlauf zwischen Git-Checkpoints;
- persönliche und gemeinsame Undo-/Redo-Regeln in Mehrbenutzersitzungen;
- echte Live-Kollaboration mit Revisionen, Wiederanlauf, Ereignisreihenfolge und Konfliktvorschau;
- paketierte Modelle und partielle Lade-/Bearbeitungsstrategien für sehr große Architekturen;
- ein durchgängiger Änderungsantrags- und Freigabeprozess gegen eine Behörden-Baseline;
- idempotente Stammdaten-Synchronisation statt ausschließlich dateibasierter Einmalimporte;
- eine klare Trennung zwischen Referenzkatalog, Behördenstammdaten, akzeptierter Architektur und Änderungsvorschlägen;
- stabile externe und interne Identitäten über Repositories, Quellen und Reorganisationen hinweg;
- ein konsistentes Modell für repositoryübergreifende Referenzen und versionierte Abhängigkeiten.

---

## 3. Fachliches Zielbild

### 3.1 Fünf logisch getrennte Schichten

Die Plattform sollte mindestens fünf fachliche Schichten unterscheiden:

```text
1. Reference Knowledge
   C3, FIM/XZuFi, APQC, Standards, Prinzipien, Produkt- und Technologiekataloge

2. Authority Master Data
   Organisationseinheiten, Verwaltungsleistungen, Rollen, Standorte,
   vorhandene Systeme, Verträge und Verantwortlichkeiten

3. Architecture Baselines
   akzeptierte Ist-, Übergangs- und Zielarchitekturen

4. Requirements and Change
   Anforderungen, Analysesnapshots, Projekte, Varianten,
   Lösungsvorschläge und Architecture Change Sets

5. Views and Evidence
   aufgabenspezifische Sichten, Diagramme, Matrizen, Roadmaps,
   Reviews, Begründungen, Nachweise und Berichte
```

Diese Schichten dürfen miteinander verknüpft werden, sollen aber nicht durch ein einziges untypisiertes Knotenmodell vermischt werden.

Beispiele:

- Eine C3-Capability ist ein Referenzobjekt.
- Eine behördenspezifische Fähigkeit kann durch diese Capability klassifiziert werden.
- Eine Organisationseinheit besitzt oder erbringt diese behördenspezifische Fähigkeit.
- Ein System realisiert einen Teil davon.
- Eine Anforderung schlägt eine Änderung an System und Prozess vor.
- Eine Architekturansicht zeigt nur den für eine Entscheidung relevanten Ausschnitt.

### 3.2 Eine logisch zentrale, aber föderierte Behördenarchitektur

**Vorgeschlagene Richtung:** Jede Behörde benötigt eine logisch autoritative Architektur-Baseline. Diese Baseline ist kein einziges Diagramm, kein einziges DSL-Dokument und nicht zwingend ein einziges physisches Repository.

Die logische Behördenarchitektur kann aus versionierten Paketen und Repositories bestehen:

```text
Föderale Referenzen
├── gemeinsame Prinzipien
├── Standards und Kataloge
└── gemeinsame Plattformbausteine
        │
        ▼
Ressort- oder Landesreferenzarchitektur
        │
        ▼
Behördenarchitektur
├── Organisation und Leistungen
├── Fachdomäne A
├── Fachdomäne B
├── Querschnittsdienste
├── Daten- und Integrationsarchitektur
├── Infrastruktur und Betrieb
└── sensible Teilarchitekturen
        │
        ▼
Projekt-Workspaces, gemeinsame Sitzungen und Änderungsvorschläge
```

Die Zusammengehörigkeit entsteht durch stabile Identitäten, versionierte Referenzen, gemeinsame Abfrage- und Sichtenlogik sowie geregelte Änderungsprozesse – nicht durch das Laden aller Inhalte in ein einziges Objekt.

### 3.3 Repository-Grenzen

Ein gemeinsames Repository ist sinnvoll, wenn Inhalte dieselben Eigenschaften besitzen:

- fachliche Eigentümerschaft;
- Schutz- und Sichtbarkeitsstufe;
- Freigabegremium;
- Veröffentlichungsrhythmus;
- Lebenszyklus;
- Betriebs- und Änderungsverantwortung.

Getrennte Repositories sind vorzuziehen, wenn sich diese Eigenschaften wesentlich unterscheiden. Besonders strenge Zugriffstrennung sollte eher durch Repository-Grenzen als durch nachträglich ergänzte Feld-ACLs erzwungen werden.

**Offene Entscheidung:** Ob zusätzlich paketbezogene Berechtigungen innerhalb eines Repositorys benötigt werden oder zunächst Repository-Grenzen ausreichen.

---

## 4. Identität, Zeit und Provenienz

### 4.1 Interne Identität

Jedes fachliche Objekt benötigt eine dauerhafte interne Identität, die nicht aus Anzeigename, Hierarchieposition oder einem nur lokal eindeutigen Fachcode abgeleitet wird.

Empfohlene Identitätsdimensionen:

```text
repositoryId
namespaceId oder packageId
objectId
```

Fachcodes bleiben such- und anzeigbare Attribute. Sie dürfen in verschiedenen Katalogen oder Behörden vorkommen.

### 4.2 Externe Identitäten

Ein Objekt kann mehrere externe Bindungen besitzen:

```text
sourceSystem = "ORG-MASTER"
externalId   = "OE-4711"
sourceVersion = "2026-08-01"
```

Weitere Bindungen können etwa aus XZuFi, IAM, CMDB oder einem bestehenden EA-Werkzeug stammen. Eine automatische Gleichsetzung mehrerer externer IDs darf nur bei eindeutiger Regel erfolgen; andernfalls wird eine überprüfbare Identitätshypothese erzeugt.

### 4.3 Fachliche und technische Zeit

Die Plattform muss mindestens zwei Zeitdimensionen unterscheiden:

- **Transaktionszeit:** Wann wurde etwas in Taxonomy aufgezeichnet oder geändert?
- **Fachliche Gültigkeit:** Seit wann beziehungsweise bis wann gilt das Objekt oder die Beziehung in der realen Organisation?

Eine Reorganisation soll daher nicht die frühere Struktur löschen. Sie beendet Gültigkeiten und erzeugt nachvollziehbare Nachfolgerbeziehungen.

---

## 5. Generalisierte Import- und Synchronisationsplattform

### 5.1 Zielarchitektur

Der heutige Framework-Import sollte zu einer allgemeinen Plattform erweitert werden:

```text
Datei, API oder Ereignisquelle
        │
        ▼
Source Adapter / Parser
        │
        ▼
neutrales Import-Staging-Modell
        │
        ▼
Mapping Profile
        │
        ▼
fachspezifisches Importziel
        │
        ▼
Change Set mit Vorschau
        │
        ▼
Prüfung, Freigabe und versionierte Anwendung
```

### 5.2 Getrennte Importziele

Mindestens drei Importziele sind erforderlich:

| Importziel | Beispiele | Charakter |
|---|---|---|
| `REFERENCE_CATALOG` | C3, APQC, Standards | versionierter Referenzbestand |
| `ARCHITECTURE_MODEL` | ArchiMate, UAF, C4, vorhandene Systemlandschaften | konkrete Architekturmodelle |
| `MASTER_DATA` | Organisation, Leistungen, Systeme, Standorte | wiederholte, quellengeführte Synchronisation |

Eine Organisationseinheit darf nicht künstlich als C3-Business-Role oder generischer Taxonomieknoten modelliert werden. Referenzklassifikation, konkrete Organisation und Verantwortungszuweisung bleiben getrennte Konzepte.

### 5.3 Synchronisationssemantik

Unterstützte Modi sollten explizit sein:

```text
SNAPSHOT        vollständiger Stand einer Quelle
DELTA           von der Quelle gelieferte Änderung
REPLACE_SOURCE  ersetze nur den von dieser Quelle verwalteten Anteil
MERGE           ergänze, ohne fremde Quellattribute zu überschreiben
```

Jeder Lauf erzeugt eine Vorschau mit:

- neuen Objekten;
- geänderten Attributen;
- Hierarchieverschiebungen;
- neuen oder entfallenen Beziehungen;
- stillzulegenden Objekten;
- Identitätskonflikten;
- Konflikten mit lokal gepflegten Architekturinformationen.

Ein wiederholter Lauf mit identischem Quellstand muss idempotent sein und darf keine neuen Dubletten oder zeitgestempelten Kopien erzeugen.

---

## 6. Kanonisches Modell, Pakete und Sichten

### 6.1 Modellpakete

Sehr große Architekturen benötigen fachlich geschnittene Pakete. Ein Paket sollte mindestens enthalten:

```text
packageId
repositoryId
namespace
name
owner
lifecycleState
securityClassification
schemaVersion
contentVersion oder commit reference
imports / dependencies
```

Pakete können mehrere DSL-Dokumente enthalten. Ein Git-Commit checkpointet eine konsistente Baumversion, ohne dass alle Inhalte in einer einzigen Datei liegen müssen.

### 6.2 Repositoryübergreifende Referenzen

Externe Referenzen benötigen eine explizite Form, beispielsweise:

```text
repositoryId + packageId + objectId + versionConstraint
```

Mögliche Versionierungsmodi:

- auf einen exakten Commit fixiert;
- auf einen freigegebenen Tag oder Release fixiert;
- einem freigegebenen Versionsbereich folgend;
- bewusst auf den aktuellen Stand eines Referenzzweigs folgend.

**Offene Entscheidung:** Welche Modi für Behörden-Baselines zulässig sind. Reproduzierbare freigegebene Baselines sollten keine unkontrollierten beweglichen Referenzen enthalten.

### 6.3 Sichten statt Gesamtmodell

Die zentrale Architektur ist ein Wissensbestand, kein Gesamtdiagramm. Benutzer bearbeiten und betrachten begrenzte Sichten, beispielsweise:

- eine Verwaltungsleistung und ihre unterstützenden Systeme;
- alle Systeme einer Organisationseinheit;
- eine Projekt- oder Beschaffungsarchitektur;
- einen Datenfluss;
- eine Abhängigkeits- oder Ausfallanalyse;
- eine Übergangsroadmap;
- eine Verantwortungs- oder RACI-Matrix.

Serverseitige Abfragen, Paging, Tiefenbegrenzungen, Filter und Suchindizes sind Teil des Kernmodells, nicht nachträgliche Optimierungen.

---

## 7. Zielbild der Editorplattform

### 7.1 Ein Modell, mehrere Editoren

Taxonomy sollte keine voneinander unabhängigen Editoren mit eigenen Datenmodellen erhalten. Alle Bearbeitungsoberflächen müssen dieselben semantischen Befehle gegen dieselbe kanonische Projektion verwenden.

Vorgesehene Oberflächen:

| Oberfläche | Typische Aufgaben |
|---|---|
| Text-/DSL-Editor | Expertenbearbeitung, Bulk-Änderungen, Review lesbarer Quellen |
| Grapheditor | Elemente und Beziehungen erzeugen, verbinden, gruppieren und anordnen |
| Baum-/Hierarchieeditor | Organisationen, Taxonomien und Paketstrukturen pflegen |
| Formular-/Property-Editor | typisierte Eigenschaften, Gültigkeit und Verantwortlichkeiten bearbeiten |
| Tabelleneditor | Massenpflege, Filterung, Kopieren und Einfügen |
| Matrixeditor | Beziehungen zwischen zwei Dimensionen pflegen, etwa Organisation × System |
| Sichteneditor | Filter, Layout, Hervorhebungen und Viewpoints definieren |
| Import-Mapping-Editor | Quellfelder, Typen, Identitäten und Konfliktregeln konfigurieren |
| Diff-/Review-Editor | Änderungsvorschläge prüfen, kommentieren und teilweise übernehmen |

Die Auswahl eines Objekts sollte zwischen den Sichten synchronisiert werden. Eine Graphänderung muss in Text, Tabelle, History und Diff dieselbe fachliche Operation darstellen.

### 7.2 Semantisches Befehlsmodell

Beispiele für fachliche Befehle:

```text
CreateElement
UpdateElementProperty
MoveElementToPackage
CreateRelation
DeleteRelation
RetireElement
ReparentOrganizationUnit
AssignResponsibility
CreateView
UpdateViewDefinition
ApplyImportChangeSet
AcceptRequirementMapping
```

Befehle können zu einer atomaren Gruppe zusammengefasst werden, beispielsweise beim Einfügen eines Teilgraphen oder Anwenden eines Imports. Eine solche Gruppe wird gemeinsam validiert, angenommen oder abgelehnt und als eine verständliche Benutzeraktion angezeigt.

### 7.3 Lokaler UI-Zustand und kanonischer Zustand

Nicht jeder UI-Zustand gehört in die Architektur:

**Lokal oder nicht-semantisch:**

- Cursorposition;
- geöffnete Panels;
- vorläufige Auswahl;
- Zoom und Viewport;
- noch nicht bestätigte Formulareingabe;
- persönliche, nicht veröffentlichte Layoutpräferenz.

**Semantisch und serverseitig:**

- Elemente, Beziehungen und Eigenschaften;
- fachlich relevante Positionen in veröffentlichten Diagrammen;
- Sichtsdefinitionen;
- Anforderungen und Zuordnungen;
- Verantwortlichkeiten;
- Importentscheidungen;
- freigegebene Layouts, wenn sie Bestandteil einer Architektursicht sind.

Der Browser darf nicht zur alleinigen Quelle kanonischer Architektursemantik werden.

### 7.4 Besonderheit des DSL-Editors

Ein Texteditor erzeugt während des Tippens viele syntaktisch unvollständige Zwischenstände. Deshalb soll nicht jeder Tastendruck als fachliche Operation persistiert werden.

Vorgeschlagene Trennung:

1. CodeMirror verwaltet einen lokalen Textpuffer und lokales Tipp-Undo.
2. „Semantische Änderungen anwenden“ parst den Puffer gegen eine bekannte Basisrevision.
3. Das System berechnet einen semantischen Änderungssatz.
4. Der Benutzer sieht bei größeren Änderungen eine Vorschau.
5. Der Änderungssatz wird als atomare Operationsgruppe angenommen oder wegen Konflikten abgelehnt.

**Offene Entscheidung:** Ob gemeinsames gleichzeitiges Rohtext-Editing überhaupt angeboten wird. Für die erste kollaborative Ausbaustufe ist ein exklusiver Text-Bearbeitungsmodus oder ein diff-basiertes Anwenden voraussichtlich sicherer als ein kanonisches CRDT-Dokument.

---

## 8. Drei unterschiedliche Arten von Undo und Wiederherstellung

Der Begriff „Undo“ darf nicht mehrere technisch und fachlich unterschiedliche Mechanismen vermischen.

### 8.1 Lokales UI-Undo

Gilt nur für noch nicht semantisch angenommene lokale Interaktionen, beispielsweise Tippen im Textpuffer oder Verschieben eines unveröffentlichten Viewports. Es ist weder auditierbar noch kollaborativ relevant.

### 8.2 Dauerhaftes semantisches Undo/Redo

Gilt für bereits serverseitig angenommene Bearbeitungsoperationen in einem Workspace oder einer gemeinsamen Sitzung. Undo und Redo erzeugen neue semantische Operationen. Frühere akzeptierte Operationen werden nicht gelöscht und die Git-History wird nicht umgeschrieben.

### 8.3 Baseline-Revert und Versionswiederherstellung

Gilt für veröffentlichte Checkpoints, Commits oder freigegebene Baselines. Hier werden Git-basierte Revert-, Restore-, Cherry-Pick- oder Änderungsantragsmechanismen verwendet. Dies ist kein Ersatz für das schnelle Editor-Undo.

```text
lokale Geste
    -> optional lokales UI-Undo
    -> serverseitig akzeptierte semantische Operation
    -> semantisches Undo/Redo als neue Operation
    -> expliziter Git-Checkpoint
    -> Review und Integration
    -> Baseline-Revert als kontrollierte Architekturänderung
```

---

## 9. Kollaborationsmodi

Die in `audio-analyzer` erprobte Trennung ist eine gute Grundlage, muss aber für große föderierte Architekturmodelle erweitert werden.

### 9.1 `PRIVATE_WORKSPACE`

- Nur der Besitzer beziehungsweise berechtigte Workspace-Teilnehmer sieht die Änderungen.
- Semantisches Undo/Redo ist persönlich.
- Veröffentlichung oder Integration in eine Baseline erfolgt explizit.
- Dies bleibt der sichere Standard für umfangreiche Architekturänderungen.

### 9.2 `SHARED_SESSION_PERSONAL_UNDO`

- Teilnehmer sehen dieselbe aktuelle Projektion.
- Jeder Benutzer kann nur die eigene letzte noch zulässige Operation zurücknehmen.
- Spätere abhängige Operationen können das Undo blockieren.
- Dies ist der bevorzugte Standard für Live-Kollaboration.

### 9.3 `SHARED_SESSION_SHARED_UNDO`

- Berechtigte Teilnehmer können eine Operation eines anderen Teilnehmers zurücknehmen.
- Das Ziel wird niemals implizit als „letzte beliebige Operation“ gewählt.
- Vor der Ausführung sind unveränderliche Vorschau, betroffener Autor, Auswirkungen und Blocker anzuzeigen.
- Eine veraltete Vorschau wird bei jeder zwischenzeitlichen semantischen Änderung ungültig.

### 9.4 Review- und Moderationsmodus

Für Behördenprozesse kann zusätzlich ein Modus sinnvoll sein, in dem viele Teilnehmer kommentieren und Vorschläge erzeugen, aber nur Moderatoren semantische Änderungen annehmen.

**Offene Entscheidung:** Eigener Sitzungsmodus oder Berechtigungspolitik innerhalb eines bestehenden Modus.

### 9.5 Unveränderlicher Sitzungsrahmen

Eine gemeinsame Sitzung sollte für ihre Lebensdauer fest an Folgendes gebunden sein:

```text
repositoryId
workspaceId oder central proposal context
branch
baseCommit
package/view scope
collaborationMode
membership and permissions
```

Ein Wechsel dieser Grundlagen erzeugt eine neue Sitzung statt einer stillen Umdeutung des Operationsverlaufs.

---

## 10. Dauerhafter semantischer Operationsverlauf

### 10.1 Grundsatz

**Vorgeschlagene Richtung:** Git bleibt der Speicher für nachvollziehbare Architektur-Checkpoints und Baselines. Der schnelle kollaborative Bearbeitungsverlauf wird zusätzlich als dauerhafter semantischer Operationsstrom gespeichert.

Ein akzeptierter Befehl folgt ungefähr diesem Ablauf:

```text
client command
    -> Authentisierung und Berechtigungsprüfung
    -> Repository-, Workspace-, Sitzungs- und Revisionsprüfung
    -> semantische Validierung
    -> Konflikt- und Auswirkungsanalyse
    -> atomare dauerhafte Operationsanlage
    -> Aktualisierung der kanonischen Projektion
    -> Revision und Ereignisfolge erhöhen
    -> Outbox-Ereignis schreiben
    -> Commit der Datenbanktransaktion
    -> geordnete Verteilung an verbundene Clients
```

### 10.2 Erforderliche Operationsdaten

Mindestens zu speichern sind:

```text
operationId
commandId                  idempotente Client-ID
commandKind                NORMAL | UNDO | REDO | IMPORT | MERGE ...
actorId
repositoryId
workspaceId
branch
sessionId
groupId                    zusammengesetzte Benutzeraktion
baseRevision
resultRevision
semanticType
versioned operation body
affectedObjectIds
before/after fingerprints
targetOperationId          bei Undo/Redo
causation/correlation ids
timestamp
validation and provenance metadata
```

Der vollständige Operationskörper muss nach einem Neustart rekonstruierbar sein. Ein kompakter Hash für Idempotenz genügt dafür nicht.

### 10.3 Persönliches Undo

Ein persönliches Undo wählt die letzte eigene aktive und grundsätzlich undo-fähige Operation. Das System darf eine blockierte eigene Operation nicht still überspringen und eine ältere Operation zurücknehmen.

Ein späterer Eingriff auf dasselbe fachliche Objekt oder eine abhängige Struktur kann das Undo blockieren. Die Antwort nennt:

- Zieloperation und ursprünglichen Zeitpunkt;
- betroffene Objekte;
- blockierende Operationen und Autoren;
- den konkreten semantischen Überschneidungsgrund.

### 10.4 Gemeinsames Undo

Gemeinsames Undo erfordert ein explizites Ziel und eine bestätigte Vorschau. Ein `previewId` wird an aktuelle Revision, Zieloperation und Blockermenge gebunden. Jede zwischenzeitliche Operation macht die Vorschau ungültig.

### 10.5 Redo

Redo invertiert eine akzeptierte Undo-Operation durch eine neue auditierbare Operation. Es wird abgelehnt, wenn:

- das Undo nicht existiert oder nicht dem Benutzer beziehungsweise der zulässigen gemeinsamen Historie gehört;
- es bereits erneut angewendet wurde;
- spätere Änderungen semantisch kollidieren;
- die erwartete Revision veraltet ist.

### 10.6 Checkpoints und Sitzungsabschluss

Ein Git-Checkpoint ist ein expliziter, revisionsgebundener Befehl. Er soll nicht jede Zeigerbewegung oder jede kleine Eigenschaftsänderung committen.

**Offene Entscheidungen:**

- Wie lange bleibt ein Operationsverlauf nach Sitzungsabschluss ausführbar?
- Werden Operationen nach einem Checkpoint weiterhin einzeln undo-fähig oder nur bis zu einer expliziten Veröffentlichungsgrenze?
- Wie werden sehr große Operationsgruppen kompakt gespeichert und trotzdem prüfbar gehalten?
- Welche Operationen sind aus Governance-Gründen grundsätzlich nicht persönlich undo-fähig, etwa angenommene Freigaben?

---

## 11. Nebenläufigkeit, Konflikte und Live-Ereignisse

### 11.1 Revisionen und Idempotenz

Jeder semantische Befehl trägt eine erwartete Revision und eine stabile `commandId`. Zwei unterschiedliche Befehle auf derselben veralteten Revision dürfen nicht unbemerkt beide denselben Zustand überschreiben.

### 11.2 Semantische Konflikte

Konflikte werden auf Fachobjekt- und Eigenschaftsebene beschrieben, nicht nur als Textkonflikt:

```text
Benutzer A ändert System X.lifecycleState
Benutzer B ändert System X.lifecycleState anders
    -> Eigenschaftskonflikt

Benutzer A löscht Organisationseinheit Y
Benutzer B weist Y eine neue Verantwortung zu
    -> Delete-versus-reference-Konflikt

Benutzer A verschiebt Paket P
Benutzer B ergänzt eine Referenz mit alter Paketadresse
    -> Identitäts-/Abhängigkeitskonflikt
```

Unabhängige Eigenschaften oder disjunkte Objekte können – abhängig von der Operation – automatisch integriert werden.

### 11.3 Soft Locks und exklusive Operationen

Optimistische semantische Nebenläufigkeit bleibt der Standard. Für bestimmte Tätigkeiten können zeitlich begrenzte Leases sinnvoll sein:

- umfangreiche Rohtextbearbeitung;
- Paketverschiebung oder Namespace-Umbenennung;
- großer Import;
- Massenmigration;
- konfliktbehaftete Merge-Auflösung.

Leases dürfen nicht als unsichtbare dauerhafte Sperren enden. Sie benötigen Besitzer, Ablaufzeit, sichtbaren Status und kontrollierte Übernahme beziehungsweise Abbruch.

### 11.4 Ereignisverteilung

SSE oder WebSocket verteilt angenommene Operationen, Präsenz und Fortschritt. Der Ereignisstrom ist Transport und Wiederholungsmechanismus, nicht Quelle der fachlichen Wahrheit.

Erforderlich sind:

- monotone Ereignisfolge pro Sitzung oder Kontext;
- begrenzte Replay-Fähigkeit;
- Snapshot-Reconciliation bei Lücken;
- transaktionale Outbox;
- idempotente Verarbeitung;
- Wiederanlauf nach Prozess- und Verbindungsabbruch;
- klare Trennung von Präsenz und semantischer Historie.

---

## 12. Änderungsanträge, Reviews und Baselines

### 12.1 Von der Anforderung zur integrierten Architektur

```text
Anforderung oder Quellversion
        │
        ▼
unveränderlicher Analysesnapshot
        │
        ▼
Lösungsvarianten und Teilarchitektur
        │
        ▼
semantischer Abgleich mit aktueller Baseline
        │
        ├── vorhandenes Objekt wiederverwenden
        ├── vorhandenes Objekt ändern
        ├── neues Objekt anlegen
        ├── Objekt stilllegen oder ersetzen
        └── Dublette oder Konflikt markieren
        │
        ▼
Architecture Change Set
        │
        ▼
fachliche, technische und sicherheitsbezogene Reviews
        │
        ▼
Integration und neuer Baseline-Checkpoint
```

Teilarchitekturen werden nicht blind angehängt. In die zentrale Architektur fließt ihr geprüfter semantischer Änderungssatz.

### 12.2 Change Set

Ein Change Set sollte enthalten:

- Basis-Repository, Branch und Commit;
- betroffene Pakete und Objekte;
- hinzugefügte, geänderte, verschobene und stillgelegte Inhalte;
- zugehörige Anforderungen, Projekte und Quellen;
- Begründungen und Nachweise;
- Validierungsergebnisse;
- Konflikte und offene Entscheidungen;
- erforderliche Reviewer und Freigaben;
- resultierende Auswirkungen und Migrationshinweise.

Teilmengen müssen – sofern semantisch geschlossen – separat angenommen oder abgelehnt werden können.

### 12.3 Rollen

Repository-Rollen wie `VIEWER`, `CONTRIBUTOR`, `MAINTAINER` und `OWNER` reichen allein möglicherweise nicht aus. Fachliche Review-Rollen können zusätzlich nötig sein:

```text
DOMAIN_ARCHITECT
ENTERPRISE_ARCHITECT
SECURITY_REVIEWER
DATA_PROTECTION_REVIEWER
OPERATIONS_OWNER
ORGANIZATION_DATA_STEWARD
RELEASE_APPROVER
```

**Offene Entscheidung:** Rollenmodell zentral, repositorybezogen oder über externe Gruppen und Richtlinien zusammengesetzt.

### 12.4 Direkte Baseline-Bearbeitung

Die zentrale Baseline sollte standardmäßig schreibgeschützt geöffnet werden. Bearbeitung erfolgt in Workspace, Shared Session oder Change Proposal. Direkte Änderungen können für berechtigte Maintainer vorgesehen werden, müssen aber denselben Befehls-, Audit- und Validierungspfad verwenden.

---

## 13. Skalierung und Bedienbarkeit großer Modelle

### 13.1 Kein vollständiges Modell im Browser

Ein Browser-Editor lädt nur den benötigten Ausschnitt. Der Server stellt bereit:

- paginierte Listen;
- begrenzte Graphnachbarschaften;
- paketweise Projektionen;
- Suchergebnisse mit Kontext;
- View-spezifische Teilgraphen;
- inkrementelle Änderungen ab einer Revision.

### 13.2 Kein einzelnes riesiges In-Memory-Aggregat als Plattformgrenze

Das heutige `CanonicalArchitectureModel` ist für Import, Export und begrenzte Dokumente nützlich. Für die Gesamtplattform wird zusätzlich eine paket- und abfrageorientierte Modellgrenze benötigt. Vollständige Listen und lineare Suchen dürfen nicht die einzige Laufzeitrepräsentation einer Behördenarchitektur bleiben.

### 13.3 Hintergrundaufgaben

Längere Prozesse benötigen einen einheitlichen Jobmechanismus:

- Import und Synchronisation;
- vollständige oder inkrementelle Materialisierung;
- Reindexierung;
- große Validierungen;
- Layoutberechnung;
- Impact-Analyse;
- Merge und Change-Set-Anwendung;
- Export großer Berichte.

Jeder Job benötigt:

```text
jobId
context and owner
state
phase
progress with meaningful units
start and update timestamps
cancel capability where safe
retry/recovery semantics
result and diagnostics
```

Die UI zeigt Fortschritt, aktuelle Phase, Abbruchmöglichkeit und einen weiter nutzbaren Arbeitsbereich. Ein modales Overlay darf nicht dauerhaft die gerade erforderlichen Bedienelemente verdecken.

### 13.4 Skalierungsnachweise

Vor einer belastbaren Kapazitätsaussage sind reproduzierbare Tests nötig, beispielsweise mit:

```text
10.000 Objekten       typische begrenzte Behörden- oder Domänensicht
100.000 Objekten      große Behördenarchitektur mit Historie und Beziehungen
1.000.000 Objekten    föderierter Katalog- und Suchtest, nicht zwingend eine Editiersicht
```

Zu messen sind mindestens:

- Snapshot- und Deltaimport;
- Suche und Kontextwechsel;
- Öffnen und Bearbeiten einer Sicht;
- semantischer Diff und Change Set;
- Undo-/Redo-Konfliktprüfung;
- Merge;
- Reindexierung;
- Speicherbedarf;
- parallele Sitzungen;
- Wiederanlauf.

Diese Größen sind Teststufen, keine bereits zugesicherten Kapazitäten.

---

## 14. Ergonomie und Zugänglichkeit

Eine echte Editorplattform benötigt einen konsistenten Interaktionsrahmen.

### 14.1 Stets sichtbarer Kontext

Benutzer müssen erkennen können:

- welches zentrale Repository geöffnet ist;
- ob sie eine Baseline, einen Workspace oder einen Vorschlag sehen;
- Branch, Commit, Paket und aktuelle Revision;
- ob die Ansicht schreibgeschützt ist;
- ob lokale, akzeptierte, checkpointete oder veröffentlichte Änderungen vorliegen;
- welcher Kollaborationsmodus aktiv ist;
- wer an derselben Sicht arbeitet.

### 14.2 Verständliches Undo

Undo- und Redo-Schaltflächen zeigen nicht nur „verfügbar“, sondern das konkrete Ziel:

```text
Eigene Änderung rückgängig machen:
„Betriebsverantwortung von System X geändert“

Gemeinsame Änderung rückgängig machen:
„Beziehung Y -> Z hinzugefügt“ von Benutzer A
```

Blockierte Aktionen erklären Ursache und betroffene Folgeoperationen. Gemeinsames Undo darf nicht durch ein generisches Tastenkürzel ohne Bestätigung ausgelöst werden.

### 14.3 Konsistente Zustände

Die UI unterscheidet sichtbar:

- lokal noch nicht angewendet;
- serverseitig akzeptiert;
- validiert;
- checkpointet;
- zur Prüfung eingereicht;
- freigegeben;
- in Baseline integriert;
- veröffentlicht;
- durch neuere Quellversion veraltet.

### 14.4 Barrierefreiheit

Graphische Bearbeitung benötigt gleichwertige Tastatur-, Tabellen- oder Formularpfade. Fokusführung, Screenreader-Beschriftungen, hohe Vergrößerung, erzwungene Farben, reduzierte Bewegung und verständliche Live-Regionen sind Bestandteil der Architektur, nicht nur späteres Styling.

---

## 15. Sicherheit, Datenschutz und Behördenbetrieb

### 15.1 Mandanten- und Kontexttrennung

Jede repositorybezogene Operation und Projektion trägt einen expliziten `repositoryId`-Kontext. Workspace, Branch, Paket und gegebenenfalls Sitzung ergänzen diesen Kontext. Ungefilterte globale Abfragen sind für repositoryeigene Daten unzulässig.

### 15.2 Schutzbedarfsgrenzen

Bei stark unterschiedlichen Schutzstufen ist eine physische oder logisch strikt getrennte Repository- beziehungsweise Deployment-Grenze vorzuziehen. Eine Gesamtansicht kann Metadaten oder freigegebene Referenzen föderieren, ohne geschützte Detailmodelle offenzulegen.

### 15.3 Personenbezug

Organisationsarchitektur sollte bevorzugt Rollen, Stellen und Einheiten modellieren. Personenbezogene Besetzungen werden nur bei fachlicher Notwendigkeit, klarer Quelle, Berechtigung und Aufbewahrungsregel übernommen.

### 15.4 Audit

Audit und Operationshistorie beantworten unterschiedliche Fragen:

- Operationshistorie: Welche fachliche Änderung erzeugte den aktuellen Modellzustand?
- Audit: Wer griff wann worauf zu, versuchte welche Aktion und mit welchem Ergebnis?
- Git-History: Welche geprüften Checkpoints und Baselines wurden veröffentlicht?

Alle drei Ebenen müssen korrelierbar, aber nicht identisch sein.

### 15.5 Digital souveräner Betrieb

Editor, Zusammenarbeit, Suche, Import, Versionierung und Undo dürfen nicht von einem externen KI- oder SaaS-Dienst abhängig sein. KI-Funktionen bleiben optionale Vorschlags- und Analysekomponenten.

---

## 16. Vorgeschlagene technische Bausteine

```text
Web editor shell
├── DSL adapter
├── graph adapter
├── tree/table/matrix adapters
├── property and review panels
└── presence/progress/history clients
        │
        ▼
Architecture command API
├── context and permission validation
├── semantic command handlers
├── validation and conflict analysis
├── undo/redo service
├── change-set service
└── job orchestration
        │
        ▼
Durable editing layer
├── session aggregate and semantic revision
├── versioned operation bodies
├── canonical package projections
├── transactional outbox
└── restart recovery
        │
        ├── Hibernate ORM / Search projections
        ├── deterministic TaxDSL packages
        └── JGit checkpoints via jgit-storage-hibernate
```

### 16.1 Modulgrenzen

Vorgeschlagene Zuordnung:

- `taxonomy-domain`: frameworkunabhängige Architekturidentitäten, semantische Operationen, Konflikt- und Undo-Regeln;
- `taxonomy-dsl`: Parsing, Serialisierung, semantischer Diff, Paket- und Dokumentabbildung;
- `taxonomy-extension-api`: Importquellen, Mappingprofile, Validierer und gegebenenfalls Editor-Erweiterungspunkte;
- `taxonomy-app`: Sitzungen, Persistenz, Outbox, Repositorykontext, Jobs, Berechtigungen und APIs;
- Web-Frontend: austauschbare Editoradapter, aber kein zweites kanonisches Architekturmodell;
- `jgit-storage-hibernate`: generische Git-Persistenz und generische History-/Suchfunktionen, keine Taxonomy-Fachsemantik.

### 16.2 Frontend-Stack

Der aktuelle Thymeleaf-/JavaScript-/CodeMirror-Ansatz ist für den bestehenden Editor funktionsfähig. Eine größere Mehrfachsichten- und Kollaborationsplattform könnte von einer stärker typisierten Frontendstruktur profitieren.

**Spike erforderlich:** Vergleich mindestens folgender Varianten:

1. evolutionäre Erweiterung der bestehenden Oberfläche;
2. dediziertes TypeScript-Frontend innerhalb desselben Deployments;
3. modularer Editorbereich, der schrittweise eingebettet wird.

Die Entscheidung soll Bundle-Reproduzierbarkeit, Barrierefreiheit, Testbarkeit, Graph-/Matrix-Komponenten, Offline-/Reconnection-Verhalten und langfristige Wartbarkeit bewerten. Eine konkrete Graphbibliothek wird in diesem Konzept noch nicht festgelegt.

---

## 17. Übernahme von Erkenntnissen aus `audio-analyzer`

Die dort dokumentierte kollaborative Workflowarchitektur liefert wertvolle, wiederverwendbare Prinzipien:

- der Server besitzt die kanonische Semantik;
- Browserprojektionen sind ersetzbar;
- akzeptierte Änderungen bilden einen dauerhaften geordneten Operationsverlauf;
- Undo und Redo sind neue semantische Operationen;
- persönliches und gemeinsames Undo werden getrennt;
- gemeinsames Undo verwendet explizite Vorschau und Bestätigung;
- Git-Commits sind Checkpoints, nicht der Editor-Undo-Stack;
- Präsenz ist nicht Teil der fachlichen Historie;
- Wiederanlauf, Idempotenz und erwartete Revisionen werden getestet.

Referenzen:

- [Durable semantic undo and redo](https://github.com/carstenartur/audio-analyzer/blob/master/docs/architecture/semantic-undo-redo.md)
- [Collaborative Workflow Platform Architecture](https://github.com/carstenartur/audio-analyzer/blob/master/docs/architecture/collaborative-workflow-platform.md)
- [ADR-006: Versioned collaborative workflow store](https://github.com/carstenartur/audio-analyzer/blob/master/docs/architecture/adr-006-versioned-collaborative-workflow-store.md)

Taxonomy kann diese Architektur jedoch nicht unverändert kopieren. Zusätzliche Anforderungen sind:

- mehrere Repositories, Pakete und externe Referenzen;
- wesentlich größere und nur partiell geladene Modelle;
- mehrere fachliche Editordarstellungen;
- langlaufende Imports und Synchronisation;
- formale Reviews und Freigaben;
- unterschiedliche Schutzbedarfe;
- zeitliche Organisations- und Stammdaten;
- Teilübernahmen und Change Sets über viele Objekttypen.

---

## 18. Vorgeschlagene APIs auf Konzeptebene

Noch keine endgültigen Endpunktnamen, aber folgende Fähigkeiten werden benötigt:

```text
POST /architecture/commands
POST /architecture/commands/preview
GET  /architecture/contexts/{id}/projection
GET  /architecture/contexts/{id}/changes?afterRevision=...

POST /editing-sessions
GET  /editing-sessions/{id}
POST /editing-sessions/{id}/join
POST /editing-sessions/{id}/leave
GET  /editing-sessions/{id}/history
GET  /editing-sessions/{id}/history/capabilities
POST /editing-sessions/{id}/undo/preview
POST /editing-sessions/{id}/undo
POST /editing-sessions/{id}/redo/preview
POST /editing-sessions/{id}/redo
POST /editing-sessions/{id}/checkpoint

POST /change-sets
GET  /change-sets/{id}
POST /change-sets/{id}/validate
POST /change-sets/{id}/submit
POST /change-sets/{id}/review
POST /change-sets/{id}/integrate

POST /imports/preview
POST /imports/jobs
GET  /jobs/{id}
POST /jobs/{id}/cancel
```

Befehlsantworten enthalten mindestens Revision, Operation, betroffene Objekte, kanonische oder inkrementelle Projektion und stabile Problemcodes bei Ablehnung.

---

## 19. Verifikation und notwendige Spikes

### 19.1 Semantisches Operationsmodell

Ein Spike muss für repräsentative Objekttypen beweisen:

- deterministische Serialisierung und Wiederherstellung jeder Operation;
- Inversion für Undo/Redo;
- zusammengesetzte atomare Operationen;
- betroffene Objektmengen und Konfliktanalyse;
- Wiederanlauf aus dauerhafter Historie;
- Migration älterer, nicht vollständig rekonstruierbarer Einträge.

### 19.2 Zwei-Browser-Kollaboration

Ein paketierter End-to-End-Test soll zeigen:

- zwei isolierte Browserkontexte;
- geordnete Konvergenz;
- persönliche Undo-Ziele;
- blockiertes Undo nach abhängiger Fremdänderung;
- gemeinsame Undo-Vorschau;
- Reconnect und Replay;
- Prozessneustart und Wiederherstellung;
- keine versteckte Abhängigkeit von Browserlokalspeicher.

### 19.3 Text-zu-Semantik

Ein Spike soll einen DSL-Puffer gegen eine Basisrevision diffen und als semantische Operationsgruppe anwenden. Zu prüfen sind:

- Umbenennung gegenüber Löschen-plus-Neuanlegen;
- stabile Identitäten;
- Paketverschiebung;
- Formatänderungen ohne Fachänderung;
- syntaktisch ungültige Zwischenstände;
- Konflikte mit parallelen strukturierten Änderungen.

### 19.4 Große paketierte Modelle

Ein Spike soll partielle Projektion, Suche, Editieren und Checkpointing eines großen synthetischen Modells beweisen, ohne den Gesamtgraphen in Browser oder eine einzelne Session zu laden.

### 19.5 Import-Synchronisation

Ein Spike soll denselben Organisations-Snapshot mehrfach importieren und anschließend eine Reorganisation als Delta beziehungsweise Snapshot-Diff anwenden. Nachzuweisen sind Idempotenz, Gültigkeitszeiträume, Identitätsauflösung, Konfliktvorschau und kontrollierte Stilllegung.

---

## 20. Offene Kernentscheidungen

| Nr. | Entscheidung | Warum sie vor der Umsetzung wichtig ist |
|---|---|---|
| D1 | Paket- und Repository-Grenzen | bestimmt Identität, Berechtigungen, Performance und Merge |
| D2 | Einheitliches semantisches Operationsmodell | Grundlage aller Editoren, Undo, Audit und Live-Kollaboration |
| D3 | Lebenszyklus von Sitzungen und Operationshistorie | verhindert widersprüchliche Undo- und Checkpoint-Semantik |
| D4 | Texteditor-Anwendung in kollaborativen Kontexten | Rohtext und strukturierte Paralleländerungen können kollidieren |
| D5 | Frontend-Architektur und Graph-/Matrixadapter | bestimmt langfristige Wartbarkeit und Testbarkeit |
| D6 | Exakte Regeln für persönliches und gemeinsames Undo | fachliche Sicherheit und verständliche UX |
| D7 | Change-Set-, Review- und Freigabemodell | nötig für Behörden-Baselines und Teilübernahmen |
| D8 | Versionierung repositoryübergreifender Referenzen | nötig für reproduzierbare föderierte Baselines |
| D9 | Stammdaten-Quellpriorität und lokale Overrides | verhindert Überschreiben autoritativer Daten |
| D10 | Schutzbedarfs- und Berechtigungsgrenzen | muss vor Index- und Projektionsdesign feststehen |
| D11 | Skalierungsziele und Messmethodik | bestimmt Datenstrukturen und Ladegrenzen |
| D12 | Kompatibilitäts- und Migrationspfad | bestehende DSL-Dokumente, Repositories und Projektionen müssen erhalten bleiben |

---

## 21. Kriterien für die spätere Issue-Zerlegung

Die Zerlegung in Epics und Einzel-Issues beginnt, sobald folgende Punkte mindestens als vorgeschlagene, intern konsistente Entscheidungen dokumentiert sind:

- [ ] Fachliche Schichten und ihre Beziehungen sind bestätigt.
- [ ] Repository-, Paket- und Identitätsmodell sind ausreichend beschrieben.
- [ ] Die Grenze zwischen Git-Checkpoint, Operationshistorie und Audit ist festgelegt.
- [ ] Die Kollaborationsmodi und Undo-/Redo-Regeln sind festgelegt.
- [ ] Der Umgang des DSL-Editors mit semantischen Operationen ist entschieden.
- [ ] Change-Set-, Review- und Baseline-Workflow sind beschrieben.
- [ ] Importziele, Synchronisationsmodi und Quellprioritäten sind beschrieben.
- [ ] Repositoryübergreifende Referenzen und Versionbindungen sind beschrieben.
- [ ] Sicherheits- und Mandantengrenzen sind festgelegt.
- [ ] Skalierungsstufen und Performance-Nachweise sind festgelegt.
- [ ] Frontend- und Eventtransport-Spikes sind definiert oder abgeschlossen.
- [ ] Migrations- und Rückwärtskompatibilitätsstrategie ist festgelegt.
- [ ] Die End-to-End-Verifikationsmatrix ist festgelegt.

Nicht jede Detailfrage muss vor dem ersten Issue gelöst sein. Es dürfen jedoch keine Issues erzeugt werden, deren technische Richtung durch eine noch ungeklärte Querschnittsentscheidung wahrscheinlich wieder verworfen werden müsste.

---

## 22. Vorläufige Epic-Landkarte – noch keine Issues

Diese Landkarte dient nur dazu, die Vollständigkeit des Konzepts zu prüfen.

1. **Canonical model, identity and package boundaries**
2. **Repository catalog and federated architecture references**
3. **Generalized import and master-data synchronization**
4. **Semantic command engine and durable operation store**
5. **Multi-view editor platform**
6. **Private and shared editing sessions**
7. **Personal/shared semantic undo and redo**
8. **Semantic diff, conflict and merge platform**
9. **Architecture change sets, review and baseline integration**
10. **Repository-scoped projections, search and caching**
11. **Large-model loading, jobs and progress UX**
12. **Authorization, protection levels, audit and governance**
13. **Migration of existing DSL, imports, workspaces and repositories**
14. **Browser, restart, concurrency, accessibility and scale verification**
15. **Documentation, operator guidance and public-sector deployment profiles**

Bei der späteren Zerlegung erhält jedes Issue:

- einen Link auf die maßgeblichen Abschnitte dieses Konzepts;
- klare Vorbedingungen und Abhängigkeiten;
- fachliche und technische Abnahmekriterien;
- Migrations- und Fehlerpfade;
- Testebene und erforderliche Beweise;
- explizite Nicht-Ziele.

---

## 23. Pflegeprozess dieses Konzeptpapiers

1. Änderungen erfolgen über Pull Requests und bleiben reviewbar.
2. Neue Erkenntnisse werden zunächst hier eingeordnet, statt sofort isolierte Issues zu erzeugen.
3. Entscheidungen erhalten bei Bedarf eigene ADRs; dieses Dokument verweist anschließend darauf.
4. Spikes dokumentieren Ergebnis, Grenzen und verworfene Alternativen.
5. Sobald die Kriterien aus Abschnitt 21 ausreichend erfüllt sind, wird ein versionierter Planungsstand markiert.
6. Aus diesem markierten Stand werden Epics und umsetzbare Issues erzeugt.
7. Während der Umsetzung bleibt das Konzept als Navigations- und Konsistenzdokument bestehen; Abweichungen werden bewusst eingepflegt und nicht stillschweigend im Code vorgenommen.

---

## 24. Empfohlene nächste Planungsschritte

Noch vor der Issue-Zerlegung sollten in dieser Reihenfolge vertieft werden:

1. Paket-, Identitäts- und Repositorymodell;
2. semantisches Operationsmodell mit Undo-Grenzen;
3. Editoroberflächen und Texteditor-Integration;
4. Sitzungs-, Revisions- und Checkpoint-Lebenszyklus;
5. Change-Set-, Review- und Baseline-Prozess;
6. Import-/Synchronisationsmodell für Organisation und vorhandene Architektur;
7. Skalierungs- und Schutzbedarfsgrenzen;
8. Frontend-, Eventtransport- und große-Modell-Spikes;
9. Migrationspfad aus der heutigen Implementierung;
10. abschließende Konsistenzprüfung und Issue-Schnitt.

Das Ziel ist nicht, vor der Implementierung jede spätere Einzelheit vorauszuplanen. Das Ziel ist, die gemeinsamen fachlichen und technischen Invarianten so weit zu klären, dass die entstehenden Issues zusammen eine kohärente Plattform ergeben und nicht nur eine Sammlung unabhängiger Funktionen.
