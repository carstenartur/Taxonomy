# Taxonomy Architecture Analyzer — Konfigurationsreferenz

Dies ist das kanonische Verzeichnis der betriebsrelevanten Umgebungsvariablen der Taxonomy-Anwendung. Ein Vertragstest gleicht die Tabellen mit Spring-Property-Dateien, direkten `@Value`-Bindings, Feature-Flags und `@ConfigurationProperties` ab.

Umgebungsvariablen haben Vorrang vor `application*.properties`. `SPRING_DATASOURCE_URL` und `TAXONOMY_DATASOURCE_URL` setzen dieselbe Spring-Eigenschaft; pro Installation nur eine davon verwenden.

Profilabhängige Standards:

| Profil | Wesentliche Standards |
|---|---|
| Standard / `hsqldb` | HSQLDB im Speicher, `ddl-auto=create`, Lucene `local-heap`, Indexstrategie `sync`, SpringDoc aktiv |
| `production` | `ddl-auto=update`, Lucene `local-filesystem`, `write-sync`, Audit und Passwortwechsel aktiv, SpringDoc aus |
| `kubernetes` | `ddl-auto=validate`, standardmäßig `local-heap`, `write-sync`, Audit aktiv, SpringDoc aus |
| `keycloak` | lokale Benutzer-/Passwortverwaltung und direkte Word-Links aus |

Git-gestützte Einstellungen (`taxonomy.llm.*`, `taxonomy.analysis.min-score`, `taxonomy.dsl.*`, `taxonomy.limits.max-*`, `taxonomy.diagram.policy` und das Request-Limit) übernehmen die Umgebung bei ihrer ersten Initialisierung. Später gespeicherte Admin-Werte haben Vorrang, bis sie erneut geändert werden.

`DOMAIN`, `JAVA_OPTS` und OpenTelemetry-Agentvariablen gehören zu Deployment-Hüllen und stehen in deren Anleitungen. Zugangsdaten gehören in den Secret Store.

## Start, Profile und Lebenszyklus

| Variable | Property / Gültigkeit | Standard | Bedeutung |
|---|---|---|---|
| `PORT` | `server.port` | `8080` | HTTP-Port. |
| `SPRING_PROFILES_ACTIVE` | Spring-Profile | `hsqldb` | Kommagetrennte Profile, etwa `production,postgres`. |
| `TAXONOMY_INIT_ASYNC` | `taxonomy.init.async` | `false` | Öffnet den Port vor dem Katalogladen; Readiness wartet weiter. |
| `TAXONOMY_INIT_RELOAD_EXISTING` | `taxonomy.init.reload-existing` | `false` | Erzwingt einen destruktiven Katalogneuimport; nur nach Sicherung. |
| `TAXONOMY_LAZY_INIT` | `spring.main.lazy-initialization` | `true` | Initialisiert die meisten Beans erst bei Bedarf. |
| `TAXONOMY_THYMELEAF_CACHE` | `spring.thymeleaf.cache` | `true` | Template-Cache. |
| `TAXONOMY_SPRINGDOC_ENABLED` | SpringDoc API/UI | Basis `true`; Produktion/Kubernetes `false` | Schaltet OpenAPI und Swagger UI technisch ein oder aus. |
| `TAXONOMY_FORWARD_HEADERS_STRATEGY` | Kubernetes: `server.forward-headers-strategy` | `framework` | Verarbeitung vertrauenswürdiger Ingress-Header. |
| `TAXONOMY_SHUTDOWN_TIMEOUT` | Kubernetes: Shutdown-Phase | `30s` | Graceful-Shutdown-Frist. |
| `TAXONOMY_LOG_FILE` | Kubernetes: `logging.file.name` | leer | Optionale Logdatei; bei read-only Containern leer lassen. |
| `TAXONOMY_CATALOGUE_RESOURCE` | `taxonomy.catalogue.resource` | mitgelieferter C3-Katalog | Excel-Basiskatalog und Report-Provenienz. |
| `TAXONOMY_CATALOGUE_OVERLAY_ENABLED` | `taxonomy.catalogue.overlay.enabled` | `true` | Wendet das versionierte, strikt validierte JSON-Overlay auf die Excel-Basis an und gleicht persistierte Knoten idempotent ab. |
| `TAXONOMY_CATALOGUE_OVERLAY_RESOURCE` | `taxonomy.catalogue.overlay-resource` | `classpath:data/nato-taxonomy.json` | Overlay mit expliziten Elternkorrekturen, Produktrollen, Sekundärklassifikationen und Review-Metadaten. |
| `TAXONOMY_REPORT_TIME_ZONE` | `taxonomy.report.time-zone` | `Europe/Berlin` | Zeitzone der Berichte. |
| `GIT_COMMIT` | `git.commit.id` | leer | Bevorzugter Quell-Commit der Report-Provenienz. |
| `GITHUB_SHA` | Fallback für `git.commit.id` | `unknown` | CI-Fallback, wenn `GIT_COMMIT` fehlt. |
| `TAXONOMY_SCHEMA_MIGRATION_ENABLED` | `taxonomy.schema-migration.enabled` | `true` | Portable idempotente Schema-Vertragsmigrationen. |
| `TAXONOMY_COMMIT_INDEX_SEARCH_REBUILD_EMPTY` | `taxonomy.commit-index.search-rebuild-empty` | `true` | Bereinigt/baut den Commit-Suchindex bei leerer Projektion neu. |
| `TAXONOMY_JGIT_STORAGE_LEGACY_ADOPTION` | `taxonomy.jgit-storage.legacy-adoption` | `false` | Einmalige, fail-closed Altschema-Adoption nach Backup und Preflight. |
| `TAXONOMY_GIT_BOOTSTRAP` | `taxonomy.git.bootstrap` | `true` | Erzeugt bei leerem Systemrepository den ersten `draft`-Commit. |
| `TAXONOMY_FEATURES_MULTI_REPOSITORY_API_ENABLED` | `taxonomy.features.multi-repository-api.enabled` | `false` | Aktiviert die opt-in `/api/repositories`-Oberfläche. |

## Datenbank und Suche

Die HSQLDB-Poolvariablen gelten nur im HSQLDB-Profil; PostgreSQL, MSSQL und Oracle haben derzeit feste Profilwerte.

| Variable | Property / Gültigkeit | Standard | Bedeutung |
|---|---|---|---|
| `TAXONOMY_DATASOURCE_URL` | `spring.datasource.url` über DB-Profile | profilabhängig | Taxonomy-Alias der JDBC-URL. |
| `SPRING_DATASOURCE_URL` | direkte Spring-Bindung | leer | JDBC-URL, unter anderem im Helm-Chart; überschreibt den Alias. |
| `SPRING_DATASOURCE_USERNAME` | `spring.datasource.username` | profilabhängig | Datenbankkonto. |
| `SPRING_DATASOURCE_PASSWORD` | `spring.datasource.password` | Entwicklungswert des Profils | Produktiv aus einem Secret setzen. |
| `TAXONOMY_DB_MIN_IDLE` | HSQLDB Hikari | `1` | Minimale Idle-Verbindungen. |
| `TAXONOMY_DB_MAX_POOL_SIZE` | HSQLDB Hikari | `4` | Maximale Poolgröße. |
| `TAXONOMY_DB_CONNECTION_TIMEOUT_MS` | HSQLDB Hikari | `30000` | Connection-Timeout in Millisekunden. |
| `TAXONOMY_DDL_AUTO` | `spring.jpa.hibernate.ddl-auto` | Basis `create`; Produktion `update`; Kubernetes `validate` | Schemaaktion; `create` nie mit persistenten Daten. |
| `TAXONOMY_SEARCH_DIRECTORY_TYPE` | Hibernate Search | Basis/Kubernetes `local-heap`; Produktion `local-filesystem` | In-Memory- oder Dateispeicher; keine Mehrreplikat-Koordination. |
| `TAXONOMY_SEARCH_DIRECTORY_ROOT` | Hibernate Search | `/app/data/lucene-index` | Wurzel für `local-filesystem`. |
| `TAXONOMY_SEARCH_SYNC_STRATEGY` | Hibernate Search | Basis `sync`; Produktion/Kubernetes `write-sync` | Sichtbarkeits-/Durchsatzstrategie nach Transaktionen. |

## Generative LLMs und Analyse

`LOCAL_ONNX` ist nur lokale Semantik, kein generatives Chatmodell. Ohne expliziten Anbieter erfolgt Autoerkennung in der Reihenfolge Gemini, OpenAI, DeepSeek, Qwen, Llama, Mistral, `CUSTOM_OPENAI`.

| Variable | Property / Gültigkeit | Standard | Bedeutung |
|---|---|---|---|
| `LLM_PROVIDER` | `llm.provider` | leer | Anbieter oder Autoerkennung. |
| `LLM_MOCK` | `llm.mock` | `false` | Deterministische CI-/Screenshot-Daten, nicht für echte Entscheidungen. |
| `GEMINI_API_KEY` | `gemini.api.key` | leer | Gemini-Zugang. |
| `OPENAI_API_KEY` | `openai.api.key` | leer | OpenAI-Zugang. |
| `DEEPSEEK_API_KEY` | `deepseek.api.key` | leer | DeepSeek-Zugang. |
| `DASHSCOPE_API_KEY` | `qwen.api.key` | leer | Qwen/DashScope-Zugang. |
| `LLAMA_API_KEY` | `llama.api.key` | leer | Llama-Zugang. |
| `MISTRAL_API_KEY` | `mistral.api.key` | leer | Mistral-Zugang. |
| `CUSTOM_LLM_URL` | `custom.llm.url` | leer | HTTP(S)-Chat-Completions-URL mit Host, ohne Userinfo, endend auf `/chat/completions`. |
| `CUSTOM_LLM_MODEL` | `custom.llm.model` | leer | Erforderliche Modellkennung für `CUSTOM_OPENAI`. |
| `CUSTOM_LLM_API_KEY` | `custom.llm.api.key` | leer | Optionaler Bearer-Token; leer sendet keinen Authorization-Header. |
| `TAXONOMY_LLM_RPM` | Git-Einstellung `taxonomy.llm.rpm` | `5` | Ausgehende Requests je Anbieter und Minute. |
| `TAXONOMY_LLM_TIMEOUT_SECONDS` | Git-Einstellung `taxonomy.llm.timeout-seconds` | `30` | Timeout eines LLM-Aufrufs. |
| `TAXONOMY_ANALYSIS_MIN_SCORE` | Git-Einstellung `taxonomy.analysis.min-score` | `70` | Mindestwert 0–100 für gewöhnliche Architektursichten. |
| `TAXONOMY_ANALYSIS_PRODUCT_BATCH_SIZE` | `taxonomy.analysis.product.batch-size` | `10` | Maximale Zahl konkreter Information Products je unabhängiger Eignungsanfrage; vor der Batchbildung wird deterministisch sortiert. |
| `TAXONOMY_ANALYSIS_PRODUCT_MIN_SCORE` | `taxonomy.analysis.product.min-score` | `50` | Unabhängige Eignungsschwelle 0–100 für konkrete Produkte. Niedrigere Werte werden zu expliziten Nullen und können eine strukturierte Produktabdeckungslücke erzeugen. |
| `TAXONOMY_RATE_LIMIT_PER_MINUTE` | Git-Einstellung `taxonomy.rate-limit.per-minute` | `10` | Zugelassene LLM-Aufrufe je stabiler authentifizierter Identität und Minute; genau `0` deaktiviert, negative Werte wirken fehlersicher als `1`. |

Das eingehende Kontingent wird nach der Autorisierung geprüft. Lokale Benutzer werden über den kanonischen Benutzernamen zugeordnet; Keycloak-Browser- und Bearer-Zugriffe verwenden das unveränderliche Paar `iss`/`sub` und teilen daher auch nach einer Änderung von `preferred_username` dasselbe Budget. Forwarding-Header und Peer-Adressen sind keine Kontingentidentitäten; abgewiesene Aufrufe erzeugen keinen Zustand. Die begrenzten In-Memory-Zähler laufen bei Inaktivität ab und liefern HTTP `429` mit `Retry-After` und `Cache-Control: no-store`. Sie gelten je Anwendungsinstanz; für ein clusterweites Budget bei mehreren Replikaten ist deshalb ein verteilter äußerer Begrenzer erforderlich. Der gleiche Pfadvertrag gilt am Root-Kontext und unter einem Präfix wie `/taxonomy`.

## LLM Record/Replay

Produktiv normalerweise vollständig deaktiviert lassen.

| Variable | Property / Gültigkeit | Standard | Bedeutung |
|---|---|---|---|
| `LLM_RECORD` | `llm.record` | `false` | Zeichnet Live-Antworten auf. |
| `LLM_REPLAY` | `llm.replay` | `false` | Spielt anhand des Prompt-Hashes auf. |
| `LLM_REPLAY_FALLBACK` | `llm.replay.fallback` | `error` | Nur `live` erlaubt bei fehlender Aufnahme einen echten Aufruf. |
| `LLM_PRUNE` | `llm.prune` | `false` | Markiert nicht benutzte Manifest-Einträge als veraltet. |
| `LLM_PRUNE_DELETE` | `llm.prune.delete` | `false` | Löscht veraltete Aufzeichnungen. |
| `LLM_RECORDINGS_DIR` | `llm.recordings.dir` | automatisch erkannt | Explizites Aufnahmeverzeichnis; Manifest ist veränderlich. |

## Lokale Embeddings

| Variable | Property / Gültigkeit | Standard | Bedeutung |
|---|---|---|---|
| `TAXONOMY_EMBEDDING_ENABLED` | `embedding.enabled` | `false` | Aktiviert lokale Vektoren und semantische Suche. |
| `TAXONOMY_EMBEDDING_MODEL_DIR` | `embedding.model.dir` | leer | Eingehängtes vorab geladenes Modell. |
| `TAXONOMY_EMBEDDING_MODEL_NAME` | `embedding.model.name` | BAAI `bge-small-en-v1.5` | Entfernte Referenz oder lokaler Modellpfad. |
| `TAXONOMY_EMBEDDING_QUERY_PREFIX` | `embedding.query.prefix` | BGE-Präfix | Präfix asymmetrischer Suchanfragen. |
| `TAXONOMY_EMBEDDING_ALLOW_DOWNLOAD` | `embedding.allow-download` | `false` | Erlaubt Laufzeitdownload; Egress muss ebenfalls passen. |
| `TAXONOMY_EMBEDDING_INDEX_LOADER_THREADS` | `embedding.index.loader-threads` | `2`, mindestens `1` | Ladethreads der Mass-Indexing-Phasen. |
| `TAXONOMY_EMBEDDING_INDEX_BATCH_SIZE` | `embedding.index.batch-size` | `16`, mindestens `1` | Entitäten je Indexierungsbatch. |

## Copilot und Autopilot

Der historische Variablenname `TAXONOMY_AI_AUTOPILOT_MAX_ARCHITECTURE_NODES` bleibt unverändert. Die Property `taxonomy.ai.max-architecture-nodes` gilt sowohl für den manuellen Copilot als auch für den Autopiloten. Zusätzlich begrenzt `TAXONOMY_LIMITS_MAX_ARCHITECTURE_NODES` jede Portfolioanalyse; wirksam ist der kleinere Wert.

Profile: `STANDARD`, `FULL`, `EXHAUSTIVE`. Prüfdurchläufe: 1–3, bei `EXHAUSTIVE` mindestens 2. Durchläufe einer Operation laufen nacheinander; Koordinatorparallelität betrifft verschiedene Operationen.

| Variable | Property / Gültigkeit | Standard | Bedeutung |
|---|---|---|---|
| `TAXONOMY_AI_COST_POLICY` | `taxonomy.ai.cost-policy` | `METERED` | Autopilot verlangt ausdrücklich `UNMETERED`; manueller Copilot nicht. |
| `TAXONOMY_AI_COPILOT_PROFILE` | `taxonomy.ai.copilot.profile` | `FULL` | Standard des manuellen Copiloten. |
| `TAXONOMY_AI_AUTOPILOT_PROFILE` | `taxonomy.ai.autopilot.profile` | `EXHAUSTIVE` | Standard des Autopiloten. |
| `TAXONOMY_AI_COPILOT_VERIFICATION_PASSES` | `taxonomy.ai.copilot.verification-passes` | `1` | Manueller Standard, gültig 1–3. |
| `TAXONOMY_AI_AUTOPILOT_VERIFICATION_PASSES` | `taxonomy.ai.autopilot.verification-passes` | `2` | Autopilot-Standard, gültig 1–3. |
| `TAXONOMY_AI_AUTOPILOT_MAX_ARCHITECTURE_NODES` | `taxonomy.ai.max-architecture-nodes` | `50`, mindestens `1` | Betreiberobergrenze für Architekturknoten des manuellen Copiloten und Autopiloten; höhere Werte vergrößern Lauf, Snapshot und Diagramm, nicht die Projekt-Batchgröße. |
| `TAXONOMY_AI_AUTOPILOT_ENABLED` | `taxonomy.ai.autopilot.enabled` | `false` | Ausdrückliche Freigabe unbeaufsichtigter Läufe. |
| `TAXONOMY_AI_AUTOPILOT_ON_REQUIREMENT_SAVE` | `taxonomy.ai.autopilot.on-requirement-save` | `true` | Start nach neuer Anforderungsversion, sofern Autopilot vollständig bereit ist. |
| `TAXONOMY_AI_AUTOPILOT_PROVIDER` | `taxonomy.ai.autopilot.provider` | leer | Expliziter, vollständig konfigurierter Anbieter unbeaufsichtigter Arbeit. |
| `TAXONOMY_AI_AUTOPILOT_PROPOSE_SOLUTIONS` | `taxonomy.ai.autopilot.propose-solutions` | `true` | Erzeugt bei Nicht-`STANDARD` `PROPOSED`-Lösungen. |
| `TAXONOMY_AI_AUTOPILOT_PROPOSE_PRODUCTS` | `taxonomy.ai.autopilot.propose-products` | `true` | Erzeugt bei Nicht-`STANDARD` `CANDIDATE`-Produkte. |
| `TAXONOMY_AI_AUTOPILOT_MAX_PROJECT_REQUIREMENTS` | `taxonomy.ai.autopilot.max-project-requirements` | `50`, gültig 1–500 | Projektweite Batchgrenze; Überschreitungen werden abgewiesen. |
| `TAXONOMY_AI_PRODUCT_PROPOSALS_MINIMUM_COVERAGE` | `taxonomy.ai.product-proposals.minimum-coverage` | `25`, 0–100 | Mindestabdeckung für deterministische Produktvorschläge. |
| `TAXONOMY_AI_PRODUCT_PROPOSALS_MINIMUM_CONFIDENCE` | `taxonomy.ai.product-proposals.minimum-confidence` | `0.25`, 0–1 | Mindestanteil abgedeckter bestätigter Lösungsknoten. |
| `TAXONOMY_AI_MAXIMUM_RUNTIME_SECONDS` | `taxonomy.ai.maximum-runtime-seconds` | `1800`, mindestens wirksam `60` | Koordinator-Wartezeit; persistierte Jobs bleiben wiederaufnehmbar. |
| `TAXONOMY_AI_COORDINATOR_MAX_CONCURRENT_OPERATIONS` | `taxonomy.ai.coordinator.max-concurrent-operations` | `4`, gültig 1–64 | Parallel koordinierte Operationen, nicht Durchläufe einer Operation. |
| `TAXONOMY_AI_COORDINATOR_QUEUE_CAPACITY` | `taxonomy.ai.coordinator.queue-capacity` | `100`, gültig 1–10000 | In-Memory-Koordinatorqueue. |

Wirksame Richtlinie: `GET /api/ai-automation`. Verbindliche Zuordnungen, Zuständigkeiten, Produkte, Beschaffung und Branch-Merge bleiben menschliche Entscheidungen.

## Portfolio und Arbeitszustand

| Variable | Property / Gültigkeit | Standard | Bedeutung |
|---|---|---|---|
| `TAXONOMY_ANALYSIS_DRAFT_MAX_CHARACTERS` | `taxonomy.analysis-draft.max-characters` | `2000000`, mindestens `10000` | Maximale serialisierte JSON-Zeichen eines branchgebundenen Analyseentwurfs. |
| `TAXONOMY_CONTEXT_MAX_HISTORY` | `taxonomy.context.max-history` | `50` | Navigationseinträge je aktivem Arbeitsbereich. |
| `TAXONOMY_PORTFOLIO_MAX_IMPORT_REQUIREMENTS` | `taxonomy.portfolio.max-import-requirements` | `100`, mindestens `1` | Anforderungen je geprüftem Import. |
| `TAXONOMY_PORTFOLIO_MAX_IMPORT_CHARACTERS` | `taxonomy.portfolio.max-import-characters` | `500000`, mindestens `1` | Gesamttext je geprüftem Import. |
| `TAXONOMY_PORTFOLIO_MAX_ANALYSIS_BATCH` | `taxonomy.portfolio.max-analysis-batch` | `100`, mindestens `1` | Anforderungen je persistiertem Analysejob. |
| `TAXONOMY_PORTFOLIO_ANALYSIS_CLAIM_TIMEOUT_SECONDS` | `taxonomy.portfolio.analysis-claim-timeout-seconds` | `900`, mindestens `60` | Frist bis zur Claim-Wiederherstellung. |
| `TAXONOMY_PORTFOLIO_ANALYSIS_WORKER_CONCURRENCY` | `taxonomy.portfolio.analysis-worker-concurrency` | `1`, mindestens `1` | Parallele Portfolio-Worker. |
| `TAXONOMY_PORTFOLIO_ANALYSIS_WORKER_QUEUE_CAPACITY` | `taxonomy.portfolio.analysis-worker-queue-capacity` | `100`, mindestens `0` | In-Memory-Dispatch-Queue; Jobs bleiben persistiert. |
| `TAXONOMY_PORTFOLIO_ANALYSIS_WORKER_SHUTDOWN_SECONDS` | `taxonomy.portfolio.analysis-worker-shutdown-seconds` | `30`, mindestens `0` | Worker-Auslaufzeit. |
| `TAXONOMY_PORTFOLIO_SNAPSHOT_STALE_AFTER_DAYS` | `taxonomy.portfolio.snapshot-stale-after-days` | `30`, mindestens `1` | Altersgrenze der Stale-Snapshot-Metrik. |

## Sicherheit und Keycloak

`ADMIN_PASSWORD` schützt zusätzliche Actuator-/Legacy-Token-Prüfungen. `TAXONOMY_ADMIN_PASSWORD` initialisiert das lokale Konto `admin`. Ohne diesen Wert erzeugt ein Nicht-Produktionsstart ein einmaliges zufälliges Startpasswort; Produktion verlangt mindestens 16 nicht triviale Zeichen.

| Variable | Property / Gültigkeit | Standard | Bedeutung |
|---|---|---|---|
| `ADMIN_PASSWORD` | `admin.token` | leer | Optionaler `X-Admin-Token`/Bearer für sensible Zusatzprüfungen; ersetzt keine rollenbasierte Anmeldung. |
| `TAXONOMY_ADMIN_PASSWORD` | `taxonomy.admin-password` | leer außerhalb Produktion; dort erforderlich | Initiales lokales Administratorpasswort. |
| `TAXONOMY_LOGIN_RATE_LIMIT` | `taxonomy.security.login-rate-limit.enabled` | `true` | Login-Fehlversuchsbegrenzung. |
| `TAXONOMY_LOGIN_MAX_ATTEMPTS` | `taxonomy.security.login-rate-limit.max-attempts` | `5` | Fehlversuche bis zur Sperre. |
| `TAXONOMY_LOGIN_LOCKOUT_SECONDS` | `taxonomy.security.login-rate-limit.lockout-seconds` | `300` | Sperrdauer. |
| `TAXONOMY_REQUIRE_PASSWORD_CHANGE` | `taxonomy.security.require-password-change` | Basis `false`; Produktion `true` | Erzwingt Änderung temporärer lokaler Passwörter. |
| `TAXONOMY_SWAGGER_PUBLIC` | `taxonomy.security.swagger-public` | Basis `true`; Produktion/Kubernetes `false` | Öffentliche oder authentifizierte Swagger-Nutzung, falls SpringDoc aktiv ist. |
| `TAXONOMY_AUDIT_LOGGING` | `taxonomy.security.audit-logging` | Basis `false`; Produktion/Kubernetes `true` | Authentifizierungs-Audit. |
| `TAXONOMY_SECURITY_LOCAL_USERS_ENABLED` | `taxonomy.security.local-users-enabled` | Basis `true`; Keycloak `false` | Lokale Benutzer-/Rollendienste. |
| `TAXONOMY_SECURITY_CHANGE_PASSWORD_ENABLED` | `taxonomy.security.change-password-enabled` | Basis `true`; Keycloak `false` | Lokale Passwortänderung. |
| `TAXONOMY_DIRECT_WORD_ENABLED` | `taxonomy.document-templates.direct-word-enabled` | Basis `true`; Keycloak `false` | `ms-word:`-Links nur mit passender WebDAV-Authentifizierung. |
| `KEYCLOAK_CLIENT_ID` | OAuth2-Client | `taxonomy-app` | OIDC-Client-ID. |
| `KEYCLOAK_CLIENT_SECRET` | OAuth2-Client | leer | Vertrauliches Client-Secret. |
| `KEYCLOAK_ISSUER_URI` | Client und Resource Server | lokale Realm-URL | Issuer für Discovery und Tokenprüfung. |
| `KEYCLOAK_JWK_SET_URI` | Resource Server | lokale Zertifikats-URL | Expliziter JWK-Endpunkt. |
| `KEYCLOAK_ADMIN_URL` | `taxonomy.keycloak.admin-console-url` | `http://localhost:8180` | Basis der Kontoverwaltungsweiterleitung. |
| `KEYCLOAK_REALM` | `taxonomy.keycloak.realm` | `taxonomy` | Realm der Kontoverwaltung. |
| `TAXONOMY_KEYCLOAK_ROLE_CLAIM_PATH` | `taxonomy.keycloak.role-claim-path` | `realm_access.roles` | JWT-Pfad für die festen Rollen `ROLE_USER`, `ROLE_ARCHITECT`, `ROLE_ADMIN`; kein konfigurierbares Präfix. |

## DSL und externe Repositories

| Variable | Property / Gültigkeit | Standard | Bedeutung |
|---|---|---|---|
| `TAXONOMY_DSL_DEFAULT_BRANCH` | Git-Einstellung `taxonomy.dsl.default-branch` | `draft` | Initialer DSL-Branch. |
| `TAXONOMY_DSL_PROJECT_NAME` | Git-Einstellung `taxonomy.dsl.project-name` | `Taxonomy Architecture` | Name in DSL-/Exportmetadaten. |
| `TAXONOMY_DSL_AUTO_SAVE_INTERVAL` | Git-Einstellung `taxonomy.dsl.auto-save-interval` | `0` | Auto-Commit-Sekunden; `0` aus. |
| `TAXONOMY_DSL_REMOTE_URL` | Git-Einstellung `taxonomy.dsl.remote-url` | leer | Historisches DSL-Remote. |
| `TAXONOMY_DSL_REMOTE_TOKEN` | Git-Einstellung `taxonomy.dsl.remote-token` | leer | Token dieses Remotes. |
| `TAXONOMY_DSL_REMOTE_PUSH_ON_COMMIT` | Git-Einstellung `taxonomy.dsl.remote-push-on-commit` | `false` | Push nach jedem DSL-Commit. |
| `TAXONOMY_EXTERNAL_GIT_USERNAME` | direkte Deployment-Zugangsdaten | `oauth2` | Benutzername des administrativ konfigurierten kanonischen Remotes. |
| `TAXONOMY_EXTERNAL_GIT_TOKEN` | direkte Deployment-Zugangsdaten | leer | Write-only Fetch-/Push-Token, nicht persistiert. |

## Eingabe-, Architektur- und Dokumentgrenzen

Die AI-Knotengrenze kann die allgemeine Portfolio-Grenze wirksam nicht überschreiten. Bytewerte sind binär.

| Variable | Property / Gültigkeit | Standard | Bedeutung |
|---|---|---|---|
| `TAXONOMY_LIMITS_MAX_BUSINESS_TEXT` | Git-Einstellung `taxonomy.limits.max-business-text` | `5000` | Zeichen einer gewöhnlichen Anforderung. |
| `TAXONOMY_LIMITS_MAX_ARCHITECTURE_NODES` | Git-Einstellung/Portfolio-Limit `taxonomy.limits.max-architecture-nodes` | `50` | Allgemeine Architekturknoten-Obergrenze. |
| `TAXONOMY_LIMITS_MAX_EXPORT_NODES` | Git-Einstellung `taxonomy.limits.max-export-nodes` | `200` | Knoten je begrenztem Export. |
| `TAXONOMY_LIMITS_DOCUMENT_MAX_UPLOAD_BYTES` | `taxonomy.limits.document.max-upload-bytes` | `52428800` (50 MiB) | Uploadgröße. |
| `TAXONOMY_LIMITS_DOCUMENT_MAX_PDF_PAGES` | `taxonomy.limits.document.max-pdf-pages` | `500` | Verarbeitete PDF-Seiten. |
| `TAXONOMY_LIMITS_DOCUMENT_MAX_EXTRACTED_CHARACTERS` | `taxonomy.limits.document.max-extracted-characters` | `1000000` | Behaltene extrahierte Zeichen. |
| `TAXONOMY_LIMITS_DOCUMENT_MAX_CANDIDATES` | `taxonomy.limits.document.max-candidates` | `2000` | Kandidaten-/Provenienzobjekte. |
| `TAXONOMY_LIMITS_DOCUMENT_MAX_LLM_CHARACTERS` | `taxonomy.limits.document.max-llm-characters` | `200000` | Zeichen für die LLM-Stufe. |
| `TAXONOMY_LIMITS_DOCUMENT_MAX_DOCX_ENTRY_BYTES` | `taxonomy.limits.document.max-docx-entry-bytes` | `67108864` (64 MiB) | Entpackte Größe eines DOCX-Eintrags. |
| `TAXONOMY_LIMITS_DOCUMENT_MAX_DOCX_TEXT_BYTES` | `taxonomy.limits.document.max-docx-text-bytes` | `134217728` (128 MiB) | Gesamte entpackte DOCX-Text-/XML-Größe. |
| `TAXONOMY_LIMITS_DOCUMENT_MIN_DOCX_INFLATE_RATIO` | `taxonomy.limits.document.min-docx-inflate-ratio` | `0.01` | Mindestkompressionsverhältnis des Zip-Bomb-Schutzes. |
| `TAXONOMY_DIAGRAM_POLICY` | Git-Einstellung `taxonomy.diagram.policy` | `defaultImpact` | `defaultImpact`, `leafOnly`, `clustering` oder `trace`. |

## Beispiele

Manueller Copilot:

```bash
LLM_PROVIDER=GEMINI
GEMINI_API_KEY=secret
TAXONOMY_AI_COPILOT_PROFILE=FULL
TAXONOMY_AI_AUTOPILOT_MAX_ARCHITECTURE_NODES=50
TAXONOMY_LIMITS_MAX_ARCHITECTURE_NODES=50
```

Ausdrücklich ungemessener eigener Autopilot-Endpunkt:

```bash
LLM_PROVIDER=CUSTOM_OPENAI
CUSTOM_LLM_URL=http://llm-server:8000/v1/chat/completions
CUSTOM_LLM_MODEL=architecture-model
TAXONOMY_AI_COST_POLICY=UNMETERED
TAXONOMY_AI_AUTOPILOT_ENABLED=true
TAXONOMY_AI_AUTOPILOT_PROVIDER=CUSTOM_OPENAI
```

Docker Compose reicht `.env` an den Anwendungscontainer weiter. Bei Helm gehören Nicht-Geheimnisse nach `config`, Zugangsdaten ins referenzierte Secret und zusätzliche Werte nach `extraEnv`.
