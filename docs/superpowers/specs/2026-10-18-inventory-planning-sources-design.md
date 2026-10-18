# Produktneutraler Bestands- und Planungsabgleich

Stand: 18. Oktober 2026. Präzisiert nach Nutzerrückmeldung: Analyse und/oder
Architektur, frei ausgewählte Teiltaxonomien und reale zeitbezogene Produktplanungen.
Status: ausgearbeiteter Entwurf zur fachlichen Prüfung.
Die Konzeption wurde beauftragt; dieser Text ist noch keine freigegebene Implementierung.
Geprüfte Codebasis: `dce3bf00a2240412f9feefd3e02b0997f194b7a5`.

## 1. Zweck und erster nutzbarer Umfang

Taxonomy soll **gespeicherte Analysen und/oder Architekturstände in ausgewählten
Teiltaxonomien** gegen reale, autorisierte Produktbestände und Produktplanungen für
bestimmte Zeiträume prüfen. Eine fertige Gesamtarchitektur, vollständige Relations-
generierung oder vollständige Analyse aller Teiltaxonomien ist keine Voraussetzung.
IP und UA sind naheliegende Beispiele, keine fest codierte Beschränkung.
LeanIX, USU, Alfabet sowie andere freigegebene Bestands-, Planungs- und Dokumentquellen
können Informationen beitragen. Ihre Namen dürfen die fachliche Verarbeitung nicht
bestimmen; insbesondere ist Alfabet nicht das gemeinsame Referenzmodell.

Der Benutzer wählt den gespeicherten Analyse- und/oder Architekturstand, eine oder
mehrere Teiltaxonomien beziehungsweise konkrete offizielle Knoten, Zielorganisation,
gegebenenfalls Zielumgebung und die Zeitfrage. Er wählt serverseitig freigegebene
Quellenbereiche und startet den Abgleich. Ohne Projektbindung bleibt das Ergebnis
ein Nachweis am gespeicherten Analyse-/Workspace-Kontext; eine spätere Portfolioauswahl
verlangt den dafür autorisierten Projektkontext. Kein künstliches Projekt nur zum Lesen.

**Erstes sichtbares Ergebnis:** Je ausgewähltem Analyse-/Architekturbedarf erscheint
eine Gegenüberstellung aus Knoten, begründeter Produktzuordnung, realem Produkt oder
Fachverfahren, gegebenenfalls Version/Service/Bereitstellung, Bestands- oder
Planungsstatus, belegtem Zeitraum, Quelle und nächstem Klärungs-/Änderungsschritt.
„Produkt passt begrifflich“, „Produkt ist vorgesehen“ und „Produkt steht diesem
Vorhaben im Zeitraum zur Verfügung“ sind getrennte Aussagen. Das Ergebnis ist bereits
ohne Übernahme in die Architektur nutzbar und als begrenzter Abgleichbericht ausgebbar.

### 1.1 Analyseabgleich und Architekturabgleich

Beim **Analyseabgleich** bleiben Originalanforderung, Analyseergebnis, Begründungen
und Knotenbewertungen unverändert. Produkte werden über belegte oder ausdrücklich
geprüfte Zuordnungen gegenübergestellt; ein Taxonomiewert in Prozent ist weder
Produkt-Eignungsgrad noch Nutzungswahrscheinlichkeit. Ein Realprodukt kann mehrere
Knoten abdecken, ein Knoten kann mehrere Produkte/Kandidaten haben. Keine Produktanlage
für jedes Blatt, keine Normierung der Scores auf die ausgewählten Teiltaxonomien.
Ein abweichender Bestandsnachweis kann einen nachvollziehbaren Hinweis zur erneuten
Prüfung der Analyse begründen, aber keine automatische Neubewertung.

Beim **Architekturabgleich** kommen die ausgewählten konkreten Elemente und deren
vorhandene Beziehungen hinzu. Fehlende Relationsgenerierung verhindert den
Produktabgleich nicht; Anschlussfähigkeit bleibt dann ausdrücklich ungeprüft.
Außerhalb der Auswahl liegende Knoten, fehlgeschlagene Analysen und noch nicht
analysierte Bereiche sind NICHT_GEPRÜFT, nicht Nullbedarf oder Produktlücke.
Widersprüchliche oder nicht zusammengehörige Analyse-/Architekturstände werden nicht
stillschweigend vereinigt. Ein Vergleich nennt die tatsächlich verwendeten Stände.

Erste Stufe: lesender, manuell ausgelöster Abgleich mit lokaler menschlicher Prüfung.
Keine gleichzeitige Pflichtimplementierung aller Herstelleradapter. Kein externes
Schreiben, automatischer Hintergrundabgleich, vollständiger CMDB-Spiegel, zusätzlicher
Modell-Editor oder automatische Beschaffung. Originaltaxonomie und Bedarf bleiben
unverändert. Begründete Auswahlentscheidungen nutzen die bestehenden Portfolioabläufe.

Die [öffentlich belegten Praxisfälle](2026-10-18-inventory-planning-sources-real-cases.md)
motivieren den Ablauf mit tatsächlichen Produkten, Umstellungen und Quellenrollen.
Öffentliche Nachweise, daraus abgeleitete Prüffragen und noch benötigte interne
Pilotdaten sind dort ausdrücklich getrennt.

## 2. Entscheidungen und verworfene Alternativen

| Entscheidung | Begründung |
|---|---|
| Kleiner typisierter Nachweiskern mit optionalen Profilen | Name und Beschreibung reichen nicht; sämtliche Herstellermodelle zu vereinigen wäre unnötig groß. |
| Technischer Adapter, Anschlussprofil und fachlicher Abgleich getrennt | API-Felder und lokale Metamodelle dürfen keine Eignungslogik im Herstelleradapter erzeugen. |
| Mehrere Quellen zulässig; zunächst nur ein produktiver Adapter | Portfolio, Betrieb und Nutzungsberechtigung können verschiedene Quellen haben. |
| Bestehende Integrations- und Portfolioarchitektur erweitern | Keine zweite Identitätsverwaltung, Synchronisationsmaschine oder Domänenpersistenz. |
| Fakten, abgeleiteter Befund und menschliche Entscheidung getrennt | Kandidatenähnlichkeit, tatsächliche Nutzbarkeit und bestätigte Auswahl sind nicht identisch. |

Nicht gewählt werden ein Alfabet-Berichtsformat als Universalmodell, ein beliebiger
REST-Proxy und eine vorsorgliche Implementierung aller Anbieter. CSV/XLSX und
ArchiMate können später denselben Nachweiskern beliefern; ein neuer Tabellenimport
ist keine zwingende Vorleistung für den ersten Alfabet-Anwendungsfall.

## 3. Verantwortlichkeiten

**Technischer Adapter:** authentifiziert sich gegen eine freigegebene Installation,
ruft nur deklarierte Lesefunktionen auf, verarbeitet deren Paging/Fehler und begrenzt
Aufwand. Er liefert keine Bewertung und keine Berechtigung zur Modelländerung.

**Anschlussprofil:** versionierter administrativer Vertrag für genau eine
Installation und deren Metamodell. Er erklärt Quellklassen, Untertypen, Felder,
Datentypen, Statuswerte, Beziehungstypen und Richtungen. Zugriffskonfiguration und
Zuordnungsregeln sind getrennt von Geheimnissen. Freie Skripte, SQL und vom Browser
gelieferte Ziel-URLs gehören nicht zum Vertrag.

**Gemeinsamer Abgleich:** verarbeitet normalisierte Nachweise gegen den eingefrorenen
Bedarf. Er erzeugt Kandidaten und Kriterienbefunde. Die vorhandene Portfoliofunktion
speichert nach Prüfung Auswahl, Begründung und Anforderungsbezug. Produktspezifische
REST-/GraphQL-/Berichtsfelder erreichen diese Logik nicht.

## 4. Gemeinsamer Vertrag v1

Die folgenden Namen sind Entwurfsbegriffe, keine Behauptung vorhandener Java-Klassen.
Sie beschreiben Transport- und Nachweiswerte, kein zweites kanonisches Architekturmodell.

### 4.1 Identitäten und Umfang

| Wert | Verbindlicher Inhalt |
|---|---|
| Quellenbeschreibung | Stabile administrative Instanz-ID, Produkt/Edition/Version soweit bekannt, Quellrepository/Mandant, Fähigkeiten, Profil-ID und unveränderliche Profilversion. |
| Objektidentität | Tupel aus Instanz-ID, dem vom Profil erklärten ID-Namensraum und originaler externer ID. Verbindung, Bezeichnung, Profilversion und Abruf sind nicht Bestandteil der fachlichen Identität. |
| Abgleichauftrag | Autorisierter Repository-/Workspace-Kontext, gegebenenfalls Projekt; eingefrorener Analyse- und/oder Architekturstand samt Anforderungsversion und Katalogstand; ausgewählte Teiltaxonomien/offizielle Knoten, Quellenbereiche und Zeitfrage; feste Budgets und Bewertungsversion. |
| Quellenbeobachtung | Operations-ID, eingefrorene Profilversion, Selektor-Fingerprint, Abrufbeginn/-ende, Datensätze, Nachweise, Verluste sowie getrennte Abruf- und Vollständigkeitsangaben. |

Die Bedarfsauswahl und der externe Quellselektor sind verschiedene Dinge.
Der Bedarf benennt reale Katalogidentitäten aus dem gespeicherten Katalogstand sowie
die Analyse-/Elementidentitäten und Begründungen. Eine Root-Bezeichnung oder das
Namenspräfix eines Herstellerobjekts beweist keine fachliche Zuordnung.
Ein gespeicherter Filter „relevante Knoten“ muss festhalten, ob er explizite Nullwerte,
fehlende und nicht ausgeführte Bewertungen ausschließt; diese Zustände bleiben erhalten.

Ein Selektor benennt erlaubte Objektarten, organisatorischen Umfang und freigegebene
Abfrage-/Berichtsparameter. Er ist keine frei übertragbare Suchsprache. Eine Änderung
des Selektors erzeugt einen neuen Beobachtungsumfang, nicht vermeintliche Löschungen.
Verbindungswechsel zur gleichen administrativ bestätigten Instanz darf Identitäten
nicht duplizieren. Instanzgleichheit wird nie aus gleicher URL oder gleichem Namen geraten.

### 4.2 Objektarten und Kontext

| Gemeinsame Art | Bedeutung und Grenze |
|---|---|
| `APPLICATION` | Fachverfahren oder logische Anwendung, nicht automatisch eine Installation. |
| `SERVICE` | Beschriebene Leistung; das Profil unterscheidet Servicebeschreibung und konkretes Angebot. Eine Beschreibung allein begründet keine Nutzungsberechtigung. |
| `TECHNOLOGY_PRODUCT` | Technologie-/Produktdefinition einschließlich belegter Edition oder Version, keine bereitgestellte Ressource. |
| `DEPLOYMENT` | Konkrete Bereitstellung/Serviceinstanz mit gegebenenfalls belegter Umgebung. Sie kann mehrere Anwendungen betreffen. |
| `OTHER` | Nicht eindeutig zugeordneter Quelltyp; als Nachweis erhalten, nicht in einen passenden Typ umdeuten. |

Jedes Objekt behält Quelltyp und Untertyp neben der gemeinsamen Art. Ein unbekannter
Untertyp wird nicht allein anhand seines Namens klassifiziert. Anzeigeersatz durch
externe ID ist zulässig, darf aber einen fehlenden Namen nicht als Quellwert ausgeben.
Organisationen, Fähigkeiten, Schnittstellen und Standards können v1 als typisierte
Kontextreferenzen/Nachweise begleiten; sie sind nicht automatisch zusätzliche
wiederverwendbare Komponenten. Eigentümer, Betreiber und berechtigte Nutzer bleiben
verschiedene Rollen. Ein Quellzugriffsrecht ist kein Recht zur Nutzung eines Services.

### 4.3 Eigenschaften, Zeit und Herkunft

Eine Eigenschaft enthält Schlüssel, typisierten Wert und Nachweisverweis. Unterstützt
werden begrenzte Texte, Boolean, Dezimalzahl mit optionaler Einheit, Datum mit Präzision,
Referenz und begrenzte Mengen dieser Werte. Das Anschlussprofil erklärt nur tatsächlich
benötigte Felder und Transformationen; unbekannte Felder gelangen nicht ungefiltert in
Persistenz oder LLM-Kontext. Benötigte, aber nicht abbildbare Semantik erzeugt einen
expliziten Verlust. Geheimnisse und unzulässige personenbezogene Inhalte bleiben draußen.

`PRESENT`, `NULL`, `ABSENT`, `NOT_SUPPORTED` und `UNAVAILABLE` sind unterschiedliche
Nachweiszustände. `false`, `0` und leerer Text sind vorhandene Werte, keine Abwesenheit.
`UNAVAILABLE` umfasst nicht zugängliche oder fehlerhafte Angaben, ohne die Existenz
verborgener Daten offenzulegen. Widerspruch entsteht aus mehreren Aussagen; er wird
nicht durch einen einzelnen fehlenden Wert ersetzt.

Ein Nachweis referenziert Quelle, Objekt/Zeile, Feld, Beobachtung und Profilversion.
Originalwert und Normalisierung werden für die freigegebenen relevanten Felder
bewahrt. Originalquelle, menschliche Ergänzung und maschinelle Ableitung bleiben
unterscheidbar. Abrufzeit ist weder Änderungszeit noch fachliche Gültigkeit.

Lebenszyklus-/Planungsangaben erklären `OBSERVED`, `DECLARED` oder `PLANNED`,
Zeitraum, Präzision und betroffenen Organisations-/Betriebskontext. Statuszuordnungen
behalten den Rohwert. Unbekannte Statuswerte werden nicht `ACTIVE`. Produktfreigabe
durch den Hersteller, organisatorischer Rollout und konkrete Inbetriebnahme sind
unterschiedliche Ereignisse. Ein fehlendes Enddatum ist nicht unbegrenzte Nutzbarkeit.

**Die Zeitfrage ist Teil des Auftrags, nicht nur ein Anzeigefilter:**

| Zeitfrage | Semantik |
|---|---|
| Stichtag | Nutzbarkeit an einem konkret gewählten Datum. |
| Startfenster | Beispielsweise „Einführung irgendwann 2030“; mögliche Termine `[2030-01-01, 2031-01-01)`, kein erfundener Start am 1. Januar. |
| Nutzungszeitraum | Beispielsweise „durchgehend 2028 bis einschließlich 2030“; belegte Abdeckung des ganzen Intervalls `[2028-01-01, 2031-01-01)` erforderlich. |

Vollständige belegte Abdeckung kann das Zeitkriterium stützen. Nur teilweise
Überschneidung beziehungsweise ein unbekannter Anfang oder ein unbekanntes Ende
belegen keine vollständige Abdeckung. Widerlegt ein belegtes Ende bereits einen
Teil eines geforderten durchgängigen Nutzungszeitraums, ist dessen vollständige
Abdeckung `CONTRADICTED`. Beim offenen Startfenster bleibt teilweise Überlappung
`UNKNOWN`; nur Ausschluss sämtlicher möglicher Starttermine widerlegt dieses Kriterium.
Zeitlücken, Nachfolger und bekannte Überlappungen erscheinen als Teilbefunde.
Keine unbelegte automatische Migration zwischen zwei Produkten.

Eine geplante Nutzbarkeit bleibt geplant, auch wenn der Zeitraum passt. Ein historischer
Nachweis wird nicht zur aktuellen Bestätigung oder Zukunftszusage. Publikationsdatum
und Abrufdatum ersetzen weder Anfang noch Ende eines Einsatzzeitraums. Öffentlich
belegter Zuschlag belegt nicht den produktiven Rollout. Fehlende genaue Termine werden
nicht ergänzt. Normverweise belegen einen Bezug, niemals allein Konformität.

### 4.4 Beziehungen

Eine Beziehung bewahrt Ausgangs-/Zielidentität, Quelltyp, Richtung, optional eigene
externe ID, Eigenschaften und Nachweise. Das Profil darf beispielsweise
`USES`, `PROVIDES`, `REALIZES`, `DEPENDS_ON` oder `REPLACES` zuordnen; nicht beweisbare
Semantik bleibt `UNMAPPED`. Diese Begriffe ersetzen keine offiziellen Taxonomierelationen.

Ohne externe Beziehungs-ID wird eine beobachtungsbezogene Nachweis-ID verwendet.
Nur ein vom Profil belegter eindeutiger natürlicher Schlüssel erlaubt dauerhaftes
Wiedererkennen. Die ID des Basisobjekts einer Berichtszeile ist keine Beziehungs-ID.
Parallele Beziehungen und mehrere Referenzen zum gleichen Ziel werden nicht durch
vermutete Gleichheit gelöscht. Nicht geladene Endpunkte bleiben unaufgelöste
Referenzen; nur zugriffsberechtigte Informationen dürfen angezeigt werden.
Eine fachliche Abhängigkeit belegt keine vorhandene technische Verbindung.

### 4.5 Fähigkeiten und Vollständigkeit

Fähigkeiten sind deklarierte Eigenschaften des Anschlusses: unterstützte Objektarten,
Felder/Profile, Beziehungstypen, begrenzte Selektion und Lesen. Paging, Detailabruf,
Änderungsstände und atomare Snapshots werden nur angeboten, soweit nachgewiesen.
Fehlende Detail- oder Änderungsfunktionen verhindern nicht grundsätzlich die Teilnahme.
Keine Lese-Fähigkeit gewährt Schreibrechte; v1 unterstützt keine Publikation.

Drei unabhängige Achsen gelten pro Quelle und ausgewähltem Datensatz:

- Abruf: `SUCCEEDED`, `FAILED`, `CANCELLED` oder `LIMIT_REACHED`.
- Umfang: `EXHAUSTED_SELECTION`, `PARTIAL` oder `UNKNOWN`.
- Konsistenz: `ATOMIC_VERIFIED` oder `NON_ATOMIC_OR_UNKNOWN`.

Alle Seiten gelesen heißt nur: die unter diesen Rechten und Filtern ausgewählte
Auflistung ist erschöpft. Das beweist weder globale Vollständigkeit noch einen
atomaren Zeitpunkt. Hashes dienen dem Wiedererkennen, nicht als erfundene externe Revision.
Gleiches gilt für Dateidaten: Dateihash und Dateizeit sind keine Bestandsvollständigkeit.

## 5. Gemeinsame Bewertung und menschliche Auswahl

Kandidatensuche verwendet zuerst bestätigte Zuordnungen und anschließend begrenzte
Suche im autorisierten Nachweisbestand. Namensähnlichkeit und Taxonomieabdeckung
sind Suchsignale, keine Eignungsnachweise. V1 benötigt keinen LLM-Aufruf; spätere
KI-Vorschläge müssen ebenfalls Quelle und Unsicherheit ausweisen und ungeprüft bleiben.

Pro Kandidat und gefordertem Kriterium lautet der Befund `SUPPORTED`, `CONTRADICTED`,
`UNKNOWN` oder `CONFLICT`. Jedes Ergebnis nennt Nachweise beziehungsweise deren Fehlen.
Nicht geforderte Eigenschaften werden nicht künstlich zum Hindernis. Bewertet werden
je nach Bedarf Funktion, Typ, Zeit, Nutzungsumfang und benötigte Beziehungen.
Ein hartes Gegenargument darf nicht durch einen hohen Suchscore überstimmt werden.
Die Zusammenfassung unterscheidet verwendbar nach Nachweislage, bedingt/ungeklärt,
geplant oder nicht geeignet; widersprüchliche Kriterien bleiben sichtbar.

Quellenübergreifend gilt keine automatische Namensgleichsetzung und kein
Last-write-wins. Bestätigte Querverweise verbinden Objekte ohne Verlust ihrer IDs.
Eine explizite, versionierte Zuständigkeit kann je Feld und Gültigkeitsbereich eine
maßgebliche Quelle festlegen; Gegenbelege bleiben erhalten. Ein neuer Abruf ändert
keine bereits begründete Entscheidung. Er kann sie als erneut prüfbedürftig markieren.

Lokale Auswahl verwendet die vorhandenen Portfoliozustände `PROPOSED`, `EVALUATED`,
`SELECTED` und `REJECTED` sowie die bestehenden Maßnahmen wie `REUSE`, `CHANGE` oder
`UNDECIDED`. Ein reiner Bestandsabgleich setzt nicht `IMPLEMENTED`/`SATISFIED`.
Keine Treffer ergeben einen offenen Bedarf im Suchumfang, nicht automatisch `CREATE`
oder `PROCURE`. Die Begründungskette bleibt Bedarf → Nachweis → Befund → Entscheidung.

## 6. Ausführung, Sicherheit, Speicherung und Oberfläche

Vor jedem Abruf werden Benutzer-, Repository-, Organisations- und Quellzugriff geprüft.
Profile sind serverseitig gebunden; technische Konten dürfen keine über den genehmigten
Taxonomy-Nutzerkreis hinausgehenden Daten sichtbar machen. Schlüssel, Filter,
Fehlermeldungen, Suchindex und Cache müssen diese Trennung ebenfalls einhalten.
Nachweise gelten als untrusted input: HTML nur escaped anzeigen, keine eingebetteten
URLs/Anweisungen ausführen und keine Quelltexte ungeprüft an externe Modelle senden.

Ein Auftrag friert Bedarf, Profile, Selektoren und Budget ein. Je Quelle werden Daten
begrenzt eingelesen, normalisiert und als ein zusammengehöriger Beobachtungsstand
bereitgestellt. Fehler in erforderlichen Teilabfragen verhindern dessen erfolgreiche
Veröffentlichung. Diagnostische Teildaten sind keine akzeptierte neue Bestandsversion.
Erfolgreiche andere Quellen bleiben eigenständig nutzbar; über fehlende Quellen hinweg
ist keine vollständige Bedarfserfüllung zu behaupten.

Bei technischem Refresh-Fehler bleibt der vorige zulässige Stand als alt markiert.
Nach Entzug von Zugriff wird er dagegen nicht weiter angezeigt oder exportiert, bis
Zugriff erneut autorisiert ist. Eine fehlgeschlagene Quelle wird nie durch eine leere
Liste ersetzt. Datenbanktransaktionen bleiben kurz und umschließen keinen Netzaufruf.

Die vorhandene Integrationspersistenz speichert begrenzte Nachweispakete, Operationen
und Querverweise; Portfolioentscheidungen referenzieren diese unveränderlich. Vorhandene
Backup-/Restore- und Geheimnisausschlussregeln müssen die Ergänzung abdecken. Es wird
kein frei bearbeitbarer Spiegelbestand aufgebaut. Nicht referenzierte Abrufe sind
nach konfigurierter Aufbewahrung löschbar; für Entscheidungen notwendige Nachweise
werden nicht ohne sichtbaren Nachweisverlust entfernt. Suchindizes sind abgeleitet.

Startgrenzen für die spätere Umsetzung: höchstens drei Quellen pro Auftrag, insgesamt
10.000 Datensätze, 100 Requests, 16 MiB dekodierte Nutzdaten und 120 Sekunden;
2 MiB pro Antwort und 100 sichtbare Kandidaten pro Bedarf. Dies sind Sicherheitslimits,
keine gemessenen Leistungszusagen. Adapter-/Installationsgrenzen dürfen strenger sein.
Zusätzlich sind 256 ausgewählte Felder pro Objekt, 64 KiB pro Feld und begrenzte
Parser-Tiefe vorzusehen. Grenzen begrenzen Objektaufwand mit; ein Payloadlimit ist
kein Heaplimit. Den Speicherbedarf muss eine spätere Messung separat belegen.
Budgetende erzeugt `LIMIT_REACHED`; eine begrenzte Kandidatenliste zeigt ihre Begrenzung.
Kein unbegrenzter Bestandsabruf pro Architekturblatt und keine verdeckten Wiederholungen.

Die vorhandene Analyse-/Integrations-/Portfoliooberfläche erhält die gemeinsame
Aktion „Mit Bestand und Planung abgleichen“, nicht Herstellerknöpfe neben jedem
Diagramm. Sie ist auch aus einer gespeicherten Teiltaxonomie-Analyse erreichbar.
Ergebnis und Bericht nennen Analyse-/Architekturstand, Katalogstand, ausgewählte
Teiltaxonomien/Knoten, Zeitfrage, Quellenalter/-umfang, Kandidaten und Kriterienbefunde.
Zählwerte unterscheiden geprüfte Bedarfe, gefundene Produkte, belegte/geplante
Zeiträume, Gegenargumente, offene Nachweise und nicht geprüfte Bereiche; kein pauschaler
Prozentwert suggeriert eine vollständige Architekturprüfung.
Bestätigen/Verwerfen verlangt Begründung, aktuelle Zugriffsrechte und exakten internen
Stand. Wiederholtes Bestätigen derselben Entscheidung erzeugt keine Duplikate.
Ein veralteter Preview verlangt erneute Prüfung. Dabei werden lokal bekannte neue
Beobachtungen und Profilstände verglichen; unbekannte externe Änderungen können ohne
entsprechende Quellgarantie nicht erkannt werden. Die Entscheidung benennt deshalb
immer ihren beobachteten Datenstand. Rendering ruft nie externe Quellen ab.

## 7. Einordnung in die vorhandenen Module

| Vorhandener Ort | Verwendung und begrenzte Erweiterung |
|---|---|
| `taxonomy-extension-api`, `IntegrationContracts` | Kontext, externe Referenzen und Fähigkeitsprinzip wiederverwenden; typisierte Nachweiswerte als additive Begleitverträge, ohne ReqIF-/ArchiMate-Verhalten umzudeuten. |
| `taxonomy-interop` | Adapter, Anschlussprofile, Abruf und Beobachtungsablage in der vorhandenen Orchestrierung. Bestehende Registry nur um wirklich nutzbare Fähigkeiten erweitern. |
| `IntegrationPortfolioPort` | Ist heute projekt-/anforderungsorientiert; durch einen kleinen fachlichen Bestandsport ergänzen, keine fremden JPA-Entities übergeben. |
| `taxonomy-portfolio` | Bestehende Lösungen, Produkte, Anforderungsbezüge und Entscheidungen nutzen; gemeinsame Kriterienbewertung hier verankern. |
| `taxonomy-app` | Autorisierte Komposition und vorhandene Oberfläche. Kein Rückverweis aus Bibliotheken auf die Anwendung. |
| Bestehendes Reporting/Backup | Eingefrorene Nachweise projizieren beziehungsweise sichern; keine parallelen Ablage- und Abgleichwege. |

Die bestehenden `Map<String,String>`-Austauschfelder reichen für typisierte Werte und
fehlende/null/nicht unterstützte Angaben allein nicht aus. Ein einzelnes
`completeScope` ersetzt ebenfalls nicht die drei oben beschriebenen Achsen.
Daher werden ergänzende Nachweisverträge benötigt, kein pauschales Ersetzen bestehender
öffentlicher Verträge und kein Ablegen ganzer Bestände in Portfolio-Extension-Strings.

Codebezüge: [Integrationsverträge](../../../taxonomy-extension-api/src/main/java/com/taxonomy/extension/api/integration/IntegrationContracts.java),
[Portfolioport](../../../taxonomy-interop/src/main/java/com/taxonomy/interop/IntegrationPortfolioPort.java),
[Lösungsservice](../../../taxonomy-portfolio/src/main/java/com/taxonomy/portfolio/service/SolutionPortfolioService.java),
[Portfoliozustände](../../../taxonomy-portfolio/src/main/java/com/taxonomy/portfolio/model/PortfolioTypes.java).

## 8. Ein erster Pilot, weitere Wege erst nach Bedarf

[PR #1260](https://github.com/carstenartur/Taxonomy/pull/1260) enthält bei Head
`7405443f479843bf5ac0a94bbe821c6350a47bea` eine noch nicht aktivierte Bibliotheksvorbereitung.
Ihr Berichtsleser ist technischer Kandidat, kein allgemeiner Bestandsvertrag.
Vor Aktivierung fehlen insbesondere autorisierte Instanzbindung, Zuordnungsprofil,
gemeinsame Nachweise, Portfolioablauf und Produktabnahme. Der offene PR wird durch
dieses Dokument weder als geprüft noch als mergebereit erklärt.

Zuerst werden gemeinsamer Vertrag, Gegenproben und reale Praxisfälle fachlich
geprüft. Danach folgen Implementierungsplan und ein begrenzter durchgängiger Pilot:
gespeicherte Analyse oder Architektur auswählen → reale Produkt-/Planungsnachweise
beziehen → ausgewählte Knoten zeitbezogen abgleichen → Ergebnisse und nächste Schritte
anzeigen. Die Wahl der ersten Quelle richtet sich nach tatsächlich freigegebenen
Pilotdaten. Alfabet bleibt durch #1260 ein vorbereiteter Kandidat, aber kein
fachlicher Zwang. LeanIX und USU können denselben Vertrag später beliefern;
keine drei parallelen Live-Adapter und keine fingierten Herstellerzugänge.

Vor Pilotstart werden mit dem Datenverantwortlichen wenige echte, freigegebene
Produkte/Fachverfahren und deren relevante Zeit-/Versions-/Nutzungsnachweise samt
bekannten Gegenbeispielen ausgewählt. Fehlende Angaben bleiben Teil der Abnahme.
Öffentliche Praxisfälle ersetzen diese vertraulichen Auszüge nicht und belegen
keinen API-Zugang. Produktivfreigabe erfordert konkrete Edition/Version, echte
freigegebene Daten und demonstrierte Zugriffsgrenzen.

Spätere, getrennt zu beauftragende Ausbaustufen sind Änderungsfolgen auf bestehende
Entscheidungen, weitergehende Zeitplanung und die Übergabe bestätigter Neubedarfe.
Lesbare Quellen müssen nicht schreibbare Ziele sein. Für Schreiben gelten der
vorhandene explizite Freigabe-/Publikationsweg, nachgewiesene Konfliktkontrolle und
sichere Wiederholung; kein blindes bidirektionales Spiegeln.

## 9. Gegenproben und Abnahmegrenze

Die [drei ausgearbeiteten Gegenproben](2026-10-18-inventory-planning-sources-probes.md)
zeigen normalisierte Erwartungen und Unterschiede an einem kleinen synthetischen
Fall. Sie sind eine nachvollzogene Konzeptprüfung, keine ausgeführten Adaptertests.
Die dortigen 24 Fälle sind die fachliche Grundlage späterer gewöhnlicher JUnit-Tests
in den vorhandenen Maven-Suiten; kein zusätzliches Testsystem und keine QA-Rohdatenserie.
Gleiche belegte Semantik muss bei gleichem Bedarf gleiche Kriterienbefunde erzeugen;
unterschiedliche Aussagen, fehlende Felder und andere Bereitstellungen dürfen nicht
zur künstlichen Ergebnisgleichheit eingeebnet werden.
