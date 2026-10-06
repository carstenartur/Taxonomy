# Taxonomy Architecture Analyzer — Container-Image

Das offizielle Container-Image wird nach einem erfolgreichen Build des Standard-Branches oder eines Release-Tags in der **GitHub Container Registry (GHCR)** veröffentlicht:

```text
ghcr.io/carstenartur/taxonomy
```

> **GHCR ist die einzige offizielle Registry.** Verwenden Sie keine Images aus anderen Registries, es sei denn, Sie haben sie selbst erstellt und veröffentlicht.

## 1. Image-Tags

| Tag | Beispiel | Aktualisierung | Verwendungszweck |
|---|---|---|---|
| `latest` | `ghcr.io/carstenartur/taxonomy:latest` | Erfolgreicher Push auf `main` | Lokale Evaluierung |
| `main` | `ghcr.io/carstenartur/taxonomy:main` | Erfolgreicher Push auf `main` | Nachverfolgung des Standard-Branches |
| `vX.Y.Z` | `ghcr.io/carstenartur/taxonomy:v1.2.6` | Erfolgreicher Release-Tag-Build | Versioniertes Release |
| `sha-<hash>` | `ghcr.io/carstenartur/taxonomy:sha-abc1234` | Erfolgreicher veröffentlichter Build | Unveränderliche Deployment-Referenz |

Verwenden Sie in Produktion ein per Digest fixiertes Image oder einen geprüften `sha-`-Tag. Feature-Branches veröffentlichen keine Anwendungs-Images.

## 2. Schnellstart mit dem veröffentlichten Image

Nur zur lokalen Evaluierung:

```bash
docker pull ghcr.io/carstenartur/taxonomy:latest
docker run --rm -p 8080:8080 \
  -e TAXONOMY_ADMIN_PASSWORD='ein-eindeutiges-lokales-passwort' \
  ghcr.io/carstenartur/taxonomy:latest
```

Öffnen Sie <http://localhost:8080> und melden Sie sich als `admin` mit dem oben gesetzten Passwort an. Es gibt kein wiederverwendbares Entwicklungspasswort. Wird die Variable außerhalb der Produktion absichtlich weggelassen, liegt die erzeugte Zugangsdaten-Datei innerhalb des Containers; im Protokoll erscheint nur ihr Pfad. Für einen kurzlebigen Container ist ein expliziter Wert einfacher.

### Cloud-LLM

```bash
docker run --rm -p 8080:8080 \
  -e GEMINI_API_KEY=your-gemini-api-key \
  ghcr.io/carstenartur/taxonomy:latest
```

### Persistente HSQLDB-Daten

Ein Volume allein reicht nicht aus: Verweisen Sie HSQLDB auf eine Datei unterhalb des gemounteten Verzeichnisses und verwenden Sie den persistenten Schema-Modus.

```bash
docker run -d --name taxonomy-analyzer \
  -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=production,hsqldb \
  -e TAXONOMY_ADMIN_PASSWORD="$TAXONOMY_ADMIN_PASSWORD" \
  -e TAXONOMY_DATASOURCE_URL='jdbc:hsqldb:file:/app/data/taxonomydb;hsqldb.default_table_type=cached;hsqldb.write_delay_millis=0;shutdown=true' \
  -e TAXONOMY_DDL_AUTO=update \
  -v taxonomy-data:/app/data \
  ghcr.io/carstenartur/taxonomy:latest
```

Setzen Sie `TAXONOMY_ADMIN_PASSWORD` vor diesem Befehl in der Host-Umgebung
auf ein einmaliges Secret mit mindestens 16 Zeichen. Der Produktionsschutz
weist fehlende Werte und wörtliche `replace-with-...`-Platzhalter zurück.
Dieses direkte `docker run -p 8080:8080` bietet nur HTTP und braucht für
nichtlokalen Betrieb einen gesondert eingerichteten TLS-Reverse-Proxy.

Port 8080 verwendet unverschlüsseltes HTTP. Nutzen Sie für jede nicht-lokale Bereitstellung einen TLS-terminierenden Reverse-Proxy.

## 3. Empfohlenes Docker-Compose-Setup

Das Repository enthält [`docker-compose.prod.yml`](../../docker-compose.prod.yml). Dieses startet Taxonomy hinter Caddy mit automatischem HTTPS.

```bash
git clone https://github.com/carstenartur/Taxonomy.git
cd Taxonomy
cp .env.example .env
# .env bearbeiten: Domain, Administratorpasswort und KI-Einstellungen.
docker compose -f docker-compose.prod.yml up -d --build
```

Der lokale Quellcode-Build bezieht `jgit-storage-hibernate-core` anonym aus dem öffentlichen unveränderlichen Maven-Repository. Ein GitHub-Konto, Token oder Maven-`settings.xml`-Eintrag ist nicht erforderlich.

Der Stack enthält:

| Dienst | Zweck |
|---|---|
| `taxonomy` | Anwendung auf internem Port 8080, aus dem ausgecheckten Quellcode gebaut |
| `caddy` | HTTPS-Reverse-Proxy auf Port 80 und 443 |

### Veröffentlichtes Image verwenden

Ein Produktions-Deployment kann die lokale Übersetzung vermeiden, indem der `build`-Block durch eine unveränderliche veröffentlichte Image-Referenz ersetzt wird:

```yaml
services:
  taxonomy:
    image: ghcr.io/carstenartur/taxonomy@sha256:<geprüfter-digest>
```

Entfernen Sie bei Verwendung eines Images den vollständigen `build:`-Block.

### Datenbankspezifische Compose-Dateien

| Datei | Datenbank / Zweck |
|---|---|
| `docker-compose-postgres.yml` | PostgreSQL 16 |
| `docker-compose-mssql.yml` | Microsoft SQL Server 2022 |
| `docker-compose-oracle.yml` | Oracle Database 23 |
| `docker-compose-keycloak.yml` | PostgreSQL plus Keycloak/OIDC |
| `docker-compose.integration-test.yml` | Zwei Taxonomy-Instanzen plus Gitea |

Alle Compose-Dateien mit Quellcode-Build lösen die veröffentlichte Storage-Bibliothek anonym und ohne Build-Zugangsdaten auf.

## 4. Umgebungsvariablen

Laufzeitvariablen:

| Variable | Standard | Beschreibung |
|---|---|---|
| `TAXONOMY_ADMIN_PASSWORD` | außerhalb der Produktion leer; in Produktion erforderlich | Initiales lokales Administratorpasswort. Ein leerer Nicht-Produktionswert erzeugt eine eigentümergeschützte einmalige Zugangsdaten-Datei und protokolliert nur deren Pfad. |
| `LLM_PROVIDER` | automatisch erkannt | `GEMINI`, `OPENAI`, `DEEPSEEK`, `QWEN`, `LLAMA`, `MISTRAL`, `CUSTOM_OPENAI` oder `LOCAL_ONNX` |
| `GEMINI_API_KEY`, `OPENAI_API_KEY`, … | leer | Provider-Zugangsdaten |
| `TAXONOMY_EMBEDDING_ENABLED` | `false` | Semantische/KNN-Suche aktivieren |
| `TAXONOMY_EMBEDDING_MODEL_PROFILE` | `MULTILINGUAL_MINILM_L12` | Mehrsprachiger Modellvertrag; `BGE_SMALL_EN` wählt das bisherige englische Modell |
| `TAXONOMY_EMBEDDING_MODEL_DIR` | leer | Eingehängtes vorab bereitgestelltes Modellverzeichnis, passend zum Profil |
| `TAXONOMY_EMBEDDING_ALLOW_DOWNLOAD` | `false` | Laufzeitdownload bei aktivierten Embeddings ausdrücklich erlauben |
| `TAXONOMY_DATASOURCE_URL` | In-Memory-HSQLDB | JDBC-URL; für persistente HSQLDB eine Datei-URL setzen |
| `TAXONOMY_DDL_AUTO` | `create` | HSQLDB-Standard; persistentes HSQLDB verwendet `update`. PostgreSQL verwendet verwaltete Migrationen und `validate` |
| `TAXONOMY_JGIT_STORAGE_LEGACY_ADOPTION` | `false` | Einmalige Zustimmung zur geprüften Übernahme des früheren JGit-Schemas |
| `JAVA_OPTS` | Dockerfile-Standard | JVM-Heap-, GC- und Stack-Einstellungen |

Für den eingecheckten Quellcode-Build sind keine reinen Build-Zugangsdaten erforderlich.

Eingehängte Modellpakete gemäß der [gepinnten Modellbereitstellung](../testing/multilingual-model-provisioning.md) vorbereiten. `LOCAL_ONNX` allein aktiviert keine Embeddings; ein Profilwechsel erfordert einen Neuaufbau des semantischen Index und das Verwerfen zwischengespeicherter Vektoren.

Siehe [Konfigurationsreferenz](CONFIGURATION_REFERENCE.md) für alle Anwendungseigenschaften und [Hibernate-basierter JGit-Speicher](JGIT_STORAGE_HIBERNATE.md) für den Storage-Vertrag.

## 5. Persistenz und Backup

Der Produktions-Compose-Stack speichert veränderlichen Zustand unterhalb von `/app/data`:

| Pfad | Inhalt |
|---|---|
| `/app/data/taxonomydb*` | Dateibasierter HSQLDB-Katalog, Benutzer sowie Git-Pack-/Ref-/Reflog-Daten |
| `/app/data/lucene-index` | Hibernate-Search-/Lucene-Indizes |

Die in `docker-compose.prod.yml` konfigurierten benannten Volumes überleben
eine Container-Neuerstellung. Stoppen Sie alle Schreiber der HSQLDB-Dateien,
auch etwaige separat gestartete Taxonomy-Container. Die folgenden Befehle
stoppen den Compose-Dienst und ermitteln dessen tatsächlich gemountetes
Volume vor dem Dateisystem-Backup:

```bash
# Im Compose-Projektverzeichnis das tatsächlich gemountete, projektpräfixierte
# Volume ermitteln; ein bloßes "taxonomy-data" bezeichnet ein anderes Volume.
app_container=$(docker compose -f docker-compose.prod.yml ps -q taxonomy)
test -n "$app_container" || { echo 'Taxonomy-Container nicht gefunden' >&2; exit 1; }
data_volume=$(docker inspect --format '{{range .Mounts}}{{if eq .Destination "/app/data"}}{{.Name}}{{end}}{{end}}' "$app_container")
test -n "$data_volume" || { echo 'Taxonomy-Datenvolume nicht gefunden' >&2; exit 1; }
docker compose -f docker-compose.prod.yml stop taxonomy || exit 1
docker run --rm \
  --mount "type=volume,src=$data_volume,dst=/data,readonly" \
  -v "$(pwd)":/backup \
  alpine \
  tar czf /backup/taxonomy-backup.tar.gz -C /data . || {
    docker compose -f docker-compose.prod.yml start taxonomy
    exit 1
  }
docker compose -f docker-compose.prod.yml start taxonomy
```

Schlägt die Archivierung fehl, starten Sie den angehaltenen Dienst vor der
Fehlersuche erneut. Für einen Restore-Test entpacken Sie das Archiv auf einem
getrennten Host in ein **neues, leeres Volume**:

```bash
if docker volume inspect taxonomy-restore-data >/dev/null 2>&1; then
  echo 'Neuen, leeren Restore-Volume-Namen wählen' >&2; exit 1
fi
docker volume create taxonomy-restore-data
docker run --rm \
  --mount type=volume,src=taxonomy-restore-data,dst=/data \
  -v "$(pwd)":/backup:ro \
  alpine tar xzf /backup/taxonomy-backup.tar.gz -C /data
```

Erstellen Sie auf diesem separaten Host `restore-volume.override.yml` neben
der Compose-Datei, damit das wiederhergestellte Volume genutzt wird:

```yaml
volumes:
  taxonomy-data:
    external: true
    name: taxonomy-restore-data
```

Starten Sie `docker compose -f docker-compose.prod.yml -f restore-volume.override.yml
-p taxonomy-restore up -d` mit unabhängiger Domain und Secrets, damit die
Testinstanz nicht in die Quelle schreibt. Prüfen Sie Benutzer, Workspaces,
Suche und Git-Historie. Behalten Sie bei einem JGit-Core-Schema-Upgrade das
Backup, bis Refs, Commit-Verlauf und Pack-BLOB-Prüfsummen verifiziert sind.
Spielen Sie kein Backup über eine laufende Datenbank und mischen Sie keine
wiederhergestellten Datenbankdateien mit einem veralteten Index-Snapshot.

## 6. Health Checks

Das Image stellt die Spring-Boot-Actuator-Readiness intern bereit. Der Produktions-Compose-Dienst prüft:

```yaml
healthcheck:
  test: ["CMD", "wget", "-q", "-O", "/dev/null", "http://localhost:8080/actuator/health/readiness"]
  interval: 30s
  timeout: 10s
  retries: 5
  start_period: 90s
```

Nützliche Endpunkte:

| Endpunkt | Zweck |
|---|---|
| `GET /actuator/health/readiness` | Container-Readiness |
| `GET /api/status/startup` | Status der Kataloginitialisierung |
| `GET /api/ai-status` | Status des LLM-Providers |
| `GET /api/embedding/status` | Status des Embedding-Subsystems |

## 7. Aktualisierung persistenter Installationen

### Normales Upgrade

1. Schreibzugriffe stoppen und ein wiederherstellbares Backup erstellen.
2. Das geprüfte Image laden oder den Quellcode-Build neu erstellen.
3. Den Anwendungscontainer ohne Löschen des Daten-Volumes neu erstellen.
4. Readiness, Repository-Refs/-Verlauf und Suchfunktion prüfen.

Die HSQLDB- und PostgreSQL-Profile führen vor Hibernate den veröffentlichten Flyway-Migrationsstrom von `jgit-storage-hibernate-core` aus. Frische Installationen und bereits verwaltete Schemas werden automatisch migriert.

### Bestehendes Taxonomy-Schema vor der Bibliotheksnutzung

Eine Installation, die mit dem früher kopierten Backend erstellt wurde, wird **nicht** stillschweigend migriert. Der Start bricht sicher ab, bis ein Operator:

1. alle Schreibzugriffe gestoppt hat;
2. ein wiederherstellbares Backup erstellt hat;
3. repräsentative Repository-/Ref-Daten und geordnete Prüfsummen aller `git_packs.data`-BLOBs erfasst hat;
4. das [JGit-Storage-Übernahmeverfahren](JGIT_STORAGE_HIBERNATE.md#übernahme-einer-bestehenden-taxonomy-datenbank) geprüft hat.

Aktivieren Sie die Übernahme für genau einen Start:

```bash
TAXONOMY_JGIT_STORAGE_LEGACY_ADOPTION=true \
  docker compose -f docker-compose.prod.yml up -d
```

Prüfen Sie danach Refs, Commit-Verlauf, Reflogs und BLOB-Prüfsummen und setzen Sie die Variable anschließend wieder auf `false`. Doppelte Pack-Identitäten, partielle Schemata, überlange Pack-Erweiterungen und andere unsichere Formen werden vor dem Übernahme-DDL abgewiesen.

`jgit-storage-hibernate-core` veröffentlicht Flyway-Migrationen für HSQLDB und PostgreSQL. SQL Server und Oracle bleiben auf Taxonomys Hibernate-Schema-Verwaltung, bis passende Upstream-Migrationen und Tests mit realen Datenbanken veröffentlicht sind.

## 8. Lokal bauen

Der Dockerfile löst alle Maven-Abhängigkeiten anonym auf und verwendet den eingecheckten Maven Wrapper:

```bash
git clone https://github.com/carstenartur/Taxonomy.git
cd Taxonomy

docker build -t taxonomy-analyzer .
docker run --rm -p 8080:8080 taxonomy-analyzer
```

Ein GitHub-Token oder eine Maven-`settings.xml` mit Zugangsdaten darf für diesen Build nicht erforderlich sein. Der mehrstufige Build verwendet Maven mit Eclipse Temurin 21 und ein Temurin-21-JRE-Laufzeit-Image.
