# Observability mit OpenTelemetry

Taxonomy unterstützt eine **optionale, herstellerneutrale Tracing-Schicht** auf Basis des OpenTelemetry-Java-Agents und OTLP. Der normale JVM- und Docker-Start bleibt unverändert: Der Agent ist im Runtime-Image vorhanden, wird aber nur angehängt, wenn dies ein Betreiber ausdrücklich aktiviert.

Die erste Implementierung lässt den bestehenden Metrikpfad bewusst unverändert:

```text
Taxonomy-Anwendung
  ├─ Spring Boot Actuator und Micrometer → /actuator/prometheus
  ├─ optionaler OpenTelemetry-Java-Agent
  │    ├─ HTTP-, Spring-, Hibernate-, JDBC-, HikariCP- und HTTP-Client-Spans
  │    └─ ausgewählte fachliche Taxonomy-Methoden-Spans
  └─ OTLP-Traces
       ↓
OpenTelemetry Collector
  ├─ Speicherbegrenzung und Batching
  ├─ Datenschutz- und Inhaltsfilter
  └─ Jaeger als lokales Beispiel-Backend
```

OpenTelemetry ist kein erforderlicher Laufzeitdienst. Taxonomy wird nicht von Jaeger, Grafana, Tempo oder einem kommerziellen Monitoringanbieter abhängig.

## Was instrumentiert wird

Der Java-Agent deckt unterstützte technische Grenzen automatisch ab, insbesondere:

- eingehende Spring-MVC-Anfragen;
- ausgehende HTTP-Anfragen einschließlich unterstützter LLM-HTTP-Clients;
- Hibernate-ORM- und JDBC-Operationen;
- HikariCP sowie Kontextweitergabe über Executors;
- Ausnahmen und Request-Ergebnisse.

`observability/javaagent.properties` ergänzt grobe interne Spans für Grenzen, die eine generische Framework-Instrumentierung nicht fachlich erkennen kann:

- Workspace-Auflösung und Routing logischer Repositories;
- JGit-Lesen, Commits, Diffs, Branches und Merges;
- DSL-Parsing und Materialisierung;
- Hibernate-Search-Abfragen;
- Anforderungs- und Kindknotenanalysen;
- LLM-Orchestrierungsaufrufe;
- Framework-Vorschau und -Import;
- Visio-, ArchiMate-, Mermaid- und Structurizr-Exporte.

Die Methodeninstrumentierung erfasst Laufzeit und Ausnahmen. Sie zeichnet **keine** Aufrufargumente oder Rückgabewerte auf.

## Lokaler Trace-Stack

Anwendung, Collector und Jaeger werden gemeinsam gestartet mit:

```bash
docker compose -f docker-compose.observability.yml up --build
```

Danach sind erreichbar:

- Taxonomy: `http://localhost:8080`
- Jaeger: `http://localhost:16686`

In Jaeger den Service `taxonomy` auswählen und anschließend in Taxonomy eine Suche, Analyse, einen Import, Export oder eine DSL-Operation ausführen. Ein Trace sollte einen HTTP-Root-Span, gegebenenfalls Framework- und Datenbank-Child-Spans sowie ausgewählte Taxonomy-Methoden-Spans enthalten.

Der lokale Stack veröffentlicht nur die Anwendung und die Jaeger-Oberfläche auf dem Loopback-Interface. Die OTLP-Ports 4317 und 4318 bleiben im Compose-Netzwerk.

Stoppen:

```bash
docker compose -f docker-compose.observability.yml down
```

Lokale Taxonomy-Datenbank zusätzlich löschen:

```bash
docker compose -f docker-compose.observability.yml down --volumes
```

## Der normale Start bleibt unverändert

Diese Aufrufe hängen den Agent nicht an:

```bash
./mvnw -pl taxonomy-app -am spring-boot:run
docker build -t taxonomy .
docker run --rm taxonomy
```

Der Docker-Entrypoint enthält absichtlich keine `-javaagent`-Option. Das bloße Bauen oder Starten des Images benötigt keinen Collector und erzeugt keinen OTLP-Verkehr.

## Externen Collector verwenden

Der mitgelieferte Agent wird ausdrücklich aktiviert und erhält einen OTLP-Endpunkt:

```bash
docker run --rm \
  -e JAVA_TOOL_OPTIONS=-javaagent:/opt/opentelemetry/opentelemetry-javaagent.jar \
  -e OTEL_JAVAAGENT_CONFIGURATION_FILE=/opt/opentelemetry/javaagent.properties \
  -e SPRING_PROFILES_ACTIVE=hsqldb,observability \
  -e OTEL_SERVICE_NAME=taxonomy \
  -e OTEL_TRACES_EXPORTER=otlp \
  -e OTEL_METRICS_EXPORTER=none \
  -e OTEL_LOGS_EXPORTER=none \
  -e OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf \
  -e OTEL_EXPORTER_OTLP_ENDPOINT=https://collector.example.invalid:4318 \
  taxonomy
```

OTLP-Zugangsdaten oder Client-Zertifikate gehören in den Secret-Speicher der Zielumgebung. Sie dürfen nicht in Compose-Dateien oder Repository-Konfigurationen eingecheckt werden.

Für einen entfernten Collector müssen TLS und Authentifizierung eingerichtet werden. Die lokale Beispielkonfiguration verwendet eine unverschlüsselte Verbindung ausschließlich innerhalb des privaten Compose-Netzwerks.

## Sampling

Das lokale Beispiel zeichnet alle Traces auf, damit jeder getestete Ablauf sichtbar ist. Für Produktion sollte ein konfigurierbares parent-basiertes Ratio-Sampling verwendet werden, beispielsweise:

```text
OTEL_TRACES_SAMPLER=parentbased_traceidratio
OTEL_TRACES_SAMPLER_ARG=0.10
```

`0.10` zeichnet ungefähr zehn Prozent neu beginnender Root-Traces auf und respektiert eine vorgelagerte Sampling-Entscheidung. Sampling reduziert Trace-Datenmenge und Overhead, verändert aber nicht die Aggregation der Prometheus-Metriken.

## Metriken

Taxonomy stellt Micrometer-Metriken weiterhin bereit unter:

```text
/actuator/prometheus
```

Das Java-Agent-Beispiel setzt:

```text
OTEL_METRICS_EXPORTER=none
```

Dadurch entsteht kein zweiter Metrikpfad und es werden Spring-Boot-, JVM-, Hibernate- und Connection-Pool-Metriken nicht doppelt exportiert. Hibernate-Statistiken sind in der Anwendung bereits aktiviert und über den vorhandenen Micrometer-/Prometheus-Pfad verfügbar.

Taxonomy-eigene Micrometer-Observations ergänzen am selben Endpunkt begrenzte fachliche Timer. Der Metrikname wird aus der Observation-Grenze abgeleitet, beispielsweise:

```text
taxonomy_workspace_resolve_seconds_count
```

Verwendet werden ausschließlich feste, niedrig-kardinale Labels: `taxonomy.component`, `taxonomy.operation` und das normalisierte `outcome` (`success` oder `error`). Benutzernamen, Workspace- oder Repository-Namen, Suchanfragen, Prompts, Dateinamen und Exception-Meldungen werden niemals Metrik-Labels. Die Live-Containerprüfung ruft `/api/relations` auf und verlangt den Workspace-Timer mit `component=workspace`, `operation=resolveCurrentContext` und `outcome=success`.

## Log-Korrelation

Das Spring-Profil `observability` formatiert die OpenTelemetry-MDC-Felder so:

```text
[trace_id=<32 hexadezimale Zeichen> span_id=<16 hexadezimale Zeichen>]
```

Mit diesen Kennungen kann der zugehörige Trace beziehungsweise Span gefunden werden. Wenn kein Span aktiv ist, erscheint `none`. Logs bleiben in dieser ersten Implementierung bei der Anwendung; der OTLP-Logexport ist deaktiviert.

Taxonomy kann an einer beobachteten fachlichen Grenze zusätzlich genau eine begrenzte DEBUG-Meldung ausgeben:

```text
Observed taxonomy operation component=workspace operation=resolveCurrentContext outcome=success
```

Sie wird nur bei Bedarf mit `LOGGING_LEVEL_COM_TAXONOMY_OBSERVABILITY=DEBUG` aktiviert. Der normale INFO-Betrieb bleibt unverändert. Die Meldung enthält ausschließlich feste Komponenten-/Operationsnamen und ein normalisiertes Ergebnis; Methodenargumente, Rückgabewerte, Identitäten, Inhalte und Exception-Meldungen werden nie ausgegeben. Der Live-Abnahmetest verlangt für diese Zeile dieselbe `trace_id` wie im exportierten HTTP-/Domain-Trace.

## Datenminimierung

Telemetrie darf keine Architektur- oder Benutzerinhalte enthalten. Anwendung und Agent erfassen keine Methodenargumente. Der Collector filtert die Daten vor dem Export ein zweites Mal.

Unzulässig sind insbesondere:

- Prompts und LLM-Antworten;
- DSL-Quelltext und erzeugte Architekturinhalte;
- Taxonomie-Titel, Beschreibungen und importierte Zellwerte;
- Workspace- und Repository-Namen;
- Benutzernamen, E-Mail-Adressen, Tokens, Rollen und Berechtigungen;
- Dateinamen und absolute Pfade hochgeladener Dateien;
- Query-Strings und beliebige HTTP-Header;
- SQL-Bindewerte.

Der Collector entfernt bekannte Generative-AI-Inhaltsattribute, Identitätsfelder, URL-Query-Felder, Taxonomy-Inhaltsfelder, Exception-Meldungen und Stacktraces. Zusätzlich begrenzt er Anzahl und Länge der Attribute. Exception-Typ und Fehlerstatus eines Spans bleiben für die Betriebsdiagnose erhalten.

`@SpanAttribute`, Request-/Response-Header-Erfassung, Request-Parameter-Erfassung und frei formulierte Identifikatoren dürfen nur nach einer gesonderten Datenschutz- und Kardinalitätsprüfung ergänzt werden.

## Ressourcen- und Fehlergrenzen

Die mitgelieferte Collector-Konfiguration verwendet:

- einen Memory-Limiter von 128 MiB;
- begrenzte Batches;
- eine begrenzte Exporter-Queue mit 512 Einträgen;
- eine begrenzte Retry-Dauer;
- keine persistente Queue;
- keinen öffentlich erreichbaren Ingestion-Port.

Ein nicht erreichbarer Collector darf den Start von Taxonomy nicht verhindern. Der Java-Agent puffert nur innerhalb begrenzter Grenzen und meldet Exportfehler in seinen eigenen Logs. Wiederholte Exportfehler sollten behoben und nicht durch unbegrenzte Queues verborgen werden.

## Performance-Prüfung

Vor der Aktivierung in Produktion muss derselbe repräsentative Workload mit und ohne Agent verglichen werden. Mindestens zu erfassen sind:

- Startdauer;
- Speicher- und CPU-Verbrauch im stabilen Betrieb;
- p50- und p95-Latenz für Suche, Analyse und Repository-Operationen;
- exportierte Spans pro Request;
- Wirkung der vorgesehenen Sampling-Rate.

Mehr als zehn Prozent p95-Latenz-Overhead im repräsentativen Test müssen untersucht werden. Zunächst sollten Span-Menge oder Sampling reduziert werden, bevor Collector-Queues oder Speicher vergrößert werden.

## Fehlerbehebung

### Keine Traces sichtbar

Prüfen, ob:

1. `JAVA_TOOL_OPTIONS` den Pfad des mitgelieferten Agents enthält;
2. `OTEL_TRACES_EXPORTER` auf `otlp` steht;
3. Endpunkt, Collector-Port und Protokoll zusammenpassen;
4. die Collector-Konfiguration fehlerfrei geladen wurde;
5. Jaeger nach einem ausgeführten Request den Service `taxonomy` anzeigt.

Nur zur vorübergehenden Diagnose kann `OTEL_JAVAAGENT_DEBUG=true` gesetzt werden. Die Agent-Debugausgabe ist sehr umfangreich und sollte im Normalbetrieb deaktiviert bleiben.

### Doppelte Spans oder Metriken

Neben dem Java-Agent darf kein separat konfiguriertes OpenTelemetry-SDK beziehungsweise kein zusätzlicher Starter parallel laufen, sofern die Überschneidung nicht ausdrücklich entworfen wurde. Solange Prometheus die Metrikautorität bleibt, müssen Micrometer-Bridge und OTLP-Metrikexport des Agents deaktiviert bleiben.

### Fachlicher Span fehlt nach einem Refactoring

`ObservabilityConfigurationTest` prüft, dass jede in `observability/javaagent.properties` genannte Methode weiterhin existiert. Bei Umbenennungen müssen Konfiguration und Dokumentation gemeinsam aktualisiert werden.

## Versions- und Supply-Chain-Regel

Das Runtime-Image verwendet eine versionierte OpenTelemetry-Java-Agent-Image-Stufe. Auch Collector und Jaeger sind auf konkrete Versionen festgelegt. Aktualisierungen erfolgen als geprüfte Abhängigkeitsänderung mit erneuter Konfigurations-, Container- und Performance-Prüfung. Da der kopierte Agent keine Maven-Runtime-Abhängigkeit ist, muss er zusätzlich beim Container-SBOM und beim Vulnerability-Scanning berücksichtigt werden.
