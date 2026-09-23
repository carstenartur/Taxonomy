# CP – Capabilities: Gruppierung und Bewertung

[Gemeinsamer Bewertungsvertrag und Implementierungsstand](../TAXONOMY_SCORING.md) · [English](../../en/taxonomies/CP.md)

## Gegenstand und Gruppierungsmaßstab

CP betrachtet funktionale Fähigkeiten, Kapazitäten sowie Fähigkeitsbedarfe oder -lücken. Die Gruppierung folgt dem Quellkatalog. Eine Fähigkeit beschreibt, was ermöglicht werden soll; sie legt nicht allein fest, mit welchem Prozess, Dienst oder Softwareprodukt dies geschieht. Eine Baumkante ist noch keine Realisierungsbeziehung.

## Bewertung heute

Der CP-Prompt verwendet dieselbe Kategorie-/Elternbudgetverteilung wie BP. Mehrere Fähigkeitskinder können positive Anteile erhalten. Bei Elternwert 60 können zwei gleichgewichtige Kinder je 30 erhalten, obwohl beide vollständig benötigt werden. Eigene und anwendbare Quell-Vorfahrenbeschreibungen bestimmen den LLM-Kontext. Die gemeinsamen Grenzen für Wurzeln, fehlende Antworten und Rundung gelten unverändert.

## Mehrere Treffer und Konsequenzen

Eine Anforderung kann sowohl die Fähigkeit zur Erfassung als auch zur Auswertung benötigen. Gleichzeitiger Bedarf darf nicht mit Wettbewerb um ein Scorebudget verwechselt werden. Umgekehrt belegt eine allgemein passende Fähigkeit weder die Existenz einer entsprechenden Implementierung noch eine Abdeckung sämtlicher Unterfähigkeiten. Dafür braucht es konkrete Beiträge und Beziehungen.

## Zielbild und Grenzen

Fachliche Fähigkeitszerlegung, Anwendungsdomäne und technische Realisierung sind auseinanderzuhalten. Zusätzliche Klassifikationswege dürfen keinen doppelten Fähigkeitsbedarf erzeugen. Konsistenz von Vater und Kind setzt denselben Maßstab und Geltungsbereich voraus; der aktuelle Summenvertrag allein prüft das nicht.

Abnahme: Zwei benötigte Fähigkeiten bleiben gleichzeitig erhalten; ein Anwendungsprodukt wird nicht mit seiner Fähigkeit gleichgesetzt; nur ein begründeter Realisierungsvorschlag erzeugt eine entsprechende Architekturbeziehung.

## Implementierungsquellen

[CP-Prompt](../../../taxonomy-analysis/src/main/resources/prompts/CP.txt) · [Parser](../../../taxonomy-analysis/src/main/java/com/taxonomy/analysis/service/LlmResponseParser.java) · [Beziehungssuchmodell](../../../taxonomy-domain/src/main/java/com/taxonomy/dto/RelationSearchModel.java)
