# QA: Bildschirmfläche, Kontext und Bedienbarkeit — 7. Oktober 2026

## Urteil

Der Ausgangsstand nutzte den verfügbaren Bildschirmplatz an mehreren zentralen
Stellen unzweckmäßig. Die Schrittführung verdrängte die eigentliche Eingabe,
der DSL-Editor teilte sich seine bereits schmale Spalte nochmals mit Werkzeugen,
und die Architektur-Workbench schnitt auf üblichen Laptopgrößen ihre Zeichenfläche
und Detailspalte unten ab. Daneben erschwerten schwer auffindbare Projektfunktionen,
falsche Ergebnisführung und unzuverlässige Fehlerrückmeldungen die Bedienung.

Die nachstehend belegten Fehler sind korrigiert. Die Änderungen konzentrieren
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

| Neue Testdatei | Fälle | Vertrag |
| --- | ---: | --- |
| `dsl-command-feedback.test.mjs` | 48 | HTTP-/Validierungsfehler, Entwurferhalt, Status-Timer und tatsächliche Zähler |
| `requirement-version-review.test.mjs` | 13 | Zeilenreihenfolge, Wiederholungen, Escape, Auswahl und begrenzter Großvergleich |
| `portfolio-basepath.test.mjs` | 37 | Root-, gemountete und verschachtelte Pfade; Job-URLs; tatsächliche Branch-Optionen |
| `portfolio-review-state.test.mjs` | 19 | Versionsmodal/Entwurf sowie Matrixzellen, Filter und Detailbedeutung |
| `portfolio-versioning-recovery.test.mjs` | 40 | Nachrichten, unabhängige Auswahl, verzögerte Reads und getrennte Mutation-/Read-Ergebnisse |
| Ergänzungen in `test-taxonomy-api-client.mjs` | 26 | Tatsächlicher Loader, I18n-Auflösung, eigener Scriptpfad, Root-Fallback und Vermeidung doppelter Module |
| **Neue Node-Fälle insgesamt** | **183** | Bestehende 920 Fälle bleiben in der maßgeblichen Prüfung enthalten. |

Die neuen Browserfolgen sind in die bestehende ADMIN-Primary-Abnahme eingebunden:
[ui-context-layout-workflow.mjs](../../.github/scripts/ui-context-layout-workflow.mjs)
und [ui-portfolio-context-workflow.mjs](../../.github/scripts/ui-portfolio-context-workflow.mjs).
Sie benutzen tatsächliche Templates, Produktionsskripte, Bootstrap/D3, native
Tastenereignisse, DOM-Fokus und Geometrie. Kontrollierte Datenantworten sind
ausdrücklich benannt. Die bereits vorhandenen Druck-, Diagramm-, Such- und
Abbruchprüfungen bleiben Teil des Workflows.

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

## 5. Abschließende Verifikation

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

Die neuen Node-Fälle sind in die vorhandenen Skripte von
[.github/package.json](../../.github/package.json) aufgenommen. Der maßgebliche
Frontendlauf bleibt:

```bash
./mvnw -B -ntp -f .github/ui-verification-pom.xml verify -Pcontracts
```

Die Browserfolgen verwenden den bestehenden Runner
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
