# Betriebsgrenzen und Wiederanlauf des Projektportfolios

Das Projektportfolio prüft serverseitige Grenzen, bevor Daten gespeichert oder kostenintensive Analysen gestartet werden.

| Eigenschaft | Umgebungsvariable | Standard | Zweck |
|---|---|---:|---|
| `taxonomy.portfolio.max-analysis-batch` | `TAXONOMY_PORTFOLIO_MAX_ANALYSIS_BATCH` | `100` | Höchstzahl der Anforderungen in einem Analysejob |
| `taxonomy.portfolio.max-import-requirements` | `TAXONOMY_PORTFOLIO_MAX_IMPORT_REQUIREMENTS` | `100` | Höchstzahl importierter Anforderungskandidaten pro Anfrage |
| `taxonomy.portfolio.max-import-characters` | `TAXONOMY_PORTFOLIO_MAX_IMPORT_CHARACTERS` | `500000` | Maximale Gesamtzahl der Zeichen aus Anforderungs- und Originalquelltexten |
| `taxonomy.portfolio.analysis-claim-timeout-seconds` | `TAXONOMY_PORTFOLIO_ANALYSIS_CLAIM_TIMEOUT_SECONDS` | `900` | Alter, ab dem ein `RUNNING`-Eintrag wiederaufgenommen werden darf |

Ein Analyseeintrag wird atomar in einer eigenen kurzen Transaktion von `PENDING` nach `RUNNING` übernommen. Der externe LLM-Aufruf beginnt erst nach dem Commit dieser Transaktion.

Eine Wiederholung setzt fehlgeschlagene Einträge sowie ausschließlich solche laufenden Einträge zurück, deren Übernahme älter als das konfigurierte Timeout ist. Auch das Zurücksetzen verwendet status- und zeitgestützte Compare-and-set-Updates. Zwei parallele Wiederholungsanfragen können deshalb denselben Eintrag weder doppelt übernehmen noch dessen Versuchszähler doppelt erhöhen.

Ein zu niedriges Timeout kann Arbeit duplizieren, wenn ein Provider legitimerweise länger benötigt. Es sollte oberhalb der längsten erwarteten Providerlaufzeit einschließlich deterministischer Nachverarbeitung liegen. Aktive Worker werden durch eine Wiederholungsanfrage nicht unterbrochen.

Zu große Analyse- oder Importanfragen werden abgelehnt, bevor ein Analysejob oder importierte Anforderungen gespeichert werden. Die Anwendungslimits sollten durch Größenlimits am Ingress, Provider-Rate-Limits und reguläre Datenbanksicherungen ergänzt werden.
