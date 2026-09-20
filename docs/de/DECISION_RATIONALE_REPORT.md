# Hierarchischer Entscheidungs- und Begründungsbericht

Der **hierarchische Entscheidungs- und Begründungsbericht** dokumentiert, wie eine Anforderung durch die Taxonomiehierarchie geführt wurde und weshalb die Analyse bei einem oder mehreren konkreten Blattknoten endet. Er ist ein eigenständiger Nachweis neben dem Architekturbericht: Der Architekturbericht erläutert die resultierende Architekturansicht, der Entscheidungsbericht erläutert die Hierarchieentscheidungen, aus denen die klassifizierten Blattkandidaten hervorgegangen sind.

## Inhalt des Berichts

Der Bericht steht als DOCX, eigenständiges HTML und strukturiertes JSON zur Verfügung. DOCX und HTML enthalten:

- eine professionell gestaltete Titelseite mit Anforderung, Dokumentstatus, Taxonomy-Anwendungsversion, Build-Commit, Version des Taxonomiekatalogs, SHA-256 der Katalogdatei, SHA-256 des tatsächlich geladenen Datenbestands, SHA-256 des Analysesnapshots, Repository-/Workspace-/Branch-/Commit-Kontext, KI-Anbieter, Erzeugungszeitpunkt und erzeugendem Account;
- eine Kurzfassung mit dem am höchsten bewerteten tatsächlichen Blattknoten und dem vollständigen Entscheidungspfad von der Wurzel bis zum Blatt einschließlich einer kurzen Begründung für jeden Schritt;
- genau ein Kapitel für jeden tatsächlichen Vaterknoten, dessen direkte Kinder mindestens einen Wert größer 0% besitzen;
- in jedem Kapitel eine deterministisch erzeugte Grafik aus Vaterknoten und sämtlichen direkten Kindern mit absolutem Wert, lokalem Anteil am Vaterwert, Rang, Pfadstatus und Kennzeichnung des innerhalb der Geschwistergruppe führenden Knotens;
- eine Vergleichstabelle mit den ursprünglichen KI-Begründungen, soweit vorhanden, sowie eindeutig gekennzeichneten deterministischen Ersatztexten;
- eine Rangliste positiv bewerteter tatsächlicher Blattknoten, Hinweise, Vollständigkeitsangaben und technische Nachweise;
- auf jeder Seite eine Fußzeile mit Erzeugungszeitpunkt, Account, Taxonomy-Version, Taxonomiedaten-Version und den Feldern `Seite X von Y`.

## Bedeutung der Prozentwerte

Die Werte sind **Relevanz- und Zuordnungswerte**, keine statistischen Wahrscheinlichkeiten.

- Die Taxonomie-Wurzelbereiche werden unabhängig auf einer Skala von 0 bis 100 bewertet. Ihre Werte müssen zusammen nicht 100 ergeben.
- Unterhalb eines Vaterknotens ist der absolute Kinderwert der bis zu diesem Kind weitergetragene Relevanzwert.
- Der lokale Anteil wird als `Kinderwert / Vaterwert × 100` berechnet und erläutert die Entscheidung innerhalb der Geschwistergruppe.
- Ein vorhandener Wert `0` bedeutet: Der Knoten wurde bewertet und für diese Anforderung verworfen.
- Ein fehlender Wert bedeutet: Der Knoten wurde nicht bewertet. Fehlend wird niemals als 0% dargestellt.

## Vollständigkeit und Dokumentstatus

Ein Bericht ist erst dann abschließend, wenn alle Wurzelbereiche bewertet wurden und für jeden positiven inneren Knoten sämtliche direkten Kinder einen Wert besitzen. Fehlen bei einem positiven Vaterknoten Kinderbewertungen oder endet die Analyse vor einem tatsächlichen Blatt, wird der Bericht deutlich als Entwurf gekennzeichnet. Positive Vaterknoten, deren vollständig bewertete Kinder sämtlich 0% erhalten, bleiben nur dann ungelöst, wenn kein validierter strukturierter `productCoverageGap` vorliegt. Belegt dieser Nachweis die vollständige erfolglose Bewertung der konkreten Produkte, ist die Produktabdeckungslücke eine abgeschlossene Feststellung und der Bericht erhält `FINAL_WITH_WARNINGS` statt `DRAFT_INCOMPLETE`.

Mögliche Zustände:

- `FINAL`
- `FINAL_WITH_WARNINGS`
- `DRAFT_INCOMPLETE`
- `NO_RESULT`

## Nachvollziehbarkeit und Reproduzierbarkeit

Bei der Berichterzeugung wird die abgeschlossene Analyse nicht nachträglich durch eine KI uminterpretiert. Der Bericht übernimmt die ursprünglichen Begründungen aus dem Scoring, verändert keine Werte und keine Hierarchiebeziehungen und kennzeichnet deterministische Ersatzbeschreibungen ausdrücklich. Bei einem Bericht aus einem unveränderlichen Projekt-Analysesnapshot wird die darin
serialisierte vollständige Taxonomiehierarchie verwendet. Der aktuelle Katalog wird nicht
mit historischen Scores vermischt. Drei voneinander unabhängige SHA-256-Fingerabdrücke
werden dokumentiert:

1. die konfigurierte Katalogressource bei Ad-hoc-Berichten beziehungsweise der Hinweis,
   dass die historische Quelldatei im Snapshot nicht separat gespeichert wurde;
2. die tatsächlich verwendete aktuelle oder eingefrorene Taxonomiehierarchie;
3. der konkrete Analysesnapshot, aus dem der Bericht erzeugt wurde.

Zusätzlich werden der im Snapshot gespeicherte Taxonomie-Fingerabdruck und der
Prompt-Fingerabdruck ausgegeben. Weicht der Fingerabdruck der eingefrorenen Hierarchie
vom gespeicherten Wert ab, erhält der Bericht einen ausdrücklichen Prüfhinweis.

Der Analyse-Fingerabdruck umfasst Anforderung, Anbieter, Status, sortierte Bewertungen, sortierte Begründungen, dokumentierte Abweichungen und validierte Produktabdeckungslücken. Derselbe Taxonomie- und Analysestand führt damit zum selben fachlichen Entscheidungsinhalt; lediglich Erzeugungsmetadaten wie Zeitpunkt und Account ändern sich.

## Extension-Architektur

Die Funktion ist als Berichtsfamilie `decision-rationale` über die vorhandene
Spring-freie SPI `ReportRendererExtension` eingebaut. Die Renderer-Registry adressiert
Renderer nun über das Paar `(reportTypeId, formatId)`. Die bisherigen Architekturformate
bleiben damit `architecture/markdown`, `architecture/html`, `architecture/docx` und
`architecture/json`; die neue Berichtsfamilie registriert:

- `decision-rationale/docx`
- `decision-rationale/html`
- `decision-rationale/json`

Über `/api/extensions` werden die Extension-IDs `decision-rationale:docx`,
`decision-rationale:html` und `decision-rationale:json` sichtbar. Der Controller enthält
keine formatspezifische Renderlogik: Er erzeugt das vertrauenswürdige Berichtsmodell und
delegiert an die Registry. Ein weiteres Ausgabeformat kann deshalb durch genau einen
zusätzlichen Renderer ergänzt werden, ohne den Controller zu ändern.

Dies ist ein Classpath-Extension-Point entsprechend der bestehenden Taxonomy-Architektur.
Beliebige externe JARs werden derzeit nicht zur Laufzeit nachgeladen.

## REST-API

```http
GET  /api/decision-report/formats
POST /api/decision-report/docx
POST /api/decision-report/html
POST /api/decision-report/json
Content-Type: application/json
```

Beispiel:

```json
{
  "businessText": "Bereitstellung einer sicheren integrierten Kommunikationsfähigkeit für Einsatzteams.",
  "scores": {
    "CO": 90,
    "CO-1000": 70,
    "CO-1010": 55
  },
  "reasons": {
    "CO": "Die Anforderung betrifft unmittelbar Kommunikationsdienste.",
    "CO-1000": "Diese Kategorie bildet die benötigte Dienstfamilie am besten ab.",
    "CO-1010": "Dieser konkrete Dienst entspricht dem beschriebenen Bedarf am genauesten."
  },
  "provider": "GEMINI",
  "analysisStatus": "SUCCESS",
  "discrepancies": [],
  "productCoverageGaps": [],
  "language": "de"
}
```

Der Server ermittelt den erzeugenden Account sowie den maßgeblichen Repository-/Workspace-/Branch-/Commit-Kontext aus der authentifizierten Anfrage. Vom Client übermittelte Identitäts- oder Fußzeilenangaben werden nicht akzeptiert.

Die Antwort enthält zusätzlich folgende Prüfüberschriften:

- `X-Taxonomy-Data-SHA256`
- `X-Taxonomy-Analysis-SHA256`


### Bevorzugter Weg für vollständig verarbeitete Anforderungen

Für Anforderungen aus dem Projektportfolio wird der Bericht unmittelbar aus dem
unveränderlichen Analysesnapshot erzeugt:

```http
GET /api/projects/{projectId}/snapshots/{snapshotId}/decision-report/formats
GET /api/projects/{projectId}/snapshots/{snapshotId}/decision-report/docx?language=de
GET /api/projects/{projectId}/snapshots/{snapshotId}/decision-report/html?language=de
GET /api/projects/{projectId}/snapshots/{snapshotId}/decision-report/json?language=de
```

Dieser Weg ist gegenüber dem Ad-hoc-Endpunkt zu bevorzugen. Er verwendet serverseitig
und mandantensicher den gespeicherten Anforderungstext, die Anforderungsversion, den
ursprünglichen Provider und das Modell, Scores, Gründe, Taxonomie- und
Prompt-Fingerabdruck, Ersteller und Erstellungszeit, Repository-/Workspace-/Branch-/Commit-
Provenienz sowie die im Analyse-Payload eingefrorene vollständige Hierarchie. Ein Browser
kann diese Nachweisdaten nicht überschreiben. Snapshots ohne eingefrorene Hierarchie
werden nicht scheinbar reproduzierbar gerendert, sondern mit einem Konflikt abgelehnt.

Die Anforderungsdetailseite zeigt für den ausgewählten Snapshot direkte Download-Aktionen
für DOCX, HTML und JSON. Sie verwendet dafür den zentralen `TaxonomyPortfolioApi`-Client
und konstruiert keinen abweichenden Projekt-API-Pfad im Seitenmodul.

## Benutzeroberfläche

Nach einer Analyse bietet der Exportbereich folgende Aktionen:

- **Entscheidungsbericht (.docx)**
- **Entscheidungsbericht (.html)**
- **Entscheidungsbericht (.json)**

Beim Ad-hoc-Export übermittelt der Browser die im aktuellen Analysesitzungszustand gehaltenen Bewertungs- und Begründungskarten. Der Bericht kennzeichnet deshalb ausdrücklich, dass er nicht an einen unveränderlichen Projekt-Analysesnapshot gebunden ist. Auch importierte gespeicherte Analysen können exportiert werden; fehlende Anbieter- oder Statusangaben werden dabei ausdrücklich als unbekannt ausgewiesen und nicht erfunden.

## Betriebliche Konfiguration

```properties
taxonomy.catalogue.resource=classpath:data/C3_Taxonomy_Catalogue_25AUG2025.xlsx
taxonomy.report.time-zone=Europe/Berlin
git.commit.id=${GIT_COMMIT:${GITHUB_SHA:unknown}}
```

Bei Release-Builds liefert `BuildProperties` die Anwendungsversion; `GitProperties` beziehungsweise `git.commit.id` liefert den Build-Commit. Nicht verfügbare Metadaten werden als `unknown` dargestellt und niemals durch einen scheinbar plausiblen Wert ersetzt.

## Word-Berichte aus unveränderlichen Snapshots

Die Architekturansicht bietet **Architektur als Word** und **Entscheidungen als Word** für die ausgewählte gespeicherte Analyse:

- `GET /api/projects/{projectId}/snapshots/{snapshotId}/architecture-report/docx?language=de`
- `GET /api/projects/{projectId}/snapshots/{snapshotId}/decision-report/docx?language=de`

Beide verwenden dieselbe gespeicherte Anforderungsversion, Entscheidungsevidenz und den persistierten Architekturgraphen. Aktuelle Katalogauswahlen, Präferenzen und Vorschläge berechnen den Graphen nicht erneut. Beide enthalten Architekturübersicht, begrenzte Detailabbildungen, vollständige Element- und Beziehungsinventare, Empfehlung, dokumentierte Lücken und Herkunftsnachweise. Der Entscheidungsbericht enthält außerdem alle Vaterknoten und Alternativen im verlinkten Entscheidungsbaum sowie sämtliche Entscheidungskapitel. Fehlende Werte bleiben „Nicht bewertet“; null Prozent bedeutet bewertet und verworfen.

`X-Taxonomy-Snapshot-Id` und `X-Taxonomy-Graph-SHA256` kennzeichnen die gemeinsame Quelle. Dieselben Werte stehen in den Dokumenteigenschaften `taxonomy.snapshot.id` und `taxonomy.graph.sha256`. Der Graphen-Fingerabdruck umfasst alle eingefrorenen Knoten- und Beziehungsdaten einschließlich Container-Metadaten, ohne veränderliche Anzeigetitel. Er unterscheidet sich vom rein semantischen Fingerabdruck der Austauschformate.

Überschriften, Inhaltsverzeichnisfelder, statische interne Navigation, wiederholte Tabellenköpfe, nummerierte Beschriftungen und Bildbeschreibungen sind native OOXML-Strukturen. Die Detailansichten umfassen alle Knoten und Beziehungen mit höchstens 12 Knoten und 18 Beziehungen je Abbildung sowie mindestens 8pt für Knotenbeschriftungen. Mehr als 500 Knoten, 1.000 Beziehungen oder 250 Detailansichten werden mit HTTP 409 abgewiesen; Graphen werden niemals still gekürzt. Fehlende oder widersprüchliche eingefrorene Evidenz führt ebenfalls zu 409.

`language=en` wählt englische generierte Beschriftungen. Gespeicherte Evidenz und administrativ gepflegte Vorlagentexte bleiben unverändert. Der Entscheidungsbericht verwendet weiterhin den versionierten DOTX-Body-Marker und Dekorator; eigene Formatvorlagen, Kopf- und Fußzeilen, Logos und Vorlagenidentität bleiben erhalten. `/api/report/docx` bleibt der strukturierte **Ad-hoc-/Live-Bericht** ohne behauptete Snapshot-Herkunft.

Die zivile Akzeptanzprüfung prüft Quellenparität und semantische Vollständigkeit und rendert beide Word-Dateien mit LibreOffice/Poppler. Dies ist keine Produktzertifizierung für Microsoft Word.

Die vollständige zivile Prüffallausgabe umfasst 71 Entscheidungsseiten (davon 48 Kapitelseiten) und 10 eigenständige Architekturseiten. Die Render-QA-Grenzen betragen 74 bzw. 12 Seiten und erlauben 3/2 Seiten Renderer-Abweichung; Evidenzabdeckung, Lesbarkeit, leere Seiten und verwaiste Überschriften werden weiterhin geprüft. Kompakte Beziehungskennungen entfallen dort, wo sie Knoten verdecken würden; native Beziehungsschlüssel und Inventare erhalten jede Kennung, Quelle, jedes Ziel und jeden Typ.
