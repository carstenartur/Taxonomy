# Hibernate-basierte JGit-Speicherung

Taxonomy speichert die Historien der Architecture DSL und der Einstellungen über die veröffentlichte Bibliothek [`jgit-storage-hibernate-core`](https://github.com/carstenartur/jgit-storage-hibernate) in relationalen Datenbanktabellen. Eine kopierte Implementierung des JGit-DFS- und Reftable-Backends gehört nicht mehr zum Taxonomy-Quellcode.

## Verantwortungsgrenze

| Taxonomy verantwortet | `jgit-storage-hibernate-core` verantwortet |
|---|---|
| DSL-Dateiname, Parser und semantischen Diff | JGit-DFS-Repository-Implementierung |
| Branch-, Merge-, Cherry-pick-, Revert- und Workspace-Abläufe | Pack-/Objektpersistenz |
| Logische Repository-Namen und Workspace-Routing | Reftable-basierte Ref-Persistenz |
| Preferences-JSON und Branch-Konvention | Abfragbare Reflog-Persistenz |
| Autorisierung, Audit, REST- und UI-Verträge | Transaktionales Löschen logischer Repositorys |
| Anwendungsprojektionen und Hibernate-Search-Indizes | Core-Entities und Schema-Migrationen |

Taxonomy verwendet nur öffentliche Typen aus `io.github.carstenartur.jgit.storage.hibernate` sowie öffentliche JGit-APIs. Anwendungscode darf keine Implementierungspakete `repository`, `objects` oder `refs` der Bibliothek importieren.

## Abhängigkeit und Paket-Zugriff

Die festgelegte Version steht im Root-POM:

```xml
<jgit-storage-hibernate.version>0.1.8</jgit-storage-hibernate.version>
```

Das Anwendungsmodul bindet das Core-Artefakt ein:

```xml
<dependency>
  <groupId>io.github.carstenartur</groupId>
  <artifactId>jgit-storage-hibernate-core</artifactId>
  <version>${jgit-storage-hibernate.version}</version>
</dependency>
```

Version 0.1.8 wird derzeit über GitHub Packages bereitgestellt. Die Maven-Zugangsdaten müssen dieselbe Server-ID wie der Repository-Eintrag in `pom.xml` verwenden:

```xml
<settings>
  <servers>
    <server>
      <id>github</id>
      <username>${env.GITHUB_ACTOR}</username>
      <password>${env.GITHUB_TOKEN}</password>
    </server>
  </servers>
</settings>
```

Das Token benötigt `read:packages`. Tokens dürfen niemals in das Repository eingecheckt werden. GitHub-Actions-Jobs verwenden ihr eingeschränktes `GITHUB_TOKEN` und benötigen die Berechtigung `packages: read`.

## Von Spring verwalteter Persistence Context

Die Core-Entities der Bibliothek liegen außerhalb von `com.taxonomy`. Deshalb nimmt die Anwendung das Paket `io.github.carstenartur.jgit.storage.hibernate.entity` ausdrücklich in `@EntityScan` auf. Der Integrationstest vergleicht das resultierende JPA-Metamodell mit `CoreEntities.annotatedClasses()`, damit eine spätere neue Bibliotheks-Entity nicht unbemerkt fehlt.

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

Die physischen Tabellen werden gemeinsam verwendet, jede Abfrage ist jedoch auf einen exakten logischen Repository-Namen eingeschränkt:

| Zweck | Logischer Name |
|---|---|
| Gemeinsame System-DSL | `taxonomy-dsl` |
| Workspace-DSL | `ws-<workspace-id>` |
| Einstellungen | `taxonomy-preferences` |

`DslGitRepositoryFactory` hält geöffnete Handles in einem Cache. Ein Cache-Evict schließt das Handle, lässt die Datenbankzeilen aber absichtlich bestehen. Beim endgültigen Löschen eines Workspaces wird zuerst das Handle geschlossen und danach `HibernateRepositoryFactory.deleteRepository(...)` aufgerufen. Dabei werden ausschließlich Zeilen des angeforderten logischen Namens entfernt.

Ein erneut geöffnetes persistentes Workspace-Repository wird nicht noch einmal mit einem zusätzlichen Initial-Commit befüllt, wenn es bereits Refs besitzt.

## Ref-Updates und Reflogs

Alle Ref-Änderungen in Taxonomy setzen:

- die erwartete alte Objekt-ID;
- die neue Objekt-ID;
- den Akteur mit `setRefLogIdent(...)`;
- eine operationsspezifische Nachricht mit `setRefLogMessage(...)`.

Jedes `RefUpdate.Result` wird geprüft. Abgelehnte, gesperrte oder auf fehlende Objekte verweisende Ergebnisse lassen die Operation fehlschlagen, statt als Erfolg protokolliert zu werden. Die Bibliothek schreibt Reftable-Update und abfragbare `git_reflog`-Zeile in derselben Repository-bezogenen Transaktion. Reflogs werden über die normale öffentliche API gelesen:

```java
repository.getReflogReader("refs/heads/draft").getLastEntry();
```

## Unterstützte Schema-Pfade

Die veröffentlichte Bibliothek stellt folgende Core-Migrationspfade bereit und testet sie:

| Datenbank | Frisches Schema | Übernahme der alten Taxonomy-Struktur |
|---|---:|---:|
| HSQLDB | ja | ja |
| PostgreSQL | ja | ja |
| H2 | ja | 0.1.4-Baseline-Pfad |
| Microsoft SQL Server | noch nicht bereitgestellt | noch nicht bereitgestellt |
| Oracle | noch nicht bereitgestellt | noch nicht bereitgestellt |

Taxonomy unterstützt SQL Server und Oracle weiterhin für seine Anwendungs-Entities. Persistenter Core-Storage auf diesen Datenbanken darf jedoch erst als migrationsunterstützt bezeichnet werden, wenn die Bibliothek dialektspezifische Migrationen und echte Integrationstests veröffentlicht.

## Neuinstallation

Verwende die öffentlichen Konstanten aus `CoreSchemaMigrations`; Klassenpfade dürfen nicht in Anwendungscode kopiert werden. Für ein leeres HSQLDB-Schema:

```java
Flyway.configure()
    .dataSource(dataSource)
    .locations(CoreSchemaMigrations.HSQLDB_LOCATION)
    .table(CoreSchemaMigrations.SCHEMA_HISTORY_TABLE)
    .load()
    .migrate();
```

Für PostgreSQL wird `POSTGRESQL_LOCATION` verwendet. Enthält ein gemeinsames Schema bereits andere Anwendungstabellen, aber noch keine Core-Tabellen, ist einmalig das in der Bibliothek dokumentierte Baseline-0-Verfahren zu verwenden.

Flyway muss abgeschlossen sein, bevor Hibernate den Persistence Context validiert. Für persistente Installationen ist Migration plus `hibernate.hbm2ddl.auto=validate` das Zielmodell. Auch das übrige Taxonomy-Anwendungsschema muss bereitgestellt sein, bevor die globale Hibernate-Einstellung auf `validate` umgestellt wird.

## Übernahme einer bestehenden Taxonomy-Datenbank

Die kopierte alte Tabelle `git_packs` enthält weder die Commit-Statusspalten noch die eindeutige logische Pack-Identität. `ddl-auto=update` darf diese Datenmigration nicht improvisieren.

Befolge den veröffentlichten [Taxonomy-Adoptionsleitfaden](https://github.com/carstenartur/jgit-storage-hibernate/blob/v0.1.8/docs/taxonomy-adoption.md) exakt:

1. Alle schreibenden Instanzen stoppen und eine wiederherstellbare Sicherung erstellen.
2. Repository-Anzahlen und geordnete Prüfsummen aller `git_packs.data`-BLOBs erfassen.
3. Vor jeder DDL-Anweisung `LegacyCoreSchemaAdoption.requireSafeToAdopt(connection)` ausführen.
4. Den eigenen HSQLDB- oder PostgreSQL-Legacy-Adoptionspfad mit separater Flyway-Historientabelle und Baseline-Version `0` ausführen.
5. Anschließend die normale Core-Historie bei `CoreSchemaMigrations.CURRENT_SCHEMA_VERSION` etablieren.
6. Hibernate mit Schema-Validierung starten.
7. Mindestens zwei logische Repositorys erneut öffnen und Refs sowie Commits traversieren.
8. BLOB-Prüfsummen und normale abfragbare Reflogs prüfen, bevor Schreibzugriffe wieder erlaubt werden.

Der Vorabtest weist partielle Schemata, unvollständige Zeilen und doppelte Identitäten aus `(repository_name, pack_name, pack_extension)` zurück. Duplikate müssen anhand von Anwendungswissen aufgelöst oder aus einer verlässlichen Sicherung wiederhergestellt werden; weder Taxonomy noch die Bibliothek wählen automatisch eine Zeile aus.

## Verifikation

`JgitStorageHibernateIntegrationTest` prüft im von Spring verwalteten HSQLDB-Persistence-Context:

- alle öffentlichen Core-Entity-Typen sind registriert;
- DSL-Commit und Branch-Ref überleben das Schließen und erneute Öffnen des Handles;
- normale Taxonomy-Commits erzeugen abfragbare Reflog-Einträge;
- zwei logische Repository-Namen bleiben isoliert;
- das Löschen eines Repositorys entfernt dessen Pack- und Reflog-Zeilen, ohne das andere Repository zu verändern.

Der vollständige Projekt-Gate bleibt:

```bash
mvn verify -DexcludedGroups="real-llm"
```

Die Container-Workflows für PostgreSQL, SQL Server und Oracle prüfen weiterhin die breitere Taxonomy-Datenbankmatrix. Die Migrationsmatrix der Storage-Bibliothek bleibt absichtlich enger, bis passende Upstream-Migrationen vorliegen.
