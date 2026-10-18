# Gegenproben: drei Quellenmodelle, ein fachlicher Vertrag

Stand: 18. Oktober 2026. Begleitende Konzeptprüfung zum
[Bestands- und Planungsabgleich](2026-10-18-inventory-planning-sources-design.md).
Alle Kennungen, Namen, Zustände und Inhalte der Beispiele sind synthetisch. Sie sind
keine offiziellen Taxonomie-IDs, keine Exporte realer Installationen und kein
Hersteller-Kompatibilitätsnachweis. Es wird hier kein ausführbarer Adapter geliefert.

## 1. Unabhängiger fachlicher Erwartungsfall

Vor irgendeiner Quellabbildung wird festgelegt: Der Bedarf `example:need` verlangt
Arbeitszeiterfassung mit Korrekturfreigabe und Anmeldung am zentralen Identitätsdienst,
für `example:org-north` in Produktion, Einsatzjahr 2030. Ein genauer Termin ist offen.
Alle Varianten betrachten denselben Bedarf und dieselbe synthetische Quellenalter-Policy.

| Beispielobjekt | Vorgegebene Nachweise | Gemeinsame Art | Erwarteter Befund |
|---|---|---|---|
| `example:time` | Zeiterfassung belegt; Korrekturfreigabe nicht angegeben | APPLICATION | Funktion teilweise nachgewiesen; Freigabefunktion UNKNOWN. Keine bestätigte Wiederverwendung. |
| `example:old-identity` | Passende Anmeldefunktion; erklärtes Nutzungsende 2029-12-31 | SERVICE, Angebot | Zeit für 2030 CONTRADICTED trotz passender Funktion. |
| `example:new-identity` | Nachfolger ist für 2030 geplant; Bereitstellung und Nutzungsfreigabe fehlen | SERVICE, Angebot | PLANNED bleibt geplant; Nutzbarkeit UNKNOWN. |
| `example:identity-product` | Technologiedefinition mit passender Funktion, keine Bereitstellung | TECHNOLOGY_PRODUCT | Produktkandidat, kein Nachweis eines nutzbaren Anmeldedienstes. |
| `example:time-test` | Testbereitstellung der Anwendung | DEPLOYMENT | Umgebung für Produktionsbedarf CONTRADICTED. |

A und B bilden dieselben fünf Objektbedeutungen mit unterschiedlichen Kennungen ab.
C ergänzt bewusst weitere betriebliche Nachweise statt künstlicher Vollständigkeit.
Die Anforderung an eine Anmeldeverbindung bleibt zunächst UNKNOWN. Eine vorhandene
Abhängigkeitsbeziehung ist nicht bereits deren Implementierungsnachweis.
Kein erwartetes Ergebnis setzt `SELECTED`, `IMPLEMENTED`, `CREATE` oder `PROCURE`.

## 2. Gegenprobe A: Alfabet-artiger Bericht

Alfabet dokumentiert vorkonfigurierte Objekt- und Beziehungsberichte sowie den
DataSet-Abruf. Die Exportdokumentation beschreibt `Limit` und `Offset` für große
Berichte. Diese Herstellerinformationen motivieren das Tabellenmodell; sie legen
nicht die Bedeutung kundeneigener Spalten fest. [A1] [A2]

Synthetische Abbildung durch Anschlussprofil `example:reports@1`:

| Berichtsquelle | Beispielwerte | Normalisierte Aussage |
|---|---|---|
| `example:apps`, Zeile `row-time` | `ClassName=Application`, `RefStr=app-17`, `Name=Zeitdienst`, `RecordsTime=true`; keine Spalte mit Freigabenachweis | Identität `(example:alfabet, Application, app-17)`, APPLICATION; RecordsTime PRESENT; Freigabenachweis ABSENT. |
| `example:services`, `row-old` | `RefStr=svc-4`, `UsageEnd=2029-12-31`, Angebot durch Profil belegt | SERVICE/Angebot; deklarierte Nutzbarkeit endet vor 2030. |
| `example:services`, `row-new` | `RefStr=svc-5`, `Status=geplant`, `StartYear=2030` | SERVICE/Angebot; PLANNED mit Jahrespräzision. Kein erfundener Start am 1. Januar. |
| `example:products`, `row-product` | `ClassName=Component`, `RefStr=prod-2`; Profil belegt Produktdefinition | TECHNOLOGY_PRODUCT; passende Funktion ist kein Betriebsnachweis. |
| `example:deployments`, `row-test` | Lokale Klasse `InstalledService`, `RefStr=dep-9`, `Environment=Test` | DEPLOYMENT; Umsetzung von `example:time` nur in Test. |
| `example:dependencies`, `row-r1` | Basisreferenz `app-17`, `From=app-17`, `To=svc-4`, Typ `Abhängigkeit` | DEPENDS_ON mit eigenem Zeilennachweis, nicht mit `app-17` als Beziehungs-ID. |
| `example:dependencies`, `row-r2` | Gleiche Basisreferenz, `To=svc-5`, Typ `geplante Abhängigkeit` | Weitere, geplante Beziehung erhalten; keine doppelte Anwendung erzeugen. |

Der vollständige Durchgang der ausgewählten Seiten ergibt höchstens
EXHAUSTED_SELECTION und NON_ATOMIC_OR_UNKNOWN. Änderung während Offset-Paging kann
bei gleicher Seitenzahl trotzdem auftreten; weder Count noch Hash erzeugt Atomarität.
Fehlen Seiten oder ein erforderlicher Beziehungsbericht, wird kein neuer erfolgreicher
Beobachtungsstand veröffentlicht. Der letzte autorisierte Stand bleibt als alt erkennbar.

**Ergebnis der Konzeptprüfung:** Kern und optionale Planung reichen für die Tabelle.
Eigene Beziehungsnachweise und präzise Fehlwertzustände sind zwingend. Ein
Alfabet-Reportobjekt darf nicht direkt als kanonischer Architekturbaustein gelten.

## 3. Gegenprobe B: LeanIX-artiges Portfolio

SAP dokumentiert Fact Sheets, deren Beziehungen, ein konfigurierbares Metamodell und
IT Components für Technologien oder Services. Die GraphQL-Schnittstelle dokumentiert
vorwärtsgerichtetes Paging mit `endCursor` und `hasNextPage`. Daher darf der allgemeine
Vertrag weder Zeilen/Offsets noch eine pauschale IT-Component-Produktzuordnung verlangen.
Diese Aussagen gelten für die dokumentierten Funktionen, nicht für jedes lokale Profil. [L1] [L2]

Synthetische Abbildung durch Anschlussprofil `example:portfolio@1`:

| Quellobjekt | Profilregel | Normalisierte Aussage |
|---|---|---|
| Fact Sheet `fs-app`, Typ Application | APPLICATION | Entspricht fachlich `example:time`; `description=null` bleibt NULL, nicht leerer Text. |
| `fs-old`, Typ ITComponent, belegter Untertyp Services | SERVICE/Angebot nur mit zusätzlichem Angebotsnachweis | Entspricht bei vorhandenem Nachweis `example:old-identity`; Lebenszyklusargument bleibt gleich. |
| `fs-product`, Typ ITComponent, Untertyp Software | TECHNOLOGY_PRODUCT | Entspricht `example:identity-product`, nicht dem Anmeldedienst. |
| `fs-new`, belegtes Serviceangebot, Phase geplant | SERVICE/Angebot, PLANNED | Entspricht `example:new-identity`; gleicher ungeklärter Verfügbarkeitsbefund. |
| `fs-test`, Typ Application, lokal belegter Untertyp Deployment | DEPLOYMENT mit Umgebung Test | Entspricht `example:time-test`, nicht der logischen Anwendung. |
| Beziehung `rel-17`, App zu Service | Ursprünglichen Typ/Richtung bewahren | Eigenständige Beziehungs-ID statt Berichtszeilenschlüssel. |

Ein allgemeiner Typ Application darf bei einem lokalen Deployment-Untertyp nicht
blind als logische Anwendung normalisiert werden. Ohne bestätigte Untertypregel
bleibt die Einordnung OTHER. Fremde Fachfähigkeiten/Tech Categories sind Kontext,
keine automatisch bestätigten Zuordnungen zur offiziellen Taxonomy-Hierarchie.

**Ergebnis der Konzeptprüfung:** Die Kriterienbefunde entsprechen bei identischen
Nachweisen Fall A; Identitäten bleiben selbstverständlich quellspezifisch. Der
Adapter kümmert sich um Cursor, der fachliche Abgleich nicht. Nicht abgefragte
Beziehungsseiten verhindern deren Vollständigkeitsbehauptung, auch wenn die
Fact-Sheet-Liste erschöpft ist. GraphQL-Fehler mit Teildaten gelten nicht als Vollerfolg.

## 4. Gegenprobe C: CMDB und Servicekatalog

ServiceNow unterscheidet Business Applications, angebotene Leistungen und Service
Instances; die Serviceinstanz beschreibt einen bereitgestellten Anwendungsverbund.
Die Beziehungsdokumentation unterscheidet CSDM-v5-Uses und ältere Consumes-Beziehungen
und nennt eine produktspezifische Ausnahme. Das Beispiel übernimmt daher keine
ungeprüfte globale 1:1-Abbildung oder universelle Richtungsregel. [C1] [C2]

Synthetische Abbildung durch Anschlussprofil `example:operations@1`:

| Quellobjekt | Nachweis | Normalisierte Aussage |
|---|---|---|
| `ci-app`, Business Application | Fachlich derselbe Zeitdienst | APPLICATION; nicht betriebsbereit allein wegen Existenz in der CMDB. |
| `ci-test`, Service Instance | Umgebung Test; Verbindung zur Anwendung | DEPLOYMENT; kann den Produktionsbedarf nicht erfüllen. |
| `ci-prod`, Service Instance | Produktion, derzeit aktiv | DEPLOYMENT; heutiger Betrieb belegt noch keine Nutzbarkeit für 2030. |
| `offer-id`, Service Offering | Nutzung nur für `example:org-south` freigegeben | SERVICE/Angebot; Nutzungsumfang für Nord CONTRADICTED, wenn Ausschließlichkeit belegt ist. |
| `product-id`, Software Product Model | Passendes Produkt | TECHNOLOGY_PRODUCT; von Anwendung, Angebot und Instanz getrennt. |

Die Quelltabellennamen sind Modellhinweise, kein ausführbarer API-Vertrag. Nur das
installationsbezogene Profil darf Typ, Referenzauflösung und Relationrichtung erklären.
„Betreiber ist Nord“ würde nicht „Nord darf das Angebot nutzen“ bedeuten. Eine
bloße Nennung von Süd ohne Ausschließlichkeitsnachweis ergäbe dagegen UNKNOWN,
nicht CONTRADICTED. Expliziter Ausschluss und unvollständige Nutzerliste sind verschieden.

**Ergebnis der Konzeptprüfung:** Das vierteilige Kernmodell verhindert die falsche
Gleichsetzung von Anwendung, Produkt, Angebot und Betrieb. Gegenüber A/B kommen
belegte Unterschiede hinzu; der Abgleich muss sie zeigen statt Ergebnisgleichheit
zu erzwingen. Ein später bestätigter Querverweis darf `ci-app` und `fs-app` verbinden,
aber nicht `ci-prod` mit beiden als identisch verschmelzen.

## 5. Gegenproben, die den Entwurf konkret verändert haben

| Beobachtung | Im gemeinsamen Entwurf festgehaltene Konsequenz |
|---|---|
| Ein Bericht kann mehrere Beziehungen unter derselben Basisobjekt-ID liefern. | Eigene Nachweisidentität, keine erfundene dauerhafte Beziehungs-ID. |
| ITComponent kann Technologie oder Service meinen; Application kann lokal Deployment-Untertypen besitzen. | Quelluntertyp und administrative Profilregel erforderlich. |
| Planung und Betrieb enthalten unterschiedliche Zeitangaben. | Abruf, Gültigkeit, Präzision und Aussageart getrennt. |
| Ein Profil unterstützt ein Feld, ein Objekt liefert es aber nicht. | Fähigkeit, ABSENT, NULL und UNAVAILABLE getrennt. |
| Ein freigegebenes Angebot ist nicht für jede Organisation freigegeben. | Leserecht, Betreiberrolle und Nutzungsumfang getrennt; Negation nur mit Beleg. |
| CSDM-Beziehungen ändern sich je Version/Produktkontext. | Versionierte Zuordnung, Erhalt des originalen Typs und der Richtung. |

## 6. Fachliche Abnahmefälle für die spätere Implementierung

Noch keine ausgeführten JUnit-Tests. Diese Erwartungen sind vor der Implementierung
festgelegt und müssen später durch echte Produktionspfade geprüft werden.

| Fall | Eingabe/Änderung | Erwartung |
|---|---|---|
| B01 | Gleiche Fachnachweise in A und B | Gleiche Kriterienbefunde; unterschiedliche externe IDs und Herkunft bleiben erhalten. |
| B02 | Umbenennen eines Bestandsobjekts | Identität unverändert, keine doppelte Lösung. |
| B03 | Gleichnamiges anderes Objekt/andere Instanz | Keine automatische Gleichsetzung. |
| B04 | Produkt und Testinstanz vorhanden | Keine belegte Produktionsverfügbarkeit ableiten. |
| B05 | Nutzungsende vor 2030; Ziel nur als Jahr bekannt | Zeit CONTRADICTED; kein stilles genaues Datum. |
| B06 | Nutzung beginnt irgendwann 2030 oder Ende fehlt | Zeit UNKNOWN; geplanter Beginn bleibt PLANNED; keine unendliche Gültigkeit. |
| B07 | NULL, fehlend, leer, false, nicht unterstützt | Alle Zustände semantisch unterscheidbar. |
| B08 | Unbekannter Typ/Status/Relation | Originalnachweis und Mappingverlust; keine positive Voreinstellung. |
| B09 | Mehrere Relationszeilen, parallele Kanten, unbekanntes Ziel | Erhalt und sichtbare Ungeklärtheit; keine falsche Identität/Technikanbindung. |
| B10 | Timeout, HTTP 403, GraphQL-Fehler, Limitende | Kein neuer erfolgreicher leerer/teilweiser Beobachtungsstand. |
| B11 | Paging erschöpft, Quelle nicht atomar | Keine globale Vollständigkeit, keine Löschung/Nichtvorhandenseinsbehauptung. |
| B12 | Zwei Quellen widersprechen bei gleicher Aussage und Zeit | CONFLICT, beide zulässigen Nachweise sichtbar; kein Last-write-wins. |
| B13 | Zugriff entzogen oder anderer Mandant | Kein alter Nachweis, Cacheeintrag, Trefferzähler oder Export als Datenleck. |
| B14 | Preview bestätigt, dann identisch wiederholt | Genau eine begründete lokale Entscheidung; kein externer Schreibaufruf. |
| B15 | Architektur, Profil, lokal bekannter Quellenstand oder Zugriffsstand nach Preview geändert | Erneute Prüfung, keine stille Übernahme alter Autorität. |
| B16 | Auswahl mit Normbezug oder reinem Suchtreffer | Weder Konformität noch Eignung/Umsetzung automatisch bestätigt. |

Produktabnahme bleibt zusätzlich erforderlich: mindestens ein echtes, erlaubtes
mehrseitiges Alfabet-Objekt-/Beziehungsbeispiel, bekannte Nichttreffer, ein Rechtefehler
und ein fehlgeschlagener Refresh. Herstellerzugang und sensible Daten gehören nicht
in öffentliche Fixtures. Die übrigen Gegenmodelle benötigen zunächst keinen Live-Adapter.

## 7. Quellen und Aussagegrenzen

Herstellerquellen, geprüft am 18. Oktober 2026. Sie belegen Modellunterschiede und
Schnittstellenmerkmale, keine Marktanteile oder konkrete installierte Konfiguration.

- [A1: Alfabet 11.9, vorkonfigurierte Objekt- und Beziehungsberichte](https://documentation.alfabet.com/11-9/en/AFLREST_AFL_RESTService_ObjectReport.html)
- [A2: Alfabet 11.7, Export von Objektdaten und Limit/Offset](https://documentation.alfabet.com/11-7/en/AFLREST_AFL_RESTService_DataExport.html)
- [L1: SAP LeanIX, Metamodell und Untertypen](https://help.sap.com/docs/leanix/ea/meta-model)
- [L2: SAP LeanIX, Pagination in GraphQL](https://help.sap.com/docs/leanix/ea/pagination-in-graphql)
- [C1: ServiceNow, CSDM-Begriffe](https://www.servicenow.com/docs/r/servicenow-platform/common-service-data-model-csdm/csdm-term-definitions.html)
- [C2: ServiceNow, versionsabhängige CSDM-Beziehungen](https://www.servicenow.com/docs/r/servicenow-platform/common-service-data-model-csdm/ci-relationships.html)
