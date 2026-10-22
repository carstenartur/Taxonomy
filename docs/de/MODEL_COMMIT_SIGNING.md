# Signaturen für Modell-Commits

Taxonomy kann neue Git-Commits des Modells in persönlichen Workspaces und zentralen
Projekt-/Fork-Repositories signieren. Es handelt sich um eine vom Betreiber
verwaltete **Dienstsignatur durch Taxonomy im Auftrag des Benutzers**. Sie bedeutet
nicht, dass der Benutzer einen persönlichen privaten Schlüssel besitzt. Das
Git-Autorenfeld nennt einen Akteur; die Signaturprüfung überprüft das gespeicherte
Objekt anhand eines vertrauenswürdigen Signaturschlüssels.

## Laufzeitkonfiguration

Die Signierung ist standardmäßig ausgeschaltet. Stellen Sie einen privaten
OpenPGP-Schlüssel über die Geheimnisverwaltung der Betriebsumgebung bereit und
binden Sie seine Datei nur für das Anwendungskonto lesbar ein. Eine erforderliche
Passphrase wird ebenfalls als Laufzeitgeheimnis bereitgestellt. Taxonomy erzeugt
keine Produktionsschlüssel und nimmt keine privaten Schlüssel über die
Modellhistorien-API entgegen.

| Spring-Eigenschaft | Umgebungsvariable | Standard / Bedeutung |
|---|---|---|
| `taxonomy.git.signing.enabled` | `TAXONOMY_GIT_SIGNING_ENABLED` | `false`; neue Commits signieren |
| `taxonomy.git.signing.secret-key-file` | `TAXONOMY_GIT_SIGNING_SECRET_KEY_FILE` | Leer; Pfad zur privaten OpenPGP-Schlüsseldatei |
| `taxonomy.git.signing.passphrase` | `TAXONOMY_GIT_SIGNING_PASSPHRASE` | Leer; Passphrase, falls der Schlüssel sie benötigt |
| `taxonomy.git.signing.trusted-public-key-files` | `TAXONOMY_GIT_SIGNING_TRUSTED_PUBLIC_KEY_FILES` | Leer; durch Kommas getrennte Pfade vertrauenswürdiger öffentlicher Schlüssel |
| `taxonomy.git.signing.trusted-public-key-directory` | `TAXONOMY_GIT_SIGNING_TRUSTED_PUBLIC_KEY_DIRECTORY` | Leer; Verzeichnis vertrauenswürdiger öffentlicher Schlüssel |
| `taxonomy.git.signing.require-signatures` | `TAXONOMY_GIT_SIGNING_REQUIRE_SIGNATURES` | `false`; bei `true` unsignierte eingehende Commits ablehnen |

Binden Sie beispielsweise den Dienstschlüssel unter
`/run/secrets/taxonomy-model-signing.asc` ein, setzen Sie
`TAXONOMY_GIT_SIGNING_ENABLED=true` und tragen Sie den Pfad in
`TAXONOMY_GIT_SIGNING_SECRET_KEY_FILE` ein. Stellen Sie eine Passphrase separat über
die Geheimnisverwaltung bereit; schreiben Sie sie nicht in Shell-Verläufe oder
versionierte Konfiguration. Bei aktivierter Signierung führen fehlende,
fehlerhafte, unbrauchbare oder nicht entsperrbare Schlüssel zum Fehler statt zu
einem stillen Rückfall auf unsignierte Commits.

Dem öffentlichen Anteil des konfigurierten Dienstschlüssels wird automatisch
vertraut. Weitere öffentliche Schlüsseldateien unterstützen andere Betreiber und
Schlüsselwechsel. Sie werden auch bei ausgeschalteter lokaler Signierung zur
Prüfung geladen. Vertrauen entsteht durch explizite Konfiguration, nicht durch
Autorenname, E-Mail-Adresse oder Remote-URL.

Private Schlüssel und Passphrasen gehören außerhalb sämtlicher Modell- und
Quellcode-Repositories, Provenienzdaten, HTTP-Antworten, Protokolle, Testdaten und
Anwendungssicherungsexporte. Sichern Sie Geheimnisse separat nach dem
Wiederherstellungsverfahren der Betriebsumgebung. Die Wiederherstellung der
Anwendungsdatenbank stellt keine eingebundene Laufzeit-Schlüsseldatei wieder her.

## Eingehende Historie und Transport

Die Standardrichtlinie akzeptiert unsignierte ältere Commits, lehnt aber ungültige,
nicht vertrauenswürdige und nicht unterstützte Signaturen ab. Mit
`require-signatures=true` werden auch unsignierte eingehende Commits abgelehnt.
Neu eingeführte Vorgänger-Commits werden vor der Integration in den aktiven Branch
geprüft. Prüfen Sie vor dem Einschalten der strikten Richtlinie die öffentlichen
Schlüssel aller beteiligten Betreiber, auch für ältere Commits in eingehenden
Historien. Signierung und Annahmerichtlinie sind getrennte Einstellungen und
sollten zusammen geplant werden.

Normales externes Git-Fetch/-Push überträgt unveränderte Git-Objekte und erhält
deren Signaturen. Repository-bezogene externe Synchronisierung akzeptiert ein
optionales `repositoryId` bei Fetch, Push, Full-Sync, Status und Konfiguration;
ohne Parameter bleibt das primäre Repository ausgewählt. Remote-Konfiguration und
Berechtigungen bleiben dem gewählten Repository zugeordnet. Zugangsdaten werden
über `taxonomy.dsl.git.remotes.<repositoryId>.username` und `.token` bereitgestellt.
Der ältere Rückfall auf `TAXONOMY_EXTERNAL_GIT_USERNAME` /
`TAXONOMY_EXTERNAL_GIT_TOKEN` gilt ausschließlich für das primäre Repository.
Diese Transportzugangsdaten sind unabhängig von OpenPGP-Signaturschlüsseln.

Semantische Veröffentlichung zwischen Repositories bleibt eine Snapshot-Operation.
Publish, Fork und andere semantische Übernahmen erzeugen einen **neuen
Ziel-Commit** mit Quellnachweisen in `.taxonomy-provenance.json`. Die Zielsignatur
authentifiziert dieses neue Objekt. Eine Quellsignatur wird dadurch nicht zu
einer Signatur über das Zielobjekt. Der Quellnachweis enthält begrenzte rohe Daten
des ursprünglichen Commits und seines Root-Trees. Geprüft werden Commit-ID,
Originalsignatur, Tree-Hash und die DSL-Blob-ID im Tree. Repository-, Branch- und
Integrationsbasis-Angaben sind Aussagen des signierenden Zieldienstes; die
Originalsignatur allein beweist weder diesen Speicherort noch dessen Abstammung.
Der Nachweis betrifft eine Übernahme. Er kopiert frühere Nachweise nicht rekursiv
und behauptet nicht, dass ein zusammengeführtes Modell unverändert der Quelle
entspricht. Eine andere Commit-ID am Ziel ist zu erwarten.
Bestehende Commits werden weder automatisch umgeschrieben
noch nachträglich signiert.

Bei einem Merge innerhalb derselben Git-Historie bleiben die Transfernachweise
in den ursprünglichen Eltern-Commits. Der neue signierte Merge verweist auf beide
Eltern und enthält keinen eigenen Transfernachweis. Die reservierte Nachweisdatei
wird dabei nicht textuell zusammengeführt; Konflikte in anderen Dateien bleiben
unverändert wirksam.

## Historie lesen

Die Versionshistorie zeigt den Prüfstatus jedes gespeicherten Commits. Aufklappbare
Details enthalten Unterzeichner, Schlüssel-Fingerabdruck und Prüfinformationen.
Der Git-Autor bleibt unabhängig davon sichtbar.

| Status | Bedeutung |
|---|---|
| Nicht signiert | Keine Signatur; gilt auch für ältere Datensätze ohne Signaturfeld |
| Signatur verifiziert | Kryptografisch gültig und durch die konfigurierte Vertrauensliste akzeptiert |
| Signaturschlüssel nicht vertrauenswürdig | Signatur vorhanden, Schlüssel aber aktuell nicht als vertrauenswürdig akzeptiert |
| Ungültige Signatur | Signaturprüfung fehlgeschlagen |
| Signatur nicht unterstützt | Format oder Algorithmus ist mit dieser Installation nicht prüfbar |

Nur verifizierte Signaturen erhalten eine grüne Kennzeichnung. Verifizierte
Taxonomy-Dienstsignaturen tragen den Text „durch Taxonomy im Auftrag des Benutzers“;
externe Signaturen werden allgemein als „Externe Signatur“ bezeichnet. Der Status
hängt von der aktuellen Vertrauenskonfiguration ab. Er belegt weder persönlichen
Schlüsselbesitz noch eine fachliche Freigabe des Modellinhalts.

## Schlüsselwechsel

1. Neuen Schlüssel über das Geheimnisverfahren bereitstellen und prüfen.
2. Öffentlichen Schlüssel vor dem Wechsel der schreibenden Installation bei allen
   Empfängern als vertrauenswürdig konfigurieren.
3. Laufzeitpfad und Passphrase umstellen und Anwendung neu starten. Fingerabdruck
   eines neuen Commits in der Historie kontrollieren und die eingehende
   Synchronisierung mit der gewählten Richtlinie prüfen.
4. Alten öffentlichen Schlüssel behalten, solange seine Signaturen weiterhin
   vertrauenswürdig sind und Historienprüfung oder eingehende Vorgänger ihn
   benötigen. Kompromittierte Schlüssel nach dem Vorfallverfahren entfernen oder
   widerrufen; ihre Commits dürfen nicht weiter als vertrauenswürdig gelten.
   Historische private Schlüssel nur nach der separaten Geheimnisrichtlinie
   aufbewahren.

Ein Schlüsselwechsel schreibt die Historie nicht um. Prüfen Sie nach einer
Wiederherstellung oder Kompromittierung Vertrauensliste und Annahmerichtlinie vor
der erneuten Integration.
