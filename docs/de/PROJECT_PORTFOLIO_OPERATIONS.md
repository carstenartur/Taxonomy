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
