# Betriebshandbuch

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
