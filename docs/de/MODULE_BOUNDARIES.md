# Modul- und Bounded-Context-Grenzen

Dieses Dokument beschreibt das schrittweise Ziel für die Zerlegung von `taxonomy-app`, ohne das Deployment-Modell von Taxonomy zu verändern. Taxonomy bleibt ein **modularer Monolith** und eine einzelne Spring-Boot-Anwendung. Ziel ist, Maven-Grenzen an fachlicher Zuständigkeit und Abhängigkeitsrichtung auszurichten – nicht das System in Microservices oder ein Modul pro Package aufzuteilen.

Die maschinenlesbare Quelle für die geplanten Extraktionskontexte ist `.github/architecture-contexts.json`. Befristete Architekturausnahmen bleiben in `.github/architecture-exceptions.json`; die geprüften aktuellen Cross-Context-Abhängigkeiten werden in `.github/architecture-dependency-baseline.json` festgeschrieben.

## Aktueller Maven-Reactor

Der Root-Reactor enthält derzeit acht Module mit unterschiedlichen Aufgaben:

| Modul | Aktuelle Aufgabe |
|---|---|
| `taxonomy-tooling` | Abhängigkeitsfreie Build- und Repository-Werkzeuge |
| `taxonomy-domain` | Frameworkfreie gemeinsame Domain-Verträge |
| `taxonomy-dsl` | Frameworkfreier TaxDSL-Parser, Modell, Validierung, Differ und Command-Logik |
| `taxonomy-export` | Frameworkfreie Diagramm-/Export-Verträge und Implementierungen |
| `taxonomy-extension-api` | Frameworkfreie gemeinsame Extension-Verträge |
| `taxonomy-app` | Ausführbare Spring-Boot-Anwendung und derzeit der Großteil der Spring-basierten Feature-Implementierungen |
| `taxonomy-coverage` | Reactor-weite Coverage-Aggregation |
| `taxonomy-build` | Build-Policy sowie Browser-/Verifikationsverträge |

Die ausgelieferten Anwendungsmodule sind damit nur ein Teil des Reactors. `taxonomy-tooling`, `taxonomy-coverage` und `taxonomy-build` erfüllen Build- und Verifikationsaufgaben und sind keine Runtime-Bounded-Contexts.

## Warum `taxonomy-app` zerlegt wird

In `taxonomy-app` haben sich mehrere eigenständig kohärente Bereiche angesammelt: Katalog/Suche, Architekturableitung, Anforderungsanalyse, Workspace-/Versionszustand, semantisches Editor-Journal und Git-Checkpoints, Projekt-/Portfolio-Workflows, externe Interoperabilität, Dokument-Templates/WebDAV, Provenance/Dokument-Ingestion, Preferences, Security und Observability. Wenn sämtlicher Spring-basierter Feature-Code im ausführbaren Modul bleibt, wird die Abhängigkeitsrichtung schwächer und Maven kann Cross-Feature-Kopplung nicht verhindern.

Die Zerlegung folgt deshalb **Zuständigkeit und Bounded Contexts**, nicht mechanisch der heutigen Package-Hierarchie.

## Geplante Bounded Contexts

### `taxonomy-knowledge`

Besitzt `catalog`, `relations` und `search`. Deren heutige gegenseitige Abhängigkeiten werden zunächst als interne Implementierungskopplung eines Knowledge-Kontexts behandelt, während die öffentlichen Verträge verengt werden. Search-Mappings und Binder gehören zur Persistenzseite dieses Kontexts und nicht als allgemeine Anwendungsabhängigkeit in die App.

### `taxonomy-workspace`

Besitzt `workspace`, `versioning` und `editor`. Diese Packages kontrollieren gemeinsam den editierbaren und versionierten Zustand, einschließlich dauerhaftem semantischem Operationsjournal, Undo/Redo, Checkpoint-Vorbereitung/-Publikation, Repository-Kontext und JGit/Hibernate-basierter DSL-Speicherung. Sie werden zunächst zusammengehalten, damit ein Maven-Split die heutige Package-Kopplung nicht lediglich in einen Modulzyklus verwandelt.

Die mit dem Editor-Umbau eingeführte Invariante bleibt unverändert: **akzeptierte semantische Operationen sind dauerhaft gespeicherte Revisionen; Git-Commits sind explizite stabile Checkpoints und nicht das Operationslog.**

### `taxonomy-architecture`

Besitzt Architekturableitung, Scoring, Gaps, Patterns, Empfehlungen, Architektur-View-/Domain-Modelle und neutrale Diagrammvorbereitung. Repository-/Workspace-Auflösung und Cross-Context-HTTP-Orchestrierung gehören nicht in dieses Modul.

### `taxonomy-analysis`

Besitzt Anforderungs- und LLM-Analyse, Provider-/Gateway-Auswahl, Response-Parsing, Prompt-/Policy-Logik, Analysesitzungen und lokale Inferenzabstraktionen. Zustandsbehafteter Repository- oder Hypothesen-Zugriff erfolgt über explizite Ports.

### `taxonomy-portfolio`

Besitzt Projekt-/Portfolio-Zustand und Orchestrierung: versionierte Anforderungen, persistierte Analysejobs/-ergebnisse/-Reviews, Queue/Recovery, Workbench-Snapshots und projektbezogene Workflows. Analysis-, Architecture- und Workspace-Fähigkeiten werden über deren APIs koordiniert; fremde Repositories werden nicht direkt angesprochen.

### `taxonomy-interop`

Besitzt geprüfte Interoperabilität mit externen Werkzeugen, einschließlich dauerhafter Integrationsoperationen, Mappings/Checkpoints/Events, Connector-Orchestrierung und OSLC-/ReqIF-/ArchiMate-Anwendungsintegration. Das Modul konsumiert schmale Ports und darf nicht von konkreten Editor-Services abhängen.

### `taxonomy-templates`

Besitzt das Dokument-Template-Subsystem: Template-Git-Repository, OOXML-Package-Codec und Sicherheitsvalidierung, Materialisierung/Cache, WebDAV-Projektion/Locking sowie Template-Administration und Health-Verträge.

## Restkontexte, die bewusst noch keine Module sind

`provenance` und `preferences` werden explizit klassifiziert, damit ihre Abhängigkeiten im Ratchet sichtbar sind; sie besitzen aber noch kein Ziel-Maven-Modul.

- `provenance` ist ein echtes Subsystem mit Dokument-Parsing/Chunking, Provenance-Persistenz und KI-gestützter Dokumentanalyse. Es überschreitet heute die Grenzen zu Analysis, Knowledge und Shared-Services; eine sofortige Extraktion würde daher eine noch unklare Abhängigkeitsrichtung festschreiben.
- `preferences` bleibt zunächst in der Anwendung, bis Zuständigkeit und Persistenzabhängigkeiten eine eigene Feature-Grenze rechtfertigen.

Beide Bereiche werden erneut bewertet, nachdem die stärkeren Context-APIs existieren. So entstehen keine kleinen Module nur zur Erhöhung der Modulzahl.

## Übergangsweise Adapter-Kontexte

`com.taxonomy.dsl.storage..` / `com.taxonomy.dsl.export..` sowie die Spring-basierten Packages `com.taxonomy.export.service..` / `com.taxonomy.export.controller..` bleiben explizite Übergangskontexte, bis ihre besitzenden Ports stabil sind.

Sie sind **nicht** der Anfang eines generischen `taxonomy-adapters`-Moduls. Jeder Adapter soll letztlich bei dem Bounded Context liegen, dessen Port er implementiert. Die frameworkfreien Module lehnen weiterhin Spring-, JPA- und Anwendungsmodul-Abhängigkeiten ab.

## Zielrolle von `taxonomy-app`

Nach der Extraktion der Feature-Kontexte bleibt `taxonomy-app` der ausführbare Composition Root. Die Context-Map klassifiziert derzeit `composition`, `observability`, `security` und `shared` als `app-composition`; auch die Root-Klassen `AppConfig` und `TaxonomyApplication` sind Composition-Klassen.

Langfristig sollen dort nur tatsächlich anwendungsweite Verantwortlichkeiten verbleiben, insbesondere:

- Spring-Boot-Assembly und Cross-Context-Wiring;
- globale Security/Authentifizierung/Request-Identity;
- Top-Level-Konfiguration;
- globales Exception-Handling sowie Observability-/Health-Aggregation;
- echte Cross-Context-MVC-Orchestrierung ohne einzelnen Feature-Eigentümer;
- finales Packaging und Anwendungsressourcen.

Ein Package namens `shared` ist nicht automatisch eine Modulgrenze. Gemeinsame Klassen müssen zum niedrigsten stabilen Eigentümer verschoben werden oder als Composition-Belange verbleiben, wenn sie tatsächlich anwendungsweit sind.

## Architektur-Fitnessfunktionen

### Hypothesen-Zuständigkeit (C2 von #1043)

Hypothesen-Lebenszyklus, Git-autoritative Reviews, Review-Zustand und die
HTTP-Adapter unter `/api/dsl/hypotheses/**` gehören zu `relations`. Der verbleibende
`DslApiController` ruft keine Hypothesen-Services auf. Historische
`WorkspaceContext`-Argumente übersetzt der Workspace-eigene
`WorkspaceRepositoryContextPort`: Eine explizite Repository-Auswahl bleibt
erhalten, widersprüchliche Workspace-Herkunft scheitert vor Review oder
Branch-Zugriff, und zentrale Kontexte bleiben schreibgeschützt.

Hypothesen-Services nutzen Workspace-APIs für Repository-Kontext, exakte
Git-Lese-/Schreiboperationen und die Publikation erzeugter DSL-Snapshots. Sie
hängen weder von Workspace-Entities/-Repositories noch von konkretem DSL-Speicher
oder JGit ab. Fachliche Reviews und Transaktions-Callbacks bleiben bei Relations;
Snapshot-Publikation bleibt von Expected-Head-Commands und Editor-Checkpoints
getrennt.

C2 reduzierte kontextübergreifende Klassenpaare von 559 auf 540.
`versioning.service -> catalog/relations` und
`versioning.controller -> relations` sind jetzt null. Keine Zyklusausnahme wurde
hinzugefügt oder erweitert.

### Entscheidungsberichte in der Composition (D1 von #1043)

`DecisionRationaleReportController` gehört jetzt zu
`com.taxonomy.composition.report` in `taxonomy-app`. Er kombiniert
Berichtserzeugung/-darstellung aus Architecture, Katalog-Scores aus Knowledge und
Workspace-Herkunft. Die Versioning-HTTP-Adapter hängen über diesen Controller
nicht mehr von Architecture-Decision-/Report- oder Katalog-Services ab.

Der D1-Schritt erfasste **543 kontextübergreifende Klassenpaare** gegenüber 540
nach C2. Diese historische Änderung von **540 auf 543** machte drei
Workspace-API-Referenzen sichtbar, die zuvor innerhalb des Workspace-Kontexts
lagen; es entstand keine zusätzliche Runtime-Abhängigkeit.
`ArchitectureDecisionReportBoundaryTest` sichert den Composition-Eigentümer und
die Versioning-HTTP-Grenze ab.

### Git-Commit-Historie unter Workspace-Zuständigkeit (D2 von #1043)

Die aktuelle D2-Baseline enthält **537 kontextübergreifende Klassenpaare**,
gegenüber **543** nach D1. Die Änderung von **543 auf 537** ordnet die Projektion
der Git-Commit-Historie der Workspace-Versionierung zu:

| Typen | Zuständiges Package |
|---|---|
| `ArchitectureCommitIndex` | `com.taxonomy.versioning.model` |
| `ArchitectureCommitIndexRepository` | `com.taxonomy.versioning.repository` |
| `CommitIndexService`, `CommitIndexSearchLifecycle`, `CommitIndexSearchRebuilder` | `com.taxonomy.versioning.service` |

Der Git-Index trägt nicht mehr zu Workspace-zu-Architecture-Abhängigkeiten bei.
Entity-, Tabellen-, Suchindex- und Analyzer-Namen sowie Mandanten-/Branch-Scope
und Recovery-Verhalten bleiben unverändert. `ArchitectureCommitHistoryOwnershipTest`
verlangt, dass alle fünf Projektionstypen bei ihren Versioning-Eigentümern bleiben.

Die **vier verbleibenden Workspace-zu-Architecture-Klassenpaare** betreffen das
importierte `ArchitectureDslDocument`-Archiv und dessen Repository, auf die
DSL-Controller und Operations-Fassaden zugreifen. Dieses Archiv bleibt bei seinem
bestehenden Eigentümer; dieser Schritt schließt keine physische Modulextraktion ab.

Die Migration wird durch sich ergänzende Schutzmechanismen abgesichert:

1. `ArchitectureCycleBoundaryTest` verhindert undokumentierte Package-Zyklen. Temporäre Ausnahmen müssen in `.github/architecture-exceptions.json` stehen und ein Ablaufdatum besitzen.
2. `ArchitectureContextDependencyRatchetTest` zählt eindeutige direkte Class-to-Class-Abhängigkeiten zwischen geplanten, verbleibenden und übergangsweisen Kontexten je Package-Paar. Neue Kanten oder steigende Zähler schlagen fehl. Entfernt ein Refactoring Abhängigkeiten, muss die niedrigere Baseline im selben Change festgeschrieben werden, damit die Verbesserung später nicht unbemerkt zurückgeht.

3. `ArchitectureDecisionReportBoundaryTest` verhindert Berichtsorchestrierung in Versioning-HTTP-Adaptern und verlangt, dass der Bericht-Controller in `composition.report` bleibt.
4. `ArchitectureCommitHistoryOwnershipTest` verlangt, dass Entity, Repository und die drei Projektions-Services der Git-Commit-Historie in ihren Versioning-Owner-Packages bleiben.

Das fokussierte Architekturprofil wird vom Repository-Root über den vollständigen
Reactor ausgeführt, damit die Production-Outputs aller Module aktuell sind:

```bash
./mvnw test -Parchitecture-tests -Dsurefire.failIfNoSpecifiedTests=false
```

Das Profil enthält die Ownership-Guards für Entscheidungsberichte und
Commit-Historie sowohl in `pom.xml` als auch in `.mvn/verification-suites.json`. Die vollständige
CI-Verifikation bleibt `./mvnw -B verify -Pci`.

Der Ratchet durchläuft außerdem `taxonomy-app/src/main/java/com/taxonomy`: Jedes Production-Java-Package unterhalb des Root-Packages muss in `.github/architecture-contexts.json` klassifiziert sein. Ein neues Feature-Package kann den Dependency-Guard daher nicht umgehen, indem es außerhalb der vorhandenen Context-Patterns angelegt wird. Root-Level-Composition-Klassen bleiben ausdrücklich zulässig.

Der Ratchet ist bewusst eine **Ist-Baseline und keine Allowlist idealer Abhängigkeitsrichtungen**. Die gewünschte Architektur wird durch explizite Port-/Refactoring-PRs verbessert und anschließend monoton abgesichert.

## Migrationsreihenfolge

Issue #628 ist der Parent für die Umsetzung. Die vorgesehene Reihenfolge lautet:

1. Context-Map und Dependency-Ratchet festschreiben;
2. konkrete Editor-/Workspace-/Versioning-Kopplung entfernen und schmale Ports einführen;
3. Knowledge-/Search-Packagezyklen entfernen;
4. Architecture-/Export-/Analysis-Zyklen entfernen;
5. `taxonomy-workspace` extrahieren;
6. `taxonomy-knowledge` extrahieren;
7. `taxonomy-interop` und `taxonomy-templates` in separat reviewbaren Änderungen extrahieren;
8. Architecture/Analysis/Portfolio erst extrahieren, wenn deren Abhängigkeitsrichtung stabil ist;
9. `provenance` und `preferences` anhand des gemessenen Abhängigkeitsgraphen statt anhand der Modulzahl neu bewerten.

Physische Maven-Verschiebungen erfolgen damit **erst nach** der Durchsetzung logischer Grenzen. So bleiben die PRs reviewbar und vorhandene Kopplung wird nicht hinter neuen POM-Abhängigkeiten versteckt.
