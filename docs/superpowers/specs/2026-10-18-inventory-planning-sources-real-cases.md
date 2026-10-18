# Reale Praxisfälle für Analyse-, Bestands- und Planungsabgleich

Stand: 18. Oktober 2026. Ergänzung zum
[gemeinsamen Entwurf](2026-10-18-inventory-planning-sources-design.md) und den
[synthetischen Gegenproben](2026-10-18-inventory-planning-sources-probes.md).

Die folgenden Produkt- und Einführungsaussagen sind öffentlich belegt. Die daraus
abgeleiteten Taxonomy-Prüffragen sind unser Anwendungsvorschlag, kein durchgeführter
Abgleich und keine behauptete interne Planung der genannten Organisationen.
Kein Firmen-/Behördenkonto wurde kontaktiert. Herstellerberichte sind als solche
gekennzeichnet. Eine Veröffentlichung ersetzt weder einen freigegebenen Bestandsauszug
noch dessen Nutzungszeiträume. Es werden keine internen IDs oder Termine erfunden.

## 1. Landesverwaltung: tatsächlich abgelöste Mailprodukte

**Öffentlich belegt:** Schleswig-Holstein meldete am 6. Oktober 2025 den Abschluss
des Umstiegs von Microsoft Exchange/Outlook auf Open-Xchange/Thunderbird am
2. Oktober 2025. Die Meldung nennt mehr als 40.000 Postfächer und beschreibt einen
sechsmonatigen Umstellungsprozess. Das ist ein datiertes Rollout-Ereignis, keine
vollständige Liste sämtlicher Anwendungen, Versionen und technischen Abhängigkeiten.
[Quelle S1: Landesregierung, 06.10.2025](https://www.schleswig-holstein.de/DE/landesregierung/ministerien-behoerden/I/Presse/PI/2025/cds/251006_cds_ox)

**Prüffrage für einen Nutzer:** „Meine gespeicherte Analyse benötigt Mail- und
Kalenderfunktionen. Welche realen Produkte sind für meine Behörde im gewählten
Zeitraum vorgesehen oder nutzbar? Muss ein auf Outlook bezogener Vorschlag überprüft
werden?“ Relevante UA-/Service-Knoten werden aus dem tatsächlichen Katalog gewählt;
die Produktnamen selbst sind keine Taxonomieknoten.

**Benötigte Pilotdaten:** freigegebener lokaler Produkt-/Anwendungsbestand mit
Organisation, Version, Ablöse- und Rolloutangaben sowie den für das Vorhaben
benötigten Schnittstellen und Nutzungsvoraussetzungen. Diese Informationen können
aus einem Portfolio, Servicekatalog und dokumentierten Migrationsentscheidungen
stammen. Kein bestimmtes EAM-Produkt wird für Schleswig-Holstein behauptet.

**Erwarteter Nutzen:** Der vorhandene alternative Produktbezug erscheint schon an
der Teilanalyse. Ein Outlook-spezifischer Bedarf wird als Anpassungs-/Klärungsfrage
sichtbar, nicht automatisch gelöscht oder umgeschrieben. Die öffentliche Meldung
allein beweist weder alle Funktionsdetails noch Nutzbarkeit bis 2030.

## 2. Unternehmen: Plattform und Anwendung gemeinsam, aber getrennt betrachten

**Öffentlich belegt:** Google beschrieb am 20. September 2019 den Einsatz von
Google Kubernetes Engine (GKE) für das Online-System von MediaMarktSaturn.
[Quelle M1: beteiligter Anbieter, 20.09.2019](https://cloud.google.com/blog/topics/inside-google-cloud/dach-businesses-embrace-google-cloud-for-digital-transformation)

Ein weiterer Google-Bericht vom 9. Juni 2021 beschreibt die Migration einer
SAP-HANA-Datenbank von MediaMarktSaturn auf Google Cloud.
[Quelle M2: beteiligter Anbieter, 09.06.2021](https://cloud.google.com/blog/topics/retail/3-german-retailers-modernizing-with-sap-on-google-cloud)

Ein öffentlich zugänglicher SAP-LeanIX-Kundenbericht beschreibt ein gemeinsames
Anwendungsinventar und das Nachverfolgen einer Cloudmigration bei MediaMarktSaturn.
Er liefert keine vollständige interne Produktplanung und keinen belastbaren
Gültigkeitszeitraum für einzelne Bestandsobjekte.
[Quelle M3: SAP-LeanIX-Kundenbericht, ohne sichtbares Publikationsdatum](https://www.leanix.net/en/customers/success-stories/mediamarktsaturn-technology)

**Prüffrage für einen Nutzer:** „Welche Containerplattform und Datenbank passen zu
den Anforderungen meiner IP-Analyse und den produktbezogenen Teilen der UA-Analyse
für den vorgesehenen Betriebszeitraum? Gibt es bereits einen geplanten oder
betriebenen Standard, statt eine neue Plattform vorauszusetzen?“

**Benötigte Pilotdaten:** freigegebene einschlägige LeanIX-Objekte oder gleichwertige
Portfolioauszüge, konkrete Produktversionen, Organisations-/Umgebungsbezüge,
Gültigkeits- und Nachfolgeplanungen sowie belegte Zuordnungen der Anwendung zur
Plattform. Ein nur ähnlich benannter Eintrag wird nicht gleichgesetzt.

**Erwarteter Nutzen:** GKE als Plattform-/Servicebezug, SAP HANA als Technologie und
die konkrete Geschäftsanwendung werden nicht zu einem Objekt vermischt. Passende
Bestandskandidaten und fehlende Versions-/Zeitraumnachweise erscheinen nebeneinander.
Aus den historischen Quellen von 2019/2021 folgt keine bestätigte Nutzbarkeit
2026 oder 2030. Ebenso ist nicht belegt, dass genau diese Objektinformationen in
einem heute zugänglichen LeanIX-API-Datensatz vorliegen.

## 3. Landes-IT-Dienstleister: vom Serviceportal zur nutzbaren Produktbereitstellung

**Öffentlich belegt:** Der USU-Kundenbericht zur BITBW nennt USU IT Service Management
und IT Asset Management, eine CMDB sowie ein landesweites Serviceportal. Er beschreibt
die Bestellung, Verfolgung und Abrechnung von Services über dieses Portal.
[Quelle U1: USU-Kundenbericht, ohne sichtbares Publikationsdatum](https://www.usu.com/de/kunden/it-service-management/bitbw)

**Prüffrage für einen Nutzer:** „Meine Analyse benötigt eine Plattform oder
Anwendung. Kann meine Behörde dafür eine vorhandene Leistung des zentralen
Dienstleisters nutzen, mit welchem Produkt und in welchem Zeitraum?“

**Benötigte Pilotdaten:** ein ausdrücklich freigegebener Service-/Assetauszug mit
Leistungsbeschreibung, zugehörigem realem Produkt, Version, Betreiber,
berechtigter Nutzerorganisation, Bereitstellungszustand und Zeitangaben. Der
Herstellerbericht selbst nennt keine vollständige Produktliste oder API-Konfiguration.
Die tatsächliche USU-Schnittstelle wäre separat zu prüfen, nicht jetzt mitzubauen.

**Erwarteter Nutzen:** Der Benutzer erhält eine konkrete Wiederverwendungs- oder
Bestellanfrage statt eines abstrakten Produktnamens. „Im Katalog angeboten“ ist
nicht „für dieses Vorhaben bestellt, eingerichtet und während des gesamten
Zeitraums verfügbar“. Fehlende Angebots- und Versionsfristen bleiben ungeklärt.

## 4. Weiterer Bereich: beschaffte, aber noch nicht als betrieben belegte Telefonie

**Öffentlich belegt:** Die Staatskanzlei Schleswig-Holstein meldete am 11. August
2026 den abgeschlossenen Zuschlag für OSKAR an Viakom. Land, Auftragnehmer und
Dataport als Betreiber sollen die bisherige Telefonie in die neue Plattform
überführen. Die Meldung belegt den Zuschlag und die beabsichtigte Ablösung, nicht
einen bereits abgeschlossenen Rollout in jeder Behörde.
[Quelle S2: Landesregierung, 11.08.2026](https://www.schleswig-holstein.de/DE/landesregierung/ministerien-behoerden/I/Presse/PI/2026/cds/260811_viakom_cds)

**Prüffrage für einen Nutzer:** „Ist für die Kommunikationsfunktionen meiner
Analyse bereits ein reales Folgeprodukt vorgesehen, oder fehlt tatsächlich eine
Planung?“ Ein fachlich passender CO-/Service-Bereich ist ebenso zulässig wie IP/UA;
die genauen Knoten müssen aus dem verwendeten Katalog bestimmt werden.

**Benötigte Pilotdaten:** genehmigter Ablöse-/Rolloutplan für die Zielbehörde,
benötigte Funktionen und Integrationen, Produkt-/Angebotsbezug sowie Übergangsfristen.
Ein öffentlicher Zuschlag allein liefert diese operativen Einzelheiten nicht.

**Erwarteter Nutzen:** Das neue Vorhaben erscheint als reale Planung mit offenen
Bereitstellungsterminen. Eine erneute Neubeschaffung wird nicht allein wegen eines
fehlenden Betriebsobjekts vorgeschlagen. Ein zugesagter Projekttermin oder eine
produktive Installation werden nicht aus dem Publikationsdatum konstruiert.

## 5. Aus den Beispielen zum ersten nutzbaren Pilotablauf

| Schritt | Konkretes Arbeitsergebnis |
|---|---|
| Gespeicherte Analyse oder Architektur auswählen | Exakter Quell-/Katalogstand und kleine fachliche Auswahl; ein Root oder einzelne Knoten reichen. |
| Zeitfrage und Nutzerbereich festlegen | Stichtag, Startfenster oder durchgehender Nutzungszeitraum, Zielorganisation und bei Bedarf Umgebung. |
| Mit Datenverantwortlichem einen Pilotbestand freigeben | Wenige reale Produkte plus mindestens ein veralteter, geplanter und fachlich unpassender Fall; keine erfundenen Lückenwerte. |
| Quelle über begrenzten Anschluss beziehen | Zunächst ein nutzbarer Adapter; weitere Quellen oder zulässige Dokumentnachweise nur bei konkretem Datenbedarf. |
| Zuordnungen und Nachweise prüfen | Fachliche Viele-zu-viele-Verbindungen zu echten Katalogknoten; Versions-, Zeit- und Nutzungsfragen getrennt. |
| Abgleich anzeigen und dokumentieren | Je Bedarf Kandidaten, Quellen/Zeiträume, Befunde, offene Fragen und zuständige nächste Schritte; unveränderte Originalanalyse. |

Die erste Abnahme verlangt keinen vollständigen Firmen-/Behördenbestand und keine
fertige Gesamtarchitektur. Sie verlangt, dass ein Nutzer einen realen Bedarf im
gewählten Teilbereich mit realen Produktnachweisen vergleichen und konkrete
Wiederverwendungs-, Anpassungs- oder Informationsanfragen ableiten kann.
Die genaue Eignung und bestehende Schnittstellen müssen aus freigegebenen Daten
belegt werden; die Beispiele sind Motivation, kein bereits bestandenes Abnahmeprotokoll.

## 6. Quellen- und Prüfumfang

Quellenrecherche am 18. Oktober 2026. S1, M1, M2, M3 und U1 wurden über die
Hersteller-/Organisationsseiten recherchiert; die S2-Aussagen wurden aus dem
indexierten offiziellen Suchergebnis entnommen, da der direkte Seitenabruf einen
Cachefehler lieferte. Für die Übernahme in echte Pilotnachweise ist die jeweilige
Quelle nochmals vollständig berechtigt abzurufen beziehungsweise vom Eigentümer
bereitzustellen. Keine Marketing-Erfolgszahlen werden als Taxonomy-Leistungsziel benutzt.
