# Verwaltungsintegration — Status & Roadmap

Dieses Dokument beschreibt die Integration des Taxonomy Architecture Analyzer mit verwaltungsfachlichen Wissensbeständen und Schnittstellen der deutschen Bundesverwaltung. Es verbindet den aktuellen Implementierungsstand mit der strategischen Roadmap für verbleibende Arbeiten.

---

## Inhaltsverzeichnis

1. [Übersicht der Integrationspunkte](#übersicht-der-integrationspunkte)
2. [FIM-Leistungskatalog-Import](#fim-leistungskatalog-import)
3. [Verwaltungsvorschriften-Parser](#verwaltungsvorschriften-parser)
4. [115-Wissensbasis-Anbindung](#115-wissensbasis-anbindung)
5. [XÖV-Schema-Mapping](#xöv-schema-mapping)
6. [Phasenplanung](#phasenplanung)

---

## Übersicht der Integrationspunkte

| Integrationspunkt | Beschreibung | Status | Hinweise |
|---|---|---|---|
| **Verwaltungsvorschriften-Parser** | PDF/DOCX → Anforderungsextraktion aus Verwaltungsdokumenten | ✅ Implementiert | `DocumentParserService` mit Apache PDFBox + Apache POI; siehe [Dokumentimport](DOCUMENT_IMPORT.md) |
| **FIM-Leistungskatalog-Import** | Verwaltungsleistungen als Anforderungen für Architektur-Analyse importieren | ⚠️ Infrastruktur bereit | `SourceType.FIM_ENTRY` vorhanden; FIM-Import-Profil noch nicht gebaut |
| **115-Wissensbasis-Anbindung** | Verwaltungsfachliche Informationen als Kontext für RAG-basierte Analyse | ⚠️ RAG-Pipeline bereit | `LocalEmbeddingService` + `HybridSearchService` betriebsbereit; 115-Connector fehlt noch |
| **XÖV-Schema-Mapping** | XÖV-Nachrichten auf Taxonomie-Knoten abbilden | 🟢 Geplant | Noch nicht implementiert |

---

## FIM-Leistungskatalog-Import

### Ziel

Der [Föderale Informationsmanagement (FIM)](https://fimportal.de/)-Leistungskatalog enthält standardisierte Beschreibungen von Verwaltungsleistungen. Durch den Import dieser Leistungsbeschreibungen als Business-Anforderungen können Architektur-Analysen für verwaltungsfachliche Prozesse automatisiert angestoßen werden.

### Implementierungsstatus: ⚠️ Infrastruktur bereit

Das Datenmodell unterstützt FIM-Einträge über `SourceType.FIM_ENTRY`, und die Dokumentimport-Pipeline kann für FIM-Daten erweitert werden. Das FIM-spezifische Import-Profil und der Format-Adapter sind noch nicht implementiert.

| Was bereit ist | Was noch fehlt |
|---|---|
| `SourceType.FIM_ENTRY` im Quell-Provenienz-Modell | FIM-XML/JSON-Format-Parser |
| Dokumentimport-Infrastruktur (`DocumentImportController`) | FIM-Feld-Mapping-Konfiguration |
| Quell-Provenienz-Tracking | FIM-Portal-API-Integration |

### Konzept

| Aspekt | Detail |
|---|---|
| **Datenquelle** | FIM-Leistungskatalog (XML/JSON-Export) |
| **Import-Format** | Leistungsbezeichnung + Leistungsbeschreibung → Business Requirement |
| **Mapping** | FIM-Leistungsnummer → Analyse-Referenz |
| **Workflow** | Import → Automatische KI-Analyse → Architekt prüft Ergebnis |

### Voraussetzungen

- Zugang zum FIM-Leistungskatalog-Export
- API-Spezifikation des FIM-Portals (sofern verfügbar)
- Mapping-Definition: Welche FIM-Felder als Business Requirement genutzt werden

---

## Verwaltungsvorschriften-Parser

### Ziel

Verwaltungsvorschriften, Dienstanweisungen und fachliche Anforderungsdokumente liegen häufig als PDF oder DOCX vor. Ein Parser soll aus diesen Dokumenten automatisch Anforderungen extrahieren, die als Input für die Architektur-Analyse dienen.

### Implementierungsstatus: ✅ Implementiert

Der Verwaltungsvorschriften-Parser wurde mit **Apache PDFBox** (PDF) und **Apache POI** (DOCX) vollständig implementiert:

| Komponente | Implementierung |
|---|---|
| **DocumentParserService** | Orchestriert Dokumenten-Parsing und Textextraktion |
| **StructuredDocumentParser** | Extrahiert strukturierte Abschnitte und Überschriften |
| **DocumentAnalysisService** | KI-gestützte Anforderungsextraktion aus geparsten Texten |
| **DocumentImportController** | REST-Endpunkte für Upload, Extraktion und Mapping |
| **taxonomy-document-import.js** | Frontend-UI für den Dokumentimport-Workflow |

Drei Import-Modi stehen zur Verfügung:

1. **Kandidaten extrahieren** — Regelbasierte Extraktion von Anforderungskandidaten aus der Dokumentstruktur
2. **KI-gestützte Extraktion** — LLM-basierte Zusammenfassung und Anforderungsextraktion
3. **Direkte Architektur-Zuordnung** — Vorschriften-zu-Architektur-Mapping per KI-Analyse

> **Details:** Siehe [Dokumentimport](DOCUMENT_IMPORT.md) für die vollständige Feature-Dokumentation.

### Konzept

| Aspekt | Detail |
|---|---|
| **Eingabeformate** | PDF, DOCX (weitere Formate erweiterbar) |
| **Verarbeitung** | Textextraktion → Abschnittsstrukturierung → Anforderungsidentifikation |
| **KI-Unterstützung** | Optional: LLM-basierte Zusammenfassung und Anforderungsextraktion |
| **Ausgabe** | Strukturierte Business Requirements für die Taxonomy-Analyse |
| **Technologie** | Apache PDFBox (PDF), Apache POI (DOCX) |

### Synergien

Dieses Feature ergänzt die in [USE_CASE_WISSENSKONSERVIERUNG.md](USE_CASE_WISSENSKONSERVIERUNG.md) beschriebene Wissenskonservierung aus Dokumenten.

---

## 115-Wissensbasis-Anbindung

### Ziel

Die [115-Wissensbasis](https://www.115.de/) enthält verwaltungsfachliche Informationen zu Behördenleistungen und -prozessen. Durch die Anbindung als Kontextquelle kann die KI-Analyse mit verwaltungsspezifischem Hintergrundwissen angereichert werden (Retrieval Augmented Generation / RAG).

### Implementierungsstatus: ⚠️ RAG-Pipeline bereit

Die RAG-Infrastruktur ist vollständig betriebsbereit. Nur der 115-spezifische Daten-Connector fehlt noch:

| Was bereit ist | Was noch fehlt |
|---|---|
| `LocalEmbeddingService` (BAAI/bge-small-en-v1.5 ONNX) | 115-Wissensbasis-Export/API-Connector |
| `HybridSearchService` (Reciprocal Rank Fusion) | 115-spezifischer Datenformat-Adapter |
| Volltext- + semantische + hybride Suchmodi | Datensynchronisierungszeitplan |

### Konzept

| Aspekt | Detail |
|---|---|
| **Nutzung** | Kontextanreicherung für LLM-Prompts (RAG) |
| **Datenquelle** | 115-Wissensbasis-Export oder API |
| **Vorteil** | Verwaltungsfachliche Präzision der KI-Ergebnisse verbessern |
| **Datenschutz** | Nur öffentlich zugängliche Informationen; keine personenbezogenen Daten |

---

## XÖV-Schema-Mapping

### Ziel

[XÖV-Standards](https://www.xoev.de/) definieren standardisierte Nachrichtenformate für den Datenaustausch in der deutschen Verwaltung (z. B. XBau, XBezahlen, XPersonenstandsrecht). Durch ein Mapping von XÖV-Schemata auf Taxonomie-Knoten können Architektur-Analysen die verwendeten Schnittstellenstandards berücksichtigen.

### Konzept

| Aspekt | Detail |
|---|---|
| **Datenquelle** | XÖV-Schema-Repository (XSD-Dateien) |
| **Mapping** | XÖV-Nachrichtentypen → Taxonomie-Knoten (z. B. Integration-Pattern) |
| **Nutzen** | Automatische Identifikation relevanter Integrationsstandards für eine Architektur |
| **Export** | XÖV-Referenzen in ArchiMate-Export integrierbar |

---

## Phasenplanung

### Phase 1: Abgeschlossen ✅

| Schritt | Beschreibung | Status |
|---|---|---|
| Parser-Implementierung | PDF/DOCX-Textextraktion mit Apache PDFBox + Apache POI | ✅ Erledigt |
| Import-API | REST-Endpunkte für Dokumenten-Upload, Extraktion und Mapping | ✅ Erledigt |
| Dokumentimport-UI | Frontend für den Dokumentimport-Workflow | ✅ Erledigt |
| Quell-Provenienz | Nachverfolgung von Anforderungsursprüngen mit SourceType-Enum | ✅ Erledigt |
| FIM-Datenmodell | `SourceType.FIM_ENTRY` im Provenienz-Modell | ✅ Erledigt |

### Phase 2: Verbleibende Arbeiten

| Schritt | Beschreibung | Aufwand |
|---|---|---|
| FIM-Import-Profil | FIM-XML/JSON-Format-Parser und Feld-Mapping | 2 Wochen |
| 115-Connector | Connector für die 115-Wissensbasis als RAG-Kontextquelle | 2 Wochen |
| XÖV-Mapping | Prototypisches Mapping von XÖV-Schemata auf Taxonomie | 3 Wochen |
| Feedback | Pilotbetrieb mit Partnerbehörde; Feedback-Integration | 4 Wochen |

---

## Verwandte Dokumentation

- [Dokumentimport](DOCUMENT_IMPORT.md) — Dokumentimport-Feature-Dokumentation (PDF/DOCX-Upload, Provenienz)
- [Use Case: Wissenskonservierung](USE_CASE_WISSENSKONSERVIERUNG.md) — Behörden-Use-Case für Wissenskonservierung
- [AI Transparency](AI_TRANSPARENCY.md) — KI-Transparenz und Datenflüsse
- [Digital Sovereignty](DIGITAL_SOVEREIGNTY.md) — Digitale Souveränität und openCode
- [API Reference](API_REFERENCE.md) — REST API-Dokumentation
