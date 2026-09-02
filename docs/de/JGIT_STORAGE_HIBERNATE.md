# Hibernate-basierte JGit-Speicherung

Taxonomy speichert die Historien der Architecture DSL und der Einstellungen über die veröffentlichte Bibliothek [`jgit-storage-hibernate-core`](https://github.com/carstenartur/jgit-storage-hibernate) in relationalen Datenbanktabellen. Taxonomy enthält keine kopierte Implementierung der JGit-DFS-, Pack-, Reftable- oder Reflog-Speicherung.

## Verantwortungsgrenze

| Taxonomy verantwortet | `jgit-storage-hibernate-core` verantwortet |
|---|---|
| DSL-Dateiname, Parser, semantischen Diff und Architekturabläufe | JGit-DFS-Repository-Implementierung |
| Branch-, Merge-, Cherry-pick-, Revert- und Workspace-Orchestrierung | Pack-, Objekt-, Ref- und Reflog-Persistenz |
| Logische Repository-Namen und exaktes Workspace-Routing | Transaktionale Repository-bezogene Speicheroperationen |
| Autorisierung, Audit, REST, UI und Recovery auf Anwendungsebene | Core-Entities und versionierte Core-/Adoptions-SQL-Ressourcen |
| Auswahl eines ausdrücklich unterstützten Datenbank-Migrationspfads | Öffentliche Migrationspfade und physische Änderungen des Core-Schemas |
| Anwendungsprojektionen und Hibernate-Search-Indizes | Interne Speicherimplementierung und Schema-Kompatibilität |

Taxonomy verwendet öffentliche Typen aus `io.github.carstenartur.jgit.storage.hibernate` sowie öffentliche JGit-APIs. Anwendungscode darf keine Implementierungspakete `repository`, `objects` oder `refs` der Bibliothek importieren. Umgekehrt besitzt die Bibliothek keine Taxonomy-spezifische Mandanten-, Architektur-, UI- oder Projektionssemantik.

## Veröffentlichte Abhängigkeit und anonymer Paket-Zugriff

Das Root-POM ist die maßgebliche Quelle für die veröffentlichte Abhängigkeit und ihr Repository:

```xml
<jgit-storage-hibernate.version>0.11.3</jgit-storage-hibernate.version>
```

```xml
<repository>
  <id>jgit-storage-hibernate-releases</id>
  <name>jgit-storage-hibernate anonymous releases</name>
  <url>https://raw.githubusercontent.com/carstenartur/jgit-storage-hibernate/maven-repository/</url>
  <releases><enabled>true</enabled></releases>
  <snapshots><enabled>false</enabled></snapshots>
</repository>
```

Das Anwendungsmodul verwendet die Property, statt die Versionsnummer erneut festzuschreiben:

```xml
<dependency>
  <groupId>io.github.carstenartur</groupId>
  <artifactId>jgit-storage-hibernate-core</artifactId>
  <version>${jgit-storage-hibernate.version}</version>
</dependency>
```

Das konfigurierte Release-Repository ist öffentlich und anonym lesbar. Ein sauberer Konsumenten-Build benötigt deshalb weder Maven-Server-Zugangsdaten noch ein Token für den Paket-Lesezugriff. Das Repository ist nicht Maven Central; seine exakte ID, URL und reine Release-Policy bleiben Teil des reproduzierbaren Taxonomy-Abhängigkeitsvertrags, bis die Distribution in einer geprüften Änderung umgestellt wird.

`JgitStorageDocumentationContractTest` liest Version und Repository-Block aus dem Root-POM und vergleicht beide Sprachfassungen mit dieser Quelle. Der Test weist außerdem das veraltete Modell eines authentifizierten Paket-Zugriffs zurück.

## Von Spring verwalteter Persistence Context

Die Core-Entities der Bibliothek liegen außerhalb von `com.taxonomy`. Deshalb nimmt die Anwendung `io.github.carstenartur.jgit.storage.hibernate.entity` ausdrücklich in `@EntityScan` auf. Integrationstests vergleichen das resultierende JPA-Metamodell mit `CoreEntities.annotatedClasses()`, damit eine spätere neue öffentliche Bibliotheks-Entity nicht unbemerkt fehlt.

Spring bleibt Eigentümer von `EntityManagerFactory` und nativer Hibernate-`SessionFactory`:

```java
@Bean
HibernateRepositoryFactory hibernateRepositoryFactory(
        EntityManagerFactory entityManagerFactory) {
    SessionFactory sessionFactory =
            entityManagerFactory.unwrap(SessionFactory.class);
    return new DefaultHibernateRepositoryFactory(sessionFactory);
}
```

Ein `HibernateGitStorage`-Handle besitzt nur das geöffnete JGit-Repository. Beim Schließen eines Handles darf niemals die von der Anwendung verwaltete `SessionFactory` geschlossen werden.

## Logische Repositorys

Die physischen Speichertabellen werden gemeinsam verwendet, jede Operation ist jedoch auf einen exakten logischen Repository-Namen eingeschränkt:

| Zweck | Logischer Name |
|---|---|
| Gemeinsame System-DSL | `taxonomy-dsl` |
| Workspace-DSL | `ws-<workspace-id>` |
| Einstellungen | `taxonomy-preferences` |

`DslGitRepositoryFactory` hält geöffnete Handles in einem Cache. Ein Cache-Evict schließt das Handle, lässt die Datenbankzeilen aber bestehen. Beim endgültigen Löschen eines Workspaces wird zuerst das Handle geschlossen und danach `HibernateRepositoryFactory.deleteRepository(...)` aufgerufen. Dabei wird ausschließlich das angeforderte logische Repository entfernt. Ein erneut geöffnetes persistentes Repository wird nicht noch einmal mit einem Initial-Commit befüllt, wenn es bereits Refs besitzt.

## Ref-Updates und Reflogs

Alle produktiven Ref-Änderungen in Taxonomy setzen die erwartete alte Objekt-ID, die neue Objekt-ID, den Akteur mit `setRefLogIdent(...)` und eine operationsspezifische Nachricht mit `setRefLogMessage(...)`.

Jedes `RefUpdate.Result` wird geprüft. Abgelehnte, gesperrte oder auf fehlende Objekte verweisende Ergebnisse lassen die Operation fehlschlagen, statt als Erfolg protokolliert zu werden. Die Bibliothek schreibt Reftable- und abfragbaren Reflog-Zustand innerhalb ihrer Repository-bezogenen Transaktion. Taxonomy liest Reflogs über die öffentliche JGit-API:

```java
repository.getReflogReader("refs/heads/draft").getLastEntry();
```

## Schema- und Migrationsautorität

Die veröffentlichte Bibliothek besitzt die unveränderlichen SQL-Ressourcen und stellt ihre stabilen Pfade über `CoreSchemaMigrations` bereit. Taxonomy entscheidet, ob einer dieser Ströme für ein Produkt-Datenbankprofil aktiviert wird. Die Verfügbarkeit in der Bibliothek ist deshalb nicht dasselbe wie ein aktivierter und zertifizierter Taxonomy-Pfad.

Das festgelegte Artefakt stellt folgende öffentliche Pfade bereit:

| Strom | Öffentliche Konstante | Paketierter Classpath-Pfad |
|---|---|---|
| HSQLDB Core | `CoreSchemaMigrations.HSQLDB_LOCATION` | `classpath:db/migration/jgit-storage-hibernate/core/hsqldb` |
| Übernahme eines alten HSQLDB-Schemas | `CoreSchemaMigrations.HSQLDB_LEGACY_ADOPTION_LOCATION` | `classpath:db/migration/jgit-storage-hibernate/core/adoption/hsqldb` |
| PostgreSQL Core | `CoreSchemaMigrations.POSTGRESQL_LOCATION` | `classpath:db/migration/jgit-storage-hibernate/core/postgresql` |
| Übernahme eines alten PostgreSQL-Schemas | `CoreSchemaMigrations.POSTGRESQL_LEGACY_ADOPTION_LOCATION` | `classpath:db/migration/jgit-storage-hibernate/core/adoption/postgresql` |
| Microsoft SQL Server Core | `CoreSchemaMigrations.SQL_SERVER_LOCATION` | `classpath:db/migration/jgit-storage-hibernate/core/sqlserver` |
| Übernahme eines alten SQL-Server-Schemas | `CoreSchemaMigrations.SQL_SERVER_LEGACY_ADOPTION_LOCATION` | `classpath:db/migration/jgit-storage-hibernate/core/adoption/sqlserver` |

Die im festgelegten Artefakt enthaltenen normalen HSQLDB-, PostgreSQL- und SQL-Server-Ströme enthalten die Migrationen `0.1.4`, `0.1.5`, `0.1.14`, `0.1.14.1`, `0.1.14.2`, `0.1.17`, `0.1.18`, `0.9.1` und `0.9.2`. Die HSQLDB- und PostgreSQL-Adoptionsströme enthalten V1 und V2 für das alte Taxonomy-Schema. Der SQL-Server-Adoptionsstrom enthält eigene V1 und V2 für das alte Sandbox-/Vor-Bibliotheks-Schema. `CoreSchemaMigrations.LEGACY_ADOPTION_VERSION` ist `2`.

Diese Nummern beschreiben die Ressourcen der exakt aufgelösten Abhängigkeit; Taxonomy führt keine Kopie der Migrations-SQL. Der Dokumentationsvertrag prüft, dass jede genannte abschließende Ressource im Test-Classpath vorhanden ist. Ein aktualisiertes Artefakt kann dadurch nicht unbemerkt von dieser Beschreibung abweichen.

Spring Boot Flyway verwendet getrennte Historientabellen:

- `CoreSchemaMigrations.SCHEMA_HISTORY_TABLE` → `jgit_storage_hibernate_core_schema_history`;
- `CoreSchemaMigrations.LEGACY_ADOPTION_SCHEMA_HISTORY_TABLE` → `jgit_storage_hibernate_core_adoption_history`.

`JgitStorageHibernateSchemaFilterProvider` hält die gemappten bibliothekseigenen Tabellen `git_packs`, `git_reflog`, `git_repository_lock` und `git_pack_chunks` aus Hibernate-Erzeugung, -Aktualisierung, -Leerung und -Löschung heraus; die Schemavalidierung bleibt aktiv. Der Core-Migrationsstrom besitzt außerdem alle weiteren von ihm erzeugten Speicherstrukturen, darunter den Repository-Lebenszykluszustand. Taxonomy darf dafür keine anwendungseigene DDL einführen.

## Tatsächlich von Taxonomy unterstützte Datenbankpfade

`JgitStorageSchemaMigrationConfig.DatabaseFamily` wählt derzeit ausschließlich HSQLDB und PostgreSQL. Die SQL-Server-Migrationsressourcen sind im festgelegten Upstream-Release vorhanden, das Taxonomy-SQL-Server-Profil aktiviert sie aber noch nicht. Für Oracle stellt die festgelegte Bibliothek keinen öffentlichen Core-/Adoptionspfad bereit.

| Datenbank | Upstream-Migrationsressourcen im festgelegten Release | Von Taxonomy verwaltete Core-Migration/Übernahme | Aktuelle Evidenz und Grenze |
|---|---|---:|---|
| HSQLDB | Core plus Taxonomy-Adoption V1/V2 | ja | Standard-/Lokaler Pfad und direkte Maven-/JUnit-Migrationstests |
| PostgreSQL | Core plus Taxonomy-Adoption V1/V2 | ja | Testcontainers-Abdeckung für Migration, Persistenz und Datenbankmatrix |
| Microsoft SQL Server | Core plus SQL-Server-Adoption V1/V2 | nein | `application-mssql.properties` lässt Flyway deaktiviert; Anwendungs-/Dialekttests zertifizieren nicht den vollständigen Core-Migrations-, Adoptions-, Restart- und Persistenzpfad |
| Oracle | kein Core- oder Adoptionspfad | nein | `application-oracle.properties` lässt Flyway deaktiviert; Anwendungs-/Dialekttests begründen keine Unterstützung persistenter Core-Migrationen |

Die Bibliothek stellt außerdem einen H2-Core-Pfad bereit, Taxonomy besitzt jedoch kein H2-Produktprofil. Das ist kein unterstützter Anwendungspfad.

SQL Server und Oracle dürfen nicht allein deshalb als von Taxonomy verwaltete persistente JGit-Core-Migrationspfade beschrieben werden, weil ein Anwendungsprofil oder ein Kompatibilitätsjob existiert. Eine Aktivierung für SQL Server erfordert eine getrennte, begrenzte Änderung mit Datenbankfamilie, frischer und alter Migration, Prüfung des physischen Schemas und der Indizes, erneutem Öffnen der Repositorys, Restart-/Fehler-Recovery sowie erfolgreicher realer SQL-Server-Matrix.

## Startklassifikation auf aktivierten Pfaden

Für HSQLDB und PostgreSQL wird Flyway abgeschlossen, bevor der von Spring verwaltete Persistence Context initialisiert wird. Taxonomy klassifiziert den physischen Zustand, bevor eine Aktion gewählt wird:

| Vorhandener Zustand | Startaktion |
|---|---|
| Leere Datenbank | Den veröffentlichten frischen Core-Strom ausführen |
| Gemeinsames Schema mit anderen Tabellen, aber ohne Core-Tabellen | Baseline `0` etablieren und danach den veröffentlichten Strom ausführen |
| Exakte unversionierte Struktur einer erkannten veröffentlichten Core-Version | Passenden geprüften Historienpunkt etablieren, ausstehende Migrationen ausführen und Ergebnis validieren |
| Verwaltete Core-Historie mit unterstützter physischer Struktur | Ausstehende Migrationen ausführen und Historie, Spalten, Längen sowie Indizes erneut prüfen |
| Exakte alte Taxonomy-Struktur | Ohne einmalige Legacy-Freigabe fehlschlagen; danach veröffentlichte Adoption V1/V2 und normalen Core-Strom ausführen |
| Adoption V1 bereits aufgezeichnet, V2 aber noch erforderlich | Ohne einmalige Freigabe fehlschlagen; danach verbleibenden veröffentlichten Adoptionsschritt ausführen |
| Partielle Tabellen, unbekannte Spalten, nicht unterstützte Längen, doppelte Identitäten, inkonsistente Historie oder fehlende Pflichtindizes | Vor automatischer Reparatur fehlschlagen |

Erkannte unversionierte Release-Strukturen umfassen die Migrationshistorie bis `0.9.2`; Taxonomy akzeptiert ausschließlich exakte physische Verträge. Die Anwendung errät keine nächstgelegene Version aus Zeitstempeln oder einer Teilmenge von Spalten.

## Übernahme einer bestehenden Taxonomy-Datenbank

Die alten Vor-Bibliotheks-Tabellen unterscheiden sich durch Commit-Statusspalten, Indizes und physische Längen vom veröffentlichten Core-Vertrag:

- `git_packs.pack_extension` war implizit `VARCHAR(255)`; Core verlangt `VARCHAR(32)`;
- `git_reflog.ref_name` war implizit `VARCHAR(255)`; Core verlangt Platz für 1024 Zeichen.

Dieser Ablauf gilt nur für die aktivierten HSQLDB- oder PostgreSQL-Pfade:

1. Alle schreibenden Instanzen stoppen und eine wiederherstellbare Sicherung erstellen.
2. Repository-Anzahlen, geordnete Prüfsummen aller `git_packs.data`-BLOBs und die vorhandenen Reflog-Zeilen erfassen.
3. Erst nach vorhandener Sicherung und Prüfevidenz einmalig mit `TAXONOMY_JGIT_STORAGE_LEGACY_ADOPTION=true` starten.
4. Der veröffentlichte schreibgeschützte Vorabtest weist partielle Schemata, unvollständige Zeilen, doppelte Identitäten aus `(repository_name, pack_name, pack_extension)` und jeden `pack_extension`-Wert mit mehr als 32 Zeichen zurück.
5. Die veröffentlichte Adoption V1 ergänzt den Commit-Zustand, füllt `committed_at` und etabliert den eindeutigen sowie den Commit-Statusindex.
6. Die veröffentlichte Adoption V2 verkleinert `pack_extension` von 255 auf 32 und erweitert `ref_name` von 255 auf 1024.
7. Taxonomy etabliert oder validiert die normale Core-Historie und prüft abschließend Spalten, Längen und Pflichtindizes.
8. Nach erfolgreichem Start die Legacy-Freigabe sofort wieder entfernen.
9. Mindestens zwei logische Repositorys erneut öffnen, Refs und Commits traversieren, BLOB-Prüfsummen und Reflog-Zeilen vergleichen und normale abfragbare Reflogs prüfen, bevor Schreibzugriffe wieder erlaubt werden.

Hibernate `ddl-auto=update`, manuelle Ad-hoc-DDL, Flyway `repair` oder das Löschen der Migrationshistorie dürfen diesen Ablauf nicht ersetzen. Taxonomy wählt niemals automatisch eine doppelte Zeile aus und kürzt keinen zu langen Wert.

## Verifikation

Die Integration wird durch normale Maven-/JUnit-/Failsafe-Autorität abgedeckt:

- `JgitStorageHibernateIntegrationTest` prüft die Registrierung öffentlicher Core-Entities, Persistenz über Schließen und erneutes Öffnen, Refs, Commits, Reflogs, Isolation logischer Repository-Namen und begrenztes Löschen.
- `JgitStorageSchemaMigrationConfigTest` deckt frische/gemeinsam genutzte Schemata, die Historienetablierung exakter Release-Strukturen, V1/V2-Adoption, Datenerhalt, ungültige/partielle Zustände und Idempotenz ab.
- `JgitStoragePostgresMigrationIT` wiederholt die alte Taxonomy-Übernahme gegen PostgreSQL und prüft die getrennten Historien.
- `JgitStorageDocumentationContractTest` leitet Abhängigkeits-/Distributionsdaten aus dem Root-POM ab, hält deutsche und englische Anleitung synchron, weist veraltete authentifizierte Zugriffsinstruktionen zurück und prüft die genannten Core-/Adoptionsressourcen des aufgelösten Artefakts.

Eine saubere Verifikation löst das festgelegte `jgit-storage-hibernate-core`-Release anonym über das konfigurierte Release-Repository auf. Verwende ohne zusätzliche Variante den autoritativen CI-Befehl des Repositorys:

```bash
./mvnw -q verify -DexcludedGroups="real-llm"
```

GitHub Actions darf Maven-Aufrufe auswählen oder parallelisieren, aber weder einen abweichenden Migrations- noch einen eigenen Dokumentations-Pass/Fail-Vertrag besitzen.
