# MSSQL-Einrichtungshandbuch

Diese Anleitung beschreibt, wie Sie den Taxonomy Architecture Analyzer mit
**Microsoft SQL Server** als Datenbank-Backend betreiben.

---

## Voraussetzungen

- SQL Server 2019 oder höher (einschließlich Azure SQL Database)
- Docker (für den Schnellstart unten) **oder** eine vorhandene SQL-Server-Instanz
- Java 17+ (zum Erstellen aus dem Quellcode)

---

## Schnellstart mit Docker Compose

Der schnellste Weg, MSSQL auszuprobieren:

```bash
docker compose -f docker-compose-mssql.yml up
# Open http://localhost:8080
```

Dieser Befehl startet einen SQL Server 2022 Developer Edition Container zusammen mit
der Taxonomy-Anwendung. Die Daten werden in einem Docker-Volume (`mssql-data`) persistiert.

So entfernen Sie die Container und löschen die Daten:

```bash
docker compose -f docker-compose-mssql.yml down -v
```

---

## Umgebungsvariablen

Aktivieren Sie MSSQL über das Spring-Profil `mssql`:

| Variable | Erforderlich | Standardwert | Beschreibung |
|---|---|---|---|
| `SPRING_PROFILES_ACTIVE` | **Ja** | `hsqldb` | Auf `mssql` setzen, um das MSSQL-Profil zu aktivieren |
| `TAXONOMY_DATASOURCE_URL` | Nein | `jdbc:sqlserver://localhost:1433;databaseName=taxonomy;encrypt=false;trustServerCertificate=true` | JDBC-URL — Host/Port/Datenbank nach Bedarf anpassen |
| `SPRING_DATASOURCE_USERNAME` | Nein | `sa` | SQL-Server-Anmeldename |
| `SPRING_DATASOURCE_PASSWORD` | **Ja** | *(leer)* | SQL-Server-Passwort (muss die Komplexitätsanforderungen erfüllen) |
| `TAXONOMY_DDL_AUTO` | Nein | `create` | Schema-Strategie: `create` (bei jedem Start neu erstellen), `update` (inkrementell), `validate` (nur lesen) |

### Beispiel: Verbindung zu einem vorhandenen SQL Server

```bash
export SPRING_PROFILES_ACTIVE=mssql
export TAXONOMY_DATASOURCE_URL="jdbc:sqlserver://myserver.example.com:1433;databaseName=taxonomy;encrypt=true;trustServerCertificate=false"
export SPRING_DATASOURCE_USERNAME=taxonomy_user
export SPRING_DATASOURCE_PASSWORD=SecurePassword123!
export TAXONOMY_DDL_AUTO=update

java -jar taxonomy-app/target/taxonomy-app-*.jar
```

---

## Schema-Verwaltung

| `TAXONOMY_DDL_AUTO` | Verhalten | Empfohlen für |
|---|---|---|
| `create` | Löscht und erstellt alle Tabellen bei jedem Start neu | Entwicklung, Tests, Docker-Compose-Demos |
| `update` | Fügt neue Spalten/Tabellen hinzu, ohne bestehende Daten zu löschen | Staging, frühe Produktion |
| `validate` | Überprüft, ob das Schema mit dem Entity-Modell übereinstimmt; schlägt bei Abweichungen fehl | Produktion mit verwalteten Migrationen |

> **Tipp:** Für den Produktionseinsatz sollten Sie Flyway oder Liquibase für
> Schema-Migrationen anstelle der automatischen Hibernate-DDL-Generierung verwenden.

---

## Zeichenkodierung — Warum `nvarchar`?

Alle Zeichenkettenfelder verwenden Hibernates `@Nationalized`-Annotation, die auf
SQL Server auf `nvarchar`-Spalten (Unicode) abgebildet wird. Dies gewährleistet die
korrekte Speicherung mehrsprachiger Taxonomie-Beschreibungen (Englisch und Deutsch).

Große Textfelder (`descriptionEn`, `descriptionDe`, `reference`) verwenden
`@Lob` + `@Nationalized`, was auf SQL Server `nvarchar(max)` erzeugt.

---

## HikariCP-Verbindungspool

Das `mssql`-Profil konfiguriert HikariCP (den Standard-Verbindungspool von Spring Boot):

| Eigenschaft | Wert | Beschreibung |
|---|---|---|
| `maximum-pool-size` | 10 | Maximale gleichzeitige Verbindungen |
| `minimum-idle` | 2 | Minimale Anzahl an Leerlaufverbindungen |
| `connection-timeout` | 30000 ms | Maximale Wartezeit auf eine Verbindung aus dem Pool |
| `idle-timeout` | 600000 ms | Maximale Leerlaufzeit, bevor eine Verbindung geschlossen wird |
| `initialization-fail-timeout` | 60000 ms | Dauer der Verbindungswiederholung beim Start |

Sie können diese Werte über Umgebungsvariablen überschreiben, z. B.:
```bash
export SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=20
```

---

## SQL-Server-Passwortanforderungen

SQL Server erzwingt Passwortkomplexität. Das Passwort muss:
- Mindestens 8 Zeichen lang sein
- Zeichen aus mindestens drei der folgenden Kategorien enthalten: Großbuchstaben, Kleinbuchstaben, Ziffern, Sonderzeichen

Das Docker-Compose-Beispiel verwendet `A_Str0ng_Required_Password`.

---

## Fehlerbehebung

### Prelogin-/TLS-Verbindungsfehler

**Symptom:** `The driver could not establish a secure connection to SQL Server by using Secure Sockets Layer (SSL) encryption.`

**Ursache:** Der MSSQL-JDBC-Treiber (v12+) verwendet standardmäßig `encrypt=true`, aber der
SQL-Server-Container nutzt ein selbstsigniertes Zertifikat.

**Lösung:** Fügen Sie der JDBC-URL Folgendes hinzu:
```
encrypt=false;trustServerCertificate=true
```
Das `mssql`-Profil enthält dies bereits in seiner Standard-URL.

### Anmelde-Zeitüberschreitung

**Symptom:** `Login failed` oder Verbindungszeitüberschreitung beim Start.

**Ursache:** SQL Server ist möglicherweise noch nicht vollständig bereit, wenn die Anwendung
versucht, eine Verbindung herzustellen.

**Lösung:** Das `mssql`-Profil setzt `initialization-fail-timeout=60000` (60 Sekunden),
wodurch HikariCP den Verbindungsaufbau bis zu 60 Sekunden lang wiederholt.

### `ntext` vs. `nvarchar(max)`

**Symptom:** Veraltete `ntext`-Spalten erscheinen im Schema.

**Ursache:** Ältere Hibernate-Versionen erzeugen möglicherweise `ntext` für `@Lob` + `@Nationalized`.

**Lösung:** Diese Anwendung verwendet Hibernate 7.x mit dem `SQLServerDialect`, der
korrekt `nvarchar(max)` generiert.

---

## Ausführen der MSSQL-Integrationstests

```bash
# Build the application JAR first
mvn package -DskipTests

# Run MSSQL tests (requires Docker)
mvn verify -pl taxonomy-app -DexcludedGroups=real-llm -Dit.test="*Mssql*IT"
```

Diese Tests starten über Testcontainers einen SQL-Server-Container und überprüfen
den gesamten Anwendungsstack.
