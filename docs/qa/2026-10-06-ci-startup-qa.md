# QA-Fortsetzung: CI-Vertrag und sicherer Integrationsstart

Ausgangsstand: `77b145288d189ac8a5ec076ae67352a5474ccdef`, PR #1177.
Dieser Nachtrag behebt einen veralteten Testvertrag und eine echte Startzustandslücke.
Er ersetzt nicht die historischen Nachweise der vorangegangenen QA-Berichte.

## Abgeschlossene CI des Ausgangsstands

Im [CI/CD-Lauf 37507757671](https://github.com/carstenartur/Taxonomy/actions/runs/37507757671)
bestanden der Core-Reaktor mit der kanonischen Maven-Prüfung, die Anwendungspaketierung,
die Interoperabilitätsprüfung und die OpenTelemetry-Prüfung. Die UI-Vertragsprüfung
scheiterte jedoch am Test `short-height task stages stay labelled without a separate horizontal scroll region`.
Dadurch wurden die Browser-Testgruppen in diesem Lauf übersprungen; der übergeordnete
Maven-Status war rot. Die separaten Datenbank-, Dokumentvorlagen-, Kubernetes- und
Reformulierungsprüfungen des Ausgangscommits bestanden.

Der [Security-Lauf 37507757519](https://github.com/carstenartur/Taxonomy/actions/runs/37507757519)
scheiterte vor dem Scanner beim Auflösen der Build-Abhängigkeiten: Maven Central
lieferte für `structurizr-dsl:6.2.3` HTTP 500 und für `javaparser-core:3.28.2` HTTP 502.
Das Artefakt `security-packaging-log` (11431983825) enthält diese Fehler.
Dies ist kein nachgewiesener Schwachstellenbefund, aber auch kein bestandener
Security-Scan. Scannerregeln, Versionen und Sicherheitsgates bleiben unverändert.

## 1. Der Layouttest muss den verbesserten Umbruch zulassen

Der ältere Copilot-Test verlangte weiterhin vier feste Spalten im niedrigen
Viewport. Das widersprach der bereits geprüften Anpassung an die tatsächliche
Analysebreite. Er prüft jetzt das begrenzte `auto-fit`-Raster und verbietet
weiterhin feste Vier-Spalten-Raster für die Stufenanzeige. Die bisherigen
Prüfungen auf Beschriftung, Schriftgröße, Wortumbruch und fehlenden horizontalen
Scrollbereich bleiben erhalten. Produktions-CSS wurde hier nicht zurückgebaut.

Der betroffene Test scheiterte lokal vor der Anpassung. Anschließend bestanden
alle vier separat ausgeführten CSS-Prüfkörper aus der tatsächlichen Testdatei,
einschließlich der Nachbarprüfungen für extreme Vergrößerung, mobile Ergebniszeilen
und Schaltflächenkontrast. Dies war kein vollständiger Lauf der Copilot-Testdatei.

## 2. Integrationsaktionen waren zu früh aktiv

Im [Szenariolauf 37507757422](https://github.com/carstenartur/Taxonomy/actions/runs/37507757422)
las `ScenarioBrowserWalkthrough.inspectPublicationControls` nach dem deutschen
Neuladen leere Ergebniszahlen, obwohl der Knopf zum Fortsetzen bereits anklickbar
war. Das anschließend gespeicherte Fehler-HTML enthielt die richtigen Zahlen.

Die Ursache ließ sich mit dem tatsächlichen Template und Produktionsskript
reproduzieren: Die Aktionsknöpfe waren bereits im initialen HTML aktiv.
`integrations.js` wartete erst auf die Übersetzungen, bevor es den Ladezustand
setzte und Verbindung sowie Vorgang las. Somit konnte ein Benutzer oder ein
Browsertest einen bedienbaren Knopf vor einem geladenen Vorgang vorfinden.

Das Template liefert seine Aktionsknöpfe und den Verbindungsschalter jetzt
initial deaktiviert aus. Eine kleine Initialisierungssperre verhindert außerdem,
dass frühe Eingabeereignisse die Knöpfe wieder aktivieren oder vorzeitig Requests
starten. Nach den Übersetzungen übernimmt der vorhandene Busy-/Berechtigungs-/
Vorgangsvertrag. Fortsetzen ist erst nach dem Laden der zugehörigen Identität,
Zustände und Ergebniszahlen möglich. Reine Leser erhalten dadurch keine
Schreibberechtigung. Ein Übersetzungsfehler bleibt sichtbar und lässt die
Aktionen gesperrt. Die Oberfläche benötigt keine zusätzlichen REST-Aufrufe,
Timer oder Wiederholungsversuche. Der Szenariotest selbst wurde nicht abgeschwächt.

## Nachweise und Grenzen

Sechs neue Node-Tests führen das echte Produktionsskript gegen kontrollierte
DOM-/API-Grenzen aus. Drei reproduzierten vor der Korrektur den Defekt; danach
bestehen alle sechs. Erfasst sind initiales HTML, verzögerte Übersetzungen,
frühe Ereignisse, verzögerte Vorgangsdaten, Übersetzungs- und Profilfehler,
Leserechte sowie eine leere Verbindungsliste. Sie werden im bestehenden
`test:integrations-review` ausgeführt und bleiben Bestandteil beider UI-Gesamtgates.

Die ergänzende Offline-Chromium-Prüfung reproduzierte den verfrüht aktiven
Fortsetzen-Knopf sowohl auf Deutsch als auch auf Englisch. Alle sechs korrigierten
Browserfälle bestanden: je Sprache ein erfolgreicher verzögerter Start, ein
reiner Lesezugriff und ein Übersetzungsfehler. Sie verwenden das tatsächliche
Template, CSS, Skript und die vorhandenen Sprachressourcen, ersetzen jedoch
I18n-Bereitschaft und API-Antworten kontrolliert. Das ist weder ein vollständiger
Server-Szenariolauf noch eine neue Axe- oder Screenreader-Abnahme.

Lokal standen Node 22.16.0 und Chromium 144.0.7559.96 bereit. Der Arbeitsbereich
wurde aus dem commitgebundenen CI-JAR und gezielt gelesenen Testdateien aufgebaut;
ein vollständiger Git-Checkout war in dieser Umgebung nicht verfügbar.
Der versuchte lokale Aufruf `npm run verify:ui-contracts` endete deshalb bereits
bei der nicht lokal vorhandenen `scripts/ui-localization-regression.test.mjs`.
Es wird kein neuer vollständiger lokaler Build oder Gesamttestlauf behauptet.
Die neue CI muss genau den veröffentlichten Nachtrag prüfen.

Das CI-Paket 11432697634 gehört zum Test-Merge
`afcf1523bb647e084bc884346d3bb0545e85ae15` und zum Quellbaum
`09fe55aaebb61ae57dcd8ba1317793d901048586`. Seine ZIP-Prüfsumme und die
Original-Blobs der bearbeiteten Dateien wurden abgeglichen. Die Änderungen
sind nicht Bestandteil dieses älteren Pakets. Die neuen Quellprüfsummen und
die eingegrenzten Ergebnisse stehen im [Nachweisinventar](https://github.com/carstenartur/Taxonomy/blob/eaca4233311dc82b6de227d1f402ab5e14c2e07b/docs/qa/evidence/2026-10-06/ci-startup-qa.json).

Die tiefere Speicher-/Latenzmessung großer Analyseergebnisse, das dokumentierte
5.000-Aufträge-Wiederherstellungslimit und eine umfassende manuelle
Barrierefreiheitsabnahme bleiben außerhalb dieses CI-Reparaturnachtrags offen.
Es erfolgten kein Merge, kein Release und kein Deployment.
