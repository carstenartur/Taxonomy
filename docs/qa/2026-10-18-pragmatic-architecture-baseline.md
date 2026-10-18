# Taxonomy: begrenzter Architekturentwurf und Ressourcenbedarf

Stand: 18. Oktober 2026. Dies ist ein Untersuchungsbericht mit einem vorgeschlagenen Abnahmerahmen. Der begrenzte Entwurfsmodus ist noch nicht implementiert oder als Ende-zu-Ende-Erfolg nachgewiesen.

## Ergebnis

Im untersuchten Flood-Fall sind bereits alle **22 Knoten**, die die unabhängige Referenz für ihre **19 Kernbeziehungen** benötigt, positiv bewertet. Der gespeicherte Teilentwurf enthält 50 Elemente und 28 Beziehungen, darunter 14 dieser 19 Kernbeziehungen. Die verbleibende globale Relationssuche hat trotzdem noch 161 geplante Suchen offen.

Das spricht dafür, einen ersten fachlich prüfbaren Entwurf aus einem kleinen, funktionsbezogenen Kandidatensatz zu erzeugen und die zusätzliche Katalogsuche gezielt nachzuladen. Es beweist noch nicht, dass ein bestimmtes Zeit- oder Speicherbudget bei gleicher Kernabdeckung eingehalten wird. Die Referenz ist eine unabhängig verfasste fachliche Annotation für diesen Fall, keine allgemeine Architektur-Wahrheit.

## Wiederhergestellter Arbeitsstand

Der unterbrochene lokale Stand wurde gefunden und vollständig als Quellstand gesichert:

- 93 geänderte und 60 neue Dateien; alle 153 Inhalte gegen das Original geprüft.
- Git-Baum: `1ffc3f7f9f73540d773acdad5227eab9bb13e511`.
- Ausgangsstand: `de3feec0f12dfc66a5444fc6e2ab12514c926ca9`.
- Veröffentlichter Wiederherstellungscommit: `ceb3b1b632cc589175a7d500ecf29631373451e5`.
- Entwurfs-PR: [#1251](https://github.com/carstenartur/Taxonomy/pull/1251).

Enthalten sind gerenderte-Prompt-Verträge und fachliche Antworten, unabhängige Referenzen, Fortsetzung und kleinere Datenbanklesungen, komprimierte Checkpoints und die Sparx-v3-Anbindung. Modelle und erzeugte Diagnosearchive wurden nicht in Git aufgenommen.

## Frische Prüfung nach der Wiederherstellung

Der vorhandene gezielte Maven-Lauf wurde auf der bytegleichen Kopie erneut ausgeführt:

- **190 Tests in 20 angeforderten Klassen; null Fehler, Fehlschläge oder Skips.**
- Maven-Ergebnis: BUILD SUCCESS; 104,093 Sekunden einschließlich Reactor.
- Java 21.0.12.1, Maven 3.9.16, Profil `test-local`.
- Der erste Versuch scheiterte an der nicht funktionierenden dynamischen Mockito-Agent-Anbindung im Prozessnamensraum. Die Wiederholung verwendete denselben Mockito-Premain-Agent beim JVM-Start; Testauswahl und Produktcode blieben unverändert.
- Dies ist kein kanonischer Gesamtbuild und keine vollständige Szenarioabnahme.

Die aufbewahrte vollständige Szenarioabnahme vom 18. Oktober, 01:26 UTC, scheiterte am unveränderten 300-Sekunden-Prozesslimit. Ein früherer Versuch hatte nach 33 Fortsetzungen den Speicherschutz erreicht. Es wurde kein vollständiger Flood-Sparx-Export als fachlich ausreichend akzeptiert.

## Tatsächlich vorhandene Ablaufdaten

Ausgewertet wurden die erhaltenen `llm-calls.json`, `prompt-catalogue.json`, `snapshot.json`, `operation.json`, `continuation-history.json` und originalen JUnit-Berichte des unterbrochenen Laufs. Die Antworten waren fachlich verfasste Testantworten an der vorhandenen HTTP-Grenze; es war kein Gemma- oder anderer Live-Modelllauf.

| Größe | Beobachtung |
|---|---:|
| Verarbeitete vorbereitete Modellantworten | 1.482 |
| Klassifikation und Begründungen | 114 |
| Extraktion von Quellbeiträgen | 57 |
| Navigation im Zielkatalog | 1.201 |
| Getrennte Relationsverifikation | 110 |
| Gesamte Promptzeichen über alle Aufrufe | 16.110.792 |
| Navigation ausschließlich zu Kandidaten ohne positive Knotenbewertung | 602 Aufrufe |
| Promptzeichen dieser 602 Aufrufe | 7.084.265 |
| Bewertete Relationsquellen im letzten Snapshot | 75 von 75 |
| Geplante Suchen | 433 |
| Abgeschlossene / offene / ungeklärte Suchen | 268 / 161 / 4 |
| Elemente / Beziehungen im Teilentwurf | 50 / 28 |
| Vorhandene unabhängige Kernbeziehungen | 14 von 19 |

Die 602 Aufrufe sind ein konkreter Ansatzpunkt für eine bewusst beschränkte Kandidatensuche. Außerhalb des Kandidatensatzes können zusätzliche sinnvolle Treffer liegen; ihr Weglassen wäre eine erklärte Grenze eines Entwurfsmodus. Die Zahlen sind keine nachgewiesene Beschleunigung.

Das Limit von 24 Relationsaufrufen gilt im beobachteten Ablauf je Fortsetzung. Die vielen Fortsetzungen begrenzen den Gesamtaufwand deshalb nicht auf 24 Aufrufe. Ein pragmatisches Gesamtbudget muss über den ganzen Auftrag erhalten bleiben.

## Fachliche Teilprüfung

Der tatsächlich gerenderte Prompt für `BP-1060` fordert die Verteilung von 27 Punkten auf fünf angebotene Kinder. Die erhaltene Antwort verteilt 20 auf `BP-1017 Acquire Data` und 7 auf `BP-1866 Prepare Data`; Analyse, Problemdefinition und das Berichten eigener Analyseergebnisse erhalten jeweils eine begründete Null.

Diese Entscheidung ist für F1/F5 fachlich vertretbar: veröffentlichte Informationen abrufen sowie Quellen-, Zeit- und Aktualitätskontext erhalten. Eigene Prognosen und neue analytische Erkenntnisse sind nicht angefordert. Die Summe bleibt 27; die 20 Punkte sind ein Anteil des Elternbudgets und keine 20-prozentige Funktionsabdeckung. Diese Stichprobe ersetzt keine erneute Gesamtprüfung aller Pfade.

Die fünf im aktuellen Teilentwurf fehlenden Kernverbindungen sind:

| Bezug | Fehlende Verbindung | Fachliche Lücke |
|---|---|---|
| R06 | `BP-1535 USES CR-1098` | Zustellung nach Gebiet und Kanal routen |
| R07 | `BP-1535 USES CR-1097` | Dauerhafte Zustellwarteschlange |
| R12 | `BP-1521 USES CR-1022` | Kontakt-/Kontoverwaltung mit Identitätslebenszyklus |
| R13 | `BP-1530 USES CR-1022` | Konto löschen beziehungsweise deaktivieren |
| R17 | `BP-1535 USES CR-1198` | Nachvollziehbare Zustellhistorie |

Diese fünf Beziehungen fehlen bereits in der erhaltenen Relationsmenge; es handelt sich nicht um ein bloßes Darstellungsproblem. Ihre Endpunkte wurden bereits positiv bewertet. Die Kernbeziehungen benötigen insgesamt 22 Knoten; alle 22 sind im vorhandenen Scoring positiv. Der kleinste dieser Werte beträgt 4. Eine pauschale Grenze von 5 würde somit einen benötigten Knoten ausschließen.

Die Größen 14/19 und 22/22 messen genau diese Referenzeigenschaften. Sie sind kein allgemeiner Prozentwert für Architekturqualität. Offene Produktidentität, Aktualitätsschwelle und konkrete technische Ausgestaltung müssen weiter sichtbar bleiben.

## Kleine lokale Ressourcenprobe

Die unveränderte, über Hashes an denselben main-Stand gebundene CI-Anwendung wurde separat mit vollständigem Katalog, In-Memory-HSQLDB, SerialGC und abgeschalteten optionalen Embeddings gestartet. Die Arbeitszeiterfassungs-Demo führte einen normalen R0-Analyseauftrag und das Laden seines gespeicherten Snapshots aus.

| Konfiguration | Ergebnis |
|---|---|
| 220 MiB maximaler Heap | Heap-OutOfMemory während des Katalogstarts |
| 384 MiB maximaler Heap | Start und Demoanalyse erfolgreich |
| Start bis erstes Bereitschaftssignal bei 384 MiB | 19,762 Sekunden |
| Demoanalyse einschließlich Statusbeobachtung | 1,833 Sekunden |
| CPU-Zeit des Java-Prozesses während der Demoanalyse | 3,12 Sekunden |
| Beobachtete maximale Prozess-RSS bei 384 MiB | 808,92 MiB |

Die RSS wurde am verifizierten Java-Hostprozess alle 200 ms aus `/proc` gemessen. Ein optionaler Heap-Diagnoseaufruf war im Prozessnamensraum nicht verfügbar; aus ihm werden keine Heapbelegungswerte abgeleitet. Die Probe ist ein einzelner lokaler Lauf, keine Kapazitätsfreigabe, kein Vergleich mit PostgreSQL und kein erfolgreicher realistischer Flood-Lauf. Inferenzserver, GPU und Browser sind nicht Bestandteil dieser Prozessmessung.

Die vorhandene CI-Observability-Probe verwendet dagegen `-Xms1024m -Xmx1024m -XX:+AlwaysPreTouch` und wiederholte Relationsabfragen. Ihre ungefähr 1.463 MiB cgroup-Anonymous-Speicher sind deshalb kein Mindestbedarf einer einzelnen Architektur.

## Vorgeschlagener nächster Abnahmerahmen

Ziel ist ein nachvollziehbarer erster Architekturentwurf mit geringer Detailtiefe. Jede wichtige Anforderung erhält eine zugeordnete Funktion oder eine konkret benannte offene Bearbeitung. Es werden keine zusätzlichen Implementierungsdetails erfunden, um ein vollständig wirkendes Bild zu erzeugen.

Als erste zu überprüfende Zielgrößen:

- Etwa 20–30 funktionale Bausteine und höchstens 40 Kernbeziehungen.
- Ein Analysedurchlauf, danach ausdrückliche gezielte Verfeinerung.
- Zunächst Beziehungen zwischen bereits passenden Funktionsknoten vorschlagen und getrennt verifizieren. Zusätzliche Katalogsuche bleibt als spätere Aufgabe ausgewiesen.
- Funktionsabdeckung und unverzichtbare Abhängigkeiten bestimmen die Reihenfolge; ein niedriger Prozentanteil entfernt keine notwendige Funktion.
- Höchstens 120 generative Aufrufe für den gesamten Analyseauftrag einschließlich Fortsetzungen und Korrekturversuchen; keine automatische neue Budgetzuteilung je Runde.
- Fünf Minuten für die Analyse und zunächst höchstens 1 GiB RSS für den Anwendungsprozess. Inferenz- und sonstige externe Ressourcen separat ausweisen.
- Bei ausgeschöpftem Budget einen gespeicherten, exportierbaren Teilentwurf mit konkreten Restlücken liefern. Der Status bleibt unvollständig.
- Die ursprüngliche vollständige Abnahme und deren Referenzen bleiben erhalten. Eine erfolgreiche Entwurfsabnahme darf nicht als vollständige Suche oder geprüfte Enterprise-Architect-Desktop-Kompatibilität bezeichnet werden.

Diese Budgets sind **Zielvorgaben für die nächste Probe**, keine bereits erreichten Leistungswerte. Im Flood-Fall muss insbesondere geprüft werden, ob die fünf fehlenden Kernbeziehungen durch gezielte Arbeit statt weiterer breiter Navigation innerhalb des Budgets hinzukommen. Erst daraus folgt ein belastbarer Nachweis für einen brauchbaren Start mit realistischen Ressourcen.
