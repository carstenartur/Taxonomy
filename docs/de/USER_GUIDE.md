# Taxonomy Architecture Analyzer — Benutzerhandbuch

Dieses Handbuch folgt Browseraufgaben von einer ersten Anforderung zu geprüften Entscheidungen und versionierten Ergebnissen. API-Syntax, Deployment-Einstellungen und Modellverträge stehen in den verknüpften Fachanleitungen; aus einer API-Funktion folgt kein entsprechender GUI-Schalter.

## Schnellster Weg — Ihre erste Analyse in 5 Schritten

| Schritt | Aktion | Wo |
|---|---|---|
| 1 | Mit dem zugewiesenen Konto anmelden. | Anmeldeseite Ihrer Installation |
| 2 | Anforderung mit wesentlichen Bedingungen eingeben. | Business Requirement Analysis |
| 3 | Umfang auswählen und unterstützte Analyse starten. | Analyse- oder Copilot-Bedienung |
| 4 | Ergebnisse, Gründe, Fortschritt und offene Arbeit prüfen. | Taxonomie- und Architekturansichten |
| 5 | Gewünschtes Ergebnis speichern/prüfen und innerhalb der unterstützten Grenzen exportieren. | Projekte, Workbench oder passende Exportaktion |

Es gibt kein wiederverwendbares Standardpasswort. Erstmalige lokale Administrator-Einrichtung ist eine gesonderte Betreiberaufgabe; Keycloak/OIDC besitzt eigene Vorgaben. Siehe [Sicherheit](SECURITY.md).

Die vorhandenen Abbildungen illustrieren Produktoberflächen. Ihr Erhalt behauptet weder neue Screenshots noch eine frische vollständige Browserabnahme für diese Dokumentationsänderung. Rolle, Sprache, Ansicht und Modus beeinflussen sichtbare Bedienelemente.

## Inhaltsverzeichnis
- [1. Übersicht](#guide-section-1)
- [2. Erste Schritte](#guide-section-2)
- [3. Die Benutzeroberfläche verstehen](#guide-section-3)
- [4. Eine Geschäftsanforderung analysieren](#guide-section-4)
- [5. Die Taxonomie erkunden](#guide-section-5)
- [6. Arbeiten mit Analyseergebnissen](#guide-section-6)
- [7. Architekturansicht — Anforderungs-Wirkungsanalyse](#guide-section-7)
- [8. Den Graph Explorer verwenden](#guide-section-8)
- [9. Arbeiten mit Beziehungsvorschlägen](#guide-section-9)
- [10. Ergebnisse exportieren](#guide-section-10)
- [11. Suche](#guide-section-11)
- [11a. Qualitäts-Dashboard](#guide-section-12)
- [11b. Relations-Browser](#guide-section-13)
- [11c. Anforderungsabdeckung](#guide-section-14)
- [11d. Architektur-Lückenanalyse](#guide-section-15)
- [11e. Architektur-Empfehlung](#guide-section-16)
- [11f. Erkennung von Architekturmustern](#guide-section-17)
- [11g. Architektur-DSL](#guide-section-18)
- [12. Versionen-Tab](#guide-section-19)
- [13. Git-Status und Kontextleiste](#guide-section-20)
- [14. Administration](#guide-section-21)
- [15. Referenz der Beziehungstypen](#guide-section-22)
- [15a. Dokumentenimport & Quellenherkunft](#guide-section-23)
- [16. Tipps und Best Practices](#guide-section-24)
- [17. Glossar](#guide-section-25)
- [18. Fehlerbehebung](#guide-section-26)
- [Gespeicherte Neuformulierungsangebote und Entscheidungsfragen](#guide-section-27)

<a id="guide-section-1"></a>
## 1. Übersicht


Taxonomy unterstützt Architekten, Analysten und Requirements Engineers in Behörden und Unternehmen. Anforderungen werden mit dem hierarchischen Katalog, Architekturvorschlägen, nachvollziehbaren Fragen und ausdrücklich übernommenen Änderungen verbunden. Ein Katalogeintrag ist nicht automatisch eine konkret installierte Lösung; Relevanz beweist weder Notwendigkeit noch Verfügbarkeit.

<a id="requirement-clarification-workflow"></a>
**Der vollständige Klärungsablauf:** Eine gespeicherte Anforderungsversion samt Analysesnapshot auswählen, ein separates Neuformulierungsangebot erzeugen, Fragen im Architekturkontext prüfen und beantworten oder zurückstellen, die Übernahme als Anforderungsentwurf ausdrücklich bestätigen und anschließend gesondert eine neue Analyse anfordern. Frühere Quellen, Antworten und Snapshots bleiben unterscheidbar. Das Modell antwortet nicht stellvertretend und genehmigt keine Architektur.

**Illustratives Beispiel, kein aufgezeichnetes Modellergebnis:** Mobile Arbeitszeiterfassung kann Entscheidungen über Offline-Betrieb und Korrekturberechtigungen erfordern. Der Nutzen liegt im Zusammenhang zwischen Frage, Architekturbegründung und dokumentierter Entscheidung, nicht in einer Garantie, dass jedes Modell genau diese Fragen findet.

Der [Portfolio-Leitfaden](PROJECT_REQUIREMENT_PORTFOLIO.md) erklärt diesen gespeicherten Ablauf; der [Architektureditor](ARCHITECTURE_EDITOR.md) dient geprüften Modelländerungen. Beginnen Sie mit Analyse und Sichtung und gehen Sie je nach Aufgabe zu Entscheidungen, Versionen und Export über.

<details>
<summary>Vorhandene Oberflächenabbildungen</summary>

![Bewerteter Taxonomiebaum](../images/15-scored-taxonomy-tree.png)

</details>

<a id="guide-section-2"></a>
## 2. Erste Schritte

<a id="die-anwendung-öffnen"></a>
<a id="ki-verfügbarkeit-prüfen"></a>

Öffnen Sie die vom Betreiber angegebene Adresse und melden Sie sich mit Ihrem zugewiesenen Konto an. Verwenden Sie für normale Arbeit kein gemeinsam genutztes Administratorkonto. Die erstmalige lokale Administration nutzt das konfigurierte Passwort oder eine eigentümergeschützte einmalige Bootstrap-Datei; es gibt kein wiederverwendbares Standardpasswort. Keycloak/OIDC besitzt eine eigene Einrichtung. Siehe [Sicherheit](SECURITY.md) und [Keycloak-Einrichtung](KEYCLOAK_SETUP.md).

Die Willkommensanzeige führt durch Anforderung, Analyse und Ergebnisse. Ihr Schließen legt kein Projekt an, genehmigt kein Modell und speichert keine Analyse. Der KI-Status zeigt Anbieterbereitschaft, nicht fachliche Richtigkeit oder Vollständigkeit. Katalogsuche und deterministische Funktionen können ohne generativen Anbieter verfügbar bleiben. Lokales ONNX unterstützt Embeddings/Suche/Bewertung, keine generative Relationsprüfung oder Neuformulierung.

<details>
<summary>Vorhandene Oberflächenabbildungen</summary>

![Vollständiges Seitenlayout](../images/01-full-page-layout.png)

</details>

<a id="guide-section-3"></a>
## 3. Die Benutzeroberfläche verstehen

<a id="linkes-panel--taxonomiebaum"></a>
<a id="rechtes-panel--analyse-und-werkzeuge"></a>
<a id="navigationsleiste"></a>
<a id="dunkelmodus"></a>

Die Analyseoberfläche verbindet Taxonomiebaum, Anforderungseingabe und Ergebniswerkzeuge. Der Ansichtsumschalter bietet Liste, Registerkarten, Sunburst, Baum, Entscheidung und Zusammenfassung. Auf-/Zuklappen und Beschreibungen ändern die Darstellung, nicht Anforderung oder Bewertungen. Über Suche und Fokussierung sind auch Elemente außerhalb des sichtbaren Graphenausschnitts erreichbar.

Das Analyseprotokoll zeigt Arbeit und Warnungen, die Architekturansicht ausgewählte Elemente mit typisierten Beziehungen, der Graph Explorer Abhängigkeitsabfragen. Projekte verwalten gespeicherte Anforderungen und Snapshots. Versionen und Architektureditor besitzen ihren eigenen Arbeitskontext: Prüfen Sie Repository, Arbeitsbereich, Branch und Revision vor Änderungen. Administration und Einstellungen erfordern die jeweilige Rolle; das Anzeigen oder Verbergen eines Panels ist keine Berechtigungsprüfung.

Dunkelmodus und Oberflächensprache sind Darstellungseinstellungen. Sie belegen keine gleichwertige Modellqualität in verschiedenen Sprachen. Ein Ansichts- oder Einstellungswechsel ist keine neue Analyse. [Konzepte](CONCEPTS.md) erläutert Zustände; [Barrierefreiheit](ACCESSIBILITY.md) dokumentiert geprüfte Interaktionen und Grenzen.

<details>
<summary>Vorhandene Oberflächenabbildungen</summary>

![Linkes Panel — Taxonomiebaum in Listenansicht](../images/02-left-panel-list-view.png)

![Match Legend](../images/10-match-legend.png)

![Rechtes Panel — Standardzustand](../images/03-right-panel-default.png)

</details>

<a id="guide-section-4"></a>
## 4. Eine Geschäftsanforderung analysieren

<a id="eine-gute-anforderung-formulieren"></a>
<a id="standardanalyse"></a>
<a id="neu-beginnen-abbrechen-und-den-arbeitsentwurf-speichern"></a>
<a id="eine-pausierte-ad-hoc-copilot-analyse-fortsetzen"></a>
<a id="interaktiver-modus"></a>
<a id="architekturansicht-kontrollkästchen"></a>
<a id="bewertungen-und-die-farblegende-verstehen"></a>
<a id="das-analyseprotokoll"></a>
<a id="streaming-fortschrittsanzeige"></a>
<a id="fehlerbehandlung-während-der-analyse"></a>
<a id="sichtbarkeit-der-export-schaltflächen"></a>

Beschreiben Sie die Anforderung in ihren eigenen Begriffen einschließlich Ergebnis und wesentlicher Bedingungen. Lassen Sie Verneinungen, optionale Merkmale, Einschränkungen oder Termine nicht stillschweigend weg, nur um Prompts zu verkürzen. Unabhängig verwaltete Bedürfnisse gehören in getrennte gespeicherte Anforderungen; parallele Nutzer oder Aufträge benötigen keinen gemeinsamen Analysekontext.

<a id="analysis-scope-and-progress"></a>
Wählen Sie vor automatischer Analyse oder Copilot die Teiltaxonomien und vollständige Analyse oder **Nur Taxonomien**. Ausgewählte Wurzeln begrenzen Bewertung und Relationsquellen. Im vollständigen Modus bleiben kompatible Relationsziele anderer Taxonomien erreichbar. „Nur Taxonomien“ überspringt Relationssuche und relationsabhängige Architekturableitung und ist keine vollständige Architekturanalyse. Eine leere Auswahl in der Oberfläche ist ungültig.

Prüfen Sie während des Laufs die aktuelle Arbeit, nicht nur Prozentwerte. Direkte Bewertungen, durch eine gültige Nullbewertung ausgeschlossene Nachfahren, offene und nicht ausgewählte Knoten sind verschieden. Fehlgeschlagene Antworten erledigen keine Arbeit. Grenzen können ein partielles Ergebnis mit bereits nutzbaren Nachweisen erzeugen. Der eingefrorene Umfang gehört zum Ergebnis; spätere Auswahländerungen betreffen den nächsten Lauf.

Der interaktive Modus bewertet über **Knoten analysieren** schrittweise einzelne Ebenen. Diese Einzelbewertungen sind kein vollständiger automatischer Lauf. Die Option Architekturansicht steuert die entsprechende Ableitung, soweit unterstützt; sie ersetzt keine Relationsbewertung.

Nutzen Sie bei unterstützten unterbrochenen oder fehlgeschlagenen Läufen **Wiederholen**, **Fortsetzen** oder **Abbrechen** entsprechend dem angebotenen Vorgang. Prüfen Sie ungelöste Arbeit. Kompatible Fortsetzung verwendet gültige Nachweise erneut. Eine übersprungene Frage ist keine negative Feststellung. Speichern Sie vor dem Verlassen oder Neustart einen Arbeitsentwurf, wo diese Aktion angeboten wird. Entwurf, Analysesnapshot und Git-Checkpoint sind getrennte Aufzeichnungen. [Copilot und Autopilot](COPILOT_AUTOPILOT.md) erläutert Aufträge und Wiederaufnahme, [Mehrbenutzeranalyse](MULTIUSER_ANALYSIS.md) Warteschlange und Zulassungsgrenzen.

Warten Sie bei einer HTTP-429-Kontingentantwort die im Antwort-Header `Retry-After` angegebene Anzahl Sekunden, bevor Sie erneut versuchen; verwenden Sie keine pauschale Wartezeit. Das eingehende LLM-Kontingent gilt je stabiler authentifizierter Identität und Anwendungsinstanz, getrennt vom Kontingent des Modellanbieters und den Brute-Force-Sperren für Anmeldung/WebDAV. Lassen Sie Authentifizierungsfehler ohne Weitergabe von Schlüsseln prüfen. Bewahren Sie bei ungültigen Antworten oder Zeitüberschreitungen Diagnose und Teilergebnisse; Ersatznullen sind keine Modellentscheidung. „Erledigt“ betrifft den konfigurierten Arbeitsplan und beweist nicht, dass jede denkbare Beziehung gefunden wurde.

<details>
<summary>Vorhandene Oberflächenabbildungen</summary>

![Business Requirement Analysis Karte](../images/04-analysis-panel-empty.png)

![Bewerteter Taxonomiebaum](../images/15-scored-taxonomy-tree.png)

![Bewerteter Taxonomiebaum — vollständig aufgeklappt](../images/35-scored-bp-tree-expanded.png)

![Interaktiver Modus](../images/16-interactive-mode.png)

![Match Legend mit Bewertungen](../images/17-match-legend-with-scores.png)

</details>

<a id="guide-section-5"></a>
## 5. Die Taxonomie erkunden

<a id="listenansicht-standard"></a>
<a id="registerkartenansicht"></a>
<a id="sunburst-ansicht"></a>
<a id="baumansicht"></a>
<a id="entscheidungskarten-ansicht"></a>
<a id="zusammenfassungsansicht--summary"></a>
<a id="zwischen-ansichten-wechseln"></a>
<a id="alle-auf-zuklappen-verwenden"></a>
<a id="beschreibungen-ein-ausblenden"></a>

Die **Liste** bietet eingerückte Katalogeinträge und Elementaktionen; **Registerkarten** gruppieren Wurzeln. Auf-/Zuklappen wirkt in Ansichten mit hierarchischer Darstellung. Beschreibungen liefern Kontext zu ähnlichen Namen. **Sunburst** bietet radiale Darstellung, Details beim Darüberfahren und Teilbaum-Zoom. Das Ausblenden von Nullrelevanz ist ein Darstellungsfilter, keine Bewertung. **Baum** zeigt Eltern-Kind-Navigation mit Wurzelauswahl.

**Entscheidung** stellt bewertete Entscheidungspunkte dar; eine Leeranzeige vor der Analyse bedeutet nicht, dass Katalogdaten fehlen. Die **Zusammenfassung** gruppiert die abgeleitete Architektur nach Fähigkeiten, Prozessen/Rollen, Diensten, Anwendungen, Informationen und Kommunikation. Die Elementauswahl führt zum Katalogkontext zurück. Verfügbarkeit hängt von Ergebnis und Ableitungsmodus ab.

Ansichtswechsel erhalten das aktuelle Ergebnis. Ein ausgeblendeter Knoten kann weiterhin im Katalog existieren. Nullwerte, ausgeschlossene Nachfahren und unbesuchte Knoten sind mit Status und Herkunft zu lesen. Lokale Navigationsgruppen sind nicht automatisch offizielle Konzepte oder zulässige Architekturendpunkte; siehe [Gruppierung und Bewertung](TAXONOMY_SCORING.md).

<details>
<summary>Vorhandene Oberflächenabbildungen</summary>

![Listenansicht mit sichtbaren Beschreibungen](../images/09-list-view-descriptions.png)

![Registerkartenansicht](../images/05-tabs-view.png)

![Sunburst-Ansicht](../images/06-sunburst-view.png)

![Bewertete Sunburst-Ansicht](../images/39-scored-sunburst.png)

![Baumansicht](../images/07-tree-view.png)

![Entscheidungskarten-Ansicht (bewertet)](../images/69-decision-map-scored.png)

![Entscheidungskarte — leerer Zustand](../images/08-decision-map-view.png)

</details>

<a id="guide-section-6"></a>
## 6. Arbeiten mit Analyseergebnissen

<a id="die-bewertungsfarben-lesen"></a>
<a id="eine-blattbegründung-anfordern--schaltfläche"></a>
<a id="warnung-bei-veralteten-ergebnissen"></a>

Lesen Sie Bewertungen zusammen mit Begründung, Quelle, Umfang, Transformation und Status. Wurzelrelevanz, Verteilung eines Elterngewichts, Produkteignung und Embedding-Ähnlichkeit folgen unterschiedlichen Regeln. Die Farblegende stellt Zahlen dar und macht daraus weder kalibrierte Wahrscheinlichkeiten noch Freigaben.

Verwerfen Sie nicht pauschal alle Knoten unter 25 Prozent und übernehmen Sie nicht alle über 50 Prozent. Ein kleiner Anteil am Elterngewicht kann unverzichtbar sein; hohe Ähnlichkeit beweist keinen Bedarf. Fehlende oder fehlgeschlagene Bewertung ist kein Irrelevanznachweis. Siehe [Bewertungssemantik](TAXONOMY_SCORING.md).

Fordern Sie bei einem geeigneten bewerteten Blatt über die Begründungsaktion eine Erklärung an und prüfen Sie diese kritisch. Das kann einen weiteren Anbieteraufruf auslösen. Sprache und Qualität hängen von der Konfiguration ab, nicht allein von der Oberflächensprache.

Nach einer Textänderung können Ergebnisse veraltet sein. Beachten Sie Warnung und Quellversion. Entscheiden Sie ausdrücklich zwischen Erhalt des historischen Ergebnisses, Zurücksetzen des Arbeitsergebnisses und neuer Analyse. Ein früherer Export darf nicht als Analyse des inzwischen geänderten Texts erscheinen.

<details>
<summary>Vorhandene Oberflächenabbildungen</summary>

![Blattbegründungs-Modal](../images/18-leaf-justification-modal.png)

![Warnung bei veralteten Ergebnissen](../images/19-stale-results-warning.png)

</details>

<a id="guide-section-7"></a>
<a id="7-architecture-view"></a>
## 7. Architekturansicht — Anforderungs-Wirkungsanalyse

<a id="das-architekturansicht-kontrollkästchen-aktivieren"></a>
<a id="was-in-der-wirkungsanalyse-erscheint"></a>
<a id="die-visualisierung-verstehen"></a>

Aktivieren Sie die Architekturansicht für eine geeignete vollständige Analyse und prüfen Sie Elemente, Ebenen, direkte oder abgeleitete Relevanz und gerichtete Beziehungstypen. Die Wirkungsansicht dient der Vorschlagsprüfung und beweist nicht das Vorhandensein sämtlicher Abhängigkeiten oder konkreter Portfolioobjekte.

Öffnen Sie bei einem gespeicherten Projekt die Architektur-Workbench und wählen Sie den exakten Snapshot. Quellanforderung, Umfang und Nachweise sind wesentlich. Bearbeitbare Arbeitsrevision, flüchtige Bewertungsvisualisierung und Snapshot-Export sind nicht austauschbar.

Prüfen Sie Richtung, Beitrag sowie optionale und alternative Bedingungen vor der Übernahme einer Beziehung. Die Kompatibilitätsmatrix beschränkt zulässige Vorschläge, beweist aber selbst keine Abhängigkeit. [Entscheidungspipeline](DECISION_PIPELINE.md), [Entscheidungsberichte](DECISION_RATIONALE_REPORT.md) und die [Exportgrenzen](FEATURE_MATRIX.md) beschreiben die Verträge.

<details>
<summary>Vorhandene Oberflächenabbildungen</summary>

![Architekturansicht](../images/20-architecture-view.png)

</details>

<a id="guide-section-8"></a>
## 8. Den Graph Explorer verwenden

<a id="einen-knoten-auswählen"></a>
<a id="upstream-abfrage--was-speist-diesen-knoten"></a>
<a id="downstream-abfrage--was-hängt-davon-ab"></a>
<a id="ausfallauswirkungs-abfrage--was-fällt-aus-wenn-dies-ausfällt"></a>
<a id="erweiterte-ausfallauswirkung--welche-anforderungen-sind-betroffen"></a>
<a id="die-ergebnistabelle-verstehen"></a>

Wählen Sie einen Knoten über seine Graphaktion oder geben Sie im Graph Explorer eine tatsächliche Katalogkennung ein. Upstream untersucht eingehende, Downstream ausgehende Verbindungen; Ausfallauswirkung untersucht erreichbare Folgen eines Ausfalls oder Entfernens. Beachten Sie Richtung und Schrittgrenze. Begrenzte Traversierung ist keine vollständige betriebliche Auswirkungsbewertung.

Lesen Sie Ergebnistabelle, Beziehungstyp, Quelle und Prüfstatus gemeinsam. Eine Ausfallauswirkungsansicht ist eine Funktion, keine Programmausnahme. Die erweiterte Auswirkung verbindet Abhängigkeiten zusätzlich mit erfasster Anforderungsabdeckung. Anforderungswirkung ist über den Explorer abrufbar, soweit dort angeboten.

Die Abfragen beschreiben den verfügbaren Graphen im ausgewählten Kontext. Fehlende Kanten, unvollständige Abdeckung und ungeprüfte Eingaben begrenzen Aussagen. Verwenden Sie für Automatisierung und Parameter die [API-Referenz](API_REFERENCE.md), statt sichtbare Anzahlen als unbelegte Risikokennzahlen zu interpretieren.

<details>
<summary>Vorhandene Oberflächenabbildungen</summary>

![Graph Explorer Panel](../images/11-graph-explorer-panel.png)

![Graph Explorer Upstream-Ergebnisse](../images/21-graph-explorer-upstream.png)

![Graph Explorer Ausfallauswirkung](../images/22-graph-explorer-failure.png)

![Graph Explorer mit akzeptierter Beziehung](../images/37-graph-with-accepted-relation.png)

</details>

<a id="guide-section-9"></a>
## 9. Arbeiten mit Beziehungsvorschlägen

<a id="vorschläge-auslösen--schaltfläche-an-einem-knoten"></a>
<a id="auswahl-eines-relationstyps"></a>
<a id="überprüfung-von-vorschlägen-filter-pending--all--accepted--rejected"></a>
<a id="annehmen-oder-ablehnen-eines-vorschlags"></a>
<a id="massenaktionen"></a>
<a id="konfidenz-werte-und-begründung-verstehen"></a>

Nutzen Sie **Relationen vorschlagen** für einen geeigneten Knoten und wählen Sie einen zulässigen Typ. Prüfen Sie Quelle, Ziel, Richtung und Begründung. Ein erzeugter Vorschlag ist keine Übernahme. Die Filter für ausstehende, akzeptierte und abgelehnte Vorschläge unterscheiden vorgeschlagene von geprüften Beziehungen.

Übernehmen Sie erst nach Prüfung der Anforderung und Nachweise oder lehnen Sie unbegründete Aussagen mit passender Begründung ab. Sammelaktionen übertragen dieselbe Prüfverantwortung auf jedes ausgewählte Element. Hohe Modellkonfidenz ist keine kalibrierte Garantie; zwei kompatible Wurzeln begründen noch keine Kante.

Erhalten Sie widersprechende menschliche und Quellnachweise, statt das Original still zu ersetzen. Aktualisieren und prüfen Sie nach Revisionskonflikten erneut. Übernommene Beziehungen können spätere Graphansichten verändern; kontrollieren Sie den Repository-/Arbeitskontext. [API-Referenz](API_REFERENCE.md) und [Bewertungsregeln](TAXONOMY_SCORING.md) erläutern Aktionen und Grenzen.

<details>
<summary>Vorhandene Oberflächenabbildungen</summary>

![Modal „Relationen vorschlagen"](../images/13-propose-relations-modal.png)

![Panel „Relationsvorschläge"](../images/12-relation-proposals-panel.png)

![Vorschlags-Überprüfungswarteschlange — alle Vorschläge](../images/28-proposal-review-queue.png)

![Akzeptierter Vorschlag](../images/36-proposal-accepted.png)

</details>

<a id="guide-section-10"></a>
## 10. Ergebnisse exportieren

<a id="svg-export"></a>
<a id="png-export"></a>
<a id="pdf-drucken"></a>
<a id="csv-bewertungen"></a>
<a id="experimentelle-visio-2012-vsdx-teilmenge"></a>
<a id="experimentelle-archimate-31-teilmenge"></a>
<a id="mermaid-flowchart-export"></a>
<a id="json-bewertungsexport"></a>
<a id="laden-einer-gespeicherten-analyse-import"></a>
<a id="wann-export-schaltflächen-erscheinen"></a>
<a id="einen-bericht-erstellen"></a>
<a id="inhalt-des-berichts"></a>

Wählen Sie den Export nach seinem Zweck und kontrollieren Sie das dargestellte Ergebnis. Exportaktionen einer flüchtigen Analyse hängen von verfügbaren Daten ab; ein gespeicherter Snapshot besitzt einen eigenen Exportpfad. Eine Dateiendung beweist keine semantische Gleichheit unterschiedlicher Endpunkte.

| Ausgabe | Verwendungszweck und Grenze |
|---|---|
| SVG / PNG | Visualisierung teilen; prüfen, ob die Aktion das vollständige Diagramm oder den sichtbaren Ausschnitt exportiert. |
| PDF / Browserdruck | Menschenlesbare Darstellung; Browserdruck und serverseitiges Vektor-PDF sind verschiedene Vorgänge. |
| CSV | Bewertungen oder ausgewählte Matrix; keine vollständige portable Projekthistorie. |
| JSON | Unterstützten Analyseinhalt speichern/laden; kein Installationsbackup und keine automatisch gemeinsame Snapshot-Hülle aller Formate. |
| Mermaid | Textuelle Diagrammprojektion; erhaltene Semantik des konkreten Serialisierers prüfen. |
| ArchiMate / VSDX | Experimentelle begrenzte Austauschmengen mit Mapping-/Verlustgrenzen und gesonderten Abnahmen unabhängiger Werkzeuge. |

Unterstützte Snapshot-Downloads rufen beim Abruf des gespeicherten Ergebnisses kein LLM auf und interpretieren es nicht mit aktuellen Einstellungen neu. Prüfen Sie die [Funktionsmatrix](FEATURE_MATRIX.md), bevor Sie auf bearbeitbaren Austausch, allgemeine Standardzertifizierung oder verlustfreie Rundläufe vertrauen. Schema-Prüfung oder technisches Öffnen zertifiziert nicht jedes Desktopwerkzeug.

**Bewertungen laden** importiert das unterstützte JSON-Analyseformat. Prüfen Sie Quelle, Umfang und Warnungen. Der Vorgang stellt nicht alle Projekte, Journale oder Git-Historien wieder her. Bewahren Sie die Originaldatei auf.

<a id="10a-generating-reports-mdhtmldocx"></a>
### 10a. Berichte erstellen (MD/HTML/DOCX)

Wählen Sie Projekt-/Anforderungsbericht und gespeicherte Quelle vor dem Erzeugen von Markdown, HTML oder Word. Prüfen Sie Vorschau, Erläuterungen, Diagramme und offene Entscheidungen, nicht nur das Aussehen. Gespeicherte Neuformulierungsrevisionen und Übernahmebelege besitzen eigene historische Exporte ohne ungespeicherte Eingaben. [Portfolioberichte](PROJECT_REQUIREMENT_PORTFOLIO.md), [Entscheidungsberichte](DECISION_RATIONALE_REPORT.md) und [Dokumentvorlagen](DOCUMENT_TEMPLATES.md) erläutern diese Abläufe.

<details>
<summary>Vorhandene Oberflächenabbildungen</summary>

![Export-Schaltflächen](../images/23-export-buttons.png)

![Export-Tab — Gesamtansicht](../images/33-export-tab.png)

![Export-Tab mit Report-Buttons](../images/23-export-buttons.png)

</details>

<a id="guide-section-11"></a>
## 11. Suche

<a id="suchmodi"></a>
<a id="verwendung-des-suchpanels"></a>
<a id="embedding-status"></a>
<a id="ähnliche-knoten-finden"></a>

Mit der Suche finden Sie Katalogeinträge unabhängig von einer vollständigen Anforderungsanalyse. Volltextsuche durchsucht indexierten Text. Semantische Suche verwendet verfügbare lokale Embeddings. Hybridsuche verbindet Rangfolgen; graphsemantische Suche berücksichtigt Graphkontext. Eine Rangfolge ist keine Freigabe oder Erfüllungszusage.

Wählen Sie einen verfügbaren Modus, geben Sie die Suche ein und prüfen Sie Kennung, Bezeichnung und Kontext. **Ähnliche finden** beginnt bei einem vorhandenen Knoten. Prüfen Sie bei fehlenden semantischen Treffern Modell-/Indexstatus; ein verfügbarer Textindex beweist keinen fertigen Embedding-Index. Vorbereitung kann je nach Betreiberkonfiguration Ressourcen oder Downloads benötigen.

Vergleichen Sie passende Fälle und Fachbegriffe in den benötigten Sprachen. Übersetzte Oberflächen belegen keine gleichwertige Suchqualität. Siehe [API-Referenz](API_REFERENCE.md), [KI-Anbieter](AI_PROVIDERS.md) und [KI-Transparenz](AI_TRANSPARENCY.md).

<details>
<summary>Vorhandene Oberflächenabbildungen</summary>

![Volltextsuche-Ergebnisse](../images/29-search-fulltext.png)

![Semantische Suche-Ergebnisse](../images/30-search-semantic.png)

![Hybridsuche-Ergebnisse](../images/31-search-hybrid.png)

![Graph-Suche-Ergebnisse](../images/32-search-graph.png)

</details>

<a id="guide-section-12"></a>
## 11a. Qualitäts-Dashboard

<a id="zusammenfassende-metriken"></a>
<a id="nach-relationstyp"></a>
<a id="am-häufigsten-abgelehnt"></a>

Im Qualitäts-Dashboard betrachten Sie Vorschlagszahlen, Prüfentscheidungen, Verteilungen nach Beziehungstyp und häufig abgelehnte Kandidaten. Aktualisieren Sie nach Entscheidungen bei Bedarf. Diese Angaben beschreiben aufgezeichnete Vorschläge und Nutzerentscheidungen, keinen unabhängigen Nachweis fachlicher Genauigkeit, Fairness oder vollständiger Architekturabdeckung.

Untersuchen Sie auffällige Ablehnungsmuster anhand ihrer Nachweise und Prompts. Verbessern Sie Kennzahlen nicht durch Ausblenden fehlgeschlagener oder offener Fälle. [API-Referenz](API_REFERENCE.md) und [KI-Transparenz](AI_TRANSPARENCY.md) beschreiben Operationen und Bewertungsgrenzen.

<a id="guide-section-13"></a>
## 11b. Relations-Browser

<a id="relationen-durchsuchen"></a>
<a id="eine-relation-erstellen"></a>
<a id="eine-relation-löschen"></a>
<a id="anforderungs-auswirkungsanalyse"></a>

Im Relations-Browser prüfen Sie vorhandene typisierte Quell-/Zielverbindungen und deren Kontext. Erstellen und Löschen erfordern passende Rechte und beeinflussen spätere Graphabfragen. Kontrollieren Sie beide Endpunkte und den Typ; eine sichtbare Bezeichnung ist keine stabile Identität.

Untersuchen Sie Abhängigkeiten vor dem Löschen oder Ersetzen und bewahren Sie erforderliche Herkunftsnachweise. Die [API-Referenz](API_REFERENCE.md) enthält Parameter und Rollenanforderungen. Eine im gefilterten Ergebnis fehlende Zeile beweist nicht, dass im gesamten Repository keine Beziehung existiert.

<a id="guide-section-14"></a>
## 11c. Anforderungsabdeckung

<a id="öffnen-des-panels"></a>
<a id="zusammenfassende-metriken-1"></a>
<a id="am-häufigsten-abgedeckte-knoten"></a>
<a id="lücken-kandidaten"></a>
<a id="aufzeichnung-einer-analyse"></a>

Die Anforderungsabdeckung verbindet gespeicherte Anforderungen mit Katalogelementen. Prüfen Sie Kennzahlen, abgedeckte Knoten und Lückenkandidaten und öffnen Sie die beitragende Anforderung samt Analyse. Erfassen Sie nur das beabsichtigte Ergebnis mit seinem Umfang; eine Teilanalyse darf keine Vollständigkeitszusage erzeugen.

Abdeckung bezeichnet eine aufgezeichnete Zuordnung. Sie beweist weder Implementierung, Beschaffung, Rechtskonformität noch Verfügbarkeit zum Zieltermin. Der [Portfolio-Leitfaden](PROJECT_REQUIREMENT_PORTFOLIO.md) erläutert Zuordnungen, Produkte, Entscheidungen und Matrizen.

<details>
<summary>Vorhandene Oberflächenabbildungen</summary>

<img src="../images/26-coverage-dashboard-empty.png" alt="Abdeckungs-Dashboard — leerer Zustand" width="600">

<img src="../images/27-coverage-dashboard-data.png" alt="Abdeckungs-Dashboard — nach Aufzeichnung einer Analyse" width="600">

</details>

<a id="guide-section-15"></a>
## 11d. Architektur-Lückenanalyse

<a id="was-sie-bewirkt"></a>
<a id="verwendung-der-api"></a>
<a id="interpretation-der-ergebnisse"></a>

Die Lückenanalyse untersucht fehlende erwartete Beziehungen und Ergänzungskandidaten anhand konfigurierter Regeln und verfügbarer Nachweise. Unterscheiden Sie eine fehlende Graphkante von einer bestätigten fehlenden Fähigkeit oder einem fehlenden Produkt. Unbewertete Zweige und unvollständige Relationensuche begründen keine negative Feststellung.

Prüfen Sie bei jedem Kandidaten Quelle, Umfang und Begründung vor einer Änderung. Die [API-Referenz](API_REFERENCE.md) beschreibt die dedizierten Operationen; daraus folgt nicht, dass jede einen eigenen GUI-Schalter besitzt. Das Portfolio dient gespeicherten Lösungs-/Produktentscheidungen.

<a id="guide-section-16"></a>
## 11e. Architektur-Empfehlung

<a id="was-sie-bewirkt-1"></a>
<a id="verwendung-der-api-1"></a>
<a id="interpretation-der-ergebnisse-1"></a>

Empfehlungen verbinden Anforderungskontext, ausgewählte Elemente, verfügbare Abhängigkeiten und Lückenkandidaten. Sie sind prüfbare Vorschläge, keine automatisch genehmigten Implementierungspläne. Klären Sie vor Übernahme einer Menge, welche Elemente gemeinsam erforderlich, optional oder alternativ sind.

Starten Sie den entsprechenden Copilot- oder unterstützten API-Ablauf und prüfen Sie Status und Nachweise. Grenzen des Laufs begrenzen nachgelagerte Empfehlungen. Siehe [Copilot und Autopilot](COPILOT_AUTOPILOT.md) und [API-Referenz](API_REFERENCE.md).

<a id="guide-section-17"></a>
## 11f. Erkennung von Architekturmustern

<a id="vordefinierte-muster"></a>
<a id="verwendung-der-api-2"></a>
<a id="interpretation-der-ergebnisse-2"></a>

Mustererkennung prüft vordefinierte Ketten von Beziehungstypen gegen den vorhandenen Graphen. Eine vollständige strukturelle Kette beweist nicht die Erfüllung der Ausgangsanforderung. Eine unvollständige Kette kann fehlende Nachweise statt eines betrieblichen Mangels bedeuten.

Prüfen Sie Knoten, Richtungen und fehlende Verbindungen, bevor Sie daraus eine Empfehlung ableiten. Verwenden Sie die dokumentierten Knoten-/Bewertungsoperationen der [API-Referenz](API_REFERENCE.md). Erfinden Sie keine Taxonomiekennungen und unterstellen Sie nicht, dass jede Nummernfolge existiert.

<a id="guide-section-18"></a>
## 11g. Architektur-DSL

<a id="warum-dsl"></a>
<a id="dsl-formatübersicht"></a>
<a id="blocktypen"></a>
<a id="elementtypen"></a>
<a id="beziehungstypen"></a>
<a id="quellartefakt"></a>
<a id="quellversion"></a>
<a id="quellfragment"></a>
<a id="anforderungs-quell-verknüpfung"></a>
<a id="erweiterungsattribute"></a>
<a id="serialisierungsgarantien"></a>
<a id="dsl-editor-panel"></a>
<a id="syntaxhervorhebung"></a>
<a id="autovervollständigung"></a>
<a id="versionskontrolle"></a>
<a id="materialisierung"></a>
<a id="hypothesen"></a>
<a id="commit-verlauf-durchsuchen"></a>

Die textuelle Architektur-DSL speichert Elemente, typisierte Beziehungen, Anforderungszuordnungen, Ansichten und Nachweise mit stabilen Identitäten. Quellreferenzen und übernommene Architekturänderungen bleiben von importierten Katalogdaten unterscheidbar. Lokale Erweiterungsattribute erhalten durch Serialisierung keinen offiziellen oder standardisierten Status.

Der DSL-Texteditor besitzt seinen eigenen Entwurfs-/Validierungsablauf. Der private Architektureditor bietet dagegen synchronisierte Formulare, Graph-/Listenansichten und eine schreibgeschützte DSL-Projektion. Das sind verschiedene Bearbeitungsoberflächen. Prüfen Sie Vorschau und Validierung und übernehmen Sie ausdrücklich. Eine gespeicherte Editoroperation erzeugt nicht automatisch einen Git-Checkpoint.

<a id="quell-provenienz-in-der-dsl"></a>
Quellprovenienz verbindet importierten Inhalt mit Dokument, Version und Fragment. Erhalten Sie diese Identitäten und die Prüfzuständigkeit bei Zuordnungsänderungen. Erfinden Sie keine Katalogeinträge für Beispiele und ersetzen Sie kanonische Bezeichnungen nicht durch bequeme Aliasse.

Materialisierung leitet Arbeitsprojektionen aus ausdrücklich gewählten Modellständen ab; Hypothesen bleiben bis zur jeweiligen Prüfung vorläufig. Commit-Suche erfasst versionierte Nachweise, nicht sämtliche flüchtigen Zustände. [Architektureditor](ARCHITECTURE_EDITOR.md), [Git-Integration](GIT_INTEGRATION.md), [Dokumentimport](DOCUMENT_IMPORT.md) und [API-Referenz](API_REFERENCE.md) sind die gepflegten Detailreferenzen statt einer zweiten Grammatik-/API-Kopie in diesem Handbuch.

<details>
<summary>Vorhandene Oberflächenabbildungen</summary>

![DSL-Editor-Panel](../images/34-dsl-editor-panel.png)

![DSL-Editor mit Beziehungen](../images/40-dsl-editor-with-relations.png)

</details>

<a id="guide-section-19"></a>
<a id="12-versions-tab"></a>
## 12. Versionen-Tab

<a id="branch-auswahl"></a>
<a id="verlaufszeitachse"></a>
<a id="letzte-änderung-rückgängig-machen"></a>
<a id="eine-benannte-version-speichern"></a>
<a id="zeitachse-aktualisieren"></a>
<a id="varianten-browser"></a>
<a id="varianten-löschen"></a>
<a id="zurückkopieren-nur-lese-kontexte"></a>
<a id="merge-vorschau"></a>
<a id="cherry-pick-vorschau"></a>
<a id="merge-konflikte-lösen"></a>
<a id="synchronisation-mit-dem-gemeinsamen-repository"></a>
<a id="divergierten-zustand-auflösen"></a>
<a id="benachrichtigungen-über-operationsergebnisse"></a>
<a id="arbeitsbereich-benutzer-badge"></a>

Prüfen Sie Branch-Auswahl und Arbeitsbereich vor Versionsaktionen. Die Zeitachse zeigt Git-Checkpoints mit Autor, Nachricht und Commit-Kennung, nicht das vollständige semantische Operationsjournal. Betrachten/vergleichen Sie exakte Versionen oder erstellen Sie für einen stabilen übernommenen Arbeitsstand einen benannten Checkpoint.

Verwenden Sie Varianten für alternative Entwürfe. Prüfen Sie Merge-/Cherry-Pick-Vorschauen, hinzugefügte/entfernte/geänderte Elemente und Konflikte vor Bestätigung. Zurückkopieren überträgt ausgewählten Inhalt aus einem schreibgeschützten in den Arbeitskontext und übernimmt nicht automatisch das ganze historische Modell. Kontrollieren Sie vor dem Löschen einer Variante Identität und erhaltene Historie.

Wiederherstellen erzeugt eine neue Version aus ausgewähltem früherem Inhalt; Revert nimmt die Änderung eines gewählten Commits zurück. **Branch-Undo ist anders:** Es setzt den Branch auf den Elterncommit und entfernt den jüngsten Commit aus dessen sichtbarer Historie. Es ist nicht die protokollierte Gegenoperation im Architektureditor. Prüfen Sie Kontext und Bestätigung. Keine dieser Aktionen ersetzt ein Installationsbackup.

Synchronisieren holt gemeinsame Änderungen; Veröffentlichen überträgt geprüfte lokale Änderungen. Beachten Sie voraus/zurück/divergiert und behandeln Sie Konflikte oder abgelehnte Pushes ausdrücklich. Eine Erfolgsmeldung genehmigt keine anderen Entwürfe. [Arbeitsbereich und Versionierung](WORKSPACE_VERSIONING.md) erläutert Vorschauen, Konfliktbehandlung und Wiederaufnahme.

<details>
<summary>Vorhandene Oberflächenabbildungen</summary>

![Versionen-Tab — Verlaufszeitachse](../images/41-versions-tab-history.png)

![Versionen-Tab — Version speichern](../images/42-versions-tab-save.png)

![Varianten-Browser-Tab](../images/47-variants-browser-tab.png)

![Modal zur Variantenerstellung](../images/46-variant-creation-modal.png)

![Schaltfläche Zurückkopieren](../images/49-copy-back-button.png)

![Merge-Konfliktlösungs-Modal](../images/52-merge-conflict-modal.png)

![Cherry-Pick-Konfliktlösung](../images/54-cherry-pick-conflict-modal.png)

![Cherry-Pick-Vorschau-Dialog](../images/62-cherry-pick-preview-modal.png)

![Erfolgreicher Cherry-Pick](../images/59-cherry-pick-success-toast.png)

![Arbeitsbereich-Benutzer-Badge](../images/45-workspace-user-badge.png)

</details>

<a id="guide-section-20"></a>
## 13. Git-Status und Kontextleiste

<a id="git-statusleiste"></a>
<a id="kontext-navigationsleiste"></a>

Git-Status und Kontextleiste bezeichnen Repository, Arbeitsbereich, Branch, Checkpoint und Arbeitsmodus. Schreibgeschützte/historische Kontexte werden nicht dadurch bearbeitbar, dass anderswo ein Editor existiert. Prüfen Sie Herkunft und Auswahl vor Kopieren, Vergleichen, Verzweigen oder Veröffentlichen.

Zurück navigiert durch Arbeitskontexte, Ursprung zum ursprünglichen Kontext, Vergleichen wählt Stände für einen Diff. Das ist weder identisch zur Browserhistorie noch zum Rückgängigmachen einer gespeicherten Operation. Ausstehende Bearbeitungen, veraltete Projektionen und unveröffentlichte Checkpoints sind verschiedene Zustände.

Verwerfen Sie keinen Entwurf nur zum Entfernen einer optischen Warnung, ohne ihren Inhalt zu prüfen. [Repository-Topologie](REPOSITORY_TOPOLOGY.md), [Arbeitsbereich/Versionierung](WORKSPACE_VERSIONING.md) und [Architektureditor](ARCHITECTURE_EDITOR.md) erläutern Zuständigkeit und Wiederherstellung.

<details>
<summary>Vorhandene Oberflächenabbildungen</summary>

![Git-Statusleiste](../images/43-git-status-bar.png)

![Kontext-Navigationsleiste](../images/44-context-bar.png)

</details>

<a id="guide-section-21"></a>
## 14. Administration

<a id="ki-statusanzeige----in-der-navigationsleiste"></a>
<a id="admin-modus-freischalten--schaltfläche--passwort-modal"></a>
<a id="llm-kommunikationsprotokoll"></a>
<a id="llm-diagnose-panel"></a>
<a id="prompt-vorlagen-editor"></a>
<a id="einstellungen-tab-"></a>

Verwenden Sie ein Administratorkonto nur für berechtigte Konfigurationsaufgaben. Ein Sperren-/Entsperren-Anzeigeelement erteilt keine serverseitigen Rechte. Lokale Benutzerverwaltung ist optional und profilabhängig; für Keycloak-Konten und -Sitzungen gilt eine andere Zuständigkeit. Das Deaktivieren eines lokalen Kontos löscht nicht dessen historische Daten und garantiert nicht die sofortige Beendigung jeder bestehenden Sitzung.

Prüfen Sie KI-Status und Diagnosen, um Konfiguration, Verbindung, Anbieterfehler und Modellfähigkeit auseinanderzuhalten. **Verbindung testen** kann den Anbieter kontaktieren. Kommunikationsprotokolle können Anforderungen, Prompts und Antworten enthalten. Behandeln Sie diese vertraulich und kopieren Sie keine Schlüssel oder ungeprüften Geschäftsdaten in Fehlerberichte.

Bearbeiten Sie Prompt-Vorlagen über den berechtigten Editor, behalten Sie eine Begründung und prüfen Sie einen geeigneten Fall. Eine Promptänderung ändert nicht stillschweigend den Antwortparser und belegt keine verbesserte Sprachqualität.

Einstellungen bieten die unterstützten Laufzeitwerte für LLM, Grenzen und Diagramme samt dokumentiertem Geltungsbereich und Historie. Historische Repository-/Standardbranch-/Autosave-Schlüssel sind nicht automatisch aktive Bedienelemente; Branch und Remotes gehören zum Repositorykontext. Kontrollieren Sie Wert und Prüfprotokoll, statt für jede Deployment-Einstellung sofortige Wirkung ohne Neustart anzunehmen. Details: [Einstellungen](PREFERENCES.md), [Konfigurationsreferenz](CONFIGURATION_REFERENCE.md), [KI-Anbieter](AI_PROVIDERS.md) und [Datenschutz](DATA_PROTECTION.md).

<details>
<summary>Vorhandene Oberflächenabbildungen</summary>

![Admin-Schloss-Schaltfläche in der Navigationsleiste](../images/14-navbar-admin-lock.png)

![LLM-Diagnose-Panel](../images/24-llm-diagnostics.png)

![Prompt-Vorlagen-Editor](../images/25-prompt-template-editor.png)

</details>

<a id="guide-section-22"></a>
## 15. Referenz der Beziehungstypen


Beziehungen besitzen ausdrücklich Quelle, Typ und Ziel. Bedeutung und zulässige Wurzelkombinationen folgen Taxonomys Modell und Kompatibilitätsregeln. Bezeichnungen behaupten keine Zertifizierung jeder Beziehung gegen ein externes Framework.

| Typ | Lesehilfe; tatsächliche Richtung und Kontext prüfen |
|---|---|
| REALIZES / FULFILLS | Beitrag mit Fähigkeit oder Bedarf in der erlaubten Richtung verbinden. |
| SUPPORTS / USES | Unterstützung beziehungsweise Nutzung zwischen erlaubten Endpunkten. |
| CONSUMES / PRODUCES | Lesen/Verbrauchen von Information vom Erzeugen unterscheiden. |
| ASSIGNED_TO | Akteur oder Rolle im modellierten Kontext zuweisen. |
| DEPENDS_ON / REQUIRES | Begründete Abhängigkeit oder Anforderung, nicht bloß aus einem Score abgeleitet. |
| COMMUNICATES_WITH | Die aufgezeichnete Kommunikationsbeziehung. |
| CONTAINS | Strukturelle Enthaltenseinsbeziehung, keine beliebige Katalogklassifikation. |
| RELATED_TO | Allgemeine Verbindung, kein Ersatz für eine fehlende spezifische Begründung. |

Lesen Sie beide Endpunkte und die Erklärung. Das Umdrehen einer gerichteten Beziehung kann die Bedeutung ändern. [Entscheidungspipeline](DECISION_PIPELINE.md), [API-Referenz](API_REFERENCE.md) und [Bewertungs-/Herkunftsregeln](TAXONOMY_SCORING.md) beschreiben die Implementierungsgrenzen.

<a id="guide-section-23"></a>
## 15a. Dokumentenimport & Quellenherkunft

<a id="import-modi"></a>
<a id="dokumente-importieren"></a>
<a id="kandidaten-extrahieren-standard"></a>
<a id="ki-gestützte-extraktion"></a>
<a id="direktes-architektur-mapping"></a>
<a id="extrahierte-kandidaten-überprüfen"></a>
<a id="quellenherkunft"></a>

Öffnen Sie Dokumentimport oder den Importablauf eines gespeicherten Projekts. Wählen Sie PDF/DOCX, Quellmetadaten und einen unterstützten Extraktionsmodus. Regelbasierte Kandidatenextraktion erfordert keine generative Synthese. KI-gestützte Extraktion und direkte Architekturzuordnung verwenden den konfigurierten Anbieter und ihre Grenzen.

Prüfen Sie extrahierte Passagen vor Bestätigung. Kopfzeilen, Textbausteine, unvollständige Fragmente und unklare Verpflichtungen dürfen nicht stillschweigend zu akzeptierten Anforderungen werden. Wählen/bestätigen Sie die beabsichtigten Kandidaten und analysieren Sie daraus entstehende Anforderungen im passenden gespeicherten oder Ad-hoc-Ablauf.

Prüfen Sie Dokumentidentität, Version und Fragmentverweise. Originalquellen, Kandidaten und akzeptierte Anforderungen besitzen unterschiedliche Lebenszyklen. Beachten Sie Upload-, Seiten-, Archiv- und Textgrenzen und untersuchen Sie Ablehnungen, statt Validierung zu umgehen. [Dokumentimport](DOCUMENT_IMPORT.md), [Importgrenzen](DOCUMENT_IMPORT_LIMITS.md) und [Portfolio](PROJECT_REQUIREMENT_PORTFOLIO.md) erläutern Prüfung und Herkunft.

<a id="guide-section-24"></a>
## 16. Tipps und Best Practices

<a id="effektive-anforderungen-formulieren"></a>
<a id="ergebnisse-interpretieren"></a>
<a id="mit-vorschlägen-arbeiten"></a>
<a id="exportieren"></a>

Behalten Sie Originalanforderung und Bedingungen bei der Vorschlagsprüfung sichtbar. Prüfen Sie fachlich, ob ein kleiner Beitrag unverzichtbar ist, und unterscheiden Sie Alternativen von gemeinsam benötigten Elementen. Prüfen Sie unvollständige Abdeckung vor einer Lückenfeststellung.

Arbeiten Sie mit der richtigen gespeicherten Quelle und im richtigen Arbeitsbereich. Verwenden Sie Varianten und Checkpoints für sinnvolle Prüfgrenzen. Browserentwurf, gespeicherter Vorschlag, Git-Version und Analysesnapshot sind keine Synonyme. Erhalten Sie menschliche Entscheidungen und untersuchen Sie Konflikte, statt das Modell so oft zu fragen, bis es zustimmt.

Wählen Sie Exporte nach dokumentierter Unterstützung, nicht nach Endung. Lokaler Modellbetrieb, Qualitätskennzahlen und technische Testerfolge sind getrennte Nachweise. Kein Diagramm, Score oder Haken beweist automatisch Rechtskonformität, Produktionsreife oder Überlegenheit gegenüber einem anderen Werkzeug.

<a id="guide-section-25"></a>
## 17. Glossar


| Begriff | Bedeutung in diesem Ablauf |
|---|---|
| Katalogknoten | Referenzeintrag, nicht automatisch ein konkret installiertes Objekt. |
| Bewertung | Wert nach Relevanz-, Verteilungs-, Eignungs- oder Ähnlichkeitsregeln; keine kalibrierte Sicherheit. |
| Anker / Wirkungsschwerpunkt | Durch Ableitungsregeln ausgewähltes Element; Nachweise prüfen. |
| Beziehung / Hypothese | Typisierte Verbindung oder vorläufiger Kandidat mit eigener Herkunft und Prüfung. |
| Lücke / Abdeckung | Fehlende Verbindung beziehungsweise aufgezeichnete Anforderungszuordnung im begrenzten Kontext. |
| Vorschlag / Entscheidung | Generierter/bearbeiteter Inhalt gegenüber ausdrücklich dokumentierter Quell-/Nutzerklärung. |
| Anforderungsversion | Identifizierter gespeicherter Text-/Herkunftsstand. |
| Snapshot | Erhaltenes Analyseergebnis zu seiner Quelle, nicht der neueste Arbeitsbereich. |
| Arbeitsrevision | Dauerhafter bearbeitbarer Stand mit semantischer Operationshistorie. |
| Checkpoint / Variante | Stabile Git-Version beziehungsweise benannter Zweig für Alternativen. |
| Materialisierung / Projektion | Abgeleitete Arbeits-/Indexrepräsentation eines maßgeblichen gespeicherten Stands. |
| Synchronisieren / Veröffentlichen | Gemeinsame Änderungen holen oder geprüfte lokale Änderungen mit Konfliktbehandlung übertragen. |
| Veraltet | Beschreibt nicht mehr aktuelle Eingabe/Kontext, bleibt aber historischer Nachweis. |
| Lokale Embeddings | Lokale Vektorsuche/Bewertung, kein vollständiges generatives LLM. |
| C3 / COI | Command, Control and Communications / Community of Interest des Katalogs. |

[Konzepte](CONCEPTS.md) enthält weitere Begriffe, [Git-Integration](GIT_INTEGRATION.md) die Versionssemantik.

<a id="guide-section-26"></a>
## 18. Fehlerbehebung

<a id="die-schaltfläche-analyze-with-ai-ist-deaktiviert-oder-ausgegraut"></a>
<a id="die-analyse-wird-ausgeführt-aber-alle-bewertungen-sind-0-"></a>
<a id="export-schaltflächen-sind-nicht-sichtbar"></a>
<a id="die-visio--oder-archimate-exportdatei-ist-leer-oder-enthält-keine-elemente"></a>
<a id="der-taxonomiebaum-wird-nicht-geladen"></a>
<a id="bewertungen-einer-früheren-analyse-werden-noch-angezeigt-nachdem-die-anforderung-geändert-wurde"></a>
<a id="die-admin-panels-llm-diagnose-prompt-editor-usw-sind-nicht-sichtbar"></a>
<a id="der-graph-explorer-zeigt-ein-failure-impact-ergebnis--ist-das-ein-fehler"></a>

| Symptom | Prüfung und Vorgehen |
|---|---|
| Analyse deaktiviert | KI-Bereitschaft, Rolle und Konfiguration prüfen; lokale Embeddings aktivieren nicht jede generative Funktion. |
| Nullwerte oder fehlende Bewertungen | Fehler, Umfang und Abdeckung prüfen, bevor „kein Treffer“ angenommen wird. Nicht bloß den Text durch Katalogwörter ersetzen. |
| Lauf hält an | Ergebniszustand und erhaltene Nachweise prüfen; angebotene Wiederholung/Fortsetzung/Abbruch nutzen. |
| Export fehlt oder ist leer | Quelle, vorhandenes Ergebnis, Ableitungsmodus und Endpunkt prüfen, bevor kostspielige Analyse wiederholt wird. |
| Anforderung geändert | Historische Nachweise trennen und bei Bedarf neu analysieren. |
| Taxonomie/Graph lädt nicht | Verbindung und sichtbaren Fehler prüfen, Entwürfe erhalten, reproduzierbare Schritte melden. |
| Administration fehlt | Rolle und aktives Profil mit Betreiber prüfen; Frontend-Entsperren ersetzt keine Rechte. |
| Merge-/Revisionskonflikt | Aktualisieren, vergleichen und neuen Stand vor erneutem Senden ausdrücklich prüfen. |
| Ausfallauswirkung sichtbar | Beabsichtigte Graphanalyse, keine Ausnahme; Kanten und Grenzen beschränken ihre Vollständigkeit. |
| Hilfelink funktioniert nicht | Dokument, Sprache und Ziel notieren; Überschriftenziele, verfasste Links und Browserrouting sind verschiedene Fehlerquellen. |

Bewahren Sie exakte Version/Kontext und relevante Diagnose ohne Geheimnisse oder nicht freigegebene personenbezogene Daten. Datenbanklöschen, Git-Umschreiben oder Wegaktualisieren ungespeicherter Arbeit sind keine allgemeinen Reparaturen. Das [Betriebshandbuch](OPERATIONS_GUIDE.md) beschreibt Betreiberabläufe.

<details>
<summary>Vorhandene Oberflächenabbildungen</summary>

![Graph Explorer Ausfallauswirkung](../images/22-graph-explorer-failure.png)

</details>

<a id="guide-section-27"></a>
## Gespeicherte Neuformulierungsangebote und Entscheidungsfragen


Öffnen Sie eine gespeicherte Anforderung mit exakter Quellversion und Analysesnapshot. Erstellen Sie ein separates Angebot und warten Sie auf das aufgezeichnete Syntheseergebnis. Initialer Entwurf oder abgeschlossener Lauf bedeutet keine Genehmigung. Original, Vorschlag und Fragen bleiben getrennt; schmale Ansichten verwenden Registerkarten.

Antworten Sie mit dem angebotenen Typ, verwenden Sie Andere nur mit Erklärung oder stellen Sie ausdrücklich zurück beziehungsweise markieren Sie nicht zutreffend. Prüfen Sie Folgefragen, Quellenkonflikte und bestehende Entscheidungen. Speichern Sie vor dem Angebotswechsel. Regeneration muss zwischenzeitliche manuelle Änderungen als prüfbare Kandidaten erhalten, nicht still überschreiben.

Nutzen Sie **Übernahme prüfen…**, kontrollieren Sie gespeicherten Text und offene Befunde, bestätigen Sie Warnungen und geben Sie vor Übernahme eine Begründung ein. Das Schließen der Vorschau übernimmt nichts. Die Übernahme aktiviert einen Anforderungsentwurf und markiert Analysebedarf; starten Sie die Folgeanalyse gesondert. Ein späteres Angebot erhält geerbte Entscheidungen mit ihrer Herkunft.

Exportieren Sie gespeicherte Revision oder Übernahmebeleg als JSON, Markdown, HTML oder Word. Ungespeicherte Eingaben fehlen absichtlich; historische Quelle und Architektur werden erhalten statt gegen den heutigen Katalog aufgelöst. Nutzen Sie einen separaten expliziten Checkpoint für portable Projekthistorie. Siehe [Portfolio-Ablauf](PROJECT_REQUIREMENT_PORTFOLIO.md) und [Neuformulierungsvertrag](../features/requirement-reformulation.md).
