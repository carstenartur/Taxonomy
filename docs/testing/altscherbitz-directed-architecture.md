# Gerichtete Beziehungen im Altscherbitz-Referenzfall

Die öffentliche Bekanntmachung 230039 des Sächsischen Krankenhauses Altscherbitz beschreibt eine Backup-Beschaffung. Der Referenzfall legt drei schrittweise präzisierte Anforderungsfassungen zugrunde. Für jede Fassung trennt die Prüfung die im Bericht vom 27.11.2026 tatsächlich vergebenen Knotenscores von der späteren Beurteilung einzelner gerichteter Beziehungen. Die Originalberichte liefen mit `TAXONOMIES_ONLY` und enthielten keine Architektur.

| Anforderungsstand | Unabhängige Erwartung für die ausgewählten Beziehungen |
| --- | --- |
| Produkt | Lizenzierung und Gerätebeschaffung belegen noch keinen konkreten Betriebsprozess. |
| Speicherdienst | Zweiter Standort, Replikation und Aufbewahrung begründen eine Speicherfunktion. Die konkrete Prozess- und Anwendungszuordnung bleibt offen. |
| Betrieb | Der Speicherdienst CR-1091 unterstützt die beschriebenen Backup-Betriebsaufgaben. Eine gerichtete `SUPPORTS`-Beziehung zu BP-1503 (CIS Service Operations) oder BP-2009 (SMC Service Operation) erfüllt die Erwartung. |

Die Bekanntmachung lässt offen, wer eine Wiederherstellung anfordert oder genehmigt. Fest zugewiesene Rollen für diese Aufgaben sowie eine als erforderlich dargestellte spezielle Wiederherstellungs- oder maritime Anwendung sind im geprüften Ausschnitt unbelegt. Eine Optimierung der bestehenden Jobs, Richtlinien und Prozesse ist an Bedarf und Abstimmung mit dem Auftraggeber gebunden. Die fachlichen Erwartungen stammen aus der Referenzauswertung. Das Krankenhaus hat die Katalogzuordnungen nicht bestätigt.

## Ergebnis der getrennt erhobenen Antworten

| Stand | Protokollaufrufe | Projizierte Beziehungen | Ergebnis gegen die vorab festgelegten Tripel |
| --- | ---: | ---: | --- |
| Produkt | 23 | 0 | Offen. Die Suche ließ Zuordnungen ungeklärt. |
| Speicherdienst | 24 | 0 | Offen. Die Speicherfunktion wurde untersucht, eine konkrete Pflichtbeziehung ergab sich im Ausschnitt noch nicht. |
| Betrieb | 60 | 4 | Abweichung. Beide erwarteten `SUPPORTS`-Alternativen wurden beobachtet. Zusätzlich projizierte Taxonomy `BP-1503 USES CR-1091` und `BP-2009 USES CR-1091`. |

`USES` beschreibt hier die Nutzung des Speicherdienstes aus Sicht des Prozesses und ist im Taxonomy-Metamodell für diese Richtung zulässig. Der vorab festgelegte Vergleich der exakten Tripel zählt die beiden zusätzlichen Beziehungen als Abweichungen. Ob beide Perspektiven nebeneinander fachlich erwünscht sind, bleibt eine Reviewentscheidung. Die vier Beziehungen enthalten prüfbare Zitate der jeweiligen Anforderungsfassung. Im geprüften Ausschnitt entstand in keiner Stufe eine obligatorische Zuordnung einer konkreten Rolle oder Wiederherstellungsanwendung. Alle drei Suchen enthielten offene Entscheidungen sowie angeforderte Verfeinerungen außerhalb des begrenzten Katalogausschnitts. Die Auswertung weist deshalb keine Präzisions- oder Recall-Prozente aus. Der grüne Regressionstest prüft auch, dass die Abweichung der dritten Stufe im Ergebnis sichtbar bleibt.

## Ausführen und Ergebnisse lesen

```bash
./mvnw -B -ntp -pl taxonomy-app -am test \
  -Dtest=AltscherbitzArchitectureStudyTest -Dsurefire.failIfNoSpecifiedTests=false
```

Unter `taxonomy-app/target/altscherbitz-architecture/` fasst `study-summary.json` die drei Stufen zusammen. Dort stehen außerdem je Stand die tatsächliche Projektion mit Suchbericht (`*-observation.json`) und die Auswertung (`*-evaluation.json`). Jede Beobachtung nennt den ursprünglichen Bericht mit SHA-256, Snapshot-Kennung und extrahierten Scores. Die Auswertung enthält die tatsächlich projizierten Tripel, unbelegte Behauptungen, offene Fragen, nicht klassifizierte Beziehungen und das Ergebnis für den untersuchten Ausschnitt. Bei unvollständigen Suchergebnissen bleiben Präzision und Recall leer. Eine bereits beobachtete falsche Beziehung wird auch in einem Teilergebnis ausgewiesen.

Das Verfahren verwendet den produktiven `RequirementRelationSearch`, die normalen SOURCES-, NAVIGATE- und VERIFY-Prompts und die reguläre Sichtprojektion. Als Suchraum dienen sechs echte Katalogelemente aus BP, BR, CR und UA unter den vier entsprechenden Wurzeln. Der kleine Suchraum verkürzt die Hierarchie für einen reproduzierbaren Gegenversuch. Die Kandidaten bleiben in allen Stufen gleich. Nur tatsächlich gespeicherte positive Scores innerhalb dieses Ausschnitts starten eine Suche. Die angebotene Rolle BR-1220 sowie die maritime Anwendung UA-1347 prüfen auf unbegründete Zuweisungen. Eine automatische Einstufung aller anderen Katalogelemente oder eine vollständige Analyse der Vergabe wird damit nicht gemessen.

Beim Erheben der Antworten erhielt eine getrennte KI-Assistenz die Anforderung und das vom produktiven Protokoll angebotene Katalogmaterial. Der Auswertungsmaßstab ist in einer separaten Datei hinterlegt und wird erst auf das projizierte Ergebnis angewandt. Maven wiederholt die festgehaltenen Antworten anhand des SHA-256 der vollständigen Anfrage. Geänderte Prompts erfordern daher eine neue Erhebung. Ein erfolgreicher Maven-Lauf belegt die reproduzierbare Verarbeitung dieser Antworten.
