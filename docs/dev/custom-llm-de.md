# Eigenes OpenAI-kompatibles LLM

Taxonomy kann einen vom Betreiber kontrollierten Sprachmodell-Server über das OpenAI-Chat-Completions-JSON-Format aufrufen. Damit lassen sich beispielsweise ein On-Premises-Gateway, ein Modellserver im selben Docker- oder Kubernetes-Netzwerk oder ein entfernter kompatibler Dienst anbinden.

## Erforderliche Konfiguration

```bash
export LLM_PROVIDER=CUSTOM_OPENAI
export CUSTOM_LLM_URL=http://llm-server:8080/v1/chat/completions
export CUSTOM_LLM_MODEL=name-des-bereitgestellten-modells
```

`CUSTOM_LLM_URL` muss der vollständige HTTP- oder HTTPS-Endpunkt für Chat Completions sein und mit `/chat/completions` enden, nicht nur ein Hostname oder eine API-Basis-URL. `CUSTOM_LLM_MODEL` wird unverändert in den Request übernommen.

Die Authentifizierung ist optional:

```bash
export CUSTOM_LLM_API_KEY=token-des-modellservers
```

Ist `CUSTOM_LLM_API_KEY` leer, sendet Taxonomy bewusst keinen `Authorization`-Header. Ist die Variable gesetzt, wird `Authorization: Bearer <token>` gesendet.

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

Streaming-Antworten und anbieterspezifische native APIs werden von dieser Integration nicht verwendet. Zu konfigurieren ist der OpenAI-kompatible Chat-Completions-Endpunkt des Servers.

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
    depends_on:
      - llm-server

  llm-server:
    image: eigener-openai-kompatibler-server:version
    expose:
      - "8000"
```

Läuft der Modellserver direkt auf dem Docker-Host, muss eine aus dem Container erreichbare Hostadresse verwendet werden. Der konkrete Hostname hängt vom Betriebssystem und der Docker-Konfiguration ab.

## Auswahl und Verfügbarkeit

Der Provider erscheint im Frontend erst, wenn sowohl `CUSTOM_LLM_URL` als auch `CUSTOM_LLM_MODEL` gültig und nicht leer sind. Ein API-Key ist nicht erforderlich.

Mit `LLM_PROVIDER=CUSTOM_OPENAI` wird der eigene Server zum globalen Standard. Ohne explizite Providerauswahl bleibt die bisherige Priorität der Cloud-API-Keys erhalten; der eigene Endpunkt wird anschließend automatisch gewählt, wenn URL und Modell vollständig konfiguriert sind.

Den aufgelösten Status zeigt:

```bash
curl http://localhost:8080/api/ai-status
```

Als Providername wird `Custom OpenAI-compatible` zurückgegeben; in `availableProviders` muss `CUSTOM_OPENAI` enthalten sein.

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
- Der Modellserver muss die maximale Promptgröße des verwendeten Analyseablaufs akzeptieren.

## Fehlerbehebung

| Symptom | Wahrscheinliche Ursache |
|---|---|
| `CUSTOM_OPENAI` fehlt in der Providerauswahl | URL oder Modell ist leer, oder die URL ist keine vollständige `http://`- beziehungsweise `https://`-Chat-Completions-URI |
| Verbindung aus Docker wird abgelehnt | Die URL verwendet `localhost` statt des Dienstnamens oder einer erreichbaren Hostadresse |
| HTTP 401/403 | Der Server verlangt einen Bearer-Token; `CUSTOM_LLM_API_KEY` setzen |
| Antwort kommt an, aber Bewertungen bleiben leer | Der Server liefert den Text nicht unter `choices[0].message.content`, oder das Modell hält das verlangte JSON-Format nicht ein |
| Aufrufe laufen in ein Timeout | `llm.timeout.seconds` erhöhen und sicherstellen, dass das Modell vor Beginn der Analyse geladen ist |

Siehe auch [KI-Anbieter](../de/AI_PROVIDERS.md) und die [Konfigurationsreferenz](../de/CONFIGURATION_REFERENCE.md).
