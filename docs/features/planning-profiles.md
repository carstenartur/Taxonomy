# Planungsangaben: begrenzte, produktneutrale Profile

## Bedienung und fachliche Grenze

Im Architektureditor steht **Planungsangaben** für die kanonischen Anforderungen des
gewählten Workspace zur Verfügung. Eine Anforderung, eine vorhandene Angabe oder
**Neue Angabe** auswählen, Felder und Begründung ausfüllen, die Änderung prüfen und
über die vorhandene Editorvorschau übernehmen. Die bestehende Rollenprüfung,
optimistische Revision, Wiederholbarkeit, Rückgängig/Wiederholen und Historie gelten
auch hier. Historische Stände bleiben schreibgeschützt. Originaltexte und offizielle
Taxonomieeinträge werden nicht geändert. Der Bereich erzeugt keine unabhängige
zweite Anforderung, wenn eine Portfolioanforderung noch nicht in der kanonischen
Workspace-Projektion vorhanden ist.

| Profil / Version | Felder | Bedeutung |
|---|---|---|
| `go-live` / `1` | `precision`: `YEAR` oder `DATE`; `value` | Geplantes Zieljahr `2030` oder genaues Datum `2030-06-01`. Keine Annahme eines Tages bei Jahresgenauigkeit; keine Bestätigung des Betriebs. |
| `standard-reference` / `1` | `identifier`; optional `edition`, `section` | Quellenbezug auf eine Norm oder interne Vorgabe. Kein Nachweis der Erfüllung und keine automatische Verbindlichkeit. |

Für Vorgaben werden vorhandene `source`, `sourceVersion`, `sourceFragment` und
`requirementSourceLink` wiederverwendet. Mehrdeutige Quellenkennungen müssen bewusst
aufgelöst werden. Fehlt die Ausgabe, wird keine erfunden; eine dennoch angegebene
Fundstelle bleibt als nicht versionsgebundener Verweis erhalten. Die Verknüpfung
hat die Bedeutung `references`. Beim Entfernen einer Angabe werden gemeinsam
genutzte Quellen nicht gelöscht. Rückgängig darf ebenfalls keine fremden
Verknüpfungen beschädigen.

Die Angaben gehören zur konkreten Anforderung im ausgewählten Workspace und
Planungsstand. Eine vorhandene Architekturzuordnung bleibt erhalten; eine Vorgabe
wird nicht automatisch auf alle erreichbaren Bausteine vererbt. Ein neues Profil
für Beziehungen oder Elemente erfordert eine explizite fachliche Projektion; diese
erste Lieferung implementiert nur die Anforderungsprofile.

## Speicherung und Erweiterungsgrenze

`PlanningInformation` verwaltet den begrenzten Namensraum
`x-planning.<entryId>.*` auf dem vorhandenen kanonischen Anforderungsblock. Der
Vorgabenbezug speichert darin nur den Verweis auf seine kanonische
Quellenverknüpfung. Die Portfolio-Neuerzeugung erhält diesen Namensraum, während
Text und Geschäftsfelder weiterhin dem Portfolio gehören. Es gibt keine neue
Datenbanktabelle, keine neue Historie und keinen zusätzlichen Dienst.

`PlanningProfile` ist eine reine Java-Schnittstelle mit versionierter Kennung,
Feldbeschreibung, Validierung und optionaler Speicherung/Leseprojektion auf dem
bestehenden Modell. Weitere Profile können über `ServiceLoader` registriert
werden (`META-INF/services/com.taxonomy.dsl.planning.PlanningProfile`). Die kleine
Feldbeschreibung ist kein ausführbares Schema und lädt keine entfernten Inhalte.
Ein zusätzliches Testprofil prüft die Erweiterbarkeit ohne Änderung der allgemeinen
Editor- und Austauschsteuerung. Produktadapter bleiben eine getrennte Grenze.

Profilkennung und Profilversion sind zusammen die Identität des Schemas. Eine
Änderung der Bedeutung erfordert eine neue Version und eine ausdrückliche
Überführung; gespeicherte Daten werden nicht stillschweigend umgedeutet.
Unbekannte Profilversionen bleiben sichtbar und nicht editierbar. Sie dürfen über
einen ausdrücklichen Löschbefehl entfernt werden. Fehlerhafte bekannte Werte und
beschädigte Namensräume sind sichtbar, bleiben in der DSL erhalten und werden
nicht als gültige Fakten exportiert. Die Herkunftsangabe ist ein Herkunftsmerkmal,
keine Freigabe oder Zertifizierung; der tatsächliche Bearbeiter steht im bestehenden
Operationsjournal.

Grenzen: 32 Angaben pro Anforderung, 32 Felder pro Angabe, 4096 Zeichen pro Wert,
65536 Zeichen im gesamten Planungsnamensraum der Anforderung. Kennungen sind
begrenzt und enthalten keine beliebigen ausführbaren Namen oder Pfade.

## ReqIF-Austausch

Nur der vorhandene ReqIF-Adapter wird erweitert. Die reservierte STRING-Eigenschaft
`Taxonomy.PlanningProfiles.v1` transportiert einen begrenzten JSON-Umschlag
(`schema: 1`, `entries`). Im neutralen Austauschobjekt heißt die Erweiterung
`planningProfilesV1`. Der Umschlag enthält Kennung, Profil, Version, Herkunft und
strukturierte Werte; maximal 131072 Zeichen und acht JSON-Verschachtelungsebenen.
Doppelte JSON-Schlüssel, doppelte Angabekennungen, falsche Typen, nachfolgende
Tokens und unbekannte **Umschlagversionen** werden abgelehnt. Eine unbekannte
**fachliche Profilversion** innerhalb eines gültigen Umschlags bleibt erhalten.

Die ReqIF-XSD-Prüfung und die bestehenden Identitätsregeln bleiben aktiv. Die
Attributdefinition wird im tatsächlichen Objekttyp ergänzt; bestehende kleinere
`MAX-LENGTH`-Grenzen werden nicht durch Abschneiden oder stilles Erweitern umgangen.
Für einen Rückexport sind die aktuellen kanonischen Werte maßgeblich, nicht die
alten Werte einer früheren Datei.

Der Adapter meldet für die beiden Profile:
- Darstellung: `REQIF_STRING_ENVELOPE`;
- Rundlaufumfang: `TAXONOMY_PROFILE_ONLY`;
- native fachliche Auswertung durch das Fremdprodukt: **nicht zugesagt**.

Damit wird kein Normenmodul oder Terminplanungsmodul eines Herstellers behauptet.
Ein Fremdwerkzeug muss das zusätzliche Attribut tatsächlich erhalten, damit der
Rundlauf funktioniert. Vorschau und Verlustbericht zeigen die eingeschränkte
Darstellung. Andere Formatadapter haben dadurch keine automatische Unterstützung.

Fehlende Planungsangaben im Rückimport sind kein Löschauftrag. Die lokale
Information bleibt erhalten; der beobachtete externe Stand behauptet dabei nicht,
dass die fehlenden Daten dort vorhanden seien. Das gilt auch für einen leeren
Umschlag. Ein Dateidownload ist weiterhin keine externe Übernahmebestätigung.

## Nicht enthalten

Keine automatische Normenprüfung, Normenvolltexte, zeitliche Abdeckungsrechnung,
neuen Live-Produktadapter, Hintergrundsynchronisation, grafischen Profilbaukästen
oder neuen Issues. Die ursprüngliche Anforderung wird nicht neu formuliert.

## Tests

Die JUnit-Einstiege `PlanningInformationTest`, `ReqifPlanningTest` und
`PlanningExchangeBridgeTest` liegen in ihren jeweiligen Fachmodulen. Ihre
abhängigkeitsarmen Contract-Runner lassen sich zusätzlich direkt ausführen.
Der JavaScript-Test `.github/scripts/architecture-planning.test.mjs` wird durch
`test:integrations-review` in beide bestehenden UI-Verifikationsketten eingebunden.

Details der ausgeführten Prüfungen und ihrer Grenzen:
[Verifikationsbericht](../testing/planning-profiles-verification.md).
