# WebJar-Auflösung nach dem QA-Merge — 20. November 2026

## Befund

Nach dem Merge der Ausgabeformat-QA in `77eaa2f9218d34e0c1981f50df4340ea84884eda`
scheiterte der Java-Build des [CodeQL-Laufs 44688886220](https://github.com/carstenartur/Taxonomy/actions/runs/44688886220)
vor der eigentlichen Analyse. Der erste Versuch und genau ein kontrollierter
Neustart meldeten dieselben 35 nicht auflösbaren WebJar-Versionsbereiche. Das
anschließend fehlende SARIF-Archiv war ein Folgefehler des abgebrochenen Builds.

Der [Security-Lauf auf demselben main-Commit](https://github.com/carstenartur/Taxonomy/actions/runs/44688886307)
hatte die Anwendung erfolgreich gebaut. Auch der
[CodeQL-Lauf des zuvor vollständig geprüften PR-Quellbaums](https://github.com/carstenartur/Taxonomy/actions/runs/44682375346)
war erfolgreich. Alle verglichenen Jobs verwendeten denselben Maven-Cache-Key
und dieselbe Archivgröße. Die Logs belegen daher keine bestimmte Netzwerk-
oder Cache-Ursache. Sie belegen einen Fehler bei der dynamischen Versionsauflösung.

## Unveränderter Bibliotheksbestand

Das tatsächlich gebaute Anwendungsartefakt aus
[main-CI 44688886224](https://github.com/carstenartur/Taxonomy/actions/runs/44688886224)
wurde anhand von ZIP-Digest, Manifest-Commit, Quellbaum und JAR-Digest geprüft:

- Artefakt: `taxonomy-ui-application`, ID `14607811909`.
- ZIP-SHA-256: `1b47109c6f5d040a878100751b86a8bf744842a519cdb9a91008fc769074286d`.
- App-JAR-SHA-256: `8ce338857e0f4b9e44614775907017e68e8abcf18b0bffbc684da435c69ab4d3`.
- Die eingebetteten POM-Properties aller 52 NPM-WebJars stimmen exakt mit dem
  geprüften PR-Testklassenpfad überein. Alle 52 JAR-Dateien sind außerdem
  bytegleich mit den lokalen Eingaben des Resolver-Nachweises.

Vier WebJars sind direkte, bereits versionsgebundene Anwendungsabhängigkeiten.
48 weitere werden über Versionsbereiche erreicht; zwei davon waren bereits
verwaltet. Die Korrektur ergänzt genau die 46 fehlenden festen Versionen im
bestehenden `dependencyManagement`, darunter elf tiefer liegende Abhängigkeiten.
Sie erhält ausdrücklich auch die tatsächlich ausgewählte Version
`babel__runtime:8.0.0-alpha.6`; eine Änderung der Bibliotheksversionen ist nicht
Teil dieser Korrektur. Die vier optionalen jsPDF-Integrationen bleiben abwesend.

## Tatsächlicher Maven-Nachweis

Ein isolierter Datei-Repository-Spiegel enthält die vorhandenen festen Artefakte
und POMs, aber keine WebJar-Versionslisten. Beide Versuche verwenden jeweils
ein neues leeres Consumer-Repository. Der Spiegel ist das einzige konfigurierte
Repository; der Nachweis benötigt keine externen Dienste.

| Gleicher Maven-Resolver und gleiche direkte Abhängigkeiten | Ergebnis |
| --- | --- |
| Ursprüngliche zwei verwaltete WebJar-Versionen | Fehlgeschlagen; alle 35 Fehlermeldungen stimmen exakt mit CodeQL überein. |
| Vollständige 48 verwaltete transitive WebJar-Versionen | Erfolgreich; exakt dieselben 52 Artefakt-/Versionspaare wie im gebauten main-JAR; weiterhin keine WebJar-Versionslisten vorhanden. |

Damit ist nachgewiesen, dass die ausgewählte Abhängigkeitskette ohne dynamische
Versionslisten auflösbar ist. Daraus folgt weiterhin keine Behauptung über den
konkreten Auslöser der fehlenden Versionsinformation auf den GitHub-Runnern.

## Dauerhafte Regression

`OfflineWebAssetsTest` liest die tatsächlich auf dem Testklassenpfad vorhandenen
WebJar-JARs und ihre eingebetteten POMs. Jeder aktive transitive Versionsbereich
muss eine feste verwaltete Version besitzen, die mit der tatsächlich ausgewählten
Version übereinstimmt. Der Test umfasst tiefere Abhängigkeiten, berücksichtigt
optionale Abhängigkeiten und übernimmt keine kopierte Liste der 46 Pins.
Eine zweite Prüfung erhält die Abwesenheit der optionalen jsPDF-Integrationen.
Die bestehenden Prüfungen für Offline-Browserdateien bleiben unverändert.

Vor der POM-Korrektur meldete die Klasse genau die 46 fehlenden Vorgaben:
fünf Tests, ein erwarteter Fehler, keine Ausführungsfehler und keine Skips.
Nach der Korrektur bestanden mit denselben Maven-Optionen alle fünf Tests ohne
Fehler oder Skips. Der normale Reaktor verwendete JDK 25 und keinen Buildcache.
Anschließend bestand auch der normale Paketbau mit der Modulauswahl und den
Test-/UI-/Modelloptionen des CodeQL-Jobs. Alle 52 WebJars im neu gebauten
Anwendungs-JAR sind bytegleich mit dem verifizierten main-Artefakt.
Die tatsächlichen Resolver- und Maven-Logs sowie die Hash- und Bestandslisten
liegen während der Abnahme unter `target/webjar-resolution-qa/`.

Anwendungscode, sichtbare Ausgaben, Workflow-Auswahl, Security-Schwellen und
fachliche Assertions werden durch diese Buildkorrektur nicht verändert.
