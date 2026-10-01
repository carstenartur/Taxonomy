# QA: Analyseumfang, Relationen und Ressourcenverbrauch

Stand: 2. Oktober 2026. Basis: `d6ecb6b16699270fc476ca2fe1e6e21d5e4a816c`.
Branch: `refactor/analysis-scope-performance`.
Review: [PR #1160](https://github.com/carstenartur/Taxonomy/pull/1160).

## Ergebnis und Reichweite

Die automatische Analyse kann ausgewählte Teiltaxonomien mit oder ohne Relationen
bewerten. Doppelte rekursive Bewertungslogik ist zusammengeführt. Die Relationensuche
vermeidet wiederholte Katalogzugriffe, doppelte Dekodierung frischer Antworten und
vollständige Aufgaben-Durchläufe für jeden Fortschrittsbericht. Ein zusätzlicher
Fehler bei gefilterten Export-Hierarchien ist behoben.

Untersucht wurden Analyse, Wiederaufnahme, Provider-Zugriff, Katalogzugriff,
Browser-Zustand, Portfolio, Versionshistorie und Export. Dies ist keine vollständige
Sicherheitsprüfung aller Produktfunktionen und kein Vergleich realer Sprachmodelle.

## Bedienung und API-Vertrag

Die Wurzelauswahl und „Nur Taxonomien“ gelten für automatische Analyse und Copilot.
Interaktive Einzelbewertungen und manuelle Bewertungen behalten ihren Ablauf.

```json
{
  "businessText": "Die Anforderung …",
  "analysisScope": {
    "taxonomyRoots": ["BP", "CP"],
    "mode": "TAXONOMIES_ONLY"
  }
}
```

- Fehlender Scope oder leere API-Wurzelliste bedeutet weiterhin alle Taxonomien;
  fehlender Modus bedeutet `FULL`. Explizit alle Browser-Checkboxen abzuwählen
  ist ungültig und startet keinen Auftrag.
- Unbekannte, leere und null-Wurzelkennungen werden vor der Aufnahme abgelehnt.
  Die unveränderliche, kanonische Auswahl gehört zur Auftragsidentität.
- Ausgewählte Wurzeln begrenzen Bewertung und Relationsquellen. Im Modus `FULL`
  bleiben passende Relationsziele aus anderen Taxonomien erreichbar.
- `TAXONOMIES_ONLY` überspringt Relationssuche, heuristische Generierung,
  Hypothesenpersistenz und relationenabhängige Architekturableitung.
- Der gesamte Katalog bleibt navigierbar. Unausgewählte Knoten erhalten keine
  erfundenen Nullbewertungen. Fortschritt und Abdeckung beziehen sich auf den Scope.
- Ergebnisumfang und nächste Einstellungen bleiben getrennt. Retry, Continue,
  Cancel, Wiederherstellung und JSON-Export/-Import erhalten den eingefrorenen Scope.
  Neue manuelle/interaktive Evidenz entfernt den vorherigen Ergebnisumfang.
- Begrenzte Copilot-Läufe erzeugen keine globalen Lücken-/Empfehlungsaussagen,
  die eine vollständige Bewertung voraussetzen.

Der bisherige SSE-Endpunkt bleibt eine Taxonomiebewertung ohne neue Relationsphase.
Seine Standard-Reihenfolge und partielle Evidenz bleiben erhalten; die normale
automatische Oberfläche verwendet weiterhin den vollständigen POST-Anwendungsfall.

## Behobene Befunde

| Befund | Änderung | Nachweis |
|---|---|---|
| Automatische Analyse nicht gezielt begrenzbar | Typisierter Scope durch UI, Commands, Bewertung, Recovery und Austausch | Auswahl, Ablehnung, Phase-Gating, Wiederaufnahme und Austauschtests |
| Doppelte rekursive Voll-/Streaming-Bewertung | Gemeinsamer Algorithmus mit optionalem Callback | Produkt-, Fehler-, Streaming- und Scope-Tests |
| Wiederholte Kindabfragen bei Relationsnavigation | Unveränderliche skalare Listen je Suche, auch leere Listen | Wiederholte Navigation und neue unabhängige Suche |
| Frische Antwort zweimal dekodiert | Validiertes Ergebnis lokal wiederverwenden; Replay weiter validieren | Gültige, ungültige und wiederaufgenommene Antworten |
| Fortschritt scannt alle Aufgaben erneut | Inkrementelle Zähler je Zielwurzel, Index je Quelle | Restore, Abschluss, ungelöste Aufgaben und Neubewertung |
| Gefilterte Vorfahren hinterlassen ungültige Export-Eltern | Nächster überlebender Vorfahr, Zyklenschutz, keine Quellmutation | Fünf Regressionen mit tatsächlicher ArchiMate-/Visio-Validierung |
| Scope überlebt Ersatz durch manuelle/interaktive Bewertung | Ergebnisumfang und Label zurücksetzen, nächste Auswahl behalten | Drei zusätzlich im Review reproduzierte Regressionen |
| Scope-Validierung führt eine neue Controller-Abhängigkeit auf Katalog-Entities ein | Skalare Wurzelkennungen über `TaxonomyService.getRootCodes()` | 22 Controller-Tests und 23 Architektur-Ratchet-Tests; Baseline unverändert |

## Relationslauf: Messwerte und Wartezeit

Die deterministischen Tests vergleichen genaue Prompts, Reihenfolge, Trace, Kanten,
logische Aufrufe und abschließenden Fortschritt. Laufzeitfelder sind von der
Ergebnisgleichheit ausgenommen. Promptinhalt und Batchgrößen bleiben unverändert.

| Messung im Fixture | Vorher | Nachher |
|---|---:|---:|
| Lesen derselben Kindliste pro Suche | 3 | 1 |
| Beschreibungszugriffe | 5 | 3 |
| Dekodieren einer frischen Antwort | 2 | 1 |
| Aufgaben-Durchläufe bei 20 Fortschrittsabfragen | 20 | 0 |
| Logische Modellaufrufe mit Kindern | 13 | 13 |
| Logische Modellaufrufe ohne Kinder | 7 | 7 |

Das belegt weniger lokale Arbeit, **keinen bestimmten Ende-zu-Ende-Speedup**
beim realen Provider. Der Cache enthält keine JPA-Entitäten und lebt nur je Suche.

Der größere Zeitanteil bleibt bei Modell und Suchprotokoll. Jeder extrahierte
Beitrag navigiert kompatible Zielrouten; auch abgelehnte Routen kosten Aufrufe.
Jeder Treffer benötigt eine gesonderte Verifikation. Ein erschöpftes Aufrufbudget
ist eine unvollständige Suche, keine negative Gesamtbewertung.

Bei Gemini sind standardmäßig fünf Anfragen pro Minute konfiguriert. Selbst mit
anfangs leerem Limiter kann die letzte von 24 physischen Anfragen frühestens nach
vier Minuten starten. Bewertung, gemeinsame Aufträge und Retries können weitere
Wartezeit hinzufügen. Andere Provider und Installationen haben andere Grenzen.
Die gemeinsame Transportgrenze erlaubt standardmäßig vier gleichzeitige Requests,
während ein Relationslauf seriell arbeitet. Logische Aufrufe sind wegen Retries
nicht identisch mit physischen HTTP-Versuchen.

Quellstellen: `LlmGatewayRegistry`, `LlmRequestAdmission`, `ProviderRequestLimiter`,
`RelationSearchEngine` und `RelationSearchProtocol`.

### Größere Beschleunigung ohne riesige Prompts

Kleine Navigations-/Verifikationsaufgaben mit gleichem Kontext gezielt bündeln,
bei festen Kandidaten- und Tokenlimits. Für ähnliche Ergebnisqualität braucht es
eine Referenzmenge mit positiven, negativen, mehrdeutigen und taxonomieübergreifenden
Fällen. Abnahme: Precision/Recall, Evidenztreue, Aufrufe, Tokens sowie P50/P95-Zeit.

Begrenzte Parallelität benötigt einen Scheduler mit kontrollierter Budgetvergabe,
geordnetem Ergebnis-Commit und Sperre für verspätete Antworten abgebrochener Tasks.
`AnalysisRunControl`, Checkpoint-Session und Provider-Kontext sind threadgebunden.
Ein unvorbereiteter `parallelStream()` gefährdet Abbruch, Mandantentrennung,
Wiederaufnahme und Budgets. Die parallele Reformulierung ist eine mögliche
Implementierungsreferenz; sie ersetzt die relationsspezifischen Verträge nicht.
Quoten wurden nicht erhöht; dieser größere Protokollumbau ist nicht Bestandteil.

## Generalisierung und Gang-of-Four-Muster

| Grenze | Sinnvolles Muster | Entscheidung |
|---|---|---|
| Provider | `LlmGateway` als Strategy | Beibehalten, Transportdetails getrennt |
| Promptbudget | `PromptBudgetEnforcingLlmGateway` als Decorator | Schutz unabhängig vom Provider |
| Ausgabetransport | Ereignis-Callbacks, Observer-ähnlich | Gemeinsame Bewertung, optionale Benachrichtigung |
| Diagrammauswahl | `DiagramSelectionPolicy` als Strategy | Hierarchiereparatur einmal an dieser Grenze |
| Portfolio-PDF | Adapter delegiert an neutralen Export-Renderer | Gleichnamige Klassen sind keine Rendering-Duplikate |
| Aufträge | Typisierte Commands und Use Cases | Scope als Wertobjekt, Transaktionsgrenzen bewahren |

Eine allgemeine Workflow-/CRUD-Basisklasse wäre keine Verbesserung. Ähnliche
Methoden haben unterschiedliche Mandanten-, Repository-, Claim- und
Transaktionsgrenzen. Wiederverwendung lohnt sich für gleiche Algorithmen und
Wertvalidierungen. Die Anzahl eingesetzter Muster ist kein Qualitätsmaß.

`LlmService` bleibt groß. Die gemeinsame Traversierung beseitigt einen konkreten
Driftpunkt; weitere Extraktion sollte Scoring, Traversierung und Ergebniserzeugung
trennen, nicht nur eine gewünschte Dateilänge erreichen.

## Weitere bestätigte, noch offene Befunde

Absolute Zeit-/Heap-Wirkungen sind ohne Lastprofil nicht gemessen.

| Priorität | Befund | Korrektur und Abnahmekriterium |
|---|---|---|
| P2 | `ProjectPortfolioService.listProjects → toProjectView`: drei Zählabfragen je Projekt, mindestens `1 + 3P` | Scope-gebundene Bulk-Aggregate; Statementzahl bei 1/20 Projekten vergleichen |
| P2 | `SolutionPortfolioService.toProjectSolutionView`: Links, Coverage und Kandidaten je Lösung; `PortfolioAggregationService.build` liest erneut | Bulk-Lesemodell je Projekt für Views/Matrizen; Sortierung und Tenant-Grenzen erhalten |
| P2 | `CommitIndexService.aggregateElementHistory` aggregiert nur 50 relevanzsortierte Treffer | Count/Min/Max über dieselbe vollständige Treffermenge, fünf zeitlich neueste Meldungen; >50 Treffer und fremden Tenant testen |
| P2 | `CommitIndexService.indexBranch/indexCommits` materialisiert Historie und fragt Existenz je Commit | Vorhandene IDs gesammelt lesen, Historie iterieren; Lücken und Merge-Vorfahren weiter reparieren |
| P2 | `TaxonomyService.getFingerprintTree` lädt volle Entities einschließlich eager Embedding-LOB | Schmale skalare Projektion; identischer Fingerprint, Orphan-/Zyklusprüfung, keine Embedding-Spalte |
| P3 | Wiederholte einfache Portfolio-Validierung und Exportkonstanten | Kleine gemeinsame Validatoren/Konfiguration nur bei identischer Semantik |

384 Embedding-Dimensionen ergeben allein `384 × 4 = 1.536` Bytes je dekodiertem
Float-Array, zuzüglich Verwaltung. Das ist eine strukturelle Abschätzung, keine
Heap-Messung. Die Fingerprint-Projektion darf keine Änderungen durch einen TTL-Cache verdecken.

Caches in `DslGitRepositoryFactory` und `WorkspaceManager` benötigen eine
Lebenszyklus-/Heap-Untersuchung. Ohne Ownership-/Lease-Vertrag ist automatische
Eviction aktiver Repositories riskant; ein Leak ist damit noch nicht bewiesen.

## Verifikation und Grenzen

Ein unabhängiges Review prüfte Verhalten, Recovery, Scope-Lebenszyklus,
Relationsoptimierungen und Export. Die dabei reproduzierten drei veralteten
Scope-Anzeigen wurden mit Regressionstests behoben. Anschließend wurde der Stand
nach einem Verlust der Ausführungsumgebung aus protokollierten Patches
wiederhergestellt und remote gesichert. Die Nachweise der neuen Ausführung sind:

| Prüfung | Ergebnis |
|---|---|
| UI-Verträge, wiederhergestellter Stand | 712 Tests, keine Fehler oder übersprungenen Tests |
| Breite lokale Java-Wiederholung bis einschließlich Portfolio | 3.784 Tests, keine Fehler oder übersprungenen Tests; Details unten |
| Scope-Antwortformat | 4 Tests grün nach Korrektur der Testattrappe auf den bestehenden Child-Assessment-Vertrag |
| Controller nach Korrektur der Architekturgrenze | 22 Tests grün |
| Architektur-Ratchet, Katalog-Fingerprint und Recovery-Austausch nach derselben Korrektur | 38 Tests grün: 23 + 9 + 6 |
| CI am Zwischenstand `b028019f` | Alle sechs Browser-Shards, UI-Verträge, Interoperabilität, Observability-Budget, Kubernetes-Smoke, Security-Scan und CodeQL erfolgreich |
| Kanonischer Anwendungstest am Zwischenstand `b028019f` | 2.259 Tests, genau ein Fehler: neue Controller/Entity-Abhängigkeit; anschließend wie oben korrigiert und gezielt nachgeprüft |

Die 3.784 lokalen Tests verteilen sich auf Tooling 161, Domain 208, DSL 332,
Extension API 2, Export 382, Workspace 757, Templates 127, Interop 133,
Knowledge 475, Architecture 167, Analysis 696 und Portfolio 344. Die 8
deterministischen Relations-Performance-Tests sind in Analysis enthalten.
Die letzten 60 gezielten Tests prüfen die nach diesem breiten Lauf vorgenommene
Korrektur der Controller-Grenze; sie sind kein zusätzlicher vollständiger Reactor-Lauf.

Der lokale Anwendungslauf ist **nicht vollständig grün**: Ein Browser-Szenario
benötigt hier nicht verfügbares Docker. Außerdem fand er die inzwischen behobene
Architekturabhängigkeit. Der Lauf wurde zur gezielten Korrektur beendet. Lokale
Chromium-Downloads lieferten keine nutzbare Browserdatei; die Browsernachweise
oben stammen aus GitHub Actions. Qualitätsgrenzen, Architektur-Baselines und
Produktionskonfiguration wurden nicht abgeschwächt.

Der erneute vollständige CI-Lauf nach der letzten Korrektur ist in der
[PR-Prüfliste](https://github.com/carstenartur/Taxonomy/pull/1160/checks) maßgeblich.
Die obigen Zwischenstands-Ergebnisse ersetzen diesen Abschlusslauf nicht.
Ein Draft-Merge-Gate bleibt absichtlich rot, solange der PR ein Entwurf ist.

Projektbefehle für vollständige CI beziehungsweise UI-Verträge:

```bash
./mvnw -B verify -Pci
cd .github
npm ci
npm run verify:ui-contracts
```

Lokal wurden Java 21 und ein Mockito-Premain-Agent verwendet, da dynamisches
Agent-Attachment hier nicht funktioniert. Die Java-Module liefen in getrennten
Maven-Aufrufen mit vorhandenen Reactor-Abhängigkeiten; die gezielten Nachtests
verwendeten explizite Testselektoren. Es wurden keine kostenpflichtigen
Provideraufrufe ausgeführt und keine reale Modellqualität oder Providerlatenz gemessen.
