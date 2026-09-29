# Begrenzte Mehrbenutzer-Analyse

Die bestehende Analyse-Registry unterscheidet jetzt angenommene wartende Vorgänge
(`QUEUED`) von tatsächlich gestarteten Analysen (`RUNNING`). Direkte Vollanalysen,
Streaming und Portfolio-Analysen nutzen an dieser Stelle dieselbe prozesslokale
Kapazität. Ein Abbruch vor dem Start gibt den Warteplatz sofort frei; ein später
startender Worker darf danach keine LLM-Anfrage mehr senden. Benutzer, Workspace,
Repository und Branch bleiben Bestandteil jeder Berechtigungsprüfung.

## Konfiguration

| Eigenschaft | Standard | Bedeutung |
|---|---:|---|
| `taxonomy.analysis.max-concurrent-jobs` | 4 | Gleichzeitig aktive Vollanalysen, 1–64 |
| `taxonomy.analysis.queue-capacity` | 16 | Live-Warteplätze, 1–10000 |
| `taxonomy.analysis.max-concurrent-jobs-per-user` | min(2, global) | Aktive Vollanalysen je Benutzername |
| `taxonomy.analysis.queue-capacity-per-user` | min(8, globale Queue) | Live-Warteplätze je Benutzername |
| `taxonomy.analysis.maximum-queue-wait-seconds` | 1800 | Maximale Wartezeit auf Analysezulassung, 1–86400 |
| `taxonomy.llm.max-concurrent-requests` | 4 | Gleichzeitige HTTP-Aufrufe je Provider, 1–64 |
| `taxonomy.llm.request-queue-capacity` | 64 | Wartende HTTP-Versuche je Provider, 1–10000 |
| `taxonomy.llm.maximum-queue-wait-seconds` | 120 | Maximale Provider-Wartezeit, 1–86400 |

Benutzergrenzen dürfen die entsprechende globale Grenze nicht überschreiten.
Ungültige Startwerte werden abgewiesen. Provider-spezifische Abweichungen verwenden
zum Beispiel `taxonomy.llm.providers.custom_openai.max-concurrent-requests=2`.

Das zusätzliche Spring-Profil `multiuser-analysis` setzt den Portfolio-Worker-Standard
auf vier und stellt die obigen Werte bereit. Vorhandene Datenbank- und
Sicherheitsprofile müssen erhalten bleiben, beispielsweise:

```text
SPRING_PROFILES_ACTIVE=hsqldb-file,multiuser-analysis
TAXONOMY_PORTFOLIO_ANALYSIS_WORKER_CONCURRENCY=4
TAXONOMY_ANALYSIS_MAX_CONCURRENT_JOBS=4
TAXONOMY_ANALYSIS_MAX_CONCURRENT_JOBS_PER_USER=2
```

Ohne dieses Profil oder eine explizite Worker-Einstellung bleibt für bestehende
Installationen der konservative Standard von einem Portfolio-Worker bestehen.
Mehr Copilot-Koordinatoren allein bedeuten nicht mehr parallele Analysen. Verfügbare
Worker und Provider-Grenzen können die tatsächliche Parallelität weiter begrenzen.

## LLM-Aufrufe und Anzeige

Jeder HTTP-Versuch der Gemini- und OpenAI-kompatiblen Gateways durchläuft dieselbe
Provider-Begrenzung, auch Wiederholungen sowie interaktive Knoten-, Begründungs-
und Neuformulierungsaufrufe über diese Gateways. Replay belegt keinen HTTP-Platz.
Die vorhandenen RPM-Einstellungen bleiben erhalten: `llm.rpm` für Gemini und
`llm.rpm.<providername-klein>` für die anderen Gateways. Sie müssen zum tatsächlichen
Kontingent des Betreibers passen. Provider-Aliase oder andere Anwendungen mit
demselben externen Kontingent werden nicht automatisch zusammengefasst.

Vorübergehende HTTP-429-Fehler werden nur begrenzt wiederholt. `Retry-After` wird
als Sekundenwert oder HTTP-Datum ausgewertet und als gemeinsame lokale
Provider-Wartefrist berücksichtigt. Über 60 Sekunden wird nicht automatisch
wiederholt; die längere Sperrfrist wird trotzdem beibehalten, nicht verkürzt.
Bekannte dauerhafte Abrechnungs-/Quota-Fehler lösen keine automatische Wiederholung
aus. Eine eigene maximale Provider-Wartezeit schützt auch Aufrufe ohne Vollanalyse-
Beobachter. Laufendes blockierendes HTTP kann nach einem Abbruch noch bis zum
Transport-Timeout benötigen.

Die Oberfläche zeigt Analyse-Warteschlange, fehlende Provider-Kapazität, Ratenlimit,
Retry und Warten auf die LLM-Antwort getrennt. Wartezeit und Ausführungszeit werden
separat angegeben. Es gibt keine erfundene Warteschlangenposition oder Startzeit
und keine Anzeige fremder Benutzer- oder Anforderungsdaten. Die bestehende
Gesamtfrist der Analyse enthält weiterhin die Wartezeit.

## Bewusste Grenze dieses Schritts

Bestehende persistierte Portfolio-Jobs behalten `PENDING`, Dispatch-Queue,
202-Schnittstelle und Claim-/Wiederanlaufregeln. Der bisherige direkte POST bleibt
synchron; seine Live-Reservierung wird nicht zu einem neuen restartfesten 202-Job.
SSE bleibt verbindungsgebunden. Die Live-Registry ersetzt nicht die dauerhafte
Portfolio-Auftragsansicht.

Die faire Auswahl gilt für Worker, die die Zulassung bereits erreicht haben.
Aufträge in vorgeschalteten Executor-FIFOs liegen noch außerhalb dieser Auswahl.
Die benutzerbezogene Live-Grenze begrenzt nicht die Zahl gespeicherter Portfolio-
Jobs. Ein großer Batch verarbeitet seine Anforderungen weiterhin nacheinander.
Eine vollständig faire persistente Dispatch-Queue wird damit nicht behauptet.

**Für installationsweite Grenzen nur eine ausführende Anwendungsinstanz verwenden.**
Live-Status und Limits sind prozesslokal, nicht über mehrere Rancher-Pods verteilt.
Eine gemeinsame Datenbank allein reicht dafür nicht. Das Profil dokumentiert
diese Grenze; es erkennt oder verbietet zusätzliche Pods nicht automatisch.
Ein Token-pro-Minute-/Tageskontingent wird hier ebenfalls nicht ergänzt; bestehende
Prompt-Grenzen und die Token-Kontingente des Providers bleiben maßgeblich.

[Prüfnachweis](../testing/multiuser-analysis-admission.md) ·
[Portfolio-Betrieb](PROJECT_PORTFOLIO_OPERATIONS.md)
