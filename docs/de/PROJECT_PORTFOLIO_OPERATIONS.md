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
| `taxonomy.portfolio.snapshot.packed-writes` | `TAXONOMY_PORTFOLIO_SNAPSHOT_PACKED_WRITES` | `false` | Erlaubt das kompakte v1-Speicherformat für geeignete neue Analyse-Snapshots |

Die Standardparallelität von eins ist bewusst konservativ: Bereits die Analyse einer einzigen Anforderung kann beim Durchlaufen der Taxonomie mehrere Provideraufrufe auslösen. Sie sollte nur zusammen mit Provider-Rate-Limits, Datenbankkapazität und einem beobachteten Latenz-/Fehlerbudget erhöht werden. Anders als bei einem Core-/Max-Pool mit großer Warteschlange entspricht dieser feste Wert der tatsächlichen dauerhaften Parallelität.

## Einführung und Rücknahme des kompakten Snapshot-Formats

Belassen Sie `TAXONOMY_PORTFOLIO_SNAPSHOT_PACKED_WRITES` während der Aktualisierung auf `false`. Die kompatible Version liest bei jeder Einstellung sowohl reines JSON als auch das kompakte v1-Format. Aktualisieren Sie vor der ausdrücklichen Aktivierung mit `true` auf ausgewählten schreibenden Instanzen alle Leser derselben Datenbank. Dazu gehören Anwendungs- und Worker-Replikate, Berichts- und Sicherungsprozesse, Kommandozeilen- und Batch-Werkzeuge sowie Integrationen mit direkten Abfragen auf `req_analysis_snapshot.analysis_payload`. Nehmen Sie ältere Replikate außer Betrieb und verhindern Sie einen Neustart alter Images mit Zugriff auf diese Datenbank. Prüfen Sie vor der Aktivierung auf der Installation, dass sich ein Portfolio-Export im v1-Format erstellen und wieder öffnen lässt.

Bei aktivierter Option speichert die Anwendung geeignete Analyse-Nutzdaten kompakt im v1-Format, sofern das Ergebnis ausreichend kleiner ist. Nutzdaten unter 65.536 oder über 24.000.000 UTF-16-Codeeinheiten sowie Nutzdaten mit zu geringer Komprimierung bleiben reines JSON. Für die übrigen Snapshot-Nutzdaten sowie für den öffentlichen Export und die Wiederherstellung gelten weiterhin die bisherigen Formate und der bisherige Funktionsumfang. Das Upgrade und die Aktivierung erfordern weder eine Schemaänderung noch eine nachträgliche Anpassung bestehender Datensätze.

Mit `false` beenden Sie das Schreiben neuer v1-Datensätze. Bereits gespeicherte v1-Datensätze erfordern weiterhin kompatible Leser. Sobald ein v1-Datensatz geschrieben wurde, ist die kompatible Version mit Unterstützung für beide Formate die älteste zulässige Rückfallversion. Vor einer Rückkehr zu einer älteren Version muss jede betroffene Datenbank separat umgestellt und geprüft werden, bevor sich ein älterer Leser wieder verbindet.

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

## Geprüfter Skalierungsvertrag

Die Größen 100, 1.000 und 10.000 prüfen bewusst unterschiedliche Risiken. Sie dürfen nicht als identische Lasttests interpretiert werden.

| Größe | Nachweis | Verbindliche Aussage |
|---:|---|---|
| 100 Anforderungen | `ProjectRequirementListQueryCountTest` legt über den öffentlichen Service ein reales Projekt mit 100 Anforderungen und Versionen an | Das vollständige Listen einschließlich aktueller Versionen benötigt höchstens drei SQL-Statements; die Queryzahl wächst nicht mit der Anzahl der Anforderungen |
| 1.000 Anforderungen | `ProjectRequirementThousandScaleContractTest` persistiert 1.000 reale Anforderungen und unveränderliche Versionen | Der reine, von der Fixture-Erzeugung getrennte Leseweg benötigt höchstens drei SQL-Statements und muss in der CI-In-Memory-Datenbank innerhalb von 30 Sekunden abschließen |
| 10.000 Anforderungen | `ProjectPortfolioViewCountTest` simuliert die Aggregatzahlen eines großen Projekts | Eine Projektzusammenfassung lädt keine vollständigen Anforderungs-, Lösungs- oder Konfliktlisten, sondern ausschließlich skalare Datenbankzählungen |

Der 10.000er-Nachweis ist ein **kontrollierter Grenzvertrag für die Zusammenfassung**, kein behaupteter End-to-End-Durchsatztest mit 10.000 vollständig materialisierten Anforderungen. Eine Anforderungsliste überträgt und materialisiert weiterhin proportional zur Ergebnisgröße Daten und Objekte; ihre SQL-Statement-Anzahl ist konstant, Speicherbedarf und Antwortgröße bleiben jedoch `O(n)`.

Das Standardlimit von 100 Anforderungen gilt pro **Analysejob**. Projekte dürfen mehr Anforderungen enthalten; große Bestände werden in mehrere Analysejobs aufgeteilt. Die 1.000er- und 10.000er-Verträge heben daher weder Providerbudgets noch Batchgrenzen auf.

Vor einer produktiven Kapazitätszusage sind zusätzlich umgebungsspezifische Messungen mit der eingesetzten PostgreSQL-Version, realistischen Text-/Snapshotgrößen, Netzwerklatenz, Heapgrenzen und gleichzeitigen Benutzern erforderlich. Die Repositorytests sichern die algorithmische und queryseitige Untergrenze, nicht eine universelle Antwortzeit für jede Infrastruktur.
