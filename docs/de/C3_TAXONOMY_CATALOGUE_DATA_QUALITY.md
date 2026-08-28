# Datenqualitätsbericht zum C3 Taxonomy Catalogue vom 25. August 2025

## Zweck und Geltungsbereich

Dieser Bericht erklärt die in der eingecheckten Arbeitsmappe
`taxonomy-app/src/main/resources/data/C3_Taxonomy_Catalogue_25AUG2025.xlsx`
deterministisch festgestellten Struktur-, Ordnungs-, Text- und
Nachvollziehbarkeitsprobleme. Er bezieht sich auf genau die folgende Datei:

- SHA-256 der Arbeitsmappe: `6b19743eff1487a76ea3e5b788d90831ba1705da31790cf58f2d69a979b14130`
- Prüfdatum: 28. August 2026
- Tabellenblätter: 8
- geprüfte Datenzeilen ohne Kopfzeilen: 2.564

Die vollständige maschinenlesbare Einzelliste ist als
[`C3_Taxonomy_Catalogue_25AUG2025_audit.csv`](https://github.com/carstenartur/Taxonomy/blob/main/docs/data/C3_Taxonomy_Catalogue_25AUG2025_audit.csv)
beigefügt. Sie enthält für jeden Befund Schweregrad, Tabellenblatt,
Excel-Zeile, Knotencode, betroffenes Feld, Ist-Wert und gegebenenfalls
verknüpfte Codes. Die Erklärung und Korrekturempfehlung für jede Kategorie
stehen in diesem Bericht, damit sie nicht in jeder CSV-Zeile wiederholt werden.

> **Abgrenzung:** Der Bericht ist vollständig für die unten beschriebenen,
> deterministisch prüfbaren Regeln. Er kann nicht garantieren, dass jede
> fachliche Definition, jede NATO-Referenz oder jede semantische Einordnung
> inhaltlich richtig ist. Solche Aussagen benötigen eine fachliche Prüfung.

## Ergebnisübersicht

Die Prüfung erzeugte 1.204 Befunde. Ein Knoten oder eine Zelle kann dabei
mehr als einen Befund verursachen, beispielsweise eine ungültige
Elternreferenz und gleichzeitig einen daraus folgenden Level-Widerspruch.

| Schweregrad | Anzahl | Bedeutung |
|---|---:|---|
| Fehler | 943 | Der Baum ist strukturell ungültig oder der gespeicherte Inhalt ist objektiv beschädigt beziehungsweise falsch geschrieben. |
| Warnung | 226 | Die Daten sind technisch lesbar, besitzen aber Qualitäts-, Darstellungs- oder Eindeutigkeitsprobleme. |
| Prüffall | 35 | Die Daten sind nicht zwingend falsch, benötigen aber eine dokumentierte fachliche oder Governance-Entscheidung. |
| **Gesamt** | **1.204** | |

### Befunde je Tabellenblatt

| Tabellenblatt | Datenzeilen | Fehler | Warnungen | Prüffälle | Befunde gesamt |
|---|---:|---:|---:|---:|---:|
| Business Processes | 408 | 0 | 45 | 5 | 50 |
| Business Roles | 306 | 2 | 22 | 5 | 29 |
| Capabilities | 33 | 0 | 1 | 4 | 5 |
| COI Services | 130 | 0 | 19 | 4 | 23 |
| Communications Services | 91 | 0 | 6 | 4 | 10 |
| Core Services | 150 | 0 | 21 | 4 | 25 |
| Information Products | 1.071 | 941 | 60 | 5 | 1.006 |
| User Applications | 375 | 0 | 52 | 4 | 56 |
| **Gesamt** | **2.564** | **943** | **226** | **35** | **1.204** |

### Befundarten

| Kategorie | Anzahl | Schweregrad |
|---|---:|---|
| Nicht eingeordnete Information Products auf Wurzelebene | 853 | Fehler |
| Wiederholte Leerzeichen | 130 | Warnung |
| Doppelte Sortierreihenfolge unter demselben Elternknoten | 93 | Warnung |
| Eindeutig erkennbare Schreibfehler | 50 | Fehler |
| Fehlende Nachvollziehbarkeitsangaben | 32 | Prüffall |
| Beschädigte Zeichenkodierung | 16 | Fehler |
| Nicht vorhandene Elternknoten | 8 | Fehler |
| Widerspruch zwischen angegebenem und ableitbarem Level | 8 | Fehler |
| Unplausibler Level 14 | 5 | Fehler |
| Gleicher Titel in unterschiedlichen Zweigen | 3 | Warnung |
| Tabellenblätter mit nicht freigegebenen Entwürfen | 3 | Prüffall |
| Doppelter Titel unter demselben Elternknoten | 1 | Fehler |
| Selbstreferenz und Hierarchiezyklus | 1 | Fehler |
| Unerwarteter zusätzlicher Wurzelknoten | 1 | Fehler |

## 1. Fehler in der Hierarchie

### 1.1 853 nicht eingeordnete Information Products

Im Tabellenblatt **Information Products** existiert mit `IP-1000` bereits der
freigegebene fachliche Wurzelknoten. Trotzdem besitzen 853 als `draft`
gekennzeichnete konkrete Information Products keinen `Parent` und sind als
Level 1 eingetragen. Der Importer behandelt sie dadurch als direkte Kinder der
virtuellen Wurzel `IP`.

Das ist kein bloßes Darstellungsproblem. Es hat folgende Auswirkungen:

- Die bereits vorhandene fachliche Produkthierarchie wird umgangen.
- Eine Analyse muss auf einer Ebene Hunderte Kandidaten verarbeiten.
- Ein Produkt kann nicht über einen nachvollziehbaren Pfad von der
  Produktfamilie bis zum Blatt erklärt werden.
- Navigation, Diagramme, Berichte und semantische Suche verlieren fachlichen
  Kontext.
- Das Analysezeitlimit wird durch einen extrem großen Prompt begünstigt.

**Erforderliche Korrektur:** Jeder gültige konkrete Produktknoten benötigt
einen geprüften primären Elternknoten innerhalb der bestehenden freigegebenen
Information-Product-Hierarchie. Weitere plausible Einordnungen sollten als
sekundäre Klassifikation oder Relation erhalten bleiben, nicht durch
Knotenduplikate.

Die CSV-Anlage listet alle 853 betroffenen Codes und Excel-Zeilen einzeln auf.

### 1.2 Acht Verweise auf nicht vorhandene Elternknoten

Die folgenden Knoten referenzieren einen Code, der in der Arbeitsmappe nicht
existiert:

| Excel-Zeile | Knoten | Titel | Nicht vorhandener Parent |
|---:|---|---|---|
| 67 | `IP-1018` | Asset State Reports | `IP-1008` |
| 144 | `IP-1021` | Capability Reports | `IP-1008` |
| 274 | `IP-1036` | Enduring Plans | `IP-1003` |
| 567 | `IP-1067` | Location Reports | `IP-1008` |
| 792 | `IP-1080` | Organizational Plans | `IP-1003` |
| 882 | `IP-1086` | Resource Reports | `IP-1008` |
| 947 | `IP-1088` | Situation Reports | `IP-1008` |
| 1033 | `IP-1096` | Time-Limited Plans | `IP-1003` |

Der aktuelle Importer kann solche Zeilen ersatzweise an die virtuelle Wurzel
hängen. Dadurch bleibt die Anwendung zwar startfähig, die eigentliche
Dateninkonsistenz wird aber verdeckt und die resultierende Hierarchie ist
fachlich falsch.

**Erforderliche Korrektur:** Den beabsichtigten Elternknoten fachlich bestimmen,
den Parent-Code korrigieren und den Level anschließend aus der validierten
Elternkette neu berechnen. Ein nicht auflösbarer Parent darf in einem gepflegten
Katalog nicht stillschweigend ersetzt werden.

### 1.3 Selbstreferenzierender Knoten `IP-2065`

`IP-2065` **Geospatial Support Requests** verweist in Excel-Zeile 370 auf sich
selbst als Parent. Damit entsteht ein Zyklus der Länge eins. Vier weitere
Geospatial-Support-Knoten verweisen wiederum auf `IP-2065`.

**Auswirkung:** Ein korrekter Baum kann nicht aufgebaut werden. Je nach
Importer entstehen Endlosschleifen, ein Abbruch oder ein Fallback an die
Wurzel.

**Erforderliche Korrektur:** `IP-2065` einer fachlich passenden
Produktfamilie zuordnen und danach die vier abhängigen Knoten prüfen.

### 1.4 Fünf Knoten mit Level 14

Die folgenden Zeilen tragen den Level `14`, obwohl der übrige Katalog nur eine
wesentlich geringere Baumtiefe verwendet:

| Excel-Zeile | Knoten | Titel | Parent |
|---:|---|---|---|
| 366 | `IP-2130` | Geospatial Support Alerts | `IP-2065` |
| 367 | `IP-2131` | Geospatial Support Analyses | `IP-2065` |
| 368 | `IP-2084` | Geospatial Support Plans | `IP-2065` |
| 369 | `IP-2099` | Geospatial Support Reports | `IP-2065` |
| 370 | `IP-2065` | Geospatial Support Requests | `IP-2065` |

Der Wert steht im selben fehlerhaften Teilbaum wie die Selbstreferenz und kann
keine gültige Hierarchietiefe beschreiben.

**Erforderliche Korrektur:** Zuerst den Parent-Graphen korrigieren. Level-Werte
sollten anschließend ausschließlich aus dem validierten Baum berechnet werden,
statt als unabhängige zweite Wahrheit gepflegt zu werden.

### 1.5 Acht weitere Level-Widersprüche

Bei den acht Knoten mit nicht vorhandenem Parent ist der angegebene Level 4.
Da der Parent nicht auflösbar ist, kann die vorhandene Struktur diesen Level
nicht herleiten. Diese acht Befunde sind Folgewirkungen der unter 1.2
beschriebenen Parent-Fehler und keine zusätzlichen acht unabhängigen
Datenursachen.

### 1.6 Zusätzlicher Wurzelknoten in Business Roles

`BR-1220` **Capability Sustainment Manager** besitzt in Excel-Zeile 48 keinen
Parent und ist als Level 1 eingetragen. Im selben Tabellenblatt existiert
bereits der vorgesehene Wurzelknoten `BR-1000`.

**Erforderliche Korrektur:** Den Knoten einem fachlich passenden Rollenbereich
unter `BR-1000` zuordnen oder ausdrücklich dokumentieren, weshalb das
Tabellenblatt mehrere Wurzeln haben soll.

## 2. Mehrdeutige oder doppelte Bezeichnungen

### 2.1 Doppelter Geschwistertitel

Unter demselben Parent `BR-1175` tragen `BR-1201` und `BR-1221` beide den Titel
**Stakeholder Communication Manager**.

Da Pfad und Titel identisch sind, können UI, Suche, Bericht und KI-Klassifikation
die Rollen anhand der Bezeichnung nicht zuverlässig unterscheiden.

**Erforderliche Korrektur:** Entweder echte Duplikate zusammenführen oder für
fachlich unterschiedliche Rollen eindeutige Titel und Beschreibungen vergeben.

### 2.2 Gleiche Titel in unterschiedlichen Zweigen

Drei Titel kommen in verschiedenen Zweigen des Business-Roles-Baums vor:

- `BR-1061` und `BR-1290`: **Information Management Roles**
- `BR-1084` und `BR-1266`: **Medical Support Roles**
- `BR-1187` und `BR-1247`: **Risk Assessment Roles**

Das kann fachlich beabsichtigt sein, ist aber für titelbasierte Suche,
Auswahllisten und Berichte mehrdeutig.

**Erforderliche Prüfung:** Entweder die Bezeichnungen qualifizieren oder überall
den vollständigen Hierarchiepfad anzeigen und indexieren.

## 3. Nicht eindeutige Sortierung

In 93 Geschwistergruppen verwenden mindestens zwei Knoten denselben Wert in der
Spalte `Order`:

| Tabellenblatt | Betroffene Geschwistergruppen |
|---|---:|
| Business Processes | 13 |
| Business Roles | 9 |
| Capabilities | 1 |
| COI Services | 10 |
| Communications Services | 1 |
| Core Services | 16 |
| Information Products | 6 |
| User Applications | 37 |

Ein doppelter Order-Wert macht den Katalog nicht unlesbar, definiert aber keine
eindeutige fachliche Reihenfolge. Die Anwendung muss dann einen sekundären Sort
verwenden, beispielsweise Titel oder Code, und kann dadurch eine andere
Reihenfolge als beabsichtigt zeigen.

**Erforderliche Korrektur:** Innerhalb jedes Elternknotens eindeutige
Order-Werte vergeben oder eine verbindliche sekundäre Sortierregel als Teil des
Datenvertrags dokumentieren. Alle 93 Gruppen stehen in der CSV-Anlage.

## 4. Text- und Kodierungsfehler

### 4.1 Beschädigte Zeichenkodierung

16 Beschreibungen im Tabellenblatt Information Products enthalten typische
UTF-8/Windows-1252-Fehlinterpretationen wie:

- `â€œ` und `â€` statt typografischer Anführungszeichen,
- `â€“` oder `â€”` statt Gedankenstrich,
- `â€¢` statt Aufzählungszeichen,
- `â€˜` statt einfachem Anführungszeichen.

Beispiele sind unter anderem `IP-1475`, `IP-1285`, `IP-1314`, `IP-1643`,
`IP-1321`, `IP-1264`, `IP-1904`, `IP-1296`, `IP-1627` und `IP-1778`.

**Auswirkung:** Sichtbar beschädigter Text, schlechtere Volltext- und
Vektorsuche, unprofessionelle Berichte und möglicherweise verfälschte
LLM-Eingaben.

**Erforderliche Korrektur:** Die ursprüngliche Zeichenkodierung ermitteln und
die betroffenen Zeichen kontrolliert reparieren. Ein globales Suchen/Ersetzen
ohne Zeilenprüfung ist wegen unterschiedlicher Zeichenfolgen nicht ausreichend.

### 4.2 130 Zellen mit wiederholten Leerzeichen

130 Textfelder enthalten doppelte oder mehrfach aufeinanderfolgende
Leerzeichen. Betroffen sind Beschreibungen, Quellen und Referenzen in mehreren
Tabellenblättern.

**Auswirkung:** Sichtbare typografische Mängel, instabile exakte Vergleiche,
uneinheitliche Suchausschnitte und unnötige Unterschiede in versionierten
Exporten.

**Erforderliche Korrektur:** Horizontale Leerzeichen normalisieren, dabei aber
Absätze und beabsichtigte Listenformatierung erhalten.

### 4.3 50 eindeutig erkennbare Schreibfehler

Die deterministische Prüfung fand folgende eindeutig korrigierbare
Zeichenfolgen:

| Ist-Wert | Anzahl | Erwartete Korrektur |
|---|---:|---|
| `infomration` | 23 | `information` |
| `andfunctions` | 20 | `and functions` |
| `adress` | 2 | `address` |
| `capabilites` | 1 | `capabilities` |
| `maintaing` | 1 | `maintaining` |
| `responsibiilty` | 1 | `responsibility` |
| `Comunications` | 1 | `Communications` |
| `Troup Contributing` | 1 | fachlich prüfen; wahrscheinlich `Troop Contributing` |

Die CSV-Anlage nennt jede betroffene Excel-Zeile. Nicht eindeutig beweisbare
Grammatik- oder Stilfragen wurden bewusst nicht als bestätigte Tippfehler
gezählt.

## 5. Freigabe- und Nachvollziehbarkeitslücken

### 5.1 886 Knoten im Status `draft`

| Tabellenblatt | Draft-Knoten |
|---|---:|
| Business Processes | 2 |
| Business Roles | 18 |
| Information Products | 866 |
| **Gesamt** | **886** |

`draft` ist kein technischer Parsefehler. Der Status bedeutet aber, dass ein
erheblicher Teil des eingebundenen Katalogs nicht als stabiler, freigegebener
Bestand ausgewiesen ist. Besonders relevant ist dies bei den 853 nicht
eingeordneten Information Products.

**Erforderliche Entscheidung:** Jeden Entwurf freigeben, ablehnen, ersetzen
oder mit verantwortlicher Stelle, Begründung und geplantem Reviewtermin bewusst
als Entwurf weiterführen.

### 5.2 Fehlende Herkunftsfelder

Für jedes Tabellenblatt wurden die Felder `Dataset`, `External ID`, `Source`
und `Reference` auf Lücken geprüft. Daraus entstehen 32 aggregierte Prüffälle:
vier Felder mal acht Tabellenblätter.

Besonders auffällig:

- `Dataset` ist in allen 2.564 Datenzeilen leer.
- `External ID` ist in allen 2.564 Datenzeilen leer.
- Im Tabellenblatt Information Products sind `Source` und `Reference` in allen
  1.071 Zeilen leer.

Diese Felder können im ursprünglichen Export optional oder absichtlich leer
sein. Ohne dokumentierte Regel ist jedoch nicht nachvollziehbar, welche
Standardfassung, Quelle oder externe Identität einen Knoten belegt.

**Erforderliche Entscheidung:** Entweder die Felder aus einer maßgeblichen
Quelle füllen oder ausdrücklich dokumentieren, dass sie in diesem
Katalogformat nicht verwendet werden und welche alternative Provenienz gilt.

## 6. Prüfungen ohne Befund

Zur Einordnung wurden auch folgende Regeln geprüft, ohne einen Fehler zu finden:

- keine doppelten Knotencodes in der gesamten Arbeitsmappe,
- keine doppelten UUIDs,
- keine syntaktisch ungültigen UUIDs,
- keine nichtnumerischen Werte in `Order` oder `Level`,
- keine Elternreferenz auf einen Knoten eines anderen Tabellenblatts,
- keine Zeile ohne Code, Titel oder Beschreibung,
- kein freigegebener Kindknoten unter einem als Entwurf markierten Parent.

Diese Nullbefunde bedeuten nicht, dass alle Fachinhalte richtig sind; sie zeigen
nur, dass die genannten formalen Fehlerarten nicht vorkommen.

## 7. Empfohlene Korrekturreihenfolge

1. **Zyklen und nicht vorhandene Parents beheben.** Ohne gültigen Parent-Graphen
   sind Level, Pfade und Analysen nicht zuverlässig.
2. **Die 853 flachen Information Products einordnen.** Primären Parent,
   Konfidenz, Begründung und gegebenenfalls sekundäre Klassifikationen pflegen.
3. **Level aus dem validierten Baum berechnen.** Level nicht länger unabhängig
   neben Parent pflegen.
4. **Zusätzlichen Business-Roles-Wurzelknoten und doppelte Titel klären.**
5. **Sortierreihenfolgen eindeutig machen.**
6. **Kodierung, Leerzeichen und bestätigte Tippfehler reparieren.**
7. **Draft- und Provenienzentscheidungen dokumentieren.**
8. **Nach jeder Änderung die vollständige Prüfung erneut ausführen** und die
   Befunddifferenz versionieren.

## 8. Bedeutung für Taxonomy

Die Anwendung sollte die externe Excel-Datei als nachvollziehbare Basis
behandeln, ihre Fehler jedoch nicht stillschweigend als gültige Hierarchie
übernehmen. Insbesondere sollten unbekannte Parents, Selbstreferenzen und Zyklen
beim Katalogaufbau sichtbar und im gepflegten Datenbestand grundsätzlich
abgelehnt werden.

Korrekturen und fachliche Ergänzungen sollten getrennt von der externen
Arbeitsmappe versioniert werden. So bleiben sowohl der unveränderte
Ausgangsstand als auch jede lokale Korrektur prüfbar, vergleichbar und
rücknehmbar.

## Vollständige Einzelliste

Die CSV-Anlage besitzt folgende Spalten:

- `finding_id`
- `severity`
- `category`
- `sheet`
- `excel_row`
- `code`
- `field`
- `current_value`
- `related_codes`

Die fachliche Erklärung und die empfohlene Korrektur sind je Kategorie in den
Abschnitten 1 bis 5 dokumentiert.

SHA-256 der Anlage:
`3691af78ac1511a17836cec3af234aaf59a8b8d693a9e7453fdd3476046c4c5c`.
