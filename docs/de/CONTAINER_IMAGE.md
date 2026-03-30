# Taxonomy Architecture Analyzer — Container-Image

Das offizielle Container-Image wird von der CI/CD-Pipeline bei jedem Push auf den
Standard-Branch in der **GitHub Container Registry (GHCR)** veröffentlicht.

```
ghcr.io/carstenartur/taxonomy
```

> **GHCR ist die einzige offizielle Registry.** Verwenden Sie keine Images aus anderen
> Registries, es sei denn, Sie haben sie selbst erstellt und veröffentlicht.

---

## Inhaltsverzeichnis

1. [Image-Tags](#1-image-tags)
2. [Schnellstart (einzelner Container)](#2-schnellstart-einzelner-container)
3. [Empfohlenes Setup (Docker Compose)](#3-empfohlenes-setup-docker-compose)
4. [Umgebungsvariablen](#4-umgebungsvariablen)
5. [Persistenz & Volumes](#5-persistenz--volumes)
6. [Health Checks](#6-health-checks)
7. [Aktualisierung](#7-aktualisierung)
8. [Lokal bauen](#8-lokal-bauen)

> Siehe auch: [Konfigurationsreferenz](CONFIGURATION_REFERENCE.md) für alle Umgebungsvariablen,
> [Bereitstellungshandbuch](DEPLOYMENT_GUIDE.md) für Render.com- und VPS-Anleitungen.

---

## 1. Image-Tags

Die CI/CD-Pipeline (`ci-cd.yml`) veröffentlicht folgende Tags:

| Tag | Beispiel | Aktualisierung | Verwendungszweck |
|---|---|---|---|
| `latest` | `ghcr.io/carstenartur/taxonomy:latest` | Bei jedem Push auf `main` | Schnellstart, lokale Tests |
| `main` | `ghcr.io/carstenartur/taxonomy:main` | Bei jedem Push auf `main` | Identisch mit `latest` (Branch-Name-Tag) |
| `sha-<hash>` | `ghcr.io/carstenartur/taxonomy:sha-abc1234` | Bei jedem Push auf beliebigen Branch | Reproduzierbare Deployments — auf bestimmten Commit fixieren |

**Tag-Auswahl:**

- Verwenden Sie **`latest`** für lokale Experimente oder CI-Pipelines, die immer den neuesten Build benötigen.
- Verwenden Sie **`sha-<hash>`** in Produktionsumgebungen, um eine bekannte Version zu fixieren und explizit zu aktualisieren.
- Feature-Branches erzeugen ebenfalls `sha-`-Tags, aber kein `latest`-Tag.

---

## 2. Schnellstart (einzelner Container)

Herunterladen und starten in einem Befehl — kein Klonen, kein Build erforderlich:

```bash
docker pull ghcr.io/carstenartur/taxonomy:latest

docker run -p 8080:8080 ghcr.io/carstenartur/taxonomy:latest
```

Öffnen Sie <http://localhost:8080> und melden Sie sich mit `admin` / `admin` an.

> ⚠️ **Nur für lokale Tests.** Der obige Befehl exponiert unverschlüsseltes HTTP auf Port 8080.
> Exponieren Sie diesen Port niemals ins Internet. Für jede nicht-lokale Bereitstellung
> verwenden Sie das [Docker-Compose-Setup](#3-empfohlenes-setup-docker-compose) unten.

### Häufige Varianten

**Mit Google Gemini (Cloud-KI):**
```bash
docker run -p 8080:8080 \
  -e GEMINI_API_KEY=your-gemini-api-key \
  ghcr.io/carstenartur/taxonomy:latest
```

**Vollständig offline (lokales ONNX-Modell, kein API-Schlüssel):**
```bash
docker run -p 8080:8080 \
  -e LLM_PROVIDER=LOCAL_ONNX \
  ghcr.io/carstenartur/taxonomy:latest
```

**Mit persistenten Daten:**
```bash
docker run -p 8080:8080 \
  -v taxonomy-data:/app/data \
  ghcr.io/carstenartur/taxonomy:latest
```

---

## 3. Empfohlenes Setup (Docker Compose)

Für jede Bereitstellung über `localhost` hinaus verwenden Sie **Docker Compose** mit einem
Reverse-Proxy. Das Repository enthält [`docker-compose.prod.yml`](../../docker-compose.prod.yml)
mit [Caddy](https://caddyserver.com) für automatisches HTTPS:

```bash
# 1. Klonen und konfigurieren
git clone https://github.com/carstenartur/Taxonomy.git
cd Taxonomy
cp .env.example .env          # mit Ihrer Domain und Ihrem API-Schlüssel bearbeiten

# 2. Starten
docker compose -f docker-compose.prod.yml up -d
```

Dies startet zwei Dienste:

| Dienst | Image | Zweck |
|---|---|---|
| `taxonomy` | Lokal gebaut (oder `ghcr.io/carstenartur/taxonomy:latest`) | Anwendung auf Port 8080 (nur intern) |
| `caddy` | `caddy:2-alpine` | Reverse-Proxy mit automatischem Let's-Encrypt-HTTPS auf Port 443 |

### Veröffentlichtes Image statt lokalem Build verwenden

Bearbeiten Sie `docker-compose.prod.yml` und ersetzen Sie die Zeile `build: .` durch das GHCR-Image:

```yaml
services:
  taxonomy:
    image: ghcr.io/carstenartur/taxonomy:latest   # ← veröffentlichtes Image verwenden
    # build: .                                     # ← entfernen oder auskommentieren
```

Dadurch wird der lokale Maven-Build übersprungen und das vorgefertigte Image direkt verwendet.

### Datenbank-spezifische Compose-Dateien

Zusätzliche Compose-Dateien für externe Datenbanken:

| Datei | Datenbank |
|---|---|
| `docker-compose-postgres.yml` | PostgreSQL 16 |
| `docker-compose-mssql.yml` | Microsoft SQL Server 2022 |
| `docker-compose-oracle.yml` | Oracle Database 23 |
| `docker-compose-keycloak.yml` | PostgreSQL + Keycloak (SSO) |

Siehe [Datenbank-Setup](DATABASE_SETUP.md) für Details.

---

## 4. Umgebungsvariablen

Wichtige Umgebungsvariablen für Container-Bereitstellungen. Siehe die
[Konfigurationsreferenz](CONFIGURATION_REFERENCE.md) für die vollständige Liste.

| Variable | Erforderlich? | Standard | Beschreibung |
|---|---|---|---|
| `GEMINI_API_KEY` | Mind. ein LLM-Key oder `LOCAL_ONNX` | *(leer)* | Google Gemini API-Schlüssel für KI-Analyse |
| `LLM_PROVIDER` | Nein | *(Auto-Erkennung)* | Anbieter erzwingen: `GEMINI`, `OPENAI`, `DEEPSEEK`, `QWEN`, `LLAMA`, `MISTRAL`, `LOCAL_ONNX` |
| `TAXONOMY_ADMIN_PASSWORD` | Empfohlen | `admin` | Login-Passwort für den Admin-Benutzer |
| `TAXONOMY_EMBEDDING_ENABLED` | Nein | `true` | Auf `false` setzen, um semantische/KNN-Suche zu deaktivieren (~80–140 MB gespart) |
| `TAXONOMY_THYMELEAF_CACHE` | Nein | `true` | Template-Cache — in Produktion auf `true` belassen |
| `TAXONOMY_SWAGGER_PUBLIC` | Nein | `false` | Auf `true` setzen, um Swagger-UI ohne Authentifizierung freizugeben |
| `TAXONOMY_AUDIT_LOGGING` | Nein | `false` | Detailliertes Audit-Logging aktivieren |
| `JAVA_OPTS` | Nein | *(siehe Dockerfile)* | JVM-Flags überschreiben (Heap, GC, Stack-Größe) |

**Beispiel:**
```bash
docker run -p 8080:8080 \
  -e GEMINI_API_KEY=your-key \
  -e TAXONOMY_ADMIN_PASSWORD=strong-password \
  -e TAXONOMY_EMBEDDING_ENABLED=true \
  -v taxonomy-data:/app/data \
  ghcr.io/carstenartur/taxonomy:latest
```

---

## 5. Persistenz & Volumes

Die Anwendung speichert alle veränderlichen Daten unter `/app/data` im Container:

| Pfad | Inhalt |
|---|---|
| `/app/data` | HSQLDB-Datenbankdateien, Lucene-Suchindex, JGit-Repository |

Mounten Sie ein **benanntes Volume**, um Daten über Container-Neustarts und Upgrades hinweg zu erhalten:

```bash
-v taxonomy-data:/app/data
```

In `docker-compose.prod.yml` ist dieses Volume bereits konfiguriert:

```yaml
volumes:
  - taxonomy-data:/app/data   # Datenbank + Lucene-Index persistieren
```

### Embedding-Modell-Cache (LOCAL_ONNX)

Bei Verwendung von `LLM_PROVIDER=LOCAL_ONNX` lädt die DJL-Bibliothek das Embedding-Modell
(~33 MB) beim ersten Start herunter. Um es über Neustarts hinweg zu cachen:

```bash
-v djl-cache:/root/.djl.ai
```

### Backup

Um Anwendungsdaten zu sichern, stoppen Sie den Container und kopieren Sie das Volume:

```bash
docker run --rm -v taxonomy-data:/data -v $(pwd):/backup alpine \
  tar czf /backup/taxonomy-backup.tar.gz -C /data .
```

---

## 6. Health Checks

| Endpunkt | Beschreibung |
|---|---|
| `GET /api/status/startup` | Immer HTTP 200; Body: `{"initialized": true/false, "status": "..."}` |
| `GET /` | HTTP 200, wenn die Anwendung vollständig läuft |
| `GET /api/ai-status` | Verfügbarkeit des LLM-Anbieters |
| `GET /api/embedding/status` | Status des Embedding-Modells |

Die `docker-compose.prod.yml` enthält bereits einen Health Check:

```yaml
healthcheck:
  test: ["CMD", "wget", "-q", "-O", "/dev/null", "http://localhost:8080/api/status/startup"]
  interval: 30s
  timeout: 10s
  retries: 3
  start_period: 60s
```

---

## 7. Aktualisierung

### Mit `latest`-Tag

```bash
docker pull ghcr.io/carstenartur/taxonomy:latest
docker stop taxonomy-analyzer
docker rm taxonomy-analyzer
docker run -d --name taxonomy-analyzer \
  -p 8080:8080 \
  -v taxonomy-data:/app/data \
  ghcr.io/carstenartur/taxonomy:latest
```

### Mit Docker Compose

```bash
cd Taxonomy
docker compose -f docker-compose.prod.yml pull    # neueste Images herunterladen
docker compose -f docker-compose.prod.yml up -d    # mit neuem Image neu erstellen
```

### Fixierte Versionen

Um auf einen bestimmten Commit zu aktualisieren:

```bash
docker pull ghcr.io/carstenartur/taxonomy:sha-abc1234
# Aktualisieren Sie den Image-Tag in docker-compose.prod.yml, dann:
docker compose -f docker-compose.prod.yml up -d
```

> **Datenkompatibilität:** Die eingebettete HSQLDB-Datenbank wird beim Start automatisch
> migriert. Zwischen Versionen sind keine manuellen Migrationsschritte erforderlich.

---

## 8. Lokal bauen

Wenn Sie lieber aus dem Quellcode bauen, anstatt von GHCR zu laden:

```bash
git clone https://github.com/carstenartur/Taxonomy.git
cd Taxonomy
docker build -t taxonomy-analyzer .
docker run -p 8080:8080 taxonomy-analyzer
```

Das mehrstufige `Dockerfile` verwendet `eclipse-temurin:21-jdk` für die Build-Stufe und
`eclipse-temurin:21-jre` für die Laufzeit-Stufe. Das finale Image ist ca. 200 MB groß.
