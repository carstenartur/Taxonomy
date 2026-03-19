# Dokumentenimport & Quellenherkunft

## Überblick

Der Taxonomy Analyzer unterstützt den Import von Anforderungen direkt aus PDF-
und DOCX-Dokumenten.  Dies ist besonders nützlich bei der Arbeit mit
Verwaltungsvorschriften, technischen Spezifikationen oder anderen Dokumenten, die
anforderungsähnliche Aussagen enthalten.

Jede Anforderung im System ist **bis zu ihrer Quelle rückverfolgbar**.  Ob eine
Anforderung manuell eingegeben, aus einem Regulierungsdokument importiert oder
aus einer Besprechungsnotiz abgeleitet wurde — die Herkunftsinformationen werden
erfasst und in der Benutzeroberfläche angezeigt.

## Quellenherkunftsmodell

Jede Anforderung ist mit einem **Quellartefakt** verknüpft — einer logischen
Identität des Materials, aus dem sie stammt.  Quellentypen umfassen:

| Quellentyp              | Beschreibung                                     |
|--------------------------|--------------------------------------------------|
| Geschäftsanforderung     | Eine frei formulierte Geschäftsanforderung       |
| Verwaltungsvorschrift    | Eine administrative oder rechtliche Vorschrift   |
| Hochgeladenes Dokument   | Eine direkt hochgeladene Datei (PDF, DOCX)       |
| Besprechungsnotiz        | Eine in einer Besprechung erfasste Anforderung   |
| Manuelle Eingabe         | Eine manuell im System erstellte Anforderung     |

Jedes Quellartefakt kann eine oder mehrere **Quellversionen** (konkrete
Momentaufnahmen) und **Quellfragmente** (rückverfolgbare Absätze oder Abschnitte
innerhalb einer Version) haben.

## Dokument hochladen

1. Öffnen Sie den **Analyse**-Tab
2. Klappen Sie das Panel **📄 Dokumentenimport** auf
3. Wählen Sie eine PDF- oder DOCX-Datei
4. Geben Sie optional einen Titel ein (z. B. „BayVwVfG §23")
5. Wählen Sie den Quellentyp (Verwaltungsvorschrift, Geschäftsanforderung,
   Dokument, Besprechungsnotiz)
6. Klicken Sie auf **📄 Hochladen & Extrahieren**

Das System wird:
- Das Dokument parsen und Text extrahieren
- Abschnittsüberschriften identifizieren, sofern möglich
- Den Inhalt in Anforderungskandidaten-Absätze aufteilen
- Das Dokument als Quellartefakt für die Herkunftsverfolgung registrieren

## Extrahierte Kandidaten überprüfen

Nach dem Upload erscheint das Panel **Extrahierte Anforderungskandidaten** mit:

- Quelldokument-Metadaten (Dateiname, Typ, Seitenzahl)
- Einer nummerierten Liste von Kandidaten-Absätzen mit Abschnittsüberschriften
- Kontrollkästchen zum Auswählen oder Abwählen einzelner Kandidaten

Sie können:
- **Alle auswählen** / **Alle abwählen** zur schnellen Anpassung der Auswahl
- Irrelevante Absätze (z. B. Inhaltsverzeichnis, Kopfzeilen) abwählen
- **🔍 Ausgewählte analysieren** klicken, um die ausgewählten Kandidaten in das
  Analysetextfeld zu übertragen

## Importierte Anforderungen analysieren

Wenn Sie **Ausgewählte analysieren** klicken, werden die ausgewählten Kandidaten
zu einem einzigen Anforderungstext zusammengefasst und im Standard-
Analysetextfeld platziert.  Sie können dann:

- Den Text bei Bedarf weiter bearbeiten
- **Mit KI analysieren** klicken, um den Standard-Analyse-Workflow auszuführen
- Alle vorhandenen Funktionen nutzen (bewerteter Baum, Architektur-Ansicht,
  Export usw.)

Die Quellenherkunftsinformationen bleiben erhalten und werden im Panel
**🔗 Quellenherkunft** unterhalb des Analysebereichs angezeigt.

## Herkunftsanzeige

Nach dem Import und der Analyse eines Dokuments zeigt das Panel
**Quellenherkunft**:

- **Quelle**: Der ursprüngliche Dokumentdateiname
- **Typ**: Der Quellentyp (Verwaltungsvorschrift, Geschäftsanforderung usw.)
- **Artefakt-ID**: Eine eindeutige Kennung für die Rückverfolgbarkeit
- **Kandidaten**: Anzahl der zur Analyse ausgewählten Kandidaten
- **Seiten**: Gesamtseitenzahl des Quelldokuments

## Unterstützte Formate

| Format | Erweiterung | Unterstützungsgrad |
|--------|-------------|---------------------|
| PDF    | `.pdf`      | Volltextextraktion, Überschriftenerkennung |
| DOCX   | `.docx`     | Volltextextraktion, Überschriftenerkennung |

## DSL-Darstellung

Wenn eine Analyse mit Quell-Provenienz in Git committet wird, werden die
Provenienz-Informationen als `source`-, `sourceVersion`- und
`requirementSourceLink`-Blöcke in der DSL dargestellt. Dies ermöglicht
vollständige Rückverfolgbarkeit im versionskontrollierten
Architektur-Repository.

Siehe den [Benutzerhandbuch](USER_GUIDE.md#quell-provenienz-in-der-dsl) für
Details zur DSL-Syntax.

## Einschränkungen

- Der regelbasierte Parser extrahiert **Anforderungskandidaten**, interpretiert
  aber keine rechtliche Bedeutung.  Benutzer müssen relevante Kandidaten
  überprüfen und auswählen.
- Nicht alle Absätze in einer Vorschrift sind Anforderungen — der Parser wirft
  absichtlich ein weites Netz und verlässt sich auf die Benutzerüberprüfung.
- Die Erkennung von Abschnittsüberschriften funktioniert am besten mit
  Standard-Überschriftenformaten (§, Art., nummerierte Abschnitte).
- Die seitengenaue Zuordnung ist für PDF-Dokumente verfügbar, kann aber nicht
  für alle Layouts präzise sein.
- KI-gestützte Extraktion und Regulation-Mapping erfordern einen konfigurierten
  LLM-Anbieter (z. B. Gemini API-Schlüssel).  Diese Modi sind nicht verfügbar,
  wenn kein LLM konfiguriert ist.
- Die KI-Extraktionsqualität hängt von Dokumentstruktur und Sprache ab.  Deutsche
  Verwaltungsvorschriften werden am besten durch den spezialisierten
  `extract-regulation`-Prompt unterstützt.
- Dies ist die erste Stufe der Verwaltungsintegration.  Zukünftige Versionen
  werden FIM-Katalogimport, XÖV-Schema-Mapping und 115-Wissensbasis-
  Verbindungen unterstützen.

## Import-Modi

Das Dokumentenimport-Panel bietet drei Modi:

| Modus | Symbol | Geeignet für | KI? |
|-------|--------|--------------|-----|
| **Kandidaten extrahieren** | 📝 | Schnelle Absatz-Extraktion | ❌ Regelbasiert |
| **KI-gestützte Extraktion** | 🤖 | Intelligente Anforderungserkennung | ✅ LLM |
| **Architektur-Mapping** | 🏛️ | Bekannte Vorschriften → Architektur | ✅ LLM |

### Kandidaten extrahieren (Standard)

Regelbasierte Absatz-Aufteilung.  Schnell, keine API-Kosten.
Am besten geeignet für die Erkundung unbekannter Dokumente.

### KI-gestützte Extraktion

Verwendet einen spezialisierten LLM-Prompt zur Identifizierung tatsächlicher
Anforderungen im Dokumenttext.  Die KI:

- Filtert Standardtexte, Überschriften und Nicht-Anforderungsinhalte heraus
- Klassifiziert jede Anforderung (FUNCTIONAL, ORGANIZATIONAL, TECHNICAL, LEGAL, PROCESS)
- Weist jeder Extraktion einen Konfidenzwert (0,0–1,0) zu
- Bewahrt Abschnitts-/Absatzreferenzen wo identifizierbar

Zwei Prompt-Varianten stehen zur Verfügung (konfigurierbar im Admin-Panel):

| Prompt | Code | Geeignet für |
|--------|------|-------------|
| Allgemeine Extraktion | `extract-default` | Jeder Dokumenttyp |
| Regulation-Extraktion | `extract-regulation` | Deutsche Verwaltungsvorschriften |

### Direktes Architektur-Mapping

Sendet die Vorschrift direkt an das LLM zusammen mit der vollständigen
Taxonomie-Knotenliste.  Gibt Architektur-Knoten-Treffer zurück mit:

- **Knotencode**: Das zugeordnete Taxonomie-Element
- **Verbindungstyp**: MANDATES, REQUIRES, ENABLES, CONSTRAINS oder REFERENCES
- **Konfidenz**: 0,0–1,0 Übereinstimmungskonfidenz
- **Absatzreferenz**: Quellposition in der Vorschrift
- **Begründung**: Kurze Begründung für die Zuordnung

## Prompt-Anpassung

Alle drei Prompt-Familien können im Admin-Panel unter **Prompt Templates**
angepasst werden.  Templates sind nach Kategorien gruppiert:

- **📊 Scoring** — Standard-Taxonomie-Scoring-Prompts (pro Wurzelcode)
- **📄 Extraction** — KI-gestützte Dokumentenextraktions-Prompts
- **🏛️ Regulation Mapping** — Regulation-zu-Architektur-Mapping-Prompts
- **📝 Justification** — Blattknoten-Begründungs-Prompts

## Best Practices

1. **Überprüfen Sie Kandidaten immer** vor der Analyse — nicht jeder Absatz ist
   eine Anforderung
2. **Verwenden Sie beschreibende Titel** beim Hochladen, um die
   Quellenverfolgung zu erleichtern
3. **Wählen Sie den richtigen Quellentyp** aus, um genaue Herkunftsdatensätze zu
   pflegen
4. **Kombinieren Sie verwandte Kandidaten** in einem einzigen Analyselauf für
   kohärente Architektur-Ansichten
