# PostgreSQL-Einrichtungshandbuch

Diese Anleitung beschreibt, wie Sie den Taxonomy Architecture Analyzer mit
**PostgreSQL** als Datenbank-Backend betreiben.

---

## Voraussetzungen

- PostgreSQL 14 oder höher (einschließlich cloudbasierter Instanzen wie Amazon RDS,
  Azure Database for PostgreSQL oder Google Cloud SQL)
- Docker (für den Schnellstart unten) **oder** eine vorhandene PostgreSQL-Instanz
- Java 17+ (zum Erstellen aus dem Quellcode)

---

## Schnellstart mit Docker Compose

Der schnellste Weg, PostgreSQL auszuprobieren:

```bash
docker compose -f docker-compose-postgres.yml up
# Open http://localhost:8080
```

Dieser Befehl startet einen PostgreSQL 16 Alpine Container zusammen mit der
Taxonomy-Anwendung. Die Daten werden in einem Docker-Volume (`postgres-data`) persistiert.

So entfernen Sie die Container und löschen die Daten:

```bash
docker compose -f docker-compose-postgres.yml down -v
```

---

## Umgebungsvariablen

Aktivieren Sie PostgreSQL über das Spring-Profil `postgres`:

| Variable | Erforderlich | Standardwert | Beschreibung |
|---|---|---|---|
| `SPRING_PROFILES_ACTIVE` | **Ja** | `hsqldb` | Auf `postgres` setzen, um das PostgreSQL-Profil zu aktivieren |
| `TAXONOMY_DATASOURCE_URL` | Nein | `jdbc:postgresql://localhost:5432/taxonomy` | JDBC-URL — Host/Port/Datenbank nach Bedarf anpassen |
| `SPRING_DATASOURCE_USERNAME` | Nein | `taxonomy` | PostgreSQL-Anmeldename |
| `SPRING_DATASOURCE_PASSWORD` | Nein | `taxonomy` | PostgreSQL-Passwort |
| `TAXONOMY_DDL_AUTO` | Nein | `create` | Schema-Strategie: `create` (bei jedem Start neu erstellen), `update` (inkrementell), `validate` (nur lesen) |

### Beispiel: Verbindung zu einem vorhandenen PostgreSQL-Server

```bash
export SPRING_PROFILES_ACTIVE=postgres
export TAXONOMY_DATASOURCE_URL="jdbc:postgresql://myserver.example.com:5432/taxonomy"
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

## Zeichenkodierung

PostgreSQL verwendet standardmäßig UTF-8, sodass alle Zeichenkettenfelder
(einschließlich mehrsprachiger Taxonomie-Beschreibungen auf Englisch und Deutsch)
ohne spezielle Spaltentyp-Annotationen korrekt gespeichert werden.

Hibernates `@Nationalized`-Annotation hat auf PostgreSQL keine Auswirkung — die
standardmäßigen `varchar`-/`text`-Spalten unterstützen bereits den gesamten
Unicode-Bereich.

---

## HikariCP-Verbindungspool

Das `postgres`-Profil konfiguriert HikariCP (den Standard-Verbindungspool von Spring Boot):

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

## Fehlerbehebung

### Verbindung abgelehnt

**Symptom:** `Connection to localhost:5432 refused.`

**Ursache:** PostgreSQL läuft nicht oder lauscht nicht auf dem erwarteten Host/Port.

**Lösung:** Überprüfen Sie, ob PostgreSQL läuft und erreichbar ist:
```bash
pg_isready -h localhost -p 5432
```
Wenn Sie Docker Compose verwenden, stellen Sie sicher, dass der `db`-Dienst gesund ist,
bevor die Anwendung startet (die bereitgestellte `docker-compose-postgres.yml` handhabt
dies automatisch).

### Authentifizierung fehlgeschlagen

**Symptom:** `FATAL: password authentication failed for user "taxonomy"`

**Ursache:** Der Benutzername oder das Passwort stimmt nicht mit der PostgreSQL-Konfiguration überein.

**Lösung:** Überprüfen Sie, ob `SPRING_DATASOURCE_USERNAME` und `SPRING_DATASOURCE_PASSWORD`
mit den in PostgreSQL konfigurierten Anmeldedaten übereinstimmen. Das Docker-Compose-Beispiel
verwendet `taxonomy` / `taxonomy`.

### Datenbank existiert nicht

**Symptom:** `FATAL: database "taxonomy" does not exist`

**Ursache:** Die Zieldatenbank wurde nicht erstellt.

**Lösung:** Erstellen Sie die Datenbank manuell:
```sql
CREATE DATABASE taxonomy OWNER taxonomy;
```
Das Docker-Compose-Beispiel erstellt die Datenbank automatisch über die
Umgebungsvariable `POSTGRES_DB`.

---

## Ausführen der PostgreSQL-Integrationstests

```bash
# Build the application JAR first
mvn package -DskipTests

# Run PostgreSQL tests (requires Docker)
mvn verify -pl taxonomy-app -DexcludedGroups=real-llm -Dit.test="*Postgres*IT"
```

Diese Tests starten über Testcontainers einen PostgreSQL-Container und überprüfen
den gesamten Anwendungsstack.
