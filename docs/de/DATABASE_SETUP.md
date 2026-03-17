# Datenbank-Einrichtungshandbuch

Diese Anleitung erläutert, wie Sie den Taxonomy Architecture Analyzer mit verschiedenen Datenbank-Backends betreiben können. Standardmäßig verwendet die Anwendung eine eingebettete **HSQLDB**-Datenbank, die keine Einrichtung erfordert. Für Produktionsumgebungen können Sie auf PostgreSQL, Microsoft SQL Server, Oracle oder andere JDBC-kompatible Datenbanken umstellen.

---

## Inhaltsverzeichnis

- [Übersicht](#übersicht)
- [HSQLDB (Standard)](#hsqldb-standard)
- [PostgreSQL](#postgresql)
- [Microsoft SQL Server (MSSQL)](#microsoft-sql-server-mssql)
- [Oracle Database](#oracle-database)
- [Neue Datenbank hinzufügen](#neue-datenbank-hinzufügen)
- [Gemeinsame Konfiguration](#gemeinsame-konfiguration)
- [Migrationspfad: HSQLDB → Produktionsdatenbank](#migrationspfad-hsqldb--produktionsdatenbank)
- [Weiterführende Dokumentation](#weiterführende-dokumentation)

---

## Übersicht

Die Anwendung unterstützt mehrere Datenbank-Backends über **Spring Profiles**. Jedes Datenbankprofil konfiguriert den JDBC-Treiber, den Dialekt, den Verbindungspool sowie datenbankspezifische Spaltentyp-Zuordnungen.

| Profil | Datenbank | Aktivierung | Docker-Compose-Datei |
|---|---|---|---|
| `hsqldb` *(Standard)* | HSQLDB (In-Process) | Keine Konfiguration erforderlich | — |
| `postgres` | PostgreSQL 14+ | `SPRING_PROFILES_ACTIVE=postgres` | `docker-compose-postgres.yml` |
| `mssql` | SQL Server 2019+ | `SPRING_PROFILES_ACTIVE=mssql` | `docker-compose-mssql.yml` |
| `oracle` | Oracle 19c+ / 23c Free | `SPRING_PROFILES_ACTIVE=oracle` | `docker-compose-oracle.yml` |

Alle Datenbankprofile verwenden dasselbe Entity-Modell. Die Hibernate-Annotationen `@Nationalized` und `@Lob` gewährleisten korrekte Unicode- und Langtext-Verarbeitung über alle Datenbanken hinweg.

---

## HSQLDB (Standard)

Die Anwendung wird mit einer eingebetteten HSQLDB-Datenbank ausgeliefert. Es ist keine Installation oder ein externer Datenbankserver erforderlich.

**Wesentliche Eigenschaften:**
- Läuft **In-Process** (gleiche JVM, kein Netzwerk-Hop)
- Verwendet `SimpleDriverDataSource` anstelle von HikariCP, um den Overhead eines Verbindungspools zu vermeiden
- Alle Daten werden beim Start aus der mitgelieferten Excel-Arbeitsmappe geladen
- Daten werden zwischen Neustarts **nicht persistiert** (In-Memory-Modus)

Dies ist ideal für Entwicklung, Tests und Demo-Bereitstellungen.

---

## PostgreSQL

### Voraussetzungen

- PostgreSQL 14 oder höher (einschließlich Amazon RDS, Azure Database for PostgreSQL, Google Cloud SQL)
- Docker (für den Schnellstart) **oder** eine vorhandene PostgreSQL-Instanz

### Schnellstart mit Docker Compose

```bash
docker compose -f docker-compose-postgres.yml up
# Open http://localhost:8080
```

Dies startet einen PostgreSQL-16-Alpine-Container zusammen mit der Taxonomy-Anwendung. Die Daten werden in einem Docker-Volume (`postgres-data`) persistiert.

```bash
# Tear down and remove data
docker compose -f docker-compose-postgres.yml down -v
```

### Umgebungsvariablen

| Variable | Erforderlich | Standard | Beschreibung |
|---|---|---|---|
| `SPRING_PROFILES_ACTIVE` | **Ja** | `hsqldb` | Auf `postgres` setzen |
| `TAXONOMY_DATASOURCE_URL` | Nein | `jdbc:postgresql://localhost:5432/taxonomy` | JDBC-URL |
| `SPRING_DATASOURCE_USERNAME` | Nein | `taxonomy` | PostgreSQL-Anmeldename |
| `SPRING_DATASOURCE_PASSWORD` | Nein | `taxonomy` | PostgreSQL-Passwort |
| `TAXONOMY_DDL_AUTO` | Nein | `create` | Schema-Strategie (siehe [Gemeinsame Konfiguration](#gemeinsame-konfiguration)) |

### Beispiel: Verbindung zu einem vorhandenen Server

```bash
export SPRING_PROFILES_ACTIVE=postgres
export TAXONOMY_DATASOURCE_URL="jdbc:postgresql://myserver.example.com:5432/taxonomy"
export SPRING_DATASOURCE_USERNAME=taxonomy_user
export SPRING_DATASOURCE_PASSWORD=SecurePassword123!
export TAXONOMY_DDL_AUTO=update

java -jar taxonomy-app/target/taxonomy-app-*.jar
```

### Zeichenkodierung

PostgreSQL verwendet standardmäßig UTF-8, sodass alle Zeichenkettenfelder ohne spezielle Annotationen korrekt gespeichert werden. Die Hibernate-Annotation `@Nationalized` hat bei PostgreSQL keine Auswirkung.

### Fehlerbehebung

| Problem | Symptom | Lösung |
|---|---|---|
| **Verbindung abgelehnt** | `Connection to localhost:5432 refused` | Überprüfen Sie, ob PostgreSQL läuft: `pg_isready -h localhost -p 5432` |
| **Authentifizierung fehlgeschlagen** | `FATAL: password authentication failed` | Prüfen Sie `SPRING_DATASOURCE_USERNAME` und `SPRING_DATASOURCE_PASSWORD` |
| **Datenbank fehlt** | `FATAL: database "taxonomy" does not exist` | Erstellen Sie diese: `CREATE DATABASE taxonomy OWNER taxonomy;` |

### Integrationstests ausführen

```bash
mvn package -DskipTests
mvn verify -pl taxonomy-app -DexcludedGroups=real-llm -Dit.test="*Postgres*IT"
```

---

## Microsoft SQL Server (MSSQL)

### Voraussetzungen

- SQL Server 2019 oder höher (einschließlich Azure SQL Database)
- Docker (für den Schnellstart) **oder** eine vorhandene SQL-Server-Instanz

### Schnellstart mit Docker Compose

```bash
docker compose -f docker-compose-mssql.yml up
# Open http://localhost:8080
```

Dies startet einen SQL-Server-2022-Developer-Edition-Container zusammen mit der Taxonomy-Anwendung. Die Daten werden in einem Docker-Volume (`mssql-data`) persistiert.

```bash
# Tear down and remove data
docker compose -f docker-compose-mssql.yml down -v
```

### Umgebungsvariablen

| Variable | Erforderlich | Standard | Beschreibung |
|---|---|---|---|
| `SPRING_PROFILES_ACTIVE` | **Ja** | `hsqldb` | Auf `mssql` setzen |
| `TAXONOMY_DATASOURCE_URL` | Nein | `jdbc:sqlserver://localhost:1433;databaseName=taxonomy;encrypt=false;trustServerCertificate=true` | JDBC-URL |
| `SPRING_DATASOURCE_USERNAME` | Nein | `sa` | SQL-Server-Anmeldename |
| `SPRING_DATASOURCE_PASSWORD` | **Ja** | *(leer)* | SQL-Server-Passwort (muss Komplexitätsanforderungen erfüllen) |
| `TAXONOMY_DDL_AUTO` | Nein | `create` | Schema-Strategie (siehe [Gemeinsame Konfiguration](#gemeinsame-konfiguration)) |

### Beispiel: Verbindung zu einem vorhandenen Server

```bash
export SPRING_PROFILES_ACTIVE=mssql
export TAXONOMY_DATASOURCE_URL="jdbc:sqlserver://myserver.example.com:1433;databaseName=taxonomy;encrypt=true;trustServerCertificate=false"
export SPRING_DATASOURCE_USERNAME=taxonomy_user
export SPRING_DATASOURCE_PASSWORD=SecurePassword123!
export TAXONOMY_DDL_AUTO=update

java -jar taxonomy-app/target/taxonomy-app-*.jar
```

### Zeichenkodierung — Warum `nvarchar`?

Alle Zeichenkettenfelder verwenden die Hibernate-Annotation `@Nationalized`, die auf SQL Server auf `nvarchar`-Spalten (Unicode) abgebildet wird. Große Textfelder verwenden `@Lob` + `@Nationalized`, was `nvarchar(max)` ergibt.

### SQL-Server-Passwortanforderungen

Das Passwort muss mindestens 8 Zeichen lang sein und Zeichen aus mindestens drei der folgenden Kategorien enthalten: Großbuchstaben, Kleinbuchstaben, Ziffern, Sonderzeichen. Das Docker-Compose-Beispiel verwendet `A_Str0ng_Required_Password`.

### Fehlerbehebung

| Problem | Symptom | Lösung |
|---|---|---|
| **TLS-Fehler** | `Could not establish secure connection` | Fügen Sie `encrypt=false;trustServerCertificate=true` zur JDBC-URL hinzu |
| **Anmelde-Timeout** | `Login failed` oder Timeout | Das Profil setzt 60 Sekunden Wiederholungszeit; erhöhen Sie diese bei Bedarf |
| **Veraltetes ntext** | `ntext`-Spalten im Schema | Stellen Sie sicher, dass Hibernate 7.x + `SQLServerDialect` verwendet werden |

### Integrationstests ausführen

```bash
mvn package -DskipTests
mvn verify -pl taxonomy-app -DexcludedGroups=real-llm -Dit.test="*Mssql*IT"
```

---

## Oracle Database

### Voraussetzungen

- Oracle Database 23c Free oder Oracle Database 19c+ (einschließlich Oracle Cloud Autonomous Database)
- Docker (für den Schnellstart) **oder** eine vorhandene Oracle-Instanz

### Schnellstart mit Docker Compose

```bash
docker compose -f docker-compose-oracle.yml up
# Open http://localhost:8080
```

Dies startet einen Oracle-Database-23c-Free-Container zusammen mit der Taxonomy-Anwendung. Die Daten werden in einem Docker-Volume (`oracle-data`) persistiert.

> **Hinweis:** Der Oracle-Container benötigt beim ersten Start möglicherweise 1–2 Minuten, während die Datenbank initialisiert wird. Das Image `gvenzl/oracle-free:23-slim-faststart` ist für einen schnellen Start optimiert.

```bash
# Tear down and remove data
docker compose -f docker-compose-oracle.yml down -v
```

### Umgebungsvariablen

| Variable | Erforderlich | Standard | Beschreibung |
|---|---|---|---|
| `SPRING_PROFILES_ACTIVE` | **Ja** | `hsqldb` | Auf `oracle` setzen |
| `TAXONOMY_DATASOURCE_URL` | Nein | `jdbc:oracle:thin:@localhost:1521/taxonomy` | JDBC-URL |
| `SPRING_DATASOURCE_USERNAME` | Nein | `taxonomy` | Oracle-Anmeldename |
| `SPRING_DATASOURCE_PASSWORD` | Nein | `taxonomy` | Oracle-Passwort |
| `TAXONOMY_DDL_AUTO` | Nein | `create` | Schema-Strategie (siehe [Gemeinsame Konfiguration](#gemeinsame-konfiguration)) |

### Beispiel: Verbindung zu einem vorhandenen Server

```bash
export SPRING_PROFILES_ACTIVE=oracle
export TAXONOMY_DATASOURCE_URL="jdbc:oracle:thin:@myserver.example.com:1521/ORCLPDB1"
export SPRING_DATASOURCE_USERNAME=taxonomy_user
export SPRING_DATASOURCE_PASSWORD=SecurePassword123!
export TAXONOMY_DDL_AUTO=update

java -jar taxonomy-app/target/taxonomy-app-*.jar
```

### Zeichenkodierung

Oracle 23c verwendet standardmäßig **AL32UTF8**. Die Hibernate-Annotation `@Nationalized` bildet auf `NVARCHAR2` / `NCLOB` ab und gewährleistet korrekte Unicode-Speicherung unabhängig vom Zeichensatz der Datenbank.

### Reservierte Oracle-Wörter

Oracle reserviert bestimmte SQL-Schlüsselwörter (z. B. `LEVEL`, `COMMENT`, `USER`). Die Anwendung ordnet konfliktbehaftete Feldnamen über `@Column(name = "...")` sicheren Spaltennamen zu — beispielsweise `TaxonomyNode.level` → Spalte `node_level`.

Wenn Sie neue Entity-Felder hinzufügen, prüfen Sie die [Oracle Reserved Words](https://docs.oracle.com/en/database/oracle/oracle-database/23/sqlrf/Oracle-SQL-Reserved-Words.html).

### Fehlerbehebung

| Problem | Symptom | Lösung |
|---|---|---|
| **Verbindung abgelehnt** | `IO Error: The Network Adapter could not establish the connection` | Überprüfen Sie, ob Oracle läuft und der Listener aktiv ist |
| **Authentifizierung fehlgeschlagen** | `ORA-01017: invalid username/password` | Prüfen Sie, ob die Anmeldedaten mit der Oracle-Konfiguration übereinstimmen |
| **Dienst nicht gefunden** | `ORA-12514: listener does not currently know of service` | Stellen Sie sicher, dass die JDBC-URL den korrekten Dienstnamen verwendet (nicht die SID) |
| **Langsamer Start** | Container benötigt >2 Minuten | Das `faststart`-Image wird verwendet; erhöhen Sie bei Bedarf die Healthcheck-Wiederholungen |

### Dienstname vs. SID

Die Standard-URL verwendet einen **Dienstnamen**: `jdbc:oracle:thin:@localhost:1521/taxonomy`

Für Oracle-Instanzen, die eine **SID** verwenden, nutzen Sie: `jdbc:oracle:thin:@localhost:1521:ORCL`

### Integrationstests ausführen

```bash
mvn package -DskipTests
mvn verify -pl taxonomy-app -DexcludedGroups=real-llm -Dit.test="*Oracle*IT"
```

---

## Neue Datenbank hinzufügen

Um Unterstützung für ein neues Datenbank-Backend hinzuzufügen:

1. **Spring-Profil erstellen** — Fügen Sie eine `application-{dbname}.properties`-Datei unter `src/main/resources/` mit dem JDBC-Treiber, dem Dialekt und den Verbindungspool-Einstellungen hinzu.
2. **Docker-Compose-Datei hinzufügen** — Erstellen Sie eine `docker-compose-{dbname}.yml` mit dem Datenbank-Container und dem Anwendungsservice.
3. **Entity-Zuordnungen testen** — Führen Sie die vollständige Testsuite gegen die neue Datenbank mit Testcontainers aus, um sicherzustellen, dass `@Nationalized`, `@Lob` und benutzerdefinierte Spalten-Zuordnungen korrekt funktionieren.
4. **Reservierte Wörter prüfen** — Überprüfen Sie, ob alle `@Column(name = "...")`-Zuordnungen reservierte Wörter der Zieldatenbank vermeiden.
5. **Integrationstest hinzufügen** — Erstellen Sie eine `*{Dbname}*IT.java`-Testklasse mit dem entsprechenden Testcontainers-Setup.
6. **Diese Dokumentation aktualisieren** — Fügen Sie dieser Datei einen neuen Abschnitt mit Schnellstart, Umgebungsvariablen, Fehlerbehebung und Testanweisungen hinzu.

Das Entity-Modell der Anwendung ist dank Hibernate datenbankunabhängig. Jede JDBC-kompatible Datenbank mit einem Hibernate-Dialekt sollte mit minimaler Konfiguration funktionieren.

---

## Gemeinsame Konfiguration

### Schema-Strategie (`TAXONOMY_DDL_AUTO`)

| Wert | Verhalten | Empfohlen für |
|---|---|---|
| `create` | Löscht und erstellt alle Tabellen bei jedem Start neu | Entwicklung, Tests, Docker-Compose-Demos |
| `update` | Fügt neue Spalten/Tabellen hinzu, ohne bestehende Daten zu löschen | Staging, frühe Produktion |
| `validate` | Überprüft, ob das Schema mit dem Entity-Modell übereinstimmt; schlägt bei Abweichungen fehl | Produktion mit verwalteten Migrationen |

> **Tipp:** Für Produktionsumgebungen sollten Sie Flyway oder Liquibase für Schema-Migrationen anstelle der automatischen Hibernate-DDL-Generierung in Betracht ziehen.

### HikariCP-Verbindungspool

Alle Produktions-Datenbankprofile (PostgreSQL, MSSQL, Oracle) konfigurieren HikariCP mit denselben Standardwerten:

| Eigenschaft | Wert | Beschreibung |
|---|---|---|
| `maximum-pool-size` | 10 | Maximale gleichzeitige Verbindungen |
| `minimum-idle` | 2 | Minimale Leerlaufverbindungen |
| `connection-timeout` | 30000 ms | Maximale Wartezeit auf eine Verbindung |
| `idle-timeout` | 600000 ms | Maximale Leerlaufzeit vor dem Abbau |
| `initialization-fail-timeout` | 60000 ms | Wiederholungsfenster für die initiale Verbindung |

Überschreiben Sie diese über Umgebungsvariablen:
```bash
export SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=20
```

> **Hinweis:** Das Standard-HSQLDB-Profil verwendet **kein** HikariCP — es nutzt `SimpleDriverDataSource`, um den Verbindungspool-Overhead im Einzeln-JVM-Modus zu vermeiden.

---

## Migrationspfad: HSQLDB → Produktionsdatenbank

Um von der Standard-HSQLDB auf eine Produktionsdatenbank zu migrieren:

1. **Datenbank wählen** — Wählen Sie PostgreSQL, MSSQL oder Oracle basierend auf Ihrer Infrastruktur.
2. **Profil setzen** — `SPRING_PROFILES_ACTIVE={postgres|mssql|oracle}`
3. **Verbindung konfigurieren** — Setzen Sie `TAXONOMY_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`.
4. **Initiales Schema** — Verwenden Sie `TAXONOMY_DDL_AUTO=create` beim ersten Start, um alle Tabellen zu erstellen.
5. **Auf update umstellen** — Wechseln Sie nach der Ersteinrichtung zu `TAXONOMY_DDL_AUTO=update`, um Daten über Neustarts hinweg zu erhalten.
6. **Überprüfen** — Prüfen Sie den Health-Endpunkt (`GET /actuator/health`) und führen Sie eine Testanalyse durch.

Die Taxonomiedaten werden beim Start immer aus der mitgelieferten Excel-Arbeitsmappe geladen, sodass für die Taxonomie selbst keine Datenmigration von HSQLDB erforderlich ist. Architektur-DSL-Daten (Git-Repository) werden in der Datenbank gespeichert und müssen manuell neu erstellt oder migriert werden.

---

## Weiterführende Dokumentation

- [Konfigurationsreferenz](CONFIGURATION_REFERENCE.md) — Alle Umgebungsvariablen
- [Bereitstellungshandbuch](DEPLOYMENT_GUIDE.md) — Docker- und Cloud-Bereitstellung
- [Architektur](ARCHITECTURE.md) — Datenbankarchitektur und Entity-Modell
