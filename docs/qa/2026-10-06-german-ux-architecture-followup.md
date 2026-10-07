# QA-Folgeprüfung: deutsche Oberfläche, Diagrammbedienung und Architektur — 6. Oktober 2026

## Ergebnis und Abgrenzung

Diese Folgeprüfung setzt den [ersten QA-Bericht](2026-10-06-german-ux-architecture.md)
und das Review von [PR #1177](https://github.com/carstenartur/Taxonomy/pull/1177) fort.
Sie schließt die dort noch offenen Arbeiten zur Tastaturbedienung der vier
Taxonomie-Diagrammrenderer, zum lokalen Einpassen, zum Workbench-Seitendruck und
zur Liste jüngster Analysen. Außerdem behebt sie zwei zusätzliche
Export-Abbruchlücken, die erst beim Prüfen der vollständigen Aufrufketten sichtbar
wurden.

Die Zahlen, Prüfsummen und Abschlussaussagen im ersten Bericht beschreiben dessen
historischen Prüfstand. Sie werden durch diesen Folgebericht nicht nachträglich
umgeschrieben. Die nachstehenden Änderungen liegen im Folgearbeitsstand auf
`qa/de-ux-architecture-20261006`; der bisherige PR-Stand hat den Commit
`bd052a0164f339f549149c4f589fc19125ac7cf6`. Ein erfolgreicher Lauf dieses älteren
Commits ist kein Nachweis einer neuen Remote-CI-Prüfung des Folgearbeitsstands.

Der frische abschließende Node-Gesamtlauf hat **889 von 889 Tests ohne Fehler
oder Skips** bestanden. Der abschließende ausgewählte
Maven-Reaktor hat **424 Tests in 29 Klassen ohne Failures, Errors oder
Skips** abgeschlossen; die zuvor fokussiert geprüften 56 Tests einschließlich
der 11 Recent-Projektionsfälle sind darin enthalten und werden nicht addiert.
Auch die anschließende Anwendungspaketierung und die Chromium-Abnahme am fertigen
Paket sind erfolgreich abgeschlossen: **58 Checks einschließlich 20
Axe-Zuständen**, jeweils ohne gemeldete Violations oder Blocking-Findings und
ohne Auditfehler. Die PDF-Prüfung bestätigt den vollständigen Workbenchdruck
mit Graph, Herkunft und langem Requirement. Offen bleibt die neue Remote-CI
nach Veröffentlichung des Folgearbeitsstands; Abschnitt 6 trennt diese von den
abgeschlossenen lokalen Nachweisen.

Das aktualisierte I18n-Inventar umfasst **20 englisch-deutsche Bundle-Paare mit
je 2.488 eindeutigen Schlüsseln**, ohne fehlende Gegenstücke oder Unterschiede
bei nummerierten Platzhaltern. Dies belegt die geprüfte Ressourcenparität,
nicht die vollständige Übersetzung aller sichtbaren Laufzeitinhalte. Die
historischen Inventarzahlen des ersten Berichts bleiben unverändert.

## 1. Entscheidungsberichte: Abbruch bis zum Ende des Bodytransfers

### Live-Analyse: Headers waren zu früh das Ende des Request-Scopes

Der erste Bericht dokumentiert bereits einen korrigierten Signalanschluss beim
Live-Export. Die Folgeprüfung fand eine weitere, davon unabhängige Lücke:
`TaxonomyApiClient.request()` beendete seinen Abort-/Timeout-Scope nach dem
Empfang und der Statusprüfung der Response-Headers. Erst danach las die
Downloadfunktion den Body mit `response.blob()`. Ein Abbruch während dieser
Phase unterdrückte zwar über einen UI-Guard den Download, beendete aber den
laufenden Bodytransfer nicht mehr.

Der zentrale Client besitzt jetzt `requestBlob()`. Diese Methode verwendet
weiterhin dieselben CSRF-, Request-ID-, Status-, Fehler- und Retryregeln, hält
aber die Weiterleitung des Caller-Aborts und den Timeout bis zum Abschluss des
Bloblesens aktiv. Sie gibt Response und Blob gemeinsam zurück; die vorhandene
Reportvalidierung prüft anschließend weiterhin Content-Type, Herkunft und
leeren Inhalt. Der Live-Export nutzt diesen Transport, ohne die zentrale
API-Grenze mit einem eigenen Fetch zu umgehen.

Der Defaulttimeout von 30 Sekunden umfasst bei dieser Methode jetzt auch den
Bodytransfer. Ein längerer, ausdrücklich gewählter `timeoutMillis` bleibt
möglich. Die Raw-Response-Methode `request()` behält ihre bisherige Semantik.

### Gespeicherte Anforderung: Custom-submit verlor das Signal

Der gespeicherte Requirement-Export verwendet einen eigenen Custom-submit im
Copilot-Modul. Dieser verwarf bislang `selection.signal`; der nachgelagerte
Portfolioadapter erhielt keine Abbruchoption, und nach `blob()` folgte ein
unbedingter Download.

Das Signal wird nun durch den echten Custom-submit bis zum bestehenden
Portfolioadapter und dessen Fetch weitergereicht. Guards vor dem Request, nach
der Response, nach dem Bodylesen und unmittelbar am Downloadstart verhindern
auch einen verspäteten Download eines Transports, der trotz Abbruch noch einen
Blob liefert. Ein erwarteter Abbruch erhält den Zustand `CANCELLED` mit
„Export abgebrochen“; die Exportcontrols werden wieder freigegeben.

Die drei ersten neuen Regressionen scheiterten vor der Korrektur an den
beabsichtigten Assertions: fehlender Live-Bodytransportabbruch, fehlender
Requirement-Adapterabbruch und ein unerlaubter verspäteter Download. Danach
bestanden sie. Weitere Fälle prüfen den Timeout nach Headers und die weiterhin
erfolgreichen Downloads beider Pfade. Die bestehende UX-Datei enthält jetzt
20 Tests statt der historischen 14. Die Tests verwenden die realen Module und
den tatsächlichen Requirement-Click-/Submit-Pfad; nur die externen
Transport-/DOM-Grenzen werden kontrolliert ersetzt.

Quellen: [zentraler API-Client](../../taxonomy-app/src/main/resources/static/js/api/taxonomy-api-client.js),
[Live-Browse-Export](../../taxonomy-app/src/main/resources/static/js/core/taxonomy-browse.js),
[Requirement-Custom-submit](../../taxonomy-app/src/main/resources/static/js/portfolio/requirement-copilot.js),
[Portfolioadapter](../../taxonomy-app/src/main/resources/static/js/api/portfolio-api.js),
[Downloadvalidierung](../../taxonomy-app/src/main/resources/static/js/shared/decision-export-dialog.js)
und [UX-Regressionen](../../taxonomy-app/src/test/js/ux-state-recovery.cjs).
Die ergänzenden Reviewhinweise stehen in
[Diskussion 4197114547](https://github.com/carstenartur/Taxonomy/pull/1177#discussion_r4197114547)
und [Diskussion 4197114622](https://github.com/carstenartur/Taxonomy/pull/1177#discussion_r4197114622).

Ein Transportabbruch bleibt von einem serverseitigen Rechenabbruch zu
unterscheiden. Diese Korrektur verspricht keinen sofortigen Stopp bereits
begonnener serverseitiger Berichtserzeugung.

## 2. Vier Taxonomie-Renderer: Tastatur und lokale Diagrammcontrols

Die zuvor hauptsächlich mausbedienten Visualisierungen besitzen jetzt einen
gemeinsamen Vertrag für den aktiven Knoten. Die Tabfolge durchläuft nicht jeden
Diagrammknoten einzeln: Im SVG ist jeweils ein aktiver Knoten ein Tabstop, im
Canvas ist die Oberfläche der einzelne Tastaturtarget. Die nativen lokalen
Bedienbuttons bleiben regulär separat erreichbar.

| Renderer | Bedienung und Zustand |
| --- | --- |
| SVG-Baum | Pfeiltasten, Home/End und Enter/Leertaste erreichen beziehungsweise öffnen und schließen sichtbare Zweige. Beim Einklappen wird der Fokus auf einen weiterhin sichtbaren Vorfahren zurückgeführt. |
| SVG-Entscheidungskarte | Derselbe Vertrag für aktive Knoten und Zweigaktionen; Auswahl und Fokus bleiben bei Rendereraktualisierung nachvollziehbar. |
| Canvas-Baum | Ein Fokusziel kündigt den aktiven Knoten mit Code, Originaltitel und Bewertung an. Der aktive Knoten bleibt beim Verlassen und erneuten Fokussieren der Oberfläche erhalten; Zweige können per Tastatur geschaltet werden. |
| Sunburst | Zweigzoom ist per Tastatur erreichbar. Native Buttons für „zurück“ und die gesamte Taxonomie unterstützen den Rückweg; es bleibt ein aktiver Knotentabstop. |

Baum-, Entscheidungs- und Canvasansicht besitzen lokale Buttons zum Vergrößern,
Verkleinern, Einpassen und Zurücksetzen des Zooms. Einpassen berechnet die
vollständigen lokalen Bounds mit Rand, statt den aktuellen Bildschirmausschnitt
als Gesamtmodell anzunehmen. Die feste bisherige Zoomuntergrenze wird dabei
nicht zur Ursache abgeschnittener großer oder hoher Diagramme. Zurücksetzen
stellt den D3-Transform auf den Ausgangszustand zurück. Die Workbench-Fitprüfung
enthält zusätzlich kleine mobile, sehr hohe und kompakte Graphen.

Die Node-Regressionen prüfen unter anderem einen Tabstop, Knotenwechsel,
Fokuswiederherstellung nach vollständigem Rendererersatz, das Einklappen,
Canvaszustand und die tatsächlichen Zoom-/Fit-Aufrufe. Die Browserfolge verwendet
real gerenderte D3-Diagramme, native Tastenereignisse, Focus und Axe; sie prüft
auch den Rückweg im Sunburst.

Beim Browsernachlauf trat ein weiterer konkreter Kontrastfehler an den neuen
nativen Diagrammbuttons auf: Bootstraps Farbüberblendung erzeugte während
Hover-/Fokuswechseln Zwischenfarben mit gemessenen Kontrastverhältnissen von
**4,48:1 beziehungsweise 2,27:1**. Die Farbänderung erfolgt für diese lokalen
Controls jetzt ohne Crossfade (`transition: none`); der Fix ist auf die
Diagrammcontrols begrenzt. Daraus folgt keine pauschale Kontrastfreigabe aller
Bedienelemente oder Themen.

Quellen: [Renderer und gemeinsame Navigation](../../taxonomy-app/src/main/resources/static/js/core/taxonomy-views.js),
[Diagramm-CSS](../../taxonomy-app/src/main/resources/static/css/taxonomy.css),
[Node-Regressionen](../../taxonomy-app/src/test/js/diagram-keyboard-controls.cjs),
[Workbench-Fitfälle](../../.github/scripts/architecture-workbench-fit.test.mjs)
und [Browser-Tastaturfolge](../../.github/scripts/ui-diagram-keyboard-workflow.mjs).

## 3. Workbench: vollständiger Seitendruck und erreichbare Scrollbereiche

### Eigene Druckansicht der dargestellten Workbench

Der neue lokalisierte Button **Ansicht drucken** bleibt deaktiviert, bis der
bestehende Fitbutton einen geladenen View signalisiert und gültige lokale
SVG-Bounds verfügbar sind. Der Printadapter beobachtet diesen bestehenden
Bereitschaftsvertrag; er verwendet weder Polling noch einen neuen REST-Aufruf.
Der Graphadapter selbst bleibt von Browserdruck und direktem Fetch getrennt.

Ein separater Adapter verarbeitet `beforeprint` und `afterprint`. Damit teilen
sich Button und nativer Browserdruck denselben Aufbereitungsweg. Er klont den
tatsächlich dargestellten SVG-Graphen einschließlich Definitions- und
Styleinhalten. Nur im Clone entfernt er den Zoomtransform, setzt die ViewBox auf
die vollständigen lokalen `getBBox()`-Bounds mit Rand und vergibt eindeutige IDs
mit angepassten Marker-/URL-Referenzen. Interaktive Attribute werden im Clone
bereinigt. Die Original-SVG, Auswahl und der Bildschirmzoom bleiben erhalten.

Die Livequelle bleibt im Drucklayout unsichtbar, aber messbar; sie liegt
außerhalb des Dokumentflusses und verbreitert die Druckseite nicht. Temporäre,
nur beim Druck verwendete Sourcegrößen am Canvas-Shell halten den bestehenden
ResizeObserver vom Wechsel auf einen anderen Viewport ab. Nach dem Druck werden
Clone und temporäre Größen wieder entfernt beziehungsweise zurückgesetzt.

Die Druckseite zeigt Diagramm, echte Heading-/Herkunftsinhalte und KPIs sowie die
aktuelle Auswahl, vollständigen Requirementtext und vorhandene Warnungen.
Gridhöhen, Scrollclipping und die begrenzte Requirementhöhe werden für Print
aufgehoben. Die Inhalte folgen mehrseitig im normalen Dokumentfluss;
Toolbar und Controls erscheinen nicht. Gedimmte Kontextknoten und -kanten werden
im Druck wieder lesbar, während Auswahl- und Suchhervorhebungen bestehen bleiben.

Das Diagramm erscheint auf A4 mit 14 mm Seitenrand, voller verfügbarer Breite,
maximal 160 mm Höhe und `preserveAspectRatio="xMidYMid meet"`. Große dargestellte
Graphen bleiben damit ein vollständiger **Überblick**. Für eine stärker
vergrößerte fachliche Diagrammausgabe bestehen weiterhin die SVG-/PDF-Vektorformate;
der Seitendruck ersetzt deren Dokumenterzeugung nicht.

Die fünf initialen Printregressionen waren vor der Implementierung rot.
Inzwischen bestehen acht Node-Fälle einschließlich Marker-/Style-Erhaltung,
Readiness, ungültiger Geometrie, Wiederholungsdruck und Sourcegrößenrestore.
Die kontrollierte Browservorprüfung verwendet unter anderem zwei weit
auseinanderliegende Knoten, veränderten Zoom, aktive Auswahl und einen
80-Zeilen-Requirementtext. Sie prüft den nativen Printaufruf, vollständige Bounds,
Herkunft und das anschließende Wiederherstellen des Bildschirmzustands. Der
nachfolgende Abschlusslauf am fertigen Anwendungspaket besteht ebenfalls.
Das erzeugte Workbench-PDF hat **vier Seiten und 80.017 Bytes**. Sein nach NFKC
normalisierter Text enthält beide Knoten `QA-01` und `QA-02`, die erste und
letzte Requirementzeile (`01` und `80`), `qa-locale`, `qa-verifikation` und die
englischen Originalinhalte. Die Normalisierung gleicht PDF-Ligaturen aus; sie
ändert keinen gedruckten fachlichen Inhalt.

### Tatsächlicher Folgefehler: Tastaturzugang zu langen Anforderungen

Mit dem langen Requirement meldete Axe
`serious:scrollable-region-focusable`: Sowohl das Detail-Aside als auch der
begrenzte Requirementbereich konnten scrollen, besaßen aber keinen eigenen
Tastaturtarget. Das Aside erhält jetzt `tabindex="0"` mit dem bestehenden
Auswahldetailtitel als Namen. Der Requirementtext erhält ebenfalls
`tabindex="0"`, `role="region"` und den lokalisierten Requirementtitel als Namen.
Beide zeigen bei Tastaturfokus einen sichtbaren **3 px Innenrahmen**; im Druck
wird dieser Rahmen entfernt.

Die Browserregression fokussiert die beiden Bereiche, scrollt den Requirementtext
mit PageDown und prüft eine steigende Scrollposition sowie den sichtbaren
Fokusrahmen. Der nachfolgende Axecheck verwendet den langen Inhalt weiter; die
Regel wird nicht ausgeblendet.

Quellen: [Printadapter](../../taxonomy-app/src/main/resources/static/js/architecture-workbench-print.js),
[Template](../../taxonomy-app/src/main/resources/templates/architecture-workbench.html),
[Workbench-CSS](../../taxonomy-app/src/main/resources/static/css/architecture-workbench.css),
[acht Printregressionen](../../.github/scripts/architecture-workbench-print.test.mjs)
und [Browser-Print-/Scrollfolge](../../.github/scripts/ui-workbench-print-workflow.mjs).

## 4. Jüngste Analysen: Projektion statt vollständiger Snapshotketten

Der erste Bericht hielt für bis zu 50 jüngste Analysen einen N+1-Pfad mit bis zu
etwa 101 Datenbanklesevorgängen fest. Diese Ausgangszahl war eine
Codebestandsaufnahme, keine Latenzmessung. Der neue Regressionstest reproduzierte
vor der Korrektur tatsächlich 101 SQL-Statements. Der korrigierte HSQL-/Hibernate-Test misst
für eine volle Seite mit 50 Ergebnissen **zwei SQL-Statements und null geladene
Entities**. Auch ein wiederholter Aufruf hält dieses Budget ein. Bei einer
leeren Ergebnisliste genügt die erste Abfrage.

Die erste Projektion liest benötigte Runmetadaten und Ergebnis-JSON, aber keine
Command-, View- oder Relation-Plan-Payloads. Die zweite Projektion liest die
Taskstatusfelder für alle ausgewählten Operationen gemeinsam, ohne Taskinput-,
Message- oder Resultpayloads zu laden. Status, Phasen, Taskzusammenfassungen,
Revisionen, Zeiten und Provenienz bleiben mit den bisherigen Einzel-Snapshotviews
kompatibel. Die Reihenfolge ist auch bei gleichen Erstellungszeiten deterministisch.

`getResultStream()` mit `fetchSize=1` verarbeitet und kompaktiert die erste
Projektion zeilenweise. Sie hält damit nicht absichtlich eine gesamte Seite
vollständiger Ergebnis-JSON-Strings zusammen mit allen Vorschauen fest. Die
gemeinsame `ScorePreview` bewahrt höchstens **8.192 Scoreeinträge**, die
vollständige Zahl bewerteter Knoten und die Information über eine gekürzte
Vorschau. Der retained Recent-Snapshot besitzt kein vollständiges Ergebnisobjekt.
Bestehende Aliaspriorität zwischen `scores` und `rawScores`, kanonische
Scorebehandlung und Vorschaukürzung werden mit dem Einzel-Snapshotverhalten
verglichen.

Die SQL-Auswahl bleibt an Owner, Repository-/Workspace-/Branchscope sowie
optionale Projekt-/Requirementfilter gebunden. Zusätzlich prüft die
Projektionsverarbeitung, dass Operation und Autoritäts-/Requirementkontext zu den
selektierten Persistenzfeldern passen. Negativfälle mit fremden oder beschädigten
Operations-, Repository-, Workspace-, Branch-, Projekt- und Requirementidentitäten
werden abgewiesen, statt die Projektion als Umgehung der Autoritätsprüfung zu
verwenden.

Ein gültiges JSON-`null` liefert weiterhin eine leere Scorevorschau. Bei
aktivierter strikter Trailing-Token-Validierung darf dieser Sonderfall die
Dokumentprüfung nicht umgehen: `null {}` wird abgewiesen. Die Prüfung des
äußeren JSON-Dokuments bleibt erhalten, auch wenn der Parser innere Scorefelder
gezielt liest und andere große Ergebnisabschnitte überspringt.

**Verbleibende Kosten:** Das Ergebnis-JSON wird weiterhin pro Run von der
Datenbank übertragen. Beim Lesen einer Scoremap kann vor der Vorschaukürzung
vorübergehend die vollständige Scoremap entstehen. Außerdem kann der konkrete
JDBC-Treiber trotz Fetch-Size-Hinweis intern puffern. Belegt sind das reduzierte
SQL-/Entitybudget und die frühere Kompaktierung; es gibt hier keine zugesagte
Heapobergrenze, gemessene Latenzverbesserung oder pauschale Übertragungsreduktion.

Quellen: [Projektion und ScorePreview](../../taxonomy-analysis/src/main/java/com/taxonomy/analysis/cluster/ClusterAnalysisStore.java),
[Recent-Beobachter](../../taxonomy-analysis/src/main/java/com/taxonomy/analysis/cluster/DurableClusterAnalysisObservation.java)
und [Recent-Projektionsregressionen](../../taxonomy-analysis/src/test/java/com/taxonomy/analysis/cluster/ClusterAnalysisRecentProjectionTest.java).

Die dokumentierte Dispatch-Recoverygrenze von **5.000 Intents je Lauf** wird durch
diese Folgearbeit nicht geändert. Weitere Intents warten auf den nächsten
Auslöser. Diese Betriebsgrenze ist kein Nachweis von Datenverlust; siehe
[Recovery-Konfiguration](../de/CONFIGURATION_REFERENCE.md).

## 5. CI-Befunde und Abgleich mit paralleler Arbeit

Auf dem bisherigen PR-Commit `bd052a0164f339f549149c4f589fc19125ac7cf6` waren
**alle sechs UI-Shards grün**. Die Oracle-, PostgreSQL-, MSSQL- und
Testcontainers-Jobs scheiterten an derselben Assetcontract-Assertion:
Der Workbenchtest erwartete das Literal `Promise.resolve()`, obwohl die
lokalisierte Workbench nun auf `window.TaxonomyI18n.ready()` warten muss.
Das unabhängig ausgewertete alte Core-JUnit-Artefakt umfasst **7.944 Tests,
eine Failure, null Errors und 79 Skips**. Die einzige darin ausgewiesene
JUnit-Failure ist der bekannte Workbench-Assetvertrag; weitere JUnit-Fehler
enthält dieses Artefakt nicht. Die historischen Skips und CI-Ergebnisse werden
nicht mit den lokalen Folgeprüfungen vermischt.

Der Vertrag prüft jetzt diese erforderliche I18n-Bereitschaft vor dem
anschließenden API-Load. Die Architekturverbote bleiben erhalten: Der
Graphadapter darf weder `window.print` noch direkte REST-/Fetch-Aufrufe
enthalten. Der neue Druckpfad liegt im separaten Printadapter. Dies ist die
Korrektur einer veralteten Testannahme, kein Entfernen der Architekturschranke.
Ein Ergebnis neuer CI-Läufe lässt sich daraus noch nicht ableiten.

Außerdem korrigiert die Interoperabilitätsprüfung den Archi-Releasepfad von
`5.10_0` auf `5.10`. Der Assetname bleibt `Archi-Linux64-5.10.0.tgz`, die Version
bleibt **5.10.0** und die gepinnte SHA-256 bleibt unverändert:

```text
f9422455a00a22f5340dc28692ceafe0ad720c8cde839eaafb0fab1cea57287f
```

Dies ist eine URL-Korrektur für dasselbe Asset und kein Produktupgrade oder neuer
Interoperabilitätsnachweis. Die Änderung ist deckungsgleich mit der parallelen
Search-Fix-Arbeit bei `52c4bff`; sie wird weder als zusätzliche Suchkorrektur noch
als zweiter Leistungsgewinn gezählt.

Quellen: [Workbench-Assetvertrag](../../taxonomy-build/src/test/java/com/taxonomy/portfolio/workbench/ArchitectureWorkbenchAssetContractTest.java)
und [Interoperabilitäts-Produktprüfung](../../.github/scripts/verify-interoperability-products.mjs).

## 6. Abschlussnachweise und noch offene Remote-CI

| Prüfbereich | Bislang bestätigter Stand | Aussagegrenze |
| --- | --- | --- |
| Frischer abschließender Node-Gesamtlauf | 889 von 889 Tests bestanden; null Fehler und Skips; Exit 0. | Vollständiger lokaler Vertragslauf; kein Ersatz für alle Browserprofile oder Datenbankvarianten. |
| Fokussierte Java-/Recent-Prüfung | 56 Tests bestanden, darunter 11 Recent-Projektionsfälle. | Diese Fälle sind eine Teilmenge der 424 ausgewählten Reaktortests; keine zusätzliche Testzählung. |
| Aktualisiertes I18n-Inventar | 20 Bundle-Paare; 2.488 englische und 2.488 deutsche Schlüssel; keine fehlenden Gegenstücke oder nummerierten Platzhalterdifferenzen. | Ressourcenprüfung, keine pauschale sprachliche Abnahme aller Seiten und Inhalte. |
| Exportregressionen | Beide zusätzlichen Fehler unabhängig RED/GREEN reproduziert; 20 UX-Tests erfolgreich. | Bodytransfer-/Downloadvertrag; keine Zusage über serverseitigen Rechenabbruch. |
| Printadapter-Nodeprüfung | Acht Fälle erfolgreich; fünf initiale Fälle zuvor RED. | Teil des Node-Gesamtlaufs, keine zusätzliche Testzählung. |
| Kontrollierte Browservorprüfung | Tastatur-, Workbench-Print- und Scrollabläufe mit realem D3/DOM reproduziert und korrigiert. | Vorprüfung und finaler Paketlauf sind getrennte Nachweise desselben lokalen Arbeitsstands. |
| CI des bisherigen PR-Stands | Sechs UI-Shards grün; gemeinsamer veralteter Assetvertrag in den genannten Datenbank-/Testcontainers-Jobs identifiziert. | Gilt ausschließlich für `bd052a0164f339f549149c4f589fc19125ac7cf6`. |
| Ausgewertetes Core-JUnit-Artefakt des alten CI-Stands | 7.944 Tests, eine bekannte Workbench-Assetcontract-Failure, null Errors und 79 Skips. | Historischer CI-Nachweis; keine Aussage über neue Remote-CI oder alle weiteren Artefakte. |
| Abschließender ausgewählter Maven-Reaktor | 424 Tests in 29 Klassen; null Failures, Errors und Skips; `BUILD SUCCESS` nach 3:58 Minuten. | Breite ausgewählte Prüfung einschließlich Architektur-, Cluster-, Transport-, Recent-, I18n- und Workbench-Verträgen; kein Lauf sämtlicher denkbaren Java-Tests oder Datenbankvarianten. |
| Anschließende Anwendungspaketierung | `BUILD SUCCESS` nach 1:41 Minuten. | Paketierung mit übersprungenen Tests; keine weitere Testzählung. |
| Abschließendes Paket-Chromium, ADMIN-Profil | Exit 0; 58 Checks, darunter 20 Axe-Zustände; überall `violations=[]` und `blocking=[]`; `auditError=null`. | Ein lokales Chromium-ADMIN-Profil mit Mock-Provider; keine pauschale Freigabe aller Browser, Rollen oder KI-Anbieter. |
| Workbench-PDF | Vier Seiten, 80.017 Bytes; beide Knoten, alle 80 Requirementzeilen, Snapshot-/Branchherkunft und englische Originalinhalte im PDF-Text nachgewiesen. | Kontrollierte QA-Daten; große Diagramme bleiben im Seitendruck ein Überblick. |
| Weitere Druck-PDFs | Decision-PDF zwei Seiten mit allen 20 Ergebniszeilen; Report-Iframe-PDF sechs Seiten mit allen 80 Berichtszeilen; wiederholte Kopfzeilen auf jeder Seite. | Dieselben kontrollierten 20 Decision- beziehungsweise 80 Berichtszeilen; keine zusätzlichen fachlichen Serverrenderer-Abnahmen. |
| Paket-/Quellbindung | 25 betroffene Ressourcen und Klassen stimmen bytegleich mit dem fertigen Paket überein. | Lokale Inhaltsbindung und Paket-SHA-256; kein erfundenes Remote-CI-Zertifikat. |
| Remote-CI des Folgecommits nach Veröffentlichung | Noch offen. | Alte grüne Jobs und lokale Gates werden nicht als neue Remote-CI ausgegeben. |

**Die lokalen Abschlussgates sind erfolgreich abgeschlossen. Offen bleibt nur
die neue Remote-CI nach Veröffentlichung.**

Die Maven-Ergebnisse und die anschließende Paketierung sind inzwischen anhand
des tatsächlichen Reaktorlogs und der aggregierten Surefire-Ergebnisse
bestätigt. Insbesondere bestehen die 11 Recent-Projektionsfälle und die fünf
Workbench-Assetcontract-Fälle auch im abschließenden ausgewählten Reaktorlauf.

Der frische Node-Lauf wurde mit Exit 0 abgeschlossen. Der Paketbrowser verwendet
einen Anwendungsstart; das vollständige ADMIN-Szenario mit Chromium bestand mit
58 Checks und 20 geprüften Axe-Zuständen. Die Workbenchfolge prüft neben den
PDF-Inhalten auch den nativen Printaufruf, vollständige lokale Graphbounds,
Tastaturzugang zum langen Requirement und den erhaltenen Bildschirmzoom samt
Auswahl nach dem Druck. Keine Axe-Regel wurde zum Umgehen des Scroll- oder
Interaktionsbefunds ausgeblendet.

Das geprüfte Anwendungspaket hat SHA-256:

```text
c5c122ff2c555f8433cec50a7f9753694b05e84fd11a9ec5dc4c67885f4dc20d
```

Die Inhaltsprüfung vergleicht die betroffenen elf Produktionsressourcen und
14 Java-Klassendateien, insgesamt 25 Einträge, mit dem paketierten Stand. Das
ist eine konkrete lokale Quell-/Paketbindung. Eine neue Remote-CI-Run-ID oder
Commitfreigabe wird erst nach deren tatsächlichem Lauf ergänzt; historische
grüne CI-Läufe werden nicht dafür ausgegeben.

Reproduzierbare Einstiegspunkte für die bereits vorhandenen Testverträge:

```bash
npm --prefix .github run verify:ui-contracts
node --test taxonomy-app/src/test/js/ux-state-recovery.cjs
node --test taxonomy-app/src/test/js/diagram-keyboard-controls.cjs
npm --prefix .github run test:architecture-workbench-basepath
```

Die native Browserfolge wird über
[Locale-/Druckworkflow](../../.github/scripts/ui-locale-print-workflow.mjs) in die
bestehende Abnahme eingebunden. Die vollständige ausgewählte Java-Klassenliste
stehen zusammen mit der Anwendungs-/Paketbindung und den PDF-Prüfergebnissen
im [maschinellen Nachweis](evidence/2026-10-06/german-ux-architecture-followup.json).
Der [vollständige Browserbericht](evidence/2026-10-06/german-ux-followup-browser-report.json)
enthält alle 58 Checks und die 20 einzelnen Axe-Ergebnisse.

### Sichtbare Nachweise aus dem abschließenden Paketlauf

| Ansicht | Nachweis |
| --- | --- |
| SVG-Baum mit deutschen lokalen Controls | [Bildschirmaufnahme](images/german-ux-architecture-followup-2026-10-06/qa-german-tree-keyboard.png) |
| Entscheidungskarte mit Tastatursteuerung | [Bildschirmaufnahme](images/german-ux-architecture-followup-2026-10-06/qa-german-decision-keyboard.png) |
| Canvas mit aktiver Knotenauswahl | [Bildschirmaufnahme](images/german-ux-architecture-followup-2026-10-06/qa-german-canvas-keyboard.png) |
| Sunburst mit Rückweg | [Bildschirmaufnahme](images/german-ux-architecture-followup-2026-10-06/qa-german-sunburst-keyboard.png) |
| Lokalisierte Workbench mit Auswahl | [Bildschirmaufnahme](images/german-ux-architecture-followup-2026-10-06/qa-german-architecture-workbench.png) |
| Vollständiger Workbench-Seitendruck | [PDF, vier Seiten](evidence/2026-10-06/german-ux-followup-workbench-view-print.pdf) · [erste Seite](images/german-ux-architecture-followup-2026-10-06/workbench-print-page-1.png) |
| Decision-Ergebnisse | [PDF, zwei Seiten](evidence/2026-10-06/german-ux-followup-decision-results-print.pdf) |
| Bericht im geladenen Iframe | [PDF, sechs Seiten](evidence/2026-10-06/german-ux-followup-report-frame-print.pdf) |

Die PDFs wurden zusätzlich als Text ausgewertet. Alle 80 Requirementzeilen,
alle 20 Ergebniszeilen und alle 80 Berichtszeilen sind enthalten; die beiden
Tabellen-PDFs wiederholen ihre Kopfzeilen auf jeder Seite. Die Textprüfung
normalisiert Unicode-Ligaturen, damit etwa das „fi“ in „qa-verifikation“ nicht
fälschlich als fehlender Herkunftstext gewertet wird.

## 7. Verbleibende Grenzen

Die Folgeprüfung erweitert konkrete Bedienpfade und schließt konkrete Fehler.
Sie bescheinigt weder eine zu 100 Prozent deutsche Oberfläche noch vollständige
WCAG-Erfüllung über alle Seiten, Inhalte, Geräte und Hilfsmittel. Fachliche
Originaltitel, Requirementtexte, technische Identitäten und Nachweise bleiben
unübersetzt. Seltene und unveränderte Nebenabläufe benötigen weiterhin ihre
eigene redaktionelle und funktionale Abnahme.

Für große Graphen ist der Seitendruck ein vollständiger Überblick der aktuell
dargestellten Auswahl. Die vorhandenen Vektorformate bleiben der ergänzende
Weg für andere Diagrammgrößen. Die Recent-Projektion reduziert die nachgewiesene
Abfrage-/Entitylast, löst aber nicht automatisch die verbleibenden JSON-,
temporären Scoremap- und Treiberkosten. Aussagen über reale Heapnutzung,
End-to-End-Latenz oder alle Datenbankvarianten benötigen eigene Messungen.

Bei der visuellen Nachsicht bleiben zwei kleinere redaktionelle beziehungsweise
Layoutbefunde sichtbar: Im Desktoplayout bricht die schmale Schrittkachel
„Beschreiben“ innerhalb des Wortes um; die Workbench-KPI verwendet bei einer
Ebene „1 Ebenen“. Die [Baumaufnahme](images/german-ux-architecture-followup-2026-10-06/qa-german-tree-keyboard.png)
und die [erste Druckseite](images/german-ux-architecture-followup-2026-10-06/workbench-print-page-1.png)
belegen diese noch offenen Details. Sie verhindern die geprüften Bedienabläufe
nicht, bleiben aber eine konkrete Aufgabe für die redaktionelle und responsive
Layoutnacharbeit.
