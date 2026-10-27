# Taxonomy Architecture Analyzer — Entwicklerhandbuch

Dieses Handbuch richtet sich an Entwicklerinnen und Entwickler, die zum Taxonomy Architecture Analyzer beitragen.

> **Neu in der Codebasis?**
> Beginnen Sie mit dem [aufgabenorientierten Einstiegspunkt](https://github.com/carstenartur/Taxonomy/blob/main/docs/dev/00-start-here.md),
> um den kleinsten sicheren Änderungsbereich für Ihre konkrete Aufgabe zu finden.
> Das vorliegende Dokument ist die **tiefergehende Architektuurreferenz**.

---

## Inhaltsverzeichnis

- [Aufgabenorientierter Leitfaden (docs/dev/)](#aufgabenorientierter-leitfaden)
- [Erweiterungspunkte](#erweiterungspunkte)
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
- [Architekturkonventionen](#architekturkonventionen)
- [Definition of Done — Benutzer-sichtbare Funktionen](#definition-of-done--benutzer-sichtbare-funktionen)
- [Wartbarkeitsmatrix](#wartbarkeitsmatrix)

---

## Aufgabenorientierter Leitfaden

Das Verzeichnis `docs/dev/` bietet eine aufgabenorientierte Übersicht für Entwicklerinnen und Entwickler,
die einen bestimmten Teil des Systems ändern möchten, ohne die gesamte Architekturreferenz lesen zu müssen.

| Datei | Zweck |
|---|---|
| [`docs/dev/00-start-here.md`](https://github.com/carstenartur/Taxonomy/blob/main/docs/dev/00-start-here.md) | Wie man eine einzelne Aufgabe angeht |
| [`docs/dev/01-change-map.md`](https://github.com/carstenartur/Taxonomy/blob/main/docs/dev/01-change-map.md) | Tabelle: Aufgaben → Pakete, Einstiegspunkte und Tests |
| [`docs/dev/07-extension-points.md`](https://github.com/carstenartur/Taxonomy/blob/main/docs/dev/07-extension-points.md) | Stabile Erweiterungsanker für häufige Feature-Ergänzungen |
| [`docs/dev/06-testing-by-change-type.md`](https://github.com/carstenartur/Taxonomy/blob/main/docs/dev/06-testing-by-change-type.md) | Maven-Befehle und Testklassen nach Änderungstyp |
| [`docs/dev/tasks/add-llm-provider.md`](https://github.com/carstenartur/Taxonomy/blob/main/docs/dev/tasks/add-llm-provider.md) | Einen neuen LLM-Anbieter hinzufügen |
| [`docs/dev/tasks/add-export-format.md`](https://github.com/carstenartur/Taxonomy/blob/main/docs/dev/tasks/add-export-format.md) | Ein neues Exportformat hinzufügen |
| [`docs/dev/tasks/add-relation-type.md`](https://github.com/carstenartur/Taxonomy/blob/main/docs/dev/tasks/add-relation-type.md) | Einen neuen Relationstyp hinzufügen |
| [`docs/dev/tasks/add-architecture-view-step.md`](https://github.com/carstenartur/Taxonomy/blob/main/docs/dev/tasks/add-architecture-view-step.md) | Einen Architekturansicht-Pipeline-Schritt hinzufügen |
| [`docs/dev/tasks/add-document-import-mapping.md`](https://github.com/carstenartur/Taxonomy/blob/main/docs/dev/tasks/add-document-import-mapping.md) | Ein Dokumentimport-Mapping hinzufügen |
| [`docs/dev/tasks/add-workspace-operation.md`](https://github.com/carstenartur/Taxonomy/blob/main/docs/dev/tasks/add-workspace-operation.md) | Eine Workspace-Operation hinzufügen |
| [`docs/dev/tasks/add-ui-panel.md`](https://github.com/carstenartur/Taxonomy/blob/main/docs/dev/tasks/add-ui-panel.md) | Ein UI-Panel hinzufügen |
| [`docs/dev/tasks/add-dsl-property.md`](https://github.com/carstenartur/Taxonomy/blob/main/docs/dev/tasks/add-dsl-property.md) | Eine DSL-Eigenschaft oder einen Blocktyp hinzufügen |

> Das Verzeichnis `docs/dev/` ist der **aufgabenorientierte Einstiegspunkt**.
> Die nachfolgenden Abschnitte bleiben die **tiefergehende Referenz** für Module, Konventionen,
> Stolperfallen und Architekturentscheidungen.

---

## Erweiterungspunkte

Bevor Sie einen Anbieter, Exporter, Relationstyp, Pipeline-Schritt oder DSL-Bereich hinzufügen,
konsultieren Sie zuerst den dedizierten Erweiterungsleitfaden:

- [`docs/dev/07-extension-points.md`](https://github.com/carstenartur/Taxonomy/blob/main/docs/dev/07-extension-points.md)

Er unterscheidet zwischen:

- **expliziten SPIs** (Registries/Interfaces bereits vorhanden) und
- **impliziten, aber dokumentierten Ankern** (die Codebasis hat eine stabile Fassade,
  einen Service oder eine Dateigrenze, aber noch keine Registry).

Verwenden Sie den Erweiterungsleitfaden, um den kleinsten sicheren Ausgangspunkt zu finden,
bevor Sie die tiefergehenden Architekturabschnitte lesen.

---

## Schnellstart

```bash
git clone https://github.com/carstenartur/Taxonomy.git
cd Taxonomy

# Begrenzte Entwicklerprüfung
./mvnw verify

# Maßgebliche CI-Prüfung mit ihren dokumentierten Laufzeitvoraussetzungen
./mvnw -B verify -Pci -DrunOnnxTests=true

# Anwendung und benötigte Bibliotheken paketieren, dann vom Repository-Root starten
./mvnw -pl taxonomy-app -am package -DskipTests
java -Dloader.path=taxonomy-app/target/features -jar taxonomy-app/target/taxonomy-app-*.jar --taxonomy.plugins.directory=taxonomy-app/target/plugins
```

Öffnen Sie <http://localhost:8080>. Die Kataloganzeige benötigt keinen LLM-Schlüssel.
Die erste lokale Administratoranmeldung beschreibt die [README](https://github.com/carstenartur/Taxonomy/blob/main/README.md#quick-start).

---

## Modularchitektur

Das vollständige Reactor-Inventar und die Zuständigkeiten stehen in
[Modulgrenzen](MODULE_BOUNDARIES.md). [Architektur](ARCHITECTURE.md#modularchitektur)
zeigt den Fachmodulgraphen sowie getrennte Laufzeit- und Persistenzansichten.

Der Reactor trennt frameworkfreie Verträge, Laufzeit-Fachmodule, die Plugin-Laufzeit,
ein unabhängiges Mermaid-Plugin, Anwendungskomposition und Build-Werkzeuge.
Fachbibliotheken hängen nicht zurück von `taxonomy-app` ab. Berichtsmodelle gehören
zu `taxonomy-reporting-api`, Renderer zu `taxonomy-reporting` und generische
Vorlagenverträge zu `taxonomy-templates-api`.

`taxonomy-app` erzeugt die ausführbare Anwendung. Der lokale Standardmodus verwendet
einen Prozess; optionale Artemis-Ausführung nutzt dasselbe Image für Koordinator-
und Workerprozesse. Siehe [Analyseausführung](ARCHITECTURE.md#analyseausführung-und-deployment).
Dadurch werden nicht sämtliche Web-/Hintergrundfunktionen clusterfähig. Build-/Release-
Werkzeuge, Coverage-Aggregation und Repository-weite Prüfungen sind keine Laufzeit-Fachdienste.

---

## Modulverantwortlichkeiten

### taxonomy-domain

Reine Datentypen, die modulübergreifend genutzt werden:

| Paket | Inhalt |
|---|---|
| `com.taxonomy.dto` | DTOs (Data Transfer Objects) — `TaxonomyNodeDto`, `AnalysisResult`, `ArchitectureRecommendation`, `GapAnalysisView`, `SavedAnalysis` usw. |
| `com.taxonomy.model` | Fachliche Enums und Kontextwerte, darunter `RelationType`, `SeedType`, `HypothesisStatus`, `ProposalStatus`, `SourceType`, `LinkType` und `WorkspaceOverlayScope` |

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

Einzige ausführbare Kompositions- und Deployment-Wurzel, nicht Eigentümer des
gesamten Spring-basierten Fachcodes. Fachliche Controller, Services, Entitäten,
Repositories und ihre Tests gehören in die unter
[Modulgrenzen](MODULE_BOUNDARIES.md) genannte Fachbibliothek. Katalog/Suche gehören
beispielsweise zu `taxonomy-knowledge`, Prompts und LLM-Analyse zu
`taxonomy-analysis`, Versionierung, Editorhistorie und JGit-Speicher zu
`taxonomy-workspace`.

| Anwendungseigener Bereich | Verantwortung |
|---|---|
| `com.taxonomy.composition` | Kontextübergreifende HTTP-/UI-Orchestrierung und Port-Adapter |
| `com.taxonomy.security`, `com.taxonomy.observability`, `com.taxonomy.shared` | Anwendungsweite Sicherheit, Observability und gemeinsame Komposition |
| `com.taxonomy.provenance`, `com.taxonomy.preferences` | Bewusst in der Anwendung verbliebene unterstützende Kontexte |
| `src/main/resources/static/`, `src/main/resources/templates/` | Browser-Ressourcen und Thymeleaf-Seiten |
| `src/main/resources/application.properties` | Anwendungskonfiguration |
| Anwendungseigene Datenbankmigrationen | Komposition der Anwendungsschemata; Schemaverträge der Speicherbibliothek bleiben separat zuständig |

Java-Paketnamen blieben bei der Modulextraktion weitgehend erhalten. Ein Paket
wie `com.taxonomy.versioning` belegt daher keine physische Zuständigkeit von
`taxonomy-app`; vor einer Änderung das zuständige Maven-Modul bestimmen.

---

## Wo Änderungen vorzunehmen sind

| Ich möchte… | Wo nachschauen |
|---|---|
| Einen neuen Taxonomie-Endpunkt hinzufügen | `taxonomy-knowledge/.../catalog/` — den katalogeigenen Controller erstellen oder erweitern; Anwendungskomposition nur für kontextübergreifende Orchestrierung |
| Einen neuen Service hinzufügen | Die zuständige Fachbibliothek aus [Modulgrenzen](MODULE_BOUNDARIES.md); ohne Spring-Bedarf einen frameworkfreien Typ verwenden |
| Ein neues Exportformat hinzufügen | [Exportaufgabe](../dev/tasks/add-export-format.md): frameworkfreie SPI, eingebauter Adapter oder unabhängiges Plugin |
| Eine neue JPA-Entität hinzufügen | Die zuständige Fachbibliothek; anwendungsweite Migrationskomposition bleibt in `taxonomy-app` |
| Ein neues DTO hinzufügen | `taxonomy-domain/.../dto/` — einen Record oder eine Klasse erstellen |
| Die DSL-Grammatik ändern | `taxonomy-dsl/.../parser/TaxDslParser.java` |
| Eine DSL-Validierungsregel hinzufügen | `taxonomy-dsl/.../validation/DslValidator.java` |
| Die Benutzeroberfläche ändern | `taxonomy-app/src/main/resources/templates/index.html` + `static/js/` |
| Einen neuen LLM-Prompt hinzufügen | `taxonomy-analysis/src/main/resources/prompts/` — `XX.txt` erstellen |
| Die Konfiguration ändern | `taxonomy-app/src/main/resources/application.properties` |

---

## Tests ausführen

Der eingecheckte Maven Wrapper ist die einzige Build-Autorität. Der
Standard-Lebenszyklus bleibt bewusst begrenzt; das Profil `ci` aktiviert
Docker-Integration, Qualitäts-Gates sowie die Browser-/Accessibility-Matrix.

```bash
./mvnw verify
./mvnw test -pl taxonomy-dsl -am
./mvnw test -pl taxonomy-app -am -Dtest=TaxonomyApplicationTests -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -B verify -Pcore-integration
./mvnw -B verify -Pdatabase-postgres
./mvnw -B verify -Pdatabase-mssql
./mvnw -B verify -Pdatabase-oracle
./mvnw -B verify -Pui-tests -DskipTests -DskipITs=true
./mvnw -B verify -Pci -DrunOnnxTests=true
```

Genaue Umfänge, Voraussetzungen, die Reproduktion einzelner Browserprofile und
die Verantwortungen der acht verbleibenden Workflows stehen in
[`docs/dev/MAVEN_VERIFICATION.md`](../dev/MAVEN_VERIFICATION.md). Testauswahl
gehört in ein POM-Profil; Workflow-YAML darf eine Umgebung beziehungsweise ein
Profil wählen, aber keine Java-Testklassen nennen und keine Playwright-/axe-
Skripte direkt aufrufen.

## Einen neuen REST-Endpunkt hinzufügen

1. Bestimmen Sie die zuständige Fachbibliothek über [Modulgrenzen](MODULE_BOUNDARIES.md) und erstellen oder erweitern Sie dort den Controller. Nur kontextübergreifende HTTP-Orchestrierung gehört nach `taxonomy-app/src/main/java/com/taxonomy/composition/`.
2. Falls der Endpunkt ein neues DTO zurückgibt, fügen Sie es unter `taxonomy-domain/src/main/java/com/taxonomy/dto/` hinzu.
3. Legen Sie Service und zugehörige Tests im selben Fachkontext an; kontextübergreifende Zugriffe verwenden dessen öffentliche API oder einen expliziten Port.
4. Prüfen Sie Services auf der niedrigsten sinnvollen JUnit-Ebene; Endpunktverhalten und Rollen benötigen HTTP-/Sicherheitsintegrationstests.
5. Falls der Endpunkt nur für Administratoren bestimmt ist, platzieren Sie ihn unter `/api/admin/` (geschützt durch `ROLE_ADMIN` in `SecurityConfig`).
6. Falls der Endpunkt Architekturdaten ändert (Relationen, DSL), platzieren Sie ihn unter `/api/relations/`, `/api/dsl/` oder `/api/git/` — Schreiboperationen auf diesen Pfaden erfordern `ROLE_ARCHITECT` oder `ROLE_ADMIN`.

---

## Ein neues Exportformat hinzufügen

Eingebaute Formate verwenden frameworkfreie Konvertierung in `taxonomy-export`
und einen Anwendungsadapter über die vorhandene Export-SPI. Für ein unabhängig
installierbares Format implementieren Sie `ExportFormatExtension` in einem eigenen
JAR und veröffentlichen es über einen `TaxonomyPlugin`-ServiceLoader-Eintrag.
Beide Wege nutzen denselben Katalog und `POST /api/diagram/export/{formatId}`.

Die [Exportaufgabe](../dev/tasks/add-export-format.md) beschreibt Packaging,
Artefaktidentität, Lebenszyklus und HTTP-Prüfung. Berichtsrenderer und Diagrammexporte
besitzen unterschiedliche Verträge; nicht jeder Bericht wird durch einen Diagrammcodec erzeugt.

---

## Einen neuen LLM-Anbieter hinzufügen

Prüfen Sie zuerst, ob die vorhandene Konfiguration von `CUSTOM_OPENAI` den Endpunkt
bereits unterstützt. Für einen neuen Transport beschreibt die
[Anbieteraufgabe](../dev/tasks/add-llm-provider.md) offene `ProviderId`-Kennungen,
`LlmTransportExtension` und den beim Start gebundenen `TaxonomyPlugin`-ServiceLoader-
Eintrag eines unabhängigen Java-25-JARs. Ein externer Anbieter erfordert keine
Änderung des eingebauten `LlmProvider`-Enums.

Der Host in `taxonomy-analysis` behält Auswahl, Zulassung, Wiederholungen, Abbruch,
Verbrauchserfassung und eingefrorene Anbieteridentität. Ein beigesteuerter Transport
führt genau einen Versuch aus. Dokumentieren Sie Konfiguration, Fähigkeiten und
Wiederanlaufgrenzen. Protokollzweige gehören nicht direkt in `LlmService`;
OpenAI-kompatible Syntax allein belegt keine Token-Obergrenzen.

---

## DSL und JGit-Speicher

Das Architecture-DSL-Subsystem verwendet JGit DFS. `jgit-storage-hibernate` speichert
Git-Objekte und Referenzen in der konfigurierten relationalen Datenbank. HSQLDB ist
der Standard, nicht das einzige Backend. `taxonomy-workspace` besitzt Repository-
Fassade und Editorjournal. Übernommene semantische Operationen sind dauerhafte
Arbeitsrevisionen; ausdrückliche Git-Checkpoints halten stabile DSL-Versionen fest.
Unveränderliche Analysesnapshots haben eigene Identitäten und Lebenszyklen.
Siehe [Persistenzzuständigkeiten](ARCHITECTURE.md#dsl-speicherarchitektur).

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

**Grenze der Speicherbibliothek:** `DfsBlockCache` gilt JVM-weit. Repository-/Pack-
Identitäten müssen unterscheidbar bleiben; Implementierung und Schema gehören
jedoch zum freigegebenen Speicheradapter. Alte Zählerimplementierungen oder
Bibliotheksmigrationen werden nicht in Taxonomy nachgebaut.
Siehe [JGit-Speicherintegration](JGIT_STORAGE_HIBERNATE.md).

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

- Fachliche Unit-Tests verwenden nach Möglichkeit JUnit ohne Spring-Kontext; HTTP-/Datenbankabnahmen nutzen die vorhandenen Spring-/HSQLDB-Fixtures.
- DSL-Modultests (`taxonomy-dsl`) sind reine JUnit-5-Tests — kein Spring-Kontext.
- JGit-Speichertests (`DslGitRepositoryTest`) sind reine JUnit-5-Tests mit datenbankgestütztem `HibernateRepository`.
- Surefire führt `*Test`/`*Tests` aus; Failsafe besitzt `*IT` in ausgewählten Profilen. Tags und Fixture bestimmen Docker-/Browservoraussetzungen, nicht allein die Dateiendung.
- Standardprüfungen nutzen deterministische Doubles oder verfasste HTTP-Playback-Antworten. Echte Modellvergleiche benötigen das ausdrückliche Opt-in-Profil `reformulation-real-llm` und einen konfigurierten Anbieter.

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

## Architekturkonventionen

Diese Konventionen gewährleisten Homogenität (Deutschland-Stack Prinzip 4 — Modularität & Wiederverwendbarkeit) im gesamten Codebase.
Siehe [Architekturprinzipien](ARCHITECTURE.md#architekturprinzipien) für die vollständige Prinzip-Zuordnung.

### Benennung

| Bereich | Konvention | Beispiel |
|---|---|---|
| REST-Controller | `*Controller` oder `*ApiController` | `ProposalApiController` |
| Services | `*Service` | `ArchitectureGapService` |
| JPA-Entitäten | Singular, PascalCase | `TaxonomyRelation` |
| DTOs | `*Dto`, `*View`, `*Result` | `GapAnalysisView` |
| Enums | PascalCase, in `taxonomy-domain` | `RelationType` |
| REST-Pfade | `/api/{ressource}` (Plural, Kebab-Case) | `/api/relations/proposals` |

### Fehlerbehandlung

- Controller geben `ResponseEntity` mit entsprechenden HTTP-Statuscodes zurück
- Service-Exceptions erweitern domänenspezifische Ausnahmeklassen
- LLM-Rate-Limits werden als `LlmRateLimitException` → HTTP 429 behandelt

### Checkliste für neue Services

1. In der zuständigen Fachbibliothek aus [Modulgrenzen](MODULE_BOUNDARIES.md) platzieren, nicht automatisch in `taxonomy-app`
2. Mit `@Service` annotieren
3. `username`-Parameter akzeptieren, wenn Workspace-bezogen
4. Unit-Test mit `@SpringBootTest` hinzufügen
5. In ARCHITECTURE.md dokumentieren, wenn es eine Schlüsselkomponente ist

### Daten-Eigentümermodell

| Entität | Eigentümer | Veränderbarkeit |
|---|---|---|
| `TaxonomyNode` | System (Excel-Import) | Nur-Lesen nach Startup |
| `TaxonomyRelation` | Workspace | Lesen-Schreiben, Workspace-bezogen |
| `RelationHypothesis` | Workspace | Transient oder akzeptiert |
| `RelationProposal` | Workspace | Lebenszyklus: vorgeschlagen → akzeptiert/abgelehnt |
| DSL-Inhalt | Workspace (JGit) | Über Git-Commits versioniert |
| `ArchitectureCommitIndex` | Branch | Bei Materialisierung neu erstellt |

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

## README-Showcase-Diagramm aktualisieren

Das Mermaid-Diagramm im README-Abschnitt „Architecture Impact Showcase" wird
deterministisch von `ReadmeShowcaseTest` generiert (kein LLM-Aufruf erforderlich).

Nach Änderungen an der Analyse-Pipeline, der Diagrammprojektion oder dem Mermaid-Export:

1. Ausführen:
   ```
   ./mvnw test -pl taxonomy-app -am -Dtest=ReadmeShowcaseTest -Dsurefire.failIfNoSpecifiedTests=false
   ```
2. `taxonomy-app/target/readme-showcase.md` öffnen und den Mermaid-Block
   (alles zwischen ` ```mermaid ` und ` ``` `) in `README.md` kopieren und
   den bestehenden Block im Abschnitt „Architecture Impact Showcase" ersetzen.
3. Außerdem die Zusammenfassungszeile oberhalb des Mermaid-Blocks aktualisieren
   (Element-, Schicht- und Relationsanzahl), damit sie zur ersten Zeile von `readme-showcase.md` passt.

`ReadmeShowcaseDriftTest` schlägt in CI fehl, wenn das README-Diagramm von der
aktuellen `exportShowcase()`-Ausgabe abweicht, sodass das Diagramm stets korrekt bleibt.

---

## Screenshot-Konventionen

Zuständigkeiten, aktuelle Selektoren, Voraussetzungen und Herkunftsregeln stehen in
der [Screenshot-Referenz](https://github.com/carstenartur/Taxonomy/blob/main/.github/copilot-ref-screenshots.md). Verwenden Sie
das zuständige Szenario statt eines zweiten nachgebauten Browserzustands:
`ScreenshotGeneratorIT` besitzt allgemeine UI-Bilder, `ArchitectureWorkbenchUiIT`
die gespeicherte Workbench und `CompleteCopilotSessionIT` das vollständige Copilot-Ergebnis.

```bash
./mvnw -B verify -Pscreenshots -DgenerateScreenshots=true
```

Diese Vollanwendungsaufnahme benötigt Docker. Der vorhandene lokale Szenarioweg und
Komponentenillustrationen haben eigene Voraussetzungen und Nachweisgrenzen; siehe
die verlinkte Referenz. Wählen Sie repräsentative Erfolgszustände oder ausdrücklich
beschriftete Leer-/Fehler-/Wiederanlaufzustände passend zum beschriebenen Ablauf.
Ein Fehlerbild wird nicht durch einen erfundenen Erfolg ersetzt; Playback ist kein
Live-Modellvergleich. Prüfen Sie betroffene Sprache und Ansichtsgröße und aktualisieren
Sie Bildunterschrift sowie Quell-/Versionsnachweis gemeinsam mit dem Bild.

---

## Internationalisierung (i18n) — Deutsch / Englisch

Die Produkt-UI unterstützt sowohl Deutsch als auch Englisch. Der aktuelle i18n-Mechanismus verwendet:
- Thymeleaf `th:text` mit Message-Keys für server-gerendertes HTML
- JavaScript-Locale-Bundles für clientseitige Texte

### Einen neuen UI-Text hinzufügen

1. Den englischen Text in `messages.properties` (oder ins JS-Locale-Bundle) hinzufügen
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
| Workspace-Modell-Änderung | `WORKSPACE_VERSIONING.md`, `CONCEPTS.md`, `REPOSITORY_TOPOLOGY.md` |
| Neue Konfigurationsvariable | `CONFIGURATION_REFERENCE.md`, `DEPLOYMENT_GUIDE.md` |
| Neues Hilfe-Dokument | In `HelpController.DOC_METADATA` registrieren, `help.toc.*` i18n-Keys hinzufügen, `docs/en/` und `docs/de/` Dateien erstellen |
| Jede i18n-sichtbare Änderung | Sowohl `messages.properties` als auch `messages_de.properties` |

### Durchsetzung

Die folgenden CI-Tests erkennen häufige Dokumentationsdrift:

- `HelpControllerTest.everyEnglishDocFileIsRegistered()` — jede `docs/en/*.md` muss in `HelpController` registriert sein
- `HelpControllerTest.everyRegisteredDocHasI18nKeys()` — jedes registrierte Dokument muss EN + DE i18n-Keys haben
- `I18nApiControllerTest.englishAndGermanHaveSameKeys()` — EN- und DE-Bundles müssen identische Key-Sets haben

---

## Wartbarkeitsmatrix

Die [Wartbarkeitsmatrix](https://github.com/carstenartur/Taxonomy/blob/main/docs/internal/MAINTAINABILITY_MATRIX.md) listet jeden Funktionsbereich mit seinem primären
Backend-Paket, Frontend-Modul, Haupt-Controller und -Service, DTOs, Test-Coverage-Flags und einer kognitiven
Belastungsbewertung auf.

Verwenden Sie sie, um die richtigen Einstiegspunkte für eine fokussierte Änderung zu finden und zu verstehen,
welche Bereiche bekannte querschnittliche Kopplungen aufweisen.

**ArchUnit-Regeln** — die Architekturgrenzregeln befinden sich in
`taxonomy-app/src/test/java/com/taxonomy/ArchitectureTest.java`.
Aktuell durchgesetzte Regeln:
- Keine zirkulären Abhängigkeiten zwischen Domain-Paketen
- Controller dürfen nicht direkt auf Repositories zugreifen
- Services dürfen nicht von Controllern abhängen
- `taxonomy-domain`, `taxonomy-dsl` (framework-freie Pakete) und `taxonomy-export` (framework-freie Pakete) müssen Spring-frei sein
- `taxonomy-dsl`- und `taxonomy-export`-framework-freie Pakete dürfen nicht von `taxonomy-app`-Paketen abhängen
- Workspace-Kontext darf nur an Request-Grenzen aufgelöst werden

## Aktuelle QA-Einstiegspunkte

Die Befehle für Standard-, Core-Container-, Datenbank-, Browser-,
Accessibility-, Abhängigkeits- und Screenshot-Prüfungen stehen in
[`docs/dev/06-testing-by-change-type.md`](https://github.com/carstenartur/Taxonomy/blob/main/docs/dev/06-testing-by-change-type.md).
Die CI-Berechtigungsgrenzen sind in
[`docs/dev/CI_SECURITY.md`](https://github.com/carstenartur/Taxonomy/blob/main/docs/dev/CI_SECURITY.md) beschrieben.
