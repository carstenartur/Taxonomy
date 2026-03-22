# Taxonomy Architecture Analyzer — Bereitstellungshandbuch

Dieses Handbuch behandelt die Bereitstellung des Taxonomy Architecture Analyzer mit Docker und Render.com.

> **Voraussetzungen:** Docker 20+ für containerisierte Bereitstellungen. Es wird keine externe Datenbank und kein Message Broker benötigt — die Anwendung ist vollständig eigenständig.

---

## Inhaltsverzeichnis

1. [Docker-Bereitstellung](#1-docker-bereitstellung)
2. [Render.com-Bereitstellung](#2-rendercom-bereitstellung)
3. [Health Check](#3-health-check)
4. [Fehlerbehebung](#4-fehlerbehebung)
5. [Datenbank-Backends](#5-datenbank-backends)
6. [HTTPS/TLS-Terminierung](#6-httpstls-terminierung)
7. [VPS-Bereitstellung](#7-hetzner--vps-bereitstellung-docker--caddy)

> Siehe auch: [Konfigurationsreferenz](CONFIGURATION_REFERENCE.md) für alle Umgebungsvariablen, [Architekturbeschreibung](ARCHITECTURE.md) für Details zum Systemdesign.

---

## 1. Docker-Bereitstellung

### Docker-Image erstellen

Das Repository enthält ein mehrstufiges `Dockerfile`:

| Stufe | Base Image | Zweck |
|---|---|---|
| **build** | `eclipse-temurin:17-jdk-alpine` | Kompiliert die Anwendung mit Maven |
| **runtime** | `eclipse-temurin:17-jre-alpine` | Führt die Anwendung aus (minimales Image) |

```bash
# Build the image
docker build -t taxonomy-analyzer .

# The image is approximately 200 MB (JRE + application JAR)
```

### Ausführung mit Docker

> ⚠️ **Die folgenden Befehle exponieren unverschlüsseltes HTTP auf Port 8080 — nur für lokale Tests verwenden.**
> Für internetfähige Bereitstellungen verwenden Sie [`docker-compose.prod.yml`](../../docker-compose.prod.yml),
> das automatisches HTTPS über Caddy bereitstellt (siehe [Abschnitt 7](#7-hetzner--vps-bereitstellung-docker--caddy)).

**Minimal (nur Browser, ohne KI):**
```bash
docker run -p 8080:8080 taxonomy-analyzer
```

**Mit einem Cloud-LLM-Anbieter (z. B. Gemini):**
```bash
docker run -p 8080:8080 \
  -e GEMINI_API_KEY=your-gemini-api-key \
  taxonomy-analyzer
```

**Mit lokaler Offline-KI (kein API-Schlüssel erforderlich):**
```bash
docker run -p 8080:8080 \
  -e LLM_PROVIDER=LOCAL_ONNX \
  taxonomy-analyzer
```

**Vollständige Produktionskonfiguration:**
```bash
docker run -d \
  --name taxonomy-analyzer \
  -p 8080:8080 \
  -e GEMINI_API_KEY=your-gemini-api-key \
  -e ADMIN_PASSWORD=your-admin-password \
  -e TAXONOMY_ADMIN_PASSWORD=strong-login-password \
  -e TAXONOMY_EMBEDDING_ENABLED=true \
  -e TAXONOMY_SWAGGER_PUBLIC=false \
  -e TAXONOMY_AUDIT_LOGGING=true \
  taxonomy-analyzer
```

### Erforderliche `-e`-Flags

| Flag | Erforderlich? | Beschreibung |
|---|---|---|
| `-e GEMINI_API_KEY=...` | Mindestens ein LLM-Schlüssel oder `LOCAL_ONNX` | Aktiviert die KI-Analyse |
| `-e ADMIN_PASSWORD=...` | Optional | Schützt Admin-Bereiche |
| `-e LLM_PROVIDER=...` | Optional | Erzwingt einen bestimmten LLM-Anbieter |
| `-e TAXONOMY_EMBEDDING_ENABLED=false` | Optional | Deaktiviert die semantische Suche |

### Volume Mounts

Die Anwendung verwendet eine dateibasierte HSQLDB-Datenbank und einen dateisystembasierten Lucene-Index, die beide unter `/app/data` gespeichert werden. Binden Sie ein persistentes Volume ein, um Daten über Container-Neustarts hinweg zu erhalten:

```bash
docker run -p 8080:8080 \
  -v taxonomy-data:/app/data \
  taxonomy-analyzer
```

Für den DJL-Embedding-Modell-Cache (LOCAL_ONNX-Modus):

```bash
docker run -p 8080:8080 \
  -e LLM_PROVIDER=LOCAL_ONNX \
  -v taxonomy-data:/app/data \
  -v djl-cache:/root/.djl.ai \
  taxonomy-analyzer
```

Damit bleiben das heruntergeladene Modell, die Datenbank und der Suchindex über Container-Neustarts hinweg erhalten.

### Health Check

Die Anwendung antwortet auf `GET /` mit dem Statuscode 200, wenn sie fehlerfrei läuft:

```bash
docker run -d \
  --name taxonomy-analyzer \
  --health-cmd="wget -q -O /dev/null http://localhost:8080/ || exit 1" \
  --health-interval=30s \
  --health-timeout=10s \
  --health-retries=3 \
  -p 8080:8080 \
  taxonomy-analyzer
```

### Veröffentlichtes Image verwenden

Die CI/CD-Pipeline veröffentlicht ein Docker-Image in der GitHub Container Registry:

```bash
docker pull ghcr.io/carstenartur/taxonomy:latest
docker run -p 8080:8080 -e GEMINI_API_KEY=your-key ghcr.io/carstenartur/taxonomy:latest
```

---

## 2. Render.com-Bereitstellung

### Die `render.yaml` verstehen

Das Repository enthält eine `render.yaml`-Blueprint-Spezifikation für die Render-**Free-Tier**-Stufe:

```yaml
services:
  - type: web
    name: taxonomy-analyzer
    runtime: docker
    plan: free
    healthCheckPath: /api/status/startup
    envVars:
      - key: GEMINI_API_KEY
        sync: false        # set manually in the Render dashboard
      - key: TAXONOMY_EMBEDDING_ENABLED
        value: "false"        # DJL/ONNX model uses ~80–140 MB native memory; disable on the 512 MB free tier
      - key: TAXONOMY_INIT_ASYNC
        value: "true"         # load taxonomy after Tomcat opens its port so Render detects the port quickly
```

| Feld | Beschreibung |
|---|---|
| `type: web` | Render-Webdienst mit öffentlicher URL |
| `name` | Anzeigename im Render-Dashboard |
| `runtime: docker` | Weist Render an, das `Dockerfile` im Repository-Stammverzeichnis zu verwenden |
| `plan: free` | Render-Free-Plan; wechseln Sie zu `starter` oder höher für mehr Ressourcen |
| `healthCheckPath: /api/status/startup` | Render prüft `GET /api/status/startup` — gibt immer HTTP 200 zurück, auch während die Taxonomie noch geladen wird |
| `TAXONOMY_EMBEDDING_ENABLED` | Auf `false` setzen in der Free-Tier-Stufe, um ~80–140 MB nativen Speicher zu sparen (deaktiviert die semantische KNN-Suche) |
| `TAXONOMY_INIT_ASYNC` | Auf `true` setzen, damit die Taxonomie in einem Hintergrund-Thread geladen wird, nachdem Tomcat seinen Port geöffnet hat (verhindert Render-Port-Scan-Timeout) |
| `envVars[].sync: false` | Die Variable muss manuell als Secret im Dashboard eingegeben werden |

> **Hinweis:** Die Render-Free-Tier-Stufe unterstützt **keine** persistenten Festplatten. Die Anwendung
> verwendet standardmäßig eine In-Memory-HSQLDB-Datenbank (`mem:`) und einen Lucene-Heap-Index, was die
> korrekten Einstellungen für die Free-Tier-Stufe sind. Die Taxonomiedaten werden bei jedem Deployment
> aus der eingebetteten Excel-Arbeitsmappe neu geladen. Um Daten über Redeployments hinweg zu erhalten,
> wechseln Sie zu einem kostenpflichtigen Render-Plan und fügen Sie einen `disk:`-Abschnitt mit
> `TAXONOMY_DATASOURCE_URL`, `TAXONOMY_DDL_AUTO=update` und
> `TAXONOMY_SEARCH_DIRECTORY_TYPE=local-filesystem` hinzu.

### Umgebungsvariablen in Render konfigurieren

1. Öffnen Sie das [Render-Dashboard](https://dashboard.render.com)
2. Wählen Sie Ihren **taxonomy-analyzer**-Dienst aus
3. Klicken Sie in der linken Seitenleiste auf **Environment**
4. Klicken Sie auf **Add Environment Variable**

**Erforderliche Variablen:**

| Schlüssel | Secret? | Wert |
|---|---|---|
| `GEMINI_API_KEY` | ✅ Ja | Ihr Gemini-API-Schlüssel |

**Optionale Variablen:**

| Schlüssel | Secret? | Wert |
|---|---|---|
| `ADMIN_PASSWORD` | ✅ Ja | Passwort für Admin-Bereiche |
| `LLM_PROVIDER` | Nein | `GEMINI`, `OPENAI`, etc. (überschreibt die automatische Erkennung) |
| `OPENAI_API_KEY` | ✅ Ja | Alternative: OpenAI-Schlüssel anstelle von Gemini |
| `TAXONOMY_EMBEDDING_ENABLED` | Nein | `true` (Standard) oder `false` — in `render.yaml` für die Free-Tier-Stufe auf `false` gesetzt |
| `JAVA_OPTS` | Nein | JVM-Flags überschreiben, ohne das Image neu zu erstellen (z. B. `-XX:+UseSerialGC -Xss512k -XX:MaxRAMPercentage=50.0 -Xmx220m`) |

> **Tipp:** Markieren Sie API-Schlüssel und Passwörter in Render als „Secret", damit sie
> nicht in Logs und im Dashboard angezeigt werden.

### Bereitstellung über GitHub

1. Verbinden Sie Ihr GitHub-Repository im Render-Dashboard
2. Render erkennt automatisch die `render.yaml` und das `Dockerfile`
3. Konfigurieren Sie Ihre geheimen Umgebungsvariablen
4. Klicken Sie auf **Manual Deploy** → **Deploy latest commit** (oder pushen Sie, um ein automatisches Deployment auszulösen)

Das Deployment dauert ungefähr 3–5 Minuten (Maven-Build + Docker-Image).

---

## 3. Health Check

Die Anwendung stellt einen Startup-Status-Endpunkt unter `GET /api/status/startup` bereit, der immer HTTP 200 zurückgibt und im JSON-Body anzeigt, ob die Taxonomie vollständig geladen wurde. Render verwendet diesen Pfad zur Zustandsüberwachung.

Weitere Zustandsindikatoren:

| Endpunkt | Was geprüft wird |
|---|---|
| `GET /api/status/startup` | Immer HTTP 200; `{"initialized": true/false, "status": "pending/loading/ready/error"}` |
| `GET /` | Die Anwendung läuft und Thymeleaf rendert (erfordert geladene Taxonomie) |
| `GET /api/ai-status` | Verfügbarkeit des LLM-Anbieters |
| `GET /api/embedding/status` | Embedding-Modell geladen und bereit |
| `GET /api/taxonomy` | Taxonomiedaten aus Excel geladen |

---

## 4. Fehlerbehebung

### Container startet nicht

- Prüfen Sie den Speicher: JRE + Lucene-Index + ONNX Runtime benötigen mindestens ~256 MB; der Heap ist auf 50 % des Container-Speichers begrenzt (harte Obergrenze `-Xmx220m`), um Platz für Off-Heap-nativen Speicher zu lassen. In Renders 512-MB-Free-Tier-Stufe wird semantisches Embedding über `TAXONOMY_EMBEDDING_ENABLED=false` deaktiviert, um ~80–140 MB nativen Speicher einzusparen.
- Prüfen Sie die Logs: `docker logs taxonomy-analyzer`

### KI-Analyse funktioniert nicht

- Überprüfen Sie, ob der API-Schlüssel gesetzt ist: `docker exec taxonomy-analyzer printenv | grep API_KEY`
- Prüfen Sie `GET /api/ai-status` für den Anbieterstatus

### Download des Embedding-Modells schlägt fehl

- Der DJL-Modell-Download erfordert beim ersten Start Internetzugang
- Für Air-Gap-Bereitstellungen siehe die [Konfigurationsreferenz](CONFIGURATION_REFERENCE.md)
  zum Vorab-Download des Embedding-Modells

---

## 5. Datenbank-Backends

Standardmäßig verwendet die Anwendung eine eingebettete HSQLDB-Datenbank (In-Memory, keine Einrichtung erforderlich). Für Produktionsbereitstellungen wechseln Sie zu einer persistenten Datenbank:

| Datenbank | Profil | Docker-Compose-Datei |
|---|---|---|
| PostgreSQL 14+ | `postgres` | `docker-compose-postgres.yml` |
| SQL Server 2019+ | `mssql` | `docker-compose-mssql.yml` |
| Oracle 19c+ / 23c | `oracle` | `docker-compose-oracle.yml` |

```bash
# Example: Run with PostgreSQL
docker compose -f docker-compose-postgres.yml up
```

Um von HSQLDB auf eine Produktionsdatenbank zu migrieren:

1. Setzen Sie `SPRING_PROFILES_ACTIVE` auf das Zielprofil
2. Konfigurieren Sie `TAXONOMY_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`
3. Verwenden Sie `TAXONOMY_DDL_AUTO=create` beim ersten Start und wechseln Sie danach zu `update`

Die Taxonomiedaten werden beim Start immer aus der mitgelieferten Excel-Arbeitsmappe geladen, sodass keine Datenmigration für die Taxonomie selbst erforderlich ist. Im Git-Repository gespeicherte Architektur-DSL-Daten müssen neu erstellt werden.

Siehe [Datenbank-Einrichtung](DATABASE_SETUP.md) für detaillierte Anleitungen zu jeder Datenbank, einschließlich Fehlerbehebung und Integrationstests.

---

## 6. HTTPS/TLS-Terminierung

Die Anwendung lauscht standardmäßig auf **HTTP-Port 8080**. Für jede Bereitstellung außerhalb von `localhost` sollte immer TLS vor der Anwendung terminiert werden. Der HSTS-Header (`Strict-Transport-Security`) wird bereits von der Anwendung bei jeder Antwort gesendet.

### Option A — Reverse Proxy (empfohlen)

Ein Reverse Proxy übernimmt Zertifikatsverwaltung, TLS-Terminierung und optional Ratenbegrenzung. Die Anwendung lauscht weiterhin auf Plain-HTTP hinter dem Proxy.

#### nginx

```nginx
server {
    listen 443 ssl http2;
    server_name taxonomy.example.com;

    ssl_certificate     /etc/letsencrypt/live/taxonomy.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/taxonomy.example.com/privkey.pem;
    ssl_protocols       TLSv1.2 TLSv1.3;
    ssl_ciphers         HIGH:!aNULL:!MD5;

    location / {
        proxy_pass http://localhost:8080;
        proxy_set_header Host              $host;
        proxy_set_header X-Real-IP         $remote_addr;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto https;
    }
}

# HTTP → HTTPS Weiterleitung
server {
    listen 80;
    server_name taxonomy.example.com;
    return 301 https://$host$request_uri;
}
```

#### Caddy (automatisches Let's Encrypt)

```
taxonomy.example.com {
    reverse_proxy localhost:8080
}
```

Caddy stellt TLS-Zertifikate automatisch bereit und erneuert sie. Keine weitere Konfiguration erforderlich.

#### Docker Compose (nginx + App)

```yaml
services:
  taxonomy:
    image: ghcr.io/carstenartur/taxonomy:latest
    environment:
      - GEMINI_API_KEY=${GEMINI_API_KEY}
      - TAXONOMY_ADMIN_PASSWORD=${TAXONOMY_ADMIN_PASSWORD}
    expose:
      - "8080"          # nur intern — nicht auf dem Host veröffentlicht

  nginx:
    image: nginx:alpine
    ports:
      - "443:443"
      - "80:80"
    volumes:
      - ./nginx.conf:/etc/nginx/conf.d/default.conf:ro
      - /etc/letsencrypt:/etc/letsencrypt:ro
    depends_on:
      - taxonomy
```

In der `nginx.conf` oben ersetzen Sie `proxy_pass http://localhost:8080` durch `proxy_pass http://taxonomy:8080` (den Docker-Servicenamen).

### Option B — Spring Boot Native SSL

Für Einzelcontainer-Setups (kein Reverse Proxy verfügbar) kann Spring Boot TLS direkt über SSL-Bundles terminieren.

**Über Umgebungsvariablen:**

```bash
docker run -p 8443:8443 \
  -e SERVER_PORT=8443 \
  -e SERVER_SSL_BUNDLE=taxonomy-tls \
  -e SPRING_SSL_BUNDLE_PEM_TAXONOMY_TLS_KEYSTORE_CERTIFICATE=/certs/cert.pem \
  -e SPRING_SSL_BUNDLE_PEM_TAXONOMY_TLS_KEYSTORE_PRIVATE_KEY=/certs/key.pem \
  -v /etc/letsencrypt/live/taxonomy.example.com/fullchain.pem:/certs/cert.pem:ro \
  -v /etc/letsencrypt/live/taxonomy.example.com/privkey.pem:/certs/key.pem:ro \
  ghcr.io/carstenartur/taxonomy:latest
```

> **Hinweis:** Bei Verwendung von nativem SSL muss der Docker-Health-Check auf HTTPS umgestellt werden:
> `--health-cmd="wget --no-check-certificate -q -O /dev/null https://localhost:8443/ || exit 1"`

### Render.com

Render.com stellt automatisches HTTPS für alle Web-Services bereit — keine zusätzliche Konfiguration erforderlich. Das TLS-Zertifikat wird automatisch von der Plattform bereitgestellt und erneuert.

### Cloud-Load-Balancer (AWS ALB, GCP, Azure)

Cloud-Load-Balancer terminieren TLS, bevor der Datenverkehr den Container erreicht. Belassen Sie die Anwendung auf Port 8080 (HTTP) und konfigurieren Sie die Zielgruppe des Load Balancers so, dass sie an den Container-Port weiterleitet.

---

## 7. VPS-Bereitstellung (Docker + Caddy)

Dieser Abschnitt beschreibt eine vollständige Bereitstellung auf einem Hetzner Cloud Server (oder einem beliebigen Linux-VPS mit Docker). Caddy übernimmt automatisches HTTPS — keine manuelle Zertifikatsverwaltung erforderlich.

### Voraussetzungen

| Anforderung | Hinweise |
|---|---|
| **Linux-VPS** | (2 vCPU, 4 GB RAM) oder vergleichbar |
| **Docker + Docker Compose** | `apt install docker.io docker-compose-plugin` auf Ubuntu/Debian |
| **Domain-Name** | A/AAAA-DNS-Eintrag, der auf die Server-IP zeigt |
| **Ports 80 + 443 offen** | Erforderlich für Let's-Encrypt-Zertifikatsvalidierung und HTTPS |

> **Ports:** Caddy lauscht auf **443** (HTTPS) und **80** (HTTP→HTTPS-Weiterleitung + ACME-Challenge). Port 8080 der Anwendung wird **niemals** ins Internet exponiert — er ist nur innerhalb des Docker-Netzwerks erreichbar.

### Schnellstart

```bash
# 1. Repository klonen
git clone https://github.com/carstenartur/Taxonomy.git
cd Taxonomy

# 2. Umgebungsdatei erstellen
cp .env.example .env
nano .env                      # Domain, Admin-Passwort und optional API-Key eintragen

# 3. Alles starten (baut das App-Image, startet Caddy + App)
docker compose -f docker-compose.prod.yml up -d --build

# 4. Logs prüfen
docker compose -f docker-compose.prod.yml logs -f
```

Öffnen Sie `https://ihre-domain.example.com` — Caddy stellt beim ersten Zugriff automatisch ein Let's-Encrypt-TLS-Zertifikat bereit. Melden Sie sich mit `admin` und dem in `.env` gesetzten Passwort an.

### Architektur

```
┌─── Internet ───────────────────────────────────────────────┐
│                                                            │
│  :443 (HTTPS) ──► Caddy ──► taxonomy:8080 (HTTP, intern)   │
│  :80  (Weiterleitung)                                      │
│                                                            │
│  Port 8080 wird NICHT auf dem Host veröffentlicht.         │
└────────────────────────────────────────────────────────────┘
```

### Dateien

| Datei | Zweck |
|---|---|
| [`docker-compose.prod.yml`](../../docker-compose.prod.yml) | Produktions-Compose-Datei: Caddy + Taxonomy-App |
| [`Caddyfile`](../../Caddyfile) | Caddy-Reverse-Proxy-Konfiguration |
| [`.env.example`](../../.env.example) | Vorlage für Umgebungsvariablen |

### Firewall (Cloud Firewall)

Erstellen Sie in der Cloud Console eine Firewall mit diesen Eingangsregeln:

| Port | Protokoll | Quelle | Zweck |
|---|---|---|---|
| **22** | TCP | Ihre IP oder `0.0.0.0/0` | SSH |
| **80** | TCP | `0.0.0.0/0` | ACME-Challenge + HTTP→HTTPS-Weiterleitung |
| **443** | TCP | `0.0.0.0/0` | HTTPS |

**Port 8080 darf nicht geöffnet werden.** Er muss intern bleiben.

### Aktualisierung

```bash
cd Taxonomy
git pull
docker compose -f docker-compose.prod.yml up -d --build
```