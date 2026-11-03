# QA: Gemeinsamer Architekturarbeitsbereich — 3. November 2026

## Bestätigter Stand

Die lokale Abnahme bestätigt eine gemeinsame Primärnavigation und den Wechsel
zwischen Analyseentwurf, eingefrorenem Snapshot und Arbeitsarchitektur in der
Anwendung. Der geprüfte Produktstand ist
`4740af975add155868ffddcd5fa9c16caa5c0e37`. Die Teständerungen und die beiden
ausgewählten Browserbilder gehören zu diesem QA-Commit.

Verwendet wurden Java 25 und der festgelegte Chromium 149.0.7827.55. Paketierte
Anwendungsabläufe verwenden den vorhandenen Mock-Provider. Kostenpflichtige
Modellaufrufe wurden nicht ausgeführt. Die native Integrationsklasse rendert
das echte Thymeleaf-Template einschließlich Fragmenten, Übersetzungen, CSS und
Produktionsskripten; nur ihre HTTP-Quelldaten sind deterministische Fixtures.

| Prüfung | Produktstand | Bestätigtes Ergebnis |
| --- | --- | --- |
| `ci-ui-application-package` | `4740af97` | Alle 21 Module gebaut; dies ist ein Paketbau ohne Testausführung. |
| `SharedApplicationChromeBrowserIT` und `ArchitectureWorkspaceBrowserIT` | `4740af97` | 35 + 6 = **41 Tests bestanden**, keine Failures, Errors oder Skips. |
| Vollständige JavaScript-Browserinventur | `4740af97` | **96 Browserfälle + 2 Ownership-Prüfungen bestanden**, keine Fehler oder Skips. Einschließlich DE/EN bei 320 und 390 CSS-Pixeln. |
| Vollständige JavaScript-Verträge | `4740af97` | **1.748 Verträge + 2 Ownership-Prüfungen bestanden**, keine Fehler oder Skips. |
| Vier paketierte Abläufe: ARCHITECT mit erzwungenen Farben, Mobilgerät, USER mit 200-%-Reflow und gewöhnliche Desktop-Analyse | `4740af97` | **8 Maven-Prüfungen bestanden**, zusammen **101 Browserchecks** (32 + 16 + 32 + 21); keine unerwarteten HTTP-, Konsolen- oder externen Browseranfragen. |
| Testkatalog und Navigationsvertrag | `59f3f1f4` | 7 + 3 = **10 Tests bestanden**; Registrierung der neuen Browserklasse und aktualisierte Menüverträge geprüft. |
| Paketierter gewöhnlicher Analyze-Ablauf mit lokaler Lupe, enthalten in den vier Profilen | `4740af97` | Echter `ANALYSIS_DRAFT`-Request mit HTTP 200, exakten Quellkoordinaten, Quellhash und `modelCalls: 0`; Eingabe und angezeigter Graph bleiben unverändert. |

## Nachgewiesene Abläufe

- Die Lupe öffnet den Architekturabschnitt ohne Dokumentwechsel. Eingabetext,
  Analysequelle und Tastaturfokus bleiben erhalten. Die sechs primären Ziele
  kommen genau einmal vor; Fragment-IDs und Skriptreihenfolge sind geprüft.
- Analyse A → gemeinsamer beziehungsweise privater Snapshot B → Arbeitsmodell B
  → Analyse A erhält die jeweiligen Repository-, Workspace- und Branch-Pins.
  Snapshot-Auswahl und Kamera bleiben erhalten. Analysemetadaten übernehmen
  keinen fremden Commit oder Revisionsstand der Arbeitsarchitektur.
- Versteckte Editorentwürfe bleiben geschützt. Angenommene und abgelehnte
  Zurück-/Vorwärtsnavigation halten URL und sichtbare Quelle zusammen.
- Alle fokussierten Primärlinks sind auch mobil vollständig sichtbar und
  behalten ihre 44-Pixel-Ziele. Die bestehenden Eingabe-, Startaktions-,
  Lesbarkeits-, Disclosure- und Seitenüberlaufprüfungen bleiben erhalten.
- Der paketierte ARCHITECT-Ablauf prüft den unveränderten No-op-Preview,
  13 aufgezeichnete Editorbefehle, lokale Vorschlagsannahme mit idempotenter
  Wiederholung und Undo, veraltete Revisionen, getrennte Historien,
  Checkpoint-Recovery sowie Snapshot-Adoption mit Preview, Replay, Undo/Redo
  und Rückkehr zum ursprünglichen Anforderungstext. Der neue Auswahl-Guard
  wird durch seinen exakten Dialog ausdrücklich bestätigt.
- Eine echte lokale Untersuchung des eingefrorenen Snapshots liefert HTTP 200,
  die unveränderte Quelle und `modelCalls: 0`. Ihr Quellhash lautet
  `3a535466d3afd4999092f27cc0eb9c78f5b8db9f9a58cc9ac3cb070370eca81e`.
  Untersuchung und Vorschlagsanzeige verändern das eingefrorene Modell nicht.
- Der gewöhnliche Desktop-Analyze-Ablauf bestätigt ebenfalls die lokale Lupe auf
  dem tatsächlich gespeicherten Analyseentwurf. Sein Quellhash lautet
  `6fc293b35940d1e418160a5109ec45c8a0f348cea7662063488b539437ed8977`.
  Beide Rollenprofile prüfen jeweils acht axe-Zustände ohne blockierende Befunde.
  Die 200-%-Prüfung verwendet 720 × 500 effektive CSS-Pixel bei nominell
  1440 × 1000 Pixeln; sie ist ein Reflow-Nachweis, kein bedienter Browser-Zoomknopf.
- Der ARCHITECT-Ablauf prüft tatsächlichen SVG-Export, eingefrorenen
  Interoperabilitätsexport, deutschen Reload und erreichbare Editorkontrollen
  bei 720 und 360 CSS-Pixeln. Die native Snapshot-Prüfung erhält alle sechs
  Exportaktionen und prüft Druckklon, Provenienz, entfernte interaktive
  Kameratransformation, Aufräumen und unveränderten Bildschirm-Viewport.
  Diese Druckprüfung belegt die DOM-Vorbereitung, keine fertige Pagination.

## Ausgewählte echte Browserbilder

Beide Bilder wurden auf `4740af97` durch
`ArchitectureWorkspaceBrowserIT.renderedApplicationHasOneNavigationAndLensOpensAnalysisInPlace`
aufgenommen. Quelle: gespeicherter Analyseentwurf, Version **3**, Repository
`repository-A`, Workspace `analysis-A`, Branch `draft/A`. Die Daten sind
kontrollierte HTTP-Fixtures; Oberfläche und Interaktion sind die
Produktionsimplementierung. Die Bilder wurden nicht nachbearbeitet.

### Deutsch, 390 × 844 CSS-Pixel

![Architektur-Lupe mit sichtbarer Analysequelle und Tastaturfokus bei 390 Pixeln](images/unified-architecture-workspace-2026-11-03/analysis-lens-de-390.png)

### Englisch, 1440 × 1000 CSS-Pixel

![Architecture focus lens und Analysegraph im gemeinsamen Arbeitsbereich](images/unified-architecture-workspace-2026-11-03/analysis-lens-en-1440.png)

## Noch ausstehende oder begrenzte Evidenz

Die vier oben genannten paketierten Profile sind vollständig abgeschlossen.
Die vollständigen CI-Gates und ihre Bindung an den jeweils aktuellen Quellstand
sind anhand der Artefakte von [PR #1367](https://github.com/carstenartur/Taxonomy/pull/1367)
zu beurteilen. CSS-Breitenprüfungen werden nicht als ungeprüfter Browserzoom
oder als vollständige Screenreaderprüfung ausgegeben.

Der lokale kanonische Maven-Lauf stoppte nach neun grünen Modulen im
unveränderten nativen GPG-Test: Der benötigte AF_UNIX-Socket wurde mit
`Operation not permitted` abgelehnt. Im Workspace-Modul bestanden 1.243 Tests,
einer scheiterte; die nachfolgenden Module dieses vollständigen Laufs wurden nicht
ausgeführt. Der Vollgate ist daher **nicht lokal grün**. Docker-, Helm- und
LibreOffice-Prüfungen wurden hier nicht ausgeführt.

Zwei vorangegangene Versuche wurden während voller temporärer Platte durch
Chromium-Abstürze beziehungsweise Schreibfehler invalidiert und nicht als
bestandene Abnahme gezählt. Der oben genannte Paketbau, die 41 nativen Fälle
und der ARCHITECT-Ablauf wurden danach mit stabiler Runtime vollständig
ausgeführt. Vollständige Logs und Bildserien bleiben in den Test-/CI-Artefakten.
