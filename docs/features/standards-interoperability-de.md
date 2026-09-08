# Standardsbasierte Werkzeugintegration

Implementierung und Kompatibilitätsnachweise werden in #926 verfolgt. Die
Architekturentscheidung steht in [ADR 0006](../adr/0006-standards-interoperability.md).
Die [QA- und Kompatibilitätsnachweise](../qa/standards-interoperability.md) enthalten
die tatsächlich ausgeführten Produktläufe mit Quellstand, Versionen und Prüfsummen.

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

Bei `LINK_ONLY` kann die Prüfung ein bestehendes Ziel ausdrücklich als
`requirement:<Schlüssel>` im ausgewählten Projekt oder `element:<ID>` im Workspace
angeben. Der Server prüft Existenz und Berechtigung. Die Verknüpfung dokumentiert
die Herkunft, ohne Felder des Ziels zu überschreiben.

Ein Dateidownload bestätigt keine Übernahme durch ein Fremdsystem. Die
Dateiadapter schreiben nicht auf einen Herstellerserver. Der OSLC-Consumer liest
und entdeckt Ressourcen; er veröffentlicht keine Änderungen auf dem Fremdserver.
Es gibt keine automatisch gestartete Hintergrundsynchronisation. Die gemeldeten
Fähigkeiten des Profils bestimmen die verfügbaren Aktionen.

ReqIF dient dem portablen Anforderungsaustausch, OSLC dauerhaften Links und
bedingtem Lesen, ArchiMate dem Architekturmodell-Austausch. Vollständige kanonische
Architekturversionen zwischen Taxonomy-Repositories verwenden weiterhin die
Git-Synchronisation mit expliziten Checkpoints. Zusätzliche Herstelleraktionen
benötigen einen optionalen Adapter mit eigenen deklarierten Fähigkeiten.

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

Fehlt für eine kanonische Relation eine verlustfreie ArchiMate-Zuordnung, bleibt
sie mit Verlustbericht in der Exportprüfung sichtbar. Sie benötigt Ablehnung oder
eine ausdrücklich gewählte Zuordnung; es gibt keinen stillen Association-Fallback.
Lokale View-Mitgliedschaften und Properties ersetzen veraltete Austauschwerte.
Generierte Export-IDs werden an ihre lokalen Ursprungsobjekte gebunden. Ein
Rückimport erzeugt dadurch keine Duplikate. Diese Bindung bestätigt keinen externen
Stand und erlaubt keine Löschung allein wegen fehlender Objekte im Rücklauf.

Der OSLC-Anbieter ist authentifiziert und lesend. Er liefert Discovery,
Ressourcenformen, freigegebene aktuelle Anforderungen, ihre unveränderlichen
Textversionen und explizite Architekturversionen als RDF/XML, Turtle oder JSON-LD.
Der Consumer verarbeitet RDF/XML. Strukturierte Blank-Node-Attribute benötigen
ein anderes ausdrücklich deklariertes Zuordnungsprofil.

Die Verbindungsansicht verlinkt die genaue OSLC-Discovery dieses Workspaces.
Discovery und Formen sind berechtigungspflichtig; der Scope-Hash ersetzt keine
Zugriffskontrolle. ETags unterstützen bedingtes Lesen. Der aktuelle Stream ist der
einzige unterstützte Konfigurationskontext. Anforderungsendpunkte liefern nur die
aktuell freigegebene Textversion: Ältere Versionen sind kein historisches
Freigabearchiv. Beliebige OSLC-Abfragen und allgemeines Configuration Management
sind nicht implementiert. XMI-, UAF-, SysML- und Herstelleradapter bleiben optionale
Erweiterungen derselben Schnittstelle.

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

Vorübergehende Git-Fehler bleiben mit derselben eingefrorenen Absicht wiederholbar.
Ist der Git-Elternstand inzwischen verschoben, wird der Vorgang als `CONFLICT`
mit `MODEL_APPLIED_CHECKPOINT_CONFLICT` protokolliert. Die akzeptierte
Datenbankänderung bleibt erhalten; der Versionskonflikt erfordert eine bewusste
Abstimmung. Ein Retry überschreibt den fremden Stand nicht automatisch.

Backups müssen Anwendungstabellen und datenbankgestützten Git-Speicher zusammen
sichern. Dazu gehören Workspace-/Editorjournal, Projektversionen, externe
Zuordnungen, Integrationsvorgänge und Checkpoints. PostgreSQL erhält Migration V20;
die übrigen Datenbanken verwenden die entsprechenden Entity-Abbildungen.
Suchindizes und Projektionen bleiben daraus wiederherstellbar.

Die Format-Tests verwenden unabhängige RMF-/Archi-Dateien und behalten fehlerhafte
Herstellerbeispiele ausdrücklich als Negativtests bei. Das ersetzt keine
Produktzertifizierung. Ein verpflichtender CI-Lauf installiert StrictDoc 0.29.0
und Archi 5.10.0 und prüft echte Import-/Export-Rundläufe. Bericht, Austauschdateien,
Produktversionen, bekannte Verluste und Prüfsummen werden an den getesteten
Git-Stand gebunden. Ohne erfolgreichen Bericht gilt kein Produktpfad als bestätigt.
Wird ein übergeordnetes Diagrammobjekt entfernt, bleibt ein vorhandenes Kind auf
der obersten Ebene erhalten. `VIEW_OCCURRENCE_REPARENTED` meldet diese Änderung
und den notwendigen Blick auf die übernommenen Koordinaten ausdrücklich.
Recovery wird über sechs getrennte JVM-Starts geprüft; Datenbanktests prüfen
konkurrierende HTTP-Schreibvorgänge auf PostgreSQL, MSSQL und Oracle. Die
Browsertests prüfen Rollen, Übernahme, Retry, Reload, Abbruch, Download,
Tastaturbedienung, schmale Ansichten und deutsche Beschriftungen. Die vollständige
CI-Abnahme bleibt erforderlich. Details und API-Verträge stehen in der
[englischen Dokumentation](standards-interoperability.md).
