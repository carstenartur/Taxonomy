# BR – Business Roles: Gruppierung und Bewertung

[Gemeinsamer Bewertungsvertrag und Implementierungsstand](../TAXONOMY_SCORING.md) · [English](../../en/taxonomies/BR.md)

## Gegenstand und Gruppierungsmaßstab

BR bezeichnet Business Roles, nicht Business Rules. Der Prompt betrachtet Akteure, Beteiligte, Organisationseinheiten und Verantwortlichkeiten, die in einer Anforderung beschrieben oder impliziert sind. Eine Rollenklasse im Katalog ist weder eine bestimmte Person noch automatisch eine zu erteilende Berechtigung. Die aktuelle Hierarchie stammt aus dem Quellkatalog; es werden keine neuen BR-Gruppen erzeugt.

## Bewertung heute

BR verwendet den Kategorie-/Elternbudgetweg, keinen unabhängigen Produktweg. Mehrere relevante Rollen teilen das rechnerische Budget, zum Beispiel 30 und 30 bei Elternwert 60. Das misst weder Personalbedarf noch Befugnisumfang. Der gemeinsame Vertrag beschreibt Normalisierung, Nullfälle, Wurzelproblem und Abbruch. Originale Vorfahrenbeschreibungen bleiben im LLM-Kontext erhalten.

## Mehrere Treffer und Konsequenzen

Eine meldende Rolle, eine prüfende Rolle und eine freigebende Rolle können gleichzeitig notwendig sein. Mehrere Katalogtreffer beweisen nicht, dass sie dieselbe Person übernehmen darf oder dass eine Funktionstrennung erforderlich ist. Solche Aussagen müssen aus Anforderung oder ausdrücklicher Entscheidung stammen. Eine Rollenklassifikation erzeugt keine Anwendungsberechtigung.

## Zielbild und Grenzen

Fachliche Rolle, Organisationseinheit und Berechtigung können verschiedene Sichtweisen sein. Zusätzliche Zugänge müssen typisiert werden; ein Wechsel der Anzeigegruppe darf keine neue Rollenidentität erzeugen. Astrelevanz und verteiltes Gewicht sind getrennt zu prüfen. Der aktuelle Summenvertrag wird hier dokumentiert, nicht als Maß für Notwendigkeit gerechtfertigt.

Abnahme: Mehrere notwendige Verantwortlichkeiten bleiben erhalten; ein niedriges Gewicht wird nicht als optional interpretiert; bei unklarer Aufgaben- oder Berechtigungstrennung entsteht eine Frage statt einer erfundenen Festlegung.

## Implementierungsquellen

[BR-Prompt](../../../taxonomy-analysis/src/main/resources/prompts/BR.txt) · [Kategorie-/Produktauswahl](../../../taxonomy-analysis/src/main/java/com/taxonomy/analysis/service/LlmService.java) · [Gemeinsamer Kontext](../../../taxonomy-knowledge/src/main/java/com/taxonomy/catalog/service/TaxonomyService.java)
