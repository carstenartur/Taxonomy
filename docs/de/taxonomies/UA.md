# UA – User Applications: Gruppierung und Bewertung

[Gemeinsamer Bewertungsvertrag und Implementierungsstand](../TAXONOMY_SCORING.md) · [English](../../en/taxonomies/UA.md)

## Gegenstand und Gruppierungsmaßstab

Der UA-Prompt betrachtet benutzerseitige Werkzeuge, operative Anwendungen und Bedienoberflächen. Der vorhandene Baum folgt dem Quellkatalog. Eine Anwendungsart ist nicht automatisch ein konkretes Herstellerprodukt, eine eingesetzte Instanz oder ein Informationsprodukt. Der Metadatentyp `PRODUCT`, nicht der alltägliche Begriff Softwareprodukt, entscheidet über den gesonderten IP-Bewertungsweg.

## Bewertung heute

UA-Kategorien verwenden die gemeinsame Elternbudgetverteilung. Eine Anforderung mit Kommunikations- und Auswertungsfunktionen kann zum Beispiel Anteile 40 und 20 eines Elternwerts 60 erzeugen. Diese Zahlen sind Gewichte, keine Anzahl erforderlicher Programme und kein Beleg für eine Aufteilung in zwei Anwendungen. Eigene und anwendbare Quell-Vorfahrenbeschreibungen bleiben erhalten. Gemeinsame Rundungs-, Wurzel- und Fehlergrenzen gelten.

## Mehrere Zugänge am Beispiel E-Mail-Client

**Zielbild, noch keine implementierte Mehrfachhierarchie:** „Funktion → Kommunikation → E-Mail-Client“ und „Anwendungstyp → Client → E-Mail-Client“ sind zwei mögliche Sichten auf dasselbe Originalelement. Das erste ordnet nach Aufgabe, das zweite nach Anwendungsart. Die Gruppen müssen ihre Achse ausdrücklich benennen. Ihre Mitgliedschaft erzeugt weder eine zweite Anwendung noch eine zweite Lizenzpflicht und ist keine Architekturbeziehung zwischen zwei Komponenten.

Ein Client kann zugleich Kommunikation und Terminverwaltung anbieten. Für eine Anforderung wird nur sein konkret benötigter Beitrag bewertet. Eine gute Eignung für E-Mail ist keine Forderung, sämtliche weiteren Funktionen ebenfalls zu realisieren. Ein zusätzlich erforderliches Planungswerkzeug und ein alternativer E-Mail-Client sind wiederum unterschiedliche Bedarfssituationen; ähnliche Scores unterscheiden sie nicht.

## Zielbild und Grenzen

Funktionsfacetten, Anwendungstyp und technische Betriebsform bleiben getrennt. Unterschiedliche tatsächliche Anwendungsinstanzen dürfen trotz gleicher Klassifikation nicht verschmolzen werden; alternative Wege zum selben Katalogeintrag dürfen ihn dagegen nicht duplizieren. Der heutige Ein-Eltern-Baum und die Kategorieformel erfüllen dieses Ziel noch nicht vollständig.

Abnahme: Zwei Wege führen zur gleichen Katalogidentität; unterschiedliche Anforderungsbeiträge behalten ihre Begründungen; mehrere notwendige Anwendungen bleiben möglich; passende Alternativen werden nicht ohne Entscheidung gemeinsam übernommen.

## Implementierungsquellen

[UA-Prompt](../../../taxonomy-analysis/src/main/resources/prompts/UA.txt) · [Kategorie-/Produktauswahl](../../../taxonomy-analysis/src/main/java/com/taxonomy/analysis/service/LlmService.java) · [Katalogmodell](../../../taxonomy-knowledge/src/main/java/com/taxonomy/catalog/model/TaxonomyNode.java)
