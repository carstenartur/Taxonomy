# Bereitstellungs-Checkliste — Behörden- / Unternehmensumgebungen

Diese Checkliste umfasst alle Schritte, die erforderlich sind, um den Taxonomy Architecture Analyzer in einer kontrollierten Behörden- oder Unternehmensumgebung einzuführen.

---

## Vor der Bereitstellung

- [ ] **Infrastruktur genehmigt** — Server oder Container-Plattform bereitgestellt (VM, Kubernetes, Docker-Host)
- [ ] **Netzwerkzugang** — ausgehender HTTPS-Zugriff auf LLM-API-Endpunkte erlaubt (oder `LLM_PROVIDER=LOCAL_ONNX` für air-gapped-Umgebungen)
- [ ] **TLS-Zertifikate** — gültiges Zertifikat für die Bereitstellungsdomäne, konfiguriert im Reverse Proxy
- [ ] **Datenbank ausgewählt** — PostgreSQL, SQL Server oder Oracle bereitgestellt (siehe [Datenbank-Einrichtung](DATABASE_SETUP.md))
- [ ] **Backup-Strategie definiert** — Datenbank-Backups, Lucene-Index, JGit-Repository-Verzeichnis
- [ ] **SBOM geprüft** — `target/taxonomy-sbom.json` über `mvn package` generiert und auf Lizenzkonformität geprüft

---

## Sicherheitskonfiguration

- [ ] **Produktionsprofil aktivieren**: `SPRING_PROFILES_ACTIVE=production,postgres`
- [ ] **Standard-Admin-Passwort ändern**: `TAXONOMY_ADMIN_PASSWORD=<strong-random-password>`
- [ ] **Admin-Panel-Token setzen**: `ADMIN_PASSWORD=<separate-admin-panel-secret>`
- [ ] **Audit-Logging aktiviert** (automatisch im Produktionsprofil): `TAXONOMY_AUDIT_LOGGING=true`
- [ ] **Passwortänderung erzwungen** (automatisch im Produktionsprofil): `TAXONOMY_REQUIRE_PASSWORD_CHANGE=true`
- [ ] **Swagger-UI eingeschränkt** (automatisch im Produktionsprofil): `TAXONOMY_SPRINGDOC_ENABLED=false`
- [ ] **Brute-Force-Schutz überprüft**: `TAXONOMY_LOGIN_RATE_LIMIT=true` (Standard)
- [ ] **HTTPS erzwungen** über Reverse Proxy — HSTS-Header werden bereits von der Anwendung gesendet

---

## LLM- / KI-Konfiguration

- [ ] **LLM-Anbieter ausgewählt** — `LLM_PROVIDER` und die zugehörige API-Key-Umgebungsvariable setzen
- [ ] **Rate-Limits konfiguriert** — `TAXONOMY_LLM_RPM` entspricht dem Kontingent des Anbieters
- [ ] **Für air-gapped-Bereitstellungen**: `LLM_PROVIDER=LOCAL_ONNX` setzen, Embedding-Modell vorab herunterladen mit `TAXONOMY_EMBEDDING_MODEL_DIR`
- [ ] **KI-Transparenz dokumentiert** — siehe [KI-Transparenz](AI_TRANSPARENCY.md)

---

## Datenschutz

- [ ] **Datenschutz-Folgenabschätzung (DSFA)** durchgeführt, sofern personenbezogene Daten verarbeitet werden
- [ ] **Aufbewahrungsrichtlinie** definiert für Audit-Logs, Benutzerkonten und Workspace-Daten
- [ ] **Auftragsverarbeitungsvertrag** abgeschlossen, sofern cloud-gehostete LLM-Anbieter genutzt werden
- [ ] **Vollständige Details**: [Datenschutz](DATA_PROTECTION.md)

---

## Benutzerverwaltung

- [ ] **Benutzerkonten erstellt** für alle initialen Benutzer über die Admin-API oder GUI
- [ ] **Rollen zugewiesen** — USER (Analysten), ARCHITECT (Architekten), ADMIN (Administratoren)
- [ ] **Keycloak- / SSO-Integration** konfiguriert, falls erforderlich (siehe [Keycloak-Einrichtung](KEYCLOAK_SETUP.md))

---

## Monitoring & Betrieb

- [ ] **Health-Check-Endpunkt** im Load Balancer konfiguriert: `GET /api/status/startup`
- [ ] **Actuator-Endpunkte** für das Betriebsteam zugänglich: `/actuator/health`, `/actuator/metrics`, `/actuator/prometheus`
- [ ] **Prometheus-Scraping** konfiguriert für `/actuator/prometheus`
- [ ] **Log-Rotation** konfiguriert — siehe [Betriebshandbuch](OPERATIONS_GUIDE.md)
- [ ] **Alarmierung** konfiguriert für Health-Check-Ausfälle und Fehlerrate-Spitzen

---

## Docker-Bereitstellung

```bash
docker run -d \
  --name taxonomy-analyzer \
  -p 8080:8080 \
  -v taxonomy-data:/app/data \
  -e SPRING_PROFILES_ACTIVE=production,postgres \
  -e TAXONOMY_ADMIN_PASSWORD=<strong-password> \
  -e ADMIN_PASSWORD=<admin-panel-secret> \
  -e GEMINI_API_KEY=<your-api-key> \
  -e TAXONOMY_DATASOURCE_URL=jdbc:postgresql://db:5432/taxonomy \
  -e SPRING_DATASOURCE_USERNAME=taxonomy \
  -e SPRING_DATASOURCE_PASSWORD=<db-password> \
  ghcr.io/carstenartur/taxonomy:latest
```

---

## Überprüfung nach der Bereitstellung

- [ ] **Anwendung startet** — prüfen Sie, ob `GET /api/status/startup` den Statuscode 200 zurückgibt
- [ ] **Anmeldung funktioniert** — als Admin authentifizieren und Passwortänderungs-Aufforderung bestätigen
- [ ] **Taxonomie geladen** — `GET /api/taxonomy` gibt ca. 2.500 Knoten zurück
- [ ] **Analyse funktioniert** — eine Testanforderung absenden und KI-Bewertung überprüfen
- [ ] **Export funktioniert** — ArchiMate-XML generieren und gültige Ausgabe überprüfen
- [ ] **Audit-Logs geschrieben** — Anwendungsprotokolle auf `LOGIN_SUCCESS`-Einträge prüfen
- [ ] **Health-Endpunkt** — `/actuator/health` gibt UP zurück

---

## BSI-KI-Konformität

- [ ] **BSI-KI-Checkliste geprüft** — alle Kriterien der [BSI-KI-Checkliste](BSI_KI_CHECKLIST.md) für die Bereitstellungsumgebung verifiziert
- [ ] **KI-Anbieterauswahl dokumentiert** — Begründung für den gewählten LLM-Anbieter festgehalten (Datenresidenz, Sicherheitseinstufung)
- [ ] **Bias-Monitoring-Prozess definiert** — Zeitplan für vergleichende Analyse über Anbieter hinweg (siehe Empfehlungen der BSI-Checkliste)
- [ ] **Überprüfungszyklus etabliert** — monatliche SBOM-Prüfung, vierteljährlicher Anbietervergleich, jährliches vollständiges KI-Audit

---

## Barrierefreiheit (BITV 2.0)

- [ ] **Barrierefreiheitskonzept geprüft** — [Barrierefreiheitskonzept](ACCESSIBILITY.md) geprüft und an den Bereitstellungskontext angepasst
- [ ] **axe/Lighthouse-Audit bestanden** — automatisiertes Barrierefreiheits-Audit mit akzeptablem Ergebnis
- [ ] **Tastaturnavigation überprüft** — alle Kernfunktionen per Tastatur erreichbar
- [ ] **Barrierefreiheitserklärung veröffentlicht** — Erklärung zur Barrierefreiheit gemäß § 12b BGG für die Bereitstellungs-URL
- [ ] **Feedback-Mechanismus eingerichtet** — Kontaktstelle für Barrierefreiheitsprobleme an Benutzer kommuniziert

---

## KI-Kompetenz

- [ ] **KI-Kompetenzschulung absolviert** — alle Benutzer gemäß [KI-Kompetenzkonzept](AI_LITERACY_CONCEPT.md) vor der Systemnutzung geschult
- [ ] **Schulungsteilnahme dokumentiert** — Teilnehmerliste mit Datum für Compliance-Zwecke aufbewahrt
- [ ] **Auffrischungsschulung geplant** — jährliche Auffrischung oder bei wesentlichen Systemänderungen

---

## Bereitgestellte Compliance-Dokumente

| Dokument | Ablageort |
|---|---|
| Sicherheitsarchitektur | [docs/SECURITY.md](SECURITY.md) |
| Datenschutz | [docs/DATA_PROTECTION.md](DATA_PROTECTION.md) |
| KI-Transparenz | [docs/AI_TRANSPARENCY.md](AI_TRANSPARENCY.md) |
| BSI-KI-Checkliste | [docs/BSI_KI_CHECKLIST.md](BSI_KI_CHECKLIST.md) |
| KI-Kompetenzkonzept | [docs/AI_LITERACY_CONCEPT.md](AI_LITERACY_CONCEPT.md) |
| Barrierefreiheitskonzept | [docs/ACCESSIBILITY.md](ACCESSIBILITY.md) |
| Digitale Souveränität | [docs/DIGITAL_SOVEREIGNTY.md](DIGITAL_SOVEREIGNTY.md) |
| Verwaltungsintegration | [docs/VERWALTUNGSINTEGRATION.md](VERWALTUNGSINTEGRATION.md) |
| Betriebshandbuch | [docs/OPERATIONS_GUIDE.md](OPERATIONS_GUIDE.md) |
| SSO-Integration | [docs/SSO_INTEGRATION.md](SSO_INTEGRATION.md) |
| Drittanbieter-Hinweise | [THIRD-PARTY-NOTICES.md](../THIRD-PARTY-NOTICES.md) |
| SBOM | `target/taxonomy-sbom.json` (wird beim Build generiert) |

---

## Freigabe

| Prüfpunkt | Verantwortlich | Datum | Unterschrift |
|---|---|---|---|
| Sicherheitsprüfung abgeschlossen | | | |
| Datenschutzprüfung abgeschlossen | | | |
| Betriebsbereitschaft bestätigt | | | |
| Go-Live genehmigt | | | |

---

## Weiterführende Dokumentation

- [Bereitstellungshandbuch](DEPLOYMENT_GUIDE.md) — detaillierte Bereitstellungsanweisungen (Docker, Render.com)
- [Konfigurationsreferenz](CONFIGURATION_REFERENCE.md) — alle Umgebungsvariablen
- [Sicherheit](SECURITY.md) — Sicherheitsarchitektur
- [BSI-KI-Checkliste](BSI_KI_CHECKLIST.md) — BSI-Kriterien-Checkliste für KI-Modelle
- [KI-Kompetenzkonzept](AI_LITERACY_CONCEPT.md) — Schulungskonzept für KI-Kompetenz
- [Barrierefreiheit](ACCESSIBILITY.md) — BITV 2.0 / WCAG 2.1 Barrierefreiheitskonzept
- [Digitale Souveränität](DIGITAL_SOVEREIGNTY.md) — Digitale Souveränität und openCode-Kompatibilität
