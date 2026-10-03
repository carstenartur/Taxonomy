# Datenschutz

**Umfang: Betreiberreferenz, am 3. Oktober 2026 gegen Quellstand `3ec8a986` geprüft.**
Diese Seite beschreibt Datenflüsse und Grenzen für die Bewertung einer Installation.
Sie ist weder die Datenschutzerklärung eines konkreten Betreibers noch ein
Rechtsgutachten oder eine Zusage, dass die Installation von Taxonomy bereits
DSGVO-Konformität herstellt. Der Betreiber muss seine tatsächlichen Daten, Zwecke,
Einstellungen, Empfänger und Aufbewahrungsverfahren bewerten.

## Inhaltsverzeichnis

1. [Zweck der Datenverarbeitung](#zweck-der-datenverarbeitung)
2. [Kategorien personenbezogener Daten](#kategorien-personenbezogener-daten)
3. [Datenspeicherorte](#datenspeicherorte)
4. [Rechtsgrundlage](#rechtsgrundlage)
5. [Datenaufbewahrung und Löschung](#datenaufbewahrung-und-löschung)
6. [Datenübermittlung an Dritte](#datenübermittlung-an-dritte)
7. [Technische und organisatorische Maßnahmen](#technische-und-organisatorische-maßnahmen)
8. [Betroffenenrechte](#betroffenenrechte)
9. [Datenschutz-Folgenabschätzung](#datenschutz-folgenabschätzung)
10. [BfDI-Leitlinien für KI in der Bundesverwaltung](#bfdi-leitlinien-für-ki-in-der-bundesverwaltung)

## Zweck der Datenverarbeitung

Taxonomy unterstützt Anforderungsklärung, Architekturanalyse, Prüfung, Versionierung
und Austausch. Hinzu kommen Konto- und Sicherheitsinformationen. Eine Nutzung für
Architekturarbeit garantiert keine anonymen Eingaben: Anforderungen, importierte
Quelldokumente, Freitextbegründungen, Entscheidungen und erzeugte Ergebnisse können
Angaben über Beschäftigte, Kunden oder Bürger enthalten. Minimieren Sie solche
Angaben vor Import oder Übermittlung; eine automatische Entfernung durch die
Anwendung darf nicht vorausgesetzt werden. Auch Modellergebnisse müssen geprüft werden.

## Kategorien personenbezogener Daten

### Benutzerkontodaten

Der lokale Benutzermodus speichert Kontokennungen, Passwort-Hashes, Rollen und
Kontostatus sowie optionale Profilangaben. Keycloak/OIDC hat eine andere Grenze
zum Identitätsanbieter; dokumentieren Sie diesen Anbieter und seine Konfiguration
separat. Kontometadaten aus einem Verwaltungsendpunkt bilden nicht sämtliche
Informationen ab, die andernorts in der Anwendung über eine Person gespeichert sind.

### Audit-Protokolldaten

Die Brute-Force-Erkennung für Anmeldung/WebDAV kann Netzwerkadressen verarbeiten; das eingehende LLM-Kontingent verwendet keine IP-Adressen. Es zählt zugelassene Aufrufe je stabiler authentifizierter Identität und Anwendungsinstanz. Peer-bezogene Anmeldesperren, deren flüchtiger Zustand und Sicherheitsprotokolle haben getrennte Datenflüsse und Aufbewahrungseinstellungen. Aus dem LLM-Kontingent folgt daher nicht, dass Authentifizierungsprotokolle IP-frei sind. Siehe [Anmeldeschutz](LOGIN_BRUTE_FORCE_PROTECTION.md) und [Konfiguration](CONFIGURATION_REFERENCE.md).

Sicherheits- und Verwaltungsereignisse können Akteure und Netzwerkteilnehmer
identifizieren. Berücksichtigen Sie Anwendungs- und Reverse-Proxy-Protokolle,
Monitoring, Diagnosen und anbieterseitige Aufzeichnungen im Dateninventar.
Diagnostische Prompt-/Antwortinhalte können dieselben sensiblen Angaben wie die
Eingabe enthalten. Ein einzelner Logging-Schalter belegt keine inhaltsfreien
Protokolle; prüfen Sie die aktivierten Wege und die Protokollkonfiguration.

### Arbeitsbereichs- und Analysedaten

| Aufzeichnung | Mögliche identifizierende Inhalte | Persistierungsgrenze |
|---|---|---|
| Projektanforderungsversionen und Quellenherkunft | Anforderungstext, Originalfragmente, Ersteller, Begründungen | Anwendungsdatenbank; gespeicherte Versionen überdauern die Browsersitzung |
| Analyseaufträge, Ergebnisse und Snapshots | Eingabeverweise, erzeugte Erklärungen, Akteur, Modell- und Prüfnachweise | Persistierte Aufträge/Snapshots und zugehörige mandantengebundene Zuordnungen |
| Neuformulierungsangebote und Entscheidungen | Eingefrorenes Original, Architekturnachweise, Fragen, Antworten und Revisionen | Separates dauerhaftes Vorschlagsjournal; Übernahme löscht ältere Nachweise nicht |
| Akzeptierte Editoroperationen | Akteur, Begründung, Gegenoperation und Arbeitsrevision | Dauerhaftes semantisches Journal, getrennt von Git-Checkpoints |
| Git-Checkpoints und exportierte Berichte | Modelltext, Nachweise und Autorenmetadaten | Datenbankgestütztes Git, konfigurierte Remotes und heruntergeladene Kopien |
| Gespeicherte Ad-hoc-Entwürfe und Fortsetzungsdaten | Arbeitstext und erhaltene Antworten | Gespeicherte Workspace-/Laufdaten, nicht nur Browserspeicher |

Numerische Bewertungen allein beschreiben nicht den vollständigen Analyseinhalt.
DSL, Ergebnisse und Indizes sind nicht automatisch personenfrei, nur weil sie
Architektur beschreiben. [Architektur](ARCHITECTURE.md) und
[Portfolio-Handbuch](PROJECT_REQUIREMENT_PORTFOLIO.md) erläutern die getrennten Historien.

## Datenspeicherorte

| Komponente | Vom Betreiber zu erfassender Umfang |
|---|---|
| Relationale Datenbank | Konten, Quellenherkunft, Portfolioaufzeichnungen, Snapshots, Journale und datenbankgestützte JGit-Objekte/Refs; maßgeblich sind die konfigurierte Datenbank und ihre tatsächlichen persistenten Volumes |
| Suchindizes und Caches | Abgeleitete Kopien indexierter Inhalte; aktivierte Indizes, Orte sowie Neuaufbau-/Löschverfahren prüfen, statt ausschließlich Taxonomiebezeichnungen anzunehmen |
| Dateien und externe Ablagen | Import-/Exportartefakte, Vorlagen, temporäre Zwischenstände, heruntergeladene Berichte, Infrastruktursicherungen und konfigurierte externe Git-Repositories |
| Protokolle und verbundene Dienste | Anwendungs-/Proxy-Protokolle, Observability, Identitätsanbieter und konfigurierte Modellendpunkte |

`jgit-storage-hibernate` speichert die logischen Git-Repositories der Anwendung
über den relationalen Datenbankadapter. Ein allgemeines Verzeichnis `/app/data/git`
ist nicht ihr universeller Ablageort. Auch die Auswahl einer Unternehmensdatenbank
aktiviert nicht automatisch eine Verschlüsselung gespeicherter Daten. Prüfen Sie
Datenbank-, Volume-, Backup- und Schlüsselschutz unabhängig; HTTPS schützt die
Übertragung, nicht sämtliche gespeicherten Kopien. Siehe
[Datenbankeinrichtung](DATABASE_SETUP.md), [Repository-Topologie](REPOSITORY_TOPOLOGY.md)
und [Betrieb](OPERATIONS_GUIDE.md).

## Rechtsgrundlage

Die verantwortliche Organisation muss die anwendbare Rechtsgrundlage ihrer
konkreten Verarbeitung einschließlich einschlägiger nationaler Regeln bestimmen.
Eine Softwarefunktion, ein Sicherheitsstandard oder ein Anbietername ist nicht
selbst diese Rechtsgrundlage. Diese Seite ordnet nicht jedem Arbeitgeber und jeder
Behörde pauschal dieselbe Grundlage aus Artikel 6 DSGVO zu. Maßgeblich ist der
[amtliche DSGVO-Text](https://eur-lex.europa.eu/eli/reg/2016/679), nicht eine Produktcheckliste.

## Datenaufbewahrung und Löschung

### Aufbewahrungsfristen

Legen Sie zweckbezogene Aufbewahrungs- und Prüffristen fest und dokumentieren Sie
sie. Diese Produktreferenz begründet weder eine allgemeine 90-Tage-Kontofrist noch
eine einjährige Protokollfrist oder unbegrenzte Aufbewahrung. Insbesondere gilt:
**Das Ende einer Browsersitzung löscht keine gespeicherten Anforderungen, Analysen,
Vorschläge, Entscheidungen oder Git-Historien.** Berücksichtigen Sie bei der
[Speicherbegrenzung nach DSGVO](https://eur-lex.europa.eu/eli/reg/2016/679) das tatsächliche
Dateninventar einschließlich Empfängerkopien und Sicherungen.

### Löschverfahren

**Ein Konto zu deaktivieren ist keine Löschung.** `UserManagementService.disableUser`
speichert `enabled=false`; historische Urheberschaft, Anforderungstexte und andere
Aufzeichnungen bleiben dadurch erhalten. Ebenso darf nicht angenommen werden, dass
Kontenänderungen sämtliche bestehenden Sitzungen oder verbundenen Zugangsdaten
widerrufen. Prüfen Sie den zutreffenden Widerrufsablauf und den Identitätsanbieter.

Direkte Datenbanklöschungen und improvisierte Git-Historienumschreibungen sind kein
unterstütztes anwendungsweites Löschverfahren. Sie können Fremdschlüssel,
Commit-Verweise, Quellenherkunft, Synchronisation und Nachweise beschädigen, während
andere Kopien erhalten bleiben. Ein neuer Git-Commit oder eine neue
Anforderungsversion entfernt den bisherigen Inhalt nicht.

Identifizieren Sie vor einer Löschentscheidung betroffene Daten und Kopien,
Aufbewahrungspflichten, Berechtigungen und Abhängigkeiten. Definieren Sie ein
geprüftes, testbares Verfahren für Journale, Snapshots, Indizes, Exporte, Backups
und Empfänger; prüfen Sie anschließend Entfernung und verbleibende Datenintegrität.
Diese Seite belegt keinen verifizierten Ein-Klick-Ablauf zur speicherübergreifenden
Löschung personenbezogener Daten. Ein davon abhängiger Einsatz darf nicht ohne
Klärung dieser Lücke freigegeben werden.

## Datenübermittlung an Dritte

### Externe LLM-Anbieter

Eine generative Anfrage kann je nach Funktion Anforderungstext, Quellauszüge,
Katalogkontext, Beziehungen, vorhandene Entscheidungen und Promptanweisungen
enthalten. Prüfen Sie den tatsächlichen Anfragevertrag der Funktion und den
konfigurierten Endpunkt, nicht nur den Anbieternamen. Protokolle oder gespeicherte
Fortsetzungsnachweise können weitere Kopien enthalten.

Bewerten Sie Verarbeitungsbedingungen, Empfänger, Aufbewahrung, Trainingsnutzung,
Zugriffsorte und gegebenenfalls Übermittlungsanforderungen für den konkreten Dienst
und seine Konfiguration. Nationalität oder EU-Adresse eines Anbieters belegen
weder Datenresidenz noch den Ausschluss von Unterauftragnehmern oder Trainingsnutzung.
Siehe [KI-Transparenz](AI_TRANSPARENCY.md) und [KI-Anbieter](AI_PROVIDERS.md).

### Betrieb ohne Internetverbindung (Air-Gapped)

Lokale Embeddings vermeiden entfernte Inferenz für die unterstützte Suche und
Bewertung. Sie implementieren keine generative Relationsbewertung oder
Neuformulierung. Bei deaktivierten Downloads muss ein lokales Modell bereits
vorhanden sein, beispielsweise:

```bash
LLM_PROVIDER=LOCAL_ONNX
TAXONOMY_EMBEDDING_MODEL_DIR=/app/models/bge-small-en-v1.5
TAXONOMY_EMBEDDING_ALLOW_DOWNLOAD=false
```

Diese Einstellungen allein belegen keine netzisolierte Installation. Erfassen Sie
Identitätsdienste, Remotes, Telemetrie, Downloads und andere aktive Integrationen;
prüfen Sie ausgehende Netzwerkfreigaben und tatsächlichen Verkehr. Eine nicht
unterstützte oder unbewertete Analysephase darf nicht als abgeschlossen erscheinen,
nur um einen ausschließlich lokalen Betrieb behaupten zu können.

<a id="technische-und-organisatorische-maßnahmen"></a>
## Technische und organisatorische Maßnahmen (TOMs)

### Technische Maßnahmen

Der [Sicherheitsleitfaden](SECURITY.md) beschreibt Authentifizierung, Rollen- und
Bereichsprüfungen, Browser-CSRF-Schutz, Zugangsdaten und Deployment-Anforderungen.
Prüfen Sie HTTPS, Speicherschutz, Backup-Zugriff, Netzwerkbeschränkungen und
Wiederherstellung in der tatsächlichen Installation. Weder ein Dokument noch eine
grüne CI belegt die Aktivierung aller Maßnahmen beim Betreiber. Browser- oder
sitzungsauthentifizierte APIs sind nicht pauschal zustandslos.

### Organisatorische Maßnahmen

Definieren Sie zugelassene Eingaben und Modellendpunkte, minimale Rechte,
Administrationsverantwortung, Aufbewahrung, Störfallbehandlung und die Bearbeitung
von Betroffenenanfragen. Änderungen an Integrationen und Prompts können Datenflüsse
ändern. Halten Sie konfigurationsbezogene Nachweise der Entscheidungen fest, statt
eine allgemeine „erfüllt“-Markierung zu verwenden. Prüfen Sie Einstellungsschlüssel
anhand der [Konfigurationsreferenz](CONFIGURATION_REFERENCE.md), statt undokumentierte
Schalter für Passwörter oder die Trennung von Administratorzugängen vorauszusetzen.

## Betroffenenrechte

Die verantwortliche Organisation muss Auskunft, Berichtigung, Löschung oder
Einschränkung nach den anwendbaren Regeln bewerten. Ein Konto-JSON ist nicht
automatisch eine vollständige Auskunft; gesperrte Anmeldung schränkt nicht jede
Verarbeitung bereits gespeicherter Angaben ein. Berücksichtigen Sie gegebenenfalls
historische Aufzeichnungen und Empfängerkopien. Ein allgemeiner JSON-Export erfüllt
nicht automatisch sämtliche Voraussetzungen der Datenübertragbarkeit. Siehe
[DSGVO](https://eur-lex.europa.eu/eli/reg/2016/679), Artikel 12–22, und das geprüfte
Verfahren der konkreten Installation.

## Datenschutz-Folgenabschätzung

Prüfen Sie anhand von Artikel 35 DSGVO und einschlägigen Aufsichtshinweisen, ob die
geplante Verarbeitung voraussichtlich ein hohes Risiko für Rechte und Freiheiten
natürlicher Personen mit sich bringt. Die Anzahl beteiligter Organisationseinheiten
oder eine Modell-API allein ist keine allgemeine Ja-/Nein-Regel. Dokumentieren Sie
die Vorprüfung und beteiligen Sie die zuständigen Datenschutzfachleute; diese Seite
ersetzt keine einsatzbezogene Bewertung.

## BfDI-Leitlinien für KI in der Bundesverwaltung

Die Überschrift bleibt für vorhandene Verweise erhalten. Sie bedeutet **nicht**,
dass die BfDI Taxonomy zertifiziert hat oder die frühere Produkttabelle einen
amtlichen BfDI-Anforderungskatalog abbildete. Verwenden Sie diese Schlussfolgerung
nicht in Beschaffungs- oder Freigabeunterlagen.

Die aufsichtsbehördliche [DSK-Orientierungshilfe KI und Datenschutz vom Mai 2024](https://www.lfd.niedersachsen.de/startseite/infothek/presseinformationen/kunstliche-intelligenz-datenschutzkonform-einsetzen-orientierungshilfe-fur-unternehmen-und-behorden-231889.html)
behandelt Auswahl, Implementierung und Nutzung. Auch die
[Mitteilung zur RAG-Orientierungshilfe vom Oktober 2025](https://www.lfd.niedersachsen.de/startseite/infothek/aktuelles/datenschutzkonferenz-veroffentlicht-orientierungshilfe-zu-ki-systemen-mit-retrieval-augmented-generation-rag-245773.html)
betont die Bewertung des Einzelfalls. Dies sind datierte Referenzen, keine Zusage
der Erfüllung aller Kriterien und kein vollständiges Verzeichnis aktueller Leitlinien.

### Empfehlungen für Behördenbetreiber

Halten Sie vor einer Freigabe den tatsächlichen Zweck, zulässige Eingaben, Endpunkte,
Datenorte und Verantwortliche fest. Prüfen Sie einen erforderlichen lokalen Betrieb
und lassen Sie seine funktionalen Grenzen sichtbar. Definieren Sie Aufbewahrungs-
und Betroffenenverfahren für dauerhafte Nachweise, nicht nur für Konten. Bewerten
Sie Änderungen an Software, Konfiguration, Modelldienst oder zugelassenen Daten neu.

## Verwandte Dokumentation

[Sicherheit](SECURITY.md) · [KI-Transparenz](AI_TRANSPARENCY.md) ·
[Architektur](ARCHITECTURE.md) · [Projektportfolio](PROJECT_REQUIREMENT_PORTFOLIO.md) ·
[Betrieb](OPERATIONS_GUIDE.md) · [Konfiguration](CONFIGURATION_REFERENCE.md)

Technische Prüfstellen im Basisquellstand:
[Anforderungsversionen](https://github.com/carstenartur/Taxonomy/blob/3ec8a98610e6cb6d7fc4b4addee4c4f1bb87fb04/taxonomy-portfolio/src/main/java/com/taxonomy/portfolio/model/ProjectRequirementVersion.java),
[Analysesnapshots](https://github.com/carstenartur/Taxonomy/blob/3ec8a98610e6cb6d7fc4b4addee4c4f1bb87fb04/taxonomy-portfolio/src/main/java/com/taxonomy/portfolio/model/RequirementAnalysisSnapshot.java),
[Kontodeaktivierung](https://github.com/carstenartur/Taxonomy/blob/3ec8a98610e6cb6d7fc4b4addee4c4f1bb87fb04/taxonomy-app/src/main/java/com/taxonomy/security/service/UserManagementService.java).
