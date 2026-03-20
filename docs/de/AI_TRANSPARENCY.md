# KI-Transparenz

Dieses Dokument stellt Transparenz über die im Taxonomy Architecture Analyzer verwendeten KI-/LLM-Komponenten her, in Übereinstimmung mit der KI-Verordnung (KI-VO, Verordnung (EU) 2024/1689) und den geltenden Transparenzanforderungen für KI-Systeme im behördlichen und unternehmerischen Einsatz.

---

## Inhaltsverzeichnis

1. [Klassifizierung des KI-Systems](#klassifizierung-des-ki-systems)
2. [Übersicht der KI-Komponenten](#übersicht-der-ki-komponenten)
3. [Unterstützte LLM-Anbieter](#unterstützte-llm-anbieter)
4. [Datenflüsse](#datenflüsse)
5. [Bewertung und Erklärbarkeit](#bewertung-und-erklärbarkeit)
6. [Lokales Embedding-Modell](#lokales-embedding-modell)
7. [Menschliche Aufsicht](#menschliche-aufsicht)
8. [Einschränkungen und Risiken](#einschränkungen-und-risiken)
9. [Diagnose und Überwachung](#diagnose-und-überwachung)
10. [Konfiguration für den behördlichen Einsatz](#konfiguration-für-den-behördlichen-einsatz)
11. [Zeitplan zur Einhaltung der KI-Verordnung](#zeitplan-zur-einhaltung-der-ki-verordnung)
12. [BSI-Konformität](#bsi-konformität)

---

## Klassifizierung des KI-Systems

Gemäß der KI-Verordnung (Verordnung (EU) 2024/1689) wird der Taxonomy Architecture Analyzer wie folgt klassifiziert:

| Aspekt | Bewertung |
|---|---|
| **Risikokategorie** | **Minimales Risiko** — Das System unterstützt bei der Architekturanalyse, einer technischen Beratungsaufgabe. Es trifft keine autonomen Entscheidungen, die Einzelpersonen betreffen. |
| **Zweck** | Entscheidungsunterstützungswerkzeug für IT-Architekten. Alle KI-Ausgaben sind beratend und erfordern eine menschliche Überprüfung. |
| **Autonomiegrad** | **Keiner** — Das System präsentiert bewertete Vorschläge. Menschliche Architekten akzeptieren, lehnen ab oder modifizieren alle Ausgaben. |
| **Verarbeitung personenbezogener Daten** | Minimal — nur Benutzerkontodaten. Texte zu Geschäftsanforderungen sollten keine personenbezogenen Daten enthalten. |

---

## Übersicht der KI-Komponenten

Die Anwendung nutzt KI in zwei unterschiedlichen Bereichen:

### 1. LLM-basierte Analyse (Cloud oder lokal)

| Aspekt | Detail |
|---|---|
| **Zweck** | Bewertung von Taxonomie-Knoten (0–100) anhand einer Geschäftsanforderung |
| **Eingabe** | Text der Geschäftsanforderung + Beschreibungen der Taxonomie-Knoten |
| **Ausgabe** | Relevanzwerte, Begründungstexte, Architekturempfehlungen |
| **Modelle** | Siehe [Unterstützte LLM-Anbieter](#unterstützte-llm-anbieter) |
| **Menschliche Überprüfung** | Erforderlich — Bewertungen werden zur Überprüfung angezeigt, nicht automatisch angewendet |

### 2. Lokales Embedding-Modell (On-Premises)

| Aspekt | Detail |
|---|---|
| **Zweck** | Semantische Suche über Taxonomie- und Architekturelemente |
| **Eingabe** | Suchanfragen und Beschreibungen der Taxonomie-Knoten |
| **Ausgabe** | Vektoreinbettungen für die Ähnlichkeitsrangfolge |
| **Modell** | BAAI/bge-small-en-v1.5 (384 Dimensionen, ONNX Runtime) |
| **Datenspeicherort** | Vollständig lokal — keine externen API-Aufrufe |

### 3. KI im Dokumentenimport

Die Dokumentenimport-Funktion bietet neben der regelbasierten Extraktion zwei
KI-gestützte Modi:

| Modus | Prompt-Code | Zweck | An LLM gesendete Daten |
|-------|-------------|-------|------------------------|
| **KI-gestützte Extraktion** | `extract-default` / `extract-regulation` | Umsetzbare Anforderungen aus Dokumenttext extrahieren | Dokumenttext (auf 15.000 Zeichen gekürzt) |
| **Direktes Architektur-Mapping** | `reg-map-default` | Regulierungstext auf Taxonomie-Knoten abbilden | Dokumenttext + vollständige Taxonomie-Knotenliste |

**Hinweise zur Datensensitivität:**

- Dokumenttext wird an den konfigurierten LLM-Anbieter gesendet (Cloud oder lokal)
- Die vollständige Taxonomie-Knotenliste (Code + Name, ca. 2.500 Knoten) ist in
  Regulation-Mapping-Prompts enthalten
- Es sollten keine personenbezogenen Daten in hochgeladenen Dokumenten enthalten sein
- Alle KI-Ausgaben werden zur menschlichen Überprüfung präsentiert
- Prompts sind über das Admin-Panel anpassbar (siehe [Dokumentenimport](DOCUMENT_IMPORT.md))

---

## Unterstützte LLM-Anbieter

| Anbieter | Modell | API-Endpunkt | Datenspeicherort | Kostenloses Kontingent |
|---|---|---|---|---|
| **Google Gemini** | gemini-2.0-flash | `generativelanguage.googleapis.com` | Google Cloud (US/EU) | 15 RPM, 1500 RPD |
| **OpenAI** | gpt-4o-mini | `api.openai.com` | OpenAI Cloud (US) | Nur kostenpflichtig |
| **DeepSeek** | deepseek-chat | `api.deepseek.com` | DeepSeek Cloud (China) | Begrenzt kostenlos |
| **Qwen** (Alibaba) | qwen-turbo | `dashscope.aliyuncs.com` | Alibaba Cloud (China) | Begrenzt kostenlos |
| **Llama** (Meta via API) | llama-3.3-70b | `api.llama-api.com` | Llama API Cloud | Begrenzt kostenlos |
| **Mistral** | mistral-small-latest | `api.mistral.ai` | Mistral Cloud (EU) | Begrenzt kostenlos |
| **LOCAL_ONNX** | Integriertes Bewertungsmodell | Keiner (lokal) | **Nur On-Premises** | Unbegrenzt |

### Anbieterauswahl

Der Anbieter wird in folgender Priorität ausgewählt:

1. **Anfragespezifische Überschreibung** — API-Clients können einen Anbieter im Anfragekörper angeben
2. **Explizite Konfiguration** — Umgebungsvariable `LLM_PROVIDER`
3. **Automatische Erkennung** — Erster verfügbarer Anbieter durch Schlüsselerkennung (Gemini → OpenAI → DeepSeek → Qwen → Llama → Mistral)
4. **Fallback** — Wenn kein Anbieter verfügbar ist, gibt die Analyse einen Fehler zurück; Navigation und Suche bleiben funktionsfähig

---

## Datenflüsse

### Cloud-LLM-Analyse (Gemini, OpenAI usw.)

```
┌─────────────┐     Business requirement text     ┌──────────────┐
│  User        │ ──────────────────────────────────▶│  Application  │
│  (Browser)   │                                    │  Server       │
└─────────────┘                                    └──────┬───────┘
                                                          │
                           Prompt (requirement + taxonomy  │
                           node names/descriptions)        │
                                                          ▼
                                                   ┌──────────────┐
                                                   │  LLM Provider │
                                                   │  (Cloud API)  │
                                                   └──────┬───────┘
                                                          │
                           Scored response (JSON with      │
                           scores and justifications)      │
                                                          ▼
┌─────────────┐     Scored taxonomy tree            ┌──────────────┐
│  User        │ ◀──────────────────────────────────│  Application  │
│  (Browser)   │                                    │  Server       │
└─────────────┘                                    └──────────────┘
```

**An den LLM-Anbieter gesendete Daten:**

- Text der Geschäftsanforderung (vom Benutzer bereitgestellt)
- Namen und Beschreibungen der Taxonomie-Knoten (aus dem C3-Katalog)
- Prompt-Vorlage (Systemanweisungen)

**NICHT gesendete Daten:**

- Benutzeranmeldedaten oder Kontoinformationen
- IP-Adressen oder Sitzungsdaten
- Frühere Analyseergebnisse (sofern nicht ausdrücklich im Prompt enthalten)

### Lokale Analyse (LOCAL_ONNX)

```
┌─────────────┐     Business requirement text     ┌──────────────┐
│  User        │ ──────────────────────────────────▶│  Application  │
│  (Browser)   │                                    │  Server       │
└─────────────┘                                    │  (local ONNX  │
                                                   │   inference)   │
                                                   └──────┬───────┘
                                                          │
┌─────────────┐     Scored taxonomy tree            │
│  User        │ ◀──────────────────────────────────┘
│  (Browser)   │
└─────────────┘
```

**Keine externe Datenübertragung.** Die gesamte Verarbeitung findet innerhalb der Anwendungs-JVM statt.

---

## Bewertung und Erklärbarkeit

### Wie Bewertungen generiert werden

1. Der Text der Geschäftsanforderung wird zusammen mit den Beschreibungen der Taxonomie-Knoten an den konfigurierten LLM-Anbieter gesendet
2. Der LLM gibt für jeden Knoten einen Relevanzwert (0–100) zurück
3. Bewertungen werden durch den Taxonomiebaum propagiert (Eltern-/Kind-Beziehungen)
4. Knoten mit Bewertungen ≥ 70 (konfigurierbar über `TAXONOMY_ANALYSIS_MIN_SCORE`) werden in Architekturansichten aufgenommen

### Erklärungsnachweise

Jedes Analyseergebnis enthält:

- **Bewertung** (0–100) — numerische Relevanzbewertung
- **Begründung** — Texterklärung des LLM, warum ein Knoten seine Bewertung erhalten hat
- **Konfidenzindikatoren** — basierend auf der selbstberichteten Sicherheit des Modells

### Einschränkungen der Erklärbarkeit

- LLM-Begründungen werden vom Modell generiert und spiegeln möglicherweise nicht exakt die interne Bewertungslogik wider
- Verschiedene Anbieter können dieselbe Anforderung unterschiedlich bewerten
- Bewertungen sollten als **beratende Vorschläge** interpretiert werden, nicht als definitive Einschätzungen
- Der `LOCAL_ONNX`-Anbieter verwendet einen einfacheren Bewertungsalgorithmus mit weniger differenzierten Erklärungen

---

## Lokales Embedding-Modell

| Aspekt | Detail |
|---|---|
| **Modell** | BAAI/bge-small-en-v1.5 |
| **Architektur** | Transformer (BERT-basiert), 33M Parameter |
| **Dimensionen** | 384 |
| **Laufzeitumgebung** | ONNX Runtime via DJL (Deep Java Library) |
| **Download** | Wird bei Erstverwendung automatisch von HuggingFace Hub heruntergeladen |
| **Größe** | ~50 MB (Modelldateien) |
| **Vorab-Download** | Setzen Sie `TAXONOMY_EMBEDDING_MODEL_DIR` für Air-Gapped-Umgebungen |

### Was das Embedding-Modell leistet

- Konvertiert Text (Taxonomiebeschreibungen, Suchanfragen) in 384-dimensionale Vektoren
- Ermöglicht semantische Ähnlichkeitssuche (findet relevante Knoten nach Bedeutung, nicht nur nach Schlüsselwörtern)
- Verwendet asymmetrisches Retrieval: Abfragetexte werden mit `"Represent this sentence for searching relevant passages: "` vorangestellt, um die Genauigkeit zu verbessern

### Was das Embedding-Modell NICHT leistet

- Sendet keine Daten an externe Server
- Generiert keinen Text und trifft keine Entscheidungen
- Verarbeitet keine personenbezogenen Daten (nur Taxonomiebeschreibungen)

---

## Menschliche Aufsicht

Die Anwendung ist als **Human-in-the-Loop**-System konzipiert:

| Phase | Rolle des Menschen |
|---|---|
| **Eingabe** | Der Architekt verfasst die Geschäftsanforderung — kontrolliert, was analysiert wird |
| **Analyseüberprüfung** | Der Architekt überprüft den bewerteten Taxonomiebaum — kann Bewertungen akzeptieren oder ablehnen |
| **Beziehungsvorschläge** | Die KI schlägt Architekturbeziehungen vor — der Architekt muss diese explizit akzeptieren |
| **Export** | Der Architekt löst den Export aus — überprüft die Ausgabe vor der Weitergabe |
| **Versionskontrolle** | Der Architekt committet DSL-Änderungen — vollständige Versionshistorie mit Zuordnung |

**Keine autonomen Aktionen:** Die KI modifiziert niemals Daten, committet keine Änderungen und erzeugt keine Ausgaben ohne explizite Benutzeraktion.

---

## Einschränkungen und Risiken

| Risiko | Gegenmaßnahme |
|---|---|
| **Halluzination** | Bewertungen werden stets zusammen mit dem vollständigen Taxonomiekontext angezeigt; Architekten können anhand des Katalogs verifizieren |
| **Bias** | Mehrere Anbieter stehen zur Kreuzvalidierung zur Verfügung; lokales Modell als Referenzwert verfügbar |
| **Datenleck** | Air-Gapped-Betrieb mit `LOCAL_ONNX` möglich; keine personenbezogenen Daten in Prompts erforderlich |
| **Verfügbarkeit** | Die Anwendung funktioniert ohne KI (Navigation, Suche, Export); nur die Analyse erfordert einen LLM |
| **Inkonsistenz** | Dieselbe Eingabe kann bei verschiedenen Anbietern oder Durchläufen unterschiedliche Bewertungen ergeben; dies ist eine inhärente Eigenschaft der LLM-Technologie |
| **Prompt-Injection** | Eingabetext ist größenbegrenzt (`TAXONOMY_LIMITS_MAX_BUSINESS_TEXT`); Prompt-Vorlagen werden vom Administrator verwaltet |

---

## Diagnose und Überwachung

### KI-Statusanzeige

Die Anwendung zeigt ein KI-Status-Badge in der Navigationsleiste an:

| Status | Bedeutung |
|---|---|
| 🟢 **Verbunden** | LLM-Anbieter ist erreichbar und antwortet |
| 🟡 **Eingeschränkt** | Anbieter ist langsam oder ratenbegrenzt |
| 🔴 **Nicht verfügbar** | Kein LLM-Anbieter konfiguriert oder erreichbar |

### Admin-Diagnose-Endpunkt

`GET /api/ai-diagnostics` (Rolle ADMIN erforderlich) gibt zurück:

- Name und Modell des aktiven LLM-Anbieters
- Anfrage-/Antwortstatistiken
- Durchschnittliche Antwortzeit
- Fehlerrate und letzter Fehler
- Status der Ratenbegrenzung

### LLM-Kommunikationsprotokoll

Das Admin-Panel enthält ein Kommunikationsprotokoll, das Folgendes anzeigt:

- Vollständiger an den LLM gesendeter Prompt-Text
- Vollständiger empfangener Antworttext
- Zeitstempel, Dauer und Token-Anzahl
- Verwendeter Anbieter und Modell

Dieses Protokoll ist nur für Administratoren zugänglich und wird im Arbeitsspeicher gehalten (nicht persistiert).

---

## Konfiguration für den behördlichen Einsatz

### Empfohlene Konfiguration

Für behördliche Bereitstellungen, die maximale Datensouveränität erfordern:

```bash
# Use local model — no external API calls
LLM_PROVIDER=LOCAL_ONNX

# Enable local embedding for semantic search
TAXONOMY_EMBEDDING_ENABLED=true

# Pre-download embedding model for air-gapped operation
TAXONOMY_EMBEDDING_MODEL_DIR=/app/models/bge-small-en-v1.5

# Enable audit logging
TAXONOMY_AUDIT_LOGGING=true

# Activate production profile
SPRING_PROFILES_ACTIVE=production,postgres
```

### EU-basierte Anbieteralternative

Wenn Cloud-basierte LLM-Analyse akzeptabel ist, die Daten aber innerhalb der EU bleiben müssen:

```bash
# Mistral is hosted in the EU (France)
LLM_PROVIDER=MISTRAL
MISTRAL_API_KEY=your-key

# Or Google Gemini with EU data residency (check current terms)
LLM_PROVIDER=GEMINI
GEMINI_API_KEY=your-key
```

---

## Zeitplan zur Einhaltung der KI-Verordnung

Die folgende Tabelle ordnet die Pflichten aus der KI-Verordnung (Verordnung (EU) 2024/1689) dem Taxonomy Architecture Analyzer zu und nennt die jeweiligen Geltungstermine:

| Pflicht | Geltungsdatum | Status | Bewertung |
|---|---|---|---|
| **Verbotene KI-Praktiken** (Art. 5) | 02.02.2025 ✅ | ✅ Nicht betroffen | Kein Social Scoring, keine Emotionserkennung oder manipulative Techniken |
| **KI-Kompetenz** (Art. 4) | 02.02.2025 ✅ | ✅ Dokumentiert | Betreiber müssen KI-kompetentes Personal sicherstellen — siehe [KI-Kompetenzkonzept](AI_LITERACY_CONCEPT.md) |
| **GPAI-Modellpflichten** (Art. 51–56) | 02.08.2025 ✅ | ✅ Nicht betroffen | Taxonomy *nutzt* GPAI, *stellt aber keine* GPAI-Modelle *bereit* — Anbieterpflicht gilt |
| **Vollständige Anwendbarkeit** (Art. 6–49) | 02.08.2026 | ✅ Konform | Klassifizierung als minimales Risiko — keine Registrierung erforderlich; Transparenzpflichten erfüllt |

### Zuordnung der GPAI-Anbieterpflichten

Da der Taxonomy Architecture Analyzer GPAI-Modelle (General Purpose AI) nutzt, diese aber weder entwickelt noch vertreibt, gelten die GPAI-spezifischen Pflichten nach Art. 51–56 für die Modellanbieter, nicht für die Taxonomy-Anwendung:

| GPAI-Pflicht | Verantwortliche Partei | Rolle der Taxonomy-Anwendung |
|---|---|---|
| Technische Dokumentation (Art. 53) | LLM-Anbieter (Google, OpenAI, Mistral usw.) | Nutzer — keine Pflicht |
| Urheberrechtskonformität (Art. 53) | LLM-Anbieter | Nutzer — keine Pflicht |
| Nachgelagerte Information (Art. 53) | LLM-Anbieter | Empfänger — Anbieterinformationen in [KI-Anbieter](AI_PROVIDERS.md) dokumentieren |
| Bewertung systemischer Risiken (Art. 55) | LLM-Anbieter (falls zutreffend) | Nutzer — keine Pflicht |

### Pflichten zur KI-Kompetenz

Seit dem 02.02.2025 verlangt Art. 4, dass Betreiber sicherstellen, dass Personal, das mit KI-Systemen interagiert, über ausreichende KI-Kompetenz verfügt. Der Taxonomy Architecture Analyzer adressiert dies durch:

- **[KI-Kompetenzkonzept](AI_LITERACY_CONCEPT.md)** — Schulungskonzept mit rollenspezifischen Lehrplänen
- **Erklärungsnachweise** — jede KI-Ausgabe enthält einen Begründungstext
- **Human-in-the-Loop-Design** — keine autonomen Aktionen; alle Ausgaben erfordern eine menschliche Überprüfung

---

## BSI-Konformität

Für Bereitstellungen in der deutschen Bundesverwaltung hat das BSI (Bundesamt für Sicherheit in der Informationstechnik) Kriterien für KI-Systeme im behördlichen Einsatz veröffentlicht. Die Konformität des Taxonomy Architecture Analyzer ist dokumentiert in:

- **[BSI-KI-Checkliste](BSI_KI_CHECKLIST.md)** — detaillierte Zuordnung der BSI-Kriterien zur Taxonomy-Implementierung

---

## Verwandte Dokumentation

- [KI-Anbieter](AI_PROVIDERS.md) — detaillierte Anbieterkonfiguration und API-Schlüssel
- [KI-Kompetenzkonzept](AI_LITERACY_CONCEPT.md) — Schulungskonzept gemäß KI-VO Art. 4
- [BSI-KI-Checkliste](BSI_KI_CHECKLIST.md) — BSI-Kriterien-Checkliste für KI-Modelle
- [Datenschutz](DATA_PROTECTION.md) — Verarbeitung personenbezogener Daten und DSGVO-Konformität
- [Sicherheit](SECURITY.md) — Authentifizierung, Autorisierung und Sicherheitsarchitektur
- [Konfigurationsreferenz](CONFIGURATION_REFERENCE.md) — alle Umgebungsvariablen
- [Digitale Souveränität](DIGITAL_SOVEREIGNTY.md) — digitale Souveränität und openCode-Kompatibilität
