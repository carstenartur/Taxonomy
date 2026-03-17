# AI-Literacy-Schulungskonzept

Dieses Dokument beschreibt das Schulungskonzept für Anwender des Taxonomy Architecture Analyzer gemäß der **AI-Literacy-Pflicht nach EU AI Act Art. 4** (in Kraft seit 02.02.2025). Betreiber und Nutzer von KI-Systemen müssen sicherstellen, dass das Personal über ausreichende KI-Kompetenz verfügt.

---

## Inhaltsverzeichnis

1. [Rechtlicher Hintergrund](#rechtlicher-hintergrund)
2. [Zielgruppen und Kompetenzstufen](#zielgruppen-und-kompetenzstufen)
3. [Schulungsinhalte nach Rolle](#schulungsinhalte-nach-rolle)
4. [Was die KI im Taxonomy Analyzer macht](#was-die-ki-im-taxonomy-analyzer-macht)
5. [Was die KI nicht macht](#was-die-ki-nicht-macht)
6. [Erklärbarkeit der KI-Ergebnisse](#erklärbarkeit-der-ki-ergebnisse)
7. [Empfehlungen für Einführungsworkshops](#empfehlungen-für-einführungsworkshops)
8. [Lernressourcen](#lernressourcen)
9. [Dokumentationspflichten](#dokumentationspflichten)

---

## Rechtlicher Hintergrund

| Aspekt | Detail |
|---|---|
| **Rechtsgrundlage** | EU AI Act (Verordnung (EU) 2024/1689), Artikel 4 — AI Literacy |
| **In Kraft seit** | 02. Februar 2025 |
| **Verpflichtung** | Anbieter und Betreiber müssen sicherstellen, dass ihr Personal über ein ausreichendes Maß an KI-Kompetenz verfügt |
| **Anwendung auf Taxonomy** | Als Betreiber des Taxonomy Architecture Analyzer (Risikoklasse: Minimal Risk) muss die einsetzende Behörde AI-Literacy-Maßnahmen umsetzen |

> **Art. 4 EU AI Act:** „Anbieter und Betreiber von KI-Systemen ergreifen Maßnahmen, um nach besten Kräften sicherzustellen, dass ihr Personal und andere Personen, die in ihrem Auftrag mit dem Betrieb und der Nutzung von KI-Systemen befasst sind, über ein ausreichendes Maß an KI-Kompetenz verfügen, wobei ihre technischen Kenntnisse, ihre Erfahrung, ihre Ausbildung und ihr Schulungsstand sowie der Kontext, in dem die KI-Systeme eingesetzt werden sollen, und die Personen oder Personengruppen, bei denen die KI-Systeme eingesetzt werden sollen, zu berücksichtigen sind."

---

## Zielgruppen und Kompetenzstufen

| Zielgruppe | Rolle im System | Kompetenzstufe | Schulungsumfang |
|---|---|---|---|
| **IT-Architekten** (Endanwender) | USER / ARCHITECT | Grundkompetenz | 2–4 Stunden |
| **Teamleiter / Projektmanager** | USER (lesend) | Orientierungswissen | 1–2 Stunden |
| **Administratoren** | ADMIN | Erweiterte Kompetenz | 4–8 Stunden |
| **IT-Sicherheitsbeauftragte** | Beratend | Spezialkompetenz KI-Sicherheit | 4 Stunden |
| **Datenschutzbeauftragte** | Beratend | Spezialkompetenz KI-Datenschutz | 2 Stunden |

---

## Schulungsinhalte nach Rolle

### Für alle Anwender (Grundkompetenz)

| # | Thema | Lernziel |
|---|---|---|
| 1 | **Was ist ein LLM?** | Verstehen, dass Large Language Models textbasierte Wahrscheinlichkeitsmodelle sind, keine wissenden Systeme |
| 2 | **KI-Scores richtig interpretieren** | Wissen, dass Scores (0–100) Relevanzeinschätzungen sind, keine Fakten; verschiedene Provider können unterschiedliche Ergebnisse liefern |
| 3 | **Justification-Texte bewerten** | Fähigkeit, KI-generierte Begründungen kritisch zu prüfen und mit eigenem Fachwissen abzugleichen |
| 4 | **Limitationen erkennen** | Kenntnis von Halluzinationen, Bias, Inkonsistenzen als inhärente LLM-Eigenschaften |
| 5 | **Human-in-the-Loop-Prinzip** | Verstehen der eigenen Verantwortung: KI schlägt vor, Mensch entscheidet |

### Für Architekten (Erweiterte Kompetenz)

| # | Thema | Lernziel |
|---|---|---|
| 6 | **Anforderungsformulierung für KI** | Fähigkeit, Business Requirements so zu formulieren, dass die KI relevante Ergebnisse liefert |
| 7 | **Provider-Vergleich** | Wissen, wann und wie verschiedene LLM-Provider verglichen werden sollten |
| 8 | **Accept/Reject-Workflow** | Strukturierte Bewertung von KI-Vorschlägen für Architektur-Relationen |
| 9 | **Keine personenbezogenen Daten in Prompts** | Verantwortung, keine PII in Business Requirement-Texte aufzunehmen |

### Für Administratoren (Spezialkompetenz)

| # | Thema | Lernziel |
|---|---|---|
| 10 | **Provider-Konfiguration** | Sichere Einrichtung von LLM-Providern; Air-Gapped-Betrieb mit LOCAL_ONNX |
| 11 | **Prompt-Template-Management** | Verantwortung für Prompt-Templates; Risiken von Prompt-Injection verstehen |
| 12 | **LLM Communication Log** | Überwachung der KI-Kommunikation; Erkennen von anomalem Verhalten |
| 13 | **Rate-Limiting und Kosten** | Konfiguration von Rate-Limits; Kostencontrolling bei Cloud-Providern |
| 14 | **AI-Diagnostics-Endpunkt** | Nutzung von `GET /api/ai-diagnostics` zur Systemüberwachung |

---

## Was die KI im Taxonomy Analyzer macht

| Funktion | Beschreibung | Eingriff in Entscheidungen? |
|---|---|---|
| **Taxonomie-Scoring** | Bewertet die Relevanz von ~2.500 Taxonomie-Knoten für eine Business-Anforderung | ❌ Nein — nur Vorschlag |
| **Justification-Generierung** | Erstellt textuelle Begründungen für die Score-Vergabe | ❌ Nein — nur Erklärung |
| **Architektur-Vorschläge** | Schlägt Relationen zwischen Architektur-Elementen vor | ❌ Nein — erfordert Accept/Reject |
| **Semantische Suche** | Findet Taxonomie-Knoten basierend auf Bedeutungsähnlichkeit (lokales Embedding-Modell) | ❌ Nein — nur Suchergebnisse |

**Kernaussage:** Die KI im Taxonomy Analyzer ist ein **Empfehlungssystem** (Minimal Risk gemäß EU AI Act). Sie trifft keine Entscheidungen und ändert keine Daten eigenständig.

---

## Was die KI nicht macht

| Aktion | Status |
|---|---|
| Eigenständige Architekturentscheidungen treffen | ❌ Nicht implementiert |
| Daten ohne Benutzerinteraktion ändern | ❌ Nicht implementiert |
| Personenbezogene Daten verarbeiten | ❌ Nicht vorgesehen |
| Eigenes Modelltraining durchführen | ❌ Nicht implementiert |
| Auf externe Systeme zugreifen (außer konfiguriertem LLM-Provider) | ❌ Nicht implementiert |
| Soziales Scoring oder Emotionserkennung | ❌ Nicht implementiert (Art. 5 EU AI Act — verbotene Praktiken) |

---

## Erklärbarkeit der KI-Ergebnisse

Der Taxonomy Analyzer bietet mehrere Mechanismen zur Nachvollziehbarkeit:

### 1. Score und Justification

Jeder bewertete Taxonomie-Knoten zeigt:
- **Score** (0–100): Numerische Relevanzeinschätzung
- **Justification**: Textuelle Begründung des LLM, warum dieser Score vergeben wurde

### 2. LLM Communication Log (Admin)

Administratoren können im Admin-Panel einsehen:
- Vollständiger Prompt-Text, der an das LLM gesendet wurde
- Vollständige Antwort des LLM
- Zeitstempel, Dauer, Token-Counts
- Verwendeter Provider und Modell

### 3. Versionskontrolle

Alle Architekturentscheidungen werden versioniert:
- JGit-basiertes Repository mit Commit-Historie
- Autor-Attribution für jeden Commit
- Diff-Ansicht zwischen Versionen

**Referenz:** Siehe [AI Transparency — Scoring and Explainability](AI_TRANSPARENCY.md#scoring-and-explainability)

---

## Empfehlungen für Einführungsworkshops

### Workshop 1: Grundlagen-Workshop (alle Anwender, 2 Stunden)

| Block | Dauer | Inhalt |
|---|---|---|
| **Einführung** | 20 min | Was ist KI/LLM? Wie funktioniert der Taxonomy Analyzer? |
| **Live-Demo** | 30 min | Anforderung eingeben → Scoring → Ergebnisse interpretieren |
| **Hands-On** | 40 min | Teilnehmer führen eigene Analyse durch; diskutieren Ergebnisse |
| **Kritisches Denken** | 20 min | Beispiele für falsche/ungenaue KI-Ergebnisse; wie erkennt man sie? |
| **Q&A** | 10 min | Offene Fragen |

### Workshop 2: Architekten-Workshop (ARCHITECT-Rolle, 2 Stunden)

| Block | Dauer | Inhalt |
|---|---|---|
| **Anforderungs-Design** | 30 min | Wie formuliere ich Anforderungen für optimale KI-Ergebnisse? |
| **Provider-Vergleich** | 30 min | Dieselbe Anforderung mit verschiedenen Providern analysieren |
| **Accept/Reject-Workflow** | 30 min | Architektur-Vorschläge systematisch bewerten |
| **DSL und Versionierung** | 20 min | Änderungen committen, Diff-Ansicht nutzen |
| **Best Practices** | 10 min | Zusammenfassung der Empfehlungen |

### Workshop 3: Admin-Workshop (ADMIN-Rolle, 4 Stunden)

| Block | Dauer | Inhalt |
|---|---|---|
| **Provider-Konfiguration** | 60 min | LLM-Provider einrichten; Air-Gapped-Betrieb; Rate-Limits |
| **Prompt-Template-Management** | 30 min | Templates anpassen; Prompt-Injection-Risiken |
| **Monitoring und Diagnostics** | 30 min | LLM Communication Log; AI-Diagnostics-Endpunkt |
| **Sicherheit und Datenschutz** | 60 min | Audit-Logging; BSI-Checkliste; DSGVO-Compliance |
| **Troubleshooting** | 30 min | Häufige Fehler und deren Behebung |
| **Q&A** | 30 min | Offene Fragen und Erfahrungsaustausch |

---

## Lernressourcen

### Interne Dokumentation

| Dokument | Relevanz |
|---|---|
| [AI Transparency](AI_TRANSPARENCY.md) | KI-Komponenten, Datenflüsse, Limitationen |
| [AI Providers](AI_PROVIDERS.md) | Provider-Details und Konfiguration |
| [User Guide](USER_GUIDE.md) | Schritt-für-Schritt-Anleitung |
| [BSI KI Checklist](BSI_KI_CHECKLIST.md) | BSI-Kriterien für KI-Einsatz |
| [Data Protection](DATA_PROTECTION.md) | DSGVO-Compliance |

### Externe Ressourcen

| Ressource | Beschreibung |
|---|---|
| [EU AI Act (Volltext)](https://eur-lex.europa.eu/eli/reg/2024/1689/oj) | Verordnung (EU) 2024/1689 |
| [BSI — Künstliche Intelligenz](https://www.bsi.bund.de/DE/Themen/Unternehmen-und-Organisationen/Informationen-und-Empfehlungen/Kuenstliche-Intelligenz/kuenstliche-intelligenz_node.html) | BSI-Empfehlungen für KI-Sicherheit |
| [BfDI — KI und Datenschutz](https://www.bfdi.bund.de/) | Datenschutzrechtliche Bewertung von KI-Systemen |

---

## Dokumentationspflichten

Die einsetzende Behörde sollte folgende Nachweise führen:

| Nachweis | Inhalt | Aufbewahrung |
|---|---|---|
| **Schulungsteilnahme** | Liste der geschulten Personen mit Datum und Workshop-Typ | Mindestens 3 Jahre |
| **Kompetenzbestätigung** | Teilnehmer bestätigen Verständnis der KI-Limitationen und ihrer Verantwortung | Mindestens 3 Jahre |
| **Wiederholungsschulung** | Jährliche Auffrischung bei wesentlichen Änderungen am System | Fortlaufend |
| **Neue Mitarbeiter** | Schulung vor erstem Einsatz des Systems | Vor Nutzungsbeginn |

---

## Verwandte Dokumentation

- [AI Transparency](AI_TRANSPARENCY.md) — KI-Transparenz und Datenflüsse
- [BSI KI Checklist](BSI_KI_CHECKLIST.md) — BSI-Kriterienkatalog-Checkliste
- [Data Protection](DATA_PROTECTION.md) — Datenschutz und DSGVO
- [Security](SECURITY.md) — Sicherheitsarchitektur
- [User Guide](USER_GUIDE.md) — Benutzerhandbuch
