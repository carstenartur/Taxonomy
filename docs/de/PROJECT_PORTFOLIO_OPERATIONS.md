# Betriebsgrenzen und Wiederanlauf des Projektportfolios

Das Projektportfolio prüft serverseitige Grenzen, bevor Daten gespeichert oder kostenintensive Analysen gestartet werden.

| Eigenschaft | Umgebungsvariable | Standard | Zweck |
|---|---|---:|---|
| `taxonomy.portfolio.max-analysis-batch` | `TAXONOMY_PORTFOLIO_MAX_ANALYSIS_BATCH` | `100` | Höchstzahl der Anforderungen in einem Analysejob |
| `taxonomy.portfolio.max-import-requirements` | `TAXONOMY_PORTFOLIO_MAX_IMPORT_REQUIREMENTS` | `100` | Höchstzahl importierter Anforderungskandidaten pro Anfrage |
| `taxonomy.portfolio.max-import-characters` | `TAXONOMY_PORTFOLIO_MAX_IMPORT_CHARACTERS` | `500000` | Maximale Gesamtzahl der Zeichen aus Anforderungs- und Originalquelltexten |
| `taxonomy.portfolio.analysis-claim-timeout-seconds` | `TAXONOMY_PORTFOLIO_ANALYSIS_CLAIM_TIMEOUT_SECONDS` | `900` | Alter, ab dem ein `RUNNING`-Eintrag wiederaufgenommen werden darf |
| `taxonomy.portfolio.analysis-worker-concurrency` | `TAXONOMY_PORTFOLIO_ANALYSIS_WORKER_CONCURRENCY` | `1` | Feste Zahl gleichzeitig ausgeführter Analysejobs |
| `taxonomy.portfolio.analysis-worker-queue-capacity` | `TAXONOMY_PORTFOLIO_ANALYSIS_WORKER_QUEUE_CAPACITY` | `100` | Persistierte Jobs, die in der prozessinternen Dispatch-Warteschlange warten dürfen |
| `taxonomy.portfolio.analysis-worker-shutdown-seconds` | `TAXONOMY_PORTFOLIO_ANALYSIS_WORKER_SHUTDOWN_SECONDS` | `30` | Graceful-Shutdown-Frist für aktive Worker |

Die Standardparallelität von eins ist bewusst konservativ: Bereits die Analyse einer einzigen Anforderung kann beim Durchlaufen der Taxonomie mehrere Provideraufrufe auslösen. Sie sollte nur zusammen mit Provider-Rate-Limits, Datenbankkapazität und einem beobachteten Latenz-/Fehlerbudget erhöht werden. Anders als bei einem Core-/Max-Pool mit großer Warteschlange entspricht dieser feste Wert der tatsächlichen dauerhaften Parallelität.

## HTTP-Jobvertrag

Analyseaufträge werden gespeichert, bevor Providerarbeit beginnt. `POST /api/projects/{projectId}/analyses`, die Einzelanforderungsanalyse und Wiederholungsanfragen liefern `202 Accepted` mit einem `AnalysisJobView` sowie einem kanonischen `Location`-Header:

```text
/api/projects/{projectId}/analysis-jobs/{jobId}
```

Clients fragen diese Ressource ab, bis der Job `SUCCESS`, `PARTIAL`, `FAILED` oder `CANCELLED` erreicht. Die Portfolio-Oberfläche erledigt dieses Polling automatisch. Der ursprüngliche HTTP-Request bleibt daher nicht mehr für die Dauer eines oder mehrerer LLM-Aufrufe geöffnet.

Der Executor ist begrenzt. Sind alle Worker und Warteschlangenplätze belegt, liefert die API ein RFC-9457-Problem mit `503 Service Unavailable`. Der Job ist zu diesem Zeitpunkt bereits gespeichert und seine Kennung steht im Problemtext. Derselbe idempotente Auftrag kann deshalb erneut gesendet werden, ohne doppelte Arbeit anzulegen.

## Übernahme und Wiederanlauf

Ein Analyseeintrag wird atomar in einer eigenen kurzen Transaktion von `PENDING` nach `RUNNING` übernommen. Der externe LLM-Aufruf beginnt erst nach dem Commit dieser Transaktion.

Eine Wiederholung setzt fehlgeschlagene Einträge sowie ausschließlich solche laufenden Einträge zurück, deren Übernahme älter als das konfigurierte Timeout ist. Auch das Zurücksetzen verwendet status- und zeitgestützte Compare-and-set-Updates. Zwei parallele Wiederholungsanfragen können deshalb denselben Eintrag weder doppelt übernehmen noch dessen Versuchszähler doppelt erhöhen. Ein gespeicherter `PENDING`-Job, dessen prozessinterner Dispatch beim Herunterfahren verloren ging, kann über denselben Wiederholungsendpunkt erneut eingeplant werden.

Ein zu niedriges Timeout kann Arbeit duplizieren, wenn ein Provider legitimerweise länger benötigt. Es sollte oberhalb der längsten erwarteten Providerlaufzeit einschließlich deterministischer Nachverarbeitung liegen. Aktive Worker werden durch eine Wiederholungsanfrage nicht unterbrochen.

Beim geordneten Herunterfahren erhalten aktive Worker die konfigurierte Abschlussfrist. Stoppt der Prozess vorher, wird die persistierte `RUNNING`-Übernahme nach Ablauf des Timeouts wiederaufnahmefähig; die prozessinterne Warteschlange ist niemals die führende Datenquelle.

Zu große Analyse- oder Importanfragen werden abgelehnt, bevor ein Analysejob oder importierte Anforderungen gespeichert werden. Die Anwendungslimits sollten durch Größenlimits am Ingress, Provider-Rate-Limits und reguläre Datenbanksicherungen ergänzt werden.
