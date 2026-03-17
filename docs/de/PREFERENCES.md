# Einstellungen

Das Einstellungssystem bietet **zur Laufzeit konfigurierbare Optionen** für den Taxonomy Architecture Analyzer. Alle Änderungen werden in einem dedizierten JGit-Repository mit vollständiger Audit-Spur persistiert — jede Aktualisierung erzeugt einen Git-Commit, der festhält, wer was und wann geändert hat.

## Inhaltsverzeichnis

- [Überblick](#überblick)
- [Zugriff auf Einstellungen](#zugriff-auf-einstellungen)
- [Verfügbare Einstellungen](#verfügbare-einstellungen)
- [LLM-Konfiguration](#llm-konfiguration)
- [DSL- und Git-Konfiguration](#dsl--und-git-konfiguration)
- [Größenbeschränkungen](#größenbeschränkungen)
- [Audit-Spur](#audit-spur)
- [Auf Standardwerte zurücksetzen](#auf-standardwerte-zurücksetzen)
- [REST-API-Endpunkte](#rest-api-endpunkte)
- [Sicherheit](#sicherheit)
- [Verwandte Dokumentation](#verwandte-dokumentation)

---

## Überblick

Einstellungen werden getrennt von der Architecture DSL in einem eigenen JGit-Repository (`taxonomy-preferences`) gespeichert. Diese Trennung stellt sicher, dass Einstellungsänderungen nicht die Architektur-Versionshistorie beeinflussen.

Beim Start lädt das System die Einstellungen vom JGit-HEAD. Wenn keine Commits vorhanden sind (erster Start), werden die Standardwerte aus `application.properties` verwendet und als initialer Zustand committet.

Änderungen werden **sofort wirksam** — ein Neustart der Anwendung ist nicht erforderlich.

---

## Zugriff auf Einstellungen

Einstellungen werden über die REST-API verwaltet. Alle Endpunkte erfordern die Rolle **ADMIN**.

```bash
# Alle aktuellen Einstellungen abrufen
curl -u admin:password http://localhost:8080/api/preferences

# Einstellungen aktualisieren
curl -u admin:password -X PUT \
  -H "Content-Type: application/json" \
  -d '{"llm.rpm": 10, "llm.timeout.seconds": 45}' \
  http://localhost:8080/api/preferences
```

---

## Verfügbare Einstellungen

### LLM-Konfiguration

| Schlüssel | Typ | Standard | Beschreibung |
|---|---|---|---|
| `llm.rpm` | int | `5` | Maximale API-Anfragen pro Minute (ausgehende LLM-Drosselung) |
| `llm.timeout.seconds` | int | `30` | HTTP-Lese-Timeout für LLM-API-Aufrufe |
| `rate-limit.per-minute` | int | `10` | Eingehende Ratenbegrenzung für Analyse-Endpunkte |
| `analysis.min-relevance-score` | int | `70` | Mindestbewertung, damit Knoten in Analyseergebnissen erscheinen |

Die Einstellung `llm.rpm` steuert die gleitende Fensterdrosselung für ausgehende LLM-API-Aufrufe. Das System verwaltet eine FIFO-Warteschlange von Zeitstempeln und lässt den Thread warten, wenn das Ratenlimit überschritten würde. Eine Toleranz von 50 ms wird für Taktabweichungen hinzugefügt.

Die Einstellung `llm.timeout.seconds` aktualisiert dynamisch das Lese-Timeout des `RestTemplate`, ohne die Anwendung neu starten zu müssen.

### DSL- und Git-Konfiguration

| Schlüssel | Typ | Standard | Beschreibung |
|---|---|---|---|
| `dsl.default-branch` | string | `"draft"` | Aktiver Branch für die DSL-Materialisierung |
| `dsl.project-name` | string | `"Taxonomy Architecture"` | Menschenlesbarer Projekt-Anzeigename |
| `dsl.auto-save.interval-seconds` | int | `0` | Automatische Speicherfrequenz (0 = deaktiviert) |
| `dsl.remote.url` | string | `""` | Remote-Git-URL für Push-/Pull-Operationen |
| `dsl.remote.token` | string | `""` | Authentifizierungs-Token für Remote-Git (in API-Antworten maskiert) |
| `dsl.remote.push-on-commit` | boolean | `false` | Nach lokalen Commits automatisch zum Remote pushen |

> **Sicherheitshinweis:** Der Wert von `dsl.remote.token` wird in allen API-Antworten maskiert. Beim Abrufen über `GET /api/preferences` erscheint er als `"****{letzte4Zeichen}"`. Das vollständige Token wird nur intern für Git-Remote-Operationen verwendet.

### Größenbeschränkungen

| Schlüssel | Typ | Standard | Beschreibung |
|---|---|---|---|
| `limits.max-business-text` | int | `5000` | Maximale Zeichenanzahl in einem geschäftlichen Anforderungstext |
| `limits.max-architecture-nodes` | int | `50` | Maximale Anzahl angezeigter Knoten in der Architekturansicht |
| `limits.max-export-nodes` | int | `200` | Maximale Anzahl von Knoten in einem Export-Vorgang |

---

## Audit-Spur

Jede Einstellungsänderung erzeugt einen Git-Commit im Repository `taxonomy-preferences`. Der Commit enthält:

- **Autor** — Der authentifizierte Benutzer, der die Änderung vorgenommen hat
- **Zeitstempel** — Wann die Änderung vorgenommen wurde
- **Vollständiger JSON-Schnappschuss** — Der vollständige Einstellungszustand nach der Änderung

Änderungshistorie anzeigen:

```bash
curl -u admin:password http://localhost:8080/api/preferences/history
```

Antwort:

```json
[
  {
    "commitId": "abc1234...",
    "author": "admin",
    "message": "Preference update",
    "timestamp": "2025-01-15T10:30:00Z"
  }
]
```

---

## Auf Standardwerte zurücksetzen

Um alle Einstellungen auf die Werte aus `application.properties` zurückzusetzen:

```bash
curl -u admin:password -X POST http://localhost:8080/api/preferences/reset
```

Dies erzeugt einen neuen Commit in der Einstellungshistorie, sodass Sie jederzeit über die Git-Historie zu einem früheren Zustand zurückkehren können.

---

## REST-API-Endpunkte

| Methode | Endpunkt | Beschreibung |
|---|---|---|
| `GET` | `/api/preferences` | Alle Einstellungen abrufen (Token maskiert) |
| `PUT` | `/api/preferences` | Einstellungen aktualisieren (erzeugt Git-Commit) |
| `POST` | `/api/preferences/reset` | Auf `application.properties`-Standardwerte zurücksetzen |
| `GET` | `/api/preferences/history` | Änderungshistorie der Einstellungen abrufen |

Alle Endpunkte erfordern die Rolle **ADMIN** und Authentifizierung.

---

## Sicherheit

- Alle Einstellungs-Endpunkte erfordern die Rolle `ADMIN`
- `dsl.remote.token` wird in allen Antworten maskiert (zeigt nur `"****{letzte4Zeichen}"`)
- Jede Aktualisierung zeichnet den authentifizierten Benutzer als Commit-Autor auf
- Das vollständige Einstellungs-JSON wird für die Audit-Konformität committet

---

## Verwandte Dokumentation

- [Konfigurationsreferenz](CONFIGURATION_REFERENCE.md) — Umgebungsvariablen und Startkonfiguration
- [Sicherheit](SECURITY.md) — Rollen, Authentifizierung und Zugriffskontrolle
- [Git-Integration](GIT_INTEGRATION.md) — DSL-Versionskontrolle und Branching
- [KI-Anbieter](AI_PROVIDERS.md) — LLM-Anbieterauswahl und -konfiguration
