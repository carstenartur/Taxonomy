# Modul- und Bounded-Context-Grenzen

Dies ist das **Inventar der heutigen Implementierung**, keine Liste geplanter
Extraktionen. Taxonomy bleibt ein modularer Monolith mit einer deploybaren
Spring-Boot-Anwendung. Die sieben unten genannten Fachbibliotheken sind bereits
extrahiert. Die frühere schrittweise Beschreibung bleibt als
[historischer Extraktionsnachweis](../internal/MODULE_BOUNDARIES_HISTORY_DE.md) erhalten.
Deren Zwischenblocker, Zählstände und Issue-Status beschreiben frühere Checkpoints,
nicht den heutigen offenen Arbeitsbestand.

## Aktueller Maven-Reactor

| Modul | Gruppe | Aktuelle Zuständigkeit |
|---|---|---|
| `taxonomy-domain` | Grundlage | Frameworkfreie gemeinsame Architektur-/Analyseverträge |
| `taxonomy-dsl` | Grundlage | Frameworkfreie TaxDSL-Syntax, Modell, Validierung, Mapping, Diff und Befehle |
| `taxonomy-export` | Grundlage | Frameworkfreie Exportverträge, Codecs und neutrales Rendering |
| `taxonomy-extension-api` | Grundlage | Frameworkfreie Erweiterungsverträge und Metadaten |
| `taxonomy-workspace` | Fachmodul | Workspace-/Repository-Identität, Editorjournal, Undo/Redo, Git-Checkpoints und Speicher |
| `taxonomy-knowledge` | Fachmodul | Katalog/Seeds, Relationen/Hypothesen, Suche, Indizes und lokale Embeddings |
| `taxonomy-templates` | Fachmodul | Vorlagenversionen, OOXML-Validierung, Materialisierung, WebDAV und Administration |
| `taxonomy-interop` | Fachmodul | Geprüfter Austausch, Zuordnungen, Konnektorsteuerung und Synchronisationscheckpoints |
| `taxonomy-architecture` | Fachmodul | Ableitung, Scoring, Lücken, Muster, Empfehlungen, Diagramme und Berichte |
| `taxonomy-analysis` | Fachmodul | Anforderungs-/LLM-Analyse, Anbieterregeln, Prompts, Parsing und Sitzungen |
| `taxonomy-portfolio` | Fachmodul | Projekte, versionierte Anforderungen, Jobs/Ergebnisse/Reviews, Wiederanlauf und Snapshots |
| `taxonomy-app` | Komposition | Einzige ausführbare Anwendung: Verdrahtung, Kontextadapter, Sicherheit, Migrationen und Packaging |
| `taxonomy-tooling` | Build/Werkzeuge | Build- und Release-Werkzeuge; keine Laufzeit-Fachbibliothek |
| `taxonomy-coverage` | Build/Werkzeuge | Reactor-weite Coverage-Aggregation; keine Laufzeit-Fachbibliothek |
| `taxonomy-build` | Build/Werkzeuge | Reactor-weite Qualitätsprüfungen und Browser-/Verifikationsverträge |

Der Root-Aggregator ist kein weiteres Untermodul. Die fünfzehn Untermodule bestehen
aus vier Grundlagen, sieben Fachmodulen, dem Kompositionsmodul und drei Build-/Werkzeugmodulen.
Nur `taxonomy-app` ist eine ausführbare Anwendung. Die Build-Gruppe bildet keine
Laufzeitdienste. Siehe [geprüften Fachmodulgraphen](ARCHITECTURE.md#modularchitektur)
und [Laufzeit-/Persistenzdarstellungen](ARCHITECTURE.md).

## Fachmodule und Zuständigkeiten

### `taxonomy-knowledge`

Besitzt Katalog, Relationen und Suche einschließlich Seeds, Suchmappings/-analyzern
und Embedding-Lebenszyklus. Framework-Importparser und Registry gehören zum Wissensmodul;
kontextübergreifende Materialisierungsadapter zur Anwendungskomposition. Interne
Package-Kopplungen rechtfertigen keine Rückabhängigkeit auf die ausführbare Anwendung.

### `taxonomy-workspace`

Besitzt Workspace-, Versions- und Editorzustand gemeinsam: dauerhaftes semantisches
Operationsjournal, Undo/Redo, Checkpoint-Vorbereitung/-Veröffentlichung, Repository-Kontext
und JGit-/Hibernate-Speicher. **Übernommene semantische Operationen sind dauerhafte
Revisionen; Git-Commits sind explizite stabile Checkpoints, nicht das Operationsprotokoll.**

### `taxonomy-architecture`

Besitzt Architekturableitung, Bewertungen, Lücken, Muster, Empfehlungen, Diagrammvorbereitung
und Berichte. Aktuelle Berichtseinstellungen kommen über `ArchitectureReportMetadataPort`.
HTTP-Berichtskomposition und Repository-/Workspace-Auflösung bleiben anwendungseigen;
die Bibliothek darf nicht von einem anwendungseigenen `WorkspaceResolver` abhängen.

### `taxonomy-analysis`

Besitzt Anforderungs-/LLM-Analyse, Prompts, Anbieterregeln, Antwort-Parsing, Sitzungen
und Analyseabstraktionen. Zustandsbehaftete Fremdzugriffe verwenden explizite Ports.
Eigene Unit-Tests und Fachressourcen gehören zur Bibliothek, nicht zu `taxonomy-app`.

### `taxonomy-portfolio`

Besitzt Projekte, versionierte Anforderungen, dauerhafte Analysearbeit/-ergebnisse/-reviews,
Warteschlangen/Wiederanlauf und Workbench-Snapshots. Die Koordination verwendet Fach-APIs,
nicht fremde Repositories. Kontextübergreifende Abnahmetests bleiben anwendungseigen.

### `taxonomy-interop`

Besitzt geprüfte externe Werkzeugoperationen, Zuordnungen, Checkpoints/Ereignisse und
Konnektorsteuerung. Portfoliozugriff läuft über einen expliziten Port mit Anwendungsadapter;
Interop darf nicht von der Portfolioimplementierung oder konkreten Editordiensten abhängen.

### `taxonomy-templates`

Besitzt das separate Vorlagen-Git-Repository, OOXML-Sicherheit/-Validierung,
Materialisierung, WebDAV-Projektion/-Sperren sowie Administrations-/Health-Verträge.
Es hängt von keiner anderen Taxonomy-Fachbibliothek ab. Vorlagenspeicher ist nicht
das semantische Operationsjournal des Editors.

## Bewusst noch nicht extrahierte Kontexte

Provenance/Dokumentimport und Preferences bleiben anwendungslokal. Ihre Zuständigkeiten
sind im Kontextmodell sichtbar; separate Module werden nicht behauptet. Übergangsadapter
für DSL/Export bleiben explizit klassifiziert, bis ihre fachlichen Ports feststehen.
Ein generisches Sammelmodul für Adapter wird nicht eingeführt.

## Zielrolle von `taxonomy-app`

Die Anwendung ist der **bereits vorhandene** Kompositions-/Deployment-Einstieg. Sie
besitzt Verdrahtung, Sicherheit/Anfrageidentität, Observability, globale Konfiguration
und Fehlerbehandlung, kontextübergreifende HTTP-/UI-Adapter, Migrationskomposition und
abschließendes Packaging. Provenance und Preferences liegen weiterhin hier. Das ist
eine dokumentierte Grenze, keine Behauptung, die Anwendung enthalte schon ausschließlich Verdrahtung.

## Fitness-Funktionen für Abhängigkeiten

Maßgebliche maschinenlesbare Eingaben bleiben [Kontextmodell](../../.github/architecture-contexts.json),
[Ausnahmen](../../.github/architecture-exceptions.json) und
[Abhängigkeitsbaseline](../../.github/architecture-dependency-baseline.json) zusammen
mit tatsächlichen POMs und produktiven Klassen. Die Dokumentation führt keine zweite
Architekturrichtlinie ein und erzeugt die Baseline nicht neu.

`taxonomy-build` besitzt die bestehenden Modulgraph-/Extraktionsprüfungen. Maven-Grenzregeln
und Packaging-Prüfungen sichern Abhängigkeitsrichtung, eindeutige Laufzeitklassen/-ressourcen
und anwendungseigene Migrationen. Fachliche Unit-Tests liegen bei den Bibliotheken;
kontextübergreifende, Datenbank-, Sicherheits- und Wiederanlauftests bei der Anwendung.

`ArchitectureDocumentationTest` verwendet im normalen Gesamt-Reactor-Test die
Reactor-Erkennung und den produktiven POM-Leser des bestehenden Gates. Geprüft werden
dieses Inventar, die englische Fassung, das README-Inventar und beide markierten
Fachmodulgraphen. Das belegt strukturelle Konsistenz, nicht jede Textaussage oder das Laufzeitverhalten.

## Historische Extraktionsnachweise

Die [frühere ausführliche Beschreibung](../internal/MODULE_BOUNDARIES_HISTORY_DE.md) ist bytegetreu
vom Stand vor dieser Neuordnung erhalten, damit Einzelnachweise nicht verloren gehen.
„Geplant“-Überschriften, wechselnde Klassenpaarzahlen und Abschlusshinweise darin sind
historisch zu lesen. Zur Orientierung gilt die aktuelle Zuständigkeitsbeschreibung oben.
Der [Abschlussvertrag der Modulextraktion](../dev/MODULE_EXTRACTION_COMPLETION.md) und die
[Gate-Dokumentation](../dev/MODULE_EXTRACTION_GATE.md) behalten ihre detaillierten Nachweise.

[Architektur](ARCHITECTURE.md) · [English](../en/MODULE_BOUNDARIES.md)
