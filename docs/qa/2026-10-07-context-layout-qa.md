# QA: Bildschirmfläche, Kontext und Bedienbarkeit — 7. Oktober 2026

**Bezug der Prüfstände:** Die ursprünglichen Messungen und die lokale Erstabnahme
in den Abschnitten 1–3 und 5 gehören zum Source-Tree
`a980c2e4d7ae476a0864b87d3b274282f35730f6`, veröffentlicht als Commit
`26bb32f8ec9ca9e16d64d31212dcb8574267e09c` in
[PR #1180](https://github.com/carstenartur/Taxonomy/pull/1180).
Die anschließend gefundenen CI-Befunde und ihre Nachprüfung stehen in den
Abschnitten 7 und 8. Abschnitt 7 dokumentiert den Stand `27f6b514…`; Abschnitt 8
trennt dessen Remote-Ergebnis von den danach vorgenommenen Korrekturen.
Abschnitt 4 beschreibt die korrigierte Testzuständigkeit. Die ursprünglichen
Ergebnisdateien bleiben als Nachweise ihres jeweiligen Prüfstands erhalten.

## Urteil

Der Ausgangsstand nutzte den verfügbaren Bildschirmplatz an mehreren zentralen
Stellen unzweckmäßig. Die Schrittführung verdrängte die eigentliche Eingabe,
der DSL-Editor teilte sich seine bereits schmale Spalte nochmals mit Werkzeugen,
und die Architektur-Workbench schnitt auf üblichen Laptopgrößen ihre Zeichenfläche
und Detailspalte unten ab. Daneben erschwerten schwer auffindbare Projektfunktionen,
falsche Ergebnisführung und unzuverlässige Fehlerrückmeldungen die Bedienung.

Die nachstehend als behoben ausgewiesenen Fehler sind korrigiert. Die Änderungen
konzentrieren
den Platz auf die aktuelle Aufgabe und erhalten die vorhandenen Funktionen,
Tastaturpfade und Daten. Ein Benutzer kann dadurch früher mit der eigentlichen
Aufgabe beginnen, passende Ergebnisse direkt prüfen und nach Fehlern mit seinem
Entwurf weiterarbeiten. Das ist eine begründete ergonomische Bewertung und ein
technischer Abnahmenachweis; eine beobachtete Studie mit neuen Nutzern wurde
in diesem Auftrag nicht durchgeführt.

## 1. Prüfstand und Methode

Ausgangspunkt ist `main` mit Commit
`f0775632ad9a1e84acee895db5de88041f8f5ac9` und Source-Tree
`aec0c360d0a801d6734dd62958e53d24d6487242`. Die früheren QA-Änderungen einschließlich
der deutschen Architektur- und Suchkorrekturen waren dort bereits enthalten.
Der aktuelle Arbeitszweig ist `qa/context-layout-20261007`.

Die Prüfung kombiniert unabhängige Quellcode-Reviews, tatsächliche
Browserbedienung, geometrische Messungen, automatisierte
Barrierefreiheitsprüfungen und gezielte Regressionen für Fehlerzustände.
Es wurden bestehende Oberflächen und vollständige Aufgabenfolgen geprüft,
einschließlich Navigation, Analyse, Ergebnisprüfung, Import, Export, Versionsvergleich,
Beziehungen, Einstellungen und Arbeitsbereichssynchronisation. Zusätzliche
Portfoliofolgen prüfen die Projekt- und Anforderungsnavigation, ein offenes
Versionsmodal, Matrizen und die Versionierung.

### Messbedingungen

- Layoutmessungen bei **1920×1080, 1366×768, 1024×768 und 390×844 CSS-Pixeln**;
  Exportmenü zusätzlich bei **320 Pixeln Breite**.
- Derselbe Chromium-Headless-Shell und dieselbe Schriftumgebung für die
  Vorher/Nachher-Vergleiche. Einige Emoji-Glyphen fehlen in dieser Testumgebung;
  die entsprechenden Kästchen in beiden Bildreihen sind kein neuer Produktfehler.
- Der Analyse- und DSL-Vergleich verwendet den tatsächlich geladenen Katalog
  mit acht Wurzeln und 2.572 Knoten. Der DSL-Text stammt aus der Anwendung.
- Die reproduzierbare Workbench-Geometrie verwendet eine ausdrücklich lokale
  Zwei-Knoten-API-Fixture mit langem Anforderungstext. Das ist ein Layoutnachweis.
  Der maßgebliche Test für den tatsächlich persistierten Copilot-Snapshot bleibt
  `ArchitectureWorkbenchUiIT`.
- Portfolio-Lesedaten werden auf reservierte QA-Projekte begrenzt. Ein tatsächlicher
  Formular-POST wird lokal mit HTTP 409 beantwortet; andere Schreibaufrufe innerhalb
  dieses Fixture-Workflows werden blockiert und als Fehler erfasst.
- Es wurden keine kostenpflichtigen Modellaufrufe verwendet. Die Anwendung lief mit
  dem vorhandenen Mock-Provider und abgeschalteten Embedding-Downloads.

Die Messwerte stehen in
[context-layout-before.json](evidence/2026-10-07/context-layout-before.json) und
[context-layout-after.json](evidence/2026-10-07/context-layout-after.json).
Die Bildherkunft und Prüfsummen dokumentiert
[context-layout-images.json](evidence/2026-10-07/context-layout-images.json).
Die Geometriedaten beschreiben Rechtecke im jeweiligen Zustand; die tatsächliche
Sichtbarkeit interaktiver Inhalte wird separat mit Browserassertionen geprüft.
Die hier verlinkten Nachher-Bilder und Nachher-Messwerte wurden zum Abschluss
erneut an der eingefrorenen JAR `5559bf41b577…` ohne Ressourcenoverride erfasst.
Eine frühere Entwicklungsaufnahme mit veränderlichen lokalen Ressourcen wurde
dadurch ersetzt. Der abschließende Capture wartet auch auf den geladenen DSL-Text;
alle in der Vergleichstabelle genannten Werte blieben dabei unverändert.

## 2. Platzverteilung: gemessene Ergebnisse

Koordinaten beginnen oben am Viewport. Ein Aktionsbutton mit Unterkante oberhalb
der Viewporthöhe ist im gemessenen Ausgangszustand ohne Scrollen vollständig
erreichbar. Die Eingabe wurde für diese Prüfung geleert, damit gespeicherte
Analysen und Fortschrittszustände den Vergleich nicht verfälschen.

| Situation | Vorher | Nachher | Bedeutung |
| --- | ---: | ---: | --- |
| Analyse, 1366×768: Höhe der Schrittführung | 347,9 px | 133,5 px | Wiederholte Erklärungen verdrängen die Eingabe wesentlich weniger. |
| Analyse, 1366×768: Beginn des Eingabefeldes | y = 634,1 px | y = 395,7 px | Das vollständige 182 px hohe Textfeld ist direkt sichtbar. |
| Analyse, 1366×768: Unterkante Copilot-Start | y = 936,7 px | y = 698,3 px | Die zentrale Aktion liegt im ersten Bildschirmausschnitt. |
| Analyse, 1024×768: Unterkante Copilot-Start | y = 931,8 px | y = 733,1 px | Auch auf dem kleineren Laptop ist die Aktion vollständig sichtbar. |
| Analyse, 390×844: Beginn des Eingabefeldes | y = 917,0 px | y = 534,5 px | Die Eingabe beginnt nicht mehr unterhalb des ersten Bildschirms. |
| Analyse, 390×844: Unterkante Copilot-Start | y = 1219,6 px | y = 837,1 px | Eingabe und Hauptaktion sind im geprüften mobilen Einstieg gemeinsam sichtbar. |
| DSL, 1366×768: Editorbreite | 310,1 px | 882,0 px | Rund 2,84-mal so viel horizontaler Arbeitsraum. |
| DSL, 1024×768: Editorbreite | 227,0 px | 654,0 px | Der Editor erhält die breite Hauptspalte. |
| DSL, 1920×1080: Editorbreite | 444,8 px | 1251,3 px | Auch große Bildschirme werden für den Code genutzt. |
| Workbench, 1366×768: Toolbarhöhe | 126,0 px | 56,0 px | Exportformate belegen Platz erst bei Bedarf. |
| Workbench, 1366×768: tatsächlich sichtbare Zeichenhöhe | 359,5 px | 506,9 px | Rund 41 % mehr sichtbare Zeichenfläche. |
| Workbench, 1024×768: tatsächlich sichtbare Zeichenhöhe | 288,3 px | 433,3 px | Rund 50 % mehr sichtbare Zeichenfläche. |
| Workbench, 1366×768: Unterkante der Zeichenfläche | y = 843,5 px | y = 768,0 px | Die unteren 75,5 px werden nicht mehr abgeschnitten. |
| Workbench, 1024×768: Unterkante der Zeichenfläche | y = 909,7 px | y = 768,0 px | Die unteren 141,7 px werden nicht mehr abgeschnitten. |
| Workbench, 390×844: Beginn der Zeichenfläche | y = 831,3 px | y = 549,9 px | Ein Teil des Diagramms ist bereits beim Einstieg sichtbar. |
| Analyse mit langem geöffnetem Log: leere Kartenfläche unter dem Kataloginhalt | 2215,5 px | 1,0 px | Die Katalogkarte wird nicht auf die Höhe des benachbarten Logs gestreckt. |

Die sichtbare Zeichenhöhe ist der Schnitt von Zeichenflächenrechteck und
Viewport. Sie unterscheidet sich bewusst von der früheren, teilweise
abgeschnittenen CSS-Gesamthöhe. Alle Zahlen sind lokale Messungen der genannten
Zustände und keine Garantie für beliebige Schriftgrößen, Betriebssystemschriften
oder Inhaltslängen.

### Visuelle Vergleichspaare

| Oberfläche | Ausgangsstand | Geprüfter Änderungsstand |
| --- | --- | --- |
| Analyse auf dem Laptop | [Vorher](images/context-layout-2026-10-07/analyze-1366-before.png) | [Nachher](images/context-layout-2026-10-07/analyze-1366-after.png) |
| Analyse auf dem Handy | [Vorher](images/context-layout-2026-10-07/analyze-390-before.png) | [Nachher](images/context-layout-2026-10-07/analyze-390-after.png) |
| DSL auf dem Laptop | [Vorher](images/context-layout-2026-10-07/dsl-1366-before.png) | [Nachher](images/context-layout-2026-10-07/dsl-1366-after.png) |
| Workbench auf dem Laptop | [Vorher](images/context-layout-2026-10-07/workbench-1366-before.png) | [Nachher](images/context-layout-2026-10-07/workbench-1366-after.png) |

Zusätzliche geprüfte Zustände: [Projektwerkzeuge auf dem Handy](images/context-layout-2026-10-07/qa-portfolio-tools-390.png),
[sichtbarer Versionskonflikt im offenen Modal](images/context-layout-2026-10-07/qa-portfolio-version-conflict.png)
und [Exportmenü innerhalb der mobilen Bildschirmgrenzen](images/context-layout-2026-10-07/qa-context-workbench-mobile-exports.png).

## 3. Behobene Befunde und ihre Auswirkungen

P1 bezeichnet hier eine unzuverlässige fachliche Rückmeldung, einen gefährdeten
Entwurf oder einen blockierten Arbeitsablauf. P2 bezeichnet eine erhebliche
Behinderung durch Platzverteilung, Auffindbarkeit oder Navigation. Diese
Prioritäten sind die Einschätzung dieser QA und keine globale Fehlerklassifikation.

### P2 — Die Schrittführung stand vor der eigentlichen Aufgabe

Vier ausführliche Hilfekarten, zusätzliche Statuszeilen und ein leerer, deaktivierter
Folgeschritt belegten im mobilen Einstieg viel Höhe. Die vier Schritte bleiben
erhalten, sind aber kompakt. Ausführliche Schrittbeschreibungen bleiben zugänglich;
in den Prüfen-/Nutzen-Zuständen erscheint der jeweils passende aktuelle Hinweis.
Eine leere deaktivierte Zweitaktion wird ausgeblendet. Die mobile Bereichsauswahl
und „Aktuelle Aufgabe“ teilen sich eine Zeile. Die Eingabebeschriftung ist kürzer,
das Textfeld bleibt sieben Zeilen hoch.

Damit wird keine fachliche Eingabe verkleinert, um eine Messung zu bestehen.
Der Gewinn entsteht oberhalb der eigentlichen Arbeit.

### P2 — Der DSL-Editor erbte eine für seine Aufgabe unpassende Spaltenstruktur

Der Katalog blieb beim Wechsel in den DSL-Bereich sichtbar, obwohl dort der
Quelltext die aktuelle Aufgabe ist. Innerhalb der verbleibenden schmalen rechten
Spalte wurde der Editor nochmals neben Commit-Werkzeugen angeordnet.

Der DSL-Bereich nutzt jetzt die volle Hauptbreite. Auf geeigneten Bildschirmgrößen
erhält der Editor etwa zwei Drittel und die Commit-/Materialisierungssteuerung
etwa ein Drittel dieser Fläche. Kopfzeile und Buttons können umbrechen. Das
vorhandene CodeMirror-Verhalten und die Bedienelemente bleiben bestehen.

### P2 — Workbench: feste Höhen und dauerhafte Exportlisten verdrängten den Graphen

Die frühere Rechnung mit einem festen Headerabzug passte nicht zu umgebrochenen
Toolbars. Eine zusätzliche Mindesthöhe des Canvas verschärfte das Problem.
Die Workbench verteilt die tatsächliche verbleibende Viewporthöhe jetzt über
Flex/Grid; Canvas und Detailspalte dürfen darin korrekt schrumpfen und scrollen.
Auf schmalen Geräten bleibt der normale vertikale Dokumentfluss erhalten.

Die sechs vorhandenen Exportaktionen liegen unter **Exportieren**. Der Button
„Ansicht drucken“ und der Zugang zum Architektureditor bleiben direkt sichtbar.
Format- und Profilhinweise stehen bei den Exportoptionen. Das Menü öffnet mit
Enter, schließt mit Escape und gibt den Fokus an den Auslöser zurück. Ein Klick
außerhalb und die Auswahl einer Aktion schließen es ebenfalls. Beim Öffnen
eines weiterführenden Dialogs wird dessen Fokus nicht zurückgezogen.

Die unabhängige Prüfung fand zusätzlich ein seitlich versetztes Menü, wenn der
Auslöser auf dem Handy umbricht. Die Verankerung an der Toolbar und die
Breitenbegrenzung korrigieren diesen Fall; die Browserprüfung kontrolliert beide
Menükanten ausdrücklich bei 1024, 390 und 320 Pixeln. Der Seitendruck behält den
vollständigen Dokumentfluss und die vorhandene Aufbereitung des Diagramms.

### P2 — Katalogkarte und Suche waren falsch an die Nebenwerkzeuge gekoppelt

Die Katalogkarte wird nicht mehr auf die Höhe eines langen, rechts geöffneten
LLM-Logs gestreckt. Die Suche sitzt jetzt direkt im Taxonomie-Browser. Sie wird
nicht mehr durch den Aufbau der Schrittführung in „Weitere Werkzeuge“ verschoben.
„Ähnliche finden“ öffnet den tatsächlich benötigten Bereich und die echten
Vorfahren der Suchoberfläche. Eine irrelevante zusätzliche Werkzeuggruppe bleibt
geschlossen. Die bestehende Suche und ihre API-Verträge werden weiterverwendet.

### P1 — „Prüfen“ führte nicht zuverlässig zum relevanten Ergebnis

Die Navigation wählt jetzt den höchsten gültigen, nichtnegativen Score eines
tatsächlich geladenen Katalogknotens. Unbekannte IDs und nicht endliche Werte
bestimmen das Ziel nicht. Bei Gleichstand bleibt die Katalogreihenfolge stabil.
Der Zielknoten wird in der vorhandenen Listenansicht einschließlich seiner
Vorfahren sichtbar, erhält den Tastaturfokus und wird ins Sichtfeld gescrollt.

Der Browser prüft den Einstieg aus Liste, Registerkarten, Baum, Sunburst,
Entscheidungsansicht und Zusammenfassung. Scores, Begründungen, Scope und
Analysedauer bleiben dabei unverändert; es wird kein neuer Provider-Aufruf
ausgelöst. Bei ausschließlich bekannten 0-Werten steht **Bewertungen prüfen**
mit einem Hinweis auf fehlende positive Relevanz. Sind nur unbekannte IDs
vorhanden, erscheint eine passende Meldung und die Schrittführung behauptet
keinen erfolgreichen Reviewfortschritt.

Ein zweiter, vom unabhängigen Review gefundener Fehler betraf gespeicherte
Begründungen bei 0: Die Listenfunktion blendete sie aus. Vorhandene Begründungen
werden jetzt auch in diesem Fall im zugänglichen Namen und im Begründungshinweis
angezeigt; 0 erhält weiterhin keine positive Hervorhebung.

### P2 — Projektwerkzeuge und Anforderungsdetails waren schwer zu finden

Ein geladenes Projekt erhält das kompakte Menü **Projektwerkzeuge** für Import,
Matrizen, Berichte und Versionierung. Die Links werden beim Projektwechsel neu
gesetzt und während eines noch nicht abgeschlossenen Projektladens zurückgesetzt.
Anforderungstitel verlinken auf ihre Detailseite und besitzen einen Namen mit
Anforderungsschlüssel und Titel. Die bisherigen Inline-Aktionen bleiben verfügbar.
Die Browserprüfung kontrolliert den Projektwechsel und die echte
ArrowDown-/Escape-Fokusführung des Bootstrap-Menüs auf Laptop und Handy.

### P1 — Versionsfehler lagen hinter dem offenen Dialog, Entwürfe waren gefährdet

Ein Fehler beim Anlegen einer Anforderungsversion wird jetzt im weiterhin offenen
Modal angezeigt und fokussiert. Er liegt nicht hinter dessen Backdrop. Text,
Änderungsgrund und Quellenfelder bleiben erhalten. Erneutes Öffnen oder ein
neuer Versuch entfernt die alte Fehlermeldung.

Hintergrundaktualisierungen prüfen nach dem Abschluss ihrer Anfrage erneut, ob
der Dialog geöffnet oder der Entwurf geändert ist. Sie schreiben dann keine
Felder neu und erhalten auch Cursor und Quellenangaben. Ein unveränderter
geschlossener Entwurf kann weiterhin aktualisiert werden. Nach erfolgreichem
Speichern wird der Änderungszustand regulär zurückgesetzt.

### P1 — Matrizen verwechselten 0 mit fehlender oder unbekannter Abdeckung

Eine ausdrücklich gespeicherte 0 bleibt **0 %**, behält ihre Evidenz und zählt
als vorhandene Beziehung. Ein fehlender Eintrag im sparsamen Matrixobjekt bleibt
leer. Fehlerhafte oder degradierte Werte werden als unbekannt behandelt. Filter
stellen ausgefilterte Zellen nicht als angeblich fehlende Beziehungen dar und
machen sie nicht versehentlich anklickbar. Die Detailansicht folgt derselben
Bedeutung wie die Zelle; die JSON-/CSV-Ausgabe erfindet keine Nullwerte.

Der reguläre Java-Vertrag liefert eine sparse Matrix mit Integerwerten. Die
Browserfolge verwendet dafür gültige 0- und 65-Werte sowie fehlende Schlüssel.
Ein zusätzlich injiziertes `null` ist ausdrücklich eine fehlerhafte Antwort für
den Robustheitstest und wird nicht als normaler Serveroutput ausgegeben.

### P1 — Versionsvergleich verlor Reihenfolge und doppelte Zeilen

Der Vergleich von Anforderungsversionen folgt jetzt der Reihenfolge der Zeilen
und erhält wiederholte Zeilen sowie Leerzeichen. Gemeinsame Präfixe und Suffixe
werden vor einem begrenzten LCS-Vergleich abgetrennt. Für übergroße Restblöcke
gibt es einen vollständigen Blockvergleich mit ausdrücklicher Erklärung; es
werden keine Änderungen stillschweigend weggelassen. Die Markierung der
gewählten Version, `aria-current` und der vorhandene Buttonfokus bleiben erhalten.
Die Begrenzung auf eine Million LCS-Zellen verhindert eine unbeschränkte
quadratische Arbeit im Browser.

### P1 — Versionierung überschrieb Eingaben und interpretierte den Branchvertrag falsch

Commit- und Merge-Nachrichten werden nur einmal vorbelegt. Spätere Aktualisierungen
erhalten exakte Eingaben einschließlich absichtlich geleerter Felder. Die vier
Branch-Auswahlfelder behalten ihre Auswahl unabhängig voneinander. Eine erfolgreich
ausgeführte Mutation bleibt als Erfolg sichtbar, auch wenn das nachfolgende Lesen
fehlschlägt; der Lesefehler wird separat angezeigt. Ein neuer Mergeversuch entfernt
eine veraltete Ergebnisanzeige.

Die unabhängige Nachprüfung fand außerdem, dass `RepositoryState.branches` eine
Liste von Zeichenketten liefert. Die bisherige Auswertung als Objekte mit `name`
entfernte die echten alternativen Zweige. Die Oberfläche verarbeitet jetzt den
tatsächlichen Vertrag. Auch die anfänglich unzutreffenden Objekt-Fixtures wurden
korrigiert. Die abschließenden Tests prüfen Beschriftungen und Optionswerte aller
vier Felder in Deutsch und Englisch sowie unter verschiedenen Context Paths.
Die früheren grünen Objekt-Fixtures sind kein Nachweis für diesen Datenvertrag.

### P1 — DSL-Befehle konnten Fehler als Erfolg melden

Commit, Materialisierung, Branchanlage, Merge und Cherry-Pick prüfen den
HTTP-Status über den bestehenden zentralen API-Client. Schreibende Aufrufe werden
nicht automatisch wiederholt. Strukturierte Validierungsfehler und ein
`valid: false` der inkrementellen Materialisierung bleiben Fehler. Eine
fehlgeschlagene History-Vorabfrage erzeugt keinen falschen Erfolg.

Entwurf und Commit-Nachricht bleiben bei Fehlern erhalten, einschließlich
Änderungen während einer noch laufenden Anfrage. Ein älterer Timer kann eine
später angezeigte Fehlermeldung nicht verstecken. Die Erfolgsmeldung verwendet
die tatsächlichen Backendzähler für erzeugte Beziehungen und Beziehungsvorschläge,
auch wenn einer davon 0 ist.

### P1 — Portfolioseiten funktionierten unter einem Application Context unvollständig

Die fünf eigenständigen Portfolioseiten berücksichtigen den exakt bekannten
Application Context beim Erkennen ihrer Route. Benötigtes I18n-/Basepath-Bootstrap
wurde in den vier zuvor unvollständigen Templates ergänzt. Generierte Links,
Downloads und gespeicherte Analysejob-URLs verwenden die vorhandene zentrale
Auflösung. Ein erwarteter Projektbezug bleibt dabei überprüfbar.

Ein ähnlich benannter Nachbarpfad, fremde Herkunft, Zugangsdaten im URL,
Query-/Fragmentzusätze und kodierte Trennzeichen dürfen nicht als kanonische
Analysejob-URL durchgehen. Es wird kein neuer globaler Fetch-Wrapper eingeführt.

### P1 — Unter einem Application Context fehlten dynamisch geladene Bedienmodule

Der zusätzliche Browserstart der tatsächlichen Anwendung unter `/taxonomy`
fand einen weiteren älteren Bootfehler. Die Anmeldung, der Katalog und die
Schrittführung funktionierten, die erwartete Bereitschaft von
`TaxonomyRoleSurface` trat aber nicht ein. Der zentrale API-Client lud die
Rollenoberfläche und die UI-Semantik mit absoluten `/js/...`-Pfaden außerhalb
des Servlet Context nach. Die vorhandene Basepath-Behandlung von `fetch`
wirkte nicht auf dynamisch eingefügte Script-Elemente.

Die Korrektur betrifft die Auflösung dieser beiden Script-Quellen. Die
serverseitige Berechtigungsprüfung bleibt unverändert. Der fehlgeschlagene
erste Unterpfadlauf bleibt als Diagnose dokumentiert und zählt nicht als
bestandene Portfolioabnahme. Der abschließende Lauf an der neu paketierten JAR
hat die tatsächliche Rollenbereitschaft, die Quellen beider geladenen Module
innerhalb `/taxonomy` und anschließend sämtliche Portfoliofolgen erfolgreich
geprüft: neun dokumentierte Checks einschließlich zweier Axe-Zustände,
keine ungefangene Browserexception. Beide Zustände sind als
[ursprünglicher Bootfehler](evidence/2026-10-07/portfolio-context-before-loader-fix.json)
und [erfolgreiche Unterpfadabnahme](evidence/2026-10-07/portfolio-context-after-loader-fix.json)
aufbewahrt.

## 4. Regressionen und maßgebliche Testbesitzer

Die erste lokale QA ergänzte 183 Node-Fälle. Die Remote-CI wies zu Recht darauf
hin, dass 109 davon die vorhandene Portfolio-Testarchitektur verletzten:
Portfolio-Verträge gehören zu JUnit und Maven Failsafe. Diese Fälle sind in
folgende Besitzer übertragen; das Architektur-Gate und die CI-Auswahl bleiben
erhalten.

| Testbesitzer nach der Korrektur | Fälle | Vertrag |
| --- | ---: | --- |
| `dsl-command-feedback.test.mjs` | 48 | HTTP-/Validierungsfehler, Entwurferhalt, Status-Timer und tatsächliche Zähler |
| Ergänzungen in `test-taxonomy-api-client.mjs` | 26 | Tatsächlicher Loader, I18n-Auflösung, eigener Scriptpfad, Root-Fallback und Vermeidung doppelter Module |
| `PortfolioClientRoutingIT` | 37 | Root-, gemountete und verschachtelte Pfade; Job-URLs; tatsächliche Branch-Optionen und Downloads |
| `PortfolioVersioningRecoveryIT` | 40 | Nachrichten, unabhängige Auswahl, verzögerte HTTP-Reads und getrennte Mutation-/Read-Ergebnisse |
| `RequirementVersionReviewIT` | 13 | Zeilenreihenfolge, Wiederholungen, tatsächliches DOM-Escaping, Auswahl und begrenzter Großvergleich |
| `PortfolioRequirementReviewIT` | 9 | Echte Versionsformulare, HTTP 400/409, Entwurf/Fokus/Quellenfelder und Hintergrundaktualisierung |
| `PortfolioMatrixReviewIT` | 10 | Tabelle, Detailfenster, native Filterbedienung, alternative Liste und heruntergeladene JSON-/CSV-Dateien |
| **Ergänzte Node-Fälle** | **74** | Die bestehenden 920 Fälle bleiben enthalten. |
| **Übertragene JUnit-Browserfälle** | **109** | Dieselben fachlichen Regressionen unter dem bestehenden Maven-Lifecycle. |

Die Client-Prüfungen rendern die tatsächlichen Thymeleaf-Templates, laden die
Produktionsskripte und benutzen die vorhandene `BrowserSession`. Fehler und
verzögerte Antworten entstehen an einer lokalen HTTP-Grenze. Die Tests greifen
weder auf freigelegte private Modulzustände zu noch betreiben sie einen
zusätzlichen JavaScript-Testläufer. Die drei Werte `undefined`, `NaN` und
`Infinity` sind nicht als JSON darstellbar; allein diese explizit benannten
Fehlerfälle werden nach dem tatsächlichen HTTP-Decoding an der öffentlichen
`Response`-Grenze injiziert. Normale sowie sonstige fehlerhafte JSON-Werte kommen
über wirkliche HTTP-Antworten.

Der bereits vorhandene
[PortfolioUiAcceptanceIT](../../taxonomy-app/src/test/java/com/taxonomy/PortfolioUiAcceptanceIT.java)
behält seinen vollständigen Workflow mit echter Persistenz, Analyse, Import,
Git und Berichten. Zwei zusätzliche Testmethoden übernehmen die Kontextfolge
am Root und nach einem sequenziellen Neustart unter dem echten Servlet-Context
`/taxonomy`. Vorlagen, Anmeldung, Ressourcen und Bootstrap stammen dabei aus
der echten Anwendung; reservierte QA-Lesedaten und der gezielte HTTP-409-Fall
sind als begrenzte Fixtures ausgewiesen. Der Standard bleibt die paketierte
Anwendung mit Selenium/Testcontainers. Das bestehende Profil `test-local`
führt dieselben Methoden mit einer isolierten Spring-Anwendung und dem
vorhandenen lokalen Browseradapter aus.

Sieben zusätzliche JUnit-Infrastrukturprüfungen sichern die Grenzen dieser
Fixtures ab: fünf für Context, reale DTOs, unerwartete Writes, Redirects und
den unveränderlichen Proxy-Zielorigin sowie zwei für die tatsächlich per HTTP
ausgelieferten statischen Ressourcen und Webjars einschließlich Pfadausbrüchen.

Die allgemeine Layoutfolge
[ui-context-layout-workflow.mjs](../../.github/scripts/ui-context-layout-workflow.mjs)
bleibt Teil der bestehenden ADMIN-Primary-Abnahme. Die Portfolio-Nodefolge und
die vier übertragenen Node-Testdateien wurden nach ihrer Java-Verifikation entfernt.
Die vorhandenen Druck-, Diagramm-, Such- und Abbruchprüfungen bleiben erhalten.
Ein früherer Axe-Nachweis aus der Portfolio-Nodefolge wird ausdrücklich nicht
als neu ausgeführter Java-Axe-Scan gezählt.

Die Layoutprüfung ersetzt nicht den vorhandenen Persistenztest.
[ArchitectureWorkbenchUiIT](../../taxonomy-app/src/test/java/com/taxonomy/ArchitectureWorkbenchUiIT.java)
prüft zusätzlich am tatsächlich gespeicherten Snapshot die Laptopfläche und die
Exportsteuerung per Tastatur. Der bestehende
[ScenarioBrowserWalkthrough](../../taxonomy-app/src/test/java/com/taxonomy/ScenarioBrowserWalkthrough.java)
öffnet das Exportmenü vor jedem tatsächlich geprüften Download erneut.
Beide Java-Testquellen sind kompiliert. Ihre Docker-gestützte Ausführung bleibt
Aufgabe der entsprechenden CI-Prüfungen.

Gezielte neue Fälle wurden vor der jeweiligen Korrektur gegen den ursprünglichen
Fehler geprüft. Bei der späteren Korrektur falscher Branch-Testdaten liefen die
zwei zunächst roten Fälle bereits gegen das korrigierte Produkt: Sie belegen
eine veraltete Fixture und werden nicht als neuer Produkt-RED-Lauf ausgegeben.
Beim zusätzlich gefundenen Script-Ladefehler scheiterten acht der 26 neuen
Loaderfälle vor dem Fix; die 18 bestehenden Root-/Fallback-/Duplikatbedingungen
blieben grün. Nach der Korrektur bestanden alle 26 Fälle sowie die vorhandenen
Transport-, Integrations- und CSRF-Prüfungen. Diese Tests führen auch den zuvor
von den Transporttests ausgeschnittenen Bootstrapblock tatsächlich aus.

## 5. Lokale Erstabnahme vor der Remote-CI

| Prüfung | Abgeschlossener Umfang | Ergebnis |
| --- | --- | --- |
| Normale Paketierung nach letzter Produktkorrektur | 16 Reactor-Module, Produktions-/Testquellen und SBOM; Java-Tests ausdrücklich übersprungen | BUILD SUCCESS |
| Maven-eigener Frontendvertrag | 1.103 Vertragsfälle, davon 183 neu; zusätzlich dieselben 26 API-Fälle im separaten Maven-Schritt | 0 Fehler, Abbrüche oder Skips |
| Primary-Workflows am finalen Paket | USER, ARCHITECT, ADMIN; einschließlich Portfolio, Ergebnisführung, Diagrammbedienung, Export und Druck | Alle drei Rollen bestanden |
| Eigenständige Accessibility-Matrix | Acht Hauptbereiche bei 1440×1000, 1024×768 und 390×844; 24 Axe-Zustände | Keine Violations |
| Reflow entsprechend 200 % und 400 % | Effektive CSS-Viewports 720×500 und 360×250; jeweils vollständige USER-Folgen, Dialoge, Architektureditor und Integrationen | Beide Profile bestanden |
| Forced Colors | ARCHITECT bei 1024×768, einschließlich Vorschlagsentscheidung und Variantenwechsel | Bestanden |
| Textabstand und Offline-Zustand | ADMIN; partielle Analyse, erhöhte Textabstände, Import-Buttonkontrast, Offline-Hinweis und Wiederholung | Bestanden |
| Echter Servlet Context `/taxonomy` | Reale Anmeldung, beide nachgeladenen Bedienmodule und alle zusätzlichen Portfoliofolgen | 9 dokumentierte Checks; 2 Axe-Zustände ohne Violations |
| Finale Geometrie-/Bildaufnahme | 13 Zustände am eingefrorenen finalen Paket, kein Ressourcenoverride | Keine Browserexception; Vergleichswerte bestätigt |
| Ausgewählte Java-Tests vor letztem Scriptfix | 8 BrowserSessionTest + 2 BrowserSessionIT + 5 AnalysisLiveProgressUiIT | 15 bestanden, 0 Fehler/Skips |
| Unabhängiges Code- und Evidenzreview | Korrekturen, Owner-Tests, tatsächliche DTOs, Hashes, Geometrie und Berichtsaussagen | Keine verbleibenden Critical-/Important-Befunde |

Die elf ausgewählten Browser-Szenarioläufe sind in
[browser-verification.json](evidence/2026-10-07/browser-verification.json)
einzeln mit Ergebnis, Berichtshash und zugehöriger Anwendungs-JAR verzeichnet.
Sie enthalten 228 protokollierte Workflowchecks und 90 Axe-Zustandsprüfungen
ohne gemeldete Violations. Axe-Einträge sind teilweise bereits Workflowchecks;
die beiden Zahlen werden nicht zu einer angeblichen Zahl unabhängiger Tests addiert.
Die Reflowprofile setzen den entsprechenden kleineren CSS-Viewport; sie behaupten
keine separate Abnahme nativer Betriebssystemvergrößerung.

Die eigenständige Accessibility-Matrix lief vor dem abschließenden
Script-Ladepfadfix am Kandidaten `22ad…`. Der Vergleich aller 225 Ressourcen
belegt, dass zwischen diesem und dem finalen Kandidaten ausschließlich
`static/js/api/taxonomy-api-client.js` verändert wurde. CSS und Templates sind
bytegleich. Primary-, Rollen-/Reflow-, Sondermodus- und Unterpfadfolgen liefen
anschließend am finalen Kandidaten `5559…`, einschließlich ihrer erneuten
Axe-Prüfungen.

Die Druckausgaben wurden außerdem vollständig gerendert und visuell geprüft:
vier Workbenchseiten und sechs Seiten der Berichts-Fixture. Die Schlusszeile 80
und die vollständigen absichtlich weit auseinanderliegenden Diagrammbounds
bleiben erhalten. Der
[visuelle Drucknachweis](evidence/2026-10-07/print-visual-review.json)
nennt den dabei verwendeten Kandidaten `22ad…` und die PDF-Prüfsummen. Die
Druck-CSS blieb danach bytegleich; der Druckworkflow besteht auch erneut am
finalen Paket. Aus diesen Fixture-Seiten folgt keine pauschale Layoutfreigabe
beliebiger Produktionsberichte.

Der frische Build paketiert alle 16 Module und kompiliert Produktions- und
Testquellen. `-DskipTests` war bei dieser Paketierung ausdrücklich gesetzt.
Separat wurden `BrowserSessionTest` (8), `BrowserSessionIT` (2) und
`AnalysisLiveProgressUiIT` (5) mit insgesamt 15 erfolgreichen Tests ausgeführt.
Diese 15 Tests liefen am ersten Kandidaten vor dem abschließenden reinen
Script-Ladepfadfix. Die Java-Quellen einschließlich der beiden geänderten
Owner-Tests blieben danach bytegleich; der neue Kandidat erhielt erneut den
vollständigen Paket- und Frontendvertragstest. Dies ist keine Behauptung eines
vollständigen Java-Regressionspasses.

Die geprüfte frische Anwendungs-JAR besitzt SHA-256
`5559bf41b57704449ede816f71d0e32e6c6cb5d70cf40ecc99cf06b18b4ba0d4`.
Alle 225 App-Ressourcen wurden mit dem aktuellen Ressourcenbaum verglichen.
Die normalen Maven-/Spring-Boot-Transformationen sind im Manifest benannt;
unerwartete Unterschiede wurden nicht gefunden. Der Baseline-Commit allein
identifiziert diese JAR nicht: Sie enthält zusätzlich die Änderungen dieses
Arbeitszweigs, deren Ressourcenhashes festgehalten sind.

Ein lokaler Metadaten-Sonderfall ist ausdrücklich festgehalten: Das
`git-commit-id`-Plugin las im verknüpften Worktree die Git-Metadaten des
ursprünglichen Checkouts und bettete dadurch eine abweichende Commit-/Branchangabe
ein. Diese eingebettete `git.properties` wird nicht als Quellnachweis verwendet.
Maßgeblich sind das im tatsächlich kompilierten Worktree gelesene `git rev-parse`,
die 42 festgehaltenen Quell-/Testhashes und der byteweise Ressourcenvergleich.
Die Quellen blieben während Paketierung und abschließendem Frontendlauf unverändert.

Nachweise: [Paketierung](evidence/2026-10-07/package-summary.json),
[Frontendverträge](evidence/2026-10-07/frontend-contracts.json),
[ausgewählte Java-Tests](evidence/2026-10-07/selected-java-tests.json) und
[Quell-/Ressourcenmanifest](evidence/2026-10-07/application-build.json).

Toolchain: Temurin JDK 21, Maven-Wrapper 3.9.16, durch den Frontend-POM
gepinntes Node 24.18.0, Playwright 1.61.1, Axe 4.12.1 und Chrome Headless Shell
149.0.7827.55. Die temporäre Buildumgebung verwendet den vorhandenen Proxy
und dessen konfigurierte CA bei weiterhin aktiver TLS-Prüfung. Für die
ausgewählten Java-Tests wurde der bereits verwendete Mockito-Core beim JVM-Start
als Agent geladen, weil nachträgliches Self-Attach in der Umgebung nicht möglich
war. MockMaker, Testauswahl und JaCoCo wurden nicht ausgetauscht.

### Grenzen und verbleibende ergonomische Fragen

- Die lokale Browserabnahme verwendet Chromium. Firefox und WebKit sowie die
  Docker-/Datenbank-/ONNX-Matrix wurden lokal nicht ausgeführt. Sie bleiben
  getrennte CI-Gates und erhalten hier keinen vorweggenommenen Pass.
- Axe prüft automatisierbare Regeln in den tatsächlich besuchten Zuständen.
  Ein fehlerfreier Scan ersetzt weder eine Screenreaderprüfung noch eine
  allgemeine WCAG-Konformitätsaussage.
- Die fachliche Navigation setzt weiterhin Verständnis der Taxonomie,
  Scores, Begründungen und Architekturbegriffe voraus. Die QA belegt
  auffindbare und funktionierende Wege; sie misst keine Erfolgsquote
  unerfahrener Anwender.
- Der Reformulierungsteil einer gespeicherten Anforderung kann bei vielen
  Angeboten viel Platz oberhalb des maßgeblichen Textes beanspruchen. Das ist
  eine verbleibende Frage für eine Prüfung mit repräsentativen gefüllten
  Anforderungen. Ohne entsprechende Messung wird daraus weder ein bestätigter
  Fehler noch eine angeblich abgeschlossene Korrektur abgeleitet.
- Für sehr große Modelle bleiben fachliche Filterung, Übersicht und Fokus
  notwendig. Mehr Zeichenfläche macht nicht automatisch jedes vollständige
  Diagramm auf einer einzelnen Bildschirmseite lesbar.

Die unabhängige Nachprüfung hat die zunächst gefundenen drei wichtigen
Reviewbefunde — Branchvertrag, 0-Begründung und mobiles Exportmenü — nach
Korrektur freigegeben. Es blieben dort keine Critical-/Important-Befunde offen.
Diese Codefreigabe und die tatsächlich ausgeführten lokalen Tests werden von
den anschließend laufenden Remote-CI-Ergebnissen getrennt dokumentiert.

## 6. Reproduzierbarkeit und Quellbezug

Die verbleibenden neuen DSL- und Loader-Node-Fälle sind in die vorhandenen Skripte von
[.github/package.json](../../.github/package.json) aufgenommen. Der maßgebliche
Frontendlauf bleibt:

```bash
./mvnw -B -ntp -f .github/ui-verification-pom.xml verify -Pcontracts
```

Die allgemeinen UI-Browserfolgen verwenden den bestehenden Runner
[run-ui-suite.mjs](../../.github/scripts/run-ui-suite.mjs) und dessen bestehende
Suite-/Profilfilter. Die maßgeblichen CI-Profile werden nicht verkleinert und
keine fehlgeschlagenen Produktprüfungen übersprungen. Die hier ausdrücklich
ausgewählte lokale Matrix ist im abschließenden Prüfnachweis aufgeführt.

Wesentliche Produktionsquellen:

- [Schrittführung und Kontextnavigation](../../taxonomy-app/src/main/resources/static/js/shared/taxonomy-onboarding.js),
  [Haupttemplate](../../taxonomy-app/src/main/resources/templates/index.html),
  [Ergonomie-CSS](../../taxonomy-app/src/main/resources/static/css/taxonomy-ergonomics.css)
  und [Workflow-CSS](../../taxonomy-app/src/main/resources/static/css/taxonomy-analysis-workflow.css).
- [Suchnavigation](../../taxonomy-app/src/main/resources/static/js/shared/taxonomy-search.js)
  und [Bewertung/Begründung im Katalog](../../taxonomy-app/src/main/resources/static/js/core/taxonomy-browse.js).
- [Workbench-Template](../../taxonomy-app/src/main/resources/templates/architecture-workbench.html),
  [Layout](../../taxonomy-app/src/main/resources/static/css/architecture-workbench.css)
  und [Exportmenüsteuerung](../../taxonomy-app/src/main/resources/static/js/architecture-workbench.js).
- [DSL-Befehle](../../taxonomy-app/src/main/resources/static/js/shared/taxonomy-dsl-editor.js).
- [Projektoberfläche](../../taxonomy-app/src/main/resources/static/js/portfolio/taxonomy-portfolio.js),
  [Anforderungsdetail](../../taxonomy-app/src/main/resources/static/js/portfolio/requirement-detail.js),
  [Matrizen](../../taxonomy-app/src/main/resources/static/js/portfolio/portfolio-matrices.js),
  [Versionierung](../../taxonomy-app/src/main/resources/static/js/portfolio/portfolio-versioning.js)
  und [Portfolio-API](../../taxonomy-app/src/main/resources/static/js/api/portfolio-api.js).

## 7. CI-Befunde und überprüfte Folgekorrekturen

### Was die erste Remote-CI zusätzlich gefunden hat

Die Remote-Prüfung des ersten veröffentlichten Stands `26bb32f8…` war nicht
vollständig grün. Ihr vollständiges Core-Artefakt enthält 7.272 Testausführungen
in 1.013 XML-Suiten mit drei Fehlschlägen, ohne Errors oder Skips. Der bestehende
Architektur-Szenariolauf, fünf der sechs UI-Shards, die Frontendverträge,
Interoperabilität, Observability, Reformulierungsnutzung und Kubernetes-Smoke
bestanden. Die Ursachen der roten Prüfungen wurden einzeln untersucht:

| Bereich | Befund am ersten veröffentlichten Stand | Folgekorrektur bzw. Abgrenzung |
| --- | --- | --- |
| Portfolio-Testarchitektur / [Document E2E](https://github.com/carstenartur/Taxonomy/actions/runs/37583519306) | Der vorhandene Architekturvertrag verbietet Portfolio-spezifische Node-Workflows. Die neu hinzugefügten Dateien verletzten diese Regel und blockierten den Lauf vor der eigentlichen E2E-Abnahme. | Alle 109 Fälle und die Portfolio-Browserfolge wurden in das vorhandene JUnit-/Failsafe-System übertragen. Die fünf bisherigen Node-Dateien und ihre Registrierungen sind entfernt. |
| Core und Datenbank-Lanes | Drei bestehende Tests verlangten die alte parameterlose Renderfunktion, eine ersetzte Media Query und den früheren Suchcontainer. | Die bestehenden Testklassen prüfen die tatsächliche Entwurfserhaltung, die aktuelle responsive Navigation und die echten Vorfahren der Suche. |
| Mobiler ADMIN-Einstieg | Mit der CI-Schrift Noto Color Emoji brach ein allein stehen gebliebener Versionstrenner in eine zusätzliche 20-px-Zeile um. Der Hauptbutton endete bei y = 857,1 statt innerhalb der 844 px. | Version und Trenner bilden jetzt eine gemeinsam ausgeblendete Gruppe. Das Eingabefeld bleibt sieben Zeilen bzw. 182 px hoch. |
| [CodeQL](https://github.com/carstenartur/Taxonomy/actions/runs/37583468826) | Eine reguläre Ersetzung von HTML-Tags im neuen Fake-DOM-Testhelfer wurde beanstandet. | Die betreffende Node-Testdatei entfällt nach Übertragung ihrer 13 Fälle auf tatsächliche DOM-Elemente im vorhandenen Browseradapter. Eine neue CodeQL-Freigabe muss zum aktualisierten PR-Stand gehören. |
| [Security-Workflow](https://github.com/carstenartur/Taxonomy/actions/runs/37583519336) | Maven konnte den Spring-Boot-Parent 4.1.1 nicht auflösen; der eigentliche Scanner startete nicht. | POM und Workflow stimmen mit dem zuvor erfolgreichen Baseline-Lauf überein. Aus diesem Infrastrukturfehler wird weder eine neue Produktlücke noch ein bestandener Security-Scan abgeleitet. |

Die Portfolio-Testarchitektur war ein Fehler im ursprünglichen QA-Ansatz.
Die vorhandene Regel, POMs, Failsafe-Auswahl und CI-Gates wurden bei der
Korrektur beibehalten. Es gibt keinen umbenannten parallelen Runner.

Alle drei Datenbank-Jobs scheiterten mit jeweils denselben drei App-Vertragsfehlern
unter 2.609 App-Testausführungen. Nachfolgende Stufen wurden deshalb nicht erreicht.
Das ist keine Freigabe der gesamten Datenbankmatrix. Job-IDs, Ergebnisse und
Ursachen stehen im [Nachweis des ersten CI-Stands](evidence/2026-10-07/ci-original-head-results.json).

### Tatsächlich ausgeführte JUnit- und Frontend-Folgeprüfung

Für alle **109 übertragenen Clientfälle** liegen erfolgreiche JUnit-/Selenium-
Ausführungen vor: 37 Routingfälle, 40 Recoveryfälle, 13 Versionsvergleiche,
neun Entwurfs-/Fehlerfälle und zehn Matrixfälle. Der
[Migrationsnachweis](evidence/2026-10-07/portfolio-junit-migration.json)
ordnet jeden Fall seinem erfolgreichen Lauf zu. Er behauptet keinen einzelnen
zusammenhängenden grünen Lauf aller 109 Fälle.

Die Tests laden echte Templates, Übersetzungen und Produktionsskripte. Sie prüfen
sichtbare Felder, Fokus, Cursorpositionen, native Tastaturbedienung und tatsächliche
Downloads. Kontrollierte HTTP-Antworten stellen langsame Reads, Fehler und echte
DTO-Strukturen bereit. Die nach der Übertragung gefundenen Testfehler betrafen
Bootstrap-Übergänge, einen unvollständigen Versionsverlauf in der Fixture und
die Bedienung nativer Auswahlfelder. Die korrigierte Bedienung löst echte
Browserereignisse aus; künstliche Input-Events ersetzen sie nicht.

Der vorhandene vollständige **`PortfolioUiAcceptanceIT` bestand mit 3/3 Fällen**:
der ursprüngliche Backend-Ablauf, die ergänzten Root-Prüfungen und dieselben
Prüfungen nach einem echten Neustart unter `/taxonomy`. Die Vorbereitung bestand
mit **15/15 Tests**: acht vorhandene Browseradapterfälle, fünf Proxyfälle und
zwei Prüfungen der tatsächlich ausgelieferten statischen Ressourcen. Der Lauf
nutzte das bestehende `test-local`-Profil, eine echte Spring-Anwendung und eine
pro Testlauf isolierte persistente Datenbank. Er enthält 14 Browseraufnahmen.
Der [vollständige Nachweis](evidence/2026-10-07/portfolio-ui-acceptance-junit.json)
führt Befehle, Testnamen, Laufzeiten, Hashes und die Grenze zu einem neuen
Docker- oder Axe-Lauf auf.

Der erste Versuch dieses vollständigen Owners scheiterte beim Aufräumen des
Kontextfalls an einem nicht wiederhergestellten Sprach-Cookie. Außerdem
verursachte eine zweite leere Datenbank im selben JVM unnötige Workspace-
Startfehler. Der erfolgreiche neue Lauf stellt den ursprünglichen `lang`-Cookie
mit seinen Attributen wieder her und behält die testisolierte Datenbank über
den Mountwechsel. Ein privater Bootstrap-Status wird nicht zurückgesetzt.

Das unveränderte Maven-Profil für die verbleibenden Frontendverträge bestand
erneut mit **994 unterschiedlichen Fällen**. Hinzu kommen dieselben 26 API-Fälle
im separaten Maven-Schritt: insgesamt **1.020 erfolgreiche Ausführungen**, ohne
Fehler, Abbrüche oder Skips. Die 109 nun von JUnit geprüften Fälle fehlen nicht
in der Abdeckung; sie haben den zuständigen Runner gewechselt. Der
[Frontendnachweis](evidence/2026-10-07/frontend-contracts-after-migration.json)
enthält die getrennten Zählungen und die Hashes der verbleibenden Registrierung.

Die drei betroffenen bestehenden Core-Klassen bestanden anschließend gemeinsam
im regulären Maven-Lauf mit **14/14 Tests**, ohne Failures, Errors oder Skips:
`ReformulationAdoptionTest` (3), `TaxonomyResponsiveNavigationContractTest` (3)
und `TaxonomySearchLargeResultContractTest` (8). Der Adoption-Owner umfasst auch
zwei getrennte Anwendungsprozesse und den vorhandenen Persistenz-/HTTP-Nachweis.
Die responsive Prüfung kontrolliert die Regeln für sichtbare Navigation,
gemeinsame Zeile und 44-px-Bedienelemente innerhalb ihrer tatsächlichen CSS-Blöcke.
Die Suchprüfung kontrolliert zusätzlich die echte Template-Elternstruktur mit
dem bereits vorhandenen HTML-Parser.

Der Entwurfsvertrag führt die tatsächliche Kette `loadAll` → `renderAll` →
`renderCurrentText` aus. Drei ausschließlich temporär veränderte Quellkopien
belegen, dass der Test den Verlust der Entwurfserhaltung, eine verspätete alte
Antwort und eine nicht weitergereichte Preserve-Angabe tatsächlich zurückweist.
Die Produktionsdatei bleibt bei diesen Gegenproben unverändert. Die frischen
Surefire-Berichte, der Maven-Exitcode und die Mutationsgegenproben stehen im
[Core-Folgenachweis](evidence/2026-10-07/core-ui-contracts-after-ci.json).
Die gespeicherte Konsolendatei ist unvollständig; sie wird deshalb nicht als
vollständiger Buildlog ausgegeben. Maßgeblich sind die drei neu geschriebenen
Berichte mit positiven Ausführungszahlen und der erfolgreiche Prozessabschluss.

### Endgültige Paketierung und erneute mobile Abnahme

Der abschließende normale Maven-`install`-Lauf bestand mit **16/16 Modulen**.
Er kompiliert und paketiert die Anwendung und installiert die eigenen Module
im lokalen Maven-Repository; Tests waren bei diesem Paketierungsschritt
ausdrücklich übersprungen. Die unabhängigen Testausführungen stehen oben.
Die danach eingefrorene Anwendungs-JAR besitzt SHA-256
`8e264dd0884bb2542bb48a5f337c25e26df173cc686ebab4ce224d4ddc29dd07`.
Alle **225 Ressourcen**, einschließlich des aktualisierten Portfolio-README,
stimmen nach den dokumentierten normalen Buildtransformationen überein.
Produkt- und Testquellen blieben während des Builds unverändert.

An genau dieser JAR, **ohne Ressourcenoverride**, bestand die aktuelle
ADMIN-Primary-Folge mit **64 Workflowchecks und 21 Axe-Zustandsprüfungen ohne
Violations**. Sie erzeugte 18 Screenshots. Die unveränderte allgemeine
Layoutfolge bestand außerdem mit 18 Assertions und ohne PageErrors; sie ist
eine fokussierte Wiederholung derselben Layoutabdeckung und wird nicht zu den
64 ADMIN-Checks als zusätzliche unabhängige Abdeckung addiert.

Die Fontmessung im Browser bestätigt nun tatsächlich Noto Color Emoji.
Bei **390×844** sinkt die Navbar durch die gemeinsame Versionsgruppe von
144 auf **124 px**. Die fachliche Eingabe bleibt **182 px** hoch. Die Unterkante
der Hauptaktion liegt bei **y = 837,1 px**, somit wieder vollständig innerhalb
des Viewports. Die bestehende Grenzprüfung wurde nicht gelockert.
Das [aktuelle mobile Bild](images/context-layout-2026-10-07/analyze-390-ci-font-after.png)
zeigt diesen Zustand am endgültigen Paket mit der CI-Schrift. Die älteren
Vorher-/Nachher-Bilder bleiben als historische Vergleichspaare erhalten.

Nachweise: [Build-/Ressourcenmanifest](evidence/2026-10-07/ci-followup-application-build.json),
[Laufzeitübersicht](evidence/2026-10-07/ci-followup-runtime-summary.json),
[ADMIN-Bericht](evidence/2026-10-07/ci-followup-primary-admin.json),
[Layout-/Fontmessungen](evidence/2026-10-07/ci-followup-context-layout.json)
und [Erläuterung des mobilen CI-Befunds](evidence/2026-10-07/ci-followup-runtime.md).

### Unveränderte Buildregeln und Statusgrenze

Nach der Installation aller aktuellen Module bestand die vollständige Testphase
von `taxonomy-build` mit **43 Testklassen** und `BUILD SUCCESS`.
Die Berichte enthalten **392 Fälle: 382 bestanden, zehn übersprungen**, ohne
Failures oder Errors. Die zehn Skips stammen ausschließlich aus bestehenden
Helm-Voraussetzungen, weil Helm 3 lokal nicht verfügbar ist. Die CI verlangt
weiterhin ihre vorhandenen tatsächlichen Chart-Prüfungen.

Der für die Migration entscheidende **`PortfolioTestArchitectureContractTest`
bestand unverändert mit 1/1**. Die zusätzlich vorgeschriebenen Architektur-
Ausführungen bestanden ebenfalls: Modulgraph 140, Modulextraktion zwei und
Selektorsynchronisierung 71 Fälle. Diese sind bereits in den 392 gemeldeten
Fällen enthalten. Root-/App-/Build-POM, Frontend-POM und Portfolio-Vertrag
wurden bytegleich zum veröffentlichten Vorgänger überprüft. Details stehen im
[Buildregel-Nachweis](evidence/2026-10-07/build-policy-after-migration.json).

Die unabhängigen Abschlussreviews fanden keine weitere blockierende Lücke in
der JUnit-Übertragung, ihren DTOs, der Entwurfserhaltung oder dem normalen
Containerpfad. Die lokale Abnahme bleibt eine gezielte Prüfung mit den oben
genannten Ergebnissen. Die zu einem aktualisierten veröffentlichten Stand
gehörenden Remote-Gates werden gesondert in den
[aktuellen PR-Prüfungen](https://github.com/carstenartur/Taxonomy/pull/1180/checks)
geführt; ein alter oder lokaler Pass wird ihnen nicht zugerechnet.

## 8. Zweite CI: Klickflächen, Auswahlkontrast und belastbare Fehlerdiagnostik

### Ergebnis des Stands `27f6b514…`

Die zweite Remote-CI gehört zu Commit
`27f6b514de45d54a8a69c73e0ee502cca7e26ce9` und zum synthetischen PR-Merge
`3e2e0b6814b8be60e2a948346a6c8baa70d5f9dd`. Beide haben nach unabhängiger Prüfung
denselben Source-Tree `6fe56ba0987f54cdc1bd225240aacc3d25e7cde9`.
Sie war erneut **nicht vollständig grün**. Die einzelnen Ergebnisse erlauben
aber eine klare Eingrenzung:

| Prüfung | Tatsächliches Ergebnis dieses Stands |
| --- | --- |
| Core | 7.960 gemeldete Testausführungen in 1.085 XML-Suiten; zwei Failures, keine Errors, 79 bestehende Skips. |
| Übertragene Portfolio-Clientfälle | Alle 109 Fälle bestanden in demselben Core-Lauf, ohne Skips. |
| Vollständiger Portfolio-Browserowner | Der ursprüngliche Backend-Hauptfall bestand. Die beiden ergänzten Kontextfälle scheiterten am Anforderungslink, jeweils schon bei 1366×768. |
| PostgreSQL, Oracle und MSSQL | Alle drei Lanes bestanden mit jeweils 16 Modulen und 2.616 App-Surefire-Ausführungen. Ihre Datenbank-Failsafe-Folgen meldeten 52, 32 bzw. 32 Fälle, jeweils ohne Fehler oder Skips. Gemeinsame Tests werden dadurch mehrfach ausgeführt. |
| Portfolio-Architektur- und Helm-Verträge | In allen drei Datenbank-Lanes positiv ausgeführt: Portfolio-Architektur 1/1, Artemis-Chart 9/9 und eingeschränkter Helm-Smoke 3/3. Die entsprechenden lokalen Helm-Skips bleiben im lokalen Bericht sichtbar. |
| UI, Frontend, Sicherheit und weitere E2E-Lanes | Alle sechs UI-Shards, Frontendverträge, Anwendungspaketierung, Interoperabilität, Observability, Security, maßgeblicher Push-CodeQL-Lauf, Document E2E, Architektur-Szenario, Reformulierungsnutzung und Kubernetes-Smoke bestanden. |
| Abschließende Zusammenführung | Korrekt am fehlgeschlagenen Core-Gate gestoppt. Digest-Prüfung, Finalisierung und abschließende Uploads wurden nicht ausgeführt. |

Die 79 Core-Skips stammen ausschließlich aus den vorhandenen Klassen
`ScreenshotGeneratorIT` (75), `MockScoreGeneratorIT` (3) und
`ObservabilityPerformanceIT` (1). Der Core-Reaktor erreichte das nachgelagerte
Buildregelmodul nach den App-Failsafe-Fehlern nicht. Dessen positive Prüfung
in den Datenbank-Lanes ist deshalb kein behaupteter Core-Pass.

Der Security-Lauf erreichte diesmal tatsächlich Paketierung und Scanner.
CodeQL meldete keine blockierenden Befunde; drei bestehende Java-Baselineeinträge
bleiben akzeptiert. Das ist keine Aussage, dass sämtliche Analysehinweise
verschwunden seien. Die beiden redundanten PR-CodeQL-Jobs wurden absichtlich
übersprungen; ihr Status wird nicht als zusätzliche erfolgreiche Analyse gezählt.

Die [CI-Ergebnisdatei](evidence/2026-10-07/ci-migration-head-results.json)
enthält Zuordnung, Job- und Artefakt-IDs, geprüfte Artefakthashes, positive
Testnamen und die nicht erreichten Abschlussstufen.

### P2 — Der Anforderungstitel hatte keine verlässlich zusammenhängende Klickfläche

Die ursprüngliche Browserprüfung kombinierte Mindestmaße von 24×24 px mit den
Viewportgrenzen und gab bei einem Fehlschlag nur einen Wahrheitswert aus.
Aus dem CI-Fehler allein lässt sich deshalb nicht behaupten, der Link sei
abgeschnitten gewesen. Das CI-Artefakt enthielt auch keine Rechteck- oder
Schriftdaten, aus denen sich die genaue Bedingung nachträglich ablesen ließe.

Die anschließende Diagnose verwendete den vorhandenen Java-/Selenium-Browseradapter,
die echten Produktionsressourcen und dieselben typisierten Projekt-Fixtures.
Sie zeigte zwei tatsächliche Schwächen des Inline-Links:

- Mit lokal tatsächlich gerendertem **Nimbus Sans** bei 1366×768 war der Link
  trotz 24 px Zeilenhöhe nur **16 px hoch**. Er lag vollständig im Viewport,
  verfehlte aber das unveränderte Mindestmaß. Nimbus Sans ist eine nachgewiesene
  Schriftvariation; die tatsächliche Schrift des fehlgeschlagenen CI-Laufs ist
  damit nicht identifiziert.
- Mit **DejaVu Sans** bei 1366×768 bestand die Gesamtbox von etwa **201×43 px**
  aus zwei jeweils 19 px hohen Fragmenten. Die alte Rechteckprüfung bestand,
  aber der Mittelpunkt traf die Tabellenzelle zwischen den Linkzeilen.
  Ein Klick auf diesen scheinbaren Bestandteil des Titellinks konnte folglich
  ohne Navigation bleiben.

Der Titel erhält jetzt eine zusammenhängende Inline-Block-Fläche mit mindestens
24×24 px. Schriftgröße und Zeilenhöhe bleiben erhalten. In den gezielten
Vorher-/Kandidatenmessungen blieben Zeilen- und Tabellenhöhe unverändert:
83/124 px mit Nimbus Sans auf dem Laptop, 128/169 px mit DejaVu Sans auf dem
Laptop sowie 215/256 px im mobilen Zustand. Der zusätzliche anklickbare Raum
vergrößert in diesen gemessenen Zeilen somit nicht die Tabelle.

Der bestehende Browserowner prüft weiterhin dieselben Mindestmaße und Grenzen.
Er kontrolliert zusätzlich, ob der tatsächliche Mittelpunkt den Link oder ein
Kind des Links trifft, und gibt bei Fehlern Rechteck, einzelne Textfragmente,
Schriftangaben, Viewport und jede einzelne Grenzbedingung aus. Die Navigation
wird nun auch bei 1920×1080 ausgeführt, zusätzlich zu 1366×768 und 390×844.

### P2 — Die aktive Projektauswahl wurde bei Hover und Tastaturfokus unlesbar

Bei der visuellen Nachprüfung fiel ein älterer CSS-Fehler auf: Die allgemeine
Hover-/Fokusregel übermalte den blauen Hintergrund auch beim aktiven Projekt
mit einem nahezu weißen Hintergrund, während seine Schrift weiß blieb.
Das betraf Projektschlüssel, Titel und die Zeile mit den Anforderungszahlen.

Die Diagnose bestätigt den Fehler sowohl mit einer echten Mausbewegung als auch
mit nativem Tab-Fokus. Weiß auf `rgb(248,249,250)` ergab **1,054:1** Kontrast.
Die Hover-/Fokusregel gilt jetzt nur noch für inaktive Projekte; die aktive
Auswahl behält ihren vorhandenen blauen Hintergrund `rgb(13,110,253)`.
Die gemessene Gegenprobe ergibt für alle drei Textteile **4,50078:1**.
Die bereits vorhandene volle Deckkraft der Metadaten bleibt erhalten.

Der gleiche Browserowner prüft mindestens **4,5:1** für alle drei Textteile,
einschließlich tatsächlicher Hintergründe und Deckkraft der Vorfahren.
Er unterscheidet reale Maus- und Tastaturzustände und kontrolliert weiterhin
die aktive semantische Kennzeichnung mit `aria-current="page"`.

Die [Diagnose mit Messwerten](evidence/2026-10-07/portfolio-target-and-selection-diagnosis.json)
kennzeichnet ihre temporär injizierten CSS-Kandidaten ausdrücklich. Sie werden
nicht als Aufnahmen einer abschließend paketierten Anwendung ausgegeben.

### Nachweis der tatsächlich eingebauten Portfolio-Korrektur

Der verstärkte vorhandene `PortfolioUiAcceptanceIT` wurde zunächst gegen das
unveränderte Produktions-CSS ausgeführt. Der bisherige Hauptfall und alle
15 ausgewählten Infrastrukturtests bestanden; beide Kontextfälle scheiterten
erwartungsgemäß am tatsächlichen Mittelpunkt des fragmentierten Links.
Alle sechs bisherigen Mindestmaß-/Viewportbedingungen waren dabei erfüllt.

Danach wurde ausschließlich das beschriebene CSS korrigiert und **dieselbe
Testquelle bytegleich** erneut ausgeführt. Dieser kanonische
`verify -Ptest-local`-Lauf bestand mit **3/3 Browserfällen und 15/15
Infrastrukturtests**, ohne Failures, Errors oder Skips, Exit 0 und
`BUILD SUCCESS`. Die Browserklasse lief 117,354 Sekunden. Die beiden Kontextfälle
prüfen alle drei Bildschirmgrößen sowie echte Hover- und Tab-Fokuszustände.
Ein unabhängiger Review der beiden Quelldateien fand keine blockierende Lücke.

Die Abnahme erzeugte **28 frische Bilder**. Die Zuordnung beruht auf dem
festgehaltenen Start-/Endfenster und Prüfsummen; ältere `failure.png` und
unbeteiligte XML-Berichte aus dem vorhandenen `target` werden nicht mitgezählt.
Das [RED/GREEN-Manifest](evidence/2026-10-07/portfolio-target-owner-verification.json)
enthält genau die vier ausgewählten XML-Suiten, ihre einzelnen Testnamen,
Quellhashes und die Abgrenzung der Diagnoseprobe.

| Ansicht | Beleg |
| --- | --- |
| Aktives Projekt bei Mausberührung vor der Korrektur | [Originales CSS in der Diagnoseprobe](images/context-layout-2026-10-07/before-selected-project-hover-1366.png) |
| Aktives Projekt bei Mausberührung nach der Korrektur | [Frischer kanonischer Lauf, 1366×768](images/context-layout-2026-10-07/after-selected-project-hover-1366.png) |
| Aktives Projekt mit nativem Tastaturfokus | [Frischer kanonischer Lauf, 390×844](images/context-layout-2026-10-07/after-selected-project-focus-390.png) |

Der erfolgreiche Lauf umfasste die reguläre Paketierung von Anwendung und
Abhängigkeiten: **13 ausgewählte Reaktormodule** bestanden. Die daraus erzeugte
JAR mit SHA-256
`244d82091f47837567e4917f34da6c818dc351546f48ce8f254204c65ae08f75`
wurde eingefroren und gegen **alle 225 Quellressourcen** geprüft; es gab keine
Abweichung nach den dokumentierten normalen Buildtransformationen.
Das korrigierte CSS stimmt außerdem byteweise mit der vom Browserowner benutzten
Classpath-Ressource überein. Dieser Owner startet eine echte Spring-Anwendung;
es wird kein zusätzlicher Browserlauf direkt aus der eingefrorenen JAR behauptet.
Nachweis: [Paket- und Ressourcenmanifest](evidence/2026-10-07/portfolio-target-application-build.json).

Das unveränderte Frontend-Maven-Profil bestand am selben Korrekturstand erneut:
**994 unterschiedliche Fälle**, zuzüglich derselben 26 API-Fälle im separaten
Maven-Schritt, somit **1.020 erfolgreiche Ausführungen**, ohne Fehler, Abbrüche
oder Skips. Der [Frontend-Nachweis](evidence/2026-10-07/frontend-contracts-target-fix.json)
führt die tatsächlichen Zählblöcke, Node 24.18.0 und Quellhashes auf.
Die neue Remote-CI bleibt ein eigener, zum veröffentlichten Folgecommit
gehörender Nachweis in den [PR-Prüfungen](https://github.com/carstenartur/Taxonomy/pull/1180/checks).

### CI-Diagnostik bleibt auch bei einem echten Fehler verfügbar

Das fehlgeschlagene Core-Artefakt enthielt die Testberichte, aber weder den
vollständigen Maven-Log noch die Portfolio-Kontextbilder. Der vorhandene
Staging-Schritt brach vor der Logkopie wegen fehlender aggregierter Coverage ab;
die Kontextbilder standen außerdem noch nicht in seiner Dateiliste.

Der bestehende Staging-Schritt sichert jetzt Log und die vorhandenen
`root`-/`taxonomy`-Kontextbilder vor dieser Prüfung. Der Coverage-Fehler bleibt
ein harter Fehler mit demselben Exitcode. Workflow, POMs, Testauswahl und
Abschlussgate wurden dabei nicht gelockert; fachliche Downloads werden nicht
in die Diagnosekopie aufgenommen. Die damaligen nicht hochgeladenen Bilder
lassen sich durch diese Änderung nicht rückwirkend wiederherstellen.

Drei neue JUnit-Fälle führen das echte Bash-Skript aus. Vor der Korrektur
scheiterten zwei davon an den fehlenden Diagnosedateien. Danach bestanden
**3/3** ohne Errors oder Skips. Sie prüfen Fehler- und Erfolgsfall,
unveränderten Provenienzbezug, wiederholtes Staging ohne alte Restbilder sowie
fehlende optionale Diagnosedateien. Die vorgeschriebenen Architekturprüfungen
bestanden ebenfalls (140 + 2 + 71). Die vier vorhandenen Node-Vertragsfälle
bestanden unverändert; eine unabhängige Wiederholung mit dem gepinnten
Node 24.18.0 bestätigt dieselben vier Fälle und zählt nicht als neue Abdeckung.
Zwei unabhängige Reviews fanden keine blockierende Lücke.
Details: [Staging-Nachweis](evidence/2026-10-07/core-evidence-staging.json).

### Verbleibender Befund zur Platzverteilung im Portfolio

Die Portfolioansicht ist im untersuchten Desktop-Leerzustand weiterhin nicht
optimal gewichtet. Sechs Kennzahlen stehen bei 1366 px in zwei Kartenreihen;
anschließend beansprucht eine leere Analyseauftragsanzeige mit Erklärung,
Statusfilter, Zusammenfassung und Leertext weiteren Platz vor den Anforderungen.
Wer gerade eine Anforderung bearbeiten möchte, muss dadurch an viel derzeit
wenig relevanter Übersicht vorbeiscrollen.

Das ist ein begründeter ergonomischer Befund aus der tatsächlichen Ansicht und
dem zugehörigen Renderpfad, kein nachgewiesener Clipping- oder Datenverlustfehler.
Die [Desktopaufnahme des korrigierten Stands](images/context-layout-2026-10-07/after-selected-project-hover-1366.png)
zeigt diese verbleibende Gewichtung. Die Kennzahlenstruktur steht im
[Projekt-Template](../../taxonomy-app/src/main/resources/templates/projects.html),
der Leerzustand im vorhandenen
[Auftragsrenderer](../../taxonomy-app/src/main/resources/static/js/portfolio/taxonomy-portfolio-async.js).
Bei laufenden Aufträgen können Fortschritt und Fehlerstatus dagegen die wichtigste
Information sein. Ein weiterer Entwurf sollte deshalb den wirklich leeren
Zustand kompakter machen und ihn von einer nur durch den Filter leeren Liste
unterscheiden. Diese zusätzliche Umgestaltung ist in diesem Reparaturschritt
noch nicht umgesetzt und wird nicht als erledigt ausgegeben.
