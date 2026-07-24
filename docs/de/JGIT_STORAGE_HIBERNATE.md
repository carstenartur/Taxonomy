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
| Anwendungsprojektionen und Hibernate-Search-Indizes | Core-Entities und versionierte Core-/Adoptionsmigrationen |

Taxonomy verwendet nur öffentliche Typen aus `io.github.carstenartur.jgit.storage.hibernate` sowie öffentliche JGit-APIs. Anwendungscode darf keine Implementierungspakete `repository`, `objects` oder `refs` der Bibliothek importieren.

## Abhängigkeit und Paket-Zugriff

Die festgelegte Version steht im Root-POM:

```xml
<jgit-storage-hibernate.version>0.1.9</jgit-storage-hibernate.version>
```

Das Anwendungsmodul bindet das Core-Artefakt ein:

```xml
<dependency>
  <groupId>io.github.carstenartur</groupId>
  <artifactId>jgit-storage-hibernate-core</artifactId>
  <version>${jgit-storage-hibernate.version}</version>
</dependency>
```

Version 0.1.9 wird derzeit über GitHub Packages bereitgestellt. Die Maven-Zugangsdaten müssen dieselbe Server-ID wie der Repository-Eintrag in `pom.xml` verwenden:

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

Das ist eine vorübergehende Einschränkung der Distribution und nicht der gewünschte Konsumentenvertrag. [`jgit-storage-hibernate` Issue #62](https://github.com/carstenartur/jgit-storage-hibernate/issues/62) verfolgt die Veröffentlichung über Maven Central. Solange ein veröffentlichtes Artefakt nicht anonym aus Maven Central aufgelöst werden kann, ist ein sauberer Taxonomy-Checkout ohne GitHub-Zugangsdaten nicht gleichwertig zum GitHub-Build; der Integrations-PR bleibt deshalb ein Draft.

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

`application-hsqldb.properties` und `application-postgres.properties` aktivieren Flyway und wählen den veröffentlichten datenbankspezifischen Migrationsstrom. Die SQL-Server- und Oracle-Konfigurationen lassen diese Core-Migrationsintegration deaktiviert, weil die Bibliothek für diese Datenbanken keine entsprechenden Core-Migrationen veröffentlicht.

`JgitStorageHibernateSchemaFilterProvider` schließt die beiden Core-Tabellen aus Erstellen, Aktualisieren, Leeren und Löschen durch Hibernate aus. In der Hibernate-Schemavalidierung bleiben die Entities vollständig enthalten. Dadurch können `ddl-auto=create` und `ddl-auto=update` weder mit Flyway konkurrieren noch die Core-Migration improvisieren, während das übrige Taxonomy-Anwendungsschema seinen bisherigen Hibernate-Lebenszyklus zunächst beibehält.

Flyway wird abgeschlossen, bevor der von Spring verwaltete Persistence Context initialisiert wird. Der Start klassifiziert die Datenbank vor Auswahl des Pfads:

| Vorhandener Zustand | Startaktion |
|---|---|
| Leere Datenbank | Veröffentlichte frische Core-Migrationen ausführen |
| Gemeinsames Schema mit anderen Tabellen, aber ohne Core-Tabellen | Baseline `0` etablieren, danach die veröffentlichten Migrationen ausführen |
| Exakte unversionierte aktuelle Core-Tabellen | Schreibgeschützte Sicherheitsprüfung, normale Historie bei `0.1.4` etablieren, ausstehende Migrationen ausführen |
| Verwaltete Core-Historie und exakte aktuelle Struktur | Ausstehende Core-Migrationen ausführen und den physischen Vertrag erneut prüfen |
| Exakte alte Taxonomy-Struktur | Ohne einmalige Legacy-Freigabe fehlschlagen; danach veröffentlichte Adoption V1 und V2 ausführen |
| Bereits mit 0.1.8 übernommen, aber weiterhin Längen 255/255 | Ohne einmalige Freigabe fehlschlagen; danach veröffentlichte Adoption V2 ausführen |
| Eine fehlende Core-Tabelle, unbekannte Spalten, nicht unterstützte Längen oder fehlende Pflichtindizes | Vor automatischer Reparatur fehlschlagen |
| Adoptionshistorie ohne normale Core-Historie | Fehlschlagen und Wiederherstellung oder dokumentierte Reparatur verlangen |

Taxonomy enthält keine datenbankspezifischen `ALTER TABLE`-Anweisungen für die bibliothekseigenen Spalten. Die Anwendung klassifiziert den Zustand, führt den Vorabtest aus und validiert das Ergebnis; alle physischen Adoptionsänderungen stammen aus den unveränderlichen Migrationsressourcen von `jgit-storage-hibernate-core:0.1.9`.

## Unterstützte Datenbankpfade

| Datenbank | Frisches Core-Schema in Taxonomy | Übernahme eines vorhandenen Taxonomy-Core-Schemas |
|---|---:|---:|
| HSQLDB | ja | ja, direkt getestet |
| PostgreSQL | ja | ja, direkt mit Testcontainers getestet |
| H2 | Bibliotheksmigration vorhanden; kein Taxonomy-Profil | kein Anwendungspfad |
| Microsoft SQL Server | keine veröffentlichte Core-Migration | nein |
| Oracle | keine veröffentlichte Core-Migration | nein |

Taxonomy unterstützt SQL Server und Oracle weiterhin für seine Anwendungs-Entities. Persistenter Core-Storage auf diesen Datenbanken darf jedoch erst als migrationsunterstützt bezeichnet werden, wenn die Bibliothek dialektspezifische Migrationen und passende Integrationstests veröffentlicht.

## Übernahme einer bestehenden Taxonomy-Datenbank

Die kopierten alten Tabellen unterscheiden sich durch Commit-Statusspalten, Indizes und physische Längen vom veröffentlichten Core-Vertrag:

- `git_packs.pack_extension` war implizit `VARCHAR(255)`; Core verlangt `VARCHAR(32)`;
- `git_reflog.ref_name` war implizit `VARCHAR(255)`; Core verlangt Platz für 1024 Zeichen.

Verwende diesen Ablauf:

1. Alle schreibenden Instanzen stoppen und eine wiederherstellbare Sicherung erstellen.
2. Repository-Anzahlen, geordnete Prüfsummen aller `git_packs.data`-BLOBs und die vorhandenen Reflog-Zeilen erfassen.
3. Erst nach vorhandener Sicherung und Prüfevidenz einmalig mit `TAXONOMY_JGIT_STORAGE_LEGACY_ADOPTION=true` starten.
4. Der veröffentlichte schreibgeschützte Vorabtest weist partielle Schemata, unvollständige Zeilen, doppelte Identitäten aus `(repository_name, pack_name, pack_extension)` und jeden `pack_extension`-Wert mit mehr als 32 Zeichen zurück.
5. Der veröffentlichte Adoptionsstrom führt alle ausstehenden Migrationen der Reihe nach aus. V1 ergänzt den Commit-Zustand, füllt `committed_at`, erzeugt die eindeutige Pack-Identität und den Commit-Statusindex. V2 verkleinert `pack_extension` von 255 auf 32 und erweitert `ref_name` von 255 auf 1024.
6. Taxonomy etabliert oder validiert die normale Core-Historie und prüft nach der Migration Spalten, Längen und Pflichtindizes.
7. Nach erfolgreichem Start die Legacy-Freigabe sofort wieder entfernen.
8. Mindestens zwei logische Repositorys erneut öffnen, Refs und Commits traversieren, BLOB-Prüfsummen und Reflog-Zeilen vergleichen und normale abfragbare Reflogs prüfen, bevor Schreibzugriffe wieder erlaubt werden.

Eine bereits mit Version 0.1.8 übernommene Datenbank enthält die erfolgreiche Adoptionsversion `1`, kann aber weiterhin beide Spalten mit Länge 255 besitzen. Wende denselben Sicherungs- und einmaligen Freigabeprozess an. Taxonomy ruft dann den veröffentlichten Adoptionsstrom auf, ohne eine der beiden Historientabellen zu löschen oder neu zu baselinen; vor Fortsetzung des Starts muss Version `2` aufgezeichnet sein.

Hibernate `ddl-auto=update`, manuelle Ad-hoc-DDL, Flyway `repair` oder das Löschen der Migrationshistorie dürfen diesen Ablauf nicht ersetzen. Taxonomy wählt niemals automatisch eine doppelte Zeile aus und kürzt keinen zu langen Wert. Das Upstream-Issue #78 ist durch Release 0.1.9 erledigt.

## Verifikation

`JgitStorageHibernateIntegrationTest` prüft im von Spring verwalteten HSQLDB-Persistence-Context die Registrierung aller öffentlichen Core-Entities, Persistenz von Commits und Refs über Schließen und erneutes Öffnen, abfragbare Reflogs, Isolation logischer Repository-Namen und das begrenzte Löschen eines einzelnen Repositorys.

`JgitStorageSchemaMigrationConfigTest` deckt frische und gemeinsam genutzte HSQLDB-Schemata, die Historienetablierung für exakte unversionierte Core-Tabellen, die veröffentlichten Adoptionsmigrationen V1/V2, den expliziten Upgrade-Pfad einer vorhandenen 0.1.8-Adoption, Erhalt von BLOB- und Reflogdaten, Duplikat- und Überlängenverweigerung, partielle Schemata und Idempotenz ab.

`JgitStoragePostgresMigrationIT` wiederholt die reale alte Taxonomy-Übernahme auf PostgreSQL, verlangt die Adoptionshistorie `0`, `1` und `2` und ist ein eigener Job in der Datenbank-Kompatibilitätsmatrix.

Alle diese Tests sind normale Maven-/JUnit-/Failsafe-Tests. GitHub Actions darf Maven-Aufrufe auswählen oder parallelisieren, aber weder eine abweichende Testimplementierung noch eigene Pass/Fail-Regeln besitzen.

Der vollständige Projekt-Gate bleibt:

```bash
mvn verify -DexcludedGroups="real-llm"
```
