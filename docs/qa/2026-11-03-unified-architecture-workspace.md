# QA: Gemeinsamer Architekturarbeitsbereich — 3. November 2026

## Erste Produktabnahme

Die erste lokale Abnahme bestätigt eine gemeinsame Primärnavigation und den Wechsel
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

## Integrationskorrekturen nach dem ersten CI-Lauf

Der erste vollständige CI-Durchlauf hat zusätzliche Integrationsgrenzen
aufgedeckt. Die Korrekturen erhalten die bestehenden Prüfziele und decken die
beobachteten Fehler zusätzlich ab:

- Die automatische Einführung überdeckt keinen direkten Architekturaufruf
  mehr. Ihre manuelle Hilfe und der gewöhnliche erste Analyseaufruf bleiben
  erhalten.
- Eine wieder aufgenommene Arbeitsarchitektur behält ihren exakt gepinnten
  Stand und unbestätigten Formularentwurf. Ein ausdrücklich ungepinnter Aufruf
  liest dagegen den aktuellen Branchstand; ein unbestätigter Entwurf wird dabei
  weiterhin durch die vorhandene Bestätigung geschützt.
- Ein ausdrücklich gewähltes Importziel bleibt beim Wechsel zwischen Analyse
  und Architektur sowie in der Browserhistorie erhalten. Leerer gemeinsamer
  Workspace, privater Workspace und fehlender Pin behalten ihre unterschiedliche
  Bedeutung. Angezeigte Analysedaten, Version, Hash, Kontextanzeige und Workflow
  bleiben an die tatsächliche Analysequelle gebunden.
- Die gespeicherte Herkunft ist mit der Tastatur erreichbar und scrollbar.
  Die neuen REST-Operationen sind im bestehenden OpenAPI-Inventar dokumentiert.
- Lange URLs in Fehlermeldungen der Anforderungsansicht umbrechen innerhalb
  der sichtbaren Meldung. Eine deterministische fehlgeschlagene Versionsabfrage
  prüft die vollständigen Textzeilen; bloßes Abschneiden würde weiterhin
  scheitern. Die übrigen Leseantworten der Fixture sind gültig.
- Die eingebettete Snapshot-Zeichenfläche nutzt die verbleibende Desktop-Höhe.
  Quelle, Workflow und Status bleiben als dieselben DOM-Knoten im scrollbar
  erreichbaren Inspektor erhalten und kehren beim Quellenwechsel an ihre genaue
  ursprüngliche Position zurück. Mobil überlagern die Druck-/Exportaktionen
  den Überschriftentext nicht; alle bisherigen Höhen- und Exportprüfungen
  bleiben bestehen.
- Die Standalone-Quellenparser übernehmen ausschließlich die kanonischen
  Quellfelder. Die Browser-Fixtures verwenden den HTML-Parser statt einer
  unvollständigen Bereinigung durch reguläre Ausdrücke.
- Die tatsächlich hinzugekommenen Architekturabhängigkeiten sind einzeln
  geprüft und dokumentiert. Zusätzliche strikte Wächter sichern die lesende
  Quellauflösung; die Kontextgrenzen und bestehenden Gate-Regeln bleiben
  bestehen.

Frische, getrennte Prüfläufe nach diesen Korrekturen:

| Prüfung | Bestätigtes Ergebnis |
| --- | --- |
| Vollständige JavaScript-Verträge | **1.756 + 2 Ownership-Prüfungen bestanden**, keine Failures, Errors oder Skips. |
| Vollständige JavaScript-Browserinventur | **111 + 2 Ownership-Prüfungen bestanden**, keine Failures, Errors oder Skips. |
| `ArchitectureWorkspaceBrowserIT` und `SharedApplicationChromeBrowserIT` | **10 + 35 = 45 Tests bestanden**, einschließlich tatsächlicher Textgrenzen, vier Snapshot-Viewportgrößen und vollständiger History-/Entwurfsprüfung. |
| `ArchitectureStandSelectionBrowserIT` | **34 Tests bestanden**, einschließlich aller sieben Wiederholungsfälle nach verlorener Dialogantwort. |
| `RestApiDocumentationTest` | **3 Tests bestanden**, tatsächlich **423 REST-Methoden** inventarisiert. |
| `ArchitectureContextDependencyRatchetTest` | **34 Tests bestanden**; zusätzliche mutierte Nur-Lesen-Verletzung wurde zuvor erwartungsgemäß erkannt. |
| Unveränderte `ArchiMateReturnUiIT` | **1 Test mit drei vollständigen Browser-/HTTP-/Datenbankdurchgängen bestanden**: privater Import, idempotente Wiederholung, gemeinsamer schreibgeschützter Preview. |

Nach der deterministischen Fehlerfixture und der Alert-Umbruchkorrektur wurde
die vollständige Navigationsklasse nochmals ausgeführt: **35/35 bestanden**.
Die vollständige Browserinventur wurde auf diesem letzten Produktstand ebenfalls
erneut ausgeführt: **111 + 2 bestanden**, jeweils ohne Fehler oder Skips.

Der Importfall prüft weiterhin genau eine importierte Relation im privaten
Arbeitsbereich des zweiten Repositorys, korrekte Herkunft und unveränderte
Relations-IDs im primären Repository. Die Erfolgsaufnahme zeigt den ausdrücklich
gemeinsamen Zielbereich und deaktivierte Übernahmeaktionen. Die ausgeführten
JavaScript-Assets stimmen mit dem geprüften Quellcode überein.

### Snapshot nach der Layoutkorrektur

Diese unveränderten Browseraufnahmen stammen aus
`embeddedSnapshotUsesRemainingViewportWithoutLosingSourceOrCamera` nach einem
echten Fit-Klick. Quelle: Snapshot **`snapshot-B`**, Repository **`repository-B`**,
gemeinsamer Workspace, Branch **`review/B`**, Projekt **42**, Anforderung **7**,
Anforderungsversion **11**. Es gelten dieselben kontrollierten HTTP-Fixtures und
echten Templates, Skripte und CSS wie oben beschrieben.

Bei 1920 × 1080 Pixeln reicht die Zeichenfläche von y = **273,13** bis
**1071,75**, bei einer Höhe von **798,63 Pixeln**. Der Inspektor bleibt innerhalb
des Viewports und scrollt unabhängig. Bei 390 × 844 Pixeln liegen zwischen
Überschriftentext und Aktionen **12 Pixel**; die Zeichenfläche beginnt bei
y = **584,59** und erfüllt die unveränderte Grenze von 70 Prozent der
Viewporthöhe. Auswahl und Kamera bleiben auch bei anschließendem Resize
erhalten.

![Gespeicherte Architektur mit großer Zeichenfläche und vollständigem Inspektor bei 1920 Pixeln](images/unified-architecture-workspace-2026-11-03/snapshot-layout-en-1920.png)

![Mobile gespeicherte Architektur ohne Überlagerung von Überschrift und Aktionen bei 390 Pixeln](images/unified-architecture-workspace-2026-11-03/snapshot-layout-de-390.png)

## Nachprüfung des vollständigen CI-Stands `92a7452c`

Der [Hauptlauf 41651027183](https://github.com/carstenartur/Taxonomy/actions/runs/41651027183)
ist vollständig ausgewertet: 18 erfolgreiche Jobs, vier fehlgeschlagene Jobs
und zwei übersprungene Jobs. Die Fehler betreffen zwei UI-Gruppen, die native
Browsergruppe und deren abhängiges Maven-Aggregat. Die Coverage-Aggregation
wurde wegen dieser Fehler nicht ausgeführt. Die Dokumentationsgruppe war
aufgrund der bestehenden Änderungsauswahl nicht vorgesehen.

Core, Plugin-Paketierung, Runtime, Domain-Integration, Recovery und die
Anwendungsszenarien waren erfolgreich. Die App-Core-Teilmenge enthält
**3.985 Tests, keine Failures oder Errors und 55 ausgewiesene Skips**; dies ist
keine behauptete Gesamtzahl des Reaktors. Die drei
[Datenbankgruppen](https://github.com/carstenartur/Taxonomy/actions/runs/41651027063)
bestanden **138 Integrationsfälle** ohne Failures, Errors oder Skips:
33 SQL-Server-, 62 PostgreSQL- und 43 Oracle-Fälle.

Der [Push-CodeQL-Lauf](https://github.com/carstenartur/Taxonomy/actions/runs/41651014238)
ist für Java und JavaScript erfolgreich. Die neun ursprünglichen
JavaScript-Befunde sind in den weiterhin aktiven Regeln nicht mehr vorhanden;
es wurden keine zusätzlichen Baseline-Ausnahmen zugelassen. Ebenfalls
erfolgreich waren der tatsächliche Word-Download, Reformulierungsabrechnung,
Security Scan, Kubernetes-Smoke und der JGit-Speicherverbrauchertest.

### Korrekturen aus den Browserbefunden

- **Isolierte Testressourcen:** Der Runner liest Bootstrap und D3 aus derselben
  laufenden paketierten Anwendung und gibt ihre lokalen Dateien ausdrücklich
  an die Browserprüfungen weiter. HTTP-Status, Inhaltstyp und nicht leere Bytes
  werden geprüft. Ein leerer Browser-Maven-Cache funktioniert damit ebenfalls;
  es wird weder ein CDN noch eine neue Produktabhängigkeit verwendet.
- **Dunkles Exportmenü:** Die globalen Farben für helle Flächen überschrieben
  die beiden Info-Schaltflächen im dunklen Menü. Der eng begrenzte Selektor
  stellt dort lesbare Farben her. Alle sechs aktivierten Exportaktionen werden
  mit dem tatsächlichen globalen CSS geprüft, einschließlich Axe sowie Hover,
  gedrücktem Zustand und Tastaturfokus der beiden betroffenen Aktionen.
- **Historischer Stand:** Der native Adoptionstest klickte als einzige Aktion
  ohne den vorhandenen expliziten Scrollpfad auf einen Historienlink außerhalb
  des sichtbaren Viewports. Dieser
  reproduzierte Fehler verwendet jetzt denselben vorhandenen sofortigen
  Scroll-/nativen Klickpfad wie die übrigen Aktionen. Produktcode, Zeitgrenzen
  und sämtliche Übernahme-, Replay-, Checkpoint-, Undo-/Redo- und
  Neustartprüfungen bleiben erhalten.
- **Kurze Desktop-Inhaltsfläche:** Bei 1024 × 625 Pixeln verlangte die eingebettete
  Workbench zusätzlich zur Werkzeugleiste mindestens `50dvh` Zeichenhöhe.
  Das überschritt ihre korrekt zugewiesene Resthöhe. Nur dieser eingebettete
  Desktop-Fall verwendet jetzt die tatsächlich verbleibende Höhe. Bei der
  reproduzierten Geometrie endet der Canvas nun bei **616,75 Pixeln**, genau
  an der Rasterunterkante, statt bei **628,41 Pixeln** außerhalb des Viewports.
  Die vorhandenen Mobil-, Druck-, Quellen-, Kamera- und Exportprüfungen bleiben
  unverändert; zwei zusätzliche kurze Größen verwenden das ursprüngliche
  Layout-Orakel.

| Frische lokale Nachprüfung | Bestätigtes Ergebnis |
| --- | --- |
| Testressourcen-Verträge | Erwarteter RED-Befund, anschließend **28 Verträge + 2 Ownership-Prüfungen bestanden**. |
| Vollständige Browserinventur mit leerem Browser-Maven-Cache | **111 + 2 Ownership-Prüfungen bestanden**, keine Failures, Errors oder Skips. |
| Frischer Anwendungsbau | Alle **21 Module** paketiert; Testausführung bei diesem Paketbau ausdrücklich ausgeschaltet. |
| Vollständiger paketierter ADMIN-Ablauf | **2 Maven-Prüfungen**, **74 Browserchecks**, **21 Axe-Zustände**, keine blockierenden Befunde oder Auditfehler; einschließlich des zuvor fehlgeschlagenen Exportmenüs und aller folgenden Schritte. |
| Paketierter Textabstands-/Offline-Ablauf | **2 Maven-Prüfungen**, **7 Browserchecks** und die vorgeschalteten **111 Browserfälle** bestanden. Bootstrap und D3 wurden tatsächlich aus dem frisch paketierten Server gelesen und entsprechen seinen WebJar-Bytes. |
| Historischer Adoptionstest und neuer nativer Vollbildfall | **2/2 Maven-Fälle bestanden**, keine Failures, Errors oder Skips, mit Chrome Headless Shell **150.0.7871.124** und passendem Driver. |
| Vollständige `ArchitectureWorkspaceBrowserIT` nach der Höhenkorrektur | **13/13 Maven-Fälle bestanden**, keine Failures, Errors oder Skips, mit derselben 150er Runtime: bisherige Quellen-, History-, Entwurfs-, Lupen-, Druck- und Größenprüfungen sowie die neuen Kurzviewport- und Vollbildfälle. |

Der vorherige eigenständige
[Architektur-Szenariolauf](https://github.com/carstenartur/Taxonomy/actions/runs/41651034467)
scheiterte beim Vollbildeintritt. Dieser Fehler ließ sich lokal weder mit der
kleinen nativen Fixture noch mit seiner originalen gespeicherten Projektion
von **50 Knoten und 36 Beziehungen** reproduzieren. Die tatsächliche Folge aus
Suche, Auswahl, Fokus, Übersicht, Kontextfilter, Fit, Zoom und Vollbild bestand
mit Chrome Headless Shell 150 bei 1600 × 1100 und 1600 × 957 Pixeln. Quelle,
Daten und Auswahl blieben erhalten. Diese Proben ersetzen den ursprünglichen
vollständigen CI-Ablauf nicht und belegen noch keine Ursache für dessen Fehler.

Der ursprüngliche Ablauf protokolliert deshalb zusätzlich die nativen
Vollbildereignisse, Browser-Policy, Aktivierung, Fokus und Ablehnungsgründe.
Die bisherigen Wartezeiten bleiben bestehen. Layoutbilder werden bereits
vor dem Zurücksetzen der Fenstergröße gesichert; die nativen Fehler- und
Geometrieartefakte sind im bestehenden CI-Upload enthalten. Normales Chrome
150 konnte lokal wegen der AF_UNIX-Socketbeschränkung nicht gestartet werden;
die erfolgreiche Ausführung ist ausdrücklich der Headless Shell zugeordnet.

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
ausgeführt. Ein weiterer kombinierter Lauf wurde durch einen Neustart der
Arbeitsumgebung unterbrochen und nicht als bestanden gewertet. Die oben unter
Integrationskorrekturen genannten Nachprüfungen wurden anschließend mit frischer
Runtime ausgeführt. Die ausgewählten Bilder sind eingecheckt; die vollständigen
aktuellen CI-Protokolle und Artefakte gehören zum jeweiligen PR-Stand.
