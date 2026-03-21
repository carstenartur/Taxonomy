# Repository-Topologie & Arbeitsbereich-Bereitstellung

## Überblick

Der Taxonomy Architecture Analyzer verwendet ein **systemverwaltetes zentrales Repository**-Modell
mit **verzögerter Arbeitsbereich-Bereitstellung** für die Zusammenarbeit mehrerer Benutzer.
Dieses Design trennt den gemeinsamen teamweiten Architekturzustand von den individuellen
Arbeitsbereichen der Benutzer und ermöglicht sicheres paralleles Bearbeiten ohne Störungen.

## Topologiemodi

Das System unterstützt zwei Topologiemodi, die definieren, wie das zentrale Repository
sich zu externen Quellen verhält:

### INTERNAL_SHARED (Standard)

Die Anwendung hostet das gemeinsame Integrations-Repository intern. Alle Benutzer
synchronisieren mit diesem internen Repository. Dies ist der Standardmodus und
erfordert keine externe Git-Einrichtung.

```
┌─────────────────────────────────────┐
│   System-Repository (intern)        │
│   Modus: INTERNAL_SHARED            │
│   Branch: "draft"                   │
└──────────┬──────────────────────────┘
           │
     ┌─────┼──────┐
     │     │      │
  alice  bob   carol
```

### EXTERNAL_CANONICAL

Ein externes Git-Repository dient als kanonische zentrale Quelle. Die Anwendung
synchronisiert sich mit diesem externen Repository. Dieser Modus ist für die
Integration mit bestehender Enterprise-Git-Infrastruktur vorgesehen.

## Lebenszyklus der Arbeitsbereich-Bereitstellung

Wenn ein neuer Benutzer auf die Anwendung zugreift, durchläuft sein Arbeitsbereich
einen Bereitstellungs-Lebenszyklus:

```
Anmeldung / Erster Zugriff
    │
    ▼
Arbeitsbereich-Metadaten erstellt
    Status: NOT_PROVISIONED
    │
    ▼
Benutzer löst "Arbeitsbereich vorbereiten" aus
    Status: PROVISIONING
    │
    ├── Erfolg → Status: READY
    │     └── Persönlicher Branch erstellt (z.B. alice/workspace)
    │
    └── Fehler → Status: FAILED
          └── Fehlermeldung für erneuten Versuch gespeichert
```

### Bereitstellungszustände

| Status | Beschreibung |
|--------|-------------|
| `NOT_PROVISIONED` | Arbeitsbereich-Metadaten existieren, aber es wurde noch kein Git-Branch erstellt |
| `PROVISIONING` | Branch-Erstellung läuft |
| `READY` | Arbeitsbereich ist vollständig bereitgestellt und einsatzbereit |
| `FAILED` | Bereitstellung fehlgeschlagen; siehe Fehlermeldung für Details |

## System-Repository

Das System erstellt beim Start automatisch einen primären Repository-Datensatz mit:
- **Topologiemodus**: `INTERNAL_SHARED`
- **Standard-Branch**: `draft`
- **Anzeigename**: "Shared Architecture Repository"

Dieser Datensatz wird von `SystemRepositoryService` verwaltet und stellt den
konfigurierbaren Namen des gemeinsamen Branches bereit, der von allen
Synchronisierungsoperationen verwendet wird.

## REST-API-Endpunkte

### GET /api/workspace/provisioning-status

Gibt den aktuellen Bereitstellungszustand des Benutzer-Arbeitsbereichs zurück.

**Antwort:**
```json
{
  "status": "READY",
  "topologyMode": "INTERNAL_SHARED",
  "sourceRepository": "uuid-des-system-repos",
  "error": null
}
```

### POST /api/workspace/provision

Erstellt den persönlichen Branch des Benutzers aus dem gemeinsamen Repository.

**Antwort:**
```json
{
  "status": "READY",
  "branch": "alice/workspace",
  "baseBranch": "draft"
}
```

### GET /api/workspace/topology

Gibt den Repository-Topologiemodus und Informationen zur gemeinsamen Quelle zurück.

**Antwort:**
```json
{
  "mode": "INTERNAL_SHARED",
  "sharedBranch": "draft",
  "systemRepositoryId": "uuid-des-system-repos",
  "displayName": "Shared Architecture Repository"
}
```

## Benutzerfreundliche Terminologie

Die folgende Terminologie wird in der Benutzeroberfläche verwendet, um
rohe Git-Konzepte zu vermeiden:

| Technischer Begriff | Englisch (UI) | Deutsch (UI) |
|-------------------|-------------|-------------|
| Zentrales Repository | Shared Space | Gemeinsamer Bereich |
| Benutzer-Arbeitsbereich | My Workspace | Mein Arbeitsbereich |
| Branch / Variante | Variant | Variante |
| Arbeitsbereich bereitstellen | Prepare Workspace | Arbeitsbereich vorbereiten |
| Vom Team synchronisieren | Sync from Team | Vom Team synchronisieren |
| Änderungen veröffentlichen | Publish for Team | Für Team veröffentlichen |
| Vergleichen | Compare | Vergleichen |
| Zusammenführen | Integrate | Integrieren |
| Cherry-Pick | Apply Single Change | Einzeländerung übernehmen |
| HEAD | Current Version | Aktuelle Version |

Begriffe wie `fork`, `fetch`, `refs`, `rebase` werden **niemals** in der
Standard-Benutzeroberfläche angezeigt.

---

## Verwandte Dokumentation

- [Workspace- & Versionierungshandbuch](WORKSPACE_VERSIONING.md) — benutzerorientierter Leitfaden für die Workspace-UI
- [GIT_INTEGRATION](GIT_INTEGRATION.md) — technische Details der JGit-gestützten DSL-Speicherung, Branching und Merge
- [Architektur](ARCHITECTURE.md) — DSL-Speicherarchitektur und Modulübersicht
