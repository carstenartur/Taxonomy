# KI-Anbieter

Der Taxonomy Architecture Analyzer unterstützt mehrere LLM-Anbieter (Large Language Model) für KI-gestützte Analysen. Dieser Leitfaden erklärt, wie Anbieter ausgewählt, konfiguriert und zur Laufzeit überwacht werden.

## Inhaltsverzeichnis

- [Überblick](#überblick)
- [Unterstützte Anbieter](#unterstützte-anbieter)
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

Der `LlmService` ist die zentrale Komponente für KI-Analysen. Er unterstützt 7 Anbieter und implementiert budgetbeschränkte Bewertung, Streaming-Analyse, Ratenbegrenzung und Diagnose. Die Anbieterauswahl verwendet ein 3-stufiges Prioritätssystem, und Anbieter können zur Laufzeit ohne Neustart der Anwendung gewechselt werden.

---

## Unterstützte Anbieter

| Anbieter | Modell | API-Schlüssel-Variable | API-Schlüssel erforderlich |
|---|---|---|---|
| **Google Gemini** | gemini-3-flash-preview | `GEMINI_API_KEY` | Ja |
| **OpenAI** | gpt-4o-mini | `OPENAI_API_KEY` | Ja |
| **DeepSeek** | deepseek-chat | `DEEPSEEK_API_KEY` | Ja |
| **Qwen** (Alibaba) | qwen-plus | `QWEN_API_KEY` | Ja |
| **Llama** | llama3.1-70b | `LLAMA_API_KEY` | Ja |
| **Mistral** | mistral-small-latest | `MISTRAL_API_KEY` | Ja |
| **LOCAL_ONNX** | bge-small-en-v1.5 | — | Nein (läuft lokal) |

`LOCAL_ONNX` verwendet ein lokales Embedding-Modell und benötigt keinen API-Schlüssel. Es ist immer als Fallback verfügbar.

---

## Anbieterauswahl

Das System verwendet eine **3-stufige Priorität**, um zu bestimmen, welcher Anbieter eine Anfrage bearbeitet:

1. **Überschreibung pro Anfrage** (höchste Priorität) — Wird über `ThreadLocal` gesetzt, wenn das Frontend einen bestimmten Anbieter für eine einzelne Anfrage auswählt.
2. **Explizite Konfiguration** — Wird über die Umgebungsvariable `LLM_PROVIDER` oder die Eigenschaft `llm.provider` gesetzt.
3. **Automatische Erkennung** — Das System prüft API-Schlüssel in dieser Reihenfolge:
   - GEMINI → OPENAI → DEEPSEEK → QWEN → LLAMA → MISTRAL
   - Der erste Anbieter mit einem konfigurierten API-Schlüssel wird ausgewählt.
4. **Standard** — Wenn kein API-Schlüssel gefunden wird, wird standardmäßig GEMINI verwendet (die Analyse schlägt fehl, bis ein API-Schlüssel konfiguriert ist).

### Aktiven Anbieter prüfen

```bash
curl http://localhost:8080/api/ai-status
```

Antwort:

```json
{
  "available": true,
  "provider": "Google Gemini",
  "availableProviders": ["Google Gemini", "LOCAL_ONNX"]
}
```

Die Liste `availableProviders` enthält immer `LOCAL_ONNX`. Weitere Anbieter erscheinen, wenn deren API-Schlüssel konfiguriert sind.

---

## Anbieterüberschreibung pro Anfrage

Das Frontend kann einen bestimmten Anbieter für einzelne Analyseanfragen auswählen. Dies wird mithilfe eines `ThreadLocal<LlmProvider>` im `LlmService` implementiert:

1. Vor der Analyse setzt `llmService.setRequestProvider(provider)` die Überschreibung.
2. `getActiveProvider()` prüft zuerst den `ThreadLocal` (Priorität 0).
3. Nach der Analyse wird `llmService.clearRequestProvider()` in einem `finally`-Block aufgerufen.

Dies ermöglicht es Benutzern, Ergebnisse verschiedener Anbieter zu vergleichen, ohne die globale Konfiguration zu ändern.

---

## KI-Statusanzeige

Die Navigationsleiste zeigt ein Badge an, das den aktuellen KI-Status anzeigt:

| Badge | Zustand | Bedeutung |
|---|---|---|
| 🟢 **AI: [Anbietername]** | Verfügbar | KI-Analyse ist aktiv. Zeigt den Anbieternamen an (z. B. „Google Gemini"). |
| 🔴 **AI: Unavailable** | Nicht verfügbar | Kein LLM-API-Schlüssel konfiguriert. Die Schaltfläche **Analyze with AI** ist deaktiviert. |
| ⚠️ **AI: Unknown** | Fehler | Statusprüfung fehlgeschlagen (Netzwerkfehler oder Server startet). Automatische Aktualisierung alle 30 Sekunden. |

Wenn Sie ein rotes Badge sehen:
- Setzen Sie einen der LLM-API-Schlüssel (`GEMINI_API_KEY`, `OPENAI_API_KEY` usw.) und starten Sie neu, oder
- Setzen Sie `LLM_PROVIDER=LOCAL_ONNX` für Offline-Analyse ohne API-Schlüssel.

Wenn KI nicht verfügbar ist, erscheint eine **Inline-Warnmeldung** unterhalb der Analyse-Schaltfläche, die die erforderlichen Umgebungsvariablen auflistet.

---

## LLM-Diagnose

Das **LLM-Diagnosepanel** (nur für Administratoren) zeigt Laufzeitstatistiken:

- Anbietername und Modellversion
- Ob ein API-Schlüssel konfiguriert ist (mit maskiertem Präfix)
- Gesamtanzahl der API-Aufrufe
- Anzahl erfolgreicher und fehlgeschlagener Aufrufe
- Durchschnittliche Antwortlatenz
- Zeitpunkt und Status des letzten Aufrufs

```bash
curl -u admin:password http://localhost:8080/api/ai-diagnostics
```

Beispielantwort:

```json
{
  "provider": "Google Gemini",
  "apiKeyConfigured": true,
  "apiKeyPrefix": "sk-****",
  "totalCalls": 347,
  "successfulCalls": 344,
  "failedCalls": 3,
  "lastCallTime": "2025-01-15T10:30:45Z",
  "lastCallSuccess": true
}
```

Klicken Sie auf **Test Connection** im Diagnosepanel, um eine Testanfrage an den LLM-Anbieter zu senden und zu bestätigen, dass dieser korrekt antwortet.

---

## Ratenbegrenzung und Drosselung

Zwei separate Ratenbegrenzungsmechanismen schützen das System:

### Ausgehende LLM-Drosselung

Gesteuert durch die Einstellung `llm.rpm` (Standard: 5 Anfragen/Minute). Verwendet einen Sliding-Window-Algorithmus:

1. Eine FIFO-Warteschlange zeichnet Zeitstempel der letzten LLM-API-Aufrufe auf.
2. Vor jedem Aufruf prüft das System, ob der älteste Eintrag in der Warteschlange älter als 60 Sekunden ist.
3. Wenn das Limit überschritten würde, wartet der Thread, bis der älteste Aufruf das Zeitfenster verlässt.
4. Eine Toleranz von 50 ms wird für Taktabweichungen hinzugefügt.

Dies kann zur Laufzeit über die [Einstellungen](PREFERENCES.md)-API angepasst werden.

### Eingehende Ratenbegrenzung

Gesteuert durch `TAXONOMY_RATE_LIMIT_PER_MINUTE` (Standard: 10 Anfragen/Minute). Angewendet auf Analyse-Endpunkte:

- `POST /api/analyze`
- `POST /api/analyze-stream`
- `POST /api/analyze-node`
- `POST /api/justify-leaf`

Gibt HTTP `429 Too Many Requests` zurück, wenn das Limit überschritten wird.

---

## Mock-Modus

Für Tests und Entwicklung können Sie den Mock-Modus aktivieren, um echte LLM-Aufrufe zu umgehen:

```bash
LLM_MOCK=true
```

Mock-Modus:
- Gibt vorberechnete Bewertungen aus `classpath:mock-scores/secure-voice-comms.json` zurück
- Fällt auf fest kodierte Wurzelbewertungen mit deterministischer Variation zurück
- Benötigt keinen API-Schlüssel
- Wird in CI, Screenshots und Entwicklungsumgebungen verwendet

---

## Prompt-Vorlagen-Editor

Der **Prompt-Vorlagen-Editor** (nur für Administratoren) ermöglicht die Anpassung der an das LLM gesendeten Anweisungen ohne erneutes Deployment:

1. Wählen Sie eine Prompt-Vorlage aus dem Dropdown.
2. Bearbeiten Sie den Vorlagentext.
3. Klicken Sie auf **Save**, um die Änderung zu speichern, oder **Reset**, um den integrierten Standard wiederherzustellen.

Änderungen werden sofort für die nächste Analyse wirksam.

---

## LLM-Kommunikationsprotokoll

Das **LLM-Kommunikationsprotokoll** (nur für Administratoren) zeichnet den vollständigen Prompt und die Rohantwort für jeden Analysevorgang auf. Verwenden Sie es für:

- Debugging unerwarteter Bewertungsergebnisse
- Überprüfung der Prompt-Effektivität
- Auditierung von LLM-Interaktionen

---

## Timeout-Konfiguration

Das HTTP-Lese-Timeout für LLM-API-Aufrufe ist zur Laufzeit konfigurierbar:

| Einstellung | Standard | Beschreibung |
|---|---|---|
| `llm.timeout.seconds` | `30` | Sekunden, die auf eine LLM-Antwort gewartet wird |

Aktualisierung über [Einstellungen](PREFERENCES.md):

```bash
curl -u admin:password -X PUT \
  -H "Content-Type: application/json" \
  -d '{"llm.timeout.seconds": 45}' \
  http://localhost:8080/api/preferences
```

Das Timeout wird dynamisch auf das `RestTemplate` angewendet, ohne die Anwendung neu zu starten.

---

## Umgebungsvariablen

| Variable | Beschreibung |
|---|---|
| `GEMINI_API_KEY` | Google Gemini API-Schlüssel |
| `OPENAI_API_KEY` | OpenAI API-Schlüssel |
| `DEEPSEEK_API_KEY` | DeepSeek API-Schlüssel |
| `QWEN_API_KEY` | Alibaba Qwen API-Schlüssel |
| `LLAMA_API_KEY` | Llama API-Schlüssel |
| `MISTRAL_API_KEY` | Mistral API-Schlüssel |
| `LLM_PROVIDER` | Explizite Anbieterauswahl (überschreibt die automatische Erkennung) |
| `LLM_MOCK` | Mock-Modus aktivieren (`true`/`false`) |
| `TAXONOMY_RATE_LIMIT_PER_MINUTE` | Eingehende Ratenbegrenzung für Analyse-Endpunkte |

Siehe [Konfigurationsreferenz](CONFIGURATION_REFERENCE.md) für die vollständige Liste der Umgebungsvariablen.

---

## Verwandte Dokumentation

- [Konfigurationsreferenz](CONFIGURATION_REFERENCE.md) — Alle Umgebungsvariablen und Startkonfiguration
- [Einstellungen](PREFERENCES.md) — Verwaltung der Laufzeiteinstellungen
- [Benutzerhandbuch](USER_GUIDE.md) — KI-Statusanzeige und Admin-Modus (§14)
- [Sicherheit](SECURITY.md) — Authentifizierung und Zugriffskontrolle
- [API-Referenz](API_REFERENCE.md) — Vollständige REST-API-Dokumentation
