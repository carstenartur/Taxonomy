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
| Anwendungsprojektionen und Hibernate-Search-Indizes | Core-Entities und versionierte Core-Migrationen |

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

Die Core-Entities der Bibliothek liegen außerhalb von `com.taxonomy`. Deshalb nimmt die Anwendung `io.github.carstenartur.jgit.storage.hibernate.entity` ausdrücklich in `@EntityScan` auf. Der Integrationstest vergleicht das resultierende JPA-Metamodell mit `CoreEntities.annotatedClasses()`, damit eine spätere neue Bibliotheks-Entity nicht unbemerkt fehlt.

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

Ein erneut geöffnetes persistentes Repository wird nicht noch einmal mit einem Initial-Commit befüllt, wenn es bereits Refs besitzt.

## Ref-Updates und Reflogs

Alle Ref-Änderungen in Taxonomy setzen die erwartete alte Objekt-ID, die neue Objekt-ID, den Akteur mit `setRefLogIdent(...)` und eine operationsspezifische Nachricht mit `setRefLogMessage(...)`.

Jedes `RefUpdate.Result` wird geprüft. Abgelehnte, gesperrte oder auf fehlende Objekte verweisende Ergebnisse lassen die Operation fehlschlagen, statt als Erfolg protokolliert zu werden. Die Bibliothek schreibt Reftable-Update und abfragbare `git_reflog`-Zeile in derselben Repository-bezogenen Transaktion. Reflogs werden über die normale öffentliche API gelesen:

```java
repository.getReflogReader("refs/heads/draft").getLastEntry();
```

## Schema-Eigentum und Startreihenfolge

Die Flyway-Integration von Spring Boot verantwortet in den unterstützten Taxonomy-Profilen die beiden Core-Tabellen:

- `git_packs`
- `git_reflog`

`application-hsqldb.properties` und `application-postgres.properties` aktivieren Flyway und wählen den veröffentlichten datenbankspezifischen Migrationsstrom. Die Standard-, SQL-Server- und Oracle-Konfigurationen lassen diese Core-Migrationsintegration deaktiviert.

`JgitStorageHibernateSchemaFilterProvider` schließt die beiden Core-Tabellen aus Erstellen, Aktualisieren, Leeren und Löschen durch Hibernate aus. In der Hibernate-Schemavalidierung bleiben die Entities vollständig enthalten. Dadurch können `ddl-auto=create` und `ddl-auto=update` weder mit Flyway konkurrieren noch die Core-Migration improvisieren, während das übrige Taxonomy-Anwendungsschema seinen bisherigen Hibernate-Lebenszyklus zunächst beibehält.

Flyway wird abgeschlossen, bevor der von Spring verwaltete Persistence Context initialisiert wird. Der Start klassifiziert die Datenbank vor Auswahl des Pfads:

| Vorhandener Zustand | Startaktion |
|---|---|
| Leere Datenbank | Veröffentlichte frische Core-Migrationen ausführen |
| Gemeinsames Schema mit anderen Tabellen, aber ohne Core-Tabellen | Baseline `0` etablieren, danach die veröffentlichten Migrationen ausführen |
| Exakte unversionierte aktuelle Core-Tabellen | Schreibgeschützte Sicherheitsprüfung, normale Historie bei `0.1.4` etablieren, ausstehende Migrationen ausführen |
| Verwaltete Core-Historie und exakte aktuelle Struktur | Ausstehende Migrationen ausführen und physischen Vertrag erneut prüfen |
| Exakte alte Taxonomy-Struktur | Ohne einmalige Legacy-Freigabe fehlschlagen |
| Eine fehlende Core-Tabelle, unbekannte Spalten oder nicht unterstützte Längen | Vor automatischer Reparatur fehlschlagen |
| Adoptionshistorie ohne normale Core-Historie | Fehlschlagen und Wiederherstellung oder dokumentierte Reparatur verlangen |

## Unterstützte Datenbankpfade

| Datenbank | Frisches Core-Schema in Taxonomy | Übernahme eines vorhandenen Taxonomy-Core-Schemas |
|---|---:|---:|
| HSQLDB | ja | ja, direkt getestet |
| PostgreSQL | ja | ja, direkt mit Testcontainers getestet |
| H2 | Bibliotheksmigration vorhanden; kein Taxonomy-Profil | kein Anwendungspfad |
| Microsoft SQL Server | keine veröffentlichte Core-Migration | nein |
| Oracle | keine veröffentlichte Core-Migration | nein |

Taxonomy unterstützt SQL Server und Oracle weiterhin für seine Anwendungs-Entities. Persistenter Core-Storage auf diesen Datenbanken darf jedoch erst als migrationsunterstützt bezeichnet werden, wenn die Bibliothek dialektspezifische Migrationen und passende Integrationstests veröffentlicht.

## Neuinstallation

Anwendungscode kopiert keine Klassenpfade für Migrationen. `JgitStorageSchemaMigrationConfig` verwendet die öffentlichen Konstanten aus `CoreSchemaMigrations` und konfiguriert die von Boot verwaltete Flyway-Instanz für HSQLDB oder PostgreSQL.

Ein frischer HSQLDB- oder PostgreSQL-Start erstellt die eigene Core-Historie und führt die veröffentlichten Migrationen `0.1.4` und `0.1.5` aus, bevor Hibernate initialisiert wird. Enthält dasselbe Schema bereits andere Taxonomy-Tabellen, zeichnet die Orchestrierung zunächst die dokumentierte Vor-Migrations-Baseline `0` auf; ein unbekanntes partielles Core-Schema wird niemals gebaselined.

Das langfristige Betriebsmodell für vollständig provisionierte persistente Installationen bleibt Flyway plus globale Hibernate-Validierung. Bis auch das übrige Taxonomy-Anwendungsschema über Flyway verwaltet wird, bildet der Schema-Filter die engere sichere Grenze: Flyway besitzt die Core-DDL, Hibernate verwaltet die übrigen Anwendungstabellen.

## Übernahme einer bestehenden Taxonomy-Datenbank

Die kopierten alten Tabellen unterscheiden sich nicht nur durch fehlende Commit-Statusspalten und die fehlende eindeutige Pack-Identität vom veröffentlichten Core-Vertrag:

- `git_packs.pack_extension` war implizit `VARCHAR(255)`; Core verlangt `VARCHAR(32)`;
- `git_reflog.ref_name` war implizit `VARCHAR(255)`; Core verlangt Platz für 1024 Zeichen.

Die Anwendung verweigert eine Legacy-Übernahme deshalb standardmäßig. Verwende diesen Ablauf:

1. Alle schreibenden Instanzen stoppen und eine wiederherstellbare Sicherung erstellen.
2. Repository-Anzahlen und geordnete Prüfsummen aller `git_packs.data`-BLOBs erfassen.
3. Erst nach vorhandener Sicherung und Prüfevidenz einmalig mit `TAXONOMY_JGIT_STORAGE_LEGACY_ADOPTION=true` starten.
4. Der schreibgeschützte Vorabtest weist partielle Schemata, unvollständige Zeilen, doppelte Identitäten aus `(repository_name, pack_name, pack_extension)` und jeden `pack_extension`-Wert mit mehr als 32 Zeichen zurück.
5. Erst nach allen erfolgreichen Vorabtests wird die exakte alte Länge 255 bei `pack_extension` auf 32 verkleinert und `ref_name` von 255 auf 1024 erweitert. Unbekannte Längen werden zurückgewiesen.
6. Die veröffentlichte Legacy-Adoptionsmigration ergänzt den Commit-Zustand, füllt `committed_at`, erzeugt die eindeutige Identität und schreibt ihre separate Historie. Anschließend wird die normale Core-Historie bei der aktuellen Version etabliert.
7. Nach erfolgreichem Start die Legacy-Freigabe sofort wieder entfernen.
8. Mindestens zwei logische Repositorys erneut öffnen, Refs und Commits traversieren, BLOB-Prüfsummen vergleichen und normale abfragbare Reflogs prüfen, bevor Schreibzugriffe wieder erlaubt werden.

Hibernate `ddl-auto=update` darf diese Datenmigration nicht ersetzen. Taxonomy wählt niemals automatisch eine von mehreren doppelten Zeilen aus.

Die in Bibliotheksversion 0.1.8 fehlende Normalisierung der Spaltenlängen wird als [`jgit-storage-hibernate` Issue #78](https://github.com/carstenartur/jgit-storage-hibernate/issues/78) verfolgt. Die Taxonomy-seitige Übergangsbrücke arbeitet absichtlich fail-closed und kann entfernt werden, sobald eine veröffentlichte Upstream-Migration bereits übernommene und noch nicht übernommene Datenbanken abdeckt.

## Verifikation

`JgitStorageHibernateIntegrationTest` prüft im von Spring verwalteten HSQLDB-Persistence-Context die Registrierung aller öffentlichen Core-Entities, Persistenz von Commits und Refs über Schließen und erneutes Öffnen, abfragbare Reflogs, Isolation logischer Repository-Namen und das begrenzte Löschen eines einzelnen Repositorys.

`JgitStorageSchemaMigrationConfigTest` deckt frische und gemeinsam genutzte HSQLDB-Schemata, die Historienetablierung für exakte unversionierte Core-Tabellen, die ausdrückliche Legacy-Freigabe, physische Spaltennormalisierung, Erhalt von BLOB- und Reflogdaten, Duplikatverweigerung, zu lange Erweiterungswerte und partielle Schemata ab.

`JgitStoragePostgresMigrationIT` wiederholt die reale alte Taxonomy-Übernahme auf PostgreSQL und ist ein eigener Job in der Datenbank-Kompatibilitätsmatrix.

Der vollständige Projekt-Gate bleibt:

```bash
mvn verify -DexcludedGroups="real-llm"
```
