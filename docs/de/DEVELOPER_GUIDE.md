# Taxonomy Architecture Analyzer — Entwicklerhandbuch

Dieses Handbuch richtet sich an Entwicklerinnen und Entwickler, die zum Taxonomy Architecture Analyzer beitragen.

---

## Inhaltsverzeichnis

- [Schnellstart](#schnellstart)
- [Modularchitektur](#modularchitektur)
- [Modulverantwortlichkeiten](#modulverantwortlichkeiten)
- [Wo Änderungen vorzunehmen sind](#wo-änderungen-vorzunehmen-sind)
- [Tests ausführen](#tests-ausführen)
- [Einen neuen REST-Endpunkt hinzufügen](#einen-neuen-rest-endpunkt-hinzufügen)
- [Ein neues Exportformat hinzufügen](#ein-neues-exportformat-hinzufügen)
- [Einen neuen LLM-Anbieter hinzufügen](#einen-neuen-llm-anbieter-hinzufügen)
- [DSL und JGit-Speicher](#dsl-und-jgit-speicher)
- [Hibernate Search und Lucene](#hibernate-search-und-lucene)
- [Testkonventionen](#testkonventionen)
- [Häufige Stolperfallen](#häufige-stolperfallen)

---

## Schnellstart

```bash
git clone https://github.com/carstenartur/Taxonomy.git
cd Taxonomy

# Compile all 4 modules (~5 seconds)
mvn compile

# Run all tests (~60 seconds, no Docker needed)
mvn test

# Run including integration tests (requires Docker)
mvn verify

# Start locally (browse-only, no API key needed)
mvn -pl taxonomy-app spring-boot:run
```

Öffnen Sie <http://localhost:8080>, um die Anwendung aufzurufen.

---

## Modularchitektur

Das Projekt ist ein Multi-Modul-Maven-Build mit vier Modulen:

```
Taxonomy/
├── taxonomy-domain/     Pure domain types (DTOs, enums) — no framework dependencies
├── taxonomy-dsl/        Architecture DSL (parser, model, validator, differ) — no framework dependencies
├── taxonomy-export/     Export services (ArchiMate, Visio, Mermaid) — no framework dependencies
└── taxonomy-app/        Spring Boot application (controllers, services, JPA, search, storage)
```

**Abhängigkeitsgraph:**

```
taxonomy-app  →  taxonomy-domain
taxonomy-app  →  taxonomy-dsl
taxonomy-app  →  taxonomy-export
taxonomy-export  →  taxonomy-domain
```

- `taxonomy-domain`, `taxonomy-dsl` und `taxonomy-export` haben **keine Spring-Abhängigkeiten** und können unabhängig getestet werden.
- Das Spring-Boot-JAR wird von `taxonomy-app` erzeugt.

---

## Modulverantwortlichkeiten

### taxonomy-domain

Reine Datentypen, die modulübergreifend genutzt werden:

| Paket | Inhalt |
|---|---|
| `com.taxonomy.dto` | DTOs (Data Transfer Objects) — `TaxonomyNodeDto`, `AnalysisResult`, `ArchitectureRecommendation`, `GapAnalysisView`, `SavedAnalysis` usw. |
| `com.taxonomy.model` | 6 Domain-Enums — `RelationType` (12 Werte), `SeedType`, `HypothesisStatus`, `ProposalStatus`, `SourceType`, `LinkType` |

### taxonomy-dsl

Framework-freie Architecture-DSL-Engine:

| Paket | Inhalt |
|---|---|
| `com.taxonomy.dsl.ast` | Abstrakte Syntaxbaum-Records — `BlockAst`, `PropertyAst`, `MetaAst`, `SourceLocation` |
| `com.taxonomy.dsl.model` | Kanonisches Architekturmodell — `CanonicalArchitectureModel`, `ArchitectureElement`, `ArchitectureRelation`, `ArchitectureView` usw. |
| `com.taxonomy.dsl.parser` | `TaxDslParser` (DSL-Text → AST → Modell), `DslTokenizer` (tokenisiert DSL-Text für die Indexierung) |
| `com.taxonomy.dsl.serializer` | `TaxDslSerializer` (Modell → DSL-Text) |
| `com.taxonomy.dsl.mapper` | `AstToModelMapper`, `ModelToAstMapper` — bidirektionale AST-↔-Modell-Konvertierung |
| `com.taxonomy.dsl.validation` | `DslValidator` — semantische Validierung einschließlich Relationstyp-Kombinationskompatibilität |
| `com.taxonomy.dsl.diff` | `ModelDiffer` — semantischer Diff zwischen zwei `CanonicalArchitectureModel`-Instanzen |

### taxonomy-export

Exportdienste — framework-frei, als Spring Beans über `ExportConfig` registriert:

| Paket | Inhalt |
|---|---|
| `com.taxonomy.export` | `ArchiMateDiagramService`, `VisioDiagramService`, `MermaidExportService`, `DiagramProjectionService` |
| `com.taxonomy.archimate` | ArchiMate-Modell-Records — `ArchiMateModel`, `ArchiMateElement`, `ArchiMateRelationship` usw. |
| `com.taxonomy.visio` | Visio-Dokumentmodell + XStream-Konverter |
| `com.taxonomy.diagram` | Neutrales Diagrammmodell-Records — `DiagramModel`, `DiagramNode`, `DiagramEdge`, `DiagramLayout` |

### taxonomy-app

Die Haupt-Spring-Boot-Anwendung:

| Verzeichnis | Inhalt |
|---|---|
| `controller/` | REST-Controller |
| `service/` | Service-Klassen — LLM, Suche, Architektur, Graph, Proposals, Reports usw. |
| `model/` | JPA-Entitäten — `TaxonomyNode`, `TaxonomyRelation`, `RelationProposal`, `RelationHypothesis`, `ArchitectureCommitIndex` usw. |
| `repository/` | Spring Data JPA Repositories |
| `config/` | Konfigurationsklassen — Sicherheit, Rate Limiting, Hibernate-Search-Analysatoren, OpenAPI, Actuator |
| `search/` | Hibernate-Search-Konfiguration |
| `dsl/storage/` | JGit-DFS-Speicher auf Hibernate-Basis — `DslGitRepository`, `HibernateRepository` usw. |
| `dsl/storage/jgit/` | JPA-Entitäten für Git-Speicher — `GitPackEntity`, `GitReflogEntity` |
| `resources/data/` | Excel-Arbeitsmappe, CSV-Fallback, JSON-Taxonomie |
| `resources/prompts/` | LLM-Prompt-Vorlagen (eine pro Taxonomieblatt + Standardvorlagen) |
| `resources/static/js/` | JavaScript-Module (UI-Logik) |
| `resources/templates/` | Einzelnes Thymeleaf-Template (`index.html`) |

---

## Wo Änderungen vorzunehmen sind

| Ich möchte… | Wo nachschauen |
|---|---|
| Einen neuen Taxonomie-Endpunkt hinzufügen | `taxonomy-app/.../controller/` — einen `@RestController` erstellen oder erweitern |
| Einen neuen Service hinzufügen | `taxonomy-app/.../service/` — eine `@Service`-Klasse erstellen |
| Ein neues Exportformat hinzufügen | `taxonomy-export/.../export/` — den Exporter implementieren und in `ExportConfig` registrieren |
| Eine neue JPA-Entität hinzufügen | `taxonomy-app/.../model/` — mit `@Entity` annotieren |
| Ein neues DTO hinzufügen | `taxonomy-domain/.../dto/` — einen Record oder eine Klasse erstellen |
| Die DSL-Grammatik ändern | `taxonomy-dsl/.../parser/TaxDslParser.java` |
| Eine DSL-Validierungsregel hinzufügen | `taxonomy-dsl/.../validation/DslValidator.java` |
| Die Benutzeroberfläche ändern | `taxonomy-app/src/main/resources/templates/index.html` + `static/js/` |
| Einen neuen LLM-Prompt hinzufügen | `taxonomy-app/src/main/resources/prompts/` — `XX.txt` erstellen |
| Die Konfiguration ändern | `taxonomy-app/src/main/resources/application.properties` |

---

## Tests ausführen

```bash
# All unit + Spring context tests (no Docker needed)
mvn test

# Tests for a single module
mvn test -pl taxonomy-dsl
mvn test -pl taxonomy-app

# Tests for a single class
mvn test -pl taxonomy-app -Dtest=TaxonomyApplicationTests

# Integration tests (requires Docker for Testcontainers)
mvn verify

# Screenshot generation (requires Docker + optionally GEMINI_API_KEY)
mvn package -DskipTests
mvn failsafe:integration-test -DgenerateScreenshots=true -Dit.test=ScreenshotGeneratorIT
```

### Integrationstests für externe Datenbanken

Das Projekt enthält Integrationstests, die die korrekte Funktionsweise der Anwendung mit PostgreSQL, Microsoft SQL Server und Oracle-Datenbanken verifizieren. PostgreSQL- und MSSQL-Tests werden als Teil des standardmäßigen `mvn verify`-Builds ausgeführt (Docker erforderlich). Oracle-Tests sind **optional** — mit `db-oracle` getaggt und standardmäßig ausgeschlossen. Um spezifische Datenbanktests auszuführen:

```bash
# Run only PostgreSQL integration tests
mvn verify -DexcludedGroups=real-llm -Dit.test="*Postgres*IT"

# Run only MSSQL integration tests
mvn verify -DexcludedGroups=real-llm -Dit.test="*Mssql*IT"

# Run only Oracle integration tests
mvn verify -DexcludedGroups=real-llm -Dit.test="*Oracle*IT"

# Run ALL external database tests
mvn verify -DexcludedGroups=real-llm

# Run all Selenium + external-db tests
mvn verify -DexcludedGroups=real-llm -Dit.test="Selenium*ContainerIT"
```

**Architektur:** Jede externe Datenbank-Testklasse erbt von `AbstractDatabaseContainerIT` (REST-/Diagnosetests) oder `AbstractSeleniumContainerIT` (Selenium-UI-Tests). Die Basisklassen enthalten die gesamte Testlogik; datenbankspezifische Unterklassen umfassen ca. 30 Zeilen Konfiguration, die den Datenbank-Container und die JDBC-Umgebungsvariablen für den App-Container festlegen.

**Funktionsweise:** Das Anwendungs-JAR wird einmal gebaut und in einem Docker-Container (`eclipse-temurin:17-jre-alpine`) ausgeführt. Ein Datenbank-Container (PostgreSQL, MSSQL oder Oracle) läuft im selben Docker-Netzwerk. Der App-Container erhält `SPRING_PROFILES_ACTIVE` (z. B. `mssql` oder `postgres`), um das datenbankspezifische Spring-Profil zu aktivieren, sowie Umgebungsvariablen wie `TAXONOMY_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` usw., um spezifische Verbindungseinstellungen zu überschreiben.

Konventionen für Testdateinamen:

| Muster | Runner | Beschreibung |
|---|---|---|
| `*Test.java`, `*Tests.java` | maven-surefire-plugin | Unit- und Spring-Kontexttests |
| `*IT.java` | maven-failsafe-plugin | Integrationstests (Docker erforderlich) |
| `@Tag("db-postgres")` | maven-failsafe-plugin | PostgreSQL-Tests (standardmäßig enthalten) |
| `@Tag("db-mssql")` | maven-failsafe-plugin | MSSQL-Tests (standardmäßig enthalten) |
| `@Tag("db-oracle")` | maven-failsafe-plugin | Oracle-Tests (standardmäßig ausgeschlossen) |

---

## Einen neuen REST-Endpunkt hinzufügen

1. Erstellen oder erweitern Sie einen Controller in `taxonomy-app/src/main/java/com/taxonomy/controller/`.
2. Falls der Endpunkt ein neues DTO zurückgibt, fügen Sie es unter `taxonomy-domain/src/main/java/com/taxonomy/dto/` hinzu.
3. Falls der Endpunkt einen neuen Service benötigt, fügen Sie ihn unter `taxonomy-app/src/main/java/com/taxonomy/service/` hinzu.
4. Fügen Sie Tests hinzu (typischerweise mit `@SpringBootTest` + `@AutoConfigureMockMvc` + `@WithMockUser(roles = "ADMIN")`).
5. Falls der Endpunkt nur für Administratoren bestimmt ist, platzieren Sie ihn unter `/api/admin/` (geschützt durch `ROLE_ADMIN` in `SecurityConfig`).
6. Falls der Endpunkt Architekturdaten ändert (Relationen, DSL), platzieren Sie ihn unter `/api/relations/`, `/api/dsl/` oder `/api/git/` — Schreiboperationen auf diesen Pfaden erfordern `ROLE_ARCHITECT` oder `ROLE_ADMIN`.

---

## Ein neues Exportformat hinzufügen

1. Fügen Sie die Exporter-Klasse unter `taxonomy-export/src/main/java/com/taxonomy/export/` hinzu.
2. Registrieren Sie sie als Spring Bean in `taxonomy-app/src/main/java/com/taxonomy/config/ExportConfig.java`.
3. Fügen Sie einen Controller-Endpunkt in `ApiController` hinzu oder erstellen Sie einen neuen Controller.
4. Fügen Sie Tests unter `taxonomy-app/src/test/` hinzu.

---

## Einen neuen LLM-Anbieter hinzufügen

Der `LlmService` unterstützt mehrere LLM-Anbieter. Um einen neuen hinzuzufügen:

1. Fügen Sie die API-Key-Eigenschaft zu `application.properties` hinzu (z. B. `newprovider.api.key=${NEW_PROVIDER_API_KEY:}`).
2. Fügen Sie den Anbieternamen zur automatischen Erkennungslogik in `LlmService` hinzu.
3. Implementieren Sie den HTTP-Aufruf in `LlmService` nach dem Muster der bestehenden Anbieter.
4. Aktualisieren Sie `LlmResponseParser`, falls sich das Antwortformat unterscheidet.
5. Dokumentieren Sie den neuen Anbieter in `CONFIGURATION_REFERENCE.md`.

---

## DSL und JGit-Speicher

Das Architecture-DSL-Subsystem verwendet JGit DFS (Distributed File System), wobei alle Git-Objekte in der HSQLDB-Datenbank gespeichert werden — es wird kein Dateisystem verwendet. Die DSL dient als **Single Source of Truth** für Architekturdefinitionen.

**DSL-Modul (`taxonomy-dsl`)** — Spring-unabhängige Bibliothek:

| Paket | Aufgabe |
|---|---|
| `dsl.parser` | Parser mit geschweiften Klammern: DSL-Text → `DocumentAst` |
| `dsl.serializer` | Deterministischer Serializer: `DocumentAst` → DSL-Text (sortierte Blöcke, kanonische Eigenschaftsreihenfolge, Escape-Sequenzen) |
| `dsl.ast` | AST-Knotentypen: `DocumentAst`, `BlockAst`, `PropertyAst`, `MetaAst` |
| `dsl.model` | Kanonisches Modell: `CanonicalArchitectureModel`, `ArchitectureElement`, `ArchitectureRelation` usw. |
| `dsl.mapper` | Bidirektionale Zuordnung: AST ↔ kanonisches Modell |
| `dsl.validation` | Validierung: Duplikat-IDs, referenzielle Integrität, Relationstyp-Kompatibilitätsmatrix |
| `dsl.diff` | Semantischer Diff: `ModelDiffer` vergleicht zwei Modelle; `SemanticDiffDescriber` erzeugt menschenlesbare Beschreibungen |

**Serialisierungsgarantien** (entscheidend für Git-freundliche Diffs):

- **Blocksortierung**: Sortiert nach Art (Element → Relation → Anforderung → Zuordnung → Ansicht → Evidenz), dann nach primärer ID
- **Eigenschaftsreihenfolge**: Kanonische Reihenfolge pro Blockart (Title → Description → Taxonomy für Elemente usw.)
- **Erweiterungen**: Alphabetisch sortiert nach bekannten Eigenschaften
- **Escape-Sequenzen**: `\"` und `\\` in zitierten Werten
- **Round-Trip-Stabilität**: `parse → serialize → parse → serialize` erzeugt immer identische Ausgabe

**JGit-Integrationsklassen:**

| Klasse | Aufgabe |
|---|---|
| `DslGitRepository` | Fassade für Commit-, Lese-, Branch- und Diff-Operationen |
| `HibernateObjDatabase` | Speichert Git-Pack-Daten als BLOBs in der `git_packs`-Tabelle |
| `HibernateRefDatabase` | Speichert Git-Refs als Reftables in Pack-Erweiterungen |
| `HibernateRepository` | Erweitert JGit `DfsRepository` mit Datenbank-Backends |
| `GitPackEntity` | JPA-Entität für die `git_packs`-Tabelle |
| `GitReflogEntity` | JPA-Entität für die `git_reflog`-Tabelle |

**Wichtig:** `DfsBlockCache` ist ein JVM-globaler Singleton. Pack-Namen **müssen** über alle `HibernateObjDatabase`-Instanzen innerhalb derselben JVM eindeutig sein. Der `packIdCounter` ist pro Instanz und wird mit `System.nanoTime()` initialisiert, um Kollisionen bei Test-Kontext-Neustarts zu vermeiden.

### Quell-Provenienz-Architektur

Die DSL unterstützt **Provenienz-Blöcke**, die Anforderungen mit ihrem
ursprünglichen Quellmaterial verknüpfen. Das Provenienz-Modell besteht aus drei
Schichten:

1. **DSL-Schicht** (`taxonomy-dsl`): Vier neue Blocktypen — `source`,
   `sourceVersion`, `sourceFragment`, `requirementSourceLink` — werden wie
   `element`- oder `relation`-Blöcke geparst und serialisiert.

2. **JSON-Export-Schicht** (`taxonomy-domain`): `SavedAnalysis` (Version 2) kann
   optional `sources`, `sourceVersions`, `sourceFragments` und
   `requirementSourceLinks` enthalten.

3. **Laufzeit/DB-Schicht** (`taxonomy-app`): JPA-Entitäten `SourceArtifact`,
   `SourceVersion`, `SourceFragment` und `RequirementSourceLink` speichern
   Provenienz-Daten in der Datenbank.

Bei der Ergänzung neuer Provenienz-Features:

- Neue Blocktypen in `TaxDslParser.KNOWN_BLOCK_TYPES` registrieren
- Modellklassen in `com.taxonomy.dsl.model` hinzufügen
- `AstToModelMapper` und `ModelToAstMapper` erweitern
- Eigenschaftsreihenfolge in `TaxDslSerializer.PROPERTY_ORDER` hinzufügen
- Tokens in `DslTokenizer.STRUCTURE_TOKENS` registrieren

---

## Hibernate Search und Lucene

Die Anwendung verwendet Hibernate Search 8 mit einem Lucene-9-Backend für Volltextsuche und KNN-basierte semantische Suche.

**Benutzerdefinierte Analysatoren** (registriert in `HibernateSearchAnalysisConfigurer`):

| Analysator | Zweck |
|---|---|
| `dsl` | WhitespaceTokenizer + LowerCaseFilter — für DSL-Block-Schlüsselwörter und Property-Token |
| `csv-keyword` | PatternTokenizer mit Trennung an Komma/Semikolon — für Element- und Relationsbezeichner-Felder |
| `english` | Standard-English-Analysator für Commit-Nachrichten |
| `german` | Standard-German-Analysator für deutschsprachige Felder |

**Indizierte Entitäten:** `TaxonomyNode` (Volltext + KNN), `TaxonomyRelation` (Volltext + KNN), `ArchitectureCommitIndex` (Volltext).

---

## Testkonventionen

- Tests verwenden `@SpringBootTest` mit HSQLDB-In-Memory und `@AutoConfigureMockMvc`.
- DSL-Modultests (`taxonomy-dsl`) sind reine JUnit-5-Tests — kein Spring-Kontext.
- JGit-Speichertests (`DslGitRepositoryTest`) sind reine JUnit-5-Tests mit datenbankgestütztem `HibernateRepository`.
- Integrationstests, die Docker erfordern, folgen dem `*IT.java`-Namensschema.
- Tests rufen **niemals** echte LLM-APIs auf — verwenden Sie stattdessen `LlmService`-Mocking.

### Sicherheitskonfiguration für Tests

Spring Boot 4 wendet `SecurityMockMvcConfigurers.springSecurity()` **nicht** automatisch auf MockMvc an. Das Projekt stellt `TestSecuritySupport` (in den Test-Quellen) bereit, das einen `MockMvcBuilderCustomizer`-Bean registriert, um:

1. Die Spring-Security-Filterkette auf alle MockMvc-Anfragen anzuwenden.
2. CSRF-Tokens automatisch auf Testanfragen anzuwenden.

Alle MockMvc-Testklassen müssen `@WithMockUser(roles = "ADMIN")` (oder eine restriktivere Rolle) verwenden, um einen authentifizierten Benutzer zu simulieren. Ohne diese Annotation geben Anfragen HTTP 401/403 zurück.

```java
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(roles = "ADMIN")
class MyControllerTest {
    @Autowired
    private MockMvc mockMvc;
    // tests here
}
```

---

## Häufige Stolperfallen

1. **HSQLDB-NCLOB-Einschränkung:** `@Nationalized @Lob`-Felder werden auf HSQLDB als `NCLOB` abgebildet. JPQL-Funktionen wie `LOWER()` können nicht auf NCLOB-Spalten angewendet werden. Verwenden Sie `@Column(length=N)` ohne `@Lob` für Felder, die JPQL-String-Funktionen benötigen.

2. **JGit-DfsBlockCache-Kollisionen:** Der Cache ist ein JVM-globaler Singleton, der nach `(Repository-Name + Pack-Name)` schlüsselt. Wenn Pack-Namen kollidieren (z. B. durch statische Zähler), verursachen veraltete Cache-Einträge `REJECTED_MISSING_OBJECT`-Fehler. Verwenden Sie immer instanzspezifische Zähler.

3. **Spring-Test-Kontext-Caching:** Spring cached `@SpringBootTest`-Kontexte zur Wiederverwendung. In Kombination mit `ddl-auto=create` kann die Tabellenneuanlage den Zustand in Beans aus älteren Kontexten ungültig machen. Beans, die internen Zustand mit Bezug auf DB-Inhalte halten, müssen damit angemessen umgehen.

4. **SimpleDriverDataSource:** Die Anwendung verwendet `SimpleDriverDataSource` anstelle von HikariCP für die In-Process-HSQLDB. Das bedeutet keinen Connection Pool — was beabsichtigt ist, um den Speicherverbrauch zu reduzieren. Wechseln Sie nicht zu HikariCP, ohne die Auswirkungen zu verstehen.

5. **Rate Limiting in Tests:** Der `RateLimitFilter` ist während `@SpringBootTest`-Tests aktiv. Wenn Tests LLM-gestützte Endpunkte schnell hintereinander aufrufen, können sie vom Rate Limiting betroffen sein. Erwägen Sie bei Bedarf die Deaktivierung über `TAXONOMY_RATE_LIMIT_PER_MINUTE=0` in der Testkonfiguration.

---

## Definition of Done — Benutzer-sichtbare Funktionen

> **Produktregel:** Der Taxonomy Architecture Analyzer ist ein **GUI-first**-Produkt.
> REST-/API-Endpunkte unterstützen Automatisierung, CI-Integration und Admin-Werkzeuge — sie sind
> **kein** Ersatz für GUI-Workflows, die für Endbenutzer bestimmt sind.

Eine benutzer-sichtbare Funktion gilt nur als **abgeschlossen**, wenn ALLE folgenden Kriterien erfüllt sind:

| Kriterium | Erforderlich für Endbenutzer-Funktionen | Erforderlich für Admin-/Automatisierungsfunktionen |
|---|:---:|:---:|
| GUI-Flow existiert in `index.html` + JS-Module | ✅ | — |
| Benutzerhandbuch (`USER_GUIDE.md`) dokumentiert den Workflow | ✅ | — |
| Screenshots zeigen den funktionierenden Produktzustand | ✅ | — |
| Eingebettete Hilfe / Tooltip in der GUI | ✅ (wo zutreffend) | — |
| REST-Endpunkt existiert | ✅ | ✅ |
| API_REFERENCE.md aktualisiert | ✅ | ✅ |
| Integrations-/Unit-Tests vorhanden | ✅ | ✅ |
| FEATURE_MATRIX.md-Zeile aktualisiert | ✅ | ✅ |

### Was NICHT als abgeschlossene Funktion zählt:

- ❌ Ein REST-Endpunkt existiert, aber kein GUI-Button/-Dialog/-Panel referenziert ihn
- ❌ Ein curl-Beispiel ist dokumentiert, aber keine UI-Anleitung existiert
- ❌ Swagger zeigt den Endpunkt, aber das Benutzerhandbuch beschreibt ihn nicht
- ❌ Ein Screenshot zeigt einen Fehlerzustand anstelle des erwarteten Ergebnisses

### Klassifizierung von Funktionen

| Kategorie | Liefererwartung | Beispiele |
|---|---|---|
| **GUI-first (Endbenutzer)** | GUI + Doku + Hilfe + Screenshot + REST | Analyse, Export, Baum-Exploration, Vorschläge, Vergleich, Verlauf |
| **API-first (Automatisierung)** | REST + API-Doku | Diagnose, Embedding-Status, Admin-Benutzerverwaltung, CI-Trigger |
| **Nur Admin** | REST oder GUI (Admin-Panel) + API-Doku | Benutzer-CRUD, Workspace-Eviction, Rate-Limit-Konfiguration |

### Zweisprachige UI-Regel (Deutsch / Englisch)

Das Produkt unterstützt sowohl deutsche als auch englische Benutzer:
- Alle **UI-Labels, Buttons, Tooltips und Hilfetexte** müssen in beiden Sprachen vorhanden sein
  (verwaltet über Thymeleaf-i18n oder JS-Locale-Bundles)
- Dokumentation unter `docs/en/` ist auf Englisch
- Übersetzungen werden in `docs/de/` (Deutsch) gepflegt
- Die README ist nur auf Englisch (internationales Publikum)
- **Validierung:** Beim Hinzufügen eines neuen UI-Elements bestätigen, dass sowohl `messages.properties`
  als auch `messages_de.properties` (oder der entsprechende i18n-Mechanismus) die Übersetzung enthalten

### Terminologieregeln

Benutzer-sichtbarer Text muss domänengerechte Begriffe anstelle von reiner Git-Terminologie verwenden:

| Verwende dies | Nicht dies |
|----------|----------|
| „Gemeinsamer Bereich" / „Shared Space" | „Central Repository" |
| „Mein Arbeitsbereich" / „My Workspace" | „User Repository" |
| „Variante" / „Variant" | „Branch" (in benutzer-sichtbaren Kontexten) |
| „Für Team veröffentlichen" / „Publish for Team" | „Push" oder „Merge" |
| „Vom Team synchronisieren" / „Sync from Team" | „Pull" oder „Fetch" |
| „Aktuelle Version" / „Current Version" | „HEAD" |
| „Einzeländerung übernehmen" / „Apply Single Change" | „Cherry-Pick" |
| „Integrieren" / „Integrate" | „Merge" (in benutzer-sichtbaren Kontexten) |

**Niemals** rohe Git-Begriffe (`fork`, `clone`, `fetch`, `refs`, `rebase`) in der
Standard-Benutzeroberfläche anzeigen. Diese dürfen in Entwicklerdokumentation und Admin-Werkzeugen erscheinen.

---

## Screenshot-Konventionen

Screenshots werden automatisch von `ScreenshotGeneratorIT` generiert und in `docs/images/` gespeichert.

### Regeln:

1. **Nur funktionierende Zustände:** Screenshots müssen erfolgreiche, repräsentative Produktzustände zeigen.
   Keine Screenshots mit:
   - Backend-Fehlerseiten (500, Stack Traces)
   - Leeren/Ladezuständen ohne Daten
   - Fehlerhaften Layouts oder JS-Fehlern in der Konsole

2. **Namenskonvention:** `NN-beschreibender-name.png` (z. B. `15-scored-taxonomy-tree.png`)

3. **Neugenerierung:** Nach jeder UI-Änderung betroffene Screenshots neu generieren:
   ```
   mvn failsafe:integration-test -DgenerateScreenshots=true -Dit.test=ScreenshotGeneratorIT
   ```

4. **Checkliste für PRs mit UI-Änderungen:**
   - [ ] Screenshots neu generiert
   - [ ] Keine Fehlerzustände in Screenshots
   - [ ] Benutzerhandbuch-Referenzen aktualisiert, wenn sich das Layout geändert hat
   - [ ] Sowohl DE- als auch EN-Labels werden korrekt dargestellt

---

## Internationalisierung (i18n) — Deutsch / Englisch

Die Produkt-UI unterstützt sowohl Deutsch als auch Englisch. Der aktuelle i18n-Mechanismus verwendet:
- Thymeleaf `th:text` mit Message-Keys für server-gerendertes HTML
- JavaScript-Locale-Bundles für clientseitige Texte

### Einen neuen UI-Text hinzufügen

1. Den englischen Text in `messages.properties` hinzufügen (oder ins JS-Locale-Bundle)
2. Die deutsche Übersetzung in `messages_de.properties` hinzufügen
3. Den Message-Key im Thymeleaf-Template oder JS-Modul verwenden — niemals Text hart kodieren
4. Beide Sprachen durch Wechseln der Browser-Locale verifizieren

> **Benutzer-sichtbare Funktionen sind nicht abgeschlossen, wenn sie nur per REST existieren.**
> Jeder sichtbare Text in Templates muss `th:text="#{...}"` verwenden. Jeder JavaScript-generierte
> Text muss `TaxonomyI18n.t('key')` verwenden. Hart kodierte Textliterale sind nicht akzeptabel.
> Die bestehenden Tests `I18nApiControllerTest.englishAndGermanHaveSameKeys()` und
> `HelpControllerTest.everyRegisteredDocHasI18nKeys()` erzwingen dies auf CI-Ebene.

---

## Dokumentations-Aktualisierungsregel

> **Pflicht:** Die Dokumentation muss aktualisiert werden, wenn sich Folgendes ändert:
>
> - **Benutzer-sichtbares Verhalten** — neue oder geänderte GUI-Flows, Buttons, Panels, Dialoge
> - **Workspace-Semantik** — Workspace-Lebenszyklus, Provisionierung, Multi-User-Isolation, Sync-Verhalten
> - **Versionierungsverhalten** — Branching, Merging, Cherry-Picking, Konfliktlösung, DSL-Formatänderungen
> - **Hilfe-Inhalte** — eingebettete Hilfe-Themen, Tooltips, Onboarding-Flows
> - **REST-API-Verträge** — neue Endpunkte, geänderte Request/Response-Schemas, entfernte Endpunkte
> - **Konfigurationsoptionen** — neue Umgebungsvariablen, geänderte Standardwerte, veraltete Einstellungen

### Was aktualisiert werden muss

| Änderungstyp | Zu aktualisierende Dokumente |
|---|---|
| Neue GUI-Funktion | `USER_GUIDE.md`, `FEATURE_MATRIX.md`, Screenshot via `ScreenshotGeneratorIT` |
| Neuer REST-Endpunkt | `API_REFERENCE.md`, `CURL_EXAMPLES.md` |
| Neuer DSL-Block/Property | `CONCEPTS.md`, `GIT_INTEGRATION.md`, `DEVELOPER_GUIDE.md` |
| Workspace-Modell-Änderung | `WORKSPACE_VERSIONING.md`, `CONCEPTS.md`, internes `WORKSPACE_DESIGN.md` |
| Neue Konfigurationsvariable | `CONFIGURATION_REFERENCE.md`, `DEPLOYMENT_GUIDE.md` |
| Neues Hilfe-Dokument | In `HelpController.DOC_METADATA` registrieren, `help.toc.*` i18n-Keys hinzufügen, `docs/en/` und `docs/de/` Dateien erstellen |
| Jede i18n-sichtbare Änderung | Sowohl `messages.properties` als auch `messages_de.properties` |

### Durchsetzung

Die folgenden CI-Tests erkennen häufige Dokumentationsdrift:

- `HelpControllerTest.everyEnglishDocFileIsRegistered()` — jede `docs/en/*.md` muss in `HelpController` registriert sein
- `HelpControllerTest.everyRegisteredDocHasI18nKeys()` — jedes registrierte Dokument muss EN + DE i18n-Keys haben
- `I18nApiControllerTest.englishAndGermanHaveSameKeys()` — EN- und DE-Bundles müssen identische Key-Sets haben
