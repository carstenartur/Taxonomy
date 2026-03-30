# Taxonomy Architecture Analyzer — Konfigurationsreferenz

Dieses Dokument ist die **kanonische, vollständige Liste** aller Umgebungsvariablen und
Anwendungseigenschaften, die vom Taxonomy Architecture Analyzer erkannt werden.  
Werte werden über Umgebungsvariablen (empfohlen für Produktion) oder in
`src/main/resources/application.properties` für die lokale Entwicklung gesetzt.

---

## LLM-Anbieter-Konfiguration

| Variable | Eigenschaft | Typ | Standard | Beschreibung |
|---|---|---|---|---|
| `LLM_PROVIDER` | `llm.provider` | Enum | *(Auto-Erkennung)* | Erzwingt einen bestimmten LLM-Anbieter. Werte: `GEMINI`, `OPENAI`, `DEEPSEEK`, `QWEN`, `LLAMA`, `MISTRAL`, `LOCAL_ONNX`. Wenn nicht gesetzt, wird der Anbieter automatisch anhand des ersten verfügbaren API-Schlüssels erkannt (siehe Prioritätsreihenfolge unten). |
| `LLM_MOCK` | `llm.mock` | Boolean | `false` | Wenn `true`, gibt der LLM-Service fest kodierte realistische Bewertungen zurück, anstatt einen echten LLM-Anbieter aufzurufen. Gedacht für CI-Pipelines, Screenshot-Generierung und Offline-Tests. Kein API-Schlüssel erforderlich, wenn der Mock-Modus aktiv ist. |
| `GEMINI_API_KEY` | `gemini.api.key` | String | *(leer)* | Google Gemini API-Schlüssel. Erhältlich unter [aistudio.google.com](https://aistudio.google.com). |
| `OPENAI_API_KEY` | `openai.api.key` | String | *(leer)* | OpenAI API-Schlüssel. |
| `DEEPSEEK_API_KEY` | `deepseek.api.key` | String | *(leer)* | DeepSeek API-Schlüssel. |
| `DASHSCOPE_API_KEY` | `qwen.api.key` | String | *(leer)* | Alibaba Cloud DashScope API-Schlüssel (Qwen-Modell). |
| `LLAMA_API_KEY` | `llama.api.key` | String | *(leer)* | Llama API-Schlüssel. |
| `MISTRAL_API_KEY` | `mistral.api.key` | String | *(leer)* | Mistral API-Schlüssel. |

### Prioritätsreihenfolge der automatischen LLM-Anbieter-Erkennung

Wenn `LLM_PROVIDER` **nicht gesetzt** ist, prüft die Anwendung verfügbare API-Schlüssel in dieser Reihenfolge:

1. **Gemini** — wenn `GEMINI_API_KEY` gesetzt ist
2. **OpenAI** — wenn `OPENAI_API_KEY` gesetzt ist
3. **DeepSeek** — wenn `DEEPSEEK_API_KEY` gesetzt ist
4. **Qwen** — wenn `DASHSCOPE_API_KEY` gesetzt ist
5. **Llama** — wenn `LLAMA_API_KEY` gesetzt ist
6. **Mistral** — wenn `MISTRAL_API_KEY` gesetzt ist

Der erste Treffer gewinnt. Wenn **kein Schlüssel** gesetzt ist und `LLM_PROVIDER` nicht `LOCAL_ONNX` ist, startet die Anwendung im **Nur-Browser-Modus** (alle Analysebewertungen = 0, Analyse-Schaltfläche deaktiviert).

Die explizite Einstellung `LLM_PROVIDER=LOCAL_ONNX` aktiviert die Offline-Semantikbewertung — kein API-Schlüssel oder Internetverbindung erforderlich (nach dem ersten Modell-Download).

---

## Lokales Embedding-Modell

| Variable | Eigenschaft | Typ | Standard | Beschreibung |
|---|---|---|---|---|
| `TAXONOMY_EMBEDDING_ENABLED` | `embedding.enabled` | Boolean | `true` | Auf `false` setzen, um Embedding und alle semantische Suche global zu deaktivieren. |
| `TAXONOMY_EMBEDDING_MODEL_DIR` | `embedding.model.dir` | Pfad | *(leer)* | Absoluter Pfad zu einem vorab heruntergeladenen Modellverzeichnis. Wenn leer, lädt DJL das Modell automatisch bei der ersten Verwendung herunter. |
| `TAXONOMY_EMBEDDING_MODEL_NAME` | `embedding.model.name` | String | `djl://ai.djl.huggingface/BAAI/bge-small-en-v1.5` | DJL-Modell-URL oder HuggingFace-Modellname. Nur ändern, wenn Sie ein anderes Embedding-Modell verwenden möchten. |
| `TAXONOMY_EMBEDDING_QUERY_PREFIX` | `embedding.query.prefix` | String | `Represent this sentence for searching relevant passages: ` | Präfix, das Abfragetexten für asymmetrisches Retrieval vorangestellt wird. Auf leeren String setzen, um es zu deaktivieren (z. B. bei Verwendung eines symmetrischen Modells). |
| `TAXONOMY_EMBEDDING_ALLOW_DOWNLOAD` | `embedding.allow-download` | Boolean | `true` | Auf `false` setzen, um den Download von Modellen zur Laufzeit zu verhindern. Wenn deaktiviert, muss ein lokales Modell über `TAXONOMY_EMBEDDING_MODEL_DIR` bereitgestellt werden. Empfohlen für CI-Umgebungen. |

### Vorab-Download des Embedding-Modells (Deployments ohne Internetzugang)

Für Umgebungen ohne Internetzugang laden Sie das Modell `bge-small-en-v1.5` vorab herunter:

```bash
# 1. Auf einem Rechner mit Internetzugang die Anwendung einmal starten, um den Download auszulösen:
LLM_PROVIDER=LOCAL_ONNX mvn spring-boot:run
# Das Modell wird unter ~/.djl.ai/cache/ zwischengespeichert (ca. 33 MB)

# 2. Das zwischengespeicherte Modellverzeichnis auf den Zielrechner kopieren:
scp -r ~/.djl.ai/cache/repo/model/ai/djl/huggingface/BAAI/bge-small-en-v1.5/ \
    target-machine:/opt/models/bge-small-en-v1.5/

# 3. Die Umgebungsvariable auf dem Zielrechner setzen:
export TAXONOMY_EMBEDDING_MODEL_DIR=/opt/models/bge-small-en-v1.5
```

Alternativ können Sie das Modell direkt von HuggingFace herunterladen:

```bash
# Modelldateien herunterladen
pip install huggingface-hub
huggingface-cli download BAAI/bge-small-en-v1.5 --local-dir /opt/models/bge-small-en-v1.5
```

---

## Authentifizierung & Sicherheit

Die Anwendung verwendet **Spring Security** mit formularbasierter Anmeldung (Browser) und HTTP-Basic-Authentifizierung (REST-Clients). Beim ersten Start wird ein Standard-`admin`-Benutzer erstellt.

### Standardbenutzer

| Benutzername | Passwort | Rollen |
|---|---|---|
| `admin` | Wert von `TAXONOMY_ADMIN_PASSWORD` (Standard: `admin`) | USER, ARCHITECT, ADMIN |

### Rollen und Berechtigungen

| Rolle | Berechtigungen |
|---|---|
| **ROLE_USER** | Alle API-Endpunkte lesen (`GET /api/**`), Analysen durchführen (`POST /api/analyze`, `POST /api/justify-leaf`), Exportieren (`POST /api/export/**`), GUI-Zugriff |
| **ROLE_ARCHITECT** | Alles aus ROLE_USER, plus Schreibzugriff auf Beziehungen (`POST/PUT/DELETE /api/relations/**`), DSL (`POST/PUT/DELETE /api/dsl/**`) und Git-Operationen (`POST/PUT/DELETE /api/git/**`) |
| **ROLE_ADMIN** | Alles aus ROLE_ARCHITECT, plus Admin-Endpunkte (`/admin/**`, `/api/admin/**`) |

### CSRF-Schutz

CSRF-Schutz ist für Browser-Sitzungen **aktiviert**, aber für `/api/**`-Pfade **deaktiviert**. Das bedeutet, dass REST-Clients, die über HTTP Basic authentifiziert sind, kein CSRF-Token einschließen müssen.

### REST-API-Authentifizierung

REST-Clients müssen sich über **HTTP Basic** authentifizieren:

```bash
curl -u admin:admin http://localhost:8080/api/taxonomy
```

### Öffentliche Endpunkte (keine Authentifizierung erforderlich)

- `/login`, `/error`
- `/actuator/health`, `/actuator/health/**`, `/actuator/info`
- `/v3/api-docs/**`, `/swagger-ui/**`, `/swagger-ui.html`
- Statische Assets: `/css/**`, `/js/**`, `/images/**`, `/webjars/**`

---

## Administration

| Variable | Eigenschaft | Typ | Standard | Beschreibung |
|---|---|---|---|---|
| `ADMIN_PASSWORD` | `admin.token` | String | *(muss für nicht-lokale Deployments gesetzt werden)* | Passwort zum Schutz von Admin-Panels (LLM-Diagnose, Prompt-Vorlagen, Kommunikationsprotokoll). **Für jedes Deployment, das über einen vollständig vertrauenswürdigen lokalen Rechner hinausgeht, MUSS dies auf einen starken, nicht-leeren Wert gesetzt werden.** Wenn leer gelassen, sind alle Admin-Panels für jeden zugänglich — dieses Verhalten ist nur für isolierte Entwicklungsumgebungen vorgesehen. Wenn gesetzt, müssen Benutzer auf die 🔒-Schaltfläche klicken und das Passwort eingeben. |
| `TAXONOMY_ADMIN_PASSWORD` | `taxonomy.admin-password` | String | `admin` | Passwort für den integrierten `admin`-Benutzer (Spring Security-Anmeldung). Wird für die Formularanmeldung (Browser) und HTTP-Basic-Authentifizierung (REST-Clients) verwendet. Ändern Sie dies vom Standard für jedes nicht-lokale Deployment. |

### Admin-Passwort konfigurieren

> **Sicherheitswarnung:** Das Ausführen der Anwendung mit einem leeren `ADMIN_PASSWORD` lässt alle Admin-Panels unauthentifiziert. **Verwenden Sie kein leeres Admin-Passwort auf einem internetexponierten, gemeinsam genutzten oder Produktionssystem.** Konfigurieren Sie in solchen Umgebungen immer einen starken, nicht-leeren Wert für `ADMIN_PASSWORD`.

**Lokale Entwicklung:**
```bash
ADMIN_PASSWORD=my-secret mvn spring-boot:run
```

**Docker:**
```bash
docker run -p 8080:8080 \
  -e ADMIN_PASSWORD=my-secret \
  -e GEMINI_API_KEY=your-key \
  ghcr.io/carstenartur/taxonomy:latest
```

**Render.com:**
Setzen Sie `ADMIN_PASSWORD` als geheime Umgebungsvariable im Render-Dashboard
(Dashboard → Service → Environment → Add Secret).

---

## Server-Konfiguration

| Variable | Eigenschaft | Typ | Standard | Beschreibung |
|---|---|---|---|---|
| `PORT` | `server.port` | Integer | `8080` | HTTP-Port, auf dem die Anwendung lauscht. Wird von Render und ähnlichen Plattformen gesetzt; fällt lokal auf `8080` zurück. |
| — | `spring.application.name` | String | `taxonomy-analyzer` | Anwendungsname (wird in Logs verwendet). |
| `TAXONOMY_THYMELEAF_CACHE` | `spring.thymeleaf.cache` | Boolean | `true` | Kompilierte Thymeleaf-Templates zwischenspeichern. Standard ist `true` (Produktion). Setzen Sie `TAXONOMY_THYMELEAF_CACHE=false` für lokale Entwicklung, um Template-Änderungen ohne Neustart zu übernehmen. |
| `TAXONOMY_LAZY_INIT` | `spring.main.lazy-initialization` | Boolean | `true` | Lazy-Bean-Initialisierung — Beans werden erst beim ersten Zugriff erstellt. Reduziert die Spring-Context-Startzeit erheblich (typische Einsparung: 30–50 s). `TaxonomyService` ist mit `@Lazy(false)` annotiert und wird unabhängig von dieser Einstellung immer sofort initialisiert. |
| `TAXONOMY_INIT_ASYNC` | `taxonomy.init.async` | Boolean | `false` | Wenn `true`, werden Taxonomiedaten in einem Hintergrund-Thread **nach** dem Start der Verbindungsannahme geladen. Empfohlen für Render und ähnliche PaaS-Plattformen, um „No open ports detected"-Deploy-Timeouts zu vermeiden. Standard ist `false` für Abwärtskompatibilität (synchrones Laden). |

---

## Datenbank-Konfiguration

Die Anwendung verwendet **Spring-Profile**, um zwischen Datenbank-Backends zu wechseln.
Setzen Sie `SPRING_PROFILES_ACTIVE`, um die Datenbank auszuwählen; der Standard ist `hsqldb`.

| Profil | Datenbank | Konfigurationsdatei |
|---|---|---|
| `hsqldb` (Standard) | HSQLDB (In-Memory oder Datei) | `application-hsqldb.properties` |
| `mssql` | Microsoft SQL Server | `application-mssql.properties` |
| `postgres` | PostgreSQL | `application-postgres.properties` |
| `oracle` | Oracle Database | `application-oracle.properties` |

### Allgemeine Datenbank-Eigenschaften

| Variable | Eigenschaft | Typ | Standard | Beschreibung |
|---|---|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `spring.profiles.active` | String | `hsqldb` | Datenbankprofil. Setzen Sie auf `mssql` für SQL Server, `postgres` für PostgreSQL oder `oracle` für Oracle Database. |
| `TAXONOMY_DATASOURCE_URL` | `spring.datasource.url` | String | *(profilabhängig)* | JDBC-URL. Jedes Profil bietet einen sinnvollen Standard; überschreiben Sie diesen für benutzerdefinierte Hosts. |
| `SPRING_DATASOURCE_USERNAME` | `spring.datasource.username` | String | *(profilabhängig)* | Datenbank-Benutzername. Standard: `sa` (MSSQL), `taxonomy` (PostgreSQL, Oracle), `sa` (HSQLDB). |
| `SPRING_DATASOURCE_PASSWORD` | `spring.datasource.password` | String | *(profilabhängig)* | Datenbank-Passwort. Standard: *(leer)* (HSQLDB), `taxonomy` (PostgreSQL, Oracle). **Erforderlich** für MSSQL (muss Komplexitätsregeln erfüllen). |
| `TAXONOMY_DDL_AUTO` | `spring.jpa.hibernate.ddl-auto` | String | `create` | Schema-Generierungsstrategie. `create` erstellt bei jedem Start neu (sicher für In-Memory-Standard). Setzen Sie auf `update` für dateibasierte Deployments, damit Daten bei Neustarts nicht gelöscht werden. |
| — | `spring.jpa.show-sql` | Boolean | `false` | Ob SQL-Anweisungen protokolliert werden sollen. |

### HSQLDB-Profil (Standard)

| Eigenschaft | Standard | Beschreibung |
|---|---|---|
| `spring.datasource.url` | `jdbc:hsqldb:mem:taxonomydb;DB_CLOSE_DELAY=-1` | In-Memory-HSQLDB. Überschreiben Sie für Festplatten-basierte Speicherung. |
| `spring.datasource.driver-class-name` | `org.hsqldb.jdbc.JDBCDriver` | HSQLDB-JDBC-Treiber. |
| `spring.datasource.type` | `SimpleDriverDataSource` | Umgeht HikariCP — kein Pool für In-Process-HSQLDB nötig. |
| `spring.jpa.database-platform` | `org.hibernate.dialect.HSQLDialect` | Expliziter Dialekt (erforderlich, da `SimpleDriverDataSource` keine JDBC-Metadaten bereitstellt). |

### MSSQL-Profil

Aktivieren mit `SPRING_PROFILES_ACTIVE=mssql`. Siehe [DATABASE_SETUP.md](DATABASE_SETUP.md#microsoft-sql-server-mssql) für Details.

| Eigenschaft | Standard | Beschreibung |
|---|---|---|
| `spring.datasource.url` | `jdbc:sqlserver://localhost:1433;databaseName=taxonomy;encrypt=false;trustServerCertificate=true` | SQL Server JDBC-URL. |
| `spring.datasource.driver-class-name` | `com.microsoft.sqlserver.jdbc.SQLServerDriver` | MSSQL-JDBC-Treiber. |
| `spring.datasource.type` | `com.zaxxer.hikari.HikariDataSource` | HikariCP-Verbindungspool. |
| `spring.jpa.database-platform` | `org.hibernate.dialect.SQLServerDialect` | SQL Server-Dialekt. |
| `spring.datasource.hikari.maximum-pool-size` | `10` | Maximale HikariCP-Verbindungen. |
| `spring.datasource.hikari.connection-timeout` | `30000` | Verbindungs-Timeout (ms). |
| `spring.datasource.hikari.initialization-fail-timeout` | `60000` | Start-Wiederholungs-Timeout (ms). |

### PostgreSQL-Profil

Aktivieren mit `SPRING_PROFILES_ACTIVE=postgres`. Siehe [DATABASE_SETUP.md](DATABASE_SETUP.md#postgresql) für Details.

| Eigenschaft | Standard | Beschreibung |
|---|---|---|
| `spring.datasource.url` | `jdbc:postgresql://localhost:5432/taxonomy` | PostgreSQL JDBC-URL. |
| `spring.datasource.driver-class-name` | `org.postgresql.Driver` | PostgreSQL-JDBC-Treiber. |
| `spring.datasource.type` | `com.zaxxer.hikari.HikariDataSource` | HikariCP-Verbindungspool. |
| `spring.jpa.database-platform` | `org.hibernate.dialect.PostgreSQLDialect` | PostgreSQL-Dialekt. |
| `spring.datasource.hikari.maximum-pool-size` | `10` | Maximale HikariCP-Verbindungen. |
| `spring.datasource.hikari.connection-timeout` | `30000` | Verbindungs-Timeout (ms). |
| `spring.datasource.hikari.initialization-fail-timeout` | `60000` | Start-Wiederholungs-Timeout (ms). |

### Oracle-Profil

Aktivieren mit `SPRING_PROFILES_ACTIVE=oracle`. Siehe [DATABASE_SETUP.md](DATABASE_SETUP.md#oracle-database) für Details.

| Eigenschaft | Standard | Beschreibung |
|---|---|---|
| `spring.datasource.url` | `jdbc:oracle:thin:@localhost:1521/taxonomy` | Oracle JDBC-URL (Thin-Treiber). |
| `spring.datasource.driver-class-name` | `oracle.jdbc.OracleDriver` | Oracle-JDBC-Treiber. |
| `spring.datasource.type` | `com.zaxxer.hikari.HikariDataSource` | HikariCP-Verbindungspool. |
| `spring.jpa.database-platform` | `org.hibernate.dialect.OracleDialect` | Oracle-Dialekt. |
| `spring.datasource.hikari.maximum-pool-size` | `10` | Maximale HikariCP-Verbindungen. |
| `spring.datasource.hikari.connection-timeout` | `30000` | Verbindungs-Timeout (ms). |
| `spring.datasource.hikari.initialization-fail-timeout` | `60000` | Start-Wiederholungs-Timeout (ms). |

---

## Hibernate Search / Lucene

| Variable | Eigenschaft | Typ | Standard | Beschreibung |
|---|---|---|---|---|
| — | `spring.jpa.properties.hibernate.search.enabled` | Boolean | `true` | Hibernate-Search-Integration aktivieren. |
| — | `spring.jpa.properties.hibernate.search.backend.type` | String | `lucene` | Such-Backend-Typ. |
| `TAXONOMY_SEARCH_DIRECTORY_TYPE` | `spring.jpa.properties.hibernate.search.backend.directory.type` | String | `local-heap` | Index-Speicher. `local-heap` = In-Memory (lokale Entwicklung / Tests). Setzen Sie auf `local-filesystem` für Produktions-Deployments mit Festplatten-basierter Speicherung. |
| `TAXONOMY_SEARCH_DIRECTORY_ROOT` | `spring.jpa.properties.hibernate.search.backend.directory.root` | String | `/app/data/lucene-index` | Stammverzeichnis für die Lucene-Indexdateien (wird nur verwendet, wenn `TAXONOMY_SEARCH_DIRECTORY_TYPE=local-filesystem`). |
| — | `spring.jpa.properties.hibernate.search.configuration_property_checking.strategy` | String | `ignore` | Unterdrückt die `HSEARCH000568`-Warnung über ein ungenutztes `directory.root`, wenn `directory.type=local-heap`. |

---

## Ratenbegrenzung

| Variable | Eigenschaft | Typ | Standard | Beschreibung |
|---|---|---|---|---|
| `TAXONOMY_RATE_LIMIT_PER_MINUTE` | `taxonomy.rate-limit.per-minute` | Integer | `10` | Maximale LLM-gestützte API-Anfragen pro IP pro Minute. Schützt vor Quota-Erschöpfung bei Gemini/OpenAI. Auf `0` setzen, um die Ratenbegrenzung zu deaktivieren. |

Geschützte Endpunkte: `POST /api/analyze`, `GET /api/analyze-stream`, `GET /api/analyze-node`, `POST /api/justify-leaf`.

Wenn das Limit überschritten wird, gibt der Server HTTP `429 Too Many Requests` zurück. Die Client-IP wird aus dem `X-Forwarded-For`-Header extrahiert (mit `X-Real-IP`-Fallback).

---

## Brute-Force-Schutz bei der Anmeldung

| Variable | Eigenschaft | Typ | Standard | Beschreibung |
|---|---|---|---|---|
| `TAXONOMY_LOGIN_RATE_LIMIT` | `taxonomy.security.login-rate-limit.enabled` | Boolean | `true` | Brute-Force-Schutz bei der Anmeldung aktivieren/deaktivieren. |
| `TAXONOMY_LOGIN_MAX_ATTEMPTS` | `taxonomy.security.login-rate-limit.max-attempts` | Integer | `5` | Maximale fehlgeschlagene Anmeldeversuche vor Sperrung. |
| `TAXONOMY_LOGIN_LOCKOUT_SECONDS` | `taxonomy.security.login-rate-limit.lockout-seconds` | Integer | `300` | Sperrdauer in Sekunden nach Überschreitung der maximalen Versuche. |

Bei Sperrung gibt der Server HTTP `423 Locked` mit einem JSON-Body zurück, der die Wartezeit enthält. Deaktivieren Sie mit `TAXONOMY_LOGIN_RATE_LIMIT=false` für die Entwicklung.

---

## Passwort-Richtlinie

| Variable | Eigenschaft | Typ | Standard | Beschreibung |
|---|---|---|---|---|
| `TAXONOMY_REQUIRE_PASSWORD_CHANGE` | `taxonomy.security.require-password-change` | Boolean | `false` | Wenn `true`, werden Benutzer mit dem Standardpasswort zu `/change-password` weitergeleitet. |

---

## Swagger-Zugriffskontrolle

| Variable | Eigenschaft | Typ | Standard | Beschreibung |
|---|---|---|---|---|
| `TAXONOMY_SWAGGER_PUBLIC` | `taxonomy.security.swagger-public` | Boolean | `true` | Wenn `true`, ist die Swagger-UI ohne Authentifizierung zugänglich. In Produktion auf `false` setzen, um Authentifizierung zu erfordern. |

---

## Sicherheits-Audit-Protokollierung

| Variable | Eigenschaft | Typ | Standard | Beschreibung |
|---|---|---|---|---|
| `TAXONOMY_AUDIT_LOGGING` | `taxonomy.security.audit-logging` | Boolean | `false` | Wenn `true`, werden Authentifizierungsereignisse protokolliert (Anmeldeerfolge/-fehler, Benutzerverwaltungsaktionen). |

---

## Keycloak-/OIDC-Konfiguration

Die Keycloak-Authentifizierung wird über das `keycloak`-Spring-Profil aktiviert (`SPRING_PROFILES_ACTIVE=keycloak`). Wenn aktiv, werden Form-Login und HTTP Basic durch OAuth2-Login (Browser) und JWT-Bearer-Tokens (REST-API) ersetzt.

| Variable | Eigenschaft | Standard | Beschreibung |
|---|---|---|---|
| `KEYCLOAK_ISSUER_URI` | `spring.security.oauth2.client.provider.keycloak.issuer-uri` | `http://localhost:8180/realms/taxonomy` | Keycloak-Realm-Issuer-URI. Wird für OIDC-Discovery und JWT-Validierung verwendet. |
| `KEYCLOAK_CLIENT_ID` | `spring.security.oauth2.client.registration.keycloak.client-id` | `taxonomy-app` | OAuth2-Client-ID, die in Keycloak registriert ist. |
| `KEYCLOAK_CLIENT_SECRET` | `spring.security.oauth2.client.registration.keycloak.client-secret` | *(leer)* | OAuth2-Client-Secret. **Muss für Produktion gesetzt werden.** |
| `KEYCLOAK_JWK_SET_URI` | `spring.security.oauth2.resourceserver.jwt.jwk-set-uri` | `http://localhost:8180/realms/taxonomy/protocol/openid-connect/certs` | JWKS-Endpunkt für JWT-Signaturvalidierung. |
| `KEYCLOAK_ADMIN_URL` | `taxonomy.keycloak.admin-console-url` | `http://localhost:8180` | Basis-URL der Keycloak Admin Console (wird für Passwortänderungs-Weiterleitungen verwendet). |
| `KEYCLOAK_REALM` | `taxonomy.keycloak.realm` | `taxonomy` | Keycloak-Realm-Name. Wird für Account-Console-Weiterleitungs-URLs verwendet. |

**Eigenschaften, die automatisch im `keycloak`-Profil gesetzt werden:**

| Eigenschaft | Wert | Auswirkung |
|---|---|---|
| `taxonomy.security.local-users-enabled` | `false` | Deaktiviert `UserManagementController` (Benutzer-CRUD über REST-API) |
| `taxonomy.security.change-password-enabled` | `false` | Deaktiviert lokalen `ChangePasswordController` (Weiterleitung zu Keycloak) |

Siehe [Keycloak- & SSO-Einrichtung](KEYCLOAK_SETUP.md) für vollständige Einrichtungsanweisungen und [Keycloak-Migrationsanleitung](KEYCLOAK_MIGRATION.md) für die Migration von Form-Login.

---

## OpenAPI / Swagger UI

Die Anwendung stellt interaktive API-Dokumentation über [springdoc-openapi](https://springdoc.org/) bereit.

| Variable | Eigenschaft | Typ | Standard | Beschreibung |
|---|---|---|---|---|
| `TAXONOMY_SPRINGDOC_ENABLED` | `springdoc.api-docs.enabled` / `springdoc.swagger-ui.enabled` | Boolean | `true` | Aktivieren oder Deaktivieren der `/swagger-ui.html`- und `/v3/api-docs`-Endpunkte. In Produktion auf `false` setzen, um Speicher zu sparen und die Angriffsfläche zu reduzieren. |

| URL | Beschreibung |
|---|---|
| `/swagger-ui.html` | Interaktive Swagger-UI |
| `/v3/api-docs` | OpenAPI 3.0 JSON-Spezifikation |

---

## Spring Boot Actuator (Überwachung)

Die Anwendung enthält Spring Boot Actuator mit Micrometer Prometheus für HTTP-basierte Überwachung.
Dies ist der moderne Ersatz für JMX (das auf dem Render Free Tier nicht verfügbar ist).

### Verfügbare Endpunkte

| Endpunkt | Authentifizierung erforderlich | Beschreibung |
|---|---|---|
| `GET /actuator/health` | Nein | Gesundheitsstatus der Anwendung (immer öffentlich — wird von Render-Gesundheitsprüfungen verwendet) |
| `GET /actuator/health/liveness` | Nein | Liveness-Probe (Kubernetes-kompatibel) |
| `GET /actuator/health/readiness` | Nein | Readiness-Probe (Kubernetes-kompatibel) |
| `GET /actuator/info` | Nein | Anwendungsinformationen (Name, Version, Java, Betriebssystem) |
| `GET /actuator/metrics` | Ja (X-Admin-Token) | Liste aller verfügbaren Metriknamen |
| `GET /actuator/metrics/{name}` | Ja (X-Admin-Token) | Wert einer bestimmten Metrik (z. B. `jvm.memory.used`) |
| `GET /actuator/prometheus` | Ja (X-Admin-Token) | Alle Metriken im Prometheus-Textformat (zum Scraping) |

### Zugriff auf geschützte Endpunkte

Endpunkte mit „Ja" erfordern den `X-Admin-Token`-Header, wenn `ADMIN_PASSWORD` konfiguriert ist:

```bash
# Gesundheit (öffentlich)
curl https://your-app.onrender.com/actuator/health

# Metriken (erfordert Admin-Token)
curl -H "X-Admin-Token: your-admin-password" \
     https://your-app.onrender.com/actuator/metrics

# Prometheus-Scrape
curl -H "X-Admin-Token: your-admin-password" \
     https://your-app.onrender.com/actuator/prometheus

# Bestimmte Metrik
curl -H "X-Admin-Token: your-admin-password" \
     https://your-app.onrender.com/actuator/metrics/jvm.memory.used
```

Wenn `ADMIN_PASSWORD` nicht gesetzt ist, sind alle Actuator-Endpunkte ohne Authentifizierung zugänglich
(abwärtskompatibel mit lokaler Entwicklung).

### JMX-Äquivalente

| JMX MBean | Actuator-Äquivalent |
|---|---|
| `java.lang:type=Memory` | `/actuator/metrics/jvm.memory.used`, `/actuator/metrics/jvm.memory.max` |
| `java.lang:type=GarbageCollector` | `/actuator/metrics/jvm.gc.pause`, `/actuator/metrics/jvm.gc.memory.promoted` |
| `java.lang:type=Threading` | `/actuator/metrics/jvm.threads.live`, `/actuator/metrics/jvm.threads.peak` |
| `java.lang:type=OperatingSystem` | `/actuator/metrics/process.cpu.usage`, `/actuator/metrics/system.cpu.usage` |
| `java.lang:type=Runtime` | `/actuator/info`, `/actuator/metrics/process.uptime` |
| Benutzerdefinierte MBeans | `/actuator/prometheus` (alle Metriken in einem Scrape) |

### Taxonomy-Gesundheitsindikator

Der `/actuator/health`-Endpunkt enthält eine benutzerdefinierte `taxonomy`-Komponente, die Folgendes meldet:

```json
{
  "status": "UP",
  "components": {
    "taxonomy": {
      "status": "UP",
      "details": {
        "initStatus": "Async taxonomy initialization complete.",
        "initialized": true,
        "heapUsedMB": 256,
        "heapMaxMB": 512,
        "heapUsagePercent": 50
      }
    }
  }
}
```

Gesundheitskomponentendetails werden nur angezeigt, wenn `management.endpoint.health.show-components=when-authorized`
und die Anfrage einen gültigen `X-Admin-Token`-Header enthält.

---

## Laufzeit-Einstellungen

Zusätzlich zu Umgebungsvariablen können mehrere Einstellungen zur Laufzeit über die Einstellungen-API geändert werden, ohne die Anwendung neu zu starten:

| Einstellungsschlüssel | Typ | Standard | Beschreibung |
|---|---|---|---|
| `llm.rpm` | int | `5` | Ausgehende LLM-API-Anfragen pro Minute |
| `llm.timeout.seconds` | int | `30` | HTTP-Lese-Timeout für LLM-Aufrufe |
| `rate-limit.per-minute` | int | `10` | Eingehende Ratenbegrenzung für Analyse-Endpunkte |
| `analysis.min-relevance-score` | int | `70` | Mindestbewertungsschwelle für Analyseergebnisse |
| `dsl.default-branch` | string | `draft` | Aktiver DSL-Branch für die Materialisierung |
| `dsl.project-name` | string | `Taxonomy Architecture` | Projekt-Anzeigename |
| `dsl.auto-save.interval-seconds` | int | `0` | Auto-Speicher-Häufigkeit (0 = deaktiviert) |
| `dsl.remote.url` | string | *(leer)* | Remote-Git-URL für Push/Pull |
| `dsl.remote.token` | string | *(leer)* | Remote-Git-Authentifizierungstoken |
| `dsl.remote.push-on-commit` | boolean | `false` | Automatisches Pushen nach lokalen Commits |
| `limits.max-business-text` | int | `5000` | Maximale Zeichen im Anforderungstext |
| `limits.max-architecture-nodes` | int | `50` | Maximale Knoten in der Architekturansicht |
| `limits.max-export-nodes` | int | `200` | Maximale Knoten beim Export |
| `diagram.policy` | string | `defaultImpact` | Diagramm-Auswahlrichtlinie: `defaultImpact`, `leafOnly`, `clustering` oder `trace` |

Siehe [Einstellungen](PREFERENCES.md) für die REST-API und den Audit-Trail.

---

## Framework-Import-Konfiguration

Die Framework-Import-Pipeline wird über Zuordnungsprofile konfiguriert. Verfügbare Profile können zur Laufzeit aufgelistet werden:

```bash
curl -u admin:password http://localhost:8080/api/import/profiles
```

| Profil-ID | Framework | Dateiformat |
|---|---|---|
| `uaf` | UAF / DoDAF | XMI / XML |
| `apqc` | APQC PCF | CSV |
| `apqc-excel` | APQC PCF | XLSX (Excel) |
| `c4` | C4 / Structurizr | DSL |

Für den Framework-Import werden keine zusätzlichen Umgebungsvariablen benötigt — Profile werden beim Start automatisch registriert.

Siehe [Framework-Import](FRAMEWORK_IMPORT.md) für detaillierte Zuordnungstabellen und Verwendung.
