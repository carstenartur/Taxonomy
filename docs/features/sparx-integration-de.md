# Austausch mit Sparx Enterprise Architect

Status: **experimentelle XMI-Implementierung; Produktkompatibilität noch ungeprüft**.
Dies ist ein Umsetzungsschritt von [#1075](https://github.com/carstenartur/Taxonomy/issues/1075).
Das Issue bleibt offen. Für die Produktabnahme standen weder Enterprise Architect
noch Pro Cloud Server zur Verfügung. Die synthetische Testdatei belegt keine
Unterstützung einer bestimmten EA-Version.

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
      civilian-pcs:
        repository-id: <repository-id>
        organization-id: USER:<repository-eigentuemer>
        base-uri: https://pcs.example.org/model/oslc/am/
        credential-environment-variable: CIVILIAN_PCS_TOKEN
        allow-private-networks: false
        allow-insecure-http: false
```

`CIVILIAN_PCS_TOKEN` enthält die PCS-Sitzungs-GUID aus dem
[dokumentierten Modell-Login](https://sparxsystems.com/enterprise_architect_user_guide/17.2/the_model_repository/oslc_user_cred.html).
Login, SSO und Token-Erneuerung erfolgen in diesem Schritt administrativ.
Der Transport sendet `Authorization: OSLC <token>` und den erforderlichen
Abfrageparameter `useridentifier`. Zugriffsprotokolle von PCS/Proxies müssen diesen
Parameter maskieren. Taxonomy speichert nur den Profilschlüssel und Ressourcen ohne
Zugangsdaten. Ein fehlgeschlagener Abruf kann nach Erneuerung des serverseitigen
Tokens mit derselben Vorgangs-ID fortgesetzt werden.

Auf der Integrationsseite AM-Profil, zulässigen Modus und `civilian-pcs` wählen.
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
keine zwischenzeitliche Änderung. Live-Anlage/-Änderung/-Löschung, teilweise
Veröffentlichung und vollständiges Push/Synchronize bleiben daher bis zu
einem nachgewiesenen PCS-Vertrag oder bedingten Sparx-Adapter offen. Das optionale
SBPI-Plugin ist ebenfalls nicht umgesetzt.

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
