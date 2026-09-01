# Architekturübergabe aus einem unveränderlichen Snapshot

Verwenden Sie die **Copilot Architecture Workbench**, wenn der Empfänger genau die in Taxonomy geprüfte Architektur erhalten soll. Die Workbench adressiert einen persistierten Analyse-Snapshot über Projekt-ID und Snapshot-ID. Beim Herunterladen wird diese gespeicherte Projektion serialisiert; das LLM wird **nicht** erneut ausgeführt.

Dies unterscheidet sich von den Komfortexporten der Ad-hoc-Analyseansicht. Diese älteren Pfade sind noch nicht beweissicher an einen persistierten Portfolio-Snapshot gebunden und dürfen nicht als Nachweis dafür verwendet werden, dass eine Datei exakt dem geprüften Ergebnis entspricht.

## Ablauf

1. Öffnen Sie eine Projektanforderung mit einer erfolgreichen aktuellen Analyse.
2. Wählen Sie **Open architecture workbench**.
3. Prüfen Sie die angezeigte Herkunft aus Projekt, Anforderung, Snapshot, Workspace, Branch und Commit.
4. Wählen Sie unter **Export format** ein Format aus.
5. Betätigen Sie **Download selected format**.

Die Auswahl bleibt deaktiviert, bis der angeforderte Snapshot erfolgreich geladen wurde. Ein fehlender, ungültiger oder nicht zugreifbarer Snapshot wird nicht durch die neueste Analyse ersetzt und löst niemals eine neue Analyse aus.

## Rollen der Formate

| Format | Übergaberolle | Geeignet für | Aktuelle Grenzen |
|---|---|---|---|
| Evidenz-JSON | `canonical-evidence` | Maschinenlesbarer Snapshot, kanonischer Graph, Render-Szene, Warnungen, Provenienz und deklarierte Exportprofile | Noch kein vollständiges ZIP-Übergabepaket und kein universelles Importformat |
| SVG | `stable-human-view` | Skalierbare, deterministische visuelle Referenz für Dokumente und Reviews | Visuelle Darstellung, kein vollständiges Architekturmodell |
| PDF | `stable-human-view` | Stabile druckbare Referenz | Visuelle Darstellung, kein vollständiges Architekturmodell |
| ArchiMate Exchange XML | `experimental-model-exchange` | Standardorientierter Architekturaustausch | Experimentell, bis Mapping, semantischer Roundtrip und unabhängige Werkzeugnachweise aus #967 vollständig sind |
| Mermaid | `lossy-text-projection` | Git, Markdown, Wiki und Dokumentationsdiagramme | Präsentationsorientiert; nicht unterstützte Taxonomy-Semantik wird bewusst nicht vollständig übertragen |
| Structurizr DSL | `lossy-text-projection` | Weiterarbeit in C4-/Structurizr-orientierten Umgebungen | Taxonomy-Konzepte werden auf ein kleineres Softwarearchitektur-Vokabular abgebildet |
| Visio VSDX | in diesem Ablauf nicht angeboten | Künftige editierbare Microsoft-Visio-Übergabe | Zurückgestellt, bis Paket-/Schema- und Realprodukt-Kompatibilität aus #965 abgeschlossen sind |

Keines der Darstellungsformate außer dem Evidenz-JSON ist als kanonisches Taxonomy-Persistenzformat zu verstehen. Autoritative Quelle bleiben Repository, Workspace, Branch, Commit und der unveränderliche Analyse-Snapshot, die in der Workbench angezeigt werden.

## Prüfen, ob Dateien zum selben Graphen gehören

Jede Antwort enthält folgende Header:

| Header | Bedeutung |
|---|---|
| `X-Taxonomy-Architecture-Snapshot` | ID des persistierten Analyse-Snapshots |
| `X-Taxonomy-Architecture-Commit` | Autoritativer Git-Commit, sofern im Snapshot hinterlegt |
| `X-Taxonomy-Architecture-Graph-SHA256` | Formatunabhängiger Fingerprint des ausgewählten Knoten-/Beziehungsgraphen |
| `X-Taxonomy-Export-Profile` | Versioniertes Exportprofil der Datei |
| `X-Taxonomy-Export-Role` | Deklarierte Übergaberolle aus der obigen Tabelle |
| `X-Taxonomy-Export-Content-SHA256` | SHA-256 der heruntergeladenen Bytes |
| `ETag` | Inhaltsdigest in HTTP-ETag-Form |

Dateien aus demselben Snapshot und semantischen Graphen tragen denselben Architektur-Graph-Fingerprint; ihre Inhaltsdigests unterscheiden sich je Format. Das Evidenz-JSON enthält Snapshot-Koordinaten, Graph-Fingerprint und alle deklarierten Formatprofile, damit die Herkunft auch nach der Trennung der Datei von den HTTP-Headern erhalten bleibt.

## API

```text
GET /api/projects/{projectId}/architecture-workbench/{snapshotId}/exports/{formatId}
```

Unterstützte Werte für `formatId`:

```text
json
svg
pdf
archimate
mermaid
structurizr
```

Die bestehenden snapshotspezifischen `.svg`- und `.pdf`-URLs bleiben verfügbar und verwenden denselben Export- und Provenienzvertrag.

## Derzeitige Nichtziele und offene Arbeiten

Dieser Ablauf behauptet bewusst noch nicht:

- eine zertifizierte editierbare Visio-Übergabe;
- verlustfreie Konvertierung zwischen Taxonomy und ArchiMate, Mermaid oder Structurizr;
- ein vollständiges Mehrdateienpaket mit `manifest.json` und `losses.json`;
- semantische Roundtrip-Gleichheit durch jedes externe Modellierungswerkzeug;
- Autorität für ältere Exporte, die nur freien Anforderungstext entgegennehmen.

Die verbleibenden Arbeiten werden in #965, #966, #967 und dem übergreifenden Interoperabilitätsprogramm #926 verfolgt.
