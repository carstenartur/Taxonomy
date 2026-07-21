#!/usr/bin/env python3
"""Apply the final verified EN/DE test and persistence documentation alignment."""

from pathlib import Path


def replace_section(path: str, start_heading: str, next_heading: str, replacement: str) -> None:
    target = Path(path)
    text = target.read_text(encoding="utf-8")
    start = text.index(start_heading)
    end = text.index(next_heading, start)
    target.write_text(text[:start] + replacement.rstrip() + "\n\n" + text[end:], encoding="utf-8")


def replace_once(path: str, old: str, new: str) -> None:
    target = Path(path)
    text = target.read_text(encoding="utf-8")
    if text.count(old) != 1:
        raise SystemExit(f"Expected one match in {path}: {old[:100]!r}")
    target.write_text(text.replace(old, new, 1), encoding="utf-8")


replace_once(
    "docs/en/DEVELOPER_GUIDE.md",
    """# Run all tests (~60 seconds, no Docker needed)
mvn test

# Run including integration tests (requires Docker)
mvn verify""",
    """# Run the complete deterministic lifecycle (no Docker needed)
mvn verify

# Run one explicit Testcontainers scenario (Docker required)
mvn -B -pl taxonomy-app -am install -DskipTests
mvn -B -pl taxonomy-app failsafe:integration-test failsafe:verify \\
  -DskipITs=false -Dit.test=DiagnosticsContainerIT \\
  -DfailIfNoTests=false \\
  -DexcludedGroups=real-llm,db-postgres,db-mssql,db-oracle""",
)
replace_once(
    "docs/de/DEVELOPER_GUIDE.md",
    """# Run all tests (~60 seconds, no Docker needed)
mvn test

# Run including integration tests (requires Docker)
mvn verify""",
    """# Vollständigen deterministischen Lebenszyklus ausführen (kein Docker nötig)
mvn verify

# Ein explizites Testcontainers-Szenario ausführen (Docker erforderlich)
mvn -B -pl taxonomy-app -am install -DskipTests
mvn -B -pl taxonomy-app failsafe:integration-test failsafe:verify \\
  -DskipITs=false -Dit.test=DiagnosticsContainerIT \\
  -DfailIfNoTests=false \\
  -DexcludedGroups=real-llm,db-postgres,db-mssql,db-oracle""",
)

replace_section(
    "docs/en/DEVELOPER_GUIDE.md",
    "## Running Tests",
    "---\n\n## Adding a New REST Endpoint",
    r'''## Running Tests

The root POM keeps `mvn verify` deterministic and bounded. It runs unit, Spring,
architecture, contract, and dependency-hygiene tests without Docker or live LLM
calls. Failsafe/Testcontainers tests are selected explicitly.

```bash
# Complete standard lifecycle (no Docker)
mvn verify

# One module or test class
mvn test -pl taxonomy-dsl
mvn test -pl taxonomy-app -Dtest=TaxonomyApplicationTests

# Prepare the reactor for an isolated integration test
mvn -B -pl taxonomy-app -am install -DskipTests

# One core Testcontainers scenario
mvn -B -pl taxonomy-app \
  failsafe:integration-test failsafe:verify \
  -DskipITs=false \
  -Dit.test=ProductionPersistenceRestartIT \
  -DfailIfNoTests=false \
  -DexcludedGroups=real-llm,db-postgres,db-mssql,db-oracle
```

### External database compatibility

PostgreSQL, Microsoft SQL Server, and Oracle tests are ordinary Maven
Failsafe/Testcontainers tests, but all three `db-*` tags are excluded from the
standard lifecycle. Relevant database-configuration pull requests run the
bounded PostgreSQL diagnostics/Selenium pair. Scheduled and manual workflows
cover the selected full matrix.

```bash
mvn -B -pl taxonomy-app \
  failsafe:integration-test failsafe:verify \
  -DskipITs=false \
  -Dit.test='*Postgres*IT' \
  -DfailIfNoTests=false \
  -DexcludedGroups=real-llm

# Substitute *Mssql*IT or *Oracle*IT for another family.
```

`-DexcludedGroups=real-llm` deliberately includes the database tags while still
excluding live LLM calls. Test classes inherit from
`AbstractDatabaseContainerIT` or `AbstractSeleniumContainerIT`; application and
database containers share a Testcontainers network and receive the selected
Spring profile and JDBC settings through environment variables.

| Pattern/tag | Runner | Standard lifecycle |
|---|---|---|
| `*Test.java`, `*Tests.java` | Surefire | Included |
| `*IT.java` | Failsafe | Skipped while `skipITs=true` |
| `db-postgres` | Failsafe/Testcontainers | Excluded |
| `db-mssql` | Failsafe/Testcontainers | Excluded |
| `db-oracle` | Failsafe/Testcontainers | Excluded |
| `real-llm` | Surefire/Failsafe | Excluded |

The authoritative command catalogue, browser matrix, accessibility audit, and
screenshot procedure are maintained in
[`docs/dev/06-testing-by-change-type.md`](../dev/06-testing-by-change-type.md).''',
)

replace_section(
    "docs/de/DEVELOPER_GUIDE.md",
    "## Tests ausführen",
    "---\n\n## Einen neuen REST-Endpunkt hinzufügen",
    r'''## Tests ausführen

Das Root-POM hält `mvn verify` deterministisch und begrenzt. Es führt Unit-,
Spring-, Architektur-, Vertrags- und Abhängigkeitshygiene-Tests ohne Docker und
ohne Live-LLM-Aufrufe aus. Failsafe-/Testcontainers-Tests werden explizit
ausgewählt.

```bash
# Vollständiger Standard-Lebenszyklus (kein Docker)
mvn verify

# Einzelnes Modul oder einzelne Testklasse
mvn test -pl taxonomy-dsl
mvn test -pl taxonomy-app -Dtest=TaxonomyApplicationTests

# Reactor für einen isolierten Integrationstest vorbereiten
mvn -B -pl taxonomy-app -am install -DskipTests

# Ein zentrales Testcontainers-Szenario
mvn -B -pl taxonomy-app \
  failsafe:integration-test failsafe:verify \
  -DskipITs=false \
  -Dit.test=ProductionPersistenceRestartIT \
  -DfailIfNoTests=false \
  -DexcludedGroups=real-llm,db-postgres,db-mssql,db-oracle
```

### Kompatibilität mit externen Datenbanken

Die PostgreSQL-, Microsoft-SQL-Server- und Oracle-Tests sind normale Maven-
Failsafe-/Testcontainers-Tests. Alle drei `db-*`-Tags sind jedoch im Standard-
Lebenszyklus ausgeschlossen. Relevante Pull Requests zur Datenbankkonfiguration
führen das begrenzte PostgreSQL-Paar aus Diagnose- und Selenium-Test aus.
Geplante und manuelle Workflows decken die ausgewählte vollständige Matrix ab.

```bash
mvn -B -pl taxonomy-app \
  failsafe:integration-test failsafe:verify \
  -DskipITs=false \
  -Dit.test='*Postgres*IT' \
  -DfailIfNoTests=false \
  -DexcludedGroups=real-llm

# Für andere Familien *Mssql*IT beziehungsweise *Oracle*IT einsetzen.
```

`-DexcludedGroups=real-llm` aktiviert bewusst die Datenbank-Tags und schließt
weiterhin Live-LLM-Aufrufe aus. Die Testklassen erben von
`AbstractDatabaseContainerIT` oder `AbstractSeleniumContainerIT`; Anwendungs-
und Datenbankcontainer teilen sich ein Testcontainers-Netzwerk und erhalten das
Spring-Profil sowie die JDBC-Einstellungen über Umgebungsvariablen.

| Muster/Tag | Runner | Standard-Lebenszyklus |
|---|---|---|
| `*Test.java`, `*Tests.java` | Surefire | enthalten |
| `*IT.java` | Failsafe | übersprungen, solange `skipITs=true` |
| `db-postgres` | Failsafe/Testcontainers | ausgeschlossen |
| `db-mssql` | Failsafe/Testcontainers | ausgeschlossen |
| `db-oracle` | Failsafe/Testcontainers | ausgeschlossen |
| `real-llm` | Surefire/Failsafe | ausgeschlossen |

Der verbindliche Befehlskatalog sowie Browsermatrix, Accessibility-Prüfung und
Screenshot-Verfahren stehen in
[`docs/dev/06-testing-by-change-type.md`](../dev/06-testing-by-change-type.md).''',
)

replace_once(
    "docs/en/ARCHITECTURE.md",
    "- **In-process HSQLDB** — taxonomy data (~2,500 nodes across 8 sheets from an Excel workbook) is loaded at startup into an embedded HSQLDB database. No external database is required by default.",
    "- **In-process HSQLDB** — the zero-configuration profile uses embedded HSQLDB; file-backed deployments persist catalogue and JGit data, while an empty database or an explicit reload imports the bundled workbook. No external database is required by default.",
)
replace_once(
    "docs/de/ARCHITECTURE.md",
    "- **In-Process HSQLDB** — Taxonomiedaten (~2.500 Knoten über 8 Blätter aus einer Excel-Arbeitsmappe) werden beim Start in eine eingebettete HSQLDB-Datenbank geladen. Standardmäßig ist keine externe Datenbank erforderlich.",
    "- **In-Process HSQLDB** — das konfigurationsfreie Profil verwendet eingebettete HSQLDB; dateibasierte Installationen persistieren Katalog- und JGit-Daten, während nur eine leere Datenbank oder ein explizites Neuladen die mitgelieferte Arbeitsmappe importiert. Standardmäßig ist keine externe Datenbank erforderlich.",
)

replace_section(
    "docs/en/ARCHITECTURE.md",
    "## CI / CD",
    "## Rate Limiting",
    r'''## CI / CD

Every push and pull request runs a read-only verification job. The build executes
the deterministic Maven lifecycle, dependency/SBOM policy, JUnit publication,
and immutable artifact creation. It does not need repository-content write
permission.

| Layer | Trigger and responsibility |
|---|---|
| **Build & Test** | `mvn install` with deterministic tests; produces JAR, SBOM, dependency, test, and coverage artifacts |
| **Core Integration** | Four explicitly selected HSQLDB/Testcontainers scenarios |
| **Database Compatibility** | PostgreSQL pair on relevant PRs; scheduled/manual PostgreSQL, MSSQL, and Oracle matrix |
| **UI / Accessibility** | Chromium/Firefox and desktop/tablet/mobile evidence |
| **Report publication** | Separate write-capable job only after a default-branch push |
| **Screenshot publication** | Separate trusted `workflow_dispatch`; read-only generation followed by an isolated main-branch publisher |
| **Container / deployment** | GHCR publication and optional Render hook only after a successful eligible push |

The verification/mutation boundary is documented in
[`docs/dev/CI_SECURITY.md`](../dev/CI_SECURITY.md).

## Database

### Default: embedded HSQLDB

The default profile needs no database server. It uses a bounded HikariCP pool
with `minimum-idle=1` and `maximum-pool-size=4`. Keeping one connection open is
important for file URLs that use `shutdown=true`; otherwise HSQLDB can close
between Spring startup phases.

The development default is an in-memory URL. Production Docker configuration
uses file-backed HSQLDB and filesystem Lucene storage. Existing persisted
catalogue rows are reused on restart. `TAXONOMY_INIT_RELOAD_EXISTING=true`
performs an intentional destructive catalogue reload from the bundled workbook.

PostgreSQL, Microsoft SQL Server, and Oracle use dedicated profiles and the
bounded Testcontainers compatibility matrix. Detailed setup and test commands
are in [Database Setup](DATABASE_SETUP.md) and
[`docs/dev/06-testing-by-change-type.md`](../dev/06-testing-by-change-type.md).''',
)

replace_section(
    "docs/de/ARCHITECTURE.md",
    "## CI / CD",
    "## Ratenbegrenzung",
    r'''## CI / CD

Jeder Push und Pull Request führt einen schreibgeschützten Verifikationsjob aus.
Der Build umfasst den deterministischen Maven-Lebenszyklus, die Abhängigkeits-
und SBOM-Policy, JUnit-Veröffentlichung und unveränderliche Artefakte. Dafür ist
keine Schreibberechtigung auf Repository-Inhalte erforderlich.

| Ebene | Auslöser und Verantwortung |
|---|---|
| **Build & Test** | `mvn install` mit deterministischen Tests; erzeugt JAR-, SBOM-, Abhängigkeits-, Test- und Coverage-Artefakte |
| **Core Integration** | Vier explizit ausgewählte HSQLDB-/Testcontainers-Szenarien |
| **Database Compatibility** | PostgreSQL-Paar bei relevanten PRs; geplante/manuelle PostgreSQL-, MSSQL- und Oracle-Matrix |
| **UI / Accessibility** | Chromium/Firefox sowie Desktop-/Tablet-/Mobil-Nachweise |
| **Berichtsveröffentlichung** | Separater schreibberechtigter Job nur nach einem Push auf den Standard-Branch |
| **Screenshot-Veröffentlichung** | Separater vertrauenswürdiger `workflow_dispatch`; schreibgeschützte Erzeugung und isolierter Main-Publisher |
| **Container / Deployment** | GHCR-Veröffentlichung und optionaler Render-Hook erst nach erfolgreichem geeignetem Push |

Die Grenze zwischen Prüfung und Mutation ist in
[`docs/dev/CI_SECURITY.md`](../dev/CI_SECURITY.md) beschrieben.

## Datenbank

### Standard: eingebettete HSQLDB

Das Standardprofil benötigt keinen Datenbankserver. Es verwendet einen
begrenzten HikariCP-Pool mit `minimum-idle=1` und `maximum-pool-size=4`. Bei
Datei-URLs mit `shutdown=true` muss eine Verbindung offen bleiben, damit HSQLDB
nicht zwischen Spring-Startphasen beendet wird.

Der Entwicklungsstandard ist eine In-Memory-URL. Die Produktions-Docker-
Konfiguration verwendet dateibasierte HSQLDB- und Lucene-Speicherung. Vorhandene
persistierte Katalogzeilen werden beim Neustart weiterverwendet.
`TAXONOMY_INIT_RELOAD_EXISTING=true` löst bewusst ein destruktives Neuladen aus
der mitgelieferten Arbeitsmappe aus.

PostgreSQL, Microsoft SQL Server und Oracle verwenden eigene Profile und die
begrenzte Testcontainers-Kompatibilitätsmatrix. Details und Testbefehle stehen
in [Datenbank-Setup](DATABASE_SETUP.md) und
[`docs/dev/06-testing-by-change-type.md`](../dev/06-testing-by-change-type.md).''',
)

replace_once(
    "docs/en/ARCHITECTURE.md",
    "At startup, `TaxonomyService` loads the C3 Taxonomy Catalogue from the bundled Excel workbook (`src/main/resources/data/C3_Taxonomy_Catalogue_25AUG2025.xlsx`) using Apache POI. A CSV fallback (`relations.csv`) provides seed relations when no Relations sheet is present in the workbook.",
    "When the catalogue is empty—or when `TAXONOMY_INIT_RELOAD_EXISTING=true` explicitly requests replacement—`TaxonomyService` imports the bundled C3 workbook (`src/main/resources/data/C3_Taxonomy_Catalogue_25AUG2025.xlsx`) through Apache POI. Otherwise persisted catalogue rows are reused. A CSV fallback (`relations.csv`) provides seed relations when no Relations sheet is present.",
)
replace_once(
    "docs/de/ARCHITECTURE.md",
    "Beim Start lädt `TaxonomyService` den C3-Taxonomiekatalog aus der mitgelieferten Excel-Arbeitsmappe (`src/main/resources/data/C3_Taxonomy_Catalogue_25AUG2025.xlsx`) mit Apache POI. Eine CSV-Seed-Datei (`relations.csv`) liefert Standard-Beziehungen, wenn kein Relations-Blatt in der Arbeitsmappe vorhanden ist.",
    "Wenn der Katalog leer ist oder `TAXONOMY_INIT_RELOAD_EXISTING=true` ausdrücklich einen Ersatz anfordert, importiert `TaxonomyService` die mitgelieferte C3-Arbeitsmappe (`src/main/resources/data/C3_Taxonomy_Catalogue_25AUG2025.xlsx`) mit Apache POI. Andernfalls werden persistierte Katalogzeilen weiterverwendet. Eine CSV-Seed-Datei (`relations.csv`) liefert Standard-Beziehungen, wenn kein Relations-Blatt vorhanden ist.",
)

Path('.github/apply-qa-doc-error.log').unlink(missing_ok=True)
Path(__file__).unlink()
