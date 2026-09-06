# Server and data storage / Server und Datenhaltung

## English

Open **Administration → Health Dashboard → Server and data storage**. The section
loads only when expanded or explicitly refreshed. It uses the existing administrator
login and `GET /api/admin/system-information`; responses use `Cache-Control: no-store`.
The snapshot identifies the current application instance and measurement time, not
an aggregated cluster state. No additional monitoring server or agent is required.

### Interpret persistence conservatively

- **In memory, application process:** contents and changes are lost when this process
  stops. Re-importing the packaged catalogue is not recovery of user-created data.
- **In memory, database server process:** restarting the remote database loses data;
  restarting Taxonomy alone is not necessarily a database restart.
- **File-backed / server-managed:** the engine's storage mode is identified, but
  durability of the underlying volume, container storage, remote database, backups
  and restore procedure is **not verified**. This is not a green persistence guarantee.
- **Unknown:** do not infer persistence from a remote JDBC connection or a profile name.

Schema actions are reported independently. Hibernate `create`, `create-drop`, `drop`
and `truncate` are destructive even with file-backed or external storage. Jakarta
Persistence `database.action=create` means create-only, unlike Hibernate's
`hbm2ddl.auto=create`; Hibernate's own action parser preserves that distinction.
An enabled catalogue reload is another separate warning. This feature changes none
of these settings and never executes schema or data modifications.

### Native version and storage probes

A Hibernate `FunctionContributor`, discovered by Java ServiceLoader, registers
zero-argument, typed HQL functions. Hibernate renders the native expression for its
actual dialect. No replacement dialect, pseudo-entity or new dependency is needed.

| Database | Native version expression |
|---|---|
| HSQLDB | `database_version()` |
| PostgreSQL | `current_setting('server_version')` |
| SQL Server | `cast(serverproperty('ProductVersion') as varchar(128))` |
| Oracle | Database row's `version_full` in `product_component_version` |

For HSQLDB, `INFORMATION_SCHEMA.SYSTEM_SESSIONINFO` identifies the actual database
URI internally, so remote HSQL databases can also be distinguished as memory, file
or read-only resource storage. The URI itself is never returned. HSQL MEMORY table
types are **not** used to infer database persistence.

The API reports `DATABASE_QUERY`, `JDBC_METADATA_FALLBACK`, `JDBC_CONNECTION` or
`UNAVAILABLE` as applicable; an unsupported or failed native query is not silently
presented as a successful query. SQL statements have a two-second query timeout;
connection acquisition still follows the existing datasource's timeout. Each
request uses a separate read-only Hibernate session with manual flush. There is no
continuous polling, new background worker or persistent diagnostic cache.

CPU count means processors available to this JVM, not physical sockets. Disk capacity
and usable bytes refer only to the local filesystems used for temporary files and an
active filesystem-backed search index. Shared filesystems are deduplicated. Missing
paths are not created and are shown as unavailable, never as zero free space. These
figures do not describe a remote database server's disks. An in-memory Lucene index
is distinguished from an in-memory database: an index is a rebuildable projection.

### Existing telemetry and sessions

The existing OpenTelemetry integration provides optional traces. JVM, system,
Hibernate and connection-pool metrics keep using Micrometer/Actuator; the documented
agent configuration disables a second metrics exporter. This snapshot does not prove
that an optional telemetry exporter is attached or successfully delivering data.
See [English observability documentation](en/OBSERVABILITY.md).

There is currently no authenticated-user/session inventory in this feature.
Hibernate sessions and database connections are not logged-in people. A separate
administrator view can use Spring Security's session lifecycle registry, with clear
local-instance versus cluster scope. Browser-session existence, recent requests,
Keycloak SSO sessions and stateless Basic/Bearer calls must not be conflated. Usernames
must not become telemetry labels. No authentication/session policy is changed here.

### Verification

`SystemInformationServiceTest` covers native HSQL version/storage, file reopening,
metadata fallback, schema-action precedence, remote-connection ambiguity, diagnostic
failure isolation and filesystem deduplication. The existing diagnostics container
base exercises native version queries on HSQLDB, PostgreSQL, SQL Server and Oracle
without adding application/container starts. Existing administrator security tests
also cover administrator, non-administrator and unauthenticated access.

The authoritative acceptance command remains:

```sh
./mvnw verify -DexcludedGroups="real-llm"
```

## Deutsch

Unter **Administration → Gesundheitsübersicht → Server und Datenhaltung** stehen
Datenbankprodukt und tatsächliche Serverversion, deren Ermittlungsquelle,
Speicherart und Schema-Aktion sowie Anwendungs-/Java-/Betriebssysteminformationen,
verfügbare Prozessoren, Laufzeit und lokal nutzbarer Plattenplatz. Die vorhandene
Gesundheitsübersicht zeigt zusätzlich den Java-Heap.

**Dateibasierte oder externe Speicherung ist kein Nachweis für ein beständiges
Container-Volume oder funktionierende Backups.** Die Oberfläche warnt ausdrücklich
vor einer In-Memory-Datenbank, vor destruktiver Schema-Neuanlage und vor unbestätigter
Speicherbeständigkeit. Ein abgelegter Suchindex ist nicht mit der maßgeblichen
Datenbank gleichzusetzen. Bei HSQLDB wird die Speicherart aus der tatsächlich
angesprochenen Datenbank ermittelt, auch bei einem entfernten HSQL-Server.

Die Datenbankabfragen verwenden Hibernate-Erweiterungspunkte und feste, rein lesende
Ausdrücke. JDBC-Metadaten dienen nur als ausdrücklich gekennzeichneter Rückfall.
Passwörter, Tokens, JDBC-Verbindungszeichenfolgen, absolute Pfade und interne
Fehlermeldungen werden nicht ausgegeben. Der Zugriff bleibt auf Administratoren
beschränkt. Aktualisierung erfolgt auf Anforderung, nicht durch ständiges Polling.

OpenTelemetry-Traces und die vorhandenen Micrometer-Metriken ergänzen diese Anzeige;
sie ersetzen weder die Persistenzbewertung noch eine Benutzer-/Sitzungsliste. Eine
Anzeige gleichzeitig angemeldeter Benutzer ist noch nicht enthalten. Sie gehört
als getrennte, administrativ geschützte Sicht auf die echte Sitzungsverwaltung, nicht
als Ableitung aus Datenbankverbindungen oder Telemetrie. Siehe auch
[OpenTelemetry-Dokumentation](de/OBSERVABILITY.md).
