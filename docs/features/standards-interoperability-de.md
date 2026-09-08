# Standardsbasierte Werkzeugintegration

Implementierung und Kompatibilitätsnachweise werden in #926 verfolgt. Die
Architekturentscheidung steht in [ADR 0006](../adr/0006-standards-interoperability.md).

Öffne **Werkzeugintegrationen** im eigenen privaten Workspace. Eine Verbindung
legt Formatprofil, externe Identität, Datenhoheit und bei Anforderungen die
Projekt-ID fest. Eine Datei oder konfigurierte OSLC-Ressource wird zuerst geprüft.
Die Vorschau zeigt den genauen internen und externen Stand, Einzeländerungen,
Konflikte und den Verlustbericht. Jede Änderung braucht eine Entscheidung; die
Übernahme benötigt eine Begründung. Filter und Seiten unterstützen große Bestände.

| Modus | Verhalten |
|---|---|
| LINK_ONLY | Nur dauerhafte Nachverfolgungsdaten; keine Modelländerung |
| IMPORT_COPY | Geprüfte unabhängige Kopien; fehlende Objekte werden nicht gelöscht |
| MIRROR_READ | Geprüfter Lesespiegel; nur vollständige Dateien können Löschkandidaten liefern |
| PUBLISH_TARGET | Ausschließlich geprüfter Export |
| BIDIRECTIONAL | Dreifacher Zustandsvergleich, geprüfte Änderungen und explizite Löschentscheidungen |

Ein Dateidownload bestätigt keine Übernahme durch ein Fremdsystem. Die
Dateiadapter schreiben nicht auf einen Herstellerserver. Der OSLC-Consumer liest
und entdeckt Ressourcen; er veröffentlicht keine Änderungen auf dem Fremdserver.
Es gibt keine automatisch gestartete Hintergrundsynchronisation. Die gemeldeten
Fähigkeiten des Profils bestimmen die verfügbaren Aktionen.

ReqIF 1.2 erhält Objektidentitäten, Datentypen, Attribute einschließlich XHTML und
Aufzählungen, Spezifikationen, wiederholte Hierarchie-Vorkommen und Relationen.
Anforderungen werden im bestehenden versionierten Projektportfolio gespeichert;
sie werden keine Taxonomieknoten. Titel und Text lassen sich bestehenden
Quellattributen zuordnen. Für zwei unabhängig bearbeitbare Felder werden zwei
unterschiedliche Zuordnungen benötigt. Aktuelle kanonische Werte ersetzen beim
Rückexport die entsprechenden alten Werte. Ein Upload wird nicht ungeprüft als
aktueller Modellstand wiedergegeben.

ArchiMate Exchange 3.1 verwendet explizite Typzuordnungen und die vorhandenen
typisierten DSL-Befehle. Nicht unterstützte Typen erfordern Zuordnung oder
Ablehnung. Namen sind keine Identitäten. Properties, Ordner, Views, Platzierungen
und Verbindungspunkte werden getrennt von der fachlichen Elementidentität
erhalten. Die Taxonomy-Typproperties sichern präzisere interne Typen beim Rückweg.

Der OSLC-Anbieter ist authentifiziert und lesend. Er liefert Discovery,
Ressourcenformen, freigegebene aktuelle Anforderungen, ihre unveränderlichen
Textversionen und explizite Architekturversionen als RDF/XML, Turtle oder JSON-LD.
Der Consumer verarbeitet RDF/XML. Strukturierte Blank-Node-Attribute benötigen
ein anderes ausdrücklich deklariertes Zuordnungsprofil.

Dateien sind auf 16 MiB und 10.000 fachliche Objekte begrenzt. Anforderungen haben
höchstens 240 Titel- und 100.000 Textzeichen. Architekturbatches sind auf 2.000
typisierte Befehle begrenzt. ReqIFZ-Archive werden nicht entpackt, Anhänge nicht
nachgeladen. DTDs, externe Entitäten/Schemata, aktive XHTML-Inhalte und automatisch
nachladende Medien/CSS sind ausgeschlossen. Die Schemaprüfung arbeitet offline
mit fest hinterlegten Schemata.

OSLC-Endpunkte werden administrativ unter `taxonomy.integrations.remotes`
konfiguriert. Ein Profil bindet Repository, Eigentümerorganisation, HTTPS-Ursprung
und Pfadgrenze. Ein optionaler Umgebungsvariablenname verweist auf ein Bearer-Token;
die Verbindung enthält nur den Profilschlüssel. Zugangsdaten gelangen nicht in
DSL, Git, Vorschauen oder Ereignisse. DNS-Prüfung bei Verbindungsaufbau, feste
Größen-/Zeitgrenzen und gesperrte Redirects begrenzen den Transport. Private Netze
und HTTP erfordern eine explizite Freigabe im jeweiligen administrativen Profil;
Link-local-/Metadatenadressen bleiben gesperrt.

Die Übernahme speichert Zuordnungen, Portfolioänderungen, semantisches Journal und
Checkpoint-Absicht in einer Datenbanktransaktion. Git folgt separat. Nach einem
Absturz wird derselbe vorbereitete Checkpoint fortgesetzt; Modelländerungen werden
nicht wiederholt. Ein unveränderter Import erzeugt keinen redundanten Git-Commit.
Vorschau, Discovery, Link-only, Abbruch und Dateiexport erzeugen ebenfalls keinen
Commit. Normale Editorbefehle behalten die Trennung aus ADR 0005 bei.

Backups müssen Anwendungstabellen und datenbankgestützten Git-Speicher zusammen
sichern. Dazu gehören Workspace-/Editorjournal, Projektversionen, externe
Zuordnungen, Integrationsvorgänge und Checkpoints. PostgreSQL erhält Migration V20;
die übrigen Datenbanken verwenden die entsprechenden Entity-Abbildungen.
Suchindizes und Projektionen bleiben daraus wiederherstellbar.

Die Format-Tests verwenden unabhängige RMF-/Archi-Dateien und behalten fehlerhafte
Herstellerbeispiele ausdrücklich als Negativtests bei. Das ersetzt keine
Produktzertifizierung. Nachweise mit installierten Werkzeugversionen sowie die
vollständigen CI-, Datenbank- und Browsertests gehören weiterhin zur laufenden
Abnahme von #926. Details und API-Verträge stehen in der
[englischen Dokumentation](standards-interoperability.md).
