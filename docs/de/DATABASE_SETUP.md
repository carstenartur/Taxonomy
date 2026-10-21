# Datenbank-Einrichtungshandbuch

Diese Anleitung erläutert, wie Sie den Taxonomy Architecture Analyzer mit verschiedenen Datenbank-Backends betreiben können. Standardmäßig verwendet die Anwendung eine eingebettete **HSQLDB**-Datenbank, die keine Einrichtung erfordert. Für den produktiven Betrieb mit externer Datenbank ist PostgreSQL mit den freigegebenen Flyway-Migrationen von JGit Core und Taxonomy vorgesehen. SQL Server und Oracle sind Kompatibilitäts-/Evaluierungsprofile; ihre vollständigen Migrations-, Upgrade- und Restore-Verträge sind nicht für Produktion abgenommen. JDBC-Treiber und Hibernate-Dialekt allein reichen nicht aus.

---

## Inhaltsverzeichnis

- [Übersicht](#übersicht)
- [Optionaler Datenbankassistent](#optionaler-datenbankassistent)
- [PostgreSQL-Pod in Rancher oder Kubernetes](#postgresql-pod-in-rancher-oder-kubernetes)
- [HSQLDB (Standard)](#hsqldb-standard)
- [PostgreSQL](#postgresql)
- [Microsoft SQL Server (MSSQL)](#microsoft-sql-server-mssql)
- [Oracle Database](#oracle-database)
- [Neue Datenbank hinzufügen](#neue-datenbank-hinzufügen)
- [Gemeinsame Konfiguration](#gemeinsame-konfiguration)
- [Wechsel von HSQLDB zu PostgreSQL](#wechsel-von-hsqldb-zu-postgresql)
- [Weiterführende Dokumentation](#weiterführende-dokumentation)

---

## Übersicht

Die Anwendung stellt Datenbank-Backends über **Spring Profiles** bereit. Jedes Datenbankprofil konfiguriert den JDBC-Treiber, den Dialekt, den Verbindungspool sowie datenbankspezifische Spaltentyp-Zuordnungen. Die Unterstützung für Betrieb und Migration unterscheidet sich:

| Profil | Datenbank | Aktivierung | Docker-Compose-Datei | Unterstützungsgrenze |
|---|---|---|---|---|
| `hsqldb` *(Standard)* | HSQLDB (In-Process) | Keine Konfiguration erforderlich | — | In-Memory für Entwicklung/Tests; dateibasierter kontrollierter Basisbetrieb |
| `postgres` | PostgreSQL 14+ | `SPRING_PROFILES_ACTIVE=postgres` | `docker-compose-postgres.yml` | Dauerhafte externe Datenbank mit freigegebenen Migrationen |
| `mssql` | SQL Server 2019+ | `SPRING_PROFILES_ACTIVE=mssql` | `docker-compose-mssql.yml` | Kompatibilität/Evaluierung; kein vollständig abgenommener Migrationspfad |
| `oracle` | Oracle 19c+ / 23c Free | `SPRING_PROFILES_ACTIVE=oracle` | `docker-compose-oracle.yml` | Kompatibilität/Evaluierung; kein vollständig abgenommener Migrationspfad |

Alle Datenbankprofile verwenden dasselbe Entity-Modell. Die Hibernate-Annotationen `@Nationalized` und `@Lob` gewährleisten korrekte Unicode- und Langtext-Verarbeitung über alle Datenbanken hinweg.

---

## Optionaler Datenbankassistent

Beim ersten geeigneten Administratorbesuch mit eingebetteter HSQLDB bietet der
Begrüßungsdialog die Datenbankeinrichtung an. Sie können ihn schließen, Escape
drücken oder mit der aktuellen Datenbank fortfahren. Dabei bleiben aktive
Konfiguration und Anwendungsdaten erhalten. `PreferencesService` speichert die
Entscheidung serverseitig als `setup.database.dismissed` für die Installation;
sie gilt auch in einer neuen Browsersitzung. Bei In-Memory-HSQLDB gilt sie nur,
solange diese Datenbank besteht. Ein Zurücksetzen oder Neuerstellen der Daten
entfernt auch die gespeicherte Entscheidung. Administratoren können den
Assistenten unter **Einstellungen > Datenbank einrichten** erneut öffnen.

1. Wählen Sie **PostgreSQL**, **SQL Server** oder **Oracle** und geben Sie den
   erreichbaren Datenbankhost, Port, Datenbanknamen (Oracle: Dienstname),
   Benutzernamen und das Passwort ein. Datenbankserver und Konto müssen bereits
   vorhanden sein.
2. Lassen Sie **TLS mit Zertifikatsprüfung** aktiviert. Für PostgreSQL geben Sie
   bei Bedarf den absoluten Pfad zu einer vertrauenswürdigen CA-Datei an, die
   bereits im **Taxonomy-Server/Pod** eingebunden ist, beispielsweise
   `/etc/taxonomy-db-ca/ca.crt`. Dies ist ein Serverpfad, kein Datei-Upload und kein
   Pfad auf dem Rechner Ihres Browsers. SQL Server und Oracle verwenden das
   bereits eingerichtete Zertifikatsvertrauen der Anwendungs-JVM; der Assistent
   installiert keine Zertifikate und erstellt keinen Truststore.
3. Prüfen Sie die Verbindung. Der Test öffnet eine separate, zeitlich begrenzte
   JDBC-Verbindung mit Read-only-Hinweis und führt weder DDL noch Schreibzugriffe
   auf Anwendungsdaten aus. Ein Erfolg bestätigt nur Erreichbarkeit und
   Datenbankanmeldung. Schemakompatibilität, Schreib-/Migrationsrechte, Upgrade
   und Wiederherstellung bleiben ungeprüft. SQL Server und Oracle bleiben auch
   nach einem erfolgreichen Test Evaluierungsprofile.
4. Kopieren Sie die Konfiguration oder laden Sie `taxonomy-database.properties`
   und `taxonomy-database-values.yaml` herunter. Passwort-Properties fehlen
   vollständig; Kommentare erläutern die Bereitstellung über das vorhandene
   Deployment-Secret oder geschützte Spring-Konfiguration. Das Testpasswort wird
   nicht als Einstellung gespeichert. Das gewählte Datenbankprofil ersetzt das
   alte; andere aktive beziehungsweise Standardprofile bleiben erhalten.
   `production` bleibt erhalten, wenn es vorhanden ist, wird aber nicht automatisch
   ergänzt. Die Helm-Ausgabe enthält zusätzlich `kubernetes`. Prüfen Sie die
   Profilliste für den vorgesehenen Betrieb und Anmeldemodus.

Meldet der Konfigurationsexport einen **Konflikt bei den Schema-Einstellungen**,
prüfen Sie vorhandene Werte für `spring.jpa.properties.hibernate.hbm2ddl.auto`,
`spring.jpa.properties.jakarta.persistence.schema-generation.database.action`
und die ältere Einstellung `spring.jpa.properties.javax.persistence.schema-generation.database.action`.
Prüfen Sie außerdem `spring.jpa.hibernate.ddl-auto` in `SPRING_APPLICATION_JSON`,
JVM-Systemeigenschaften oder Startargumenten: Diese Quellen können Vorrang vor
den erzeugten Umgebungsvariablen haben. Entfernen Sie die widersprüchlichen
Überschreibungen im Deployment oder setzen Sie sie auf `validate`. Wiederholen
Sie den Export, sobald die korrigierte Konfiguration wirksam ist. Der normale
HSQLDB-Standardwert in einer Application-Properties-Datei verhindert den Export
nicht. Der Verbindungstest bleibt auch bei einem Konflikt möglich. Die Prüfung
erfasst die aktuelle Serverkonfiguration; kontrollieren Sie vor dem Neustart auch
später ergänzte Einstellungen des Zieldeployments.

Der Betreiber übernimmt die Konfiguration und startet beziehungsweise deployt
Taxonomy anschließend bewusst neu. Der Assistent wechselt keine laufende
Datenquelle, erstellt keine Pods oder Schemata und startet die Anwendung nicht
neu. Er **überträgt keine vorhandenen HSQLDB-Daten**. Bewahren Sie die
Quelldatenbank und eine geprüfte Sicherung auf, wenn deren Datensätze erhalten
bleiben müssen; siehe [Wechsel von HSQLDB zu PostgreSQL](#wechsel-von-hsqldb-zu-postgresql).

TLS-Referenzen: [pgJDBC](https://jdbc.postgresql.org/documentation/ssl/),
[Microsoft JDBC](https://learn.microsoft.com/en-us/sql/connect/jdbc/connecting-with-ssl-encryption)
und [Oracle JDBC](https://docs.oracle.com/en/database/oracle/oracle-database/23/jjdbc/client-side-security.html).

## PostgreSQL-Pod in Rancher oder Kubernetes

Ein einfacher Ausgangspunkt ist ein separates PostgreSQL-**StatefulSet** mit
eigenem **PersistentVolumeClaim (PVC)** und **ClusterIP-Service** im selben
Namespace wie Taxonomy. Das Taxonomy-Chart und der Browserassistent erstellen die
Datenbank nicht. Die vorhandene [Anleitung für Datenbank-Pods](https://github.com/carstenartur/Taxonomy/blob/main/deploy/helm/taxonomy/DATABASE_PODS.md)
und das [PostgreSQL-Values-Beispiel](https://github.com/carstenartur/Taxonomy/blob/main/deploy/helm/taxonomy/values-postgres-service.yaml)
zeigen die Verbindung zu einer separat verwalteten Datenbank.

1. **Workload und Speicher anlegen.** Öffnen Sie in Rancher die Workload-Ansicht
   des Clusters, erstellen Sie ein StatefulSet im vorgesehenen Namespace und
   binden Sie einen Volume Claim mit geeigneter StorageClass ein. Legen Sie eine
   für Ihren Release und Betrieb freigegebene PostgreSQL-Imageversion fest. Beim
   offiziellen `postgres`-Image verwendet Version 17 `/var/lib/postgresql/data`;
   Version 18 bindet das Volume unter `/var/lib/postgresql` ein und setzt
   standardmäßig `PGDATA=/var/lib/postgresql/18/docker`. Stimmen Sie den PVC-Mount
   auf Image und Konfiguration ab. Siehe [Rancher-Workloads](https://ranchermanager.docs.rancher.com/how-to-guides/new-user-guides/kubernetes-resources-setup/workloads-and-pods/deploy-workloads),
   [Kubernetes-StatefulSets](https://kubernetes.io/docs/concepts/workloads/controllers/statefulset/)
   und die [offizielle Image-Anleitung](https://hub.docker.com/_/postgres).
2. **Datenbank und Anwendungskonto erstellen.** Verwenden Sie die Datenbank
   `taxonomy` und ein eigenes eingeschränktes Konto, etwa `taxonomy_app`.
   Administrator-/Bootstrap-Zugangsdaten bleiben getrennt. `POSTGRES_USER` des
   offiziellen Images erstellt einen **Superuser**; der Anwendungsname in dieser
   Variable ergibt kein eingeschränktes Anwendungskonto. Richten Sie die Rolle
   separat mit den für Startmigrationen benötigten Eigentums-/DDL-Rechten auf
   Taxonomy-Datenbank und Schema ein, ohne clusterweite Superuser-Rechte.
   `POSTGRES_DB=taxonomy` und Initialisierungsskripte wirken auf ein leeres
   Datenverzeichnis; Änderungen konfigurieren kein vorhandenes PVC nachträglich
   um. Siehe [Initialisierungsvertrag des Images](https://hub.docker.com/_/postgres).
3. **Internen Service und TLS einrichten.** Ein Service `postgres` auf TCP 5432
   ist im selben Namespace unter `postgres` erreichbar. Für einen anderen
   Namespace eignet sich etwa `postgres.database.svc.cluster.local`, angepasst
   an die DNS-Domain des Clusters. Verwenden Sie den schreibfähigen Primary-Endpunkt.
   `localhost` wäre der Taxonomy-Pod; NodePort, öffentlicher LoadBalancer oder
   HTTP-Ingress sind dafür nicht erforderlich. Aktivieren Sie TLS auf PostgreSQL,
   verwenden Sie ein Serverzertifikat für den gewählten Service-DNS-Namen und
   mounten Sie die öffentliche CA-Datei in Taxonomy. Die TLS-Auswahl im Assistenten
   konfiguriert den Datenbankserver nicht. Siehe [Service-DNS](https://kubernetes.io/docs/concepts/services-networking/dns-pod-service/)
   und [pgJDBC-Zertifikatsprüfung](https://jdbc.postgresql.org/documentation/ssl/).
4. **Taxonomy konfigurieren.** Hinterlegen Sie im Taxonomy-Namespace im durch
   `existingSecret` referenzierten Secret die Schlüssel `SPRING_DATASOURCE_URL`,
   `SPRING_DATASOURCE_USERNAME` und `SPRING_DATASOURCE_PASSWORD`. Die URL kann
   beispielsweise `jdbc:postgresql://postgres:5432/taxonomy?sslmode=verify-full&sslrootcert=/etc/taxonomy-db-ca/ca.crt`
   lauten. Wählen Sie `database.type=postgres`; lassen Sie `database.url` und
   `database.username` leer, um diese Secret-Schlüssel zu verwenden, oder
   überschreiben Sie die jeweiligen Werte bewusst in den Values. Erhalten Sie den Anmeldemodus
   samt benötigter Secret-Schlüssel, etwa `ADMIN_PASSWORD` für lokale Anmeldung
   oder `KEYCLOAK_CLIENT_SECRET` für Keycloak. Die Datenbankauswahl darf keine
   Anmelde- oder Deploymentprofile entfernen.
5. **Beide Netzwerkrichtungen freigeben.** Anwendbare NetworkPolicies müssen
   Taxonomy-Egress zur Datenbank und Datenbank-Ingress von Taxonomy auf TCP 5432
   erlauben. Auch der Cluster-DNS-Dienst muss aus Taxonomy erreichbar sein.
   Verwenden Sie die tatsächlichen Namespace- und Pod-Labels und erhalten Sie
   weitere nötige Regeln und Mounts beim Kombinieren von Values-Dateien. Siehe
   [Kubernetes-NetworkPolicies](https://kubernetes.io/docs/concepts/services-networking/network-policies/)
   und die [Chart-Beispiele](https://github.com/carstenartur/Taxonomy/blob/main/deploy/helm/taxonomy/DATABASE_PODS.md#netzwerk-und-dauerhafte-daten).
6. **Prüfen, deployen und Persistenz abnehmen.** Führen Sie den Verbindungstest
   aus dem laufenden Taxonomy-Server aus und übernehmen Sie anschließend bewusst
   die geprüfte Deployment-Konfiguration mit `TAXONOMY_DDL_AUTO=validate`. Beim
   nächsten Anwendungsstart initialisieren die freigegebenen Flyway-Strecken von
   JGit Core und Taxonomy ein frisches PostgreSQL-Schema. Der Verbindungstest
   erledigt dies nicht. Prüfen Sie den tatsächlichen Start, die Anmeldung und die
   Anwendungsfunktionen. Erhalten Sie das Datenbank-PVC unabhängig vom Taxonomy-PVC
   für `/app/data` und richten Sie geprüfte Sicherung/Wiederherstellung ein.
   Ein einzelner Datenbank-Pod bietet noch keine Hochverfügbarkeit.

---

## HSQLDB (Standard)

Die Anwendung wird mit einer eingebetteten HSQLDB-Datenbank ausgeliefert. Es ist keine Installation oder ein externer Datenbankserver erforderlich.

**Wesentliche Eigenschaften:**
- Läuft **In-Process** (gleiche JVM, kein Netzwerk-Hop)
- Verwendet einen begrenzten HikariCP-Pool (`minimum-idle=1`, `maximum-pool-size=4`)
- Nutzt standardmäßig eine In-Memory-URL für Entwicklung und Tests ohne Einrichtung
- Unterstützt für persistente Installationen eine Datei-URL; der Pool hält eine Verbindung offen, damit `shutdown=true` HSQLDB nicht während des Spring-Starts beendet
- Verwendet einen vorhandenen persistierten Katalog nach einem Neustart weiter; `TAXONOMY_INIT_RELOAD_EXISTING=true` löst bewusst ein destruktives Neuladen aus

Der In-Memory-Standard eignet sich für Entwicklung und Tests. Die Produktions-Docker-Konfiguration verwendet dateibasierte HSQLDB- und Lucene-Speicherung.

Vor einem Upgrade von 1.3 alle schreibenden Anwendungsinstanzen stoppen und eine
vollständige [Sicherung und Wiederherstellung](CONTAINER_IMAGE.md#5-persistenz-und-backup)
prüfen. Mit aktiviertem HSQLDB-Profil und aktivierter Schemamigration bereitet der
Start vorhandene Repository- und Portfolio-Daten vor, bevor Hibernate das übrige
Schema ergänzt. Repository-, Workspace- und Branch-Zuordnungen werden aus
gespeicherten Herkunftsdaten abgeleitet; bereits vollständige Zuordnungen bleiben
erhalten. Uneindeutige Repositories, kollidierende Portfolio-Schlüssel im selben Mandanten
oder widersprüchliche Elternbezüge stoppen das Upgrade. Vor der Behebung des
gemeldeten Datenkonflikts und einem erneuten Versuch die Sicherung des gestoppten
Systems wiederherstellen. Ein Upgrade-Fehler darf nicht mit `create` oder durch
Abschalten der Schemamigration umgangen werden.

Historische zentrale Portfolios bleiben über die zentrale Ansicht lesbar
(API-Clients senden `X-Taxonomy-Workspace-Id` ausdrücklich leer). Ihre
GET-/HEAD-Leserouten behalten die genaue Repository- und Branch-Zuordnung.
Neue Analysen und Änderungen benötigen weiterhin einen isolierten Workspace;
seine Auswahl verschiebt die alten zentralen Datensätze nicht dorthin.

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
Dies ist ein **flüchtiges Evaluierungsbeispiel**: Die Compose-Datei setzt
`TAXONOMY_DDL_AUTO=create`; ein Neustart der Anwendung erstellt
Hibernate-verwaltete Tabellen erneut und kann trotz persistentem Volume Daten
löschen. Für dauerhafte Produktionsdaten sind `production,postgres` mit
`TAXONOMY_DDL_AUTO=validate`, gesicherte PostgreSQL-Daten und die
freigegebenen Migrationen zu verwenden.

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
| `TAXONOMY_DDL_AUTO` | Nein | Basis `create`; Produktion `update`; Kubernetes `validate` | Mit freigegebenen PostgreSQL-Migrationen `validate` verwenden; niemals `create` auf dauerhaften Daten. |

### Beispiel: Verbindung zu einem vorhandenen Server

```bash
export SPRING_PROFILES_ACTIVE=production,postgres
export TAXONOMY_DATASOURCE_URL="jdbc:postgresql://myserver.example.com:5432/taxonomy"
export SPRING_DATASOURCE_USERNAME=taxonomy_user
export SPRING_DATASOURCE_PASSWORD="$DATABASE_PASSWORD"
export TAXONOMY_ADMIN_PASSWORD="$TAXONOMY_ADMIN_PASSWORD"
export TAXONOMY_DDL_AUTO=validate

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
./mvnw -B verify -Pdatabase-postgres
```

---

## Microsoft SQL Server (MSSQL)

### Voraussetzungen

- SQL Server 2019 oder höher (einschließlich Azure SQL Database)
- Docker (für den Schnellstart) **oder** eine vorhandene SQL-Server-Instanz

### Schnellstart mit Docker Compose (nur Kompatibilitätstest)

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
| `TAXONOMY_DDL_AUTO` | Nein | Basis `create`; Produktion `update`; Kubernetes `validate` | Nur Kompatibilitätstest; die freigegebene PostgreSQL-Flyway-Strecke gilt hier nicht. Niemals `create` auf dauerhaften Daten. |

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
| **TLS-Fehler** | `Could not establish secure connection` | Vertrauenswürdige CA und Serverzertifikat mit passendem Hostnamen konfigurieren; Zertifikatsprüfung für Produktion nicht abschalten. |
| **Anmelde-Timeout** | `Login failed` oder Timeout | Das Profil setzt 60 Sekunden Wiederholungszeit; erhöhen Sie diese bei Bedarf |
| **Veraltetes ntext** | `ntext`-Spalten im Schema | Stellen Sie sicher, dass Hibernate 7.x + `SQLServerDialect` verwendet werden |

### Integrationstests ausführen

```bash
./mvnw -B verify -Pdatabase-mssql
```

---

## Oracle Database

### Voraussetzungen

- Oracle Database 23c Free oder Oracle Database 19c+ (einschließlich Oracle Cloud Autonomous Database)
- Docker (für den Schnellstart) **oder** eine vorhandene Oracle-Instanz

### Schnellstart mit Docker Compose (nur Kompatibilitätstest)

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
| `TAXONOMY_DDL_AUTO` | Nein | Basis `create`; Produktion `update`; Kubernetes `validate` | Nur Kompatibilitätstest; die freigegebene PostgreSQL-Flyway-Strecke gilt hier nicht. Niemals `create` auf dauerhaften Daten. |

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
./mvnw -B verify -Pdatabase-oracle
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

Das Entity-Modell allein qualifiziert kein neues Backend: Auch Migrationen der Storage-Bibliothek und der Anwendung sowie Persistenz, Upgrade und Restore müssen für die Datenbank geprüft sein.

---

## Gemeinsame Konfiguration

### Schema-Strategie (`TAXONOMY_DDL_AUTO`)

| Wert | Verhalten | Empfohlen für |
|---|---|---|
| `create` | Erstellt Hibernate-verwaltete Tabellen bei jedem Start neu und löscht den bisherigen Inhalt | Nur flüchtige Entwicklung/Tests, niemals bestehende Daten |
| `update` | Hibernate versucht additive Änderungen; kein versioniertes Migrationsverfahren | Dateibasierter HSQLDB-Compose-Basisbetrieb, nicht PostgreSQL mit verwalteten Migrationen |
| `validate` | Überprüft, ob das Schema mit dem Entity-Modell übereinstimmt; schlägt bei Abweichungen fehl | Produktion mit verwalteten Migrationen |

> **PostgreSQL:** Freigegebene Flyway-Migrationen von JGit Core und Taxonomy laufen bereits beim Start. Verwenden Sie nach geprüfter Sicherung `TAXONOMY_DDL_AUTO=validate`. Das generische `production`-Profil verwendet weiterhin `update` für die dateibasierte HSQLDB-Basis; PostgreSQL benötigt die ausdrückliche Überschreibung. SQL Server und Oracle deaktivieren diesen Flyway-Pfad bis zur Abnahme.

### HikariCP-Verbindungspool

Alle externen Datenbankprofile (PostgreSQL, MSSQL, Oracle) konfigurieren HikariCP mit denselben Standardwerten:

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

> **Hinweis:** Das HSQLDB-Profil verwendet einen kleinen begrenzten HikariCP-Pool (`minimum-idle=1`, `maximum-pool-size=4`). Die Leerlaufverbindung ist für einen stabilen Lebenszyklus dateibasierter Datenbanken mit `shutdown=true` erforderlich.

---

## Wechsel von HSQLDB zu PostgreSQL

Das Profil `postgres` verbindet Taxonomy mit einer **anderen Datenbank**.
Benutzer, Workspaces, Relationen, Hypothesen, Git-Packs/Refs und andere
Datensätze werden dabei nicht aus HSQLDB kopiert. Das Projekt liefert kein
automatisches datenbankübergreifendes Transferverfahren. Sichern und testen
Sie die vollständige Quelldatenbank samt zugehörigem Index und Konfiguration.
Qualifizieren Sie eine Übertragung auf einer isolierten Kopie und prüfen Sie
Zeilen und Git-Historie, Anmeldung, Workspaces und repräsentative Suche vor
einem kontrollierten Wechsel. Verwenden Sie `TAXONOMY_DDL_AUTO=create` niemals
auf einer Datenbank mit zu erhaltenden Daten.

Für eine **neue, leere** PostgreSQL-Installation geben Sie Datenbank- und
lokale Anmeldedaten sowie `SPRING_PROFILES_ACTIVE=production,postgres` an, setzen
`TAXONOMY_DDL_AUTO=validate` und lassen die freigegebenen Flyway-Strecken
von JGit Core und Taxonomy das Schema anlegen. Vor weiteren Upgrades ist eine
Sicherung nötig; Helm verwendet sicherheitshalber `Recreate`, während
Rolling-Kompatibilität release-spezifisch nachzuweisen ist. Siehe
[JGit-Speicher](JGIT_STORAGE_HIBERNATE.md) und
[Helm-Upgrade-Vertrag](https://github.com/carstenartur/Taxonomy/blob/main/deploy/helm/taxonomy/README.md#upgrade-safety-and-database-migrations).

SQL-Server- und Oracle-Compose-Dateien sind Evaluierungsumgebungen, keine
produktiven Migrationsziele und kein Ersatz für einen geprüften Datentransfer.

---

## Weiterführende Dokumentation

- [Konfigurationsreferenz](CONFIGURATION_REFERENCE.md) — Alle Umgebungsvariablen
- [Bereitstellungshandbuch](DEPLOYMENT_GUIDE.md) — Docker- und Cloud-Bereitstellung
- [Architektur](ARCHITECTURE.md) — Datenbankarchitektur und Entity-Modell

## Testarchitektur und Kompatibilitätsnachweise

`./mvnw verify` ist der begrenzte deterministische Standard-Lebenszyklus und startet
keine externe Datenbank. Die zentralen HSQLDB-Container-Tests sowie die
PostgreSQL-/MSSQL-/Oracle-Matrix sind normale Failsafe-/Testcontainers-Tests und
werden mit `-DskipITs=false` aktiviert.

Der Workflow `Database Compatibility` führt bei relevanten Pull Requests die
PostgreSQL-Diagnose- und Selenium-Tests aus, wöchentlich alle sechs Szenarien und
bei manueller Ausführung die ausgewählte Datenbankfamilie. Die lokalen Befehle
stehen in [`docs/dev/06-testing-by-change-type.md`](../dev/06-testing-by-change-type.md).
