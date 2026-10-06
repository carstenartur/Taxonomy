# QA-Nachtrag: Vertragstests von nativer Browserinstallation entkoppeln

## Befund am 6. Oktober 2026

Ausgangspunkt ist PR #1177, Commit
`c4a84a13f169ba0ee74762cb0014d331ef9354aa`.
Im [UI-Vertragsjob 112475543550](https://github.com/carstenartur/Taxonomy/actions/runs/37523793274/job/112475543550)
endeten die protokollierten Node-Tests um **20:06:05 UTC** erfolgreich. Die
Summen der 36 Testgruppen ergeben **905 Tests ohne Fehler oder Skips**. Separat
protokollierte API-Transport- und Routingprüfungen waren ebenfalls erfolgreich;
sie werden nicht zusätzlich als erfundene Testzahlen gezählt.

Anschließend startete das Maven-Profil `contracts` den Schritt
`prepare-pinned-playwright-runtime`. Dieser installierte WebKit-Systempakete
über den Ubuntu-Paketspiegel. Das Protokoll nennt 126 MB Downloads und
181 neue Pakete. Um **20:25:45 UTC** wurde der Job während dieser Installation
abgebrochen. Der Workflow begrenzt den gesamten Job auf 20 Minuten.
Dies war kein fehlgeschlagener UI-Test: Die unnötige nachgelagerte Installation
verhinderte einen erfolgreichen Abschluss und damit den Start der Browser-Shards.

## Eng begrenzte Korrektur

Aus dem Profil `contracts` in
[ui-verification-pom.xml](../../.github/ui-verification-pom.xml) wird ausschließlich
die native Browserinstallation entfernt. Gepinnter Node, `npm ci`,
API-Transportvertrag und vollständiges `verify:ui-contracts` bleiben erhalten.
Die Vertragstests starten keinen Browser.

Die Profile `shard` und `evidence-gate` bleiben bytegenau unverändert.
Jeder Browser-Shard installiert weiterhin seine gepinnte Laufzeit in
`pre-integration-test`, bevor die eigentliche Browserabnahme in
`integration-test` beginnt. Die abschließende Kontrolle aller gebundenen
Shard-Nachweise bleibt verpflichtend. Die sechs Shards, ihre 18 Szenarien,
Job-Abhängigkeiten, Abbruchgrenzen, Sicherheitsprüfungen und Versionen werden
nicht geändert. Es wird weder ein zusätzlicher Workflow noch ein alternativer
Prüfpfad eingeführt.

Der Browser-Archivcache transportiert keine auf einem anderen Runner
installierten Betriebssystempakete. Die zusätzliche Installation im
Vertragsjob bereitete daher die getrennten Shard-Runner nicht vor.

## Regressionen und tatsächlich ausgeführte Prüfung

Die bestehende Datei
[ui-shard-plan.test.mjs](../../.github/scripts/ui-shard-plan.test.mjs)
erhält drei zusätzliche Fälle. Die vier bisherigen Tests bleiben unverändert.
Damit laufen die neuen Fälle über den schon vorhandenen
`test:ui-shard-plan`-Eintrag in den bestehenden UI-Verifikationskommandos mit.

Vor der POM-Korrektur bestanden sechs der sieben Fälle; der neue Vertragstest
scheiterte gezielt an der Browserinstallation im Vertragsprofil. Nach der
Korrektur bestehen **7 von 7 Tests ohne Fehler, Skips oder Abbrüche**.
Zusätzliche isolierte Negativprüfungen entfernen jeweils den Aufruf der
vollständigen Vertragstests, der Shard-Browserinstallation oder der
abschließenden Nachweiskontrolle. Jede dieser drei Manipulationen erzeugt
genau den erwarteten Testfehler. Danach wurde der unveränderte korrigierte
Stand nochmals mit allen sieben Fällen erfolgreich geprüft.

Die XML-Struktur wurde zusätzlich mit einem XML-Parser geprüft. Die zwei
unangetasteten Profile stimmen textuell und strukturell mit dem Ausgangsstand
überein; die vier verbleibenden Vertragsausführungen sind inhaltlich unverändert.
JavaScript-Syntax und Whitespace der Änderungen sind geprüft.

Reproduktion im vollständigen Repository:

```sh
cd .github
npm run test:ui-shard-plan
```

## Abgrenzung

Lokal wurden die sechs benötigten Quelldateien vom exakten Ausgangscommit
übernommen und über ihre Git-Blob-Hashes abgeglichen. Verwendet wurde
**Node 22.16.0**. Es handelt sich nicht um einen vollständigen Repository-Checkout.
Der versuchte vollständige lokale Aufruf `npm run verify:ui-contracts` konnte
hier mangels lokaler `.github/package.json` nicht starten (ENOENT). Eine neue
lokale vollständige Maven-, Node-24-, Datenbank- oder Browserabnahme wird daher
nicht behauptet. Die 905 erfolgreichen Tests oben gehören zum älteren
Remote-Commit, nicht zum neuen Nachtrag.

Der neue Remote-Lauf muss die aktualisierte Maven-Konfiguration und alle
Prüfungen gemeinsam bestätigen. Ein weiterhin langsamer Paketspiegel kann
nach wie vor die tatsächliche Browserinstallation eines Shards verzögern;
der Nachtrag behebt die unnötige zentrale Abhängigkeit, nicht den externen
Paketspiegel. Die früher dokumentierten Grenzen zu Speicher-/Latenzmessungen,
manueller Barrierefreiheitsabnahme und dem Wiederherstellungslimit bleiben bestehen.
