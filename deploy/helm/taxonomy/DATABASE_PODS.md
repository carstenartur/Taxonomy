# Datenbanken in separaten Kubernetes-Pods

Taxonomy verbindet sich per JDBC mit einer erreichbaren Datenbank. Der Datenbankserver
kann in einem anderen Pod desselben Namespaces, einem eigenen Namespace oder außerhalb
des Clusters liegen. Dieses Chart installiert **keinen** Datenbankserver und verwaltet
weder dessen Volume noch Sicherungen, Upgrades oder Lizenzen. Keine Sidecar-Datenbank
und keine gemeinsamen Datendateien zwischen Taxonomy und Datenbank.

## Datenbank auswählen, Anmeldung unabhängig konfigurieren

`database.type` ist `existing` (auch wenn nicht gesetzt), `postgres` oder `mssql`.
`existing` erhält die bisherige explizite Profilkonfiguration. Eine ausdrückliche
Auswahl ersetzt ausschließlich die bekannten Datenbankprofile `postgres`, `mssql`,
`hsqldb` und `oracle`; Deployment-, Anmelde- und weitere Profile bleiben erhalten.
Der JDBC-Treiber und Hibernate-Dialekt kommen aus dem vorhandenen Anwendungsprofil.
Mit den Chart-Defaults wird beispielsweise `postgres,kubernetes` zu `mssql,kubernetes`.
Die zusätzliche Auswahl `authentication.mode=local` ergänzt wie bisher die lokale
Benutzerverwaltung, `keycloak` die zentrale Anmeldung. Ein Datenbankwechsel aktiviert
keinen zusätzlichen Anmeldemechanismus und schaltet keine Migrationen frei.

`database.url` und `database.username` sind optionale Überschreibungen der bestehenden
Secret-Zuordnungen. Leer bedeutet: `SPRING_DATASOURCE_URL` beziehungsweise
`SPRING_DATASOURCE_USERNAME` aus `existingSecret` verwenden. Der Passwortschlüssel
`SPRING_DATASOURCE_PASSWORD` bleibt im Secret. Das Secret muss im **Namespace von
Taxonomy** vorhanden sein; eine Pod-Referenz kann kein Secret aus dem Datenbank-Namespace
lesen. Lokale Anmeldung benötigt außerdem den bisherigen `ADMIN_PASSWORD`-Schlüssel,
Keycloak stattdessen `KEYCLOAK_CLIENT_SECRET`; Monitoring nutzt separat `ADMIN_TOKEN`.
Verwende ein eigenes Datenbankkonto mit den erforderlichen, nicht pauschalen Adminrechten.

Sichtbare JDBC-Adressen müssen zum ausgewählten Profil passen. URLs mit eingebetteten
Zugangsdaten werden abgewiesen. Der Renderer kann Inhalte vorhandener Secrets nicht
prüfen; die richtige dort hinterlegte Adresse und ihre Erreichbarkeit sind gesondert
zu prüfen. Ein erfolgreicher Helm-Render ist kein Datenbank-Verbindungstest.

## Service statt Pod-IP

Für eine Datenbank in einem separaten Pod ist `localhost` falsch: Das wäre der
Taxonomy-Pod selbst. Verwende einen stabilen Datenbank-Service, für PostgreSQL den
Schreib-/Primary-Service, nicht einen beliebigen Replica-Service.

| Ziel | Beispiel für den Host |
| --- | --- |
| Gleicher Namespace | `postgres` beziehungsweise `mssql` |
| Namespace `database` | `postgres.database.svc.cluster.local` beziehungsweise `mssql.database.svc.cluster.local` |

`cluster.local` ist der übliche, aber konfigurierbare Cluster-DNS-Domainname. Passe
DNS-Namen, Ports und Namespace an den Cluster an. JDBC verwendet keinen HTTP-Ingress;
für interne Datenbanken ist kein öffentliches NodePort-/LoadBalancer-Angebot nötig.

## PostgreSQL

[values-postgres-service.yaml](values-postgres-service.yaml) zeigt eine Verbindung
zum Service `postgres` im Namespace `database`, Port 5432, Datenbank `taxonomy`.
`sslmode=verify-full` prüft Zertifikat und Hostnamen. Lege die vertrauenswürdige CA
als `ca.crt` in der ConfigMap `taxonomy-database-ca` im Taxonomy-Namespace ab; das
Beispiel mountet sie schreibgeschützt. Das Serverzertifikat muss zum verwendeten
Service-DNS-Namen passen. Die ConfigMap enthält nur die öffentliche CA, keinen
privaten Schlüssel. Ein TLS-Fehler soll behoben, nicht durch `sslmode=disable`
oder eine deaktivierte Hostnamenprüfung verdeckt werden.

## Microsoft SQL Server

[values-mssql-service.yaml](values-mssql-service.yaml) wählt das vorhandene
`mssql`-Profil und den Service `mssql` im Namespace `database`, Port 1433.
`databaseName=taxonomy;encrypt=true;trustServerCertificate=false` hält die
Zertifikatsprüfung aktiv. Richte die CA-Vertrauenskette der Java-Laufzeit und das
Serverzertifikat passend ein; bei einer internen CA ist ein betreiberseitig
bereitgestellter Truststore erforderlich. Das Beispiel schaltet die Prüfung
**nicht** über `trustServerCertificate=true` ab.

**Reifegradgrenze:** `application-mssql.properties` kennzeichnet MSSQL derzeit als
Kompatibilitätsprofil, nicht als produktionszertifiziert. Dort bleibt
`spring.flyway.enabled=false`; die PostgreSQL-/JGit-Core-Flyway-Strecke darf
nicht für MSSQL aktiviert werden. Die portablen JDBC-Vertragsmigrationen über
`taxonomy.schema-migration.enabled` bleiben standardmäßig aktiv. Die
Chart-Voreinstellung `TAXONOMY_DDL_AUTO=validate`
bleibt erhalten. Eine leere MSSQL-Datenbank ist damit nicht automatisch vollständig
initialisiert. Vor produktivem Einsatz müssen die MSSQL-spezifische Schemaanlage,
PackStore-/Suchkompatibilität, Versionswechsel und Wiederherstellung für den
betreffenden Release nachgewiesen sein. Die neue Formularauswahl ersetzt diese
Abnahme nicht. Auch bestehende Daten werden durch eine Typauswahl nicht migriert.

## Netzwerk und dauerhafte Daten

Die beiden Beispieldateien erlauben Taxonomy ausgehend ausschließlich den jeweiligen
Datenbank-Port zum passenden Namespace **und** Pod-Label, zusätzlich zur bestehenden
DNS-Regel. Beide Selektoren stehen im selben `to`-Eintrag. Übernimm die tatsächlichen
Labels der Datenbank-Pods; Labels des Services allein genügen nicht.

Die Beispiele deaktivieren die pauschale Freigabe aller Ziele im gleichen Namespace.
Falls Datenbank-Pods durch Ingress-NetworkPolicies isoliert sind, muss ihre Seite
zusätzlich TCP 5432 beziehungsweise 1433 aus dem Taxonomy-Namespace und von den
Taxonomy-Pods erlauben. Beide Richtungen müssen erlaubt sein. Die Durchsetzung hängt
vom eingesetzten Netzwerk-Plugin ab. Auch DNS muss aus dem Taxonomy-Pod funktionieren.

Beim Kombinieren von Values-Dateien ersetzen Listen wie `networkPolicy.egress`,
`extraVolumes` und `extraVolumeMounts` die vorherige Liste. Erhalte deshalb bewusst
weitere benötigte Regeln/Mounts, etwa für Keycloak, KI-Endpunkte oder Zertifikate.
Keine globale Egress-Freigabe als Ersatz für eine fehlende Datenbankregel setzen.

Betreibe die Datenbank mit eigenem persistentem Volume und geeignetem StatefulSet
oder Datenbank-Operator sowie getesteter Sicherung/Wiederherstellung. Das Taxonomy-PVC
für `/app/data` ist **nicht** der Datenspeicher des externen Datenbankservers. Ein
einzelner Datenbank-Pod ist noch keine Hochverfügbarkeitslösung. Ein Update oder eine
Deinstallation von Taxonomy darf die separat verwaltete Datenbank nicht löschen.

## Prüfung

Die Maven-eingebundene `verify-setup.sh` rendert beide Service-Beispiele und prüft
Profile, unveränderte Migrationseinstellungen, Secret-Zuordnung sowie ungültige
Kombinationen. Diese Tests beweisen keine reale Clusterverbindung oder MSSQL-Abnahme.
Führe den expliziten Konfigurations-/Verbindungscheck im tatsächlichen Taxonomy-Pod
mit denselben Secrets, CA-Dateien und Netzwerkregeln aus. Die Schema-/Restore-Abnahme
ist davon getrennt; nicht geprüfte Eigenschaften bleiben `NOT_CHECKED`.

Grundlagen: [Kubernetes Service-DNS](https://kubernetes.io/docs/concepts/services-networking/dns-pod-service/),
[NetworkPolicies](https://kubernetes.io/docs/concepts/services-networking/network-policies/),
[PostgreSQL JDBC TLS](https://jdbc.postgresql.org/documentation/ssl/),
[Microsoft JDBC-Verbindungsadressen](https://learn.microsoft.com/en-us/sql/connect/jdbc/building-the-connection-url).
