# Datenbanken in separaten Kubernetes-Pods

Taxonomy verbindet sich per JDBC mit einer erreichbaren Datenbank. Der Datenbankserver
kann in einem anderen Pod desselben Namespaces, einem eigenen Namespace oder außerhalb
des Clusters liegen. Dieses Chart installiert **keinen** Datenbankserver und verwaltet
weder dessen Volume noch Sicherungen, Upgrades oder Lizenzen. Keine Sidecar-Datenbank
und keine gemeinsamen Datendateien zwischen Taxonomy und Datenbank.

Die vollständige Anleitung zum Erhalten von Einstellungen und Daten vor dem ersten
Neustart ist in [Deutsch](../../../docs/de/DATABASE_SETUP.md#persistent-installation)
und [English](../../../docs/en/DATABASE_SETUP.md#persistent-installation) verfügbar
und aus dem bestehenden Datenbankassistenten erreichbar. Dieser Begleiter beschreibt
die konkreten Chart-Values für getrennte Datenbank-Pods.

## Datenbank auswählen, Anmeldung unabhängig konfigurieren

`database.type` ist `existing` (Standard), `postgres`, `mssql` oder `oracle`.
`existing` erhält die bisherige explizite Profilkonfiguration. Eine ausdrückliche
Auswahl ersetzt ausschließlich die bekannten Datenbankprofile `postgres`, `mssql`,
`hsqldb`, `hsqldb-file` und `oracle`; Deployment-, Anmelde- und weitere Profile bleiben erhalten.
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

Der Service muss zu einer logisch zusammengehörigen, beschreibbaren Datenbank führen;
er darf Verbindungen nicht auf unabhängig initialisierte Datenbanken verteilen.
Bei Hochverfügbarkeit den vorgesehenen Primary-/PDB-Service des Datenbank-Operators
verwenden. Der Assistent prüft logische Namen und Zugänge, keine Replikation oder Topologie.

| Ziel | Beispiel für den Host |
| --- | --- |
| Gleicher Namespace | `postgres`, `mssql` beziehungsweise `oracle` |
| Namespace `database` | `postgres.database.svc.cluster.local`, `mssql.database.svc.cluster.local` beziehungsweise `oracle.database.svc.cluster.local` |

`cluster.local` ist der übliche, aber konfigurierbare Cluster-DNS-Domainname. Passe
DNS-Namen, Ports und Namespace an den Cluster an. JDBC verwendet keinen HTTP-Ingress;
für interne Datenbanken ist kein öffentliches NodePort-/LoadBalancer-Angebot nötig.
Ein ClusterIP-Service beschränkt den Zugriff aber nicht allein auf Taxonomy.
Passende, vom Netzwerk-Plugin durchgesetzte NetworkPolicies begrenzen die erlaubten
Pods; TLS schützt zusätzlich den Transport und die geprüfte Serveridentität.

## PostgreSQL

[values-postgres-service.yaml](values-postgres-service.yaml) zeigt eine Verbindung
zum Service `postgres` im Namespace `database`, Port 5432, Datenbank `taxonomy`.
`sslmode=verify-full` prüft Zertifikat und Hostnamen. Lege die vertrauenswürdige CA
als `ca.crt` in der ConfigMap `taxonomy-database-ca` im Taxonomy-Namespace ab; das
Beispiel mountet sie schreibgeschützt. Das Serverzertifikat muss zum verwendeten
Service-DNS-Namen passen. Die ConfigMap enthält nur die öffentliche CA, keinen
privaten Schlüssel. Das Beispiel setzt serverseitig eingerichtetes TLS voraus.
Für bewusst unverschlüsselten Betrieb muss die gewählte Betriebsrichtlinie diesen
zulassen und die Verbindung ausdrücklich entsprechend konfiguriert werden. Ein
TLS-Fehler löst keinen automatischen Wechsel auf eine unverschlüsselte Verbindung aus.

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

## Oracle

[values-oracle-service.yaml](values-oracle-service.yaml) wählt das vorhandene
`oracle`-Profil und den Service `oracle` im Namespace `database`. Das Beispiel
verwendet den PDB-Service `FREEPDB1`, den lokalen Anwendungsbenutzer `TAXONOMY_APP`
und einen **vorher eingerichteten TCPS-Listener auf Port 2484**. Ein unverändertes
`gvenzl/oracle-free`-Image stellt normalerweise TCP 1521 bereit; diese Values
aktivieren dort kein TCPS. Listener, Service-Port, JDBC-Port und NetworkPolicy
müssen übereinstimmen. Der Assistent verwendet für Oracle einen PDB-Servicenamen,
keine PostgreSQL-artige Datenbankanlage und keine SID einer neuen Instanz.

Die TCPS-Adresse enthält `(SSL_SERVER_DN_MATCH=YES)`. Stellen Sie die CA-Vertrauenskette
im Java-Truststore und ein zum Service-DNS-Namen passendes Serverzertifikat bereit.
Der öffentliche Truststore kann über die vorhandenen `extraVolumes`/
`extraVolumeMounts` eingebunden werden; notwendige geheime Werte gehören in Secrets.
Für ausdrücklich gewählten unverschlüsselten Betrieb ist beispielsweise
`jdbc:oracle:thin:@oracle.database.svc.cluster.local:1521/FREEPDB1` möglich; dabei
muss auch die Egress-Regel den tatsächlichen Port verwenden. Das einzelne `@` nach
`thin:` ist Oracle-Syntax; Zugangsdaten wie `user/password@...` sind nicht zulässig.

Der optionale Assistent kann in der vorhandenen PDB einen lokalen Benutzer mit
eigenem Schema, einem vorhandenen Anwendungstablespace und endlichem Kontingent
vorbereiten. Er erstellt keine Oracle-Instanz/PDB und keine Taxonomy-Tabellen.
Auch Oracle bleibt ein **Kompatibilitäts-/Evaluierungsprofil** mit deaktivierter
Flyway-Strecke und normalem `TAXONOMY_DDL_AUTO=validate`. Eine erfolgreiche
Anmeldung an ein leeres Schema bedeutet keine startfähige Anwendung. Lesen Sie
die [Schema-Initialisierung](../../../docs/de/DATABASE_SETUP.md#schema-initialization)
([English](../../../docs/en/DATABASE_SETUP.md#schema-initialization)), bevor Sie
die neue Verbindung übernehmen. Dieselbe Grenze gilt für SQL Server.

## Netzwerk und dauerhafte Daten

Die drei Beispieldateien erlauben Taxonomy ausgehend ausschließlich den jeweiligen
Datenbank-Port zum passenden Namespace **und** Pod-Label, zusätzlich zur bestehenden
DNS-Regel. Beide Selektoren stehen im selben `to`-Eintrag. Übernimm die tatsächlichen
Labels der Datenbank-Pods; Labels des Services allein genügen nicht.

Die Beispiele deaktivieren die pauschale Freigabe aller Ziele im gleichen Namespace.
Falls Datenbank-Pods durch Ingress-NetworkPolicies isoliert sind, muss ihre Seite
zusätzlich TCP 5432, 1433 beziehungsweise den eingerichteten Oracle-Listener-Port
aus dem Taxonomy-Namespace und von den
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

### Dauerhafte Installation vorbereiten

| Image/Modus | Zu erhaltender Mount |
|---|---|
| Offizielles PostgreSQL 18 | `/var/lib/postgresql`; Standard-Datenpfad `/var/lib/postgresql/18/docker` |
| Offizielles PostgreSQL bis 17 mit Standard-`PGDATA` | `/var/lib/postgresql/data` |
| Microsoft SQL Server Linux | `/var/opt/mssql` oder ausdrücklich konfigurierte Daten-/Log-/Secret-Verzeichnisse |
| `gvenzl/oracle-free:23-slim-faststart` | `/opt/oracle/oradata`; Variablen und Berechtigungen dieses konkreten Images beachten |
| Eingebettete Datei-HSQLDB | Taxonomy-PVC unter `/app/data` **und** JDBC-Datei-URL dorthin; eine Instanz mit `Recreate` |

In Rancher beim Workload die tatsächlich verwendeten Volumes/Mounts und
Umgebungs-/Secret-Referenzen prüfen. Unter **PersistentVolumeClaims** Claim,
StorageClass und Bindung kontrollieren; im PV außerdem die Reclaim-Policy.
PVC-Größe und Schreibrechte müssen zum Datenbankprozess passen. Ein StatefulSet
oder vorhandenes PVC allein beweist keine korrekte Datenablage.

`persistence.enabled=true` im Taxonomy-Chart schützt `/app/data`. Es konvertiert
keine In-Memory-HSQLDB, überträgt keine Daten und ersetzt kein externes Datenbank-PVC.
Anwendungseinstellungen einschließlich ihrer Git-Historie liegen in der aktiven
Datenbank. Weitere Dateien unter `/app/data`, etwa aufbewahrte Quellen oder
dateibasierte Suchindizes, benötigen ebenfalls Schutz; nicht jeder fehlende Index
wird beim Start vollständig neu aufgebaut. Secrets und ConfigMaps/Values behalten
die Deployment-Konfiguration unabhängig von diesen Daten.

**Vor dem ersten Neustart einer flüchtigen Installation:** Quelle und Speicher
feststellen, Konfiguration geschützt sichern und einen tatsächlich wiederherstellbaren
Zustand herstellen. Eine wertvolle In-Memory-Installation muss weiterlaufen, bis
ein geprüfter Übertragungsweg besteht. Für den Wechsel von einem befüllten
Containerverzeichnis zu einem PVC zuerst konsistent sichern und im Ziel
wiederherstellen; das Mounten eines leeren PVC kopiert die bisherigen Dateien nicht.
Die [bilinguale Anleitung oben](../../../docs/de/DATABASE_SETUP.md#persistent-installation)
trennt frische Tests, zu erhaltende In-Memory-Daten und bestehende dauerhafte Datenbanken.

Das Chart erstellt sein Anwendungs-PVC ohne `existingClaim`-Option. Sein Erhalt
nach Helm-Deinstallation oder PVC-/Namespace-Löschung ist nicht zugesichert;
`Delete` als PV-Reclaim-Policy kann den Speicher entfernen. Ein PVC ist kein
Backup. Die vorhandenen [Rancher-](RANCHER.md) und
[Container-Wiederherstellungsanleitungen](../../../docs/de/CONTAINER_IMAGE.md#5-persistenz-und-backup)
beschreiben die getrennten Verantwortlichkeiten. SQL Server Developer ist keine
Produktionslizenz; Oracle Free hat eigene Grenzen/Nutzungsbedingungen.

## Prüfung

Die Maven-eingebundene `verify-setup.sh` rendert alle drei Service-Beispiele und prüft
Profile, unveränderte Migrationseinstellungen, Secret-Zuordnung sowie ungültige
Kombinationen. `verify-setup-security.sh` prüft unter anderem Oracle-URLs und die
Ablehnung eingebetteter Zugangsdaten mit festen, geheimnisfreien Fehlermeldungen,
auch ohne JSON-Schema. Diese Tests beweisen keine reale Clusterverbindung oder
MSSQL-/Oracle-Abnahme.
Führe den expliziten Konfigurations-/Verbindungscheck im tatsächlichen Taxonomy-Pod
mit denselben Secrets, CA-Dateien und Netzwerkregeln aus. Die Schema-/Restore-Abnahme
ist davon getrennt; nicht geprüfte Eigenschaften bleiben `NOT_CHECKED`.

Grundlagen: [Kubernetes Service-DNS](https://kubernetes.io/docs/concepts/services-networking/dns-pod-service/),
[NetworkPolicies](https://kubernetes.io/docs/concepts/services-networking/network-policies/),
[Persistent Volumes](https://kubernetes.io/docs/concepts/storage/persistent-volumes/),
[PostgreSQL-Image](https://hub.docker.com/_/postgres),
[SQL-Server-Speicher](https://learn.microsoft.com/en-us/sql/linux/containers/configure),
[gvenzl-Oracle-Image](https://github.com/gvenzl/oci-oracle-free),
[PostgreSQL JDBC TLS](https://jdbc.postgresql.org/documentation/ssl/),
[Microsoft JDBC-Verbindungsadressen](https://learn.microsoft.com/en-us/sql/connect/jdbc/building-the-connection-url),
[Oracle JDBC TLS](https://docs.oracle.com/en/database/oracle/oracle-database/23/jjdbc/client-side-security.html).
