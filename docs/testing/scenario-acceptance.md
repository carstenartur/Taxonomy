# Szenariobasierte Architekturabnahme

Die Abnahme beginnt mit einer öffentlichen Anforderung und führt den echten
Taxonomy-Copilot bis zum gespeicherten Architektur-Snapshot und dessen Exporten aus.
Nur die HTTP-Antwort der entfernten LLM wird durch Spring ersetzt. Projektverwaltung,
Authentifizierung, Katalog, Prompts, Providerformat, Parser, Analyse, Verifikationsläufe,
anforderungsbezogene Beziehungssuche, Datenbank, Git und Renderer bleiben echte
Anwendungskomponenten. Die Abnahme prüft einen begrenzten funktionalen Referenzfall
mit fachlich verfassten Modellantworten. Sie ist kein Nachweis, dass ein beliebiges
Live-Modell vollständige oder bessere Produktionsarchitekturen erzeugt.

## Nachvollziehbare Anforderung

`FLOOD-001` beschreibt einen ergänzenden Hochwasserinformationsdienst für England.
Die [GOV.UK-Dienstbeschreibung](https://www.gov.uk/get-flood-warnings) belegt
gebietsbezogene Warnungen, Kontaktkanäle und die Verwaltung eines Benutzerkontos.
Die [API-Dokumentation der Environment Agency](https://environment.data.gov.uk/flood-monitoring/doc/reference)
beschreibt Warngebiete, Messstellen, Beobachtungen, effiziente Abfragen und die Grenzen
der Verfügbarkeit. Beide Quellen wurden am 19. September 2026 geprüft.

Die Anforderung ist eine eigene, ausdrücklich gekennzeichnete Zusammenfassung dieser
Quellen. Warteschlange, idempotente Zustellung und Auditspur sind zusätzliche
Testannahmen (`A1`). Der öffentliche Datenzugang garantiert keine Verfügbarkeit und
ersetzt keine offiziellen sicherheitskritischen Warnkanäle. Es werden weder echte
Warnungen verschickt noch reale Abonnentendaten verwendet.

Die Datei
[`flood-information.json`](../../taxonomy-app/src/test/resources/scenarios/flood-information.json)
enthält Quellen, Anforderung, Katalogbindungen, hierarchische Bewertungsantworten und
die fachlich verfassten Beiträge und Beziehungsbehauptungen der Wiedergabe. Die
ausgelagerten Ausschlussregeln werden beim Laden ergänzt. Das ist ein mehrstufiger
Antwortkorpus, keine Liste von acht festgelegten Architektur-Endpunkten und keine
Aufzeichnung echter LLM-Ausgaben. Alle gebundenen Codes und Namen werden gegen
`/api/taxonomy` geprüft, das den echten Katalog liefert.

Die unabhängig gepflegte
[`flood-quality-reference.json`](../../taxonomy-app/src/test/resources/scenarios/flood-quality-reference.json)
beschreibt die erwarteten gerichteten Funktionen, erlaubte bedingte Ergänzungen,
Abdeckungspflichten und offen zu haltenden Eingangsidentitäten. Ihre fachlichen
Grenzen und die Entwicklung des Antwortkorpus stehen in der
[Referenzprüfung](../qa/scenario-reference-review.md). Bewertungswerte wählen und
gewichten Katalogbeiträge; sie sind weder ein Erfüllungsgrad der Anforderung noch
eine Wahrscheinlichkeit für die Richtigkeit oder eine Freigabe der Architektur.

## Testgrenze und Ablauf

`ScenarioLlmConfiguration` bindet `MockRestServiceServer` an das vorhandene
`RestTemplate`. `llm.mock=false` bleibt gesetzt. Der echte OpenAI-kompatible Gateway
sendet seine normalen Requests an `https://scenario.invalid/v1/chat/completions`;
der Spring-Server beantwortet sie im Prozess. Kein Netzwerk-Fallback ist möglich.
Unbekannte URLs, Modelle, Anfragen, Aufgaben, Budgets oder Kategorien führen zum Fehler.

[`ScenarioLlmPlayback`](../../taxonomy-app/src/test/java/com/taxonomy/acceptance/ScenarioLlmPlayback.java)
und die fachlichen Wiedergabestrategien prüfen die Anforderung, Bewertungsbudgets,
vollständige Kandidatenmengen, Quellbeiträge, Suchrichtung und den jeweiligen
Navigations- oder Verifikationsschritt. Unbekannte oder widersprüchliche Anfragen
werden nicht durch eine allgemeine Erfolgsantwort beantwortet. Auch vom
Anwendungscode abgefangene Playback-Fehler lassen die Abnahme scheitern. Die
Abdeckung wird für beide unabhängigen Verifikationsläufe geprüft; Fortsetzungen
dürfen gespeicherte Antworten wiederverwenden und sind keine zusätzlichen Läufe.

Der standardmäßig aktivierte produktive Pfad
[`RequirementRelationSearchService`](../../taxonomy-analysis/src/main/java/com/taxonomy/analysis/relations/RequirementRelationSearchService.java)
ermittelt anforderungsbezogene Beiträge, sucht passende Gegenstellen hierarchisch
und lässt einen Treffer gesondert verifizieren. Richtung, Funktion, Notwendigkeit,
Bedingung und wörtlicher Originalbeleg bleiben strukturiert erhalten.
[`EvidenceRelationProjection`](../../taxonomy-architecture/src/main/java/com/taxonomy/architecture/pipeline/EvidenceRelationProjection.java)
übernimmt gesondert geprüfte notwendige Beziehungen in die erforderliche Sicht;
Optionen, Alternativen und offene Fragen bleiben unterscheidbar. Eine hohe
Bewertung zweier Katalogeinträge allein erzeugt hier keine Beziehung. Die
Snapshot-Vorschläge sind damit noch nicht in die aktive Architektur übernommen.

1. Anmelden; Projekt und Anforderung anlegen.
2. Copilot mit Profil **Exhaustive**, zwei Verifikationsläufen und Lösungsvorschlägen starten.
3. Auf den gespeicherten Endzustand warten und fortsetzbare Budgetarbeit für beide
   ursprünglichen Läufe über die vorhandene Fortsetzungsaktion weiterführen.
4. Beide Snapshots auf gleiche Scores, Begründungen und semantische Architektur
   prüfen; ursprüngliche Snapshots und getrennte Verbrauchsjournale erhalten.
5. Den ausgewählten Snapshot erneut über HTTP öffnen und dessen Identität prüfen.
6. Exporte aus diesem Snapshot bzw. seiner erzeugten Graphstruktur erstellen und prüfen.
7. Die erzeugte ArchiMate-Datei in einem per REST angelegten getrennten Repository prüfen
   und übernehmen; denselben gerichteten Graphen mit seinen Belegen über das
   Sparx-Profil `sparx-xmi-2.1@3` ausliefern und wieder einlesen.
8. Optional denselben Ablauf im echten Browser ausführen, während der Analyse neu laden,
   Ergebnisse und Workbench öffnen, Bedienelemente ausführen und Screenshots speichern.

[`ScenarioContinuationWalkthrough`](../../taxonomy-app/src/test/java/com/taxonomy/ScenarioContinuationWalkthrough.java)
begrenzt die Fortsetzungen je ursprünglichem Lauf. Jeder Schritt muss Fortschritt
nachweisen, die ursprünglichen Ressourcenlimits einhalten und einen neuen Snapshot
erzeugen; sein unveränderlicher Vorgänger bleibt abrufbar. Die Abnahme verwendet
bewusst einen engeren Ressourcenrahmen als die konfigurierbaren Produktionsvorgaben.

Der erwartete Endzustand dieses Referenzfalls ist ausdrücklich **`PARTIAL`** mit
**`COMPLETED_WITH_GAPS`**: Eine begrenzte Frage zur Identität eines F5-Eingangs bleibt
offen. Sie darf weder durch eine erfundene Produktidentität noch durch eine pauschale
Erfolgsmeldung verschwinden. Die unabhängige Prüfung verlangt die vorgesehenen
Funktionen und Belege, keine ausstehende Budgetarbeit und ausschließlich die exakt
zugelassenen offenen Bindungen. `PASS_WITH_OPEN_POINTS` im fachlichen Prüfbericht
ist deshalb mit einem weiterhin partiellen Snapshot vereinbar.

Der Test verwendet eine echte HSQLDB-Datenbank. Optionales ONNX-Embedding ist deaktiviert;
es wird nicht durch eine Attrappe ersetzt. Datenbankvarianten und reale Remote-LLMs
bleiben Aufgaben ihrer vorhandenen separaten Testreihen.
Während des Abschlusses lesen drei HTTP-Clients gleichzeitig den Auftragsstatus.
Jede Antwort muss erfolgreich sein; ein HTTP-500-Fehler wird nicht durch erneutes
Versuchen verdeckt.

## Ausführen

Abnahme ohne Docker und ohne LLM-Schlüssel, mit lokalem Chrome/ChromeDriver
(siehe [Voraussetzungen und GUI-Vorschau](docker-free-tests.md)):

```bash
./mvnw -B -ntp -pl taxonomy-app -am test \
  -Ptest-local,scenario-acceptance
```

Browserabnahme und Dokumentationsbilder mit Docker/Selenium:

```bash
./mvnw -B -ntp -pl taxonomy-app -am test -Pscenario-acceptance
```

Alternativ kann ein lokal installiertes zusammenpassendes Chrome/ChromeDriver-Paar
mit `-Dwebdriver.chrome.driver=/absolute/path/chromedriver` und optional
`-Dscenario.chrome.binary=/absolute/path/chrome` verwendet werden.
Das Profil `scenario-acceptance` setzt `generateScreenshots=true` und aktiviert damit
Browser und Screenshots. `-DgenerateScreenshots=false` schaltet beides aus.
HTML-Inhalte, vorbereitete Analyseergebnisse und erfolgreiche Auftragszustände
werden nicht injiziert; ersetzt werden ausschließlich die entfernten Provider-Antworten.
Der Container-Browser vertraut ausschließlich der dynamischen HTTP-Testadresse
`host.testcontainers.internal:<port>`, wie die vorhandene Container-Testinfrastruktur.
Damit blockiert Chrome die Anhänge nicht als unsichere Downloads. Die Ausnahme gilt
nur für diesen Browserlauf; produktive Bereitstellungen benötigen ihre reguläre
HTTPS-Konfiguration.

Die ergänzende CI
[`.github/workflows/scenario-acceptance.yml`](../../.github/workflows/scenario-acceptance.yml)
führt den Browserweg bei relevanten Pull Requests aus und bewahrt das gesamte
Abnahmepaket als Workflow-Artefakt auf. Sie benötigt keine Provider-Secrets und schreibt
nichts in den Branch zurück. Der kanonische Einstieg für den kombinierten lokalen
CI-Prüfumfang lautet:

```bash
./mvnw -B verify -Pci -DrunOnnxTests=true
```

Die [Maven-Verifikationsautorität](../dev/MAVEN_VERIFICATION.md) beschreibt dessen
native Voraussetzungen, separate Datenbank-Lanes und zusätzliche externe
Freigabebedingungen. Die Szenarioauswahl oben ersetzt diese Gesamtprüfung nicht.

## Exporte und Prüftiefe

Die [Word- und Sparx-Abschlussprüfung](../qa/word-and-sparx-completion-review.md)
unterscheidet die beiden eingefrorenen Word-Exporte, historische Renderbefunde,
implementierte Integrationsverträge und weiterhin gesondert erforderliche
Produktabnahmen. Maßgeblich sind jeweils die dort genannten Revisionen und Artefakte.

| Ausgabe | Geprüfte Eigenschaften |
|---|---|
| Architektur-SVG | XML, Knotenidentitäten, kein Skript, Koordinaten innerhalb der Szene |
| Architektur-PDF | PDFBox liest das Dokument; Knoten, Anforderung und Snapshot im Text; mindestens 7 pt für sämtliche Zeichen; optional gerenderte Vorschau |
| ArchiMate XML + ZIP | Profil-Reader rekonstruiert denselben Graphen; Bundle-Inhalt und Hash stimmen überein |
| Visio VSDX + ZIP | XML-Paket, ursprüngliche Element-/Beziehungsidentitäten, vorhandene Connector-Endpunkte, gleicher Bundle-Inhalt |
| Entscheidungsbericht HTML, DOCX, JSON | Anforderung und Snapshot erhalten; Word-Dokument mit echtem POI-Parser gelesen |
| Registrierte Diagrammadapter | ArchiMate, Visio, SVG, Mermaid, Structurizr und Sparx erhalten denselben erzeugten Graphen; Mermaid-Labels sowie offizieller Structurizr-Parser und lokale Importparität |
| Allgemeiner Bericht Markdown, HTML, DOCX, JSON | Echte REST-Endpunkte erhalten die erzeugten Scores; Dokumente lesbar und der Anforderung zugeordnet |
| Unabhängiges Rendering | LibreOffice Writer/Draw öffnen DOCX/VSDX; Poppler prüft Begründungen, Knotenlabels und die tatsächlichen Beziehungs-Schlüssel/Richtungen/Typen auf den nativen Detailseiten, leere Seiten und quellgebundene Seitengrenzen; PNGs für Sichtprüfung |
| Sparx-XMI über echte Integrations-API | Geprüfter ArchiMate-Import, natives Journal, Exportvorschau, ausdrückliche Review-Entscheidungen, Dateiabruf und semantischer Vergleich des vollständigen Szenariographen mit Profil v3 |
| ArchiMate-Integrationscodec | Tatsächlich exportierte Datei wird eingelesen, erneut geschrieben und mit gleichen Identitäten eingelesen |
| Unabhängige fachliche Architekturprüfung | Gerichtete erforderliche Beziehungen und zulässige Ergänzungen; funktionale und qualitative Pflichten am richtigen Element bzw. an der richtigen Beziehung; Originalzitate, Notwendigkeit, Bedingungen, Herkunft, Review-Status und eng begrenzte offene Fragen bis in die Sparx-Datei |

Die Snapshot-/Berichtsdateien und die geprüfte Sparx-XMI-Datei entstehen zusammen
mit Snapshot, Auftragsdaten, Fortsetzungshistorie, LLM-Aufrufprotokoll und
Qualitätsberichten in `taxonomy-app/target/scenario-acceptance/`. Die registrierten
Diagrammadapter werden direkt über ihre produktive Spring-Registrierung aufgerufen,
da ihre älteren REST-Endpunkte bewusst eine neue Analyse starten würden. Die Snapshot-Exporte dürfen
keinen zusätzlichen LLM-Aufruf auslösen.

[`ScenarioArchitectureQuality`](../../taxonomy-app/src/test/java/com/taxonomy/acceptance/ScenarioArchitectureQuality.java)
prüft den tatsächlichen Snapshot, den Workbench-Graphen und das gelieferte Sparx-XMI
gemeinsam gegen die unabhängige Referenz. `classification-quality.json`,
`prompt-evidence-quality.json`, `relation-quality.json` und `architecture-quality.json`
trennen Bewertungs-, Prompt-, Such- und funktionale Modellnachweise.
`integration-import-review.json` und `integration-sparx-review.json` halten die
ausdrücklichen Übernahmeentscheidungen fest; der ursprüngliche Snapshot bleibt
unverändert. Die aktuelle Integrationsprüfung verlangt, dass alle Beziehungen des
erzeugten Szenariographen über Profil v3 mit gerichteten Endpunkten, Typen und
Taxonomy-Belegen erhalten bleiben. Das ist eine Prüfung des funktionalen Modells
und der Austauschverträge, keine Ausführung oder Zertifizierung von Sparx EA.

Die ältere [Referenzprüfung](../qa/civilian-reference-review.md) beschreibt
den historischen Fall mit acht ausgewählten Endpunkten, 38 Graphknoten und
44 Beziehungskandidaten sowie dem damaligen Sparx-Profil v1 mit acht lieferbaren
Beziehungen. Diese Zahlen und damaligen F3/F4-Lücken sind historische Befunde;
sie beschreiben weder den heutigen Antwortkorpus noch die aktuelle Abnahme.

Als reproduzierbare Dokumentregression sind die komprimierten Dateien unter
[`complete-flood-v1`](../../taxonomy-tooling/src/test/resources/scenario-documents/complete-flood-v1)
erhalten. Ihr `run.json.gz` identifiziert den Lauf
[39296468148](https://github.com/carstenartur/Taxonomy/actions/runs/39296468148) an
Revision `2fb5a7ae342af5c1ade5b97b65de4bed658f38f5`; die Berichtsmetadaten datieren
ihn auf den **19. Oktober 2026**. Dieser gespeicherte Lauf enthält **50 Knoten,
36 Beziehungen und 5.994 verfasste Provider-Antworten über die HTTP-Testgrenze**;
sein Analysestatus bleibt `PARTIAL`. Die Aufrufzahl bezeichnet Wiedergabeaustausche
des gesamten Abnahmelaufs, keine echten Remote-Modellaufrufe und keine Zusage zum
Aufwand einer neuen Analyse. Korpus, Referenz und aktuelle Prüfläufe können sich
weiterentwickeln; ihre Fingerabdrücke und tatsächlichen Ergebnisse sind jeweils
getrennt zu lesen.

Die CI rendert die tatsächlichen Dateien zusätzlich mit:

```bash
./mvnw -B -ntp -pl taxonomy-tooling -am compile -DskipTests
java -cp taxonomy-tooling/target/classes com.taxonomy.tooling.TaxonomyTooling check-scenario-documents --artifacts taxonomy-app/target/scenario-acceptance
```

Dafür werden LibreOffice Writer/Draw und Poppler benötigt. `document-qa/` enthält
PDFs, sämtliche Seiten-PNGs und `quality.json` mit Renderer-Version, Hashes und
Textprüfungen einschließlich der Zuordnung jeder kanonischen Beziehung zu ihrer
lesbaren Detailseite. Ein historischer, revisionsgebundener VSDX-Nachweis steht in
[visio-graphical-quality.json](../qa/visio-graphical-quality.json). Mit dem optionalen
Schalter `--visio-only` wird gezielt VSDX geprüft, wenn unveränderte Word-Nachweise
bereits vorliegen; ohne Schalter bleibt die vollständige Dokumentprüfung aktiv.
Dies ersetzt keine Abnahme mit Microsoft Visio oder Sparx EA.

`quality.json` dokumentiert Prüfungen, Graphgrößen, isolierte Kategorien und Datei-Hashes.
`run.json` benennt Szenario, Fixture-Hash, Snapshot und in der CI den Commit und Run.
`controls.json` und `browser.json` entstehen nur bei erfolgreicher Browserabnahme.
Die vier Workbench-Downloads werden tatsächlich im Browser heruntergeladen und
anschließend derselben Inhaltsprüfung unterzogen; `browser.json` enthält ihre Hashes.
Der Fokus muss exakt den gewählten Knoten und seine direkten Nachbarn enthalten,
und „Fit“ muss die Knoten innerhalb der sichtbaren SVG-Fläche platzieren.
Der vollständige Snapshot bleibt ein generiertes Testartefakt; er wird nicht als
vorbereitete Eingabe in den nächsten Lauf geladen.

Die mobile Browserprüfung verwendet Chromes Geräteemulation mit 390 × 844 CSS-Pixeln
und prüft die tatsächliche Viewport-Breite. Eine bloße Fenstergrößenanforderung reicht
nicht aus: Chrome begrenzt normale Fenster in dieser Umgebung auf mindestens 500 Pixel.
Die Emulation verändert weder Anwendungsdaten noch Netzwerkantworten.

## Bedienelemente des dokumentierten Ablaufs

| Ansicht / Bedienelement | Funktion und erwartetes Ergebnis |
|---|---|
| Projects / neues Projekt / Save | Legt das Projekt über den echten Portfolio-Endpunkt an. |
| Neue Anforderung / Typ / Save | Speichert den Text und eine erste Anforderungsversion. |
| Anforderung / Portfolio / Matrices | Öffnet die Projektübersicht bzw. die Matrizen des Projekts. |
| Anforderung / EN / DE | Wechselt die Sprache der Seite. |
| Anforderung / Open architecture workbench | Öffnet die Architektur des ausgewählten gespeicherten Ergebnisses. |
| Anforderung / Analyze current version | Startet die einzelne Analyse der aktuellen Anforderungsversion. Der Referenzablauf verwendet den vollständigen Copilot darunter. |
| Anforderung / New version | Öffnet den Dialog für einen neuen Textstand; Create version speichert ihn, Cancel schließt den Dialog. |
| Anforderung / Text & source | Zeigt Anforderungstext und gegebenenfalls das importierte Quellfragment. Die Quellen dieses manuell angelegten Falls stehen in der Szenariodatei. |
| Anforderung / Versions / Analyses | Zeigt gespeicherte Textstände bzw. Analysen und deren auswählbare Snapshots. |
| Anforderung / Taxonomy & architecture | Zeigt Zuordnungen und Architekturinformationen des Ergebnisses. |
| Anforderung / Decisions / Solutions & products | Zeigt offene fachliche Entscheidungen bzw. Lösungs- und Produktvorschläge. Diese erfordern menschliche Prüfung. |
| Copilot / Profile | Standard, Full oder Exhaustive wählen; die Referenz verwendet Exhaustive für zwei Verifikationsläufe. |
| Copilot / Force | Erzwingt einen neuen Lauf, auch wenn ein verwendbares Ergebnis existiert. |
| Copilot / Run full analysis | Startet den persistenten Auftrag; Status und Verifikationsläufe erscheinen. |
| Copilot / Cancel | Fordert den Abbruch aktiver Läufe an; nach dem Abschluss ist der Knopf deaktiviert. Abbruch-Fehlerszenarien besitzt `CompleteCopilotSessionIT`. |
| Copilot / Ergebnisaktionen | Öffnen den ausgewählten Snapshot bzw. die Architektur, keine neue Analyse. |
| Ergebnis / Berichtsformate | Laden den Entscheidungsbericht des ausgewählten Snapshots als HTML, DOCX oder JSON. |
| Workbench / Suche | Markiert Knoten nach Code, Titel oder Schicht; die Referenz sucht CI-1052. |
| Workbench / × | Leert die Suche und entfernt die Suchmarkierung. |
| Workbench / Knoten auswählen | Zeigt Identität, Herkunft, Begründung und Review-Status im Detailbereich. |
| Workbench / Context elements | Schaltet bei vorhandenen Impact-Ankern zusätzliche Kontextknoten ein oder aus. Die evidenzbasierte Sicht hat keine solchen Anker: Der Schalter bleibt dann markiert und deaktiviert, alle Modellelemente bleiben sichtbar. Beide Fälle werden anhand des tatsächlichen Snapshots geprüft. |
| Workbench / Overview | Zeigt wieder die vollständige sichtbare Übersicht. |
| Workbench / Focus | Zeigt den gewählten Knoten und seine Nachbarn; benötigt eine Auswahl. |
| Workbench / − und + | Verkleinern bzw. vergrößern den Ausschnitt. |
| Workbench / Fit | Passt die Darstellung an die verfügbare Zeichenfläche an. |
| Workbench / Fullscreen | Wechselt in den Vollbildmodus und zurück. |
| Workbench / Architecture editor | Öffnet den separaten Editor; die angezeigte Workbench selbst bleibt eine schreibgeschützte Snapshot-Ansicht. |
| Workbench / Download SVG / PDF | Lädt die serverseitige Architektur; die Datei ist kein Screenshot der Seite. |
| Workbench / Download ArchiMate + manifest | Lädt Austausch-XML mit Profil- und Verlustinformationen. |
| Workbench / Download Visio + manifest | Lädt VSDX mit stabilen Identitäten und Verlustinformationen. |

Ansichtsfilter und Zoom verändern den gespeicherten Snapshot nicht. Die Download-
Endpunkte verwenden dessen vollständigen Graphen. Das vollständige DOM-Inventar
enthält zusätzlich sichtbare, versteckte und deaktivierte Steuerelemente; die Tabelle
beschreibt die für diesen Ablauf relevanten Aktionen, keine vollständige Anleitung
aller Verwaltungsseiten der Anwendung.

## Historische Bildschirmnachweise

Frühere, unveränderte Browseraufnahmen und ihre Commit-/Snapshot-Nachweise stehen
im [historischen Bildanhang](../qa/scenario-screenshots-history.md). Sie werden nicht
als Aufnahmen des aktuellen Quellstands ausgegeben. Neue Prüfläufe verwenden die
neutralen Szenarionamen und erzeugen ihre eigenen Dateien und Fingerabdrücke.

## Historische Qualitätsbefunde und Prüfgrenzen

Die Abnahme hat eine reale Vervielfachung abgeleiteter Beziehungen aufgedeckt:
auch gleich bewertete Vorfahren wurden als konkrete Impact-Endpunkte behandelt.
Die Auswahl unterdrückt nun einen Vorfahren, wenn ein qualifizierter Nachfolger
seinen Pfad repräsentiert. Andere Zweige und schwach bewertete Nachfolger bleiben
korrekt unterscheidbar. Im damaligen Referenzfall sank die Zahl von 334 auf
44 Beziehungen. Das beschreibt die historische Ableitung, nicht die aktuelle
anforderungsbezogene Beziehungssuche.

Zwei weitere Befunde betreffen die Ausgabe: Ein interner Übersetzungsschlüssel wurde
als Titel übernommen; jetzt wird dafür der lesbare Anforderungstitel verwendet.
Die breite Szene wurde zuvor auf A3 bis auf 5 pt verkleinert, und lange Typnamen
ragten aus dem Knoten. Der PDF-Renderer vergrößert jetzt die Seite bis zur natürlichen
Szenengröße, trennt Typ und Score und prüft deren Positionen in einer Regression.
A3 bleibt die Mindestgröße. Breite Diagramme sind damit Poster-PDFs; ein Ausdruck
mit „auf A3 verkleinern“ würde die Schrift erneut verkleinern. Extrem große Szenen
oberhalb der PDF-Seitengrenze von 14.400 pt werden weiterhin skaliert.

Parallele Statusabfragen deckten außerdem einen Schreibkonflikt beim Abschluss des
Copilot-Auftrags auf. Die Auswahl des aktuellen Snapshots sperrt jetzt die betroffene
Anforderung innerhalb ihrer Transaktion; wiederholte Abschlussversuche bleiben
idempotent. Die Regression verwendet drei gleichzeitige echte HTTP-Abfragen.

„Fit“ berücksichtigt nun auch Ansichten unter 20 % Zoom. Die frühere Untergrenze
schnitt breite Diagramme auf einem Telefon und sehr hohe Diagramme ab. Die
Geometrieprüfung reproduziert beide Fälle und verlangt vollständig sichtbare Grenzen
mit Rand; die Browserabnahme prüft zusätzlich die tatsächliche mobile SVG-Fläche.

Der Copilot liefert eine **Taxonomie-basierte funktionale Kandidatenarchitektur**.
Die aktuelle Referenz prüft die deklarierten Funktionen und ihre gebundenen
Qualitätsanforderungen einschließlich Kontoverwaltung; die F5-Identitätsfrage bleibt
sichtbar. Das belegt keine implementierten Abläufe, Zustellgarantien, konkreten
Datenmodelle, Laufzeitwerte oder Sicherheitszertifizierung. Modellgestützte
Verifikation und wörtliche Belege ersetzen keine menschliche semantische Prüfung;
die Konfidenz ist nicht als Wahrscheinlichkeit der Architekturrichtigkeit kalibriert.
Vollständigkeit außerhalb dieses begrenzten Referenzfalls, Lesbarkeit bei gewünschtem
Druckmaßstab und echte Zielprodukt-Kompatibilität sind zusätzliche Abnahmekriterien.
Der lokale Structurizr-Importer ist keine unabhängige Prüfung der vollständigen DSL.
Sparx EA und Microsoft Visio werden durch diese Tests nicht als Desktop-Produkte gestartet.

Für eine visuelle Freigabe sind die echten Browserbilder und die gerenderte PDF-Seite
zu prüfen: lesbare Bezeichnungen, keine Überdeckungen, nachvollziehbare Verbindungen,
kein abgeschnittener Text, verständliche Hinweise auf Hypothesen und Exportverluste.
Ein erfolgreicher Parser-Test allein ist keine visuelle Freigabe.

Die erste tatsächliche Word-Renderprüfung lieferte einen historischen Befund:
Der damalige Entscheidungsbericht umfasste 87 Seiten, darunter eine Seite ohne Inhalt
zwischen Titelseite und Zusammenfassung. Wiederholte Begründungen in schmalen
Tabellenspalten erschwerten die kompakte Darstellung. Diese damalige Datei war trotz
bestandener Strukturprüfung nicht visuell freigegeben. Der
[historische Validierungsnachweis](../qa/civilian-acceptance-validation.md) bleibt
erhalten; spätere eingefrorene Word-Berichte und die quellgebundene Complete-Flood-
Paginierung sind in der [Word-Abschlussprüfung](../qa/word-and-sparx-completion-review.md)
gesondert dokumentiert. Ein neuer Lauf benötigt seine eigenen Render- und Sichtnachweise.
