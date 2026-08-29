# Reproduzierbare Vorschläge für den Katalog-Overlay

Die eingecheckte C3-Arbeitsmappe bleibt die Basis für Knotenidentität, Titel, Beschreibungen, Quellstatus und Provenienz. Die eingecheckte Datei `nato-taxonomy.json` bleibt der fachlich geprüfte Overlay. Die Vorschlagserzeugung überschreibt **keine** dieser Eingaben.

## Zweck

Bei einer neuen C3-Katalogversion muss nachvollziehbar werden, welche Zuordnungen weiterhin zur Quelle passen, welche vorläufigen Zuordnungen möglicherweise einen anderen Vaterknoten benötigen und welche neuen Entwurfsknoten noch nicht im geprüften Overlay vorkommen. Der repository-eigene Generator erzeugt zwei deterministische Prüfarbelege:

- `catalogue-overlay-proposal.json` für maschinenlesbare Vergleiche und spätere Werkzeuge;
- `catalogue-overlay-review.md` für die fachliche Prüfung und als CI-Artefakt.

Die Artefakte enthalten keinen Erzeugungszeitpunkt. Ihr Inhalt hängt ausschließlich von den Bytes der Arbeitsmappe, den Bytes des Overlays und dem versionierten deterministischen Algorithmus ab. Identische Eingaben liefern deshalb byte-identische Ausgaben.

## Lokale Ausführung

Im Wurzelverzeichnis des Repositories:

```bash
tools/catalogue-overlay/generate.sh
```

Standardmäßig werden folgende Dateien verwendet:

```text
taxonomy-app/src/main/resources/data/C3_Taxonomy_Catalogue_25AUG2025.xlsx
taxonomy-app/src/main/resources/data/nato-taxonomy.json
target/catalogue-overlay/catalogue-overlay-proposal.json
target/catalogue-overlay/catalogue-overlay-review.md
```

Andere Pfade können angegeben werden, ohne den geprüften Overlay zu verändern:

```bash
tools/catalogue-overlay/generate.sh \
  --catalogue pfad/zum/katalog.xlsx \
  --overlay pfad/zum/geprueften-overlay.json \
  --output target/vorschlag.json \
  --report target/pruefbericht.md
```

Der Generator lehnt jeden Ausgabepfad ab, der auf die Arbeitsmappe oder den geprüften Overlay zeigt.

## Entscheidungsautorität

Jeder Vorschlag nennt seine Autorität ausdrücklich:

- `REVIEWED_OVERLAY`: `reviewRequired=false`; der vorhandene geprüfte Vaterknoten und die Sekundärklassifikationen bleiben gesperrt und werden vom Generator niemals geändert.
- `AUTOMATED_PROPOSAL`: deterministische lexikalische Empfehlung für eine vorläufige oder neu erkannte Zuordnung im strikten Geltungsbereich. Sie ist ein Prüfindiz, keine freigegebene Klassifikation.

Bei zu geringer Evidenz wird das Ergebnis als ungelöst markiert, statt einen Gewinner zu erfinden.

## Validierung und Drifterkennung

Die Erzeugung bricht kontrolliert ab bei:

- einer unerwarteten Spaltenstruktur der Arbeitsmappe;
- doppelten Knotencodes oder UUIDs;
- unpassender Overlay-Schemaversion oder falschem Basiskatalog;
- Abweichungen von erwartetem Quelltitel oder Quellstatus;
- unbekannten, selbstreferenziellen, taxonomieübergreifenden oder zyklischen Vaterbeziehungen;
- ungültigen oder doppelten Sekundärklassifikationen;
- einem als `PRODUCT` klassifizierten Knoten mit Kindknoten.

Neue Knoten im strikten Geltungsbereich ohne Overlay-Eintrag erscheinen als `NEW_MAPPING` oder `NEW_UNRESOLVED`. Der Generator ändert den Overlay dabei nicht.

## Prüf- und Übernahmeprozess

1. Beide Artefakte erzeugen und prüfen.
2. Alle Zeilen mit `REVIEW_REQUIRED_*`, `NEW_*` sowie ungelöste Vorschläge einschließlich Alternativen und Fan-out-Änderungen fachlich bewerten.
3. Akzeptierte Entscheidungen mit einer neuen `mappingVersion` in einem Branch in `nato-taxonomy.json` eintragen.
4. Die normalen Laufzeitvalidierungen des Overlays und die vollständige CI-Matrix ausführen.
5. Die Overlay-Änderung ausschließlich über den regulären geprüften Git-Prozess mergen.

Der Generator besitzt bewusst keinen Übernahmemodus. Ein Vorschlag kann damit nicht unbemerkt zum produktiven Katalogzustand werden.
