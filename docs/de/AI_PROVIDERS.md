# KI-Anbieter

Der Taxonomy Architecture Analyzer unterstützt mehrere LLM-Anbieter (Large Language Model) für KI-gestützte Analysen. Dieser Leitfaden beschreibt Auswahl, Konfiguration und Laufzeitüberwachung.

## Inhaltsverzeichnis

- [Überblick](#überblick)
- [Unterstützte Anbieter](#unterstützte-anbieter)
- [Eigener OpenAI-kompatibler Endpunkt](#eigener-openai-kompatibler-endpunkt)
- [Anbieterauswahl](#anbieterauswahl)
- [Anbieterüberschreibung pro Anfrage](#anbieterüberschreibung-pro-anfrage)
- [KI-Statusanzeige](#ki-statusanzeige)
- [LLM-Diagnose](#llm-diagnose)
- [Ratenbegrenzung und Drosselung](#ratenbegrenzung-und-drosselung)
- [Mock-Modus](#mock-modus)
- [Prompt-Vorlagen-Editor](#prompt-vorlagen-editor)
- [LLM-Kommunikationsprotokoll](#llm-kommunikationsprotokoll)
- [Timeout-Konfiguration](#timeout-konfiguration)
- [Umgebungsvariablen](#umgebungsvariablen)
- [Verwandte Dokumentation](#verwandte-dokumentation)

---

## Überblick

Der `LlmService` ist die zentrale Komponente für KI-Analysen. Er unterstützt acht Laufzeitprovider sowie budgetbeschränkte Bewertung, Streaming-Analyse, Ratenbegrenzung, Diagnose und die Providerauswahl pro Anfrage.

---

## Unterstützte Anbieter

| Anbieter | Standardmodell | Konfiguration | API-Key erforderlich |
|---|---|---|---|
| **Google Gemini** | gemini-3-flash-preview | `GEMINI_API_KEY` | Ja |
| **OpenAI** | gpt-4o-mini | `OPENAI_API_KEY` | Ja |
| **DeepSeek** | deepseek-chat | `DEEPSEEK_API_KEY` | Ja |
| **Qwen** (Alibaba) | qwen-plus | `DASHSCOPE_API_KEY` | Ja |
| **Llama** | llama3.1-70b | `LLAMA_API_KEY` | Ja |
| **Mistral** | mistral-small-latest | `MISTRAL_API_KEY` | Ja |
| **CUSTOM_OPENAI** | vom Betreiber festgelegt | `CUSTOM_LLM_URL`, `CUSTOM_LLM_MODEL`, optional `CUSTOM_LLM_API_KEY` | Nein |
| **LOCAL_ONNX** | bge-small-en-v1.5 | lokale Embedding-Einstellungen | Nein |

`CUSTOM_OPENAI` bindet ein generatives LLM über die OpenAI-kompatible Chat-Completions-Schnittstelle an. `LOCAL_ONNX` ist davon zu unterscheiden: Es liefert lokale Ähnlichkeitsbewertungen auf Basis von Embeddings und erzeugt keine textuellen LLM-Antworten.

---

## Eigener OpenAI-kompatibler Endpunkt

Mit `CUSTOM_OPENAI` kann Taxonomy einen selbst betriebenen oder anderweitig vom Betreiber kontrollierten Modellserver verwenden:

```bash
export LLM_PROVIDER=CUSTOM_OPENAI
export CUSTOM_LLM_URL=http://llm-server:8000/v1/chat/completions
export CUSTOM_LLM_MODEL=architecture-model
```

Die Authentifizierung ist optional:

```bash
export CUSTOM_LLM_API_KEY=geheimer-token
```

Ohne API-Key sendet Taxonomy keinen `Authorization`-Header. Mit konfiguriertem Key wird Bearer-Authentifizierung verwendet. URL und Modell sind Pflichtangaben; die URL muss ein vollständiger `http://`- oder `https://`-Chat-Completions-Endpunkt sein.

Der [Leitfaden für ein eigenes OpenAI-kompatibles LLM](CUSTOM_LLM.md) enthält den JSON-Vertrag, Docker-Beispiele, Sicherheitshinweise und eine Fehlerbehebung.

---

## Anbieterauswahl

Die Auswahl folgt dieser Priorität:

1. **Überschreibung pro Anfrage** — Auswahl im Frontend für einen einzelnen Aufruf.
2. **Explizite Konfiguration** — `LLM_PROVIDER` beziehungsweise `llm.provider`.
3. **Automatische Erkennung** — erste vollständige Konfiguration in dieser Reihenfolge:
   - GEMINI → OPENAI → DEEPSEEK → QWEN → LLAMA → MISTRAL → CUSTOM_OPENAI
4. **Standard** — GEMINI, wenn keine Providerkonfiguration erkannt wird.

Für die üblichen Cloud-Anbieter wird der jeweilige API-Key geprüft. Bei `CUSTOM_OPENAI` werden eine gültige `CUSTOM_LLM_URL` und ein nicht leeres `CUSTOM_LLM_MODEL` verlangt; der API-Key bleibt optional.

### Aktiven Anbieter prüfen

```bash
curl http://localhost:8080/api/ai-status
```

Beispiel:

```json
{
  "available": true,
  "provider": "Custom OpenAI-compatible",
  "availableProviders": ["LOCAL_ONNX", "CUSTOM_OPENAI"]
}
```

`availableProviders` enthält immer `LOCAL_ONNX`. Cloud-Anbieter werden bei vorhandenem API-Key ergänzt; `CUSTOM_OPENAI` erscheint bei vollständiger und gültiger URL-/Modellkonfiguration.

---

## Anbieterüberschreibung pro Anfrage

Das Frontend kann für einen einzelnen Analyseaufruf einen Provider auswählen:

1. `llmService.setRequestProvider(provider)` speichert die Auswahl vor der Analyse.
2. `getActiveProvider()` wertet zuerst diese anfragebezogene Auswahl aus.
3. `llmService.clearRequestProvider()` entfernt sie in einem `finally`-Block.

Damit lassen sich Ergebnisse verschiedener Provider vergleichen, ohne die globale Deployment-Konfiguration zu ändern.

---

## KI-Statusanzeige

Die Navigationsleiste zeigt den aktuellen KI-Zustand:

| Badge | Zustand | Bedeutung |
|---|---|---|
| 🟢 **AI: [Anbietername]** | Vollständig | Ein generativer HTTP-Provider oder der Mock-Modus ist verfügbar. |
| 🟡 **AI: [Anbietername]** | Eingeschränkt | Lokale, embeddingbasierte Analyse ist verfügbar. |
| 🔴 **AI: Unavailable** | Nicht verfügbar | Dem gewählten Provider fehlt eine Pflichtkonfiguration und es ist kein nutzbarer lokaler Fallback geladen. |
| ⚠️ **AI: Unknown** | Fehler | Die Statusabfrage ist fehlgeschlagen oder die Anwendung startet noch. |

Bei `CUSTOM_OPENAI` müssen `CUSTOM_LLM_URL` und `CUSTOM_LLM_MODEL` geprüft werden. Ein fehlender `CUSTOM_LLM_API_KEY` ist bei einem vertrauenswürdigen, nicht authentifizierten Server zulässig.

---

## LLM-Diagnose

Das Diagnosepanel für Administratoren zeigt:

- aktiven Provider;
- Vorhandensein der erforderlichen Verbindungskonfiguration;
- ein maskiertes API-Key-Präfix, wenn ein echter Key konfiguriert ist;
- Gesamtzahl erfolgreicher und fehlgeschlagener Aufrufe;
- Zeitpunkt und Ergebnis des letzten Aufrufs;
- letzten Fehler.

```bash
curl -u admin:password http://localhost:8080/api/diagnostics
```

Mit **Test Connection** kann eine echte Testanfrage gesendet werden.

---

## Ratenbegrenzung und Drosselung

### Ausgehende LLM-Drosselung

Jedes HTTP-Gateway besitzt eine unabhängige Sliding-Window-Drosselung. Providerspezifische Einstellungen folgen diesem Schema:

```text
llm.rpm.<provider-id-in-kleinbuchstaben>
```

Für den eigenen Provider lautet die Einstellung:

```text
llm.rpm.custom_openai
```

Der Standardwert für den eigenen Endpunkt ist `0`, also keine ausgehende RPM-Begrenzung. Das Gemini-Gateway behält seinen am Free Tier orientierten Standard.

### Eingehende Ratenbegrenzung

`TAXONOMY_RATE_LIMIT_PER_MINUTE` begrenzt eingehende LLM-gestützte Aufrufe pro Client und liefert bei Überschreitung HTTP `429`.

---

## Mock-Modus

Der Mock-Modus umgeht echte LLM-Aufrufe:

```bash
LLM_MOCK=true
```

Er liefert deterministisch vorbereitete Bewertungen, benötigt keinen API-Key und ist für Tests, Screenshots sowie Offline-Entwicklung vorgesehen.

---

## Prompt-Vorlagen-Editor

Im Prompt-Vorlagen-Editor können Administratoren die an den aktiven Provider gesendeten Anweisungen ohne neues Deployment ändern. Gespeicherte Änderungen gelten für die nächste Analyse.

---

## LLM-Kommunikationsprotokoll

Das Kommunikationsprotokoll zeichnet Prompt und generierten Rohtext der Analyseaufrufe auf. Es hilft bei fehlerhaften Antwortformaten, unerwarteten Bewertungen und der Optimierung der Prompts. Geheimnisse und Authorization-Header werden nicht in den Prompttext geschrieben.

---

## Timeout-Konfiguration

Die Laufzeiteinstellung `llm.timeout.seconds` steuert das HTTP-Lese-Timeout. `llm.retry.max` bestimmt die Wiederholungsversuche bei geeigneten Serverfehlern und Timeouts.

```bash
curl -u admin:password -X PUT \
  -H "Content-Type: application/json" \
  -d '{"llm.timeout.seconds": 90, "llm.retry.max": 2}' \
  http://localhost:8080/api/preferences
```

Selbst betriebene Modelle können beim erstmaligen Laden ein längeres Timeout benötigen.

---

## Umgebungsvariablen

| Variable | Beschreibung |
|---|---|
| `LLM_PROVIDER` | Explizite Providerauswahl einschließlich `CUSTOM_OPENAI` und `LOCAL_ONNX` |
| `LLM_MOCK` | Deterministischen Mock-Modus aktivieren |
| `GEMINI_API_KEY` | Google-Gemini-API-Key |
| `OPENAI_API_KEY` | OpenAI-API-Key |
| `DEEPSEEK_API_KEY` | DeepSeek-API-Key |
| `DASHSCOPE_API_KEY` | Alibaba-DashScope-API-Key für Qwen |
| `LLAMA_API_KEY` | Llama-API-Key |
| `MISTRAL_API_KEY` | Mistral-API-Key |
| `CUSTOM_LLM_URL` | Vollständiger eigener OpenAI-kompatibler Chat-Completions-Endpunkt |
| `CUSTOM_LLM_MODEL` | An den eigenen Endpunkt gesendete Modellkennung |
| `CUSTOM_LLM_API_KEY` | Optionaler Bearer-Token für den eigenen Endpunkt |
| `TAXONOMY_RATE_LIMIT_PER_MINUTE` | Eingehendes Rate Limit für LLM-gestützte Endpunkte |

Die [Konfigurationsreferenz](CONFIGURATION_REFERENCE.md) enthält die maßgebliche Zuordnung zu Spring-Properties.

---

## Verwandte Dokumentation

- [Eigenes OpenAI-kompatibles LLM](CUSTOM_LLM.md)
- [Konfigurationsreferenz](CONFIGURATION_REFERENCE.md)
- [Einstellungen](PREFERENCES.md)
- [Sicherheit](SECURITY.md)
- [API-Referenz](API_REFERENCE.md)
