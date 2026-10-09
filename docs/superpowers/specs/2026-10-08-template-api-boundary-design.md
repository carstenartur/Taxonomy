# Modulgrenzen und Pluginfähigkeit – revidierte Empfehlung

Stand: 8. Oktober 2026. Status: überarbeiteter Entwurf, keine Produktimplementierung.
Geprüfte Produktbasis: `ed875906fb9bd542ad7a6e91e9b80f3ed4d75568`.
Diese Fassung ersetzt die pauschale Adapter-Rückverlagerung im ersten Entwurf.

## Ziel und gleichbleibende Maßstäbe

Taxonomy bleibt ein modularer Monolith. Fachliche Zusammengehörigkeit,
Informationskapselung, eindeutige Daten-/Transaktionsverantwortung und nachweisbar
geringere Änderungskosten bestimmen die Grenzen. Dateizahl, Klassenlänge und
Spring-Annotationen sind Suchsignale, keine selbständigen Umbaugründe.

Fachmodule dürfen eigene HTTP-, WebDAV-, Persistenz- und Health-Adapter besitzen.
`taxonomy-app` trägt den Prozessstart, die anwendungsweite Sicherheits- und
Betriebskonfiguration sowie notwendige fachbereichsübergreifende Verdrahtung.
Ein fachmodulspezifischer Controller wird nicht allein wegen HTTP dorthin
verschoben. Die bestehende [Modulrichtlinie](../../dev/extension-module-boundaries.md)
formuliert diese Eigentumsregel bereits.

Die neue Nutzerfrage erweitert das Zielbild um Erweiterungen durch Plugins und
eine mögliche spätere Aufteilung bestehender Funktionen in installierbare
Funktionspakete. Sie beauftragt hier die Überarbeitung der Empfehlung, noch keine
Einführung eines Plugin-Frameworks. Der bestehende Verbesserungsauftrag bleibt
erhalten; dieser Text unterscheidet aktuelle Korrekturen von weiterem Ausbau.

## Was im geprüften Code bereits vorhanden ist

| Quelle | Befund | Bedeutung |
| --- | --- | --- |
| `taxonomy-extension-api` | Frameworkfreie gemeinsame Metadaten und Report-/Import-/LLM-/Integrationsverträge | Vorhandene Grundlage weiterverwenden; kein zweites allgemeines SPI-System |
| `ExtensionRegistry`, `ReportRendererRegistry`, `ImportProfileRegistry`, `ExportFormatExtensionRegistry` | Spring injiziert Listen; Registries bauen im Konstruktor unveränderliche Maps auf | Discovery bei der Initialisierung, kein implementierter dynamischer Plugin-Lebenszyklus |
| `taxonomy-dsl/.../PlanningInformation.java` | Lädt `PlanningProfile` über `ServiceLoader`, danach unveränderliche Registry | Bereits externe Java-Service-Erweiterung möglich; kein allgemeiner Installations-/Update-/Entlademechanismus |
| `LlmProviderExtensionRegistry` / `LlmGatewayRegistry` | Anbieter-ID muss zu `LlmProvider.valueOf(...)` passen; Gateways werden zentral konstruiert | Metadaten-SPI reicht für einen unabhängig ergänzten Anbieter noch nicht |
| `ReportRenderContext` / Entscheidungsrenderer | Allgemeines `Object`-Payload, tatsächliche Renderer erwarten `DecisionRationaleReport` aus `taxonomy-architecture` | Laufzeitprüfung vorhanden; für externe Renderer muss auch das konkrete Modell ein unterstützter Vertrag werden |
| `ArchitectureViewContext` | Veränderlicher Pipelinezustand und Bezüge auf `TaxonomyService` / `TaxonomyNode` | Interner Erweiterungspunkt, noch kein kleiner, implementationsunabhängiger Plugin-Vertrag |
| Aktuelle Feature-POMs | Unter anderem Portfolio → Analysis → Architecture; Architecture → Knowledge, Workspace, Templates | Maven-Module sind noch keine beliebig abwählbaren Funktionspakete |

Die SPIs für Diagrammexport liegen bewusst beim Export-Modell in
`taxonomy-export`. Sie werden nicht allein für ein zentralisiertes SDK in
`taxonomy-extension-api` verschoben. Ein allgemeines Modul darf nicht nach oben
auf Fachimplementierungen zeigen. Die Übersicht
[Extension Points](../../dev/07-extension-points.md) enthält teilweise überholte
Quellpfade; Code und POMs sind für diese Bestandsaufnahme maßgeblich.

## Drei verschiedene Ausbaustufen

| Stufe | Betriebsversprechen | Konsequenz |
| --- | --- | --- |
| A: optionale Funktionen | Gewählte vorhandene Funktionen bilden ein startfähiges Produkt | Explizite Abhängigkeiten, bedingte Verdrahtung, UI-Capabilities und Starttests ohne optionale Komponenten |
| B: installierbare Plugins | Separat gebautes Plugin-JAR wird vor dem Start ergänzt; kein Neubau des Host-Codes | Definierter externer Ladepfad, veröffentlichte Verträge, Pluginmanifest, Prüfung vor Aktivierung; Änderungen erfordern Neustart |
| C: dynamische Plugins | Plugin wird im laufenden Prozess geladen, aktiviert und gegebenenfalls aktualisiert/entladen | Eigener Lebenszyklus, Classloader-Regeln, atomare Registrierung, Behandlung laufender Aufrufe und Ressourcen |

Ein Maven-Profil, das nur eine andere Gesamtanwendung baut, erfüllt Stufe B nicht.
Ein `ServiceLoader.reload()` allein erfüllt Stufe C ebenfalls nicht: Der
Provider-Cache wird erneuert, aber Taxonomys Jobs, Beans, HTTP-Routen und Daten
erhalten dadurch noch keinen koordinierten Lebenszyklus.

Empfehlung: Die aktuellen Refactorings schaffen klare Verträge; anschließend
beweist ein kleiner Pilot Stufe B. Stufe C bleibt ein ausdrücklich begrenzter
Ausbau für geeignete Erweiterungsarten. Additives Laden und sicheres Entfernen
werden getrennt abgenommen.

## Empfohlene Zuordnung bestehender Funktionen

| Bereich | Empfehlung | Voraussetzung |
| --- | --- | --- |
| Einzelne Diagrammexporte und Reportformate | Erste Plugin-Kandidaten | Vollständige autorisierte Eingabemodelle, definierte Ausgabe, keine fremden Repository-Zugriffe |
| LLM-Anbieteradapter | Plugin-Kandidaten nach Erweiterung der ausführbaren SPI | Offene stabile Provider-ID, Host-eigene Budget-/Nebenläufigkeits-/Abbruchregeln, erhaltene Konfigurations- und Persistenzkompatibilität |
| Hersteller-/Standardkonnektoren und Importformate | Weitere Plugin-Kandidaten | Vorhandene Review-/Apply-/Publikationsverträge und dauerhafte Quittungen bleiben beim Host/Fachkern |
| Planungs-/Validierungsprofile | Vorhandenen Mechanismus weiterentwickeln | Versionierte Daten, unbekannte Profile bleiben erhalten und verständlich als nicht unterstützt markiert |
| Vorlagenverwaltung und Reporting | Zusammenhängende optionale Funktionspakete vorsehen | Reporting deklariert Vorlagenbedarf; Vorlagen speichern unabhängig vom installierten Renderer; keine umgekehrte Abhängigkeit |
| Portfolio, Analyse, Architekturansichten | Später als größere Funktionspakete prüfbar; zuerst Startzeit-Optionalität | Ihre heutigen Pflichtabhängigkeiten und Jobs ausdrücklich berücksichtigen; kein Entfernen mitten in einer Verarbeitung |
| Workspace, Versionierung, Editorjournal, Katalog/Relationen und zentrale Autorisierung | Zunächst feste Kernfähigkeiten | Datenautorität und Wiederherstellung sind wichtiger als dynamische Austauschbarkeit |

Der vollständige bisherige Funktionsumfang bleibt als Standarddistribution
verfügbar. Eingebaute Erweiterungen sollen dieselben öffentlichen Verträge wie
externe Erweiterungen benutzen; nicht jede Klasse und nicht jedes Maven-Modul
benötigt deswegen ein eigenes Plugin.

## Konkrete Korrektur des Template-Schnitts

Die öffentliche API wird von `DocumentTemplateGitRepository` entkoppelt.
Vorlagenbeschreibung, Revision, Vergleich, Download und fachliche Fehler sind
modul-eigene Vertragstypen. Der vorhandene Preview-Port bleibt erhalten.
Git-Bäume, JGit-Handles und gespeicherte interne Snapshots werden nicht zur
Anwendungs-API.

Vorlagenspezifische HTTP-/WebDAV-Adapter, Locking, technische Validierung und
Health-Berechnung dürfen beim Vorlagenmodul bleiben, getrennt von der öffentlichen
API und durch Architekturtests geschützt. Für jede tatsächlich nötige
Rückverlagerung nach `taxonomy-app` muss eine anwendungsweite Verantwortung oder
eine konkrete Abhängigkeitsverbesserung benannt werden.

Entscheidungsberichtsspezifische Token-/Inhaltsregeln gehören zum Reporting-
Fachbereich. Ein Reportpaket kann über den bestehenden Vorlagenvertrag seine
Anforderungen und über einen schmalen Beitrag sein Standardtemplate anmelden.
Die generische Vorlagenverwaltung kennt dann keine einzelnen Reportfamilien.
Seeding und Health werden nach Verantwortlichkeit aufgeteilt: Vorlagenprüfung
gehört zum Vorlagenmodul, Anforderungen eines Reports zum Reportpaket, die
übergreifende Aktivierungs-/Readiness-Entscheidung zur Anwendung.

Das erste Refactoring benötigt noch keinen neuen Loader. Es muss nachweisbar
eine andere Vorlagen-/Reportfamilie ermöglichen, ohne den generischen Git- oder
OOXML-Kern zu verändern. API- und interne Implementierungspakete bleiben getrennt;
kein Split Package wird eingeführt.

## Verträge, die vor externen Plugins präzisiert werden

- Stabile Plugin-ID, Pluginversion, unterstützte Host-/SPI-Versionen, benötigte
  Fähigkeiten/Plugins und Konfliktregeln. Inkompatible oder doppelte Registrierungen
  werden vor Aktivierung mit einem konkreten Fehler abgewiesen.
- Öffentlich unterstützte Ein-/Ausgabemodelle statt impliziter Kenntnisse über
  Serviceklassen. Fachspezifische Vertragsmodule entstehen erst bei tatsächlichem
  Bedarf; `taxonomy-extension-api` wird kein Sammelbecken aller Fachmodelle.
- Genau eine Laufzeitidentität der gemeinsamen API-Klassen. Plugins dürfen keine
  eigenen Kopien des Host-SDK unter identischen Paketnamen mitbringen.
- Expliziter, vom Host geprüfter Nutzer-/Workspace-/Repository-/Snapshot-Kontext.
  Renderer laden keinen stillschweigend neueren Zustand nach. Erweiterungen
  übernehmen nicht die Entscheidung, welche Daten der Nutzer sehen oder ändern darf.
- Ein zusammenhängender Aufruf oder Job bleibt an Plugin-ID, Version und
  Konfiguration gebunden. Gespeicherte Ergebnisse und Nachrichten verwenden
  versionierte Datenverträge, keine Java-serialisierten Plugin-Implementierungen.
- UI-Erweiterungen benötigen definierte Beiträge für Navigation, Aktionen und
  Exportoptionen sowie eigene i18n-/Ressourcennamen. Berechtigungen, Theme,
  Barrierefreiheit und fehlende Fähigkeiten müssen auch ohne optionales Plugin
  korrekt dargestellt werden. Ein Backend-Loader allein macht die UI nicht modular.
- Plugins mit eigenen Daten deklarieren Schema-/Backup-/Migrationsverantwortung.
  Deaktivieren löscht keine Nutzerdaten; Reaktivieren, fehlende Versionen und
  historische Snapshots müssen definiert bleiben. Rollback von Code ist keine
  automatische Rücknahme einer Datenmigration.

Im Prozess laufende Java-Plugins gelten als vertrauenswürdiger Anwendungscode;
Classloader-Trennung ist keine Sicherheits-Sandbox. Für nicht vertrauenswürdigen
Code wäre eine Prozessgrenze mit enger RPC-/Daten-API ein anderes Betriebsmodell.

## Echtes Nachladen und Technologieentscheidung

Startzeit-Erweiterungen passen zunächst zum vorhandenen Spring-Boot-Modell:
explizite Auto-Konfiguration in externen JARs für Spring-Komponenten und
`ServiceLoader` für frameworkfreie Dienste. Der Pilot muss den externen Ladepfad
mit dem tatsächlich gepackten Boot-JAR nachweisen; vorhandene Klassen auf dem
Entwicklungs-Classpath reichen dafür nicht.

Für dynamische zustandsarme Erweiterungen ist PF4J ein geeigneter Prüfkandidat.
Es liefert Pluginzustände und Classloader-Mechanik. Taxonomy müsste trotzdem
sichere Aktivierung und Entfernung, Versionsbindung laufender Aufrufe sowie die
Anbindung seiner Registries implementieren. Ein erfolgreicher PF4J-Start beweist
noch keine korrekte Spring-/JPA-/JMS-Integration.

OSGi bietet einen umfassenderen standardisierten Modul-/Dienstlebenszyklus und
ist dann zu bewerten, wenn unabhängige Updates vieler gegenseitig abhängiger
Funktionen eine echte Produktanforderung werden. Die Umstellung der bestehenden
Spring-Boot-Anwendung darauf wird hier nicht vorweggenommen. Reines Startzeit-
Laden allein rechtfertigt diesen zusätzlichen Integrationsschritt nicht.

Dynamische Aktivierung erfolgt erst nach Validierung aller Beiträge. Beim
Deaktivieren werden neue Aufrufe gesperrt, laufende Aufrufe und Jobs kontrolliert
beendet oder auf ihrer bisherigen Version weitergeführt und erst danach
Registrierungen, Ressourcen, Threads und Listener freigegeben. Abhängige aktive
Plugins dürfen nicht mit ungültigen Referenzen zurückbleiben.

Neue JPA-Entitäten lassen sich nicht einfach der bestehenden Hibernate-
SessionFactory hinzufügen: Ihr Laufzeit-Metamodell steht nach der Erstellung
fest. Ganze zustandsbehaftete Funktionspakete bleiben deshalb zunächst beim
Neustart-Lebenszyklus. Im Cluster kommen koordinierte Installation und
Migration sowie die Zuordnung von Jobs zu Workern mit passender Pluginversion
hinzu. Eine nur auf einem Pod geladene Erweiterung genügt nicht.

## Überarbeitete Reihenfolge und überprüfbare Ergebnisse

1. **Template-Verträge und Paketgrenzen korrigieren.** Andere Module verwenden
   keine verschachtelten Repository-Typen. Fachadapter behalten einen begründeten
   Eigentümer. Eine zusätzliche Reportfamilie benötigt keinen Eingriff in den
   generischen Vorlagenkern; historische Revisionen und Restore bleiben erhalten.
2. **Erweiterungsrelevante Teile von Reporting und Analyse herauslösen.** Ein
   Renderer erhält ein vollständiges, stabiles Reportmodell. Ein Anbieter stellt
   ausführbares Verhalten hinter einer SPI bereit. Orchestrierung, Budgetierung,
   Ergebnisvalidierung und Persistenz bleiben bei ihren fachlichen Eigentümern.
   Bestehende Klassen werden nach Änderungsgründen, nicht nach Ziel-Zeilenzahlen
   zerlegt.
3. **Ein vorhandenes einfaches Exportformat als externes Pilot-Plugin paketieren.**
   Separater Maven-Build gegen veröffentlichte Verträge; Installation und
   Erkennung im unverändert gebauten Host, Aufruf über die vorhandene Oberfläche/
   API; sauberes Verhalten bei Abwesenheit, doppelter ID und inkompatibler Version.
   Der Pilot schafft keine zweite Exportimplementierung.
4. **Den Pilot auf optionale Funktionspakete erweitern.** Eine Dependency-Matrix
   definiert unterstützte Produktkombinationen. Startup-/HTTP-/UI-/Backup-Tests
   laufen mit und ohne die gewählten Pakete. Erst jetzt sind zusätzliche
   API-/Implementierungs-Maven-Artefakte begründet.
5. **Begrenztes dynamisches Laden erproben, wenn benötigt.** Export/Renderer ohne
   neue Entities zuerst; parallele Aufrufe, Abbruch, fehlgeschlagener Start,
   Deaktivierung und wiederholtes Laden werden geprüft. State-/Classloader-Leaks
   und Cluster-/Recovery-Verhalten sind eigene Abnahmepunkte.

Parallel werden das Backup-Split-Package und echte problematische
Abhängigkeiten gezielt bereinigt. Architektur-Ausnahmen werden nur entfernt,
wenn die zugrunde liegende Kopplung behoben ist. Der Maven-DAG, unabhängige Tests
und enge API-Zugriffsregeln schützen diese Grenzen.

## Unveränderliche Produktverträge und Verifikation

[ADR 0005](../../adr/0005-private-architecture-editor.md) bleibt verbindlich:
dauerhaft akzeptierte semantische Operationen mit Undo/Redo, separate
wiederaufnehmbare Git-Checkpoints, keine gemeinsame ORM/JGit-Transaktion und kein
Git-Commit pro Editoraktion. Snapshotgebundene Autorität, geprüfte
Interoperabilität, Nutzerisolation, bestehende URLs und Datenformate bleiben
erhalten. Pluginwechsel darf keine dieser Garantien umgehen.

Plugin-Abnahmen werden über Maven in die vorhandenen Prüfpfade integriert.
Der maßgebliche Abschlusslauf bleibt
`./mvnw -B verify -Pci -DrunOnnxTests=true`, ergänzt um die tatsächlich erforderlichen
bestehenden Datenbank-, Security-, Browser- und Wiederherstellungsnachweise.
Die [bekannten Grenzen der lokalen Verifikation](../../dev/MAVEN_VERIFICATION.md)
werden nicht als gelöst ausgegeben. Keine neuen unabhängigen GitHub-Testworkflows,
keine abgeschwächten Gates und keine Rohlogs im Repository.

Diese Überarbeitung beruht auf Quellcodeprüfung und Primärdokumentation. Sie ist
kein ausgeführter Plugin-Prototyp, kein neuer Maven-Testlauf und keine Aussage,
dass Taxonomy heute bereits dynamisch nachladbare Feature-Plugins unterstützt.

## Technische Primärquellen

- [Java 21 ServiceLoader](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/ServiceLoader.html): Service-Discovery und Provider-Cache.
- [Spring Boot Auto-Konfiguration](https://docs.spring.io/spring-boot/reference/features/developing-auto-configuration.html): explizite Konfiguration aus externen JARs.
- [PF4J Plugin-Lebenszyklus](https://pf4j.org/doc/plugin-lifecycle.html) und [Classloading](https://pf4j.org/doc/class-loading.html): Zustände, Versionserfordernisse und Loader-Regeln.
- [Hibernate SessionFactory](https://docs.hibernate.org/orm/7.1/javadocs/org/hibernate/SessionFactory.html): festes Entity-/Mapping-Metamodell.
- [OSGi Core: Lifecycle Layer](https://docs.osgi.org/specification/osgi.core/8.0.0/framework.lifecycle.html): Installation, Auflösung, Start, Stopp und Updates.
