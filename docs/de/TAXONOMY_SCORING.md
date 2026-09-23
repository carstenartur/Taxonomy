# Gruppierung und Bewertung der Teiltaxonomien

> **Implementierungsstand:** 23. September 2026, Quellstand `e73dcfe43964b68aaa6bc7d967471fe209da9a8a` aus PR #1118. Diese Seite beschreibt den dort lesbaren Code, keine bereits ausgerollte Installation. Die gesonderten Parseränderungen aus #1113 sind in dieser Ausgangsbasis nicht enthalten. Abschnitte mit **Zielbild** sind noch keine implementierten Fähigkeiten. Eine Dokumentationsänderung ändert weder Scores noch Katalogdaten.

[English](../en/TAXONOMY_SCORING.md) · [Kontext und Navigation](../dev/hierarchy-context-and-navigation.md) · [Technischer Score-Datenvertrag](../dev/ANALYSIS_SCORE_SEMANTICS.md)

## Eine gemeinsame Ausführung, unterschiedliche fachliche Fragen

Die Teiltaxonomien haben unterschiedliche Gegenstände, aber nicht acht verschiedene implementierte Rechenverfahren. Im regulären LLM-Pfad verwenden Kategorien denselben Elternbudget-Parser. Die Prompts unterscheiden den fachlichen Blickwinkel. Nur ausdrücklich durch Metadaten als `PRODUCT` klassifizierte Einträge erhalten die unabhängige Produktbewertung. Ein Blatt ist nicht allein deshalb ein Produkt; eine User Application verwendet nicht automatisch die IP-Produktformel.

| Seite | Fachliche Frage | Heutiger regulärer Bewertungsweg |
|---|---|---|
| [BP – Business Processes](taxonomies/BP.md) | Welche Tätigkeiten und Abläufe werden benötigt? | Kategorie-/Elternbudget |
| [BR – Business Roles](taxonomies/BR.md) | Welche Rollen und Verantwortlichkeiten sind betroffen? | Kategorie-/Elternbudget |
| [CP – Capabilities](taxonomies/CP.md) | Welche Fähigkeiten werden benötigt? | Kategorie-/Elternbudget |
| [CI – COI Services](taxonomies/CI.md) | Welche gemeinschafts- beziehungsweise fachgebietsspezifischen Dienste werden benötigt? | Kategorie-/Elternbudget |
| [CO – Communications Services](taxonomies/CO.md) | Welche Kommunikations- und Übertragungsdienste werden benötigt? | Kategorie-/Elternbudget |
| [CR – Core Services](taxonomies/CR.md) | Welche grundlegenden IT-Dienste werden benötigt? | Kategorie-/Elternbudget |
| [IP – Information Products](taxonomies/IP.md) | Welche Informationen werden erzeugt, gelesen oder verändert? | Familien: Kategorie; `PRODUCT`: unabhängige Eignung |
| [UA – User Applications](taxonomies/UA.md) | Welche benutzerseitigen Anwendungsfunktionen werden benötigt? | Kategorie-/Elternbudget |

## Was bedeutet eine Gruppierung?

Eine Quell-Unterordnung, eine ergänzte fachliche Klassifikation, eine Navigationsgruppe und eine Architekturbeziehung sind nicht austauschbar. Die bestehende Persistenz hat einen primären `parentCode`. Das IP-Overlay ergänzt diesen sowie optionale `secondaryClassificationCodes`. Diese Metadaten sind noch kein vollständiger, mehrdimensionaler Suchbaum.

PR #1118 trennt den sichtbaren Navigationspfad vom Bewertungskontext: Originale Vorfahrenbeschreibungen bleiben erhalten; an einer Overlay-Elternzuordnung endet die automatische Vererbung. Die Quellzugehörigkeit ist damit nachvollziehbar, aber nicht als mathematisch oder fachlich unfehlbar bewiesen. Ein fehlender, zyklischer oder wurzelübergreifender semantischer Pfad wird nicht still repariert. Ein `reviewRequired=false` aus deterministischen Zuordnungsregeln ersetzt keine fachliche Prüfung.

**Zielbild: mehrere getrennte Klassifikationsachsen.** Funktion, fachlicher Gegenstand und Artefakt-/Produkttyp können unabhängige Zugänge sein. Ein E-Mail-Client kann über „Funktion → Kommunikation“ und über „Anwendungstyp → Client“ erreichbar sein. Beide Wege referenzieren dieselbe Originalidentität. Das Beispiel bezeichnet keine zusätzlichen C3-Katalogcodes. „Kommunikation“ als Facette ist nicht automatisch die Teiltaxonomie CO; ein Client ist auch nicht allein wegen des Wortes Produkt ein IP-Informationsprodukt.

Eine künftige Mitgliedschaft muss mindestens Facette, Gruppenidentität, Originalidentität, Beziehungsart, Herkunft, Geltungsbereich und Prüfstatus unterscheiden. Bei echten, gemeinsam geltenden Oberbegriffen gelten beide Einschränkungen. Alternative Navigationswege werden dagegen nicht zu einer erfundenen UND-Bedeutung vermischt. Doppelte Zugänge dürfen weder Knoten noch Abdeckung noch Modellabfragen vervielfachen. Unterschiedliche Anforderungsbeiträge müssen dennoch als getrennte Belege erhalten bleiben.

## Heutige Kategorie-Rechnung: ein Gewichtungsbudget

Der Prompt fordert für die angebotenen Kategorien ganzzahlige Scores und Begründungen, deren Summe dem Elternwert `P` entspricht. Für gelesene nichtnegative Werte `r_i` mit positiver Summe `S` normalisiert `LlmResponseParser` bei Abweichungen:

```text
w_i = P * r_i / S
Ganzzahlen durch Abrunden und Vergabe der verbleibenden Einheiten
an die größten Nachkommareste (Largest-Remainder-Verfahren).
```

Bei `S = P` werden die Werte unverändert übernommen. Sind alle Werte null, bleiben sie null; es werden nicht künstlich positive Werte erzeugt. Beispiel: `P = 60`, gelieferte Kinderwerte `80, 40` ergeben `40, 20`. Der gelieferte Summenwert über dem Budget erzeugt bereits einen `TaxonomyDiscrepancy`-Eintrag. Dieser Summenbefund ist nicht die noch ausstehende Prüfung semantischer Kind-/Vater-Konsistenz.

**Rationale und Grenze:** Die Normalisierung realisiert eine hierarchische Gewichtungsverteilung. Sie beweist weder einen Erfüllungsgrad noch Notwendigkeit, Wahrscheinlichkeit, Kostenanteil oder gegenseitigen Ausschluss. Mehrere Prozesse, Rollen, Fähigkeiten, Dienste oder Anwendungen können gleichzeitig erforderlich sein. Auch ein Kind mit kleinem Budgetanteil kann unverzichtbar sein. Mehr erforderliche Geschwister können allein rechnerisch die Anteile vorhandener Geschwister senken.

### Wurzel-Sonderfall: dokumentierte Abweichung von der Absicht

`analyzeAllTaxonomies` und `analyzeStreaming` bewerten jede Wurzel getrennt mit `P = 100`. Laut Kommentar soll das unabhängige Astrelevanz liefern. Der tatsächlich verwendete reguläre Kategorie-Parser normalisiert aber auch diesen Ein-Knoten-Satz: Eine Antwort `20` wird zu `100`; eine Antwort `0` bleibt `0`. Die Wurzeln werden zwar nicht miteinander auf 100 verteilt, der einzelne positive Modellwert bleibt dabei jedoch nicht erhalten. Das ist eine zu korrigierende Einschränkung, keine fachliche Empfehlung. Mockdaten können abweichende Wurzelwerte zeigen.

## Heutige IP-Produktrechnung: Eignung plus abgeleiteter Wert

Nur `analysisRole=PRODUCT` schaltet auf den Produktweg. Kandidaten werden nach Code sortiert, in Paketen von höchstens zehn bewertet und anhand der Originalanforderung unabhängig mit 0–100 und Begründung eingeschätzt. Die Paketgröße ist konfigurierbar. Die Mindestgrenze beträgt standardmäßig 50; gelieferte Werte darunter werden im strukturierten Ergebnis zu null. `49, 80, 90` ergeben bei Grenze 50 also `0, 80, 90`, nicht eine Verteilung auf 100.

Für generische nachgelagerte Verbraucher verwendet `AnalysisScoreSemantics` Version 1 weiterhin:

```text
effektive Relevanz = round(direkte Elternrelevanz * Produkteignung / 100)
Beispiel: Elternwert 40, Produkteignung 80 -> effektiver Wert 32.
```

Fehlt der bewertete direkte Elternwert, wird der effektive Wert null und eine Warnung erzeugt. Das ist ein technischer Ersatzwert, kein Nachweis fehlender Eignung. Die Produktbewertung fragt nach der gesamten Anforderung, nicht ausdrücklich nach einer bedingten Wahrscheinlichkeit. Die Multiplikation ist daher eine bestehende Gewichtungsheuristik, keine fachlich validierte Wahrscheinlichkeitsrechnung. Ein unpassender Elternknoten kann die weitere Bewertung verhindern oder den abgeleiteten Wert unbegründet verringern. #1118 ändert diese Formel nicht.

In gemischten Geschwistersätzen erhalten die Kategorien das Elternbudget und die Produkte jeweils unabhängige Eignungswerte. Diese Werte dürfen nicht gemeinsam zu einer vermeintlichen Erfüllungssumme addiert werden.

## Eignung ist nicht Bedarf

Zwei Werte von 90 können zwei gemeinsam notwendige Produkte, zwei alternative Realisierungen oder zwei nur allgemein passende Kandidaten betreffen. Der Score allein unterscheidet das nicht. Der konkrete Anforderungsbeitrag und die verifizierte Beziehung müssen getrennt begründen, was benötigt wird. Die zuschaltbare Beziehungssuche unterscheidet `REQUIRED`, `OPTIONAL` und `ALTERNATIVE`; ihre Navigations- und Prüfentscheidungen sind keine Prozentanteile. Auch eine alternativ klassifizierte Beziehung ersetzt noch keinen vollständigen Variantenentscheid auf Produktebene.

**Zielbild:** Astrelevanz bei gleichem Anforderungskontext und echter semantischer Unterordnung soll konsistent sein (`Kind <= Vater`). Ein Widerspruch wird als Befund mit Originalbelegen sichtbar und nicht durch stilles Kappen oder Multiplizieren verdeckt. Das erzwingt keine Summengleichheit zwischen Geschwistern. Eine rein organisatorische Umgruppierung darf weder die Eignung eines Originaleintrags noch dessen Bedarf verändern. Eine Suchpriorität einer Navigationsgruppe ist ein eigener Wert, nicht die Relevanz eines Architekturbausteins.

## Fehler, Abbruch und gespeicherte Werte richtig lesen

Die Basis von #1118 enthält im Kategorie-Parser noch fehlende-Antwort-zu-null-Ergänzungen und in Fehlerpfaden Nullplatzhalter. Daher muss eine Null zusammen mit Aufruffehler, Warnungen, Status und Rohantwort gelesen werden. #1113 bearbeitet Teile dieses Vertrags separat; seine Integration ist hier nicht vorausgesetzt. Eine durch Produkt-Mindestgrenze erzeugte Null ist ebenfalls keine ursprünglich vom Modell gelieferte Null.

`LlmCallDetail` und inkrementelle Score-Ereignisse transportieren Werte nach Parsernormalisierung beziehungsweise Produktschwelle. `AnalysisResult.rawScores` liegt vor der zusätzlichen Produkt-Elterngewichtung, aber nicht zwingend vor allen Transformationen der Modellantwort. Die vollständige Antwort im LLM-Protokoll ist für diese Unterscheidung maßgeblich. `scoreDetails` trennt Bewertungsart, gespeicherten Ausgangswert und abgeleitete Relevanz; `scoreSemanticsVersion`, Warnungen und eingefrorener Katalog gehören zur Interpretation eines Snapshots.

Der automatische Abstieg verfolgt im untersuchten Pfad positive Kategorieeinträge weiter; Produkte sind Endpunkte. Ein nicht besuchter Knoten ist damit nicht automatisch negativ bewertet. Prompt-, Aufruf- und Speichergrenzen sowie Fehler können Teilergebnisse erzeugen. Manuelle Werte, Mock-/Replaydaten und lokale Embedding-Ähnlichkeit sind keine austauschbaren Nachweise einer vom Modell begründeten Notwendigkeit. Lokale Embeddings umgehen die natürlichsprachlichen Scoring-Prompts; die hier beschriebene Kontextkorrektur ist kein Nachweis einer identischen lokalen Embedding-Auswertung. Promptüberschreibungen können die Frage verändern, nicht automatisch den Parservertrag.

## Änderungsauftrag und Prüfkriterien

Die koordinierte Weiterentwicklung in #1111 muss Wurzelrelevanz, Astrelevanz, verteiltes Gewicht, Produkteignung und Bedarf ausdrücklich trennen; den fehlerhaften Ein-Wurzel-Normalisierungseffekt korrigieren; Originalwerte und Transformationen versioniert erhalten; mehrdimensionale Zugänge anhand fachlicher Definitionen prüfen; und eine fragwürdige Elternzuordnung nicht zum einzigen Suchzugang machen.

Prüfbeispiele: Nur die Beschreibung eines BP-Vorfahren definiert den fachlichen Geltungsbereich. Zwei benötigte Produkte bleiben gleichzeitig auswählbar. Zwei Alternativen werden nicht automatisch beide übernommen. Ein Produkt über zwei Facetten bleibt ein Objekt. Eine reine Navigationsänderung verändert seine Bewertung nicht. Gleicher Maßstab mit Kind über Vater erzeugt einen erklärbaren Befund. Fehlende Antwort, echte Null und unterschwellige Antwort bleiben unterscheidbar.

## Quellen im Repository

[Prompts](../../taxonomy-analysis/src/main/resources/prompts) · [LlmService](../../taxonomy-analysis/src/main/java/com/taxonomy/analysis/service/LlmService.java) · [LlmResponseParser](../../taxonomy-analysis/src/main/java/com/taxonomy/analysis/service/LlmResponseParser.java) · [AnalysisScoreSemantics](../../taxonomy-domain/src/main/java/com/taxonomy/dto/AnalysisScoreSemantics.java) · [TaxonomyService](../../taxonomy-knowledge/src/main/java/com/taxonomy/catalog/service/TaxonomyService.java) · [IP-Gruppenregeln](../../taxonomy-tooling/src/main/java/com/taxonomy/tooling/CatalogueHierarchyAudit.java)
