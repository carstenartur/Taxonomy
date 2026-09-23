# BP – Business Processes: Gruppierung und Bewertung

[Gemeinsamer Bewertungsvertrag und Implementierungsstand](../TAXONOMY_SCORING.md) · [English](../../en/taxonomies/BP.md)

## Gegenstand und Gruppierungsmaßstab

Der aktuelle BP-Prompt betrachtet Tätigkeiten, operative Abläufe, Ausführungsschritte und verfahrensbezogene Aspekte einer Anforderung. Grundlage sind die ursprünglichen Katalogeinträge und Elternbeziehungen; die neuen IP-Navigationsgruppen betreffen BP nicht. Eine Elternbeziehung im Klassifikationsbaum bedeutet weder zeitliche Reihenfolge noch automatisch eine vollständige Prozesszerlegung.

## Bewertung heute

BP verwendet den gemeinsamen Kategorie-/Elternbudgetweg. Die angebotenen Kinder teilen rechnerisch den Elternwert. Bei Elternwert 60 und gelieferten Gewichten 80 und 40 entstehen 40 und 20. Das ist keine Aussage, dass die zweite Tätigkeit nur zu einem Drittel benötigt wird. Der dokumentierte Ein-Wurzel-Normalisierungseffekt gilt auch hier.

Der Bewertungskontext enthält eigene Beschreibungen und anwendbare Quell-Vorfahrenbeschreibungen. Damit bleibt beispielsweise der am Vater definierte Geltungsbereich „Bearbeitung von Sicherheitsvorfällen“ für ein knapp beschriebenes Kind „Bewertung durchführen“ erhalten. Dieses Beispiel ist keine neu behauptete C3-Katalogidentität. Die Beschreibungen müssen im Prompt nicht an jedem Kind wiederholt werden.

## Mehrere Treffer und Konsequenzen

Gefahrenmeldung, Maßnahmenplanung und Wirksamkeitsprüfung können gemeinsam erforderlich sein. BP ist daher keine grundsätzlich exklusive Auswahl, im Gegensatz zu der Annahme „mehrere Treffer sind nur bei Produkten erlaubt“. Ein niedrigerer Gewichtsanteil bedeutet nicht optional. Ablauffolge, Rollenzuordnung sowie gelesene oder erzeugte Informationen brauchen eigenständige begründete Beziehungen.

## Zielbild und Grenzen

Bei echter semantischer Unterordnung und demselben Maßstab soll Astrelevanz konsistent sein. Ein höherer Kinderwert verlangt die Prüfung von Vaterbewertung, Kontext oder Unterordnung, nicht bloßes Herunterrechnen. Eine zusätzliche Anzeigegruppe darf keinen Bedeutungswechsel erzeugen. Das ist noch kein vollständig implementierter Konsistenzprüfer und kein Ersatz des heutigen Summenvertrags.

Abnahme: Ein nur am Vater erklärter Anwendungsbereich bleibt beim Kind erhalten; zwei gemeinsam notwendige Tätigkeiten werden nicht als Alternativen behandelt; eine Anzeigeumgruppierung verändert nicht den fachlichen Bedarf.

## Implementierungsquellen

[BP-Prompt](../../../taxonomy-analysis/src/main/resources/prompts/BP.txt) · [Parser](../../../taxonomy-analysis/src/main/java/com/taxonomy/analysis/service/LlmResponseParser.java) · [Gemeinsamer Kontext](../../../taxonomy-knowledge/src/main/java/com/taxonomy/catalog/service/TaxonomyService.java)
