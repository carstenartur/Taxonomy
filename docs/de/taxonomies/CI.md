# CI – COI Services: Gruppierung und Bewertung

[Gemeinsamer Bewertungsvertrag und Implementierungsstand](../TAXONOMY_SCORING.md) · [English](../../en/taxonomies/CI.md)

## Gegenstand und Gruppierungsmaßstab

COI steht hier für Community of Interest. Der CI-Prompt betrachtet gemeinschafts- beziehungsweise fachgebietsspezifischen Datenaustausch, gemeinsam genutzte Informationsdienste und Interoperabilität. Der aktuelle Baum übernimmt die Quellklassifikation. Die Zugehörigkeit zu einer Fachgemeinschaft ist nicht dasselbe wie eine technische Kommunikationsschnittstelle oder ein konkretes Informationsprodukt.

## Bewertung heute

CI verwendet den gemeinsamen Kategorie-/Elternbudgetweg. Es gibt keine eigene CI-Formel und keine automatische unabhängige Produkteignung an jedem Blatt. Beispielsweise können zwei Dienstkategorien je 30 von einem Elternbudget 60 erhalten. Diese Anteile sagen nicht, dass jeweils nur eine Teilfunktion des Dienstes implementiert werden muss. Originale Vorfahrenkontexte bleiben erhalten; die gemeinsamen Fehler- und Wurzelgrenzen gelten.

## Mehrere Treffer und Konsequenzen

Ein fachlicher Informationszugang und ein fachlicher Austauschdienst können gemeinsam benötigt werden. Ein hoher Score belegt weder eine bereits vorhandene Schnittstelle noch die Kompatibilität ihrer Daten. Verbindungen zu Prozessen, Anwendungen und Informationsprodukten sind anforderungsbezogen zu prüfen; die bloße Nähe im Katalog genügt nicht.

## Zielbild und Grenzen

Fachgemeinschaft und Dienstfunktion können getrennte Klassifikationsachsen sein. Ein über beide Achsen auffindbarer Dienst bleibt derselbe Kandidat, während verschiedene Anforderungsbeiträge getrennt nachweisbar bleiben. Eine neue Navigationsgruppe darf keine fachliche Community-Zugehörigkeit vortäuschen.

Abnahme: Zwei gemeinsam benötigte Dienste bleiben möglich; eine fachliche Klassifikation wird nicht automatisch als technische Interoperabilität gewertet; alternative Zugänge duplizieren keine Dienste.

## Implementierungsquellen

[CI-Prompt](../../../taxonomy-analysis/src/main/resources/prompts/CI.txt) · [Bewertungsaufrufe](../../../taxonomy-analysis/src/main/java/com/taxonomy/analysis/service/LlmService.java) · [Beziehungssuchmodell](../../../taxonomy-domain/src/main/java/com/taxonomy/dto/RelationSearchModel.java)
