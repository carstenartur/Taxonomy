# Austausch mit Sparx Enterprise Architect

Status: **experimentelle XMI- und optionale QEA-Implementierungen; Produktkompatibilität noch ungeprüft**.
Dies ist ein Umsetzungsschritt von [#1075](https://github.com/carstenartur/Taxonomy/issues/1075).
Das Issue bleibt offen. Für die Produktabnahme standen weder Enterprise Architect
noch Pro Cloud Server zur Verfügung. Die synthetische Testdatei belegt keine
Unterstützung einer bestimmten EA-Version.

## Optionaler QEA-Import als Plugin

Das eigenständige [Sparx-QEA-Plugin](../../plugins/taxonomy-sparx-plugin/README.md)
ergänzt das Profil **Sparx EA QEA** (`sparx-qea@1`) in derselben
Integrationsoberfläche. Es liest SQLite-basierte QEA-Snapshots. Das aktuelle Plugin
benötigt Java 25 und die Host-SPI ab 1.5.0 innerhalb der Hauptversion 1; sein Profil
in Version 1 bleibt rein lesend. Es wird separat gebaut und installiert:

```bash
./mvnw -B -pl taxonomy-extension-api,taxonomy-sparx-mapping -am install
./mvnw -B -f plugins/taxonomy-sparx-plugin/pom.xml verify
```

Den Host stoppen, die fertige Plugin-JAR aus dem `target`-Verzeichnis des Plugins
in `taxonomy.plugins.directory` kopieren und den Host neu starten. Die JAR ist
eine vertrauenswürdige Betreiber-Erweiterung und läuft im Hostprozess. Die
STARTUP-Installation hat denselben Lebenszyklus wie Alfabet und LeanIX.

1. Unter **Werkzeugintegrationen** eine Verbindung mit `sparx-qea@1`, einer
   dauerhaft eindeutigen externen Modellkennung und **Importkopie** anlegen.
2. Einen konsistenten, geschlossenen QEA-Snapshot hochladen (im Host höchstens
   16 MiB). Die Datei wird lesend verarbeitet. Dieses Profil behandelt seine
   Projektion immer als unvollständig und leitet aus fehlenden Objekten keine
   Löschungen ab.
3. Typzuordnungen, Beziehungen, Paketverschiebungen und den Verlustbericht prüfen.
   Unbekannte Typen benötigen eine ausdrückliche Zuordnung oder Ablehnung.
   Diagrammzugehörigkeit und Paketzugehörigkeit werden getrennt behandelt.
4. Die ausgewählten Änderungen mit Begründung übernehmen. Wiederholte Importe
   verwenden dieselben externen GUIDs; eine Umbenennung erzeugt kein neues Objekt.

Das Profil bietet zunächst den lesenden Import. Diagrammgeometrie und weitere
EA-Details können als Nachweis erhalten bleiben, ohne vollständig in Taxonomy
darstellbar zu sein; maßgeblich ist der Verlustbericht. Synthetische JUnit-Tests
prüfen den deklarierten Umfang. Ein Test mit einer realen EA-Version steht aus.

Bei einer aktualisierten Plugin-JAR muss eine offene Importvorschau neu erstellt
werden (`INTEGRATION_PLUGIN_CHANGED`). Bereits übernommene Daten und die gespeicherte
Historie bleiben bei einer Deinstallation erhalten. Details zu Schema, unterstützten
Typen, Grenzen und Herkunft stehen im Plugin-README.

## Geprüfte native QEA-Dateien: Version 2

Dasselbe optionale Plugin ergänzt `sparx-qea@2` für die geprüfte Ausgabe nativer
QEA-Dateien. Zusätzlich zum Dateiimport implementiert es den öffentlichen Vertrag
`BASED_FILE_EXPORT_V1`. Der Host verwaltet hochgeladene Basisdateien, eingefrorene
Vorschauen, Entscheidungen, unveränderliche Downloads und Historie. Das Plugin
liest eine bereitgestellte SQLite-Basis und schreibt eine separate Kopie; die
Originaldatei wird nicht bearbeitet. Profil 1 behält sein bisheriges Importverhalten.

| Modus | Erforderliche Basis | Identität und Ergebnis |
|---|---|---|
| `NEW_PROJECT` | Eine vom Betreiber bereitgestellte leere, konsistente QEA-Vorlage | Erzeugt einen unabhängigen Namensraum für Modell- und Objekt-GUIDs, der vor der Prüfung feststeht. |
| `UPDATE_COPY` | Genau der zuvor in diese Verbindung importierte QEA-Snapshot | Ändert eine Kopie unter Erhalt der importierten Modell-/Objekt-GUIDs, vorhandenen lokalen IDs und unberührten Basisinhalte. |

Eine leere Vorlage aus einer berechtigten EA-Installation verwenden oder direkt
aus den [Repository-Ressourcen von Sparx](https://sparxsystems.com/resources/repositories/)
beziehen. Taxonomy liefert keine proprietäre Vorlage mit. Manche leeren Vorlagen
enthalten noch keine `ProjectGUID`; `NEW_PROJECT` vergibt dafür eine neue
Modellkennung und behandelt die Vorlage nicht als bereits importiertes Modell.
Im Host gilt die Upload-Grenze von 16 MiB. Das Quellprojekt schließen und einen
konsistenten Dateisnapshot bereitstellen, einschließlich bestätigter Änderungen,
die sonst noch in einer WAL-Datei liegen könnten.

1. Unter **Werkzeugintegrationen** eine Verbindung mit Version 2 anlegen. Für eine
   Aktualisierung **Importkopie** wählen, den Snapshot importieren und dessen
   geprüfte Änderungen zunächst übernehmen. Für ein unabhängiges Projekt eine
   separate Verbindung als **Veröffentlichungsziel** verwenden. Der Server bietet
   nur die zur Verbindung und ihrer Historie passenden Modi an.
2. Den gewünschten Dateimodus wählen und die Basis hochladen oder eine bereits zu
   dieser Verbindung gespeicherte Basis auswählen. Ein Update verlangt den SHA-256
   des zuletzt importierten Snapshots; eine andere Datei ersetzt diesen Stand
   nicht stillschweigend.
3. Den aktuellen kanonischen Zustand als Vorschau prüfen. Änderungen, Zuordnungen
   und Verluste einschließlich transformierter Diagrammplatzierungen durchsehen.
   Über die vorhandene Integrationsprüfung ausdrückliche Entscheidungen und eine
   Begründung angeben.
4. Den geprüften Dateivorgang abschließen und die erzeugte `.qea` herunterladen.
   Der Download ist an Vorgang, Basisdatei und installiertes Plugin-Artefakt gebunden.
5. Die Kopie in einer berechtigten EA-Umgebung öffnen und prüfen. Der Download
   bestätigt keine erfolgreiche Übernahme in EA. Für eine neue externe
   Abgleichbasis einen später beobachteten Snapshot zurück importieren.

### Unterstützter Dateiumfang und Verluste

Version 2 umfasst unterstützte Elemente, Anforderungen, Pakete (`SPECIFICATION`),
Ansichten, Paketplatzierungen und Diagrammzugehörigkeit. Die kanonische Bedeutung
von Elementen und Beziehungen stammt aus derselben Sparx-Mapping-Bibliothek wie
bei XMI-Version 3. Die 12 deklarierten Beziehungsabbildungen erhalten erforderliche
explizite Tags, damit ihre Bedeutung erhalten bleibt. Nicht unterstützte oder
widersprüchliche Zuordnungen werden abgewiesen. Für Anforderungen gelten weiterhin
die vorhandenen Projekt- und Endpunktregeln.

Bei `UPDATE_COPY` bleiben unveränderte Tabellen, unbekannte Spalten, BLOBs und
unbeteiligte Zeilen in der kopierten Basis erhalten. Vorhandene Diagrammgeometrie
und Diagrammverknüpfungen bleiben bestehen, soweit keine unterstützte ausdrückliche
Änderung einen Eingriff erfordert. Neue Diagrammzugehörigkeiten erhalten einfache,
deterministische Rechtecke und einen deklarierten `TRANSFORMED`-Verlust. Das ist
kein vollständiger MDG- oder beliebiger EA-Layout-Rundlauf. Fehlende Zuordnungen
oder Objekte führen nicht zu abgeleiteten Löschungen. Unbekannte erforderliche
Schemastrukturen werden abgewiesen; der Writer erfindet kein vermeintlich
vollständiges EA-Datenbankschema.

Modell- und geprüfte Objekt-GUIDs sind von lokalen SQLite-Zeilen-IDs und einzelnen
Diagrammvorkommen getrennt. Paketbesitz und Diagrammzugehörigkeit sind unabhängig.
Eine Dateiausgabe verändert weder die kanonische Architektur noch den beobachteten
externen oder gemeinsamen Synchronisationscheckpoint.

### Aufbewahrung, Neustart und öffentliche API

Der Host speichert binäre Basisdateien und Ergebnisse getrennt vom Vorgangs-JSON.
Die Übersicht enthält gespeicherte `fileBases`. Der `fileExport`-Deskriptor eines
Vorgangs enthält Modus, Identitätsnamensraum, Basis-ID, SHA-256, Medientyp und
Byteanzahl, Modellkennung sowie nach der Ausgabe die Ergebnisdatei-ID, deren
SHA-256 und Byteanzahl. Diese Datenbankeinträge zusammen mit dem übrigen
Integrationszustand sichern.

Eine identische Wiederholung verwendet dieselbe eingefrorene Basis und denselben
Identitätsnamensraum. Nach einem Neustart kann die Prüfung fortgesetzt werden.
Ein verändertes Plugin-Artefakt verhindert die Ausführung einer offenen Prüfung
(`INTEGRATION_PLUGIN_CHANGED`); eine neue Vorschau ist erforderlich. Ohne Plugin
sind neue Vorgänge gesperrt, während abgeschlossene Dateien, kanonische Daten,
Identitäten und Historie erhalten bleiben. Ein fertiger Download benötigt keinen
Plugin-Aufruf.

API-Clients verwenden multipart
`POST /api/integrations/{connection}/export-previews`. Der JSON-Part `request`
enthält `operationId`, den exakten lokalen Zustand `expected`,
`expectedExternalVersion`, `mode`, `mediaType` und optional `baseFileId`. Genau
eine Basis angeben: den optionalen binären Part `file` oder eine vorhandene
`baseFileId`. Die Prüfung verwendet weiterhin JSON
`POST /api/integrations/{connection}/files` mit `ReviewedChangeSet`. Abgeschlossene
Dateien liefert `GET /api/integrations/{connection}/operations/{operation}/file`.

Der getrennte PCS-Connector bleibt bei Lesen/Pull, bis für seinen konkreten
Provider ein verifizierter Vertrag zur bedingten Veröffentlichung vorliegt.
Eine native Dateiausgabe begründet keine PCS-Schreibgarantien. Die Anleitung
[QEA-Dateiabnahme und Betreiberbelege](../qa/sparx-qea-file-acceptance.md) beschreibt
den HTTP-Test der installierten JAR, eigene Vorlagen und getrennte EA-Nachweise.
Native EA-/PCS-Ausführung bleibt `NOT_EXECUTED`, solange kein tatsächlicher
Produktlauf separat belegt ist. Das Zurücklesen erzeugter SQLite-Dateien ersetzt
diesen Nachweis nicht.

## Geprüfter XMI-Ablauf

Unter **Werkzeugintegrationen** das berechtigte Repository, den Workspace und
Branch auswählen. Eine Verbindung mit dem Profil **Sparx EA XMI 2.1** anlegen.
Als externes System `SPARX` und eine dauerhaft eindeutige Repository-/Modellkennung
verwenden. Bei Anforderungen zusätzlich ein Anforderungsprojekt angeben.
Profil und Profilversion bleiben für diese Verbindung festgelegt.

1. Den vorgesehenen EA-Modellbereich als unterstützte XMI-2.1-Teilmenge mit
   unveränderten GUIDs exportieren. Vor dem Test das EA-Modell sichern.
2. Die `.xmi`- oder `.xml`-Datei hochladen. **Vollständiger externer Bereich** nur
   bestätigen, wenn sie genau den bisherigen Synchronisationsbereich vollständig
   und verbindlich enthält.
3. Neuanlagen, Feldänderungen, Verschiebungen, Beziehungen, Löschkandidaten,
   Konflikte und den Verlustbericht prüfen. Die Vorschau ändert keine Fachdaten.
4. Für jede Änderung eine Entscheidung treffen. Feldkonflikte ausdrücklich
   auflösen und eine Begründung angeben. Die Sammelannahme übernimmt keine
   Löschkandidaten.
5. Gegen die angezeigte exakte Workspace-Revision anwenden. Vorschau, Entscheidungen,
   Zuordnungen, Nachweise und Wiederanlaufstatus bleiben nach Neuladen erhalten.
6. Den Export des aktuellen Zustands zunächst prüfen, dann `taxonomy-sparx.xmi`
   herunterladen. In EA GUIDs erhalten und **Diagrammimport deaktivieren**.
   Diesen Ablauf zuerst an einer gesicherten Testkopie abnehmen.
7. Den geprüften EA-Zustand zurück exportieren und in Taxonomy übernehmen. Erst
   dieser beobachtete Zustand liefert eine neue Abgleichbasis. Der Dateidownload
   allein bestätigt keine Änderung in EA und verschiebt keinen Sync-Checkpoint.

Die gemeinsame Oberfläche bietet Tastaturbedienung, Filter und Seiten mit jeweils
40 Änderungen. Beschriftungen und Hinweise sind deutsch und englisch verfügbar.

## Abbildung in Version 1

| EA-/XMI-Konstrukt | Kanonischer Austausch | Wirkung in Taxonomy |
|---|---|---|
| `uml:Package` | `SPECIFICATION` mit stabilen Platzierungen | Dauerhafte, geprüfte Austauschsemantik; keine erfundene Komponente |
| Class, Component, Actor, Activity, UseCase, Interface, Node, Artifact | `ELEMENT` mit explizitem Typ | Typisierte Architekturkommandos |
| Requirement-Stereotyp / EA-Typ Requirement | `REQUIREMENT` | Versionierte Projektanforderung; Projekt erforderlich |
| Name und Dokumentation | Titel und Beschreibung als Text | Native editierbare Felder |
| Stereotyp | Erweiterungsfeld `stereotype` | Erhalten; bekannte Typnamen verfeinern das Mapping |
| Tagged Values | Eindeutige Attribute `tag:<name>` | In versionierten Austauschnachweisen erhalten |
| Tags `taxonomy.<property>` | Erweiterungen `taxonomy:<property>` | Deklarierte editierbare Architekturfelder werden angewendet |
| Dependency | `DEPENDS_ON` | Native Typ- und Endpunktregeln gelten |
| Realization | `REALIZES` | Native Typ- und Endpunktregeln gelten |
| InformationFlow | `COMMUNICATES_WITH` | Native Typ- und Endpunktregeln gelten |
| Usage | `CONSUMES` | Native Typ- und Endpunktregeln gelten |
| Association | `RELATED_TO` | Allgemeine Beziehung |
| Composition | `CONTAINS` | Native Regeln für Enthaltensein gelten |
| Umgekehrte Beziehungsrichtung | Ursprüngliche Endpunkte und Richtung bleiben erhalten | Native Endpunkte werden vertauscht |
| Bidirektionale Beziehung / Rollen und Multiplizitäten an Endpunkten | Ausdrücklicher Verlusthinweis | Bidirektionale Beziehungen müssen vor dem nativen Apply abgelehnt werden; Endpunktdaten bleiben nur in der Quellevidenz |
| Diagramme, Koordinaten, Stil | Expliziter Ausschluss im Verlustbericht | Keine Änderung nativer Ansichten; kein Diagramm-XML im Export |
| Attribute, Operationen, weitere UML-/MDG-Funktionen | Expliziter Verlustbericht | Nur in der ursprünglichen Quelldatei erhalten; kein Export |

Standardabbildung: Class/Component → Component, Actor → BusinessRole,
Activity → Process, UseCase → Capability, Interface → CoreService,
Node → System, Artifact → InformationProduct. Ein bekannter Stereotyp oder ein
explizites Tag `taxonomy.elementType` präzisiert die Zuordnung. Unbekannte Typen
müssen abgelehnt oder bewusst auf einen unterstützten Typ abgebildet werden.

Der XMI-Codec verlangt, dass unterstützter EA-Transporttyp und deklarierte
kanonische Bedeutung übereinstimmen. Ein fremder Transporttyp wie
`ApplicationComponent` muss zuerst durch die native Projektion oder ein explizites
Remapping gehen. `Component` mit kanonischem Typ `System` braucht den passenden
Stereotyp oder das Tag `taxonomy.elementType`. Widersprüchliche Beziehungs- und
kanonische Typen werden vor dem Export abgewiesen.

Version 1 bewahrt Pakete und ihre Hierarchie als Austauschnachweise. Der aktuelle
native Editor unterstützt transportneutrales Anlegen, Umbenennen und Platzieren
von Paketen; das ändert die eingefrorene Austauschsemantik von Version 1 nicht.
Version 2 ergänzt native Paketprojektion und explizite Anforderungszuordnungen in
der [nativen Prüfung](#native-prüfung-mit-version-2). Andere Endpunktkombinationen
erfordern weiterhin eine erlaubte Projektion oder Ablehnung. Nicht jede
EA-Beziehung ist damit bearbeitbar.

## Identität, Konflikte und Historie

GUIDs werden aus Klammer-, `EAID_`- und `EAPK_`-Schreibweisen vereinheitlicht.
Umbenennungen und Verschiebungen behalten ihre Identität. Der vorhandene
Integrationsspeicher bindet sie an interne Fachkennungen und isoliert die
Zuordnung nach Organisation, Repository, Workspace und Branch. Exporte enthalten
`taxonomy.id` und `taxonomy.mappingProfile`. Ein externes ID-Tag berechtigt
niemals zur Bearbeitung eines beliebigen internen Objekts.

Der Dreiwegevergleich verwendet den letzten bestätigten Integrationszustand.
Unabhängige Feldänderungen bleiben erhalten; konkurrierende Änderungen brauchen
eine ausdrückliche Entscheidung. Wiederverwendete interne Identitätsangaben
erfordern eine Identitätskorrektur, auch bei einer anderen EA-GUID oder einer
bereits entfernten historischen Zuordnung. Unvollständige Daten ergeben keine
Löschkandidaten. Abgelehnte Änderungen bleiben nachvollziehbar. Die Zustandsprüfung
berücksichtigt auch semantische Revision und Projekt-Fingerprint, nicht nur Git.

Akzeptierte Architekturänderungen verwenden das vorhandene typisierte
Operationsjournal. Die Integrationsoperation enthält Akteur, Zeitpunkt,
Verbindung/Bereich, Profil, Vorher/Nachher-Daten, externen Fingerprint und
Begründung. Git bleibt die getrennte, wiederholbare Checkpoint-Ebene.
Wiederholungen erzeugen keine zusätzlichen Fachoperationen oder Anforderungen.

## Sicherheit, Grenzen und Wiederherstellung

Die gemeinsame XML-Verarbeitung blockiert DTDs, externe Entitäten und das Auflösen
von XInclude. Grenzen: 16 MiB Eingabe/Ausgabe, XML-Tiefe 96, 250.000 XML-Elemente
und 10.000 Fachobjekte. Das native Anwenden behält seine vorhandene Kommandogrenze.
Externe XML-Dateien steuern weder Schemas noch Netzwerkziele. Öffentliche Fehler
enthalten keine Parserauszüge. Der XMI-Weg benötigt keine Zugangsdaten.

Integrationsdatenbank und Git-Repository gemeinsam sichern. Die Datenbank besitzt
Vorschauen, Zuordnungen, Konflikte und Checkpoints; Git allein stellt diese nicht
wieder her. Eine neue Profilversion erfordert explizite Migration und erneuten
Abgleich. Bestehende Zuordnungen dürfen nicht stillschweigend umgedeutet werden.

## PCS OSLC AM lesen und geprüft übernehmen

Das getrennte Profil **Sparx PCS OSLC AM 2.0 read/pull** (`sparx-oslc-am-2.0@1`)
nutzt dieselbe Prüfung, GUID-Zuordnung, nativen Kommandos, Journale und Checkpoints.
Zulässig sind `LINK_ONLY`, `IMPORT_COPY` und `MIRROR_READ`. Der generische
OSLC-RM-Connector ersetzt den AM-Connector nicht. Vertragstests sind vorhanden;
die Kompatibilität mit einer echten PCS-Installation ist noch nicht bestätigt.

Ein Administrator bindet den Endpunkt an das genaue Taxonomy-Repository und dessen
Eigentümer. Beispiel mit zu ersetzenden, nicht geheimen Werten:

```yaml
taxonomy:
  integrations:
    remotes:
      scenario-pcs:
        repository-id: <repository-id>
        organization-id: USER:<repository-eigentuemer>
        base-uri: https://pcs.example.org/model/oslc/am/
        credential-environment-variable: SCENARIO_PCS_TOKEN
        allow-private-networks: false
        allow-insecure-http: false
```

`SCENARIO_PCS_TOKEN` enthält die PCS-Sitzungs-GUID aus dem
[dokumentierten Modell-Login](https://sparxsystems.com/enterprise_architect_user_guide/17.2/the_model_repository/oslc_user_cred.html).
Login, SSO und Token-Erneuerung erfolgen in diesem Schritt administrativ.
Der Transport sendet `Authorization: OSLC <token>` und den erforderlichen
Abfrageparameter `useridentifier`. Zugriffsprotokolle von PCS/Proxies müssen diesen
Parameter maskieren. Taxonomy speichert nur den Profilschlüssel und Ressourcen ohne
Zugangsdaten. Ein fehlgeschlagener Abruf kann nach Erneuerung des serverseitigen
Tokens mit derselben Vorgangs-ID fortgesetzt werden.

Auf der Integrationsseite AM-Profil, zulässigen Modus und `scenario-pcs` wählen.
Die **externe Repository-Identität muss genau `base-uri` entsprechen**; die externe
Konfiguration bleibt leer. Für Anforderungselemente ein Projekt zuordnen.
Das Ressourcenfeld für `sp/`-Discovery leer lassen oder `qc/` eingeben. Gefilterte
Abfragen und Eigenschaftsprojektionen werden abgewiesen. Vorschau und Verluste
prüfen, jede Änderung entscheiden und mit Begründung anwenden. Bei `REMOTE_STALE`
hat sich die Sammlung verändert: Vorschau abbrechen und neu abrufen. Bereits
übernommene Vorgänge werden ohne weiteren Abruf oder Modelländerung wiedergegeben.

Alle angekündigten `oslc:nextPage`-Seiten werden gelesen: höchstens 20 Seiten und
insgesamt 16 MiB einschließlich Discovery. Gemeinsame HTTP-/DNS-Grenzen verhindern
Weiterleitungen und fremde Endpunkte. Schleifen und zurückgespiegelte Zugangsdaten
werden abgewiesen. Ein Seitenfehler erzeugt keine Teilvorschau. Vor der Übernahme
wird die Sammlung erneut gelesen; ihr Fingerabdruck umfasst Seitenadressen,
Inhalte und ETags. Dies garantiert **keinen atomaren PCS-Modellstand**. Vollständiges
Durchlaufen der Seiten erlaubt niemals das Löschen fehlender Objekte.

Version 1 übernimmt Paket- und Elementeigenschaften, Beschreibungen, GUIDs und
Pakethierarchie einschließlich Anforderungselementen. Ein einzelner eingebetteter
Stereotyp wird aus der dokumentierten RDF-Struktur `ss:stereotypename/ss:name`
gelesen. Mehrere oder unbekannte strukturierte Stereotypen erzeugen einen Verlust;
ein Element benötigt dann eine ausdrückliche Typzuordnung vor dem Anwenden.
Beziehungen, Tags, Attribute,
Operationen und Diagramme werden nicht nachgeladen; der Verlustbericht benennt
diese Grenzen. XMI bleibt der umfassendere semantische Austauschweg. Das AM-Profil
bietet weder Dateiexport noch Live-Schreiben an; die Prüfung einer Auswahl ist
von einer Veröffentlichung getrennt.

## Verbleibende Produktabnahme und Live-Schreiben

Sparx beschreibt eigene POST-Endpunkte und einen Modell-Login-Token, der bei GET
als Abfrageparameter und bei POST im RDF übertragen wird. Aus der geprüften
Anbieterdokumentation ergibt sich keine belegte atomare Versionsvorbedingung und
keine abgesicherte idempotente Anlage. Ein GET vor einem unbedingten POST verhindert
keine zwischenzeitliche Änderung. Die gemeinsame bedingte Veröffentlichung samt
Teilresultaten, Wiederaufnahme und Push/Synchronize ist mit einem echten HTTP-Testvertrag
umgesetzt und geprüft. Produktive PCS-Anlage/-Änderung/-Löschung bleibt bis zu einem
nachgewiesenen Vertrag gesperrt. Der unten beschriebene optionale SBPI-Cliententwurf
ist kein ausgeführtes Plugin.

Die reale EA-Abnahme muss Export → EA-Import → Umbenennung/Verschiebung/Tag- und
Beziehungsänderung → EA-Export → geprüfte Übernahme einschließlich Erhalt des
Diagrammlayouts testen. Nachweise mit Quell-SHA, Produktversionen, Richtung,
Datum und Verlusten in `docs/qa/sparx-compatibility.json` ergänzen. Synthetische
Tests dürfen den Produktstatus nicht auf erfolgreich setzen.

Quellen: [XMI-Austausch](https://sparxsystems.com/enterprise_architect_user_guide/17.2/model_exchange/importexport.html),
[OSLC-Funktionen](https://sparxsystems.com/enterprise_architect_user_guide/17.2/the_model_repository/info_accessed_via_oslcam.html),
[Update-Protokoll](https://sparxsystems.com/enterprise_architect_user_guide/17.2/the_model_repository/oslc_upd_resources.html),
[Authentifizierung](https://sparxsystems.com/enterprise_architect_user_guide/17.2/the_model_repository/oslc_user_cred.html),
[GUID-Präfixe](https://sparxsystems.com/enterprise_architect_user_guide/17.2/the_model_repository/guid_prefix_tables.html).

## Explizite Version 2 und semantische Features

Neue Verbindungen können `sparx-xmi-2.1@2` oder `sparx-oslc-am-2.0@2` wählen.
Die Profil-ID bleibt gleich; die Version ist ein eigener unveränderlicher Wert.
Ohne `profileVersion` wählt die API Version 1. Bestehende Verbindungen und bereits
geprüfte Vorgänge behalten ihre genaue Profilversion.

AM und XMI verwenden in Version 2 dieselbe semantische Zuordnung. Die Beispiele
sind **synthetische Vertragsfixtures, keine EA-Exporte oder PCS-Aufzeichnungen**.
Native Paketbearbeitung und explizite Anforderungs-Endpunktzuordnung stehen in der
[nativen Prüfung](#native-prüfung-mit-version-2) zur Verfügung. UML-Features bleiben
überprüfbare Austauschdaten; Live-Schreiben ist nicht verfügbar. Reale
EA/PCS-Kompatibilität bleibt ungeprüft (`NOT_EXECUTED`).

| Konstrukt | Austauschdarstellung | Verhalten |
|---|---|---|
| Tags (`tv_`, `attv_`, `optv_`) | `FEATURE`, `tagged-value`, GUID und Eigentümer | Doppelte Namen bleiben getrennt; kein Wert gewinnt. Nur eindeutige Namen erhalten zusätzlich `tag:<name>`. |
| Attribute (`at_`) | `FEATURE`, `attribute` | Element-Eigentümer, optionale Position, Datentyp/Sichtbarkeit/Standardwert/Multiplizität |
| Operationen (`op_`) | `FEATURE`, `operation` | Element-Eigentümer, Position und Operationsfelder |
| Parameter (`pr_`) | `FEATURE`, `parameter` | Operations-Eigentümer, Position, Richtung und Datentyp |
| AM-Beziehung (`lt_`) | Gleiche gerichtete `Relation` wie XMI | Identische Mehrfachmeldungen werden zusammengeführt; widersprüchliche GUIDs scheitern. |
| Endpunkt außerhalb der gelesenen Wurzeln | `FEATURE`, `external-connector` | Expliziter Verlustbericht, nur erhaltene Evidenz; keine native Beziehung oder Löschannahme |
| Unbekanntes RDF-Feld | `attribute:<Prädikat>` oder `extensions.rdf:<Prädikat>` | Begrenzte erhaltene Evidenz mit `PRESERVED_EXTENSION`; Links werden nicht verfolgt. |

Der XMI-Schreiber überträgt die unterstützten Attribute, Operationen, Parameter
und GUID-basierten Tags. Eine deklarierte `evidence`-Erweiterung bewahrt weitere
kanonische Felder innerhalb dieses Fixture-Profils. EA-Erhaltung solcher
Erweiterungen ist nicht bestätigt. Nicht semantisch verarbeitete Feature-Eigenschaften,
einschließlich Stereotypen, bleiben als begrenzte Evidenz mit `PRESERVED_EXTENSION`
erhalten. Nicht unterstützte Feature-Kinder und externe Beziehungen müssen vor
einer sonst verlustbehafteten XMI-Ausgabe abgelehnt werden. Nicht geschriebene
Attribute und Teilbäume an Verbinderendpunkten erhalten vor der Ausgabe explizite
Verlustmeldungen mit Verbinder-ID und Feldpfad. Quell-/Zielidentität und Richtung
behalten ihre bisherige Bedeutung.

AM Version 2 liest jede Sammlungsseite für Wurzeln, Beziehungen, Tags, Attribute,
Operationen, Attribut-/Operations-Tags und Parameter. Ein gemeinsames Budget gilt
für Discovery und alle Folgeseiten: 250 angereicherte Wurzeln, 20 Seiten pro
Sammlung, 1.024 Antworten, 16 MiB, 100.000 RDF-Aussagen, 10.000 Objekte,
128 Eigenschaften je Objekt, 32 Stereotypen und 30 Sekunden für einen erfolgreichen
Lesevorgang. Fehler oder Grenzüberschreitungen liefern keine Teilvorschau.

Alle Seiten verwenden denselben geschützten PCS-Transport. Endpunktpfade entstehen
aus geprüften IDs; beliebige RDF-Links werden nicht abgerufen. Der Fingerabdruck
erfasst Sammlungstyp, Eigentümer, bereinigte URI, ETag und Inhalt jeder Antwort.
Die Übernahme liest alles erneut; auch reine Feature-Änderungen führen zu
`REMOTE_STALE`. Vollständig gelesene Sammlungen sind kein atomarer Snapshot und
keine Löschfreigabe: `completeScope` bleibt in AM Version 2 immer `false`.

## Native Prüfung mit Version 2

Version 2 übernimmt Pakete als neutrale native Pakete mit stabilen IDs und
EA-GUID-Zuordnungen. Lokales Umbenennen, Verschieben und Platzieren erscheint in der
XMI-Ausgabe; Version-1-Verbindungen behalten ihre bisherige Paketnachweis-Semantik.
Unvollständige Eingangsbereiche bewahren nicht erwähnte native Geschwister.
Paketzuordnungen von Anforderungen bleiben Nachweise
(`SPARX_REQUIREMENT_PACKAGE_PRESERVED_ONLY`).

Die **Native Endpunktprojektion** wählt `ARCHITECTURE_RELATION`,
`REQUIREMENT_MAPPING` oder `PRESERVE_ONLY`. Quell-/Ziellisten enthalten dauerhafte
native Identitäten aus der API für den exakten Prüfzustand, nach Normalisierung der
Richtung. Anforderungszuordnungen erfordern eine explizite Auswahl. Paketendpunkte,
Anforderungspaare und bidirektionale Verbinder werden nur als Nachweis erhalten oder
abgelehnt. Ungültige Zuordnungen melden vor nativen Schreibvorgängen
`SPARX_ENDPOINT_KIND_UNMAPPED`, `SPARX_ENDPOINT_MAPPING_REQUIRED` oder
`SPARX_DIRECTION_UNMAPPED`.

Beispiel für einen Prüfentscheid:

```json
{"endpoints":{"change-id":{"sourceInternalIdentity":"PROJECT__REQ-1","targetInternalIdentity":"arch-reader","projection":"REQUIREMENT_MAPPING","canonicalType":null}}}
```

Der reine Gesamtplan löst das exakte Paar `x-project-key`/`x-requirement-key` auf,
bewahrt bestehende kanonische IDs und lehnt Sanitizer-Kollisionen mit
`REQUIREMENT_IDENTITY_MISMATCH` ab. Anforderungsschreibvorgänge, echter
Portfoliobeitrag, typisierte Zuordnung und lokales semantisches Journal bleiben in
derselben Transaktion. Analysezuordnungen sind durch
`REQUIREMENT_MAPPING_OWNERSHIP_CONFLICT` geschützt. Altes Prüf-JSON darf `endpoints`
weglassen. Anwendungs- und Browsertests verwenden Vertragsfixtures; die tatsächliche
EA/PCS-Produktkompatibilität bleibt **NOT_EXECUTED**.

Endpunktauswahl bestätigt die exakten normalisierten Verbinderendpunkte. Auch das Umleiten auf ein anderes natives Objekt desselben Typs wird mit `SPARX_ENDPOINT_KIND_UNMAPPED` abgelehnt; Projektions-/Typauswahl bleibt möglich. Dadurch stimmen native Bedeutung und bewahrte/exportierte Endpunktnachweise überein.

### Native Paket- und Endpunktbedienung

Im Paketpanel prüft **Änderung vorschauen** Anlage oder Umbenennung/Beschreibung;
**Löschung vorschauen** prüft die Entfernung unter den vorhandenen Strukturregeln.
**Ausgewähltes Paket verschieben** verwendet das gewählte Elternpaket und die
Geschwisterposition. **Ausgewähltes Element zuordnen** prüft dessen Mitgliedschaft
im gewählten Elternpaket; **Ausgewähltes Element lösen** entfernt nur die Zuordnung,
nicht das Element. Alle Aktionen bleiben im bestehenden Vorschau-/Begründungs-/
Übernahmeablauf. Ein leeres Paket-Auswahlfeld beginnt eine Neuanlage; Neuladen
stellt den gespeicherten Stand wieder her.

Die native Endpunktprojektion und Quell-/Zielauswahl sind Prüfentscheidungen,
keine sofortigen Schreibaktionen. Sie behalten die oben beschriebenen exakten
normalisierten Identitäten und Anforderungsregeln. Die vollständige
[zweisprachige Bedientabelle](sparx-integration.md#native-package-and-endpoint-controls)
erläutert alle fünf Paketknöpfe.

## Bedingte Veröffentlichung und Wiederaufnahme

Die gemeinsame Anwendung unterstützt jetzt geprüftes **Push** (Taxonomy → externes
Werkzeug) und **Synchronisieren** (geprüfte Änderungen in beiden Richtungen).
Der Server entscheidet anhand der exakten Adapter-/Profilversion,
Anbieterkonfiguration und des Modell-/Paketumfangs. Beim produktiven Sparx-Adapter
bleibt Schreiben mit `PUBLICATION_GUARANTEES_UNVERIFIED` gesperrt. Ein ausgefüllter
Umfang oder ein gemeldetes Fähigkeitsmerkmal hebt diese Sperre nicht auf.
Der echte HTTP-Vertragsanbieter `taxonomy-publication-contract-v1` wird ausschließlich
in Tests eingebunden. Seine Ergebnisse und Bilder sind **TEST ONLY / NUR TEST**,
keine PCS-Produktabnahme.

Wählen Sie Modell-/Paketidentität, den vom Anbieter definierten Teilumfang und den
exakten erwarteten externen Stand. Der Teilumfang folgt dem Adaptervertrag
(im Test `all`); Benutzer müssen keinen erfundenen Digest berechnen. Die Vorschau
friert außerdem den lokalen Stand ein. Ein explizit abweichender Branch wird vor
Veröffentlichungseffekten abgelehnt.

Die Verfügbarkeitsprüfung erklärt die konkrete Freigabe oder Sperre. **Push zum externen Werkzeug prüfen**
und **Synchronisierung prüfen** erzeugen nur eine Vorschau. Die Prüfung zeigt
BASE lokal/extern, LOCAL, REMOTE, den MERGED-Vorschlag, geänderte Felder, Konflikte,
Abhängigkeiten und Löschanforderungen. Wählen Sie für jedes geänderte Objekt
**unabhängig zusammenführen**, **lokal behalten und extern veröffentlichen**,
**extern übernehmen** (nur Synchronisieren) oder **überspringen**. Löschungen
benötigen eine ausdrückliche Wahl und Begründung. Die Sammelaktionen gelten nur
für sichtbare Zeilen; automatisches Zusammenführen beschränkt sich auf konfliktfreie
Objekte, die auf beiden Seiten vorhanden sind. Bestehende Anforderungs- und
Endpunktzuordnungen behalten ihre exakten Identitätsregeln.

**Geprüfte Entscheidungen veröffentlichen** friert den vollständigen geprüften
Plan vor Effekten ein. **Dauerhaften Vorgang fortsetzen** setzt denselben gespeicherten Vorgang
mit denselben Schlüsseln und Requests fort; nachträgliche Formularänderungen
ändern ihn nicht. **Verknüpfte Abgleichvorschau erstellen** erzeugt einen neuen,
mit dem Vorgänger verknüpften Vorgang erst nach Auflösung unbekannter Ergebnisse
und nur bei Serverfreigabe. **Vorgängervorgang öffnen** führt zum berechtigten
ursprünglichen Vorgang. **Vorschau abbrechen** steht nur vor Effekten zur Verfügung.
Beschriftete Auswahlfelder, Begründung und Schaltflächen sind per Tastatur bedienbar.
Beim Neuladen der Vorgangs-URL werden gespeicherte Entscheidungen, Umfang,
erwarteter externer Stand, Request-Fingerprint und Einzelzustände wiederhergestellt.

Geht die Veröffentlichungsantwort oder Statusabfrage verloren, bleibt die Prüfung
gesperrt. Der Veröffentlichungsbutton zeigt **Veröffentlichungsstatus unklar — vor
Änderungen Status neu laden**. **Aktualisieren** liest den gespeicherten Vorgang.
Erst eine bestätigt unangenommene Vorschau erlaubt korrigierte Sammelentscheidungen
oder Begründungen. Ein angenommener Vorgang behält seine gespeicherte Prüfung und
wird über **Dauerhaften Vorgang fortsetzen** wiederaufgenommen. Ein anderer
geöffneter Vorgang übernimmt niemals die ausstehende Prüfung des ersten.

Bestätigte, unbekannte, verbleibende, veraltete, noch nicht versuchte und nachweislich
effektlose Einzelaktionen werden getrennt angezeigt. Ein Teilresultat oder eine
unterbrochene HTTP-Antwort ist kein Gesamterfolg. Übersprungene Abweichungen verhindern
auch bei bestätigten ausgewählten Aktionen einen gemeinsamen Prüfpunkt. Lokaler
Git-Prüfpunkt, letzte Pull-Beobachtung (OBSERVATION) und verifizierter gemeinsamer
Stand (COMMON) sind getrennt. COMMON setzt vollständige externe Prüfung, bestätigte
Effekte, abgeschlossenen lokalen Git-Stand und unveränderten lokalen Inhalt voraus.

Nach einer lokalen Änderung gilt eine effektlose Einzelaktion nur als geklärt,
wenn jeder SEND dieser Einzelaktion seine eigene gespeicherte Antwort ohne Effekt
besitzt. Sobald alle Einzelaktionen geklärt sind, steht die verknüpfte Abgleichvorschau
zur Verfügung. Bleibt ein älterer SEND unklar, bleibt die Reservierung bestehen;
**Dauerhaften Vorgang fortsetzen** fragt dann ausschließlich Belege ab. Derselbe
nichtterminale Beleg beendet diesen Aufruf. Ein verbindlicher terminaler Beleg
kann die Unsicherheit auflösen. Nach lokaler Änderung wird der alte eingefrorene
Plan nicht erneut gesendet. Fehlercodes bleiben im validierten Beleg vollständig
erhalten; bei mehr als 64 Zeichen dient der Belegzustand als begrenzter Diagnosecode
in Historie und Einzelstatus.


### API und optionaler SBPI-Cliententwurf

Die authentifizierten Routen stehen unter `/api/integrations/{connection}`:
POST `/publication-previews`, POST `/publish`, GET `/operations/{operation}/publication`,
POST `/operations/{operation}/retry` und POST `/operations/{operation}/reconciliation-previews`.
Das bestehende Operation-GET liefert den autorisierten ursprünglichen Kontext;
die zusätzliche Schema-1-Projektion liefert begrenzte gespeicherte Review-/Umfangsdaten,
keine Leases, Rohrequests oder Zugangsdaten. Veröffentlichung verwendet weder
Dateiausgabe `/files` noch die eingehende Übernahme `/apply`.

Aus EA-Sicht bedeutet „Pull from Taxonomy“ Taxonomy-Push; „Push to Taxonomy“ bedeutet
Taxonomy-Pull über Remote-/XMI-Vorschau und geprüfte Übernahme. Die konkrete
[Aktion-/API-/Deep-Link-Tabelle](sparx-integration.md#authenticated-api-and-optional-sbpi-client-design)
beschreibt einen optionalen Cliententwurf. Es wurde kein proprietäres SDK-Plugin
kompiliert oder im Host ausgeführt. Taxonomy funktioniert ohne Plugin. Bestehende
Anmeldung, Berechtigung und CSRF-Prüfung gelten weiter; Deep Links enthalten keine
Zugangsdaten. Sparx beschreibt `GetMenuList` für Navigation und reserviert
`GenericRequest`; daraus folgt kein frei erfundener Menü-Callback.
[SBPI-Schnittstelle](https://sparxsystems.com/enterprise_architect_user_guide/17.2/the_model_repository/sbpi_integration_plugin_interface.html).

Sparx OSLC unterstützt grundsätzlich Schreiboperationen. Dokumentierte EA-Updates
verwenden RDF/XML-POST an eine Update-URL. Diese Endpunkte beweisen jedoch nicht
den hier erforderlichen atomaren Umfangsvergleich, abgesicherte Anlage,
dauerhafte Idempotenz und Belegabfrage. Deshalb bleibt unser PCS-Schreiben gesperrt.
[OSLC AM](https://sparxsystems.com/enterprise_architect_user_guide/17.2/the_model_repository/info_accessed_via_oslcam.html),
[EA-Updates](https://sparxsystems.com/enterprise_architect_user_guide/17.2/the_model_repository/oslc_upd_resources.html).

Aktuelle [Umsetzungsnachweise](../qa/1075-completion-evidence.md) und
[offene Produktabnahme](../qa/sparx-compatibility.json) bleiben getrennt dokumentiert.

### Echte zweisprachige Browserbeispiele

[Die sieben EN/DE-Bildpaare](../qa/conditional-publication-browser.md) zeigen
Paketknöpfe, Endpunktprojektion, gerichtete Prüfung, Teilresultat mit UNKNOWN,
Wiederaufnahme und freigegebenen Abgleich samt verknüpfter Folge-Vorschau.
Die unveränderten Bilder stammen aus echter CI und wurden vollständig visuell
geprüft; Quelle und Artefakthashes sind dokumentiert. **NUR TEST / TEST ONLY**,
keine EA-/PCS-Produktabnahme.
