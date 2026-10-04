# Betriebshandbuch

Verteilte Analyse mit externem Artemis: [Bereitstellung, TLS/Secrets, Worker-Skalierung, HA und Administration](#betrieb-mit-externem-artemis-broker).

Dieses Dokument beschreibt die betrieblichen Verfahren für den Betrieb des Taxonomy Architecture Analyzer in Produktionsumgebungen.

---

## Inhaltsverzeichnis

1. [Systemanforderungen](#systemanforderungen)
2. [Health Checks](#health-checks)
3. [Überwachung](#überwachung)
4. [Protokollierung](#protokollierung)
5. [Sicherung und Wiederherstellung](#sicherung-und-wiederherstellung)
6. [Datenbankwartung](#datenbankwartung)
7. [Lucene-Indexverwaltung](#lucene-indexverwaltung)
8. [JGit-Repository-Wartung](#jgit-repository-wartung)
9. [Skalierungsaspekte](#skalierungsaspekte)
10. [Fehlerbehebung](#fehlerbehebung)

---

## Systemanforderungen

### Minimum (Entwicklung / Kleine Teams)

| Ressource | Anforderung |
|---|---|
| **CPU** | 2 Kerne |
| **RAM** | 512 MB (ohne Embedding), 1 GB (mit Embedding) |
| **Festplatte** | 500 MB (Anwendung + Daten) |
| **Java** | 21+ (JRE) |

### Empfohlen (Produktion / 10+ Benutzer)

| Ressource | Anforderung |
|---|---|
| **CPU** | 4 Kerne |
| **RAM** | 2–4 GB |
| **Festplatte** | 5 GB (SSD empfohlen für Lucene-Index) |
| **Datenbank** | PostgreSQL 16+ (extern) |

---

## Health Checks

### Endpunkte

| Endpunkt | Authentifizierung erforderlich | Zweck |
|---|---|---|
| `GET /api/status/startup` | Nein | Gibt 200 zurück, sobald die Anwendung Verbindungen akzeptiert. Verwenden Sie diesen Endpunkt als Docker/Kubernetes Health Check. |
| `GET /actuator/health` | Nein (Zusammenfassung), Ja (Details) | Spring Boot Health Indicator — aggregiert Datenbank-, Festplatten- und Lucene-Status |
| `GET /actuator/health/liveness` | Nein | Kubernetes Liveness Probe |
| `GET /actuator/health/readiness` | Nein | Kubernetes Readiness Probe |
| `GET /api/ai-status` | Ja | Verfügbarkeit des LLM-Anbieters (connected / degraded / unavailable) |
| `GET /api/embedding/status` | Ja | Status des lokalen Embedding-Modells |

### Docker Health Check

```dockerfile
HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD wget -q --spider http://localhost:8080/api/status/startup || exit 1
```

### Kubernetes Probes

```yaml
livenessProbe:
  httpGet:
    path: /actuator/health/liveness
    port: 8080
  initialDelaySeconds: 60
  periodSeconds: 30
readinessProbe:
  httpGet:
    path: /actuator/health/readiness
    port: 8080
  initialDelaySeconds: 30
  periodSeconds: 10
```

---

## Überwachung

### Prometheus-Metriken

Die Anwendung stellt Prometheus-kompatible Metriken unter folgendem Endpunkt bereit:

```
GET /actuator/prometheus
```

Wichtige zu überwachende Metriken:

| Metrik | Beschreibung | Schwellenwert für Alarme |
|---|---|---|
| `http_server_requests_seconds_count` | Anfragenanzahl nach Endpunkt und Status | Fehlerrate > 5% |
| `http_server_requests_seconds_sum` | Gesamte Anfragedauer | P99 > 5s |
| `jvm_memory_used_bytes` | JVM-Heap-Nutzung | > 80% des Maximums |
| `jvm_threads_live_threads` | Anzahl aktiver Threads | > 200 |
| `hibernate_sessions_open_total` | Anzahl der Datenbanksitzungen | Anhaltend hoher Wert |
| `process_cpu_usage` | CPU-Auslastung | > 80% anhaltend |
| `disk_free_bytes` | Verfügbarer Festplattenspeicher | < 500 MB |

### Grafana-Dashboard

Importieren Sie die Prometheus-Datenquelle und erstellen Sie Panels für:

1. **Anfragerate** — `rate(http_server_requests_seconds_count[5m])`
2. **Fehlerrate** — `rate(http_server_requests_seconds_count{status=~"5.."}[5m])`
3. **Antwortzeit** — `histogram_quantile(0.99, rate(http_server_requests_seconds_bucket[5m]))`
4. **JVM-Heap** — `jvm_memory_used_bytes{area="heap"}`
5. **Aktive Sitzungen** — `hibernate_sessions_open_total`

---

## Protokollierung

### Protokollformat

Die Anwendung verwendet SLF4J/Logback mit dem Spring Boot-Standardformat:

```
2026-03-15 10:30:00.000  INFO 1234 --- [main] c.t.service.TaxonomyService : Taxonomy loaded: 2500 nodes
```

### Sicherheits-Audit-Ereignisse

Wenn `TAXONOMY_AUDIT_LOGGING=true` (Standard im Produktionsprofil):

```
LOGIN_SUCCESS user=admin ip=192.168.1.100
LOGIN_FAILED user=unknown ip=10.0.0.1
USER_CREATED user=analyst roles=[USER] by=admin
```

### Protokollrotation

Konfigurieren Sie die Protokollrotation im Container oder auf dem Host:

**Docker (empfohlen):**

```yaml
# docker-compose.yml
services:
  taxonomy:
    logging:
      driver: json-file
      options:
        max-size: "50m"
        max-file: "5"
```

**Logback (Anwendungsebene):**

Erstellen Sie `logback-spring.xml` in `src/main/resources/`, wenn dateibasierte Protokollierung erforderlich ist:

```xml
<configuration>
  <appender name="FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
    <file>/app/logs/taxonomy.log</file>
    <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
      <fileNamePattern>/app/logs/taxonomy.%d{yyyy-MM-dd}.log</fileNamePattern>
      <maxHistory>30</maxHistory>
      <totalSizeCap>500MB</totalSizeCap>
    </rollingPolicy>
    <encoder>
      <pattern>%d{ISO8601} %level [%thread] %logger{36} - %msg%n</pattern>
    </encoder>
  </appender>
  <root level="INFO">
    <appender-ref ref="FILE" />
  </root>
</configuration>
```

### Protokollebenen

Passen Sie die Protokollebenen über Umgebungsvariablen an:

```bash
LOGGING_LEVEL_COM_TAXONOMY=DEBUG       # Anwendungs-Debug-Protokollierung
LOGGING_LEVEL_ORG_HIBERNATE=WARN       # Hibernate-Ausgaben reduzieren
LOGGING_LEVEL_ORG_ECLIPSE_JGIT=INFO    # JGit-Operationen
```

---

## Sicherung und Wiederherstellung

### Was gesichert werden muss

| Komponente | Speicherort | Methode |
|---|---|---|
| **Maßgebliche Datenbank mit JGit-Packs, Refs und Reflog** | PostgreSQL bei externer Produktion; `/app/data/taxonomydb*` bei dateibasierter HSQLDB | Konsistente datenbankeigene Sicherung oder alle Schreiber vor einem HSQLDB-Volume-Snapshot stoppen |
| **Lucene-Indizes** | `TAXONOMY_SEARCH_DIRECTORY_ROOT` bei Dateispeicherung | Zum Datenbankstand passender Snapshot; Suche nach Restore prüfen |
| **Konfiguration und Secrets** | Deployment-spezifischer Secret-Store, externe Konfiguration | Geschützte, getrennt verwaltete Sicherung |
| **Quelldateien und Provenienz** | Deployment-abhängig, falls verwendet | Mit demselben Wiederherstellungsstand sichern |

Für PostgreSQL ist eine datenbankeigene Sicherung der Primary-Datenbank zu
verwenden. Stoppen Sie für einen koordinierten Restore alle Anwendungsschreiber;
spielen Sie die Sicherung mit den Datenbankwerkzeugen in eine isolierte, leere
Zieldatenbank ein und prüfen Sie Schema-Historie und Nutzdaten. Ein Snapshot des
Taxonomy-Volumes sichert keine externe PostgreSQL-Datenbank. Die Datenbank
enthält auch die maßgeblichen JGit-Objekte und die Historie; ein separates
`/app/data/git`-Verzeichnis ist dafür nicht vorgesehen.

### Produktions-Compose / dateibasierte HSQLDB

Verwenden Sie das [Sicherungs- und Restore-Verfahren des Containerleitfadens](CONTAINER_IMAGE.md#5-persistenz-und-backup).
Es ermittelt das tatsächliche Volume des Compose-Dienstes, statt ein neues,
projektunabhängiges `taxonomy-data`-Volume anzulegen. Stoppen Sie alle
Prozesse, die HSQLDB-Dateien schreiben können, vor der Archivierung. Testen
Sie das Entpacken in ein neues, leeres Volume auf einer separaten Instanz und
sichern Sie die Deployment-Secrets getrennt.

### Wiederherstellungsverfahren

1. Alle Schreiber stoppen und ein vollständiges Datenbank-Backup in einer isolierten Instanz wiederherstellen.
2. Bei dateibasiertem Lucene den zum Datenbankstand passenden Index-Snapshot wiederherstellen; keine verschiedenen Sicherungszeitpunkte mischen.
3. Isolierte Instanz starten, Readiness und Anwendungslogs prüfen.
4. Anmeldung/Rollen, Workspaces, Taxonomie, DSL-Branches und Commit-Verlauf sowie repräsentative Such- und Exportergebnisse prüfen.
5. Nur einen getesteten Wiederherstellungsstand gemäß betrieblichem Änderungsverfahren übernehmen.

Ein fehlender Index wird beim Start **nicht** garantiert vollständig neu
aufgebaut. Die Commit-Index-Startaufgabe indiziert nur dann ihren Bereich,
wenn die relationale Commit-Projektion leer ist. Für die Neuindizierung der
Taxonomie-Knoten und -Relationen als Embeddings muss der lokale ONNX-Pfad
aktiv sein. Löschen oder ersetzen Sie Indexdateien erst nach einem
release-spezifisch geprüften Wiederherstellungsverfahren.

---

## Datenbankwartung

### HSQLDB

HSQLDB verwendet für die Entwicklung standardmäßig den In-Memory-Modus;
beim Neustart gehen diese Daten verloren. Der Produktions-Compose-Stack
verwendet dagegen dateibasierte HSQLDB. Sichern Sie sie nach dem oben
beschriebenen Verfahren bei angehaltenen Schreibern.

### PostgreSQL

```sql
-- Datenbankgröße prüfen
SELECT pg_size_pretty(pg_database_size('taxonomy'));

-- Vacuum und Analyze (wöchentlich oder nach Massenoperationen ausführen)
VACUUM ANALYZE;

-- Lang laufende Abfragen prüfen
SELECT pid, now() - pg_stat_activity.query_start AS duration, query
FROM pg_stat_activity
WHERE state = 'active' AND now() - pg_stat_activity.query_start > interval '5 minutes';
```

### Connection-Pool-Überwachung

Überwachung über Actuator mit gesondertem konfiguriertem Maschinen-Token (kein Anmeldepasswort in der Shell-Historie):

```bash
curl -H "Authorization: Bearer $ADMIN_TOKEN" http://localhost:8080/actuator/metrics/hikaricp.connections.active
```

---

## Lucene-Indexverwaltung

### Indexspeicherort

- **Entwicklung (In-Memory):** `local-heap` — kein Festplatten-I/O, geht beim Neustart verloren
- **Produktion (persistent):** `local-filesystem` unter `TAXONOMY_SEARCH_DIRECTORY_ROOT` (Standard: `/app/data/lucene-index`)

### Index-Wiederherstellung

Löschen Sie `/app/data/lucene-index/*` nicht in der Erwartung eines
vollständigen automatischen Neuaufbaus. Stellen Sie einen passenden Snapshot
wieder her und prüfen Sie repräsentative Suchanfragen. Der Commit-Suchbereich
wird beim Start nur dann neu aufgebaut, wenn die relationale Projektion leer
ist. Lokale Knoten-/Relationsvektoren werden nur bei aktivierten Embeddings
und `LLM_PROVIDER=LOCAL_ONNX` neu indiziert. Für andere indizierte Entitäten
ist vor dem Verwerfen ihrer Dateindizes ein geprüftes, release-spezifisches
Neuaufbauverfahren nötig.

### Indexgröße

Typische Indexgröße für ~2.500 Taxonomie-Knoten: **5–20 MB** (abhängig von der Anzahl der Relationen und Analysedaten).

---

## JGit-Repository-Wartung

### Datenbankgestützte Historie

Architecture-DSL-Objekte, Refs und Reflog liegen über JGit Core in der
konfigurierten relationalen Datenbank, unter anderem in `git_packs` und
`git_reflog`. Sichern Sie diese über das Datenbank-Backup und prüfen Sie
Refs, Commit-Verlauf und Pack-Inhalte nach dem Restore. Dateibasierte
Befehle wie `git gc` und `git bundle` unter `/app/data/git` warten oder
sichern diesen Speicher nicht.

### Remote-Replikation

Wenn `TAXONOMY_DSL_REMOTE_URL` konfiguriert ist, werden DSL-Commits an ein Remote-Git-Repository gepusht. Überwachen Sie Push-Fehler in den Anwendungsprotokollen.

---

## Skalierungsaspekte

### Einzelinstanz

Die Anwendung ist für den Betrieb als Einzelinstanz konzipiert. Die Mehrbenutzerfähigkeit wird durch Workspace-Isolierung innerhalb derselben JVM realisiert.

### Leistungsoptimierung

| Parameter | Umgebungsvariable | Standard | Empfehlung |
|---|---|---|---|
| JVM-Heap | `JAVA_OPTS` | 220 MB | 1–2 GB für Produktion |
| LLM-Ratenlimit | `TAXONOMY_LLM_RPM` | 5 | An Anbieterkontingent anpassen |
| Eingehendes LLM-Kontingent | `TAXONOMY_RATE_LIMIT_PER_MINUTE` | 10 | Je authentifizierter Identität und Anwendungsinstanz dimensionieren |
| JDBC-Batch-Größe | `spring.jpa.properties.hibernate.jdbc.batch_size` | 50 | Für die meisten Workloads geeignet |

Das eingehende LLM-Kontingent wird je Anwendungsinstanz im Arbeitsspeicher geführt. Lokale Benutzernamen und unveränderliche Keycloak-Identitäten aus `iss`/`sub` erhalten getrennte Budgets. Bei mehreren Replikaten ist für eine clusterweite Obergrenze ein verteilter Ingress-Begrenzer nötig; andernfalls wächst das Gesamtkontingent mit der Replikazahl.

### Reverse Proxy

Betreiben Sie die Anwendung stets hinter einem Reverse Proxy (nginx, Caddy, HAProxy) für:

- TLS-Terminierung
- Request-Pufferung
- Verbindungslimitierung
- Caching statischer Ressourcen

Beispielhafte nginx-Konfiguration:

```nginx
server {
    listen 443 ssl;
    server_name taxonomy.example.gov;

    ssl_certificate     /etc/ssl/certs/taxonomy.crt;
    ssl_certificate_key /etc/ssl/private/taxonomy.key;

    location / {
        proxy_pass http://localhost:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

---

## Fehlerbehebung

### Anwendung startet nicht

| Symptom | Wahrscheinliche Ursache | Lösung |
|---|---|---|
| `OutOfMemoryError` | Unzureichender Heap-Speicher | `-Xmx` in `JAVA_OPTS` erhöhen |
| `Connection refused` (Datenbank) | Datenbank läuft nicht | Datenbankdienst und `TAXONOMY_DATASOURCE_URL` prüfen |
| Port bereits belegt | Ein anderer Prozess auf 8080 | Umgebungsvariable `PORT` ändern |
| `ClassNotFoundException` | Falsche Java-Version | Java 21+ sicherstellen |

### Langsame Analyse

| Symptom | Wahrscheinliche Ursache | Lösung |
|---|---|---|
| Analyse dauert > 30s | LLM-Timeout | `TAXONOMY_LLM_TIMEOUT_SECONDS` erhöhen |
| Ratenlimit-Fehler | Zu viele Anfragen | Anzahl gleichzeitiger Benutzer reduzieren oder `TAXONOMY_LLM_RPM` erhöhen |
| Embedding-Modell langsam | Erstmaliger Download | Modell vorab herunterladen über `TAXONOMY_EMBEDDING_MODEL_DIR` |

### Suche funktioniert nicht

1. Embedding-Modell-Status prüfen: `GET /api/embedding/status`
2. Index-Backup und release-spezifischen Wiederherstellungspfad prüfen; ein Neustart garantiert keine vollständige Neuindizierung
3. Protokolle auf `HSEARCH`-Fehler prüfen

---

## Weiterführende Dokumentation

- [Deployment-Leitfaden](DEPLOYMENT_GUIDE.md) — Erstinstallationsanweisungen
- [Deployment-Checkliste](DEPLOYMENT_CHECKLIST.md) — Deployment-Checkliste für Behörden
- [Konfigurationsreferenz](CONFIGURATION_REFERENCE.md) — alle Umgebungsvariablen
- [Sicherheit](SECURITY.md) — Sicherheitsarchitektur und Härtung

---

## Betrieb mit externem Artemis-Broker

Standard bleibt der Transport `local` mit Laufzeitrolle `all`. Verteilte Analysen
verwenden dasselbe Taxonomy-Image für Web-Pods mit Rolle `coordinator` und separat
skalierbare Pods mit Rolle `worker`. Die Datenbank ist die Autorität für Operationen,
Quellkontext, Aufgabeneffekte, Abbruch und Replay; der externe Broker übernimmt die
Zustellung. Produktions-Pods starten keinen eingebetteten Broker.

### Coordinator und Worker-Sets bereitstellen

Alle Pods benötigen dieselbe externe Datenbank, eine gemeinsame unveränderliche
Image-Version, dasselbe Zielpräfix sowie passende Provider- und Quellkonfiguration.
Keine private HSQLDB pro Pod verwenden. Reine Worker laden exakte Root-Snapshots
für ihre konfigurierten Roots statt den globalen Katalog und seine Indizes zu
initialisieren. Der öffentliche Ingress führt nur zu Coordinators. Zusätzliche
Worker lösen nicht automatisch die Sitzungs- und Hintergrundjob-Koordination
mehrerer Web-Replikate; dafür bleibt `scaling.allowMultipleReplicas` erforderlich.

Das [Artemis-Overlay](../../deploy/helm/taxonomy/values-artemis.yaml) erzeugt ein
Coordinator-Deployment, ein CP-Set mit zwei Replikaten und ein Set für die übrigen
sieben Roots. Das ist ein Beispiel, keine vorgeschriebene Topologie. Ein Set kann
mehrere Roots besitzen; null Replikate pausieren dieses Set. Für jeden auswählbaren
Root einschließlich Relation-Zielroots muss ein Consumer verfügbar sein.

```bash
helm upgrade --install taxonomy deploy/helm/taxonomy \
  --namespace taxonomy \
  --values deploy/helm/taxonomy/values-artemis.yaml \
  --values /secure/config/taxonomy-site.yaml \
  --set existingSecret=taxonomy-secrets \
  --set image.digest=sha256:REPLACE_WITH_VERIFIED_IMAGE_DIGEST
```

Die Standortdatei enthält geprüfte Endpunkte und Policy-Selektoren, keine
Zugangsdaten. Den Digest-Platzhalter durch einen echten Digest mit 64 Zeichen
ersetzen. `taxonomy-artemis` außerhalb des Charts über den Secret-Manager oder
geschützte Dateien mit `ARTEMIS_USER` und `ARTEMIS_PASSWORD` bereitstellen. Beide
werden als verpflichtende Secret-Referenzen injiziert; das Chart erzeugt keine
Zugangsdaten. Einen Broker-Anwendungsbenutzer ohne Verwaltungsrechte verwenden.
Datenbank- und Provider-Secrets folgen weiterhin `existingSecret` / `secretEnv`.

Nur das gewünschte Set ändern und erneut mit denselben Helm-Werten bereitstellen:

```yaml
analysis:
  workerSets:
    - name: cp
      replicas: 4
      shards: [CP]
      consumersPerShard: 1
    - name: general
      replicas: 1
      shards: [BP, BR, CI, CO, CR, IP, UA]
      consumersPerShard: 1
```

Helm ersetzt Listen vollständig: Die übrigen Sets in der Wertedatei erhalten.
Jeder Worker konsumiert Subtaxonomie- und Relation-Queues seiner Roots;
`consumersPerShard` gilt pro Aufgabenfamilie. HTTP-Kapazität zusätzlich mit
Provider-Permits begrenzen. Ein optionaler `resources`-Block pro Set überschreibt
die gemeinsamen Ressourcen. Worker nutzen flüchtige Arbeitsverzeichnisse;
dauerhafte Operationen, Ergebnisse und unveränderliche Quell-Snapshots liegen in
der gemeinsamen Datenbank. Kein gemeinsam beschreibbares Lucene-Volume verwenden.

### TLS und NetworkPolicy

Jeder TCP-Connector einer Failover-URL benötigt `sslEnabled=true`. Zertifikatsketten-
und Hostnamenprüfung aktiviert lassen; niemals `trustAll=true` oder
`verifyHost=false` verwenden. Bei `requireTls=true` weist das Chart sichtbar
unsichere URLs zurück. `requireTls=false` ist nur für ausdrücklich isolierte,
kurzlebige Testnetze vorgesehen. Für eigene CAs können PKCS12/JKS-Stores über
`analysis.artemis.tlsSecret` schreibgeschützt unter `/var/run/taxonomy-artemis`
eingebunden werden. `trustStorePath`/`trustStoreType` sowie bei mTLS
`keyStorePath`/`keyStoreType` beziehen sich auf diese Dateien.

Enthalten Connector-Optionen ein Store-Passwort, die **gesamte Broker-URL** als
Secret-Schlüssel `ARTEMIS_URL` hinterlegen:

```yaml
analysis:
  artemis:
    brokerUrl: ""
    brokerUrlSecretKey: ARTEMIS_URL
    existingSecret: taxonomy-artemis
    tlsSecret: taxonomy-artemis-tls
```

Helm kann Secret-Inhalte nicht prüfen. Bei Erstellung/Rotation sämtliche TLS-,
Trust- und Hostnamenoptionen kontrollieren. Nach URL-/Credential-Rotation Pods neu
starten; eine aktualisierte Store-Datei ersetzt die laufende JMS-Connection-Factory
nicht. Passwörter gehören weder in `JAVA_OPTS` noch in normale Werte, Render-Artefakte
oder die Shell-Historie. Siehe [Transport-Konfiguration](https://artemis.apache.org/components/artemis/documentation/latest/configuring-transports.html).

`analysis.artemis.egress` ergänzt Web- und Worker-NetworkPolicies um Regeln mit
Ziel **und** TCP-Port. Alle Primary-/Backup-Adressen einschließlich vom Broker
angekündigter Topologie-Endpunkte freigeben. DNS hat eine eigene Regel. Mit
`allowSameNamespaceEgress=false` brauchen Datenbank, Provider, OIDC, OTLP und weitere
Dienste eigene geprüfte Regeln unter `networkPolicy.egress`. Kubernetes-NetworkPolicy
löst keine FQDNs auf: geprüfte CIDRs oder Namespace-/Pod-Selektoren verwenden,
alternativ separat verwaltete CNI-FQDN-Policies. Rendering prüft die Regeln; nur
ein Cluster mit durchsetzender CNI prüft ihre tatsächliche Wirkung.

Worker haben standardmäßig keinen Service und verweigern eingehenden Verkehr.
Bei aktiviertem ServiceMonitor entstehen interne ClusterIP-Metrik-Services;
`analysis.workerMetricsIngressFrom` muss die Monitoring-Peers benennen. Web-Service
und Ingress wählen niemals Worker-Pods aus. Das Chart erstellt keine Broker-Services,
Administrationsports, Hawtio- oder Jolokia-Routen.

### Broker-Konfiguration und Zustellung

[broker.xml](../../deploy/artemis/broker.xml) ist ein echtes Artemis-Beispiel, das
`ArtemisBrokerConfigurationTest` mit der Broker-Abhängigkeit des Repositories parst.
Adress-, ACL- und Zustelleinstellungen in die extern verwaltete Installation
übernehmen. Geschützte TLS-Schlüssel, JAAS-Benutzer/Rollen, dauerhafte Datenträger
und HA separat konfigurieren; die Datei ist keine vollständige HA-Installation.
Änderungen an `destinationPrefix` erfordern passende Broker-Namen und ACL-Muster.

| Ziel unter `taxonomy.analysis` | Routing und Aufbewahrung |
|---|---|
| `subtaxonomy.BP` … `subtaxonomy.UA` | Acht dauerhafte Anycast-Queues; idempotenter Effekt pro deterministischer Aufgabenidentität |
| `relation.BP` … `relation.UA` | Acht dauerhafte Anycast-Queues, geroutet nach Zielroot |
| `relation.general` | Kompatibilitätsqueue für mehrere Roots; nur Consumer mit allen Roots |
| `completion` | Dauerhafte Anycast-Coordinator-Queue ohne Broker-Verfall |
| `progress`, `control` | Multicast mit flüchtigem Abonnement pro Prozess; Datenbank bleibt Replay-/Abbruchautorität |
| `rejected` | Ungültige, unbekannte oder falsch geroutete Nachrichten |
| `dlq`, `expiry` | Dauerhafte Fehlereingänge; der Coordinator setzt bekannte Aufgaben auf fehlgeschlagen |
| `failed` | Inhaltsfreie Fehlerdiagnosen nach dauerhafter Zustandsänderung für Administratoren |
| `provider-permits.<quota-group>` | Separat provisionierte dauerhafte Anycast-Tokens ohne Verfall oder endliches Zustelllimit |

Das Beispiel begrenzt Aufgaben auf zehn Zustellversuche mit wachsendem Abstand bis
30 Sekunden. Subtaxonomie-/Relation-Nachrichten verfallen nach 24 Stunden, falls
keine JMS-TTL gesetzt wurde. Diese Beispielwerte mit Anwendungsfristen,
Wiederherstellungsfenstern und Datenträgerkapazität abstimmen. DLQ/Expiry bedeutet
Fehler, nicht erfolgreiche Fertigstellung. Der Coordinator setzt bekannte Aufgaben dauerhaft auf fehlgeschlagen und leitet
inhaltsfreie Diagnosen nach `failed` weiter; die Datenbank behält den expliziten
End-/Teilzustand. Ursache vor selektiver Wiederherstellung beheben; niemals
Tenant-/Quell-/Task-IDs umschreiben.
Bei vollem Speicher verwendet der Broker dauerhaftes Paging statt Nachrichten
zu verwerfen. Siehe [Adressparameter](https://artemis.apache.org/components/artemis/documentation/latest/address-settings.html).

Aufgabeneffekte werden vor der Zustellbestätigung gespeichert. Ein Worker-Absturz
kann Redelivery und Replay gespeicherter Effekte auslösen; genau einmalige externe
Provider-Abrechnung ist nicht garantiert. Dispatch-Recovery läuft bei Start,
Wiederverbindung oder administrativer Reparatur
(`POST /api/admin/analysis/dispatch/repair`), nicht über periodische DB-Abfragen.
Abbruch wird vor dem Control-Event gespeichert; ein verpasstes Live-Event kann
abgebrochene Arbeit nicht wieder aktivieren.

### Clusterweite Provider-Permits

`taxonomy.analysis.provider-permits.enabled` ausdrücklich aktivieren und jeden
HTTP-Provider einer Quotengruppe zuordnen. Beispielsweise teilen
`provider-groups.openai=shared-openai` und
`provider-groups.custom-openai=shared-openai` die Tokens desselben Upstream-Kontos.
Die Quotenzuordnung nicht aus Anzeigenamen ableiten. Vor dem Start der Consumer
mit dem separaten einmaligen Provisionierer genau N dauerhafte Nachrichten in
der neuen Queue anlegen. Anwendungsstarts erzeugen keine Tokens. Eine vorhandene
Queue niemals anhand der aktuell verfügbaren Nachrichten auffüllen: Tokens
können in offenen Transaktionen gebunden sein.

Für Helm eine explizite JSON-Map verwenden, damit Provider-Namen wie
`custom-openai` erhalten bleiben. Reine Umgebungsvariablen können Trennzeichen
verlieren. In ein vorhandenes JSON-Objekt integrieren; Rolle und Transport
weiterhin über `analysis`-Chart-Werte setzen:

```yaml
config:
  SPRING_APPLICATION_JSON: >-
    {"taxonomy":{"analysis":{"provider-permits":{"enabled":true,
    "provider-groups":{"openai":"shared-openai","custom-openai":"shared-openai"}}}}}
```

Den Provisionierer aus der gepackten Anwendung mit separatem Provisionierungskonto
über `TAXONOMY_ANALYSIS_ARTEMIS_BROKER_URL`, `TAXONOMY_ANALYSIS_ARTEMIS_USER` und
`TAXONOMY_ANALYSIS_ARTEMIS_PASSWORD` starten. TLS-Vorgabe und optionales
`TAXONOMY_ANALYSIS_PROVIDER_PERMITS_DESTINATION_PREFIX` gelten weiterhin. Beispiel
für zwei Permits in `shared-openai` (außerhalb des Images den Jar-Pfad anpassen):

```bash
java -Dloader.main=com.taxonomy.composition.analysis.artemis.ArtemisProviderPermitProvisioner \
  -cp /app/app.jar org.springframework.boot.loader.launch.PropertiesLauncher shared-openai 2
```

Permit-Queues dürfen weder verfallen noch nach endlich vielen Versuchen in der DLQ
landen. Das XML setzt unbegrenzte Zustellversuche, keinen Verfall, kein Auto-Delete
und kein Leeren bei null Consumern. Die Broker-Transaktionsfrist muss länger als
der längste physische HTTP-Aufruf sein; die beispielhaften 900 Sekunden mit den
Provider-Timeouts abstimmen. Bei Ausfall eines Halters wird das unbestätigte Token
wieder verfügbar. Unterbrochene Erstprovisionierung nur bei gestoppten Consumern
prüfen: Zwischen Queue-Anlage und Token-Commit kann eine leere Queue bleiben, die
bewusst neu angelegt werden muss. Gleichzeitigkeit ist keine clusterweite
RPM-/TPM-/RPD-Grenze; vorhandene lokale Ratenbegrenzungen bleiben bestehen.

### Readiness, Monitoring und Administration

| Signal | Bedeutung |
|---|---|
| Web `/actuator/health/readiness` | `readinessState,taxonomy`: Editor/Katalog bleiben bei Brokerausfall nutzbar |
| Worker `/actuator/health/readiness` | `readinessState,analysisBroker`: Broker-Verbindung ohne globalen Katalog |
| `/actuator/health/broker` | Broker-Gruppe; Detailzugriff unterliegt weiterhin Actuator-Autorisierung |
| `/actuator/health/liveness` | Prozesszustand; Brokerausfall darf keine Neustartschleife auslösen |

Worker-Readiness beweist weder Provider-Zugangsdaten noch Quotentokens, DNS/TLS zum
Modell, Modellverfügbarkeit oder erfolgreiche Inferenz. Provider-Konfiguration
über vorhandene autorisierte Prüfungen validieren, ohne unfreigegebene kostenpflichtige
Anfragen auszulösen. Ein getrennter Worker kann bereits empfangene Arbeit noch
abschließen; das dauerhafte Protokoll sichert die Wiederherstellung.

Der vorhandene authentifizierte `/actuator/prometheus`-ServiceMonitor erfasst
Web- und Worker-Metrik-Services. Das getrennte Maschinen-Secret `ADMIN_TOKEN`
verwenden, niemals das interaktive Login-Passwort. Externe Broker-Metriken benötigen
dessen Exporter/Metrik-Plugin und einen separat verwalteten Monitor. Alarme für
Queue-Tiefe ohne Consumer, anhaltende Delivering-/Redelivery-Zahlen,
Expiry-/DLQ-/Rejected-Zuwachs, Verbindungsfehler und Paging-Plattenfüllung setzen.
Bei Permit-Queues verfügbare plus zugestellte Tokens mit der provisionierten
Kapazität vergleichen. Anwendungssignale beschreiben dauerhafte Aufgaben,
Broker-Metriken die Zustellung; keine Operations-/Benutzer-IDs als Metriklabels.
Siehe [Broker-Metriken](https://artemis.apache.org/components/artemis/documentation/latest/metrics.html).

Hawtio/Jolokia sind Administrationsoberflächen. Konsole an private Interfaces
binden, TLS und authentifizierte Administratorrollen erzwingen und Netzwerkzugriff
auf Admin-Netze oder authentifiziertes Port-Forwarding begrenzen. JAAS und
Konsolen-/Jolokia-Autorisierung ausdrücklich konfigurieren; Anwendungsbenutzer
erhalten keine `manage`-/Admin-Rollen. Hawtio-Proxy-Allowlist und Jolokia-Origin-Policy
eng halten. Weder 8161 noch 8778 oder Verwaltungskontexte über Taxonomys öffentlichen
Ingress veröffentlichen. Normales Monitoring nutzt Zähler statt Nachrichteninhalte
oder Prompt-/Antwortaufzeichnungen. Siehe
[Broker-Sicherheit](https://artemis.apache.org/components/artemis/documentation/latest/security.html)
und [Verwaltungskonsole](https://artemis.apache.org/components/artemis/documentation/latest/management-console.html).

### HA, Upgrades und Wiederherstellungsübungen

Ein Primary-/Backup-Paar mit dauerhaften Journal-, Bindings-, Paging- und
Large-Message-Daten über unterstützte Shared-Store- oder Replikations-HA betreiben.
Quorum/Fencing und Netzpartitionen gemäß gewählter Policy konfigurieren. Zwei
unabhängige Broker hinter einem Loadbalancer bilden keine gemeinsame dauerhafte
Queue oder Permit-Kapazität. Eine Failover-URL benennt Endpunkte, repliziert aber
keine Daten. Pro Permit-Queue muss während Failover genau eine logische Autorität
bestehen. Aktivierung und Failback mit TLS und tatsächlich angekündigten Hostnamen
prüfen. Siehe [Artemis-HA](https://artemis.apache.org/components/artemis/documentation/latest/ha.html).

`Recreate` wirkt pro Deployment. Es stoppt nicht alle Worker-Sets, bevor der
Coordinator die gemeinsame Datenbank migriert. Ohne bestätigte Schema-Kompatibilität
zwischen Versionen Aufnahme stoppen, Arbeit beenden/abbrechen oder dokumentieren,
alle Taxonomy-Deployments auf null skalieren und deren Ende abwarten. Datenbank und
Broker sichern, danach das neue Image für alle Rollen bereitstellen. Nach
Migration/Readiness Redelivery und Dispatch-Reparatur prüfen und Betrieb aufnehmen.
Keine ältere wiederhergestellte Datenbank ungeprüft mit einem neueren Broker-Journal
kombinieren. Rolling Updates erfordern weiterhin die ausdrückliche
versionsspezifische Kompatibilitätsbestätigung.

Der Maven-Test `HelmArtemisContractTest` rendert lokale, verteilte und begrenzte
Profile und prüft unabhängige CP-Skalierung, TLS/Secrets, Selektortrennung, Metriken
und Broker-Egress. `ArtemisBrokerConfigurationTest` prüft echte XML-Parser und Ziele:

```bash
./mvnw -B -pl taxonomy-build -am test \
  -Dtest=HelmArtemisContractTest,HelmConstrainedSmokeContractTest,ArtemisBrokerConfigurationTest \
  -Dsurefire.failIfNoSpecifiedTests=false
bash deploy/helm/taxonomy/verify.sh
```

Diese Tests ersetzen weder den vorhandenen #638-Cluster-Smoke noch die
Mehrpod-Fehlerabnahme. Dessen Single-Pod-Quota ist keine Kapazitätszusage für die
vier Anwendungspods dieses Beispiels plus externe Abhängigkeiten. In einem
wegwerfbaren Cluster mit durchsetzender CNI, externer Datenbank und HA-Broker
Belege für unabhängige CP-Skalierung, CP-Worker-Verlust bei weiterlaufendem IP,
Broker-Neustart/Failover, Coordinator-Verlust, SSE-Wiederverbindung zu einem anderen
Web-Pod, Expiry/DLQ, Permit-Recovery und verweigerten sonstigen/Admin-Egress sammeln.
Deterministischen Provider und echte Katalogroots verwenden. Befehle,
Image-/Chart-Versionen, Ressourcen, Commit, Zeiten und Ergebnisse ohne Secrets
oder Payloads festhalten; reine Render-Tests niemals als erfolgreiche Live-Übung
bezeichnen.
