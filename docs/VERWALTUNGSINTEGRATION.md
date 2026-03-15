# Verwaltungsintegration — Roadmap

Dieses Dokument beschreibt die geplante Integration des Taxonomy Architecture Analyzer mit verwaltungsfachlichen Wissensbeständen und Schnittstellen der deutschen Bundesverwaltung. Es handelt sich um ein strategisches Roadmap-Dokument.

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

| Integrationspunkt | Beschreibung | Phase | Priorität |
|---|---|---|---|
| **FIM-Leistungskatalog-Import** | Verwaltungsleistungen als Anforderungen für Architektur-Analyse importieren | Phase 1 | 🟡 Mittel |
| **Verwaltungsvorschriften-Parser** | PDF/DOCX → Anforderungsextraktion aus Verwaltungsdokumenten | Phase 1 | 🟡 Mittel |
| **115-Wissensbasis-Anbindung** | Verwaltungsfachliche Informationen als Kontext für RAG-basierte Analyse | Phase 2 | 🟢 Niedrig |
| **XÖV-Schema-Mapping** | XÖV-Nachrichten auf Taxonomie-Knoten abbilden | Phase 2 | 🟢 Niedrig |

---

## FIM-Leistungskatalog-Import

### Ziel

Der [Föderale Informationsmanagement (FIM)](https://fimportal.de/)-Leistungskatalog enthält standardisierte Beschreibungen von Verwaltungsleistungen. Durch den Import dieser Leistungsbeschreibungen als Business-Anforderungen können Architektur-Analysen für verwaltungsfachliche Prozesse automatisiert angestoßen werden.

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

### Konzept

| Aspekt | Detail |
|---|---|
| **Eingabeformate** | PDF, DOCX (weitere Formate erweiterbar) |
| **Verarbeitung** | Textextraktion → Abschnittsstrukturierung → Anforderungsidentifikation |
| **KI-Unterstützung** | Optional: LLM-basierte Zusammenfassung und Anforderungsextraktion |
| **Ausgabe** | Strukturierte Business Requirements für die Taxonomy-Analyse |

### Synergien

Dieses Feature ergänzt die in [USE_CASE_WISSENSKONSERVIERUNG.md](USE_CASE_WISSENSKONSERVIERUNG.md) beschriebene Roadmap für die Wissenskonservierung aus Dokumenten.

---

## 115-Wissensbasis-Anbindung

### Ziel

Die [115-Wissensbasis](https://www.115.de/) enthält verwaltungsfachliche Informationen zu Behördenleistungen und -prozessen. Durch die Anbindung als Kontextquelle kann die KI-Analyse mit verwaltungsspezifischem Hintergrundwissen angereichert werden (Retrieval Augmented Generation / RAG).

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

### Phase 1: Konzept und Prototyp (3–6 Monate)

| Schritt | Beschreibung | Aufwand |
|---|---|---|
| FIM-Katalog-Analyse | Format und Verfügbarkeit des FIM-Exports klären | 1 Woche |
| Parser-Prototyp | PDF/DOCX-Textextraktion mit Apache Tika evaluieren | 2 Wochen |
| Import-API | REST-Endpunkt für Anforderungsimport aus strukturierten Quellen | 2 Wochen |
| Pilot-Test | Testlauf mit 10–20 FIM-Leistungen | 1 Woche |

### Phase 2: Erweiterte Integration (6–12 Monate)

| Schritt | Beschreibung | Aufwand |
|---|---|---|
| 115-Anbindung | Evaluierung der 115-Wissensbasis als RAG-Kontextquelle | 2 Wochen |
| XÖV-Mapping | Prototypisches Mapping von XÖV-Schemata auf Taxonomie | 3 Wochen |
| Feedback | Pilotbetrieb mit Partnerbehörde; Feedback-Integration | 4 Wochen |

---

## Verwandte Dokumentation

- [Use Case: Wissenskonservierung](USE_CASE_WISSENSKONSERVIERUNG.md) — Behörden-Use-Case für Wissenskonservierung
- [AI Transparency](AI_TRANSPARENCY.md) — KI-Transparenz und Datenflüsse
- [Digital Sovereignty](DIGITAL_SOVEREIGNTY.md) — Digitale Souveränität und openCode
- [API Reference](API_REFERENCE.md) — REST API-Dokumentation
