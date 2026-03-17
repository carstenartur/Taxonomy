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
| **Java** | 17+ (JRE) |

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

| Komponente | Speicherort | Häufigkeit | Methode |
|---|---|---|---|
| **Datenbank** | PostgreSQL / MSSQL / Oracle | Täglich | `pg_dump`, SQL Server Backup, RMAN |
| **Lucene-Index** | `/app/data/lucene-index` | Täglich | Dateisystem-Snapshot |
| **JGit-Repository** | `/app/data/git` | Täglich | Dateisystem-Snapshot oder `git bundle` |
| **Konfiguration** | Umgebungsvariablen | Bei Änderung | Versionskontrollierte `.env`-Datei |
| **Hochgeladene Daten** | `/app/data/uploads` (falls zutreffend) | Täglich | Dateisystem-Snapshot |

### Datenbanksicherung (PostgreSQL)

```bash
# Automatische tägliche Sicherung
pg_dump -h localhost -U taxonomy -d taxonomy -F c -f /backup/taxonomy-$(date +%Y%m%d).dump

# Wiederherstellung
pg_restore -h localhost -U taxonomy -d taxonomy /backup/taxonomy-20260315.dump
```

### Vollständige Anwendungssicherung (Docker-Volume)

```bash
# Container stoppen
docker stop taxonomy-analyzer

# Daten-Volume sichern
docker run --rm -v taxonomy-data:/data -v /backup:/backup \
  alpine tar czf /backup/taxonomy-data-$(date +%Y%m%d).tar.gz /data

# Neu starten
docker start taxonomy-analyzer
```

### Wiederherstellungsverfahren

1. Anwendung stoppen
2. Datenbank aus der letzten Sicherung wiederherstellen
3. Lucene-Indexverzeichnis wiederherstellen (oder die Anwendung den Index beim Start neu aufbauen lassen)
4. JGit-Repository-Verzeichnis wiederherstellen
5. Anwendung starten
6. Überprüfung über `GET /api/status/startup` und `GET /actuator/health`
7. Taxonomiedaten über `GET /api/taxonomy` überprüfen

> **Hinweis:** Der Lucene-Index wird beim Start automatisch aus der Datenbank neu aufgebaut, wenn er fehlt. Die Datenbanksicherung ist der kritische Wiederherstellungspfad.

---

## Datenbankwartung

### HSQLDB (nur Entwicklung)

HSQLDB verwendet standardmäßig den In-Memory-Modus. Es ist keine Wartung erforderlich. Daten gehen beim Neustart verloren.

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

Überwachung über Actuator:

```bash
curl -u admin:password http://localhost:8080/actuator/metrics/hikaricp.connections.active
```

---

## Lucene-Indexverwaltung

### Indexspeicherort

- **Entwicklung (In-Memory):** `local-heap` — kein Festplatten-I/O, geht beim Neustart verloren
- **Produktion (persistent):** `local-filesystem` unter `TAXONOMY_SEARCH_DIRECTORY_ROOT` (Standard: `/app/data/lucene-index`)

### Index neu aufbauen

Der Index wird beim Start der Anwendung automatisch aus der Datenbank neu aufgebaut. Um einen Neuaufbau zu erzwingen:

1. Anwendung stoppen
2. Indexverzeichnis löschen: `rm -rf /app/data/lucene-index/*`
3. Anwendung starten — der Index wird während der Initialisierung neu aufgebaut

### Indexgröße

Typische Indexgröße für ~2.500 Taxonomie-Knoten: **5–20 MB** (abhängig von der Anzahl der Relationen und Analysedaten).

---

## JGit-Repository-Wartung

### Repository-Speicherort

Das JGit-Repository speichert DSL-Versionen, Branches und Merge-Historien unter dem von der Anwendung konfigurierten Pfad (typischerweise `/app/data/git`).

### Garbage Collection

JGit-Repositories sammeln im Laufe der Zeit lose Objekte an. Führen Sie regelmäßig eine Garbage Collection durch:

```bash
cd /app/data/git
git gc --aggressive --prune=now
```

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
| API-Ratenlimit | `TAXONOMY_RATE_LIMIT_PER_MINUTE` | 10 | Bei hohem Datenverkehr erhöhen |
| JDBC-Batch-Größe | `spring.jpa.properties.hibernate.jdbc.batch_size` | 50 | Für die meisten Workloads geeignet |

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
| `ClassNotFoundException` | Falsche Java-Version | Java 17+ sicherstellen |

### Langsame Analyse

| Symptom | Wahrscheinliche Ursache | Lösung |
|---|---|---|
| Analyse dauert > 30s | LLM-Timeout | `TAXONOMY_LLM_TIMEOUT_SECONDS` erhöhen |
| Ratenlimit-Fehler | Zu viele Anfragen | Anzahl gleichzeitiger Benutzer reduzieren oder `TAXONOMY_LLM_RPM` erhöhen |
| Embedding-Modell langsam | Erstmaliger Download | Modell vorab herunterladen über `TAXONOMY_EMBEDDING_MODEL_DIR` |

### Suche funktioniert nicht

1. Embedding-Modell-Status prüfen: `GET /api/embedding/status`
2. Lucene-Index prüfen: Anwendung neu starten, um eine Neuindizierung auszulösen
3. Protokolle auf `HSEARCH`-Fehler prüfen

---

## Weiterführende Dokumentation

- [Deployment-Leitfaden](DEPLOYMENT_GUIDE.md) — Erstinstallationsanweisungen
- [Deployment-Checkliste](DEPLOYMENT_CHECKLIST.md) — Deployment-Checkliste für Behörden
- [Konfigurationsreferenz](CONFIGURATION_REFERENCE.md) — alle Umgebungsvariablen
- [Sicherheit](SECURITY.md) — Sicherheitsarchitektur und Härtung
