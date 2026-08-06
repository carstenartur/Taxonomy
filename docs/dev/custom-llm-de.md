# Eigenes OpenAI-kompatibles LLM

Taxonomy kann einen vom Betreiber kontrollierten Sprachmodell-Server über das OpenAI-Chat-Completions-JSON-Format aufrufen. Damit lassen sich beispielsweise ein On-Premises-Gateway, ein Modellserver im selben Docker- oder Kubernetes-Netzwerk oder ein entfernter kompatibler Dienst anbinden.

## Erforderliche Konfiguration

```bash
export LLM_PROVIDER=CUSTOM_OPENAI
export CUSTOM_LLM_URL=http://llm-server:8080/v1/chat/completions
export CUSTOM_LLM_MODEL=name-des-bereitgestellten-modells
```

`CUSTOM_LLM_URL` muss der vollständige HTTP- oder HTTPS-Endpunkt für Chat Completions sein und mit `/chat/completions` enden. `CUSTOM_LLM_MODEL` wird unverändert in den Request übernommen.

Die Authentifizierung ist optional:

```bash
export CUSTOM_LLM_API_KEY=token-des-modellservers
```

Ist `CUSTOM_LLM_API_KEY` leer, sendet Taxonomy bewusst keinen `Authorization`-Header. Ist die Variable gesetzt, wird `Authorization: Bearer <token>` gesendet. Eine Variable `CUSTOM_OPENAI_API_KEY` existiert nicht.

## Schnittstellenvertrag

Taxonomy sendet genau eine Benutzernachricht:

```json
{
  "model": "name-des-bereitgestellten-modells",
  "messages": [
    {
      "role": "user",
      "content": "Taxonomy-Analyseprompt"
    }
  ]
}
```

Der Server muss den generierten Text unter `choices[0].message.content` zurückgeben:

```json
{
  "choices": [
    {
      "message": {
        "content": "{\"BP-1000\": {\"score\": 80, \"reason\": \"...\"}}"
      }
    }
  ]
}
```

Streaming-Antworten und anbieterspezifische native APIs werden von dieser Integration nicht verwendet.

## Semantische Embeddings sind eine getrennte Opt-in-Funktion

Die Auswahl des Chat-Providers aktiviert nicht das lokale ONNX-Embedding-Modell. Taxonomy verwendet sichere Standardwerte:

```bash
TAXONOMY_EMBEDDING_ENABLED=false
TAXONOMY_EMBEDDING_ALLOW_DOWNLOAD=false
```

Mit diesen Werten initialisieren oder laden Start, Taxonomieimport und Hibernate-Search-Indexierung das Modell `BAAI/bge-small-en-v1.5` nicht. Dokumente werden ohne Vektorfeldwerte indexiert.

Für ein eingehängtes lokales Modell ohne ausgehenden Download:

```bash
export TAXONOMY_EMBEDDING_ENABLED=true
export TAXONOMY_EMBEDDING_ALLOW_DOWNLOAD=false
export TAXONOMY_EMBEDDING_MODEL_DIR=/models/bge-small-en-v1.5
```

Ein Laufzeitdownload erfordert zwei ausdrückliche Entscheidungen:

```bash
export TAXONOMY_EMBEDDING_ENABLED=true
export TAXONOMY_EMBEDDING_ALLOW_DOWNLOAD=true
```

In produktiven Containern und Kubernetes sollte ein eingehängtes, geprüftes Modellverzeichnis verwendet werden.

## Beispiel mit Docker Compose

Befinden sich Taxonomy und das LLM im selben Compose-Projekt, wird der Dienstname des LLM-Servers als Host verwendet. `localhost` wäre falsch, weil es aus dem Taxonomy-Container auf den Taxonomy-Container selbst zeigt.

```yaml
services:
  taxonomy:
    image: ghcr.io/carstenartur/taxonomy:latest
    ports:
      - "8080:8080"
    environment:
      LLM_PROVIDER: CUSTOM_OPENAI
      CUSTOM_LLM_URL: http://llm-server:8000/v1/chat/completions
      CUSTOM_LLM_MODEL: architecture-model
      TAXONOMY_EMBEDDING_ENABLED: "false"
      TAXONOMY_EMBEDDING_ALLOW_DOWNLOAD: "false"
    depends_on:
      - llm-server

  llm-server:
    image: eigener-openai-kompatibler-server:version
    expose:
      - "8000"
```

Läuft der Modellserver direkt auf dem Docker-Host, muss eine aus dem Container erreichbare Hostadresse verwendet werden.

## Auswahl, Verfügbarkeit und Diagnose

Der Provider erscheint im Frontend erst, wenn sowohl `CUSTOM_LLM_URL` als auch `CUSTOM_LLM_MODEL` gültig und nicht leer sind. Ein API-Key ist nicht erforderlich.

Mit `LLM_PROVIDER=CUSTOM_OPENAI` wird der eigene Server zum globalen Standard. Ohne explizite Providerauswahl bleibt die bisherige Priorität der Cloud-API-Keys erhalten; der eigene Endpunkt wird anschließend automatisch gewählt, wenn URL und Modell vollständig konfiguriert sind.

Den aufgelösten Status zeigt:

```bash
curl http://localhost:8080/api/ai-status
```

Als Providername wird `Custom OpenAI-compatible` zurückgegeben; in `availableProviders` muss `CUSTOM_OPENAI` enthalten sein.

Der nur für Administratoren verfügbare Endpunkt `/api/diagnostics` unterscheidet Providerkonfiguration und Authentifizierung. Ein korrekt konfigurierter, nicht authentifizierter Endpunkt wird mit `providerConfigured=true`, `apiKeyConfigured=false` und `authenticationMode=NONE` ausgewiesen.

Konfigurations- und Aufruffehler nennen ihre tatsächliche Ursache, darunter fehlende `CUSTOM_LLM_URL`, fehlendes `CUSTOM_LLM_MODEL`, ungültige URI oder Pfad, nicht erreichbarer Endpunkt und HTTP 401/403. Authentifizierungsfehler nennen ausschließlich die optionale Variable `CUSTOM_LLM_API_KEY`.

## Rate Limit und Timeout

Für den eigenen Provider gilt standardmäßig keine ausgehende RPM-Begrenzung. Bei Bedarf kann sie über folgende Laufzeiteinstellung gesetzt werden:

```text
llm.rpm.custom_openai
```

Die gemeinsamen LLM-Einstellungen für Timeout und Wiederholungsversuche gelten ebenfalls:

```text
llm.timeout.seconds
llm.retry.max
```

## Sicherheitshinweise

- `CUSTOM_LLM_URL` ist vertrauenswürdige Betreiberkonfiguration und steuert eine ausgehende serverseitige HTTP-Verbindung.
- Über nicht vertrauenswürdige Netze sollten HTTPS und ein Bearer-Token verwendet werden.
- Ein nicht authentifizierter Modellserver sollte ausschließlich in einem isolierten internen Netz erreichbar sein.
- Geheimnisse gehören nicht in `CUSTOM_LLM_URL`, sondern in `CUSTOM_LLM_API_KEY`.
- URLs mit eingebetteten Benutzerinformationen werden abgelehnt.
- Laufzeitdownloads für Embeddings bleiben in Produktion deaktiviert, solange keine ausdrückliche Egress-Entscheidung vorliegt.
- Der Modellserver muss die maximale Promptgröße des verwendeten Analyseablaufs akzeptieren.

## Fehlerbehebung

| Symptom | Konkrete Ursache bzw. Maßnahme |
|---|---|
| `CUSTOM_OPENAI` fehlt in der Providerauswahl | `CUSTOM_LLM_URL` und `CUSTOM_LLM_MODEL` setzen; die HTTP(S)-URI muss mit `/chat/completions` enden |
| `MISSING_URL` oder `MISSING_MODEL` | Die ausdrücklich genannte Pflichtvariable setzen; ein API-Key ersetzt weder URL noch Modell |
| `INVALID_PATH` | `CUSTOM_LLM_URL` auf den Chat-Completions-Endpunkt richten, nicht auf `/v1`, `/models` oder die Serverwurzel |
| Verbindung abgelehnt bzw. Endpunkt nicht erreichbar | Dienstname, DNS, NetworkPolicy und Erreichbarkeit aus dem Taxonomy-Container prüfen |
| HTTP 401/403 | Der Endpunkt verlangt oder verwirft Bearer-Authentifizierung; optionale `CUSTOM_LLM_API_KEY` setzen oder korrigieren |
| Antwort kommt an, aber Bewertungen bleiben leer | Der Server liefert den Text nicht unter `choices[0].message.content`, oder das Modell hält das verlangte JSON-Format nicht ein |
| Aufrufe laufen in ein Timeout | `llm.timeout.seconds` erhöhen und sicherstellen, dass das Modell vor Beginn der Analyse geladen ist |
| ONNX-Modell wird nicht verwendet | Embeddings ausdrücklich aktivieren und ein lokales Modell bereitstellen oder den Download ausdrücklich erlauben |

Siehe auch [KI-Anbieter](../de/AI_PROVIDERS.md) und die [Konfigurationsreferenz](../de/CONFIGURATION_REFERENCE.md).
