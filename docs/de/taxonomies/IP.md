# IP – Information Products: Gruppierung und Bewertung

[Gemeinsamer Bewertungsvertrag und verbindliche Quellgrenzen](../TAXONOMY_SCORING.md) · [English](../../en/taxonomies/IP.md)

## Originalstruktur und begrenzte Ergänzung

IP bezeichnet Informationsprodukte, nicht allgemein Softwareprodukte. Die auditierte Quelle enthält 1.071 IP-Einträge; das bisherige Overlay ordnet 866 Einträge ein, davon 853 als `PRODUCT`. Diese Zahlen sind strukturelle Befunde, keine fachlichen Einzelabnahmen. Die Laufzeit verwendet einen primären Elterncode; zusätzliche Klassifikationscodes sind Metadaten.

**Verbindlicher Änderungsauftrag:** Alle Originalknoten einschließlich ihrer Beschreibungen, IDs, Quellstatus und ursprünglichen Anordnung bleiben erhalten. Bestehende sinnvolle Elternbeziehungen und die Quellreihenfolge werden nicht geändert. Neue Gruppenknoten sind nur an belegten IP-Anschlusslücken erlaubt, nicht zur allgemeinen Neuordnung des Katalogs. Eine geringe KI-Bewertung beweist keinen fehlerhaften Elternbezug. Auch das bisherige Overlay muss auf diesen eingeschränkten Umfang geprüft werden.

Die Ergänzung ist separat versionierte Navigation; sie überschreibt weder Original-Elternangaben noch Reihenfolge. Sinnvolle Beziehungen innerhalb eines betroffenen Teilbaums bleiben bestehen. Es genügt ein einziger nachvollziehbarer Baum; mehrere Facetten sind nicht gefordert. Dies ersetzt das frühere Ziel einer allgemeinen facettierten Neuordnung. Die konkrete Auswahl und Anbindung aller zulässigen Fälle bleibt offen.

## Maßstab der fünf bisherigen Vorschläge

Der vorhandene Generator verwendet **nur englische Wörter im Originaltitel** der durch das Overlay als `PRODUCT` markierten Einträge. Er wandelt mit `Locale.ROOT` in Kleinbuchstaben um, trennt an Zeichen außerhalb von Unicode-Buchstaben/Zahlen und prüft exakte Wörter. Beschreibungen werden im Audit erhalten, aber nicht zur Gruppenauswahl ausgewertet. Es gibt dabei keine KI- oder fachliche Unterordnungsprüfung.

| Vorschlag | Exakte Wörter | Referenzen im geprüften Katalog |
|---|---|---:|
| Gefahren-/Warninformationen | `hazard`, `hazards`, `warning`, `warnings` | 26 |
| Berichte | `report`, `reports` | 222 |
| Pläne | `plan`, `plans` | 61 |
| Anfragen | `request`, `requests` | 48 |
| Aufträge | `order`, `orders` | 50 |

Diese Regeln mischen Gegenstand/Zweck und Artefaktform. Sie sind weder eine einheitliche fachliche Zerlegung noch vollständig; Wortgleichheit kann irreführen, Synonyme werden übersehen. Die Referenzmengen überlappen und dürfen nicht als disjunkte Abdeckung addiert werden. 222 Berichte sind auch keine begrenzte Modell-Kandidatenliste. Die Regeln prüfen bisher **nicht** die nun verbindliche Beschränkung auf belegte IP-Anschlusslücken.

Alle Vorschläge bleiben ungeprüfte `NAVIGATION_GROUP` mit `reviewRequired=true`, `affectsScores=false`, `inheritsSemantics=false` und `createsArchitectureElement=false`. Identitäten beginnen mit `local:ip:navigation:`; `ip-reports` ist nur ein Anzeigecode. Sie sind nicht in den aktiven Baum eingefügt. Ihre Existenz berechtigt nicht zur Änderung korrekt eingeordneter Originale.

## Navigation erlaubt, Architekturbeziehungen verboten

**Zunächst darf kein lokaler Gruppenknoten Quelle oder Ziel einer Architekturbeziehung sein**, auch nicht nach interner Freigabe. Gruppen sind weder benötigte Informationsprodukte noch offizielle Klassifikationen von Architekturbausteinen. Diese Grenze ist als zentrale Herkunfts-/Rollenprüfung für KI, API/manuelle Eingabe, Speicherung/Übernahme, Projektion und Export umzusetzen; sie ist nicht schon durch die Vorschlagsmetadaten bewiesen.

Eltern-Kind-Zuordnungen in der ergänzten Navigation bleiben dagegen nötig und zulässig. Ein lokaler Ast führt zu Originaleinträgen; die fachliche Beziehung endet an einem geeigneten **originalen** Blatt oder Zwischenknoten. Ein Gruppenfund allein begründet keine Kante. Keine automatische Umleitung auf den nächsten Originalvater oder irgendein Blatt; ungelöste Zielsuche bleibt offen. Herkunft wird nicht aus Groß-/Kleinschreibung oder frei übergebenen API-Feldern abgeleitet.

Originalbegriff und vorgeschlagene Beziehung besitzen getrennte Herkunft: Auch eine KI-Kante zwischen Original-IP-Knoten ist keine offizielle Aussage des Taxonomieherausgebers.

## Bewertung heute und noch offene Grenzen

Familien/Kategorien verwenden das Elternbudget. Konkrete `PRODUCT`-Einträge erhalten unabhängige Eignung zur Originalanforderung in Paketen von höchstens zehn. Die Standardgrenze 50 setzt kleinere Werte im strukturierten Ergebnis auf null. Mehrere Produkte können gleichzeitig hohe Werte haben; ihre Summe muss nicht dem Familienwert entsprechen.

Nachgelagert gilt noch `round(Elternrelevanz * Produkteignung / 100)`: 40 und 80 ergeben 32. Die Heuristik ist keine validierte bedingte Wahrscheinlichkeit. Der gemeinsame Vertrag erklärt Fehlernullen, fehlende Elternwerte, Wurzelnormalisierung sowie Modellantwort gegenüber gespeichertem Ausgangs-/Effektivwert. Nur vollständig erfolgreiche Produktprüfung ohne geeigneten Kandidaten darf einen bestätigten Katalogabdeckungsbefund liefern. #1118 ändert diese Formeln nicht.

Gefährdungsdatensatz, Maßnahmenplan und Wirksamkeitsnachweis können gemeinsam nötig sein; andere Berichtsformen können Alternativen für denselben Beitrag sein. Eignung allein unterscheidet das nicht. Der konkrete Beitrag, Zugriff und Bedingungen müssen Bedarf und Beziehung begründen.

## Abnahme

Quellidentitäten, Inhalte, Status, sinnvolle Elternbeziehungen und Reihenfolge bleiben erhalten. Nur belegte IP-Lücken werden angebunden; kein Original wird dupliziert oder verloren. Eine lokale Navigationsstufe ändert weder Eignung noch Bedarf. Ein gefundener lokaler Ast bleibt navigierbar, aber jede fachliche Kante zu ihm wird sichtbar abgewiesen. Originale Zwischenknoten bleiben möglich; notwendige Produkte bleiben gemeinsam auswählbar. Eine ungeklärte Anbindung darf passende Originaleinträge nicht still ausschließen. Fachliche Zuordnung, aktive Navigation, Endpunktsperre und koordinierte Bewertungsänderungen bleiben offene Arbeit in #1111.

## Implementierungsquellen

[Gruppenregeln und Audit](../../../taxonomy-tooling/src/main/java/com/taxonomy/tooling/CatalogueHierarchyAudit.java) · [Overlay](../../../taxonomy-knowledge/src/main/resources/data/nato-taxonomy.json) · [Familienprompt](../../../taxonomy-analysis/src/main/resources/prompts/IP.txt) · [Produktprompt](../../../taxonomy-analysis/src/main/resources/prompts/IP-product.txt) · [Abgeleitete Werte](../../../taxonomy-domain/src/main/java/com/taxonomy/dto/AnalysisScoreSemantics.java)
