# Oracle-Datenbank-Einrichtungshandbuch

Diese Anleitung beschreibt, wie Sie den Taxonomy Architecture Analyzer mit
**Oracle Database** als Datenbank-Backend betreiben.

---

## Voraussetzungen

- Oracle Database 23c Free oder Oracle Database 19c oder höher (einschließlich
  Oracle Cloud Autonomous Database)
- Docker (für den Schnellstart unten) **oder** eine vorhandene Oracle-Instanz
- Java 17+ (zum Erstellen aus dem Quellcode)

---

## Schnellstart mit Docker Compose

Der schnellste Weg, Oracle auszuprobieren:

```bash
docker compose -f docker-compose-oracle.yml up
# Open http://localhost:8080
```

Dieser Befehl startet einen Oracle Database 23c Free Container zusammen mit der
Taxonomy-Anwendung. Die Daten werden in einem Docker-Volume (`oracle-data`) persistiert.

> **Hinweis:** Der Oracle-Container kann beim ersten Start 1–2 Minuten benötigen,
> während die Datenbank initialisiert wird. Das Image
> `gvenzl/oracle-free:23-slim-faststart` ist im Vergleich zu den vollständigen
> Oracle-Images für einen schnellen Start optimiert.

So entfernen Sie die Container und löschen die Daten:

```bash
docker compose -f docker-compose-oracle.yml down -v
```

---

## Umgebungsvariablen

Aktivieren Sie Oracle über das Spring-Profil `oracle`:

| Variable | Erforderlich | Standardwert | Beschreibung |
|---|---|---|---|
| `SPRING_PROFILES_ACTIVE` | **Ja** | `hsqldb` | Auf `oracle` setzen, um das Oracle-Profil zu aktivieren |
| `TAXONOMY_DATASOURCE_URL` | Nein | `jdbc:oracle:thin:@localhost:1521/taxonomy` | JDBC-URL — Host/Port/Service nach Bedarf anpassen |
| `SPRING_DATASOURCE_USERNAME` | Nein | `taxonomy` | Oracle-Anmeldename |
| `SPRING_DATASOURCE_PASSWORD` | Nein | `taxonomy` | Oracle-Passwort |
| `TAXONOMY_DDL_AUTO` | Nein | `create` | Schema-Strategie: `create` (bei jedem Start neu erstellen), `update` (inkrementell), `validate` (nur lesen) |

### Beispiel: Verbindung zu einem vorhandenen Oracle-Server

```bash
export SPRING_PROFILES_ACTIVE=oracle
export TAXONOMY_DATASOURCE_URL="jdbc:oracle:thin:@myserver.example.com:1521/ORCLPDB1"
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

Oracle Database 23c verwendet standardmäßig **AL32UTF8** als Zeichensatz, der den
gesamten Unicode-Bereich unterstützt. Alle Zeichenkettenfelder (einschließlich
mehrsprachiger Taxonomie-Beschreibungen auf Englisch und Deutsch) werden korrekt
gespeichert.

Hibernates `@Nationalized`-Annotation wird auf Oracle auf `NVARCHAR2`-/`NCLOB`-Spalten
abgebildet, wodurch die korrekte Unicode-Speicherung unabhängig vom
Datenbank-Zeichensatz gewährleistet ist.

Große Textfelder (`descriptionEn`, `descriptionDe`, `reference`) verwenden
`@Lob` + `@Nationalized`, was auf Oracle `NCLOB` erzeugt.

---

## HikariCP-Verbindungspool

Das `oracle`-Profil konfiguriert HikariCP (den Standard-Verbindungspool von Spring Boot):

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

**Symptom:** `IO Error: The Network Adapter could not establish the connection`

**Ursache:** Oracle läuft nicht oder lauscht nicht auf dem erwarteten Host/Port.

**Lösung:** Überprüfen Sie, ob Oracle läuft und der Listener aktiv ist:
```bash
# Using Docker
docker compose -f docker-compose-oracle.yml ps

# Using tnsping (if available)
tnsping localhost:1521/taxonomy
```
Wenn Sie Docker Compose verwenden, stellen Sie sicher, dass der `db`-Dienst gesund ist,
bevor die Anwendung startet (die bereitgestellte `docker-compose-oracle.yml` handhabt
dies automatisch).

### ORA-01017: Ungültiger Benutzername/Passwort

**Symptom:** `ORA-01017: invalid username/password; logon denied`

**Ursache:** Der Benutzername oder das Passwort stimmt nicht mit der Oracle-Konfiguration überein.

**Lösung:** Überprüfen Sie, ob `SPRING_DATASOURCE_USERNAME` und `SPRING_DATASOURCE_PASSWORD`
mit den in Oracle konfigurierten Anmeldedaten übereinstimmen. Das Docker-Compose-Beispiel
verwendet `taxonomy` / `taxonomy`.

### Dienstname vs. SID

**Symptom:** `ORA-12514: TNS:listener does not currently know of service requested`

**Ursache:** Die JDBC-URL verwendet den falschen Dienstnamen oder die falsche SID.

**Lösung:** Stellen Sie sicher, dass die URL den korrekten Dienstnamen verwendet. Das
Docker-Image `gvenzl/oracle-free` erstellt eine Pluggable Database mit dem Dienstnamen,
der durch `ORACLE_DATABASE` angegeben wird. In der Standardkonfiguration ist dies `taxonomy`:
```
jdbc:oracle:thin:@localhost:1521/taxonomy
```

Für Oracle-Instanzen, die eine SID anstelle eines Dienstnamens verwenden:
```
jdbc:oracle:thin:@localhost:1521:ORCL
```

### Reservierte Wörter in Oracle

Oracle reserviert bestimmte SQL-Schlüsselwörter (z. B. `LEVEL`, `COMMENT`, `USER`), die
nicht als Spaltennamen ohne Anführungszeichen verwendet werden können. Die Anwendung bildet
kollidierende Java-Feldnamen über `@Column(name = "...")`-Annotationen auf sichere
Spaltennamen ab — beispielsweise wird `TaxonomyNode.level` auf die Spalte `node_level`
abgebildet.

Wenn Sie neue Entity-Felder hinzufügen, prüfen Sie die
[reservierten Wörter von Oracle](https://docs.oracle.com/en/database/oracle/oracle-database/23/sqlrf/Oracle-SQL-Reserved-Words.html),
um sicherzustellen, dass der Spaltenname nicht reserviert ist.

### Langsamer Container-Start

**Symptom:** Der Oracle-Container benötigt viel Zeit zum Starten.

**Ursache:** Oracle Database erfordert eine erhebliche Initialisierungszeit.

**Lösung:** Das Image `gvenzl/oracle-free:23-slim-faststart` ist für einen schnellen Start
optimiert. Der Docker-Compose-Healthcheck hat 20 Wiederholungen (bis zu ~200 Sekunden),
um dies zu berücksichtigen. Falls der Start dennoch fehlschlägt, erhöhen Sie den
`retries`-Wert in `docker-compose-oracle.yml`.

---

## Ausführen der Oracle-Integrationstests

```bash
# Build the application JAR first
mvn package -DskipTests

# Run Oracle tests (requires Docker)
mvn verify -pl taxonomy-app -DexcludedGroups=real-llm -Dit.test="*Oracle*IT"
```

Diese Tests starten über Testcontainers einen Oracle-Container und überprüfen
den gesamten Anwendungsstack.
