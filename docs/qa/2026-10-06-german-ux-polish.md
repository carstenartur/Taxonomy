# QA-Nachtrag: Kennzahlen und schmale Analyseansichten

## Ausgangspunkt

Dieser begrenzte Nachtrag zu [PR #1177](https://github.com/carstenartur/Taxonomy/pull/1177)
behebt die beiden im [Folgebericht](2026-10-06-german-ux-architecture-followup.md)
noch genannten redaktionellen und visuellen Befunde. Grundlage ist Commit
`4f1410eeba1533665083f5aedac91002e9666a2d` mit Quellbaum
`674b7ce9c48145a3982f60e6afb305a720414835`.

## 1. Einzahl in den Workbench-Kennzahlen

`updateKpis()` verwendete immer die Mehrzahl. Damit erschienen unter anderem
„1 Elemente“, „1 Beziehungen“ und „1 Ebenen“. Die Funktion nutzt jetzt bei genau
einem Eintrag die vorhandenen übersetzten Singularbezeichnungen der Detailansicht.
Bei null und mehreren Einträgen bleibt die Mehrzahl erhalten. Die Zählung selbst,
die Filter und die zugrunde liegenden fachlichen Daten bleiben unverändert.

Die neuen Tests führen den tatsächlichen Produktionshandler mit den vorhandenen
englischen und deutschen Ressourcen für 0, 1, 2 und 11 aus. Sie prüfen außerdem,
dass eine nachfolgende leere Ansicht wieder null und die Mehrzahl zeigt.
Es werden keine zusätzlichen Sprachschlüssel oder sprachabhängige Wortendungen
benötigt.

## 2. Lesbare Workflow-Schritte in schmalen Spalten

Die Stufenanzeige erzwang vier Spalten, auch wenn die eigentliche Analysefläche
viel schmaler als das Browserfenster war. Bei geringer Höhe war dies ebenfalls
fest vorgeschrieben. Deutsche Wörter wurden dadurch mehrfach zerlegt oder
ragten über den verfügbaren Textbereich hinaus.

Das CSS verwendet jetzt ein an der verfügbaren Spaltenbreite ausgerichtetes
Raster mit `auto-fit`. Die Mindestbreite beträgt regulär 9 rem und im kompakten
Höhenprofil 8 rem, jeweils begrenzt auf die verfügbare Breite. Die bestehende
einspaltige mobile Hochformatdarstellung bleibt erhalten. Bei wenig Platz
wechseln die kompakten Schritte auf zwei beziehungsweise bei Bedarf eine Spalte;
bei ausreichend Platz bleiben mehrere Schritte nebeneinander.

## 3. Nachweise und Grenzen dieses Nachtrags

Vor der Korrektur scheiterten vier der zehn neuen Node-Fälle an den vorgesehenen
Assertions: zwei Singularfälle und zwei CSS-Verträge. Nach der Korrektur bestehen
alle zehn. Zusammen mit den sechs unveränderten Lokalisierungsregressionen
bestehen **16 von 16 Tests, ohne Fehler oder Skips**. Die neue Testdatei wird aus
der bestehenden `ui-localization-regression.test.mjs` geladen und läuft damit in
den vorhandenen `test:ui-localization`- und UI-Verifikationskommandos mit.

Reproduktion im vollständigen Repository:

```sh
cd .github
npm run test:ui-localization
```

Zusätzlich wurde das Layout in Chromium mit den echten Produktions-CSS-Dateien
und einer isolierten, strukturgleichen Stufenanzeige geprüft. Die sieben Fälle
umfassen 320/360/390 x 250, 667 x 375, 390 x 844 sowie Desktopfenster mit einer
400 beziehungsweise 610 Pixel breiten Analysefläche. Vorher verletzten fünf Fälle
die Prüfung auf vollständig passende Schritttitel; danach bestehen alle sieben
ohne horizontalen Dokumentüberlauf oder aufgebrochene Titelwörter.

Diese Zusatzmessung ist kein vollständiger Lauf der Anwendung und keine erneute
Axe- oder Screenreader-Abnahme. Es wurden Systemschrift und kontrollierter
Beispielinhalt verwendet. Lokal standen Node 22.16.0 und Chromium bereit, nicht
die vollständige Maven-/Node-24-CI-Umgebung. Die lokale Prüfung nutzte die aus dem
verifizierten CI-Paket gewonnenen Ressourcen und die vom selben Commit gelesenen
Testquellen; ein neuer vollständiger Build wird damit nicht behauptet.

Das zugrunde liegende CI-Artefakt 11430535838 aus Lauf 37501370701 hat SHA-256
`a531bad592889f983fcd6eb88ceb7ee083fcb23c5e139e21f1270ac7cea76046`.
Das enthaltene JAR hat SHA-256
`454fd2bc4b6618346b6a5bdc68a47c961c2290f5f0b3ee52534afab0f43016c4`.
Sein Manifest verweist auf den Test-Merge `2a0fefcc60860fba301bb8133d09c33f038845f8`
und denselben Quellbaum wie der oben genannte Ausgangscommit. Beide Prüfsummen
wurden lokal bestätigt. Die neuen Änderungen sind nicht Bestandteil dieses
älteren Pakets; dessen CI-Ergebnisse zertifizieren nicht den neuen Nachtrag.

Der Nachtrag verändert zwei Produktionsdateien und ergänzt Regressionstests.
Die umfangreicheren Ergebnisse des ersten und zweiten QA-Berichts bleiben
historische Nachweise ihrer jeweiligen Prüfstände. Insbesondere bestehen die
Abgrenzungen zur vollständigen CI-Matrix, zum Speicherbedarf großer
Analyseergebnisse, zum 5.000-Aufträge-Wiederherstellungslimit und zur manuellen
Barrierefreiheitsprüfung fort.
