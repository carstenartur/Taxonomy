# Copilot und Autopilot

Taxonomy unterscheidet zwischen einer ausdrücklich gestarteten **Copilot-Vollanalyse** und dem unbeaufsichtigten **Autopilot**.

## Copilot-Vollanalyse

Eine gespeicherte Anforderung wird über persistente, mandanten- und branchgebundene Analysejobs verarbeitet. Ein vollständiger Durchlauf erzeugt Taxonomiebewertungen, Architekturelemente und -relationen, Lücken- und Musteranalyse, eine Architekturempfehlung sowie einen unveränderlichen Snapshot. Navigation oder Neuladen der Seite verliert den Vorgang nicht.

Der manuelle Start erfolgt über:

```text
POST /api/projects/{projectId}/requirements/{requirementId}/copilot
```

Vorhandene Vorgänge können über die Operations-Endpunkte fortgesetzt oder abgebrochen werden. Die Profile `STANDARD`, `FULL` und `EXHAUSTIVE` führen ein bis drei begrenzte Prüfdurchläufe aus. Identische Eingaben verwenden ihre persistierten Jobs erneut, sofern nicht bewusst `force=true` gesetzt wird.

## Ausdrückliche Autopilot-Freigabe

Taxonomy nimmt niemals automatisch an, dass ein eigener OpenAI-kompatibler Endpunkt kostenlos ist. Unbeaufsichtigte Ausführung benötigt alle drei Einstellungen:

```text
TAXONOMY_AI_COST_POLICY=UNMETERED
TAXONOMY_AI_AUTOPILOT_ENABLED=true
TAXONOMY_AI_AUTOPILOT_PROVIDER=CUSTOM_OPENAI
```

Der ausgewählte Provider muss außerdem vollständig konfiguriert sein. Für `CUSTOM_OPENAI` sind `CUSTOM_LLM_URL` und `CUSTOM_LLM_MODEL` erforderlich; bei einem vertrauenswürdigen lokalen Server kann der Schlüssel leer bleiben.

Mit `TAXONOMY_AI_AUTOPILOT_ON_REQUIREMENT_SAVE=false` lässt sich der automatische Start nach dem Speichern abschalten, ohne ausdrücklich ausgelöste projektweite Autopilot-Läufe zu deaktivieren.

## Projektweite Ausführung

```text
GET  /api/projects/{projectId}/autopilot
POST /api/projects/{projectId}/autopilot/run
```

Der POST-Body kann `requirementIds` und `maxRequirements` enthalten. Der Server kürzt ein Projekt niemals stillschweigend: Überschreitet die Auswahl die konfigurierte Batch-Grenze, wird der Aufruf abgewiesen und verlangt eine kleinere explizite Auswahl oder eine bewusste Anpassung des Betreiberlimits.

Wichtige Grenzen sind:

```text
TAXONOMY_AI_AUTOPILOT_MAX_PROJECT_REQUIREMENTS=50
TAXONOMY_AI_AUTOPILOT_MAX_ARCHITECTURE_NODES=50
TAXONOMY_AI_COORDINATOR_MAX_CONCURRENT_OPERATIONS=4
TAXONOMY_AI_COORDINATOR_QUEUE_CAPACITY=100
TAXONOMY_AI_MAXIMUM_RUNTIME_SECONDS=1800
```

## Menschliche Freigabegrenze

Der Autopilot bereitet Nachweise und Vorschläge vor. Er darf nicht selbstständig:

- Taxonomie- oder Relationszuordnungen bestätigen;
- organisatorische Verantwortung verbindlich festlegen;
- ein Produkt auswählen oder eine Beschaffung autorisieren;
- eine freigegebene Architektur überschreiben;
- einen Entwurfszweig in einen freigegebenen Zweig mergen.

Erzeugte Lösungen bleiben `PROPOSED`, Produkte bleiben `CANDIDATE`. Jede verbindliche Entscheidung benötigt eine menschliche Freigabe.
