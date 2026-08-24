# Copilot und Autopilot

Taxonomy unterscheidet zwischen einer ausdrücklich gestarteten **Copilot-Vollanalyse** und dem unbeaufsichtigten **Autopiloten**. Beide verwenden persistente, mandanten-, repository- und branchgebundene Portfolio-Analysejobs. Navigation oder Neuladen verliert eine Operation nicht.

Das kanonische Verzeichnis aller Anwendungseinstellungen steht in [CONFIGURATION_REFERENCE.md](CONFIGURATION_REFERENCE.md). Diese Seite erklärt die AI-Automatisierung im Nutzungskontext.

## Manuelle Copilot-Vollanalyse

Eine gespeicherte unveränderliche Anforderungsversion durchläuft einen oder mehrere begrenzte Prüfdurchläufe. Eine abgeschlossene Operation erzeugt Taxonomiebewertungen mit Begründungen, eine relationsbezogene Architektursicht, Lücken- und Musteranalyse, eine Architekturempfehlung, unveränderliche Snapshots und—wenn aktiviert—deterministische Lösungs- und Produktvorschläge.

```text
POST /api/projects/{projectId}/requirements/{requirementId}/copilot
GET  /api/projects/{projectId}/requirements/{requirementId}/copilot/latest
GET  /api/projects/{projectId}/copilot-operations/{operationId}
POST /api/projects/{projectId}/copilot-operations/{operationId}/cancel
```

| Profil | Mindestdurchläufe | Lösungs-/Produktvorschläge |
|---|---:|---|
| `STANDARD` | 1 | deaktiviert |
| `FULL` | konfigurierter Copilot-Standard, mindestens 1 | aktiv, sofern die Anfrage sie nicht abschaltet |
| `EXHAUSTIVE` | mindestens 2 | aktiv, sofern die Anfrage sie nicht abschaltet |

Konfigurierte oder angeforderte Durchlaufzahlen müssen zwischen 1 und 3 liegen. Die Durchläufe einer Operation laufen nacheinander. Identischer Eingabestand und identische Einstellungen verwenden die persistierte Operation erneut, sofern nicht bewusst `force=true` angefordert wird.

Der manuelle Copilot benötigt einen bereiten Anbieter, aber **kein** `TAXONOMY_AI_COST_POLICY=UNMETERED`, weil jeder Lauf ausdrücklich durch einen Benutzer gestartet wird.

## Grenzen der Architekturknoten

`TAXONOMY_AI_AUTOPILOT_MAX_ARCHITECTURE_NODES` behält aus Kompatibilitätsgründen seinen historischen Namen. Die zugehörige Spring-Property heißt `taxonomy.ai.max-architecture-nodes` und gilt **sowohl für den manuellen Copilot als auch für den Autopiloten**. Sie ist die Betreiberobergrenze der Architektursicht jedes AI-Automatisierungsdurchlaufs. Eine manuelle API-Anfrage darf einen kleineren Wert wählen, aber keinen größeren.

Zusätzlich ist `TAXONOMY_LIMITS_MAX_ARCHITECTURE_NODES` die allgemeine Portfolio-Analyseobergrenze. Wirksam ist daher der kleinere Wert. Sofern keine bewusst kleinere AI-Sicht gewünscht ist, sollten beide Werte übereinstimmen:

```text
TAXONOMY_AI_AUTOPILOT_MAX_ARCHITECTURE_NODES=50
TAXONOMY_LIMITS_MAX_ARCHITECTURE_NODES=50
```

Ein höherer Wert kann mehr relevante Knoten bewahren, vergrößert aber Pipeline-Arbeit, Snapshots, Relationserzeugung und Diagramme. Er verändert nicht die Anzahl der in einem projektweiten Autopilot-Batch verarbeiteten Anforderungen.

## Ausdrückliche Autopilot-Freigabe

Taxonomy nimmt niemals automatisch an, dass ein eigener OpenAI-kompatibler Endpunkt kostenlos oder ungemessen ist. Unbeaufsichtigte Ausführung benötigt alle drei bewussten Einstellungen:

```text
TAXONOMY_AI_COST_POLICY=UNMETERED
TAXONOMY_AI_AUTOPILOT_ENABLED=true
TAXONOMY_AI_AUTOPILOT_PROVIDER=CUSTOM_OPENAI
```

Der gewählte Anbieter muss vollständig konfiguriert sein. Für `CUSTOM_OPENAI` sind `CUSTOM_LLM_URL` und `CUSTOM_LLM_MODEL` erforderlich; der API-Schlüssel darf bei einem vertrauenswürdigen unauthentifizierten lokalen Server leer bleiben.

`TAXONOMY_AI_AUTOPILOT_ON_REQUIREMENT_SAVE=false` deaktiviert den automatischen Start nach dem Speichern, ohne ausdrücklich gestartete projektweite Läufe abzuschalten.

## Projektweite Ausführung

```text
GET  /api/projects/{projectId}/autopilot
POST /api/projects/{projectId}/autopilot/run
```

Der POST-Body kann `requirementIds` und `maxRequirements` enthalten. Der Server kürzt ein Projekt niemals still. Überschreitet die Auswahl die wirksame Anfrage-/Betreibergrenze, wird sie abgewiesen und verlangt eine kleinere explizite Auswahl oder eine bewusste Änderung des Betreiberlimits.

## Konfigurationstabelle

| Umgebungsvariable | Standard / Prüfung | Wirkung |
|---|---|---|
| `TAXONOMY_AI_COST_POLICY` | `METERED`; Enum | Kostenerklärung des Betreibers. Autopilot verlangt `UNMETERED`, manueller Copilot nicht. |
| `TAXONOMY_AI_COPILOT_PROFILE` | `FULL` | Standardprofil manuell gestarteter Operationen. |
| `TAXONOMY_AI_AUTOPILOT_PROFILE` | `EXHAUSTIVE` | Profil unbeaufsichtigter Operationen. |
| `TAXONOMY_AI_COPILOT_VERIFICATION_PASSES` | `1`; gültig 1–3 | Manueller Standard vor Anwendung des Profilminimums. |
| `TAXONOMY_AI_AUTOPILOT_VERIFICATION_PASSES` | `2`; gültig 1–3 | Unbeaufsichtigter Standard; `EXHAUSTIVE` verlangt weiterhin mindestens 2. |
| `TAXONOMY_AI_AUTOPILOT_MAX_ARCHITECTURE_NODES` | `50`; mindestens 1 | Knotengrenze der Architektursichten des manuellen Copiloten und Autopiloten; zusätzlich durch `TAXONOMY_LIMITS_MAX_ARCHITECTURE_NODES` begrenzt. |
| `TAXONOMY_AI_AUTOPILOT_ENABLED` | `false` | Erste ausdrückliche Freigabe unbeaufsichtigter Ausführung. |
| `TAXONOMY_AI_AUTOPILOT_PROVIDER` | leer | Expliziter, vollständig konfigurierter Autopilot-Anbieter passend zur `UNMETERED`-Erklärung. |
| `TAXONOMY_AI_AUTOPILOT_ON_REQUIREMENT_SAVE` | `true` | Startet bei vollständiger Bereitschaft eine unbeaufsichtigte Operation für eine neu gespeicherte unveränderliche Anforderungsversion. |
| `TAXONOMY_AI_AUTOPILOT_PROPOSE_SOLUTIONS` | `true` | Erzeugt bei Nicht-`STANDARD`-Autopilot-Läufen deterministische Lösungsverknüpfungen im Zustand `PROPOSED`. |
| `TAXONOMY_AI_AUTOPILOT_PROPOSE_PRODUCTS` | `true` | Erzeugt bei Nicht-`STANDARD`-Autopilot-Läufen deterministische Produktvorschläge im Zustand `CANDIDATE`. |
| `TAXONOMY_AI_AUTOPILOT_MAX_PROJECT_REQUIREMENTS` | `50`; gültig 1–500 | Maximale projektweite Auswahl; Überschreitungen werden abgewiesen und nie still gekürzt. |
| `TAXONOMY_AI_PRODUCT_PROPOSALS_MINIMUM_COVERAGE` | `25`; auf 0–100 begrenzt | Minimale überlappende bestätigte Taxonomieabdeckung eines deterministischen Produktvorschlags. |
| `TAXONOMY_AI_PRODUCT_PROPOSALS_MINIMUM_CONFIDENCE` | `0.25`; auf 0–1 begrenzt | Minimaler Anteil bestätigter Lösungsknoten, die das Produkt abdeckt. |
| `TAXONOMY_AI_MAXIMUM_RUNTIME_SECONDS` | `1800`; mindestens wirksam 60 | Maximale Koordinator-Wartezeit je Durchlauf. Der persistierte Job bleibt anschließend wiederaufnehmbar. |
| `TAXONOMY_AI_COORDINATOR_MAX_CONCURRENT_OPERATIONS` | `4`; gültig 1–64 | Zahl verschiedener parallel koordinierter Copilot-/Autopilot-Operationen. Parallelisiert keine Durchläufe derselben Operation. |
| `TAXONOMY_AI_COORDINATOR_QUEUE_CAPACITY` | `100`; gültig 1–10000 | In-Memory-Koordinatorwarteschlange. Nach Kapazitätsabweisung bleiben persistierte Operationen wiederaufnehmbar. |

Der Portfolio-Analyse-Worker ist ein getrennter Ausführungspool. Seine Einstellungen sind `TAXONOMY_PORTFOLIO_ANALYSIS_WORKER_CONCURRENCY`, `TAXONOMY_PORTFOLIO_ANALYSIS_WORKER_QUEUE_CAPACITY` und `TAXONOMY_PORTFOLIO_ANALYSIS_WORKER_SHUTDOWN_SECONDS`. Eine höhere Koordinatorgrenze beschleunigt keinen einzelnen Anbieteraufruf und kann den Rate-Limit-Druck erhöhen.

Wirksame Bereitschaft und Begründung sind hier sichtbar:

```text
GET /api/ai-automation
```

## Menschliche Freigabegrenze

Der Autopilot bereitet Nachweise und Vorschläge vor. Er darf nicht selbstständig:

- Taxonomie- oder Relationszuordnungen bestätigen;
- organisatorische Verantwortung verbindlich festlegen;
- ein Produkt auswählen oder eine Beschaffung autorisieren;
- eine freigegebene Architektur überschreiben;
- einen Entwurfszweig in einen freigegebenen Zweig mergen.

Erzeugte Lösungen bleiben `PROPOSED`, Produkte bleiben `CANDIDATE`. Jede verbindliche Entscheidung benötigt eine menschliche Freigabe.
