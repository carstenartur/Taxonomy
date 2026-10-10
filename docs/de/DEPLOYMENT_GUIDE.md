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

Embeddings sind standardmäßig deaktiviert; für lokale Suche/Bewertung muss zusätzlich `TAXONOMY_EMBEDDING_ENABLED=true` gesetzt werden. Das Standardprofil `MULTILINGUAL_MINILM_L12` und das passende eingehängte Paket werden in der [gepinnten Modellbereitstellung](../testing/multilingual-model-provisioning.md) beschrieben. `TAXONOMY_EMBEDDING_MODEL_PROFILE=BGE_SMALL_EN` wählt ausdrücklich das bisherige englische Modell. Bei einem Profilwechsel den semantischen Index neu aufbauen und zwischengespeicherte Vektoren verwerfen.

## Abnahme

Vor dem Go-live die [Deployment-Checkliste](DEPLOYMENT_CHECKLIST.md) vollständig mit Nachweisen ausfüllen. Zwingend sind Neustart-Persistenztest, Backup-Restore, Rollen-/CSRF-Tests, Accessibility-Gate, reale Analyse, Workspace-/TaxDSL-Prüfung und Exporttests.

## Optionale Funktionspakete und externe Plugins

Das Release-Archiv `taxonomy-<version>-distribution.tar.gz` enthält `app.jar` sowie
`features/` mit sechs Fach-JARs und `plugins/` mit dem separat gebauten Mermaid-Adapter.
Alle drei gemeinsam ausliefern. Die Host-JAR allein enthält absichtlich nur den festen
Kern. Docker und native Pakete enthalten die Vollausstattung; native Starter beziehen
beide Verzeichnisse auf die Installation, unabhängig vom Arbeitsverzeichnis.
Bei einem heruntergeladenen Release 1.5.0 zuerst die Prüfsummen prüfen und dann
aus dem entpackten Verzeichnis starten:

```sh
sha256sum --check taxonomy-1.5.0-distribution.tar.gz.sha256
tar -xzf taxonomy-1.5.0-distribution.tar.gz
cd taxonomy-1.5.0
sha256sum --check SHA256SUMS
java -jar app.jar
```

Für einen Quellcode-Build nach `./mvnw package` diese Befehle im Repository-Stamm
ausführen. Die Version wird aus dem Checkout gelesen, damit Release- und
Entwicklungs-Builds den tatsächlichen Maven-Artefaktnamen verwenden:

```sh
taxonomy_version=$(./mvnw -q -DforceStdout help:evaluate -Dexpression=project.version)
cd taxonomy-app/target
java -Dloader.path=features -Dtaxonomy.plugins.directory=plugins -jar "taxonomy-app-${taxonomy_version}.jar"
```

Startzeitpakete sind `templates`, `architecture`, `reporting`, `analysis`, `portfolio`
und `interop`. Reporting benötigt Templates, Analysis benötigt Architecture, Portfolio
benötigt Analysis, Architecture und Reporting. Vor einer Änderung alle betroffenen
Instanzen stoppen. Host und Fach-JARs müssen dieselbe Version besitzen. Unvollständige
oder doppelte Zusammenstellungen scheitern vor der Datenbankinitialisierung. Entfernte
JARs rechtfertigen keine Löschung von Tabellen oder Git-Historie. Datenbank und genaue
Artefakte für die Wiederherstellung aufbewahren. Ein Code-Rollback macht eine
Datenmigration nicht rückgängig.

Externe Plugins sind vertrauenswürdiger Java-Code, den der Betreiber lokal installiert.
Es gibt keinen Upload und keinen Download von beliebigen URLs. JARs samt SHA-256 und
Konfiguration aufbewahren. Externe LLM-Anbieter benötigen eine ausdrückliche, nicht geheime
`taxonomy.llm.providers.<id>.configuration-revision`. Sie muss sich bei Änderungen an
Konfiguration oder ausgewählten Zugangsdaten ändern. Dauerhafte Aufträge binden Anbieter,
Artefakt, Version, Prüfsumme und Konfigurationsrevision. Fehlt diese Bindung oder passt sie
nicht, stoppt der Auftrag vor Anbieteraufruf und Quota-Verbrauch. Es gibt keinen Ersatz
durch einen anderen Anbieter. Alle Cluster-Worker vor Zulassung des neuen Auftragsformats
aktualisieren.

Dynamische Aktivierung ist standardmäßig aus. Nur im Einzelinstanzbetrieb schaltet
`taxonomy.plugins.dynamic.enabled=true` die ADMIN- und CSRF-geschützten Operationen
unter `/api/admin/plugins` frei. Sie gelten ausschließlich für lokal installierte
DYNAMIC-Export-/Renderer-Plugins. Fachpakete und Anbieter bleiben Startzeiterweiterungen.
Beim Drain werden neue Aufrufe abgewiesen; zugelassene Aufrufe beenden sich mit ihrem
alten Artefakt. HTTP 202 bedeutet, dass Ressourcen noch genutzt werden. Clusterbetrieb
weist dynamische Änderungen ab. `/api/capabilities` steuert die verfügbaren Bedienelemente.

Vollständige Sicherungen benötigen sämtliche datenhaltenden Features. Capture und Restore
bekommen ein explizites unveränderliches Inventar aus Startprüfung und Startzeit-Plugin-Katalog.
Auch direkte Aufrufer von Backup-Koordinator und Archivleser müssen dieses übergeben.
Das Manifest enthält Artefakt-, Versions- und SHA-256-Voraussetzungen, die vor Übergabe an
schreibende Wiederherstellungsadapter geprüft werden. Zustandslose dynamische Formate sind
keine Voraussetzung zur Datenwiederherstellung. Fehlende Auftragsbindungen und ursprüngliche
Artefakte erhalten; alte Aufträge eingebauter Anbieter bleiben lesbar.
