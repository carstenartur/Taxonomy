# Architekturqualität: Prüfung am Ausbildungsportal

Berichtsstand: 21. November 2026, 20:43 Uhr MEZ. Laufende Abschlussprüfungen
und deren jeweils neueste Ergebnisse sind im
[Pull Request 1584](https://github.com/carstenartur/Taxonomy/pull/1584) dokumentiert.

## Anlass und Ziel

Der gespeicherte QA-Lauf enthielt als einzige automatisch verifizierte Beziehung
`CI-1151 SUPPORTS BP-1000`. In der ursprünglichen Katalogstruktur ist `BP-1000`
ein Sammelknoten mit fachlichen Unterknoten. Eine solche Beziehung kann die Suche
orientieren, erklärt aber noch nicht, welcher konkrete Prozess durch den Dienst
unterstützt werden muss. Ein formell gültiges Modell oder eine fehlerfreie
ausgewählte Exportansicht reicht deshalb als Qualitätsnachweis nicht aus.

Die Änderungen setzen an den Grenzen zwischen Modellantwort, fachlicher Evidenz,
begrenzter Suche, gespeichertem Ergebnis und exportierter Lieferung an. Sie erhöhen
die Verlässlichkeit auch bei ungenauen oder widersprüchlichen Antworten. Sie sind
kein Nachweis, dass ein beliebiges Modell bereits erfahrene Architekten übertrifft.

## Verbindliches Verhalten

| Bereich | Verhalten | Aussagegrenze |
| --- | --- | --- |
| Fachliche Endpunkte | Ursprüngliche Navigationsknoten und nachweisbare übergeordnete Sammelknoten werden vor der Aufnahme einer automatischen Beziehung verfeinert. | Eine echte fachliche Wurzelebene oder ein tieferer Knoten mit Unterknoten bleibt zulässig. Entscheidend ist die aufgezeichnete Struktur. |
| Begrenzte Suche | Bereits belegte, kompatible Gegenstücke mit demselben Originalzitat werden früher geprüft; unbekannte Zweige bleiben im Arbeitsvorrat. | Die Reihenfolge ersetzt keine semantische Prüfung und erhöht keine Aufrufgrenze. |
| Widersprüche | Positive Antworten mit offenen Fragen sowie negative Antworten mit positiven Behauptungen oder offenen Fragen gehen in die begrenzte Korrektur. | Eine weiterhin ungültige Antwort bleibt als unerledigte Arbeit sichtbar. |
| Historische Projektion | Nachweisbar grobe automatische Beziehungen werden vor Knotenlimit und Herkunftszuweisung ausgeschlossen; die ursprüngliche Evidenz bleibt gespeichert. | Ein alter, strukturell unvollständiger Snapshot wird nicht anhand des heutigen Katalogs umgedeutet. Manuelle Git-/DSL-Änderungen bleiben bestehen. |
| Anforderungen | Modellergänzungen, abgeschnittene Quellenfragmente und unbelegte Bedingungen erhalten keine automatische Originalautorität. | Die formalen Schutzregeln sind keine allgemeine Prüfung semantischer Folgerichtigkeit. |
| Große Abgleiche | Nach dem vollständigen Prompt werden bei Bedarf begrenzte, vorab geprüfte Übersicht-, Detail- und Archivansichten verwendet. | Nicht vollständig vergleichbare Aussagen werden als prüfbedürftig ausgewiesen; ein unteilbar zu großer Inhalt wird nicht gekürzt. |
| Wiederaufnahme | Archivierte v2-Prompt-Bytes und bestehende Identitäten bleiben stabil; neue scoped Schritte haben eigene Identitäten. | Der neue Suchvertrag erfordert bei alten, inkompatiblen Fortsetzungen einen erneuerten Quellkontext. |
| Bearbeitbarer Entwurf | Nachweisbar redundante Originalzitate und Katalogzusammenfassungen werden aus dem bearbeitbaren Text herausgehalten; Aussagen, Quellen und Entscheidungen bleiben strukturiert und in einer aufklappbaren Evidenzansicht erhalten. | Redaktionelle Benutzeränderungen, Bedingungen, Modellergänzungen, Konflikte und offene Fragen werden nicht durch Ähnlichkeitsheuristiken entfernt. |
| Übernahme in die Architektur | Quellengebundene Evidenzblöcke erhalten beide gerichteten Beiträge, Originalzitate, Bedingungen und Herkunft. Ein eigener Fingerabdruck bindet die Evidenz an Vorschau, Journal und Undo/Redo. | Bestehende manuelle Relationsfelder werden nicht überschrieben; unbekannte Konfidenz bleibt unbekannt, echte Null bleibt Null. Historische Ansichten erhalten keine nachträglich erfundene Evidenz. |
| Verteilte Wiederholung | Neu zugelassene Suchen verteilen Quellpakete innerhalb ihrer Kohorte über die Katalogbereiche und erhalten die Reihenfolge gepackter Zielpakete. Ein gespeicherter Zeitplan erhält die bereits bezahlte Reihenfolge älterer Arbeit. | Die lexikografische Zielsortierung wird dadurch nicht zu einer allgemeinen Sortierung nach Klassifikationswert. Aufrufgrenzen und Frageidentitäten bleiben erhalten. |
| Diagrammkanten | Sichtbare Verbindungen umgehen fremde Karten, behalten gespeicherte Endpunkte und passen mit Beschriftungen in den Ausschnitt. | Überlappende extern angelieferte Szenen ohne freie Zwischenräume können auf den ursprünglichen Pfad zurückfallen. |
| Zeitbezug | Die Lease-Uhr liest den Datenbankwert mit Zeitzonenoffset und wandelt ihn in einen absoluten Zeitpunkt um. | Der gezielte Regressionstest prüft HSQLDB mit einer Sitzung bei UTC+05:30. |
| Dokumente | Unicode und Absätze bleiben erhalten, Diagramme erhalten begrenzte Druckseiten und ein vollständiges Inventar, unbekannte Messwerte bleiben unbekannt. | Ein gespeicherter Status `accepted` belegt keinen zusätzlich durchgeführten menschlichen Review. |
| ADMBw-Lieferung | JSON, Editor und beide nativen Diagramme nennen den gesamten Ausgangsbestand, enthaltene und fehlende Objekte sowie den gespeicherten Analysestatus. | C1–S1/R7-Strukturprüfung, vollständige Architektur und Konformität mit der gesamten Methode sind getrennte Aussagen. |

Die Tests benutzen auch absichtlich schwache oder widersprüchliche Modellantworten.
Diese gezielten Regressionen messen die deterministische Verarbeitung solcher
Antworten. Sie sind kein unabhängiger Qualitätsbenchmark eines tatsächlich
betriebenen schwachen Modells.

## Neuer tatsächlicher Anwendungsablauf

Der neue Lauf startet die Produktionsanwendung mit dateibasierter HSQLDB und
Lucene sowie `CUSTOM_OPENAI`. Ein lokaler OpenAI-kompatibler HTTP-Endpunkt reicht
jeden echten Prompt an den QA-Assistenten weiter. Jede Antwort wird frisch
verfasst; Mock-, Replay- und Record-Modus sind ausgeschaltet. Die Anwendung
verwendet keine Testklassen. Anfrage, Antwort und Auslieferung sind durch Hashes
gebunden. Dieser Aufbau testet die Integration einer vLLM-kompatiblen API; er
betreibt kein vLLM-Modell und vermisst kein schwächeres Modell.

Bis zum ersten kontrollierten Neustart wurden **157 von 384 erlaubten**
HTTP-Anfragen ausgeführt. Alle 157 Antworten wurden ausgeliefert. Der Lauf
verwendet den vollständigen Katalog mit 2.572 Knoten in acht Bereichen.

| Schritt | Tatsächliches Ergebnis |
| --- | --- |
| Eingabe und A0 | Die ursprüngliche Anforderung mit zwölf fachlichen Aussagen wurde gespeichert. A0 bewertete 75 Knoten, 465 blieben offen. Der hoch bewertete Sammelknoten BP-1000 wurde nicht zum Architekturanker. |
| Automatische Überarbeitung | 13 ursprüngliche und sieben gezielte Knotenschritte sowie zwei Abgleiche wurden ausgeführt. Die neue Revision wurde gespeichert; sie war mit 7.258 Zeichen und 16 Katalogzusammenfassungen zu umfangreich für einen brauchbaren Entwurf. Sie wurde nicht unverändert übernommen. |
| Redaktionelle Überarbeitung | Der QA-Assistent erstellte zwölf nachvollziehbare Abschnitte und erhielt die offene Frage, wer die Auditereignisse lesen darf. Die echte Übernahmeprüfung wurde an Vorschau-ID, Quellversion und Text-Hash gebunden. Version 2 wurde über HTTP gespeichert; Version 1 blieb abrufbar. Dies ist eine assistentengestützte redaktionelle Verbesserung. |
| A1, BP und CI | 15 Klassifikations- und 20 Relationsaufrufe lieferten 20 Elemente und eine konkrete Beziehung: `CI-1151 SUPPORTS BP-2125`. 64 Arbeitspakete und zehn Quellbewertungen blieben offen; Status `PARTIAL`. |
| A2, alle acht Bereiche | 52 Klassifikations- und 32 Relationsaufrufe lieferten 25 Elemente und die Beziehung `UA-1209 SUPPORTS BP-1535`. 257 Knoten waren bewertet, 2.315 nicht bewertet. 19 von 45 Relationsquellen waren geprüft; 170 Arbeitspakete blieben offen. Status `PARTIAL`. |

A1 stützt die Beziehung auf die Aussage, dass Ausbilder freigegebene Lehrinhalte
für räumlich getrennte Ausbildungsstandorte bereitstellen. Die Bedingung über
freigegebene Inhalte gehört zu diesem Beitrag; daraus entsteht kein erfundener
Freigabeprozess. A2 verbindet den Zugriff der Ausbilder auf den Lernfortschritt
ihrer zugeordneten Teilnehmer. Daraus entsteht kein Recht zur Bearbeitung von
Lernfortschritten oder zum Lesen von Auditdaten.

Die übernommene Anforderungsversion ist durch den SHA-256-Hash
`81776e9ff4dd5989851fa3f6476af90692bf2c76a2502d749a084879c591c9e4`
gebunden. Die unveränderte Eingabe hat den Hash
`fc4b08b23ccde117bc14ac0db2c37b8027a34284ded444c815b6e9106008366f`.
A1 stammt aus Snapshot `fc4714a3-556a-4631-b97c-829354a8106a`, A2 aus
`b7ef268d-1ed6-4ea8-b0c2-32544c1c6c8e`. Eine gespeicherte Zustimmung des
QA-Assistenten ist keine unabhängige fachliche Abnahme.

### Im echten Ablauf gefundene und korrigierte Schwächen

Der überlange automatische Entwurf führte zur gemeinsamen kompakten Projektion
für normale und gezielte Synthese. Eine erneute Projektion der exakt gespeicherten
Evidenz erzeugt **1.066 statt 7.258 Zeichen**. Alle zwölf Aussagen, 16
Katalogzusammenfassungen und die offene Frage bleiben in der Evidenz enthalten.
Dies ist ein Test der gespeicherten Evidenz mit dem neuen Renderer, keine neue
Modellsynthese. Die Satzaufteilung erhält auch Titel und Initialen wie „Dr. E.
White“.

Der vollständige Reaktor deckte außerdem auf, dass die neue Absatzaufteilung
eine bereits übernommene Anforderung verändern konnte. Für solche gebundenen
Quellen bleiben Text und Layout jetzt bytegetreu erhalten, sowohl in normaler
als auch in gezielter Synthese. Die Regression prüft vollständige Gleichheit
statt des früheren Teilstringvergleichs. Der Demo-Vertrag prüft nun die neue
kompakte Darstellung: genau eine Originalfassung am Anfang, alle fünf
Ergänzungen und menschlichen Antworten sowie die unveränderten Fragen und die
abgelehnte GPS-Aussage in der strukturierten Historie.

Die ursprüngliche Übernahme von A1 reduzierte die Beziehung auf Endpunkte und
Status. Der neue Weg erhält den vollständigen gerichteten Beleg im kanonischen
Modell. Ein separater Test mit dem erfassten A1 bestätigt dieselbe Ansichts-ID,
beide Originalzitate, beide Bedingungen und die gespeicherte Begründung.
Ungültige einzelne UTF-16-Surrogate werden vor der UTF-8-Identitätsbildung
abgewiesen; gültige ergänzende Unicode-Zeichen überstehen den echten
Git-Checkpoint.

A2 zeigte, dass das Voranstellen mehrerer Zielpakete deren Reihenfolge umkehrte.
Der relevante BP-1327 wurde in diesem konkreten Fall hinter drei mit Null
bewerteten Geschwistern besucht. Die neue Ausführung erhält die gepackte
Reihenfolge. Innerhalb einer Antwort werden bereits gefundene Kandidaten vor
weiterer Verzweigung verifiziert; dies gilt auch für den letzten verfügbaren
Arbeitsplatz. Ein späterer Katalogfehler erhält bereits erkannte Arbeit als
unvollständig. Die Quellbewertung verteilt begrenzte Pakete über die in derselben
Kohorte enthaltenen Bereiche. Historische verteilte Arbeit erhält ihre alte
Reihenfolge: Wiederholung eines bereits bezahlten Grants erzeugt keine neuen
Provideranfragen und behält die bereits verifizierte Entscheidung.

Im Browser verdeckte CI-1047 die Verbindung von CI-1151 zu BP-2125. Die neue
Darstellung führt sie durch die freien Zwischenräume. Ein echter Chromium-Lauf
mit der gespeicherten A1-Szene bestätigte unveränderte Positionen aller 20 Karten,
keine Überschneidung von Kante oder Beschriftung mit einer Karte und den Pfeil am
ursprünglichen Ziel. Die Beschriftung „Direkt bewertet“ bezeichnet nun eine
Klassifikationsbewertung, ohne eine fachliche Freigabe vorzutäuschen.

Dieselbe gespeicherte Szene legte den verdeckten Pfad auch im SVG und in der
Word-Übersicht offen. Die Java-Renderer verwenden nun ebenfalls einen begrenzten
Umweg durch freie Zwischenräume. PDF wendet ihn nach der Übersetzung in das
jeweilige Seitenpanel an; die vorhandenen seitenübergreifenden Verweise bleiben
erhalten. Pfeile folgen dem letzten Pfadsegment. SVG begrenzt zusätzlich die
Pfeilspitzengröße auf die verfügbare Strecke. Parallele Kantenbeschriftungen
erhalten kollisionsfreie Positionen oder bleiben über Inventar, Legende und
zugängliche Kantenbedienung verfügbar, wenn kein freier Platz existiert.
Die gespeicherten Knotenpositionen, Endpunkte und fachlichen Aussagen ändern
sich dadurch nicht. Die unabhängige Prüfung bestätigte sowohl den tatsächlichen
A1-Export als auch die drei parallelen Kanten im Browser.

### Kontrollierte Wiederaufnahme nach den Korrekturen

Die Produktionsklassen wurden vor dem neuen Gesamtbuild in ein unveränderliches
Laufzeitverzeichnis kopiert und bytegenau geprüft. Der Neustart öffnete das
Projekt in 21 Sekunden; echte GET-Aufrufe bestätigten beide Anforderungsversionen,
alle drei bisherigen Snapshots und den privaten Architektur-Checkpoint. Mermaid
und Sparx wurden geladen. Der anschließende Vergleichslauf A3 wurde mit derselben
Anforderungsversion und 20 Relationsaufrufen zugelassen.

Der Austausch der Ausführungsumgebung unterbrach diesen ersten A3-Versuch nach
einer frisch beantworteten und einer weiteren zugelassenen Anfrage. Er hat kein
abgeschlossenes Ergebnis. Seine Daten bleiben getrennt. Eine Wiederholung zählt
beide Zulassungen zum gemeinsamen Höchstwert: 159 von 384 sind damit bereits
verbraucht. Eine Wiederaufnahme darf den Zähler nicht auf null setzen oder den
unterbrochenen Versuch als Erfolg werten.

### Abgeschlossener Vergleichslauf A3

Nach Wiederherstellung derselben Abhängigkeiten wurde eine neue A3-Ausführung
auf Basis der unveränderten, sauber geschlossenen Daten zugelassen. Job
`8840e201-eaed-47fd-8495-4b882eebb5fa` speicherte Snapshot
`4d4b8dda-a4c3-46c1-88a4-321a1f427e02`. Alle 35 neu erzeugten
HTTP-Antworten wurden ausgeliefert. Der gemeinsame Zähler steht danach bei
194 von 384 Zulassungen: 157 aus dem ersten vollständigen Durchgang, zwei aus
dem unterbrochenen Versuch und 35 aus A3. Eine der zwei unterbrochenen
Zulassungen hat keine Antwort; sie wird trotzdem gezählt.

Bei gleicher Version 2, gleichem BP/CI-Umfang und unverändert 20 Relationsaufrufen
bewertete A3 nach dem ersten BP-Paket direkt CI-1023, CI-1047 und CI-1151.
Die erste unter BP-1000 besuchte Teilmenge enthielt jetzt BP-1327. Danach folgten
BP-1351, der konkrete Kandidat BP-2125 und dessen separate Verifikation. A3
bestätigte erneut `CI-1151 SUPPORTS BP-2125`.

| Messgröße | A1 vor der Reihenfolgekorrektur | A3 nach der Korrektur |
| --- | ---: | ---: |
| Relationsaufrufe | 20 | 20 |
| Bewertete / nicht bewertete Katalogknoten | 75 / 465 | 75 / 465 |
| Architekturelemente / verifizierte Beziehungen | 20 / 1 | 20 / 1 |
| Nicht bewertete Relationsquellen | 10 | 6 |
| Ausstehende / ungeklärte Quell-Ziel-Suchen | 95 / 1 | 80 / 1 |
| Offene Arbeitspakete | 64 | 107 |
| Analysestatus | PARTIAL | PARTIAL |

Die größere Zahl offener Pakete gehört zum reichhaltigeren, weiterhin offenen
Suchplan. Sie wird nicht als Verbesserung umgedeutet. A3 verwendete außerdem den bereits vorhandenen A1-Checkpoint als
Modellkontext und frisch verfasste Antworten. Dieser Vergleich ist deshalb
kein isolierter Kausalvergleich und kein kontrollierter Modellbenchmark.
Nachgewiesen sind die korrigierte Reihenfolge, breitere Quellbewertung und
weiterhin konkret belegte Beziehung; weder mehr Beziehungen noch vollständige
Architekturabdeckung wurden dadurch erreicht.

### Tatsächliche ADMBw-Prüfung des neuen Modells

Der neue kanonische A1-Checkpoint
`e6f7d99db5c45244ef12d168fe31d3f3ae0e86d7` enthält 20 Elemente, eine
Beziehung, eine gebundene Anforderung und 20 Zuordnungen. Die über HTTP
abgerufene C1–S1/R7-Prüfung meldet **`CHANGES_REQUIRED`** und die
Lieferabdeckung **`PARTIAL`**. Sechs von 20 Elementen sind abbildbar; die
Beziehung, die Anforderung und die 20 Zuordnungen werden in diesem Profilstand
noch nicht abgebildet. Für die sechs vorhandenen CI-Kandidaten fordert die Prüfung die ausdrückliche
Deklaration als ServiceSpecification und je eine akzeptierte Exhibits-Zuordnung
zu einer offiziellen Capability; außerdem fehlt der R7-Planungseintrag. Die Oberfläche zeigt die Lücken und
bietet keinen erfolgreichen nativen Export an.

Die automatische Klassifikation von Rollen- und Berichtskategorien ist
außerdem noch keine abgesicherte Aussage, dass die Rollen `BR-1228` oder `BR-1232`
oder eine bestimmte Berichtspflicht gefordert sind. Diese Kandidaten sind nicht
als fachlich abgenommen zu lesen. Aus dem neuen Lauf folgt daher keine
vollständige automatische ADMBw-Architektur.

### Tatsächliche A2-Übernahme und aktueller kanonischer Stand

Die A2-Vorschau hatte keine Konflikte. Die über HTTP ausgeführte Übernahme
speicherte Checkpoint `f9c031ce194e743aea39ef5dc2bf840944a1351b`, Revision 4.
Er enthält 36 Elemente, zwei Beziehungen, eine gebundene Anforderung und 20
Zuordnungen. Die ältere A1-Beziehung bleibt im gespeicherten Status `accepted`;
die neue Beziehung `UA-1209 SUPPORTS BP-1535` bleibt `proposed`.

Ein echter nachfolgender Checkpoint-Lesezugriff bestätigt für A2 den neuen
strukturierten Evidenzblock: das exakte Zitat, die Bedingung, gerichtete Beiträge,
Notwendigkeit, Verifikationsentscheidung, Snapshot- und Ansichtsidentität.
Die Herkunft ist `LLM_SUPPORTED`, unbekannte Konfidenz bleibt leer. Die
kanonische PDF nennt Status, Herkunft und die partiellen Snapshot-Quellen;
die vollständigen strukturierten Zitate sind im DSL/API-Nachweis enthalten.

Die aktuelle ADMBw-Prüfung meldet für den gesamten Checkpoint 13 von 36
abbildbare Elemente, für die ausgewählte A2-Sicht zehn von 36. Beide erfassen
keine der zwei Beziehungen, keine der einen Anforderung und keine der 20
Zuordnungen in der gültigen Profil-Lieferung. C1–S1 und R7 bleiben
`CHANGES_REQUIRED`. Der native Export bleibt trotz nachweislich geladenem
Sparx-Plugin gesperrt.

Eine weitere Lesprüfung der zwölf Anforderungsklauseln findet für acht davon
zitiergebundene Knotenbeiträge. Zwei Klauseln haben zusätzlich separat
verifizierte gerichtete Beziehungen: AF-02 für die Bereitstellung freigegebener
Lehrinhalte und AF-06 für die Einsicht in Bearbeitungsstände zugeordneter
Teilnehmer. Für Rollenunterscheidung und konkrete Berechtigungsdurchsetzung
bleiben weitere Nachweise offen. Rahmen- und Ausschlussklauseln benötigen
nicht automatisch eine zusätzliche Komponente. Die Leseberechtigung für
Auditprotokolle bleibt eine offene Frage ohne gespeicherte Antwort. Diese
Prüfung fand keinen Widerspruch zu den zwölf Klauseln; sie belegt weder
vollständige Umsetzung noch unabhängige fachliche Abnahme.

Alle drei gespeicherten Snapshot-Ansichten wurden erneut in Chrome geöffnet.
Die gerichteten Pfade schneiden keine fremde Karte; insbesondere bleibt
CI-1151 als Quelle der A1-Kante sichtbar. Danach wurde die Anwendung bei leerer
Antwortwarteschlange sauber beendet. Datenbank und Lucene-Indizes wurden für
die abschließende Exportprüfung erneut mit Hashes gesichert.

### Abschließender HTTP-Export des identischen Checkpoints

Ein weiterer Neustart verwendete die zuletzt geprüften Produktionsklassen aus
einem unveränderlichen Laufzeitverzeichnis und lehnte neue Modellaufrufe ab.
Die ursprüngliche und die übernommene Anforderung, alle vier Snapshots und
Checkpoint `f9c031ce194e743aea39ef5dc2bf840944a1351b` blieben identisch.
Der Zähler blieb bei 194 Zulassungen.

Alle 14 Downloads antworteten mit HTTP 200: jeweils SVG, PDF und DOCX für A1,
A2 und A3 sowie Anforderungstext, kanonisches DSL, kanonische PDF und die beiden
ADMBw-Bewertungen. Die SVG-Prüfung bestätigte korrekte Anschlusspunkte und keine
Schnittpunkte mit fremden Karten in allen drei Ansichten. A1 umgeht jetzt auch
im tatsächlichen Serverexport die Karte CI-1047. Die Sichtprüfung bestätigte
die Word-Übersicht und die Detaildarstellung, den achtseitigen kanonischen
PDF-Bericht sowie repräsentative Seiten des neunseitigen A3-Word-Berichts.

Der Browser zeigte weiterhin die partiellen Quellen und die unveränderten
ADMBw-Lücken. Trotz geladenem Sparx-Plugin wurde kein nativer Export angeboten.
Es entstand keine QEA aus diesem aktuellen Checkpoint. Nach der Prüfung wurde
die Anwendung mit leerer Warteschlange und sauber geschlossener Datenbank
beendet. `live-current-export-final/final-http-evidence-manifest.json` bindet
Quelldateien, Laufzeit, 417 externe JARs, Downloads, Browsernachweise und 19
geschlossene Datenbank-/Indexdateien an ihre Hashes.

## Erneuter Export des unveränderten QA-Checkpoints

Ausgangsstand ist der zuvor gespeicherte Architektur-Checkpoint
`8c6347731aa4e34fcbacc44d091b8953007e8898`, C1–S1-Sicht
`qa-admbw-ea0c5d079e0a56ee`, Anforderung
`QA-MASC-20261121__QA-REQ-001`, Version 2. Ihr Text-Hash bleibt
`85832beaa3c97c458b61861398c85a4a812df5ebd34fb6562a7e9872f4052f20`.
Der erneute Export ruft Produktions-Profil und QEA-Writer direkt auf; er erzeugt
keine neue Modellanalyse und ersetzt keine HTTP-Abnahme.

| Ausgangsobjekte | Im Checkpoint | Im C1–S1/R7-Export abgebildet | Nicht abgebildet |
| --- | ---: | ---: | ---: |
| Elemente | 31 | 5 | 26 |
| Beziehungen | 2 | 1 | 1 |
| Anforderungen | 1 | 1 | 0 |
| Anforderungszuordnungen | 26 | 0 | 26 |

Die ausgeschlossene Beziehung umfasst gerade `CI-1151 SUPPORTS BP-1000`. Die
Zuordnungen werden nicht als C1–S1-/R7-Beziehungen ausgegeben, weil sich ihre
Semantik unterscheidet. Das Manifest enthält jede ausgelassene Identität und ihren
Grund. Der Status der aufgenommenen Ausgangsanalyse bleibt `PARTIAL`.

Beide nativen Diagramme enthalten den gleichen vollständigen Nachweis. Die
unabhängige, nur lesende Prüfung der neuen QEA-Datei gegen die unveränderte
MASC-Basis und das bereits archivierte MDG hat 426 Prüfungen ohne Fehler
abgeschlossen. Geprüft sind unter anderem SQLite-Struktur, ursprüngliche Zeilen,
MDG-Typen, Endpunkte und die gebundene Anforderungsversion. Das Ergebnis lautet
`CHECKS_PASSED_REVIEW_REQUIRED`; eine Sichtprüfung in Enterprise Architect und
die fachlichen Methodenprüfungen sind darin nicht enthalten.

## Laufzeit und Nachweise

Die vorhandene Konfiguration `hsqldb-file` verwendet dateibasierte HSQLDB-Tabellen
mit begrenztem Cache sowie Lucene `local-filesystem`. Der neue Live-Lauf
verwendet genau diese Konfiguration mit 512 MiB Java-Heap. Die höchste gemessene
RSS der Anwendung lag bei 935.440.384 Bytes (rund 892 MiB); ein lokaler
Modellprozess ist in dieser Messung nicht enthalten. Eine Änderung
globaler Produktionsvorgaben war dafür nicht nötig.

Die lokalen Java-Prüfungen verwenden Java 25 und den eingecheckten Maven Wrapper.
Ein ausschließlich für die Arbeitsumgebung erzeugtes Maven-Proxy-/Truststore-Setup
stellt den Downloadzugriff her. Mockito wird wegen blockierter dynamischer
Agent-Anbindung beim Start geladen. Diese Einrichtung deaktiviert keine
Assertions, TLS-Prüfungen oder Produkt-Qualitätsregeln.

Die Rohprotokolle und reproduzierten Dokumente liegen unter
`target/quality-20261121/` und werden nicht als Quellcode eingecheckt. Die
Prüfungen wurden ohne abgeschwächte Qualitätsgrenzen und mit deaktiviertem
Maven-Build-Cache ausgeführt. Angepasste Darstellungsverträge und verstärkte
Quellschutz-Assertions sind oben ausdrücklich beschrieben. Die folgenden Läufe
überschneiden sich in ihren Testfällen und dürfen
nicht zu einer Anzahl unterschiedlicher Tests addiert werden:

| Maven-Prüfung | Ergebnis | Nachweis |
| --- | --- | --- |
| Gemeinsame Auswahl der betroffenen Java-Pfade einschließlich Wiederaufnahme, HTTP-Verträgen und Dateiprofil | 593 Tests, keine Fehler, keine übersprungenen Tests | `focused-union-tests.log` |
| Abschließende Auswahl nach den letzten JSON- und DSL-Randfallkorrekturen | 268 Tests, darunter 74 JavaScript-Prüfungen; keine Fehler oder übersprungenen Tests | `final-delta-confirmation.log` |
| Separates Sparx-Plugin mit dem aktuellen Extension-SDK | 110 Tests, `verify` erfolgreich | `sparx-plugin-tests.log` |
| Wiederholung der sechs zunächst an der Laufumgebung gescheiterten Tooling-Klassen | 3.423 Tests, darunter 3.355 JavaScript-Prüfungen; keine Fehler oder übersprungenen Tests | `tooling-environment-confirmation.log` |
| Vollständige Exporttests samt benötigten Modulen nach der portablen Visio-Textmessung | 630 Exporttests und 300 Tests der abhängigen Module; keine Fehler oder übersprungenen Tests | `visio-portable-export-tests.log` |
| Gemeinsamer Reaktor nach den Live-Korrekturen: Entwurf, Evidenzübernahme, DSL, Relationssuche, JS und echte Browserfälle | 3.758 Tests, darunter fünf Selenium-Tests; keine Fehler oder übersprungenen Tests | `post-live-combined.log` |
| Letzte Korrektur der Verifikations- und Geschwisterreihenfolge, erneut kompiliert | 176 Tests in acht Klassen; keine Fehler oder übersprungenen Tests | `final-schedule-maven.log` |
| Gemeinsamer Reaktor nach den Export- und Quellschutzkorrekturen | 3.571 Tests in 24 Klassen, darunter fünf Selenium-Tests; keine Fehler oder übersprungenen Tests | `final-integrated-regressions.log` |
| Provider-Zulassung, Transportmessung, Konfiguration und Gateway-Verträge nach der deterministischen Zeitprüfung | 324 Tests in 54 Klassen; keine Fehler oder übersprungenen Tests | `provider-admission-clock/maven-confirmation.log` |
| Sämtliche 112 Portfolio-Klassen des vorangegangenen Gesamtversuchs nach der Korrektur ihres gemeinsamen Fortsetzungs-Testaufbaus | 831 Tests; keine Fehler oder übersprungenen Tests | `portfolio-continuation-fixture/maven-portfolio.log` |
| Native Signaturprüfung mit isoliertem öffentlichem Schlüsselring | 47 Tests, keine Fehler oder übersprungenen Tests | `native-gpg-public-keyring-confirmation.log` |
| Kompakter Entwurf, normale/gezielte Synthese und tatsächlicher Browser im isolierten `test-local`-Reaktor | 111 Tests, darunter fünf Selenium-Tests; keine Fehler oder übersprungenen Tests | `../taxonomy-reformulation-ux/target/quality-compact-draft/maven-strict-shell.log` |

Der erste vollständige CI-Versuch stoppte im Tooling-Modul: Unterprozesse fanden
Java 21 statt Java 25, kein `jar` auf `PATH` oder keine Proxy-Einstellungen für
geschachteltes Maven. Die betroffenen Quellen sind gegenüber dem Ausgangsstand
unverändert. Mit Java 25 auf `PATH` und denselben temporären Maven-Einstellungen
auch für Unterprozesse bestand die vollständige Auswahl der betroffenen Klassen.
Es wurden hierfür weder Produktcode noch Tests angepasst. Der ursprüngliche
Fehllauf bleibt in `canonical-ci.log` dokumentiert.

Der nächste Gesamtversuch erreichte die Exporttests und deckte einen bestehenden
Portabilitätsfehler auf: Die logische AWT-Schrift `SansSerif` verwendete auf diesem
Rechner andere Metriken als beim Erzeugen der eingecheckten Visio-Vergleichsdatei.
Der Ausgangscode erzeugte unter derselben Umgebung genau dieselben abweichenden
Bytes wie der geänderte Stand. Die Textmessung für Visio verwendet jetzt direkt
die bereits mitgelieferte DejaVu-Sans-Schrift mit unveränderter Schriftgröße.
Damit besteht die unveränderte Prüfung aller Paketbestandteile; weder die
Vergleichsdatei noch ihre Assertions wurden angepasst. Die Schrift wird nicht
global registriert. Die übrigen Anwendungen behalten ihre Schriftauswahl.

Der anschließende Gesamtversuch stoppte bei der nativen GPG-Testeinrichtung:
Der Import des öffentlichen Testschlüssels versuchte einen hier gesperrten
Agent-Socket zu öffnen. Der Test schreibt jetzt seinen erzeugten öffentlichen
Schlüsselring direkt in das isolierte Testverzeichnis und prüft ihn mit nativem
GPG einschließlich Fingerabdruck. Die anschließende echte
`git verify-commit`-Prüfung und ihre Assertions bleiben unverändert.

Der vorgeschriebene Befehl lautet:

```sh
./mvnw -B verify -Pci -DrunOnnxTests=true -Dmaven.build.cache.enabled=false
```

Der Gesamtversuch `canonical-ci-portable-font.log` erreichte vor dem
GPG-Einrichtungsfehler 7.201 Tests. Der abschließende Gesamtversuch nach den
Live-Befunden wurde am 21. November um 19:16 Uhr MEZ während der Tooling-Tests
durch den Austausch der Ausführungsumgebung unterbrochen. Der Ersatz hatte
keinen Maven-Artefaktcache; der erste Neustart scheiterte daher bereits vor
dem Start der JVM. Beide Protokolle bleiben getrennt erhalten. Nach
Wiederherstellung der exakten Abhängigkeiten erreichte `canonical-ci-final.log`
11.012 Tests in 928 Klassen mit zwei Fehlern und sieben übersprungenen Fällen.
Die beiden Fehler betrafen den beschriebenen Schutz übernommener Anforderungen
und den bisherigen Demo-Darstellungsvertrag. Beide Korrekturen bestehen den
gemeinsamen Reaktor mit 3.571 Tests und danach auch den vollständigen
Analysemodul-Lauf.

`canonical-ci-final-code.log` erreichte 11.020 Tests in 929 Klassen mit einem
Fehler und sieben übersprungenen Fällen. Der unverändert vom Ausgangsstand
übernommene `ProviderPermitRateReservationTest` scheiterte an seiner realen
Zehn-Millisekunden-Frist nach der bereits erfolgreichen zweiten Broker-Zulassung.
Die lokale RPM-Reservierung war damit verfügbar; eine bewusst verzögerte
Baseline-Prüfung reproduzierte dieselbe Fristüberschreitung nach dem Broker.

Die Zulassungsprüfung besitzt jetzt eine paketinterne kontrollierbare Uhr und
Wartefunktion. Produktionsaufrufe verwenden weiterhin `System.nanoTime` und
dieselben kooperativen Wartephasen. Der Test behält seine Zehn- beziehungsweise
Hundert-Millisekunden-Grenzen: Broker-Ausfall verbraucht keinen Aufruf, Erholung
erlaubt genau einen Aufruf ohne Wartezeit, der nächste Aufruf wird durch das
verbrauchte RPM-Kontingent blockiert. Eine virtuelle Verzögerung von 150
Millisekunden gegen die Hundert-Millisekunden-Grenze gibt das Permit zurück,
ohne den Request zu starten. Drei gezielt fehlerhafte Varianten bestätigten,
dass fehlende Rückgabe, irrtümliche Rückgabe nach Erfolg und fehlende Frist
weiterhin erkannt werden. Der frisch kompilierte Maven-Lauf besteht mit 324
Tests; die Änderung wurde unabhängig geprüft.

Die sieben übersprungenen Fälle waren fünf Helm-Prüfungen ohne vorhandenes Helm
und zwei optionale Visio-PDF-Prüfungen ohne Aktivierung. Sie gelten nicht als
bestandene Tests.

Der erneute vollständige Reaktor `canonical-ci-final-clock.log` schloss sämtliche
1.960 Tests des Analysemoduls erfolgreich ab, einschließlich aller drei zuvor
gefundenen Regressionen. Er erreichte insgesamt 11.851 Tests in 1.041 Klassen.
Im anschließenden Portfoliomodul scheiterten alle 21 Fälle einer gemeinsamen
Fortsetzungs-Testklasse. Die isolierte Wiederholung zeigte zwei veraltete
Annahmen des Testaufbaus: Der künstliche Katalog lieferte die jetzt für die
Quellprüfung benötigten Kindknoten nicht, und seine negativen Modellantworten
enthielten gleichzeitig positive Behauptungen. Die reale Hierarchieabfrage
stoppte deshalb vor dem ersten Relationsaufruf; nach alleiniger Reparatur des
Katalogs wies der strengere Relationsvertrag die widersprüchlichen Antworten ab.

Der Testaufbau liefert nun für beide Katalogzugriffe dieselben gespeicherten
Eltern-Kind-Beziehungen und neutrale Ablehnungs-/Navigationsantworten. Die 33
Quellen, der Zielkatalog, alle Originalzitate, Aufrufgrenzen und Assertions
bleiben erhalten. Produktionscode wurde hierfür nicht geändert. Die gesamte
Klasse besteht mit 21 Fällen, anschließend bestand die frische Maven-Ausführung
aller zuvor ausgeführten Portfolio-Klassen mit 831 Tests. Der Diff und weitere
vergleichbare Testaufbauten wurden unabhängig geprüft.

Ein erfolgreicher Abschluss des vollständigen Gates ist zu diesem
Berichtsstand noch nicht festgestellt. Der lokale Rechner hat weder Docker
noch Helm; die unveränderte gehostete Matrix führt die dafür erforderlichen
Prüfungen aus. Die bestandenen Teilauswahlen gelten nicht als Ersatz.

## Veröffentlichter Prüfstand

Der Quellstand wurde zunächst als Entwurfs-PR
[1584](https://github.com/carstenartur/Taxonomy/pull/1584) veröffentlicht.
GitHub führt im Job „Maven verification“ für Entwürfe ausdrücklich
„Keep draft heads non-mergeable“ aus und beendet ihn mit Fehler. In
[Lauf 44935210443](https://github.com/carstenartur/Taxonomy/actions/runs/44935210443)
war dies der beobachtete Grund; der Job führte keine Codetests aus. Die
Entwurfssperre bleibt als unveränderte Regel bestehen und wird nicht als
bestandene CI interpretiert.

Nach den unabhängigen Reviews und dem erfolgreichen Provider-Reaktor wurde
der PR am 21. November um 20:16 Uhr MEZ zur regulären Prüfung freigegeben.
Damit starteten die vollständige gehostete CI und die drei Datenbankprofile
auf dem veröffentlichten Quellstand. Eine Zusammenführung wurde nicht
ausgeführt. Der erste vollständige gehostete Lauf
[44940086711](https://github.com/carstenartur/Taxonomy/actions/runs/44940086711)
fand in der QA-Dokumentation eine Formulierung, die gegen den bestehenden
neutralen Namensvertrag verstieß. Die Formulierung benennt jetzt die
unveränderten Katalogkennungen. Derselbe unveränderte Namens- und
Verdrahtungsvertrag scheiterte vor der Korrektur und bestand danach.
Die Prüfregel, die Testauswahl und die Produktdaten wurden nicht angepasst.

## Noch nicht durch diese Änderung bewiesen

- Eine vollständige automatische Live-Analyse bis zur fachlich abgenommenen
  und im ganzen Umfang ADMBw-kompatiblen Architektur. Die tatsächlich
  ausgeführten Analysen und die sichtbare Profilablehnung sind oben beschrieben.
- Eine Überlegenheit gegenüber manuell erstellten Architekturen auf unabhängigen
  Anforderungen und mit repräsentativen schwächeren Modellen.
- Die native Darstellung und fachliche Abnahme in Enterprise Architect.
- Die vollständige Browser-, externe Datenbank- und CI-Matrix. Das zunächst
  beobachtete Chrome-Startproblem wurde für die ausgewählten realen Browsertests
  mit der passenden Chrome-for-Testing-Headless-Shell 149.0.7827.55 gelöst.
  Fünf Selenium-Szenarien, die Live-Ansichten und der gespeicherte A1-Pfad wurden
  tatsächlich im Browser geprüft; dies ist keine vollständige Matrixabnahme.

Für die fachliche Vergleichsprüfung müssen dieselben unveränderten Anforderungen,
ein versionierter Katalog und gleiche Grenzen für Zeit und Modellaufrufe verwendet
werden. Unabhängige Prüfer müssen die konkreten Endpunkte, Belegtreue, notwendigen
Beziehungen, fehlenden Anforderungen, Widersprüche und Bearbeitbarkeit beurteilen.
Eine hohe Anzahl von Knoten oder formal gültigen Kanten ist kein Ersatz dafür.

### Optionaler Live-Test des Architekturdialogs mit vLLM

Der JUnit-Test sendet zwei Fragen zu einem synthetischen Ausbildungsportal über
den produktiven `CUSTOM_OPENAI`-Transport. Für das sichtbare Teilnehmerkonto
muss die Antwort die Sichtregel und die Kontozuordnung belegen. Bei der
Folgefrage zur offenen Freigaberolle akzeptiert der Test eine belegte offene
Entscheidung oder eine Enthaltung. Beide Antworten beziehen sich auf denselben
Checkpoint. Der Test verwendet bewusst kurze Antwortformate, damit seine
fachlichen Erwartungen maschinell prüfbar sind.

Der Test verwendet ausschließlich den unter `CUSTOM_LLM_URL` angegebenen
vLLM-Server über HTTP oder HTTPS. Im Rancher-Cluster kann das der interne
DNS-Name des Kubernetes-Service sein. Der Maven-Prozess braucht direkten
Netzzugriff auf diesen Endpunkt. `CUSTOM_LLM_MODEL` bezeichnet das dort
bereitgestellte Modell. Ein optionaler Schlüssel kommt aus der Umgebung (`CUSTOM_LLM_API_KEY`).
Der normale Build überspringt den Test. Der folgende Aufruf deaktiviert den
Maven-Buildcache, damit jede angeforderte Prüfung neue Antworten einholt.

```bash
# Beispiel für den Service llm-service im selben Kubernetes-Namespace
export CUSTOM_LLM_URL=http://llm-service:8000/v1/chat/completions
export CUSTOM_LLM_MODEL='bereitgestellter-modellname'
./mvnw -pl taxonomy-app -am test -Dtest=ArchitectureDialogueVllmLiveTest \
  -Dgroups=real-llm -DexcludedGroups=onnx -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtaxonomy.dialogue.evaluateLive=true -Dmaven.build.cache.enabled=false
```
