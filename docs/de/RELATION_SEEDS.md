# Relationstyp-Seed-Modell

## Überblick

Der Taxonomy Architecture Analyzer verwendet eine **Relations-Seed-CSV-Datei**, um
Standard-Architekturrelationen zwischen Taxonomie-Elementtypen zu definieren. Diese Seeds werden
beim Anwendungsstart geladen, wenn kein Relations-Sheet in der Excel-Arbeitsmappe vorhanden ist.

Die Seed-Datei befindet sich unter `src/main/resources/data/relations.csv` im
`taxonomy-app`-Modul.

---

## CSV-Format

Die CSV verwendet ein 10-Spalten-Format mit einer obligatorischen Kopfzeile:

```
SourceCode,TargetCode,RelationType,Description,SourceStandard,SourceReference,Confidence,SeedType,ReviewRequired,Status
```

| Spalte | Erforderlich | Standard | Beschreibung |
|---|---|---|---|
| **SourceCode** | ja | — | Taxonomie-Stammcode des Quellelements (z. B. `CP`, `CR`, `BP`) |
| **TargetCode** | ja | — | Taxonomie-Stammcode des Zielelements |
| **RelationType** | ja | — | Einer der `RelationType`-Enum-Werte (z. B. `REALIZES`, `SUPPORTS`) |
| **Description** | nein | `null` | Verständliche Erklärung, warum diese Relation existiert |
| **SourceStandard** | nein | `null` | Das Framework oder der Standard, der diese Relation begründet (z. B. `TOGAF`, `NAF`, `APQC`, `FIM`, `LOCAL`) |
| **SourceReference** | nein | `null` | Spezifische Referenz innerhalb des Standards (z. B. `NCV-2`, `Business Architecture`) |
| **Confidence** | nein | `1.0` | Numerischer Konfidenzwert zwischen 0,0 und 1,0 |
| **SeedType** | nein | `TYPE_DEFAULT` | Klassifizierung: `TYPE_DEFAULT`, `FRAMEWORK_SEED` oder `SOURCE_DERIVED` |
| **ReviewRequired** | nein | `false` | Ob eine menschliche Prüfung dieser Relation empfohlen wird |
| **Status** | nein | `accepted` | Aktueller Status: `accepted` oder `proposed` |

### Abwärtskompatibilität

Der Parser (`RelationSeedParser`) unterstützt Legacy-CSV-Dateien mit 3 oder 4 Spalten.
Fehlende Spalten erhalten ihre Standardwerte. Dies stellt sicher, dass ältere CSV-Dateien
ohne Änderung weiterhin funktionieren.

---

## Seed-Typen

| Seed-Typ | Bedeutung | Provenienz-Präfix |
|---|---|---|
| **TYPE_DEFAULT** | Strukturelle Relationen, die immer zwischen Taxonomietypen erwartet werden (z. B. CP → CR REALIZES). Diese bilden das grundlegende Architekturmodell. | `csv-default` |
| **FRAMEWORK_SEED** | Relationen, die von einem spezifischen Architektur-Framework wie TOGAF, NAF, APQC oder FIM abgeleitet sind. Diese erweitern das Standardmodell mit framework-spezifischem Wissen. | `csv-framework` |
| **SOURCE_DERIVED** | Relationen, die aus einem Regulierungsdokument, Industriestandard oder einer Referenzquelle abgeleitet sind, nicht aus einem Framework. | `csv-source-derived` |

### Provenienz-Kodierung

Wenn Seed-Relationen in die Datenbank geladen werden, wird ihr Provenienz-Feld auf
`{Präfix}:{SourceStandard}` gesetzt — zum Beispiel `csv-default:NAF` oder
`csv-framework:TOGAF`. Wenn kein SourceStandard angegeben ist, wird nur das Präfix
verwendet (z. B. `csv-source-derived`).

---

## Quellstandards

| Standard | Beschreibung |
|---|---|
| **NAF** | NATO Architecture Framework — Architekturstandard für Militär/Regierung |
| **TOGAF** | The Open Group Architecture Framework — Enterprise-Architekturstandard |
| **APQC** | American Productivity & Quality Center — Prozessklassifikations-Framework |
| **FIM** | Föderales Informationsmanagement — deutsches Verwaltungsinformationsmodell |
| **LOCAL** | Projekt- oder domänenspezifische Relation, nicht aus einem veröffentlichten Standard abgeleitet |

---

## Neue Seed-Relationen hinzufügen

Beim Hinzufügen neuer Relationen zur CSV-Datei folgende Richtlinien beachten:

1. **Semantische Korrektheit prüfen.** Jede Relation muss eine reale
   Architekturabhängigkeit darstellen, die von den Taxonomie-Elementtypen unterstützt wird.

2. **Richtig klassifizieren.** `TYPE_DEFAULT` nur für universell erwartete
   Relationen verwenden. `FRAMEWORK_SEED` für framework-abgeleitete Relationen verwenden.
   `SOURCE_DERIVED` nur verwenden, wenn die Relation aus einem bestimmten Dokument stammt.

3. **Konfidenz zuweisen.** `1.0` für gut etablierte Relationen verwenden und niedrigere
   Werte (z. B. `0,8`–`0,9`) für Relationen, die angemessen sind, aber möglicherweise nicht
   in allen Kontexten gelten.

4. **Review-Erforderlich markieren.** `ReviewRequired` auf `true` setzen für jede Relation,
   deren semantische Passung diskutabel ist oder deren Auswirkung auf die Propagierung von
   einem Domänenexperten validiert werden sollte.

5. **Relationen nicht zusammenfassen.** Die Verwendung von `RELATED_TO` als Auffangbecken
   vermeiden. Jede Relation sollte den spezifischsten `RelationType` verwenden, der zutrifft.

6. **Begründung dokumentieren.** Die Description-Spalte sollte erklären, *warum* die
   Relation existiert, nicht nur den Typnamen wiederholen.

### Beispiel: Eine Seed-Relation für eine öffentliche Regulierung hinzufügen

```csv
CP,IP,REQUIRES,Capability CP-1023 requires IP-2001 per BSI IT-Grundschutz APP.1.1,BSI,IT-Grundschutz APP.1.1,0.85,SOURCE_DERIVED,true,proposed
```

---

## Relationstypen

Das System definiert 12 Relationstypen. Siehe
[CONCEPTS.md — Relationstyp](CONCEPTS.md#relation-type) für die vollständige Tabelle.

---

## Implementierung

| Klasse | Modul | Rolle |
|---|---|---|
| `RelationSeedParser` | taxonomy-app | Parst und validiert die CSV-Datei |
| `RelationSeedRow` | taxonomy-domain | Unveränderlicher Record, der eine geparste CSV-Zeile darstellt |
| `SeedType` | taxonomy-domain | Enum zur Klassifizierung der Seed-Provenienz |
| `TaxonomyService.loadRelationsFromCsv()` | taxonomy-app | Lädt geparste Seeds beim Start in die Datenbank |
| `RelationCompatibilityMatrix` | taxonomy-app | Validiert Quell-/Zieltyp-Kompatibilität |
