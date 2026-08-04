# Projekt-, Anforderungs-, Lösungs- und Produktportfolio

## Zweck und primäre Benutzeroberfläche

Das Portfolio erweitert einzelne Anforderungsanalysen zu einem nachvollziehbaren Projektprozess:

```text
Projekt
→ getrennt identifizierte Anforderungen und unveränderliche Versionen
→ unabhängige Analyse-Snapshots
→ Taxonomie- und Architekturwirkung
→ geprüfte Maßnahmen und Entscheidungen
→ wiederverwendbare Lösungen
→ quellengebundene Produktkandidaten
→ Konflikte, Matrizen, Git-Historie und Berichte
```

Die primäre Benutzeroberfläche ist der Webarbeitsbereich:

```text
/projects
```

Für die auf dieser Seite beschriebenen Arbeitsabläufe sind weder REST noch JSON oder cURL erforderlich. Integrations- und Automatisierungsverträge stehen getrennt in der [Projektportfolio-API](PROJECT_PORTFOLIO_API.md).

## Navigation

Nach der Anmeldung **Projekte** öffnen oder `/projects` aufrufen.

Der Arbeitsbereich enthält:

- eine durchsuchbare Projektliste,
- Projektkennzahlen und offene Entscheidungen,
- Anforderungen, Taxonomie, Lösungen, Produkte, Konflikte und Snapshots,
- ein dauerhaftes Analyse-Job-Center,
- Verweise auf Dokumentimport, Matrizen, Versionierung und Berichte.

Die sichtbaren Aktionen hängen von der angemeldeten Rolle ab. `USER` darf lesen und freigegebene Analysen starten. `ARCHITECT` und `ADMIN` dürfen Projektentscheidungen verändern. Der Server erzwingt die Berechtigungen unabhängig davon, ob eine Schaltfläche im Browser deaktiviert ist.

## 1. Projekt anlegen und auswählen

1. `/projects` öffnen.
2. **Neues Projekt** wählen.
3. Eindeutigen Projektschlüssel, Titel und optional eine Beschreibung eingeben.
4. **Anlegen** wählen.

Das ausgewählte Projekt wird bei der Rückkehr in das Portfolio wiederhergestellt. Die Daten bleiben im aktiven Workspace isoliert.

## 2. Anforderungen aus PDF oder DOCX importieren

Folgende Seite öffnen:

```text
/projects/{projectId}/import
```

Der Assistent besitzt drei ausdrückliche Schritte.

### 2.1 Quelle hochladen

1. PDF- oder DOCX-Datei auswählen.
2. Quelltitel und Quellentyp angeben.
3. **Dokument auslesen** wählen.
4. Optional nach der deterministischen Extraktion **KI-Kandidaten ergänzen** wählen.

Quellartefakt und Quellversion werden vor der Kandidatenprüfung gespeichert. Seiten, Abschnitte und Originaltexte bleiben mit den entstehenden Anforderungsversionen verbunden.

### 2.2 Jeden Kandidaten prüfen

Jeder Kandidat erscheint als eigenständige bearbeitbare Karte. Zu prüfen sind:

- Anforderungsschlüssel,
- Titel und Text,
- Typ, Priorität und Kritikalität,
- Quellabschnitt und Seite,
- Extraktionsherkunft und gegebenenfalls Konfidenz.

Für jeden Kandidaten genau eine Entscheidung wählen:

- **Neue Anforderung** – neue stabile Anforderungsidentität anlegen;
- **Neue Version** – geprüften Text einer vorhandenen Anforderung zuordnen;
- **Zusammenführen** – ausdrücklich mit einem anderen beibehaltenen Kandidaten verbinden;
- **Verwerfen** – nicht persistieren.

Identische und ähnliche vorhandene Anforderungen werden hervorgehoben. Mehrere Kandidaten werden niemals stillschweigend zu einem gemeinsamen Analysetext verkettet.

Der Prüfentwurf kann im Browser gespeichert und wiederhergestellt werden. Beim Schließen eines ungespeicherten Entwurfs erscheint eine Warnung.

### 2.3 Atomaren Import bestätigen

Die Abschlussseite nennt vor dem Speichern neue Anforderungen, neue Versionen, Zusammenführungen und verworfene Kandidaten.

Festlegen, ob jede betroffene Anforderung anschließend getrennt analysiert werden soll. Der Server übernimmt alle geprüften Anforderungs- und Versionsentscheidungen in einer Transaktion. Ein später fehlerhafter Kandidat kann keinen halb importierten Projektstand hinterlassen.

## 3. Anforderungen manuell erfassen und analysieren

Im Reiter **Anforderungen**:

1. **Neue Anforderung** wählen.
2. Stabilen Schlüssel, Titel, Typ und Text eingeben.
3. Anforderung speichern.
4. **Analysieren** für eine Anforderung oder **Alle analysieren** für das Projekt wählen.

Die Analyse läuft asynchron. Der Server antwortet nach dem Persistieren des Jobs; die Seite bleibt bedienbar.

## 4. Analyse-Job-Center verwenden

Das Job-Center bleibt im Projekt sichtbar und übersteht ein Neuladen des Browsers.

Es zeigt:

- wartende, laufende, erfolgreiche, teilweise erfolgreiche, fehlgeschlagene und abgebrochene Einträge,
- Fortschritt je Anforderung,
- Versuche, erzeugte Snapshots und Fehlerdetails,
- **Fehlgeschlagene Einträge wiederholen**, ohne erfolgreiche Einträge erneut auszuführen.

Ein UI-Polling-Timeout markiert den serverseitigen Job nicht als fehlgeschlagen. Der persistierte Job bleibt auffindbar und wird vom Browser wieder aufgenommen.

## 5. Anforderungsdetail öffnen

Jede Anforderung besitzt eine teilbare URL:

```text
/projects/{projectId}/requirements/{requirementId}
```

Der Detailarbeitsplatz verbindet die gesamte Nachweiskette.

### Text und Quelle

Aktueller Anforderungstext und ursprüngliches Quellfragment werden mit Quellartefakt, Abschnitt und Seite nebeneinander dargestellt.

### Versionen

- Jede Textänderung erzeugt eine unveränderliche Version.
- Autor, Zeitpunkt und Änderungsgrund bleiben sichtbar.
- Ein verständlicher Vergleich zeigt hinzugekommene und entfallene Zeilen.

### Analysen

- Die Snapshot-Historie bleibt auswählbar.
- Provider, Modell, Laufzeit, Taxonomie- und Prompt-Fingerprint, Branch und Commit bestimmen die exakte Baseline.
- Warnungen, Lücken und Empfehlungen werden beim Snapshot angezeigt.

### Taxonomie und Architektur

Zuordnungen zeigen Code, Titel, Hierarchiepfad, Score, Relevanz, Konfidenz, Herkunft und Prüfstatus. Beziehungen werden getrennt von Elementzuordnungen dargestellt.

### Entscheidungen

Menschliche Entscheidungen zeigen Maßnahme, tatsächliche Evidenz, Reviewer und Zeitpunkt. Die Anwendung erfindet keine menschliche Prüfbegründung.

### Lösungen und Produkte

Verknüpfte wiederverwendbare Lösungen, Anforderungsabdeckung und quellengebundene Produktkandidaten erscheinen im selben Anforderungskontext.

## 6. Taxonomiezuordnungen und Maßnahmen prüfen

Einen Snapshot öffnen und eine Maßnahme wählen, beispielsweise:

- bereits erfüllt,
- wiederverwenden,
- ändern,
- neu schaffen,
- beschaffen,
- organisatorisch umsetzen,
- stilllegen oder ersetzen.

Beim Bestätigen öffnet sich ein geführter Entscheidungsdialog. Nur tatsächlich geprüfte Evidenz oder Begründung eingeben. Automatische Systemmetadaten und menschliche Aussagen bleiben getrennt.

## 7. Lösungen und Produkte pflegen

### Lösungen

Der Reiter **Lösungen** verwaltet wiederverwendbare Lösungsdefinitionen und projektspezifische Entscheidungen.

Beim Ergänzen einer Taxonomieabdeckung den Taxonomie-Picker verwenden. Mindestens zwei Zeichen eines Codes oder Titels eingeben. Vorschläge enthalten Taxonomiebereich und Hierarchiekontext; Codes müssen nicht auswendig bekannt sein.

Aus bestätigter Lösungsabdeckung kann **Lösungen vorschlagen** Kandidaten erzeugen. Die Verknüpfungen bleiben vorgeschlagen, bis ein Mensch sie bestätigt.

### Produkte

Jedes Produkt benötigt Hersteller, Produktname, Quellenreferenz und Verifikationszeitpunkt. Produktkandidaten werden in einer Vergleichstabelle gegenübergestellt:

- Abdeckung,
- Prüf- und Auswahlstatus,
- harte Ausschlusskriterien,
- Quelle und Version.

Ein Kandidat kann nur `SELECTED` werden, wenn die Prüfung bestätigt ist und kein hartes Ausschlusskriterium vorliegt.

## 8. Konflikte erkennen und lösen

Im Projektkopf **Konflikte erkennen** wählen.

Der geführte Dialog zeigt:

- beide Anforderungen,
- Konflikttyp,
- Evidenz und Konfidenz,
- ausgewählte Prüfentscheidung,
- Feld für Lösung und Begründung.

Ein Konflikt kann vorgeschlagen bleiben, bestätigt, verworfen oder gelöst werden. Beim Lösen ist eine ausdrückliche Erläuterung erforderlich, die Teil des Audit-Trails wird.

## 9. Interaktive Matrizen untersuchen

Folgende Seite öffnen:

```text
/projects/{projectId}/matrices
```

Verfügbar sind:

- Anforderungen × Taxonomie,
- Anforderungen × Lösungen,
- Lösungen × Produkte.

Nach Zeilen-/Spaltentext, Mindestabdeckung und Beziehungszustand filtern. Jede Zelle ist eine per Tastatur bedienbare Schaltfläche. Das Detailpanel erläutert Beziehung, Prüfstatus, Evidenz, Produktquelle und verlinkt gegebenenfalls zur Anforderung.

Eine leere Zelle bedeutet „keine gespeicherte Beziehung“ und nicht „expliziter Null-Score“. Für schmale, mobile oder stark vergrößerte Ansichten steht eine alternative Liste zur Verfügung. Die gefilterte Sicht kann als CSV oder JSON exportiert werden.

## 10. Portfolio committen, wiederherstellen und mergen

Folgende Seite öffnen:

```text
/projects/{projectId}/versioning
```

Die Seite zeigt Workspace, aktiven Branch, HEAD, Portfoliozahlen und erzeugtes TaxDSL.

### Commit

1. Erzeugte Portfolioprojektion prüfen.
2. Zielbranch auswählen.
3. Aussagekräftige Commitnachricht eingeben.
4. **Geprüften Stand committen** wählen.

Der angemeldete Benutzer wird Git-Autor. Das Ergebnis zeigt die exakte Commit-ID.

### Branch materialisieren

1. Branch auswählen.
2. **Materialisierung prüfen** wählen.
3. Ziel-HEAD, Fingerprints und hinzugekommene/entfallene Zeilen prüfen.
4. Erst nach Prüfung einer möglichen destruktiven Änderung bestätigen.

Unmittelbar vor dem Anwenden wiederholt der Browser die Vorschau. Ein inzwischen veränderter Ziel-HEAD stoppt die Operation statt einen veralteten Stand anzuwenden.

### Merge

Verschiedene Quell- und Zielbranches sowie eine Mergenachricht wählen. Zunächst wird ein normaler Git-Merge versucht. Wenn das TaxDSL textuell nicht sicher zusammengeführt werden kann, greift der semantische TaxDSL-Merge. Nicht aufgelöste semantische Konflikte stoppen ohne Mutation.

## 11. Projekt- und Anforderungsberichte erzeugen

Folgende Seite öffnen:

```text
/projects/{projectId}/reports
```

Gesamtes Projekt oder einzelne Anforderung wählen. Vor dem Export die HTML-Vorschau prüfen.

Formate:

- HTML,
- DOCX,
- Markdown,
- JSON mit stabilen IDs und vollständiger Provenienz,
- CSV für jede Portfoliomatrix.

Berichte enthalten Anforderungen und Quellen, Taxonomieabdeckung, Lösungen, Produkte, Konflikte und die exakte Reproduzierbarkeitsbaseline: Anforderungsversion, Snapshot, Provider/Modell, Taxonomie- und Prompt-Fingerprint, Branch und Commit.

## 12. Barrierefreiheit und robuste Bedienung

Das Portfolio verwendet native Schaltflächen und Navigation statt eines unvollständigen ARIA-Listbox-Musters. Die wichtigsten Abläufe werden browserseitig geprüft mit:

- Tastaturbedienung,
- zugänglichen Namen und Live-Status,
- mobiler Darstellung,
- Textvergrößerung,
- Forced-Colors-/Kontrastprüfung,
- HTML-, ARIA- und Screenshot-Evidenz.

Informationen sollen nicht ausschließlich durch Farbe vermittelt werden.

## Fehlerbehebung

### Eine Analyse läuft nach dem Verlassen der Seite weiter

Das ist beabsichtigt. Der Job ist serverseitig persistiert. Zu `/projects` zurückkehren; das Job-Center stellt den aktuellen Zustand wieder her.

### Eine Schreibaktion ist deaktiviert

Die angemeldete Rolle besitzt möglicherweise Lese- oder Analyse-, aber keine Architekturänderungsrechte. Der Grund wird am Bedienelement erklärt. Bei falscher Rollenvergabe Administrator kontaktieren.

### Ein Import wird abgelehnt

Doppelte Schlüssel, fehlende Zielanforderungen für neue Versionen, leere beibehaltene Texte und konfigurierte Importgrenzen prüfen. Da der Import atomar ist, bleibt kein früherer Kandidat derselben Bestätigung teilweise erhalten.

### Eine Materialisierungsvorschau hat sich geändert

Ein anderer Commit hat den Branch weitergeschaltet. Vorschau aktualisieren und den neuen Ziel-HEAD vor dem Anwenden erneut prüfen.

## Weiterführende Dokumentation

- [Projektportfolio-API](PROJECT_PORTFOLIO_API.md) – REST, JSON und Automatisierungsbeispiele
- [Portfolio-Betrieb](PROJECT_PORTFOLIO_OPERATIONS.md) – Migration, Backup, Limits und Recovery
- [Architektur der Git-Zusammenarbeit](PROJECT_PORTFOLIO_GIT_COLLABORATION.md)
- [ADR 0001](../adr/0001-project-requirement-solution-portfolio.md)
