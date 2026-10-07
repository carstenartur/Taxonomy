# QA: Bildschirmfläche, Kontext und Bedienbarkeit — 7. Oktober 2026

## Ergebnis und Umfang

Die Prüfung fand erhebliche Probleme bei Platzverteilung, Ergebnisführung und
Fehlerrückmeldungen. Die behobenen Befunde sind in
[PR #1180](https://github.com/carstenartur/Taxonomy/pull/1180) und der
[Portfolio-Fortsetzung #1183](https://github.com/carstenartur/Taxonomy/pull/1183)
umgesetzt. Die unten aufgeführten Restbefunde bleiben offen.

Geprüft wurden bestehende Oberflächen und Aufgabenfolgen mit Quellcode-Review,
tatsächlicher Browserbedienung, Geometriemessungen, automatisierten
Barrierefreiheitsprüfungen und gezielten Fehlerfällen. Die Layoutgrößen waren
1920×1080, 1366×768, 1024×768 und 390×844 CSS-Pixel; das Exportmenü wurde zusätzlich
bei 320 Pixeln geprüft. Die Anwendung lief mit dem vorhandenen Mock-Provider.
Kostenpflichtige Modellaufrufe wurden nicht verwendet.

Die Bewertung der Bedienbarkeit beruht auf diesen Prüfungen. Eine Studie mit
Erstnutzern, eine vollständige Screenreaderprüfung und eine allgemeine
WCAG-Zertifizierung wurden nicht durchgeführt.

## Behobene Befunde

| Bereich | Problem und Korrektur |
| --- | --- |
| Analyse | Ausführliche Schrittführung verdrängte Eingabe und Startaktion. Kompakte Schritte und bedarfsgerechte Hinweise schaffen Platz; das Eingabefeld bleibt sieben Zeilen hoch. |
| DSL | Der Editor erbte die schmale Analysespalte. Er nutzt jetzt die Hauptbreite mit einer eigenen, schmaleren Werkzeugspalte. HTTP- und Validierungsfehler werden als Fehler angezeigt; Entwürfe bleiben erhalten. |
| Architektur-Workbench | Feste Höhen schnitten Canvas und Details auf Laptops ab. Die Ansicht verteilt die tatsächlich verbleibende Höhe. Exportformate liegen in einem tastaturbedienbaren Menü, das auch mobil innerhalb des Bildschirms bleibt. |
| Katalog und Suche | Die Katalogkarte wurde auf die Höhe langer Nebeninhalte gestreckt. Die Suche ist wieder direkt im Browser zugänglich. „Ähnliche finden“ öffnet den benötigten Bereich. |
| Ergebnisse prüfen | Die Navigation wählt den höchsten gültigen Score eines bekannten Knotens, öffnet dessen Vorfahren und übergibt den Fokus. Nullbewertungen, unbekannte IDs und vorhandene Begründungen werden korrekt behandelt. |
| Projektwerkzeuge | Anforderungslinks haben zusammenhängende Klickflächen. Die aktive Projektauswahl bleibt bei Hover und Tastaturfokus lesbar; Projektwerkzeuge sind auch mobil erreichbar. |
| Versionsdialoge | Fehler erscheinen im offenen Dialog. Entwurf, Fokus und Quellenfelder bleiben bei HTTP 400/409 erhalten. Hintergrundantworten überschreiben neu eingegebene Werte nicht. |
| Matrizen und Versionsvergleich | Null, fehlende und unbekannte Abdeckung bleiben unterscheidbar. Vergleichsreihenfolge, doppelte Zeilen und Text-Escaping sind erhalten. Branch-Auswahl und Versionierung folgen dem tatsächlichen API-Vertrag. |
| Application Context | Portfolio-Routen, dynamisch geladene Module, Links und Downloads funktionieren unter `/` und `/taxonomy` über die vorhandene zentrale Pfadauflösung. |
| Portfolio-Platzverteilung | Sechs Kennzahlen nutzen bei geeigneter Breite eine Reihe und umbrechen bei größerem Text. Eine wirklich leere Jobhistorie bleibt kompakt; eine gefilterte leere Liste behält Filter und Gesamtzahlen. |
| Portfolio-Sprache | Deutsche und englische Zählungen, Singular-/Pluralformen und Statusbezeichnungen verwenden das vorhandene Wörterbuch. Nutzereingaben bleiben unverändert. |
| Jobsuche und Abruffehler | Eine erfolgreich abgeschlossene Projektauswahl startet die begrenzte Jobsuche erneut. Formularereignisse werden sofort gebunden; langsame Übersetzungen bleiben im Ladezustand abgesichert. Ein erfolgreicher Abruf entfernt veraltete Pollingwarnungen. |
| Jobdetails | Aktualisierungen erhalten unveränderte Karten sowie Fokus und horizontale Tabellenposition. Geänderte Karten erhalten ihre vorherige Position und ihr passendes Fokusziel zurück. |

## Gemessene Platzgewinne

Vorher und nachher wurden jeweils mit demselben Browser- und Schriftstand und
vergleichbaren Daten gemessen. Die Werte beschreiben die geprüften Zustände;
sie sind keine Zusage für beliebige Textlängen, Schriftarten oder Jobhistorien.

| Situation | Vorher | Nachher |
| --- | ---: | ---: |
| Analyse, 1366×768: Höhe der Schrittführung | 347,9 px | 133,5 px |
| Analyse, 1366×768: Unterkante der Startaktion | y = 936,7 px | y = 698,3 px |
| Analyse, 390×844: Unterkante der Startaktion | y = 1219,6 px | y = 837,1 px |
| DSL, 1366×768: Editorbreite | 310,1 px | 882,0 px |
| Workbench, 1366×768: sichtbare Zeichenhöhe | 359,5 px | 506,9 px |
| Workbench, 1024×768: sichtbare Zeichenhöhe | 288,3 px | 433,3 px |
| Katalog neben langem Log: ungenutzte Kartenhöhe | 2215,5 px | 1,0 px |
| Portfolio, 1366×768: Höhe der Kennzahlen | 232,00 px | 108,17 px |
| Portfolio, 1366×768: Höhe der leeren Jobhistorie | 198,00 px | 50,00 px |
| Portfolio, 1366×768: Beginn der Anforderungen | y = 756,97 px | y = 485,14 px |
| Portfolio, 1366×768: Unterkante der ersten Anforderung | y = 945,47 px | y = 673,64 px |
| Portfolio, 390×844: Höhe der leeren Jobhistorie | 303,00 px | 75,00 px |

Die Portfolio-Geometrie stimmt in den geprüften Zuständen für `/` und `/taxonomy`
überein. Bei 200 Prozent Wurzelschriftgröße bleiben Werte, Beschriftungen und
Projektbutton lesbar und innerhalb ihrer Container. Mobil steht die Projektliste
weiterhin vor dem Arbeitsbereich; die erste Anforderung liegt dort noch nicht
im ersten Bildschirmausschnitt.

Ausgewählte Dokumentationsbilder: [Analyse vorher](images/context-layout-2026-10-07/analyze-1366-before.png),
[Analyse nachher](images/context-layout-2026-10-07/analyze-1366-after.png),
[DSL nachher](images/context-layout-2026-10-07/dsl-1366-after.png) und
[Workbench nachher](images/context-layout-2026-10-07/workbench-1366-after.png).

## Regressionen und ausgeführte Prüfungen

Die Portfolio-Regressionen liegen in den vorhandenen Java-/JUnit-/Failsafe-Tests:
`PortfolioUiAcceptanceIT`, `PortfolioClientRoutingIT`,
`PortfolioVersioningRecoveryIT`, `RequirementVersionReviewIT`,
`PortfolioRequirementReviewIT` und `PortfolioMatrixReviewIT`. Sie verwenden
`BrowserSession`, die echten Templates und Produktionsskripte sowie kontrollierte
lokale HTTP-Fixtures. Fehlerzustände werden über HTTP-Antworten ausgelöst.
Die DSL- und Loader-Regressionen gehören zu den bestehenden, Maven-gesteuerten
Frontend-Verträgen. Es gibt keinen zusätzlichen Portfolio-Testworkflow.

Die letzte lokale Abnahme der Fokus-/Scrollkorrektur am 7. Oktober 2026 bezog sich
auf den anschließend als `eaca423` veröffentlichten Stand:

| Prüfung | Ergebnis |
| --- | --- |
| Gegenprobe mit Produktion aus `93d85bd` und denselben vier Testquellen | 15 Supporttests bestanden; der UI-Test scheiterte an den sechs erwarteten Fokus-/Scroll-Assertiongruppen; keine Testfehler oder Skips |
| Vollständige ausgewählte Portfolio-Abnahme | 63 bestanden; keine Failures, Errors oder Skips |
| Test- und Architekturverträge | 217 bestanden; keine Failures, Errors oder Skips |
| Bestehende Frontend-Verträge | 1.020 Ausführungen in 39 Blöcken bestanden; keine Failures oder Skips. Darin ist der 26-Fälle-API-Vertrag zweimal enthalten. |

Die Browserprüfung öffnet Details mit Enter, prüft den folgenden Tab-Schritt und
hält echte Job-GET-Antworten bis zur gezielten Freigabe zurück. Nach einer fremden
Aktualisierung blieb die Fehlerdetailtabelle bei `scrollLeft = 142`, nach einer
eigenen Aktualisierung die laufende Tabelle bei `scrollLeft = 63`; vor der
Korrektur wurden beide auf 0 zurückgesetzt und verloren den Fokus. Die neuen
Antwortinhalte erschienen weiterhin unter beiden Anwendungspfaden.

Diese fokussierten Ergebnisse ersetzen keine vollständige Abnahme aller
Datenbank-, Browser-, ONNX-, Deployment- und Sicherheitsprüfungen. Die
Maven-Aufrufe und ihre aktuellen Voraussetzungen stehen in
[Tests und GUI ohne Docker](../testing/docker-free-tests.md). Der dortige lokale
Testumfang darf nicht als gleichwertig mit allen CI-Prüfungen bezeichnet werden.

Nach der Bereinigung und Runtime-Korrektur bestanden 320 ausgewählte Maven-Tests
ohne Failures, Errors oder Skips, einschließlich der Portfolio-Abnahme. Dabei war
globales Node gesperrt; der Browser wurde ausdrücklich als Headless Shell
vorgegeben und sein Treiber automatisch beschafft. Der automatische Download des
festgelegten Chrome gelang, sein Start scheiterte an der Socket-Beschränkung der
Ausführungsumgebung. Die Node-Wiederherstellung bei einem echten Maven-Build-
Cachetreffer wurde zusätzlich mit zuvor entferntem Runtime-Verzeichnis geprüft.

## Offene Befunde

| Priorität | Befund und nächster Prüfschritt |
| --- | --- |
| Hoch | Mobil verdrängt die vollständige Projektliste den ausgewählten Arbeitskontext. Bei 390×844 und zwei Projekten beginnt die Anforderungsüberschrift noch bei y = 1352,8 px. Einen kompakten Projektwähler und eine klare Übergabe in den Arbeitsbereich prüfen. |
| Hoch | Erfolgreiche und projektfremde Jobs stehen vor der aktuellen Arbeit; eine eindeutige Projektkennzeichnung fehlt. Gemischte große Historien messen und abgeschlossene Historie sinnvoll zusammenfassen, ohne aktive oder fehlerhafte Vorgänge zu verstecken. |
| Mittel | Eine laufende Wiederholungsanfrage sperrt nur den gerade vorhandenen Button. Ein Neuaufbau kann die Aktion vorzeitig wieder freigeben. Mit gehaltenem Retry-POST reproduzieren und den Sperrzustand im bestehenden Modul führen. |
| Mittel | Die 20er-Grenze der Jobhistorie schützt ältere aktive oder fehlgeschlagene Jobs nicht ausdrücklich. Einen gemischten Fall oberhalb der Grenze prüfen. |
| Offen | Die mobile Fokusprüfung belegt horizontalen Zustandserhalt, aber noch keine vollständige Sichtbarkeit jeder fokussierten Tabelle unter dem Sticky-Header. Tabellenlage, Überdeckung und den vollständigen nativen Tab-Weg prüfen. Nichttrivialer vertikaler Tabellen-Scroll wurde nicht geprüft. |
| Offen | Viele Reformulierungsangebote können vor dem maßgeblichen Anforderungstext viel Platz beanspruchen. Mit repräsentativen gefüllten Anforderungen messen. |

## Umgang mit Prüfausgaben

Generierte Logs, JSON-Rohberichte, Messwertkopien und vollständige Bildserien
gehören unter `target/` beziehungsweise in die CI-Artefakte. Sie werden nach der
Auswertung nicht in die Quelldateien übernommen. Im Repository bleiben die
Tests, kurze Befunde, relevante Messwerte und gezielt ausgewählte
Dokumentationsbilder.

Die ursprünglichen Rohdaten und die ausführliche Ablaufchronik sind in der
[Git-Historie vor der Bereinigung](https://github.com/carstenartur/Taxonomy/tree/eaca4233311dc82b6de227d1f402ab5e14c2e07b/docs/qa)
weiter verfügbar. Neue Abnahmen aktualisieren die Ergebniszusammenfassung;
sie hängen keine weiteren Rohprotokolle an diese Dokumentation an.
