# Deployment-Leitfaden

Verteilte Analyse mit externem Artemis: [Bereitstellung, TLS/Secrets, Worker-Skalierung, HA und Administration](OPERATIONS_GUIDE.md#betrieb-mit-externem-artemis-broker).

Dieses Dokument beschreibt die sicherheits- und persistenzrelevanten Mindestanforderungen. Die vollständigen, technisch maßgeblichen Details stehen im [englischen Deployment Guide](../en/DEPLOYMENT_GUIDE.md).

## Unterstützte Betriebsarten

| Modus | Datenhaltung | Zweck |
|---|---|---|
| Lokaler Maven-/Docker-Start | standardmäßig flüchtig | Entwicklung und Evaluation |
| `docker-compose.prod.yml` | dateibasierte HSQLDB und Lucene unter `/app/data` | kleine kontrollierte Produktivinstallation |
| `production,postgres` mit `TAXONOMY_DDL_AUTO=validate` | externe Datenbank mit freigegebenen Migrationen plus persistenter Lucene-Pfad | empfohlener Mehrbenutzerbetrieb |
| `mssql` / `oracle` | externe Testdatenbank | Kompatibilitätsevaluation; produktiver Migrations-, Upgrade- und Restore-Vertrag ausstehend |
| Render Free | flüchtig | öffentliche Demonstration, nicht kollaborative Produktion |

## Produktivstart mit Docker und Caddy

```bash
cp .env.example .env
# DOMAIN und ein eindeutiges langes TAXONOMY_ADMIN_PASSWORD eintragen
docker compose -f docker-compose.prod.yml up -d --build
```

Der Stack aktiviert `production,hsqldb`, veröffentlicht Port 8080 nicht am Host und konfiguriert:

```text
TAXONOMY_DATASOURCE_URL=jdbc:hsqldb:file:/app/data/taxonomydb;hsqldb.default_table_type=cached;shutdown=true
TAXONOMY_DDL_AUTO=update
TAXONOMY_SEARCH_DIRECTORY_TYPE=local-filesystem
TAXONOMY_SEARCH_DIRECTORY_ROOT=/app/data/lucene-index
```

Ein Produktionsstart wird verweigert, wenn das Administratorpasswort fehlt, einem dokumentierten Platzhalter entspricht oder kürzer als 16 Zeichen ist.

Das generische `production`-Profil verwendet für die HSQLDB-Compose-Basis
`ddl-auto=update`. Für PostgreSQL setzen Sie ausdrücklich
`TAXONOMY_DDL_AUTO=validate`; die freigegebenen JGit-Core- und Taxonomy-Flyway-
Migrationen laufen vor der Hibernate-Validierung. Ein Profilwechsel kopiert
keine HSQLDB-Daten. Siehe [Datenbank-Einrichtung](DATABASE_SETUP.md#wechsel-von-hsqldb-zu-postgresql).

## Sicherheitsanforderungen

- TLS über Caddy, Ingress oder einen freigegebenen Reverse Proxy terminieren.
- `TAXONOMY_SWAGGER_PUBLIC=false`, Audit-Logging und Login-Limitierung aktiv lassen.
- USER, ARCHITECT und ADMIN nach dem Minimalprinzip vergeben.
- Browser-Sitzungen benötigen für schreibende API-Aufrufe einen CSRF-Token.
- Diagnose, Prompts, Logs und Einstellungen sind ausschließlich über `ROLE_ADMIN` zugänglich.
- Container über Image-Digest statt nur über `latest` festlegen.

## Lokale KI und Netzisolation

`LLM_PROVIDER=LOCAL_ONNX` bedeutet lokale KI-Ausführung, aber nicht automatisch vollständige Netzisolation. Dafür zusätzlich:

- Modellrevision und SHA-256 festhalten;
- Modelle vorab bereitstellen;
- `TAXONOMY_EMBEDDING_ALLOW_DOWNLOAD=false` setzen;
- lokale WebJars verwenden;
- Betrieb mit gesperrtem ausgehendem Netzwerk testen.

## Abnahme

Vor dem Go-live die [Deployment-Checkliste](DEPLOYMENT_CHECKLIST.md) vollständig mit Nachweisen ausfüllen. Zwingend sind Neustart-Persistenztest, Backup-Restore, Rollen-/CSRF-Tests, Accessibility-Gate, reale Analyse, Workspace-/TaxDSL-Prüfung und Exporttests.
