# Gruppierung und Bewertung der Teiltaxonomien

> **Implementierungsstand:** Quellstand `e73dcfe43964b68aaa6bc7d967471fe209da9a8a` aus PR #1118, nicht eine bereits ausgerollte Installation. Die separaten Parseränderungen aus #1113 sind in dieser Basis nicht enthalten. Die unten ausdrücklich als **verbindlicher Änderungsauftrag** bezeichneten Grenzen wurden am 23. September 2026 präzisiert; ihre Dokumentation ist kein Nachweis einer bereits durchgängigen Laufzeitsperre. Dieser Dokumentationsschritt verändert weder Code noch Scores, Katalogdaten oder historische Snapshots.

[English](../en/TAXONOMY_SCORING.md) · [Kontext und Navigation](../dev/hierarchy-context-and-navigation.md) · [Technischer Score-Datenvertrag](../dev/ANALYSIS_SCORE_SEMANTICS.md)

## Eine gemeinsame Ausführung, unterschiedliche fachliche Fragen

Es gibt nicht acht verschiedene implementierte Rechenverfahren. Im regulären LLM-Pfad verwenden Kategorien denselben Elternbudget-Parser; die Prompts unterscheiden den fachlichen Blickwinkel. Nur ausdrücklich als `PRODUCT` klassifizierte Einträge erhalten die unabhängige Produktbewertung. Ein Blatt oder eine User Application ist nicht allein deshalb ein solches Produkt.

| Seite | Fachliche Frage | Heutiger regulärer Bewertungsweg |
|---|---|---|
| [BP – Business Processes](taxonomies/BP.md) | Welche Tätigkeiten und Abläufe werden benötigt? | Kategorie-/Elternbudget |
| [BR – Business Roles](taxonomies/BR.md) | Welche Rollen und Verantwortlichkeiten sind betroffen? | Kategorie-/Elternbudget |
| [CP – Capabilities](taxonomies/CP.md) | Welche Fähigkeiten werden benötigt? | Kategorie-/Elternbudget |
| [CI – COI Services](taxonomies/CI.md) | Welche gemeinschafts-/fachgebietsspezifischen Dienste werden benötigt? | Kategorie-/Elternbudget |
| [CO – Communications Services](taxonomies/CO.md) | Welche Kommunikations- und Übertragungsdienste werden benötigt? | Kategorie-/Elternbudget |
| [CR – Core Services](taxonomies/CR.md) | Welche grundlegenden IT-Dienste werden benötigt? | Kategorie-/Elternbudget |
| [IP – Information Products](taxonomies/IP.md) | Welche Informationen werden erzeugt, gelesen oder verändert? | Familien: Kategorie; `PRODUCT`: unabhängige Eignung |
| [UA – User Applications](taxonomies/UA.md) | Welche benutzerseitigen Anwendungsfunktionen werden benötigt? | Kategorie-/Elternbudget |

## Verbindlicher Änderungsauftrag: Originalstruktur erhalten

**Die Originaleinträge und ihre Anordnung in der Excel-Quelle bleiben maßgeblich.** IDs, Titel, Beschreibungen, Quellstatus, ursprüngliche Elternangaben und Quellreihenfolge bleiben erhalten. Bestehende sinnvolle Eltern-Kind-Beziehungen werden nicht umgeordnet oder durch neue Zwischenknoten unterbrochen. Das gilt für originale Zwischenknoten ebenso wie für Blätter und für alle Teiltaxonomien. Ein aus der Quelle stammender Draft-Eintrag wird dadurch nicht als fachlich freigegeben ausgegeben.

**Ergänzungen sind ausschließlich an belegten IP-Anschlusslücken erlaubt.** Zulässige Gründe sind eine fehlende oder nicht auflösbare Elternreferenz oder ein konkret belegter fachlich ungeeigneter Elternbezug. Ein niedriger Score, Titelwortähnlichkeit oder ein für die Suche bequemerer Baum reicht nicht aus. Bei unklarer Semantik bleibt die Zuordnung offen. Existierende sinnvolle Beziehungen innerhalb eines betroffenen Teilbaums bleiben erhalten; ergänzt wird nur die fehlende Anbindung.

Die Ergänzung wird getrennt versioniert und überschreibt weder das originale `Parent`-Feld noch die Quellreihenfolge. Originalansicht und ergänzte Navigation müssen unterscheidbar sein. **Ein einziger nachvollziehbarer Baum genügt; Mehrfachwege und Facetten sind nicht gefordert.** Frühere Aussagen über eine allgemeine Neuklassifikation oder verpflichtende Facettennavigation sind damit ersetzt. Die bestehenden Overlay-Zuordnungen müssen ebenfalls auf diesen engeren Umfang geprüft werden.

## Lokale Navigation ist keine offizielle Taxonomieaussage

Erfundene Gruppenknoten sind sichtbar als lokale Navigationshilfen zu kennzeichnen. Herkunft und Rolle müssen explizit und versionsbezogen sein; Kleinbuchstaben im Code oder ein `reviewRequired`-Wert reichen nicht als Herkunftsnachweis. Interne Prüfung verleiht einer lokalen Gruppe keinen offiziellen Status.

**Zunächst sind Architekturbeziehungen mit lokalen Gruppenknoten als Quelle oder Ziel verboten.** Das gilt für KI-Vorschläge, manuelle/API-Eingaben, Speicherung/Übernahme, Architekturprojektion und Export. Eine Gruppe darf weder als fachlicher Architekturbaustein erzeugt noch als offizielle Taxonomieklassifikation eines Bausteins verwendet werden. Die für den Baum nötigen Navigationszuordnungen sind davon getrennt: Ein lokaler Ast darf zu zulässig angeschlossenen Original-IP-Einträgen führen.

Ein Verstoß muss sichtbar abgewiesen werden. Eine Kante darf nicht still ausgeblendet oder auf einen Originalvater bzw. das erste Blatt umgebogen werden. Solange nur die lokale Gruppe passt, bleibt die Suche oder der Bedarf offen. Originale Zwischenknoten bleiben mögliche Endpunkte, wenn Typ und Anforderungsbeleg passen. Historische Nachweise bleiben unverändert; Konflikte bei erneuter Verwendung müssen offengelegt werden.

Auch eine KI-Beziehung **zwischen Originaleinträgen** ist keine offizielle Herausgeberaussage. Begriffsherkunft und Beziehungsherkunft sind getrennt zu dokumentieren.

## Heutiger Kontextpfad

Quell-Unterordnung, ergänzte Klassifikation, Navigation und Architekturbeziehung sind unterschiedliche Dinge. Die Persistenz hat einen primären `parentCode`; das Overlay ergänzt außerdem `secondaryClassificationCodes`. PR #1118 behält anwendbare Quell-Vorfahrenbeschreibungen im Bewertungskontext, beendet die automatische Vererbung aber an Overlay-Elternzuordnungen. Fehlende Eltern, Zyklen und wurzelübergreifende semantische Pfade scheitern explizit. Das setzt die oben beschriebene neue Zuordnungs- und Endpunktsperre noch nicht vollständig um.

## Heutige Kategorie-Rechnung: Gewichtungsbudget

Der Prompt verlangt ganzzahlige Scores und Begründungen mit Summe `P`, dem Elternbudget. Bei positiver gelieferter Summe `S` normalisiert der Parser Abweichungen proportional:

```text
w_i = P * r_i / S
Abrunden, dann restliche Einheiten an die größten Nachkommareste vergeben.
P = 60; gelieferte Kinderwerte 80, 40 -> 40, 20.
```

Bei `S = P` bleiben Werte unverändert; ein vollständig mit null beantworteter Satz bleibt null. Eine gelieferte Summe über `P` erzeugt einen `TaxonomyDiscrepancy`-Eintrag. Dieser Summenbefund ist nicht die noch fehlende semantische Kind-/Vater-Konsistenzprüfung.

Die Normalisierung verteilt Gewichte. Sie beweist weder Erfüllung, Notwendigkeit, Wahrscheinlichkeit, Kostenanteil noch gegenseitigen Ausschluss. Mehrere Prozesse, Rollen, Dienste, Anwendungen oder Produkte können gleichzeitig erforderlich sein; ein kleiner Budgetanteil kann unverzichtbar sein.

**Bekannter Wurzelfehler:** `analyzeAllTaxonomies` und `analyzeStreaming` senden jede Wurzel einzeln mit `P = 100` durch denselben Parser. Eine positive Einzelantwort `20` wird so `100`; `0` bleibt `0`. Das widerspricht der beabsichtigten unabhängigen Wurzelrelevanz und ist noch zu korrigieren. Mockwerte können davon abweichen.

## Heutige IP-Produktrechnung

`analysisRole=PRODUCT` aktiviert unabhängige Eignung für die Originalanforderung. Kandidaten werden nach Code sortiert, in konfigurierbaren Paketen von höchstens zehn geprüft und anhand der Standard-Mindestgrenze 50 gefiltert: `49, 80, 90` wird im strukturierten Ergebnis `0, 80, 90`. Das ist keine Aufteilung auf 100.

```text
Version 1: effektive Relevanz = round(direkte Elternrelevanz * Produkteignung / 100)
Elternwert 40 und Produkteignung 80 -> effektiver Wert 32.
```

Fehlt ein bewerteter direkter Elternwert, entsteht ein effektiver Ersatzwert `0` mit Warnung, kein Nachweis fehlender Eignung. Die Multiplikation ist eine bestehende Gewichtungsheuristik, keine validierte bedingte Wahrscheinlichkeit. Ein falscher Elternbezug kann die Suche oder den abgeleiteten Wert verzerren. In gemischten Geschwistersätzen erhalten Kategorien das Elternbudget und Produkte unabhängige Eignung; beide Werte dürfen nicht zu einer Erfüllungssumme addiert werden.

## Eignung, Bedarf und Suchpriorität

Gleich hohe Scores können gemeinsam benötigte Beiträge, Alternativen oder nur allgemein passende Kandidaten beschreiben. Erst Beitrag, Belege und Bedingungen begründen den Bedarf. Die zuschaltbare Beziehungssuche unterscheidet `REQUIRED`, `OPTIONAL` und `ALTERNATIVE`, ersetzt damit aber noch keinen vollständigen Produktvariantenentscheid.

**Änderungsziel:** Gleicher Anforderungskontext und echte semantische Unterordnung erfordern konsistente Astrelevanz (`Kind <= Vater`); Widersprüche bleiben als Befund mit Originalbelegen sichtbar statt durch Kappen/Multiplizieren verborgen zu werden. Daraus folgt keine Summengleichheit der Geschwister. Lokale Navigationsgruppen bekommen höchstens eine getrennte Suchpriorität, keine fachliche Produktrelevanz. Ihre Einfügung darf Eignung und Bedarf eines Originaleintrags nicht ändern. Der Abstieg muss mehrere erforderliche Äste verfolgen können, ohne mehrere Wege je Eintrag zu benötigen. Eine ungeklärte IP-Anbindung darf relevante Einträge nicht unbemerkt ausschließen.

## Fehler, gespeicherte Werte und Grenzen

Die beschriebene Basis enthält noch fehlende-Kategorieantwort-zu-null-Ergänzungen und Fehlernullen. Null muss zusammen mit Fehler, Warnung, Status und Rohantwort interpretiert werden. #1113 bearbeitet dies separat; seine Integration ist nicht vorausgesetzt. Eine unterschwellige Antwort ist ebenfalls keine ursprünglich gelieferte Null.

`LlmCallDetail` und inkrementelle Ereignisse enthalten bereits normalisierte/gefilterte Werte. `AnalysisResult.rawScores` liegt vor der zusätzlichen Produkt-Elterngewichtung, nicht zwingend vor jeder Transformation der Modellantwort. Rohantwort, `scoreDetails`, `scoreSemanticsVersion`, Warnungen und eingefrorener Katalog gehören zum Nachweis. Bei der weiteren Migration müssen Originalwerte und Transformationen getrennt erhalten bleiben.

Der automatische Abstieg verfolgt positive Kategorien; Produkte sind Endpunkte. Nicht besucht ist nicht negativ bewertet. Aufruf-, Prompt- und Speichergrenzen können Teilergebnisse erzeugen. Manuelle Werte, Mock-/Replaydaten und lokale Embedding-Ähnlichkeit sind keine austauschbaren Notwendigkeitsnachweise. Lokale Embeddings umgehen die natürlichsprachlichen Prompts. Promptüberschreibungen ändern nicht automatisch den Parservertrag. Keine dieser Einschränkungen wird durch diese Dokumentationsänderung repariert.

## Abnahme der nächsten Umsetzung

Original-IDs, Inhalte, Quellstatus, sinnvolle Elternbeziehungen und Reihenfolge bleiben erhalten. Nur belegte IP-Lücken werden ergänzt; jeder Originaleintrag bleibt einmal repräsentiert. BP-Kontext wird weiter vererbt. Navigation durch eine lokale Gruppe funktioniert, aber lokale Quelle, lokales Ziel und manipulierte Herkunft einer Architekturbeziehung werden abgewiesen. Originale Zwischenknoten bleiben bei passender Semantik zulässig. Keine Kante wird umgeleitet. Gemeinsam erforderliche Produkte bleiben möglich; Alternativen werden nicht automatisch gemeinsam übernommen. Fehlende, echte Null- und unterschwellige Antworten bleiben unterscheidbar. Diese Abnahme ist offen, nicht durch reine Struktur- oder Dokumentationsprüfungen erledigt.

## Quellen im Repository

[Prompts](../../taxonomy-analysis/src/main/resources/prompts) · [LlmService](../../taxonomy-analysis/src/main/java/com/taxonomy/analysis/service/LlmService.java) · [LlmResponseParser](../../taxonomy-analysis/src/main/java/com/taxonomy/analysis/service/LlmResponseParser.java) · [AnalysisScoreSemantics](../../taxonomy-domain/src/main/java/com/taxonomy/dto/AnalysisScoreSemantics.java) · [TaxonomyService](../../taxonomy-knowledge/src/main/java/com/taxonomy/catalog/service/TaxonomyService.java) · [IP-Gruppenregeln](../../taxonomy-tooling/src/main/java/com/taxonomy/tooling/CatalogueHierarchyAudit.java)
