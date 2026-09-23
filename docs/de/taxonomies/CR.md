# CR – Core Services: Gruppierung und Bewertung

[Gemeinsamer Bewertungsvertrag und Implementierungsstand](../TAXONOMY_SCORING.md) · [English](../../en/taxonomies/CR.md)

## Gegenstand und Gruppierungsmaßstab

Der CR-Prompt betrachtet grundlegende IT- und Führungsunterstützungsdienste, Infrastruktur und Plattformfunktionen. Der aktuelle Baum folgt dem Quellkatalog. Ein Grundlagendienst ist keine Hardwarebestellung und ein Katalogblatt nicht automatisch ein konkret auszuwählendes Produkt.

## Bewertung heute

CR verwendet den gemeinsamen Kategorie-/Elternbudgetweg, keine besondere Infrastrukturformel. Zwei Kinder mit je 30 bei Elternwert 60 teilen ein Relevanzgewicht; das sind keine Verfügbarkeitswerte, Kostenanteile oder Mengen. Der LLM-Kontext bewahrt eigene und anwendbare Quell-Vorfahrenbeschreibungen. Die gemeinsamen Normalisierungs- und Fehlergrenzen gelten.

## Mehrere Treffer und Konsequenzen

Identitätsprüfung, Speicherung und Informationszugriff können gleichzeitig gebraucht werden. Ein Grundlagendienst kann erst durch eine begründete Abhängigkeit sichtbar werden, obwohl sein Name nicht in der Originalanforderung steht. Dieser Bedarf muss als abgeleitet gekennzeichnet werden und darf nicht nachträglich als wörtliche Forderung erscheinen. Ein niedriger direkter Score beweist nicht, dass eine Abhängigkeit entfallen kann.

## Zielbild und Grenzen

Plattformfunktion, Betriebsmodell und Technologie sind mögliche getrennte Klassifikationsachsen. Der konkrete Betriebs- oder Technologieentscheid soll nicht aus einer unklaren Gruppenzuordnung entstehen. Mehrere Wege zum selben Dienst erzeugen nicht mehrere Implementierungspflichten.

Abnahme: Mehrere notwendige Grundlagendienste bleiben möglich; abgeleiteter Bedarf behält seine Begründung; eine Navigation nach Technologie verändert nicht die fachliche Originalidentität.

## Implementierungsquellen

[CR-Prompt](../../../taxonomy-analysis/src/main/resources/prompts/CR.txt) · [Bewertungsaufrufe](../../../taxonomy-analysis/src/main/java/com/taxonomy/analysis/service/LlmService.java) · [Beziehungssuchmodell](../../../taxonomy-domain/src/main/java/com/taxonomy/dto/RelationSearchModel.java)
