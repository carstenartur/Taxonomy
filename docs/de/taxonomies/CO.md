# CO – Communications Services: Gruppierung und Bewertung

[Gemeinsamer Bewertungsvertrag und Implementierungsstand](../TAXONOMY_SCORING.md) · [English](../../en/taxonomies/CO.md)

## Gegenstand und Gruppierungsmaßstab

Der CO-Prompt betrachtet Sprach- und Datenkommunikation, Netze, Übertragung und Kommunikationsinfrastruktur. Die bestehende Quellhierarchie ist der Ausgangspunkt. Das Wort „Kommunikation“ in einer zusätzlichen Anwendungsfacette darf nicht automatisch mit der gesamten CO-Teiltaxonomie gleichgesetzt werden.

## Bewertung heute

CO verwendet die gemeinsame Kategorie-/Elternbudgetverteilung. Der reguläre Parser behandelt seine Kategorien rechnerisch wie BP, BR oder CP; nur die fachliche Frage des Prompts ist anders. Bei Elternwert 60 können Sprach- und Datendienstkandidaten beispielsweise 20 und 40 erhalten. Das bedeutet weder 20/40 Prozent Bandbreite noch Verfügbarkeit oder Zuverlässigkeit. Quell-Vorfahrenkontext und gemeinsame Null-/Fehlerregeln gelten.

## Mehrere Treffer und Konsequenzen

Sprach- und Datenkommunikation können gleichzeitig erforderlich sein. E-Mail, SMS und Push sind dagegen nur dann Alternativen, wenn sie denselben benötigten Beitrag austauschbar erfüllen sollen. Das ist aus zwei ähnlichen Scores nicht ableitbar. Eine Forderung nach Benachrichtigung berechtigt nicht zur stillen Wahl eines Kanals.

## Zielbild und Grenzen

Dienstfunktion, Kommunikationsmedium und technische Realisierung können unterschiedliche Achsen sein. Ein E-Mail-Client ist eine benutzerseitige Anwendung, nicht automatisch der darunterliegende Kommunikationsdienst. Für deren Verbindung ist eine begründete Architekturbeziehung nötig, keine erfundene Taxonomie-Elternkante.

Abnahme: Gemeinsam verlangte Sprach- und Datendienste bleiben erhalten; ein nicht festgelegter Kanal wird als Frage oder Variante behandelt; eine Funktionsfacette verändert keine ursprüngliche Katalogidentität.

## Implementierungsquellen

[CO-Prompt](../../../taxonomy-analysis/src/main/resources/prompts/CO.txt) · [Parser](../../../taxonomy-analysis/src/main/java/com/taxonomy/analysis/service/LlmResponseParser.java) · [Beziehungssuchmodell](../../../taxonomy-domain/src/main/java/com/taxonomy/dto/RelationSearchModel.java)
