# IP – Information Products: Gruppierung und Bewertung

[Gemeinsamer Bewertungsvertrag und Implementierungsstand](../TAXONOMY_SCORING.md) · [English](../../en/taxonomies/IP.md)

## Gegenstand und bisherige Hierarchie

IP bezeichnet Informationsprodukte, etwa Berichte, Pläne, Lageinformationen oder andere gelesene und erzeugte Informationsartefakte, nicht allgemein Softwareprodukte. Die Quelle enthält 1.071 IP-Einträge. Das bestehende Overlay ordnet 866 Einträge ein, darunter 853 als `PRODUCT`; diese Zahlen sind strukturelle Auditbefunde, keine fachliche Einzelabnahme. Der wirksame Baum hat einen primären Elterncode; zusätzliche Klassifikationscodes sind Metadaten. In #1118 wird eine Overlay-Zuordnung nicht als Beweis geerbter Quellsemantik verwendet.

## Nach welchem Maßstab entstehen die fünf neuen Vorschläge?

**Ausschließlich durch englische Wörter im Originaltitel.** Der Generator betrachtet nur Originaleinträge, die das bestehende Overlay als `PRODUCT` markiert. Er wandelt den Titel mit `Locale.ROOT` in Kleinbuchstaben um, trennt an Zeichen, die weder Unicode-Buchstaben noch Zahlen sind, und prüft auf mindestens ein genau übereinstimmendes Wort. Die vollständige Beschreibung wird im Audit erhalten, aber nicht für diese Gruppenauswahl ausgewertet. Es gibt dafür keinen KI-Aufruf, kein Embedding und keine Bewertung der fachlichen Unterordnung.

| Vorgeschlagener Zugang | Exakte Wörter | Referenzen im geprüften Katalog |
|---|---|---:|
| Gefahren-/Warninformationen | `hazard`, `hazards`, `warning`, `warnings` | 26 |
| Berichte | `report`, `reports` | 222 |
| Pläne | `plan`, `plans` | 61 |
| Anfragen | `request`, `requests` | 48 |
| Aufträge | `order`, `orders` | 50 |

Diese fünf Regeln mischen mindestens Gegenstand/Zweck mit Artefaktform. Sie sind deshalb **keine saubere einheitliche fachliche Zerlegung**, keine vollständige Abdeckung aller Produkte und kein fertig aufgebauter IP-Baum. Ein Warnbericht kann mehrere Zugänge erfüllen. Ein passendes Synonym ohne eines der Wörter bleibt unentdeckt; eine zufällige Wortgleichheit kann falsch zuordnen. Die Zahlen sind überlappende Referenzmengen und dürfen nicht als disjunkte Gesamtzahl addiert werden. 222 Berichte in einer Gruppe sind auch noch keine begrenzte KI-Kandidatenliste.

Alle Vorschläge sind `NAVIGATION_GROUP`, `reviewRequired=true`, `affectsScores=false`, `inheritsSemantics=false` und `createsArchitectureElement=false`. Ihre Identitäten beginnen mit `local:ip:navigation:`; `ip-reports` ist nur ein Anzeigecode. `parentId=IP` im Vorschlagsdokument hängt keine neuen Knoten in den Laufzeitbaum. Keine Original-ID, Originalbeschreibung oder aktive Elternzuordnung wird dadurch verändert.

## Bewertung heute

Familien und Kategorien verwenden den Elternbudgetweg. Konkrete `PRODUCT`-Einträge erhalten unabhängige Eignungswerte für die Originalanforderung, in Paketen von höchstens zehn. Die Standard-Mindestgrenze 50 setzt kleinere Werte im strukturierten Ergebnis auf null. Mehrere Produkte dürfen gleichzeitig hohe Werte haben, ihre Summe muss nicht 100 oder dem Familienwert entsprechen.

Nachgelagert gilt noch `round(Elternrelevanz * Produkteignung / 100)`: 40 und 80 ergeben 32. Diese Heuristik ist nicht als bedingte Wahrscheinlichkeit gerechtfertigt und wird durch die neuen Navigationsgruppen nicht verbessert. Der gemeinsame Vertrag dokumentiert den fehlenden-Elternwert-Fallback, Fehlernullen und den Unterschied zwischen Modellantwort, gespeichertem Ausgangswert und effektiver Relevanz. Nur eine vollständig erfolgreiche Produktprüfung ohne geeigneten Kandidaten darf einen bestätigten Katalogabdeckungsbefund erzeugen.

## Mehrere Produkte: gemeinsam oder alternativ?

Für Gefahrenmeldung und Maßnahmenverfolgung können ein Gefährdungsdatensatz, ein Maßnahmenplan und ein Wirksamkeitsnachweis gemeinsam benötigt werden. Zwei verschiedene Berichtsformen können dagegen denselben Bedarf alternativ erfüllen. Hohe Eignung beweist weder gleichzeitige Notwendigkeit noch Austauschbarkeit. Die Entscheidung braucht den konkreten Anforderungsbeitrag, Zugriffsart und Bedingungen. Die Beispiele benennen keine neuen Original-C3-Codes.

## Zielbild: explizite Facetten statt vermischter Äste

Fachlicher Gegenstand, Verwendungszweck und Artefaktform sollen getrennt beschrieben werden. Beispielsweise kann derselbe Warnbericht über „Gegenstand → Gefährdungen“ und „Artefaktform → Bericht“ erreichbar sein. Gruppen erhalten positive und negative Abgrenzungen; die Aufnahme stützt sich auf Titel und Beschreibung mit Herkunftsnachweis. Originale und fachlich geprüfte Unterordnungen bleiben erhalten. Ein Zweig dient entweder einer begründeten fachlichen Unterordnung oder nur der Navigation; diese Rollen werden nicht still vertauscht.

Mehrfachzugänge referenzieren eine Originalidentität. Sie dürfen keine Mehrfachzählung, keine Multiplikation verschiedener Elternwerte und keine unnötige Wiederholungsbewertung auslösen. Andere Anforderungsbeiträge bleiben getrennte Belege. Ein schwach bewerteter provisorischer Zugang darf nicht allein alle anderen Zugänge sperren. Diese facettierte Laufzeitnavigation und die koordinierte Scoremigration sind noch offen in #1111.

Abnahme: Ein Produkt über zwei Facetten bleibt ein Objekt; gemeinsam benötigte Produkte bleiben gemeinsam möglich; Alternativen werden nicht automatisch beide übernommen; reine Umgruppierung verändert die Eignung nicht; Fachzuordnungen haben überprüfbare Definitionen statt bloßer Titelwortregeln.

## Implementierungsquellen

[Gruppenregeln und Audit](../../../taxonomy-tooling/src/main/java/com/taxonomy/tooling/CatalogueHierarchyAudit.java) · [Overlay](../../../taxonomy-knowledge/src/main/resources/data/nato-taxonomy.json) · [Familienprompt](../../../taxonomy-analysis/src/main/resources/prompts/IP.txt) · [Produktprompt](../../../taxonomy-analysis/src/main/resources/prompts/IP-product.txt) · [Abgeleitete Werte](../../../taxonomy-domain/src/main/java/com/taxonomy/dto/AnalysisScoreSemantics.java)
