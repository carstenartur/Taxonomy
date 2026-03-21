# Einstellungen

Das Einstellungssystem bietet **zur Laufzeit konfigurierbare Optionen** für den Taxonomy Architecture Analyzer. Alle Änderungen werden in einem dedizierten JGit-Repository mit vollständiger Audit-Spur persistiert — jede Aktualisierung erzeugt einen Git-Commit, der festhält, wer was und wann geändert hat.

## Inhaltsverzeichnis

- [Überblick](#überblick)
- [Geltungsbereich der Einstellungen](#geltungsbereich-der-einstellungen)
- [Zugriff auf Einstellungen](#zugriff-auf-einstellungen)
- [Verwendung der Web-Oberfläche](#verwendung-der-web-oberfläche)
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

## Geltungsbereich der Einstellungen

Alle Einstellungen sind **systemweit** — sie gelten für die gesamte Anwendungsinstanz und betreffen alle Benutzer und Arbeitsbereiche. Es gibt keine benutzerspezifischen oder arbeitsbereichsspezifischen Einstellungen.

| Geltungsbereich | Symbol | Bedeutung |
|---|---|---|
| ☁️ **System** | Gemeinsam für alle Benutzer und Arbeitsbereiche | Alle Einstellungen in dieser Anwendung |

Die folgende Tabelle verdeutlicht den Geltungsbereich für jede Einstellungskategorie:

| Kategorie | Geltungsbereich | Wer kann ändern | Betrifft |
|---|---|---|---|
| **LLM-Konfiguration** | ☁️ System | Nur Admin | Alle Analyseanfragen für alle Benutzer |
| **DSL- und Git-Konfiguration** | ☁️ System | Nur Admin | Das gemeinsame DSL-Repository und alle Branches |
| **Größenbeschränkungen** | ☁️ System | Nur Admin | Analyse-, Export- und Ansichtsoperationen aller Benutzer |

> **Hinweis:** Benutzerspezifische und arbeitsbereichsspezifische Einstellungen werden derzeit nicht unterstützt. Wenn eine Einstellung für verschiedene Benutzer oder Teams unterschiedlich sein soll, muss sie auf der Ebene der Umgebungsvariablen über separate Bereitstellungsinstanzen konfiguriert werden (siehe [Konfigurationsreferenz](CONFIGURATION_REFERENCE.md)).

**Verwandte bereichsspezifische Daten:**
- **Arbeitsbereichszustand** (aktueller Branch, Navigationshistorie, Projektion) — pro Benutzer, verwaltet durch den [Workspace Manager](WORKSPACE_VERSIONING.md)
- **Architecture DSL** — gespeichert in Git-Repositories, verwaltet durch `DslGitRepositoryFactory`; jeder Arbeitsbereich hat ein eigenes Repository (Repository-pro-Arbeitsbereich-Isolation mit arbeitsbereichsübergreifendem Publish/Sync), mit einem gemeinsamen `taxonomy-dsl`-System-Repository
- **Einstellungen** — gespeichert in einem separaten JGit-Repository (`taxonomy-preferences`); systemweit
- **Benutzerkonten und Rollen** — in der Datenbank gespeichert; pro Benutzer

---

## Zugriff auf Einstellungen

Einstellungen können über die **Web-Oberfläche** oder die **REST-API** verwaltet werden. Beide Methoden erfordern die Rolle **ADMIN**.

### REST-API

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

## Verwendung der Web-Oberfläche

Der Tab **Einstellungen** in der Navigationsleiste bietet eine grafische Oberfläche zur Verwaltung aller Anwendungseinstellungen. Dieser Tab ist nur für Benutzer mit Admin-Rechten sichtbar.

### Einstellungen öffnen

1. Klicken Sie auf **⚙️ Preferences** in der Hauptnavigationsleiste (nur sichtbar, wenn als Admin angemeldet).
2. Die Einstellungsseite öffnet sich und zeigt alle Einstellungen in drei Abschnitten.

### Einstellungen bearbeiten

Die Einstellungsseite ist in drei einklappbare Karten organisiert:

1. **🤖 LLM-Konfiguration** — Steuerelemente für LLM-Anfragerate, Timeout, Server-Ratenbegrenzung und Mindest-Relevanzbewertung.
2. **📂 JGit / DSL-Konfiguration** — Standard-Branch, Projektname, Auto-Speicher-Intervall, Remote-Git-URL, Token und Push-bei-Commit-Schalter.
3. **📈 Größenbeschränkungen** — Maximale Geschäftstextlänge, Architekturknoten und Exportknoten.

Jede Karte zeigt den aktuellen Wert für jede Einstellung. Um eine Einstellung zu ändern:

1. Ändern Sie den Wert im Eingabefeld.
2. Klicken Sie auf **💾 Save**, um die Änderungen zu speichern (erzeugt einen Git-Commit im Einstellungs-Repository).
3. Eine Erfolgsmeldung bestätigt das Speichern.

### Änderungshistorie anzeigen

Am unteren Rand der Einstellungsseite können Sie den Abschnitt **📋 Preferences Change History** aufklappen, um eine Audit-Spur aller Einstellungsänderungen zu sehen, einschließlich Commit-ID, Autor, Zeitstempel und Commit-Nachricht.

### Auf Standardwerte zurücksetzen

Klicken Sie auf **↩️ Reset to Defaults**, um alle Einstellungen auf die Werte aus `application.properties` zurückzusetzen. Dies erzeugt ebenfalls einen Git-Commit, sodass die vorherigen Werte aus der Historie wiederhergestellt werden können.

---

## Verfügbare Einstellungen

> Alle unten aufgeführten Einstellungen haben den Geltungsbereich **☁️ System** — sie gelten global für alle Benutzer und Arbeitsbereiche.

### LLM-Konfiguration

| Schlüssel | Typ | Standard | Geltungsbereich | Beschreibung |
|---|---|---|---|---|
| `llm.rpm` | int | `5` | ☁️ System | Maximale API-Anfragen pro Minute (ausgehende LLM-Drosselung) |
| `llm.timeout.seconds` | int | `30` | ☁️ System | HTTP-Lese-Timeout für LLM-API-Aufrufe |
| `rate-limit.per-minute` | int | `10` | ☁️ System | Eingehende Ratenbegrenzung für Analyse-Endpunkte (pro IP) |
| `analysis.min-relevance-score` | int | `70` | ☁️ System | Mindestbewertung, damit Knoten in Analyseergebnissen erscheinen |

Die Einstellung `llm.rpm` steuert die gleitende Fensterdrosselung für ausgehende LLM-API-Aufrufe. Das System verwaltet eine FIFO-Warteschlange von Zeitstempeln und lässt den Thread warten, wenn das Ratenlimit überschritten würde. Eine Toleranz von 50 ms wird für Taktabweichungen hinzugefügt.

Die Einstellung `llm.timeout.seconds` aktualisiert dynamisch das Lese-Timeout des `RestTemplate`, ohne die Anwendung neu starten zu müssen.

### DSL- und Git-Konfiguration

| Schlüssel | Typ | Standard | Geltungsbereich | Beschreibung |
|---|---|---|---|---|
| `dsl.default-branch` | string | `"draft"` | ☁️ System | Aktiver Branch für die DSL-Materialisierung |
| `dsl.project-name` | string | `"Taxonomy Architecture"` | ☁️ System | Menschenlesbarer Projekt-Anzeigename |
| `dsl.auto-save.interval-seconds` | int | `0` | ☁️ System | Automatische Speicherfrequenz (0 = deaktiviert) |
| `dsl.remote.url` | string | `""` | ☁️ System | Remote-Git-URL für Push-/Pull-Operationen |
| `dsl.remote.token` | string | `""` | ☁️ System | Authentifizierungs-Token für Remote-Git (in API-Antworten maskiert) |
| `dsl.remote.push-on-commit` | boolean | `false` | ☁️ System | Nach lokalen Commits automatisch zum Remote pushen |

> **Sicherheitshinweis:** Der Wert von `dsl.remote.token` wird in allen API-Antworten maskiert. Beim Abrufen über `GET /api/preferences` erscheint er als `"****{letzte4Zeichen}"`. Das vollständige Token wird nur intern für Git-Remote-Operationen verwendet.

### Größenbeschränkungen

| Schlüssel | Typ | Standard | Geltungsbereich | Beschreibung |
|---|---|---|---|---|
| `limits.max-business-text` | int | `5000` | ☁️ System | Maximale Zeichenanzahl in einem geschäftlichen Anforderungstext |
| `limits.max-architecture-nodes` | int | `50` | ☁️ System | Maximale Anzahl angezeigter Knoten in der Architekturansicht |
| `limits.max-export-nodes` | int | `200` | ☁️ System | Maximale Anzahl von Knoten in einem Export-Vorgang |

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
