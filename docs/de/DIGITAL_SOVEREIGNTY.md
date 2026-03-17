# Digitale Souveränität

Dieses Dokument beschreibt die Positionierung des Taxonomy Architecture Analyzer hinsichtlich **digitaler Souveränität**, **openCode-Kompatibilität** und **Deutsche Verwaltungscloud (DVC) Architektur-Konformität** für den Einsatz in der deutschen Bundesverwaltung.

---

## Inhaltsverzeichnis

1. [Souveränitätskriterien](#souveränitätskriterien)
2. [openCode-Kompatibilität](#opencode-kompatibilität)
3. [Deutsche Verwaltungscloud (DVC) Kompatibilität](#deutsche-verwaltungscloud-dvc-kompatibilität)
4. [Nachnutzbarkeit durch Behörden](#nachnutzbarkeit-durch-behörden)
5. [Konfiguration für souveränen Betrieb](#konfiguration-für-souveränen-betrieb)

---

## Souveränitätskriterien

| Kriterium | Taxonomy-Umsetzung | Status |
|---|---|---|
| **Open Source** | MIT-Lizenz; vollständiger Quellcode auf [GitHub](https://github.com/carstenartur/Taxonomy) | ✅ |
| **Offene Standards** | ArchiMate 3.x XML, JSON, Mermaid, ONNX, OpenAPI, CycloneDX SBOM | ✅ |
| **Keine Vendor-Lock-In** | 7 austauschbare LLM-Provider; Standard-Datenbanken (PostgreSQL, SQL Server, Oracle) | ✅ |
| **Air-Gapped-Betrieb** | `LOCAL_ONNX`-Modus für vollständig lokale Inferenz; vorgeladene Embedding-Modelle | ✅ |
| **SBOM / Supply Chain** | CycloneDX SBOM automatisch bei `mvn package` generiert | ✅ |
| **Modulare Architektur** | 4 Maven-Module (domain, dsl, export, app); erweiterbar und austauschbar | ✅ |
| **Datenportabilität** | Export in 5+ Formate (ArchiMate XML, Visio, Mermaid, JSON, Reports); JGit-Repository exportierbar | ✅ |
| **EU-Datenresidenz** | Mistral AI (Frankreich/EU) oder LOCAL_ONNX für On-Premises-Betrieb konfigurierbar | ✅ |
| **Standard-Technologien** | Java 17 (LTS), Spring Boot 4, Maven, Docker — breit verfügbare Kompetenzen | ✅ |
| **Keine Cloud-Abhängigkeit** | Vollständig on-premises deploybar; keine Cloud-Dienste erforderlich | ✅ |

---

## openCode-Kompatibilität

Der Taxonomy Architecture Analyzer erfüllt die Kriterien für eine Veröffentlichung auf der [openCode-Plattform](https://opencode.de/) (ehemals Open CoDE) des Zentrums für Digitale Souveränität (ZenDiS):

| openCode-Kriterium | Status | Nachweis |
|---|---|---|
| **Open-Source-Lizenz** | ✅ | MIT-Lizenz ([LICENSE](../../LICENSE)) |
| **Öffentlich zugänglicher Quellcode** | ✅ | [GitHub Repository](https://github.com/carstenartur/Taxonomy) |
| **Dokumentierte Build-Anleitung** | ✅ | [README](../../README.md) + [Developer Guide](DEVELOPER_GUIDE.md) |
| **SBOM vorhanden** | ✅ | CycloneDX (`target/taxonomy-sbom.json`) |
| **Verwaltungsrelevanter Einsatzzweck** | ✅ | Architektur-Analyse und Wissenskonservierung für Behörden (siehe [Use Case](USE_CASE_WISSENSKONSERVIERUNG.md)) |
| **Nachnutzung durch andere Behörden** | ✅ | Docker-Deployment; umfangreiche Konfigurationsmöglichkeiten |
| **Dokumentation in deutscher Sprache** | ✅ | Behördentauglichkeits-Dokumentation auf Deutsch |
| **Drittanbieter-Transparenz** | ✅ | [THIRD-PARTY-NOTICES.md](../../THIRD-PARTY-NOTICES.md) |
| **Sicherheitsdokumentation** | ✅ | [SECURITY.md](SECURITY.md) |
| **Datenschutzdokumentation** | ✅ | [DATA_PROTECTION.md](DATA_PROTECTION.md) |

---

## Deutsche Verwaltungscloud (DVC) Kompatibilität

Der Taxonomy Architecture Analyzer ist mit der Zielarchitektur der Deutschen Verwaltungscloud kompatibel:

| DVC-Anforderung | Taxonomy-Umsetzung | Status |
|---|---|---|
| **Container-basiertes Deployment** | Docker-Image vorhanden; Kubernetes-fähig (Dockerfile im Repository) | ✅ |
| **Mandantenfähigkeit** | Workspace-Isolation über Branch-basierte Benutzertrennung (siehe [Workspace Design](../internal/WORKSPACE_DESIGN.md)) | ⚠️ In Entwicklung |
| **Föderale Nachnutzung** | Open Source (MIT); konfigurierbar für verschiedene Behördenkontexte | ✅ |
| **Standardisierte Schnittstellen** | REST API mit OpenAPI/Swagger-Dokumentation | ✅ |
| **Datensouveränität** | On-Premises-Betrieb + Air-Gapped-Modus möglich | ✅ |
| **Interoperabilität** | Export in offene Formate (ArchiMate XML, JSON, Mermaid) | ✅ |
| **Monitoring** | Spring Boot Actuator (Health, Metrics, Prometheus) | ✅ |
| **Skalierbarkeit** | Stateless REST API; Datenbank-Backend austauschbar | ✅ |
| **Hochverfügbarkeit** | Docker-basiert; Load-Balancer-kompatibel (Health Endpoint) | ✅ |

---

## Nachnutzbarkeit durch Behörden

### Einfache Inbetriebnahme

```bash
# Minimal-Deployment (air-gapped, keine externe Abhängigkeit)
docker run -p 8080:8080 \
  -e LLM_PROVIDER=LOCAL_ONNX \
  -e SPRING_PROFILES_ACTIVE=production \
  -e TAXONOMY_ADMIN_PASSWORD=<sicheres-passwort> \
  ghcr.io/carstenartur/taxonomy:latest
```

### Anpassbarkeit

| Bereich | Konfigurationsmöglichkeit |
|---|---|
| **Datenbank** | PostgreSQL, SQL Server, Oracle, HSQLDB (Standard) |
| **LLM-Provider** | 7 Provider oder vollständig offline (LOCAL_ONNX) |
| **Authentifizierung** | Lokale Benutzerverwaltung oder Keycloak/OIDC (SSO) |
| **Taxonomie-Daten** | C3-Katalog vorgeladen; weitere Kataloge importierbar |
| **Export-Formate** | ArchiMate XML, Visio, Mermaid, JSON, Reports |
| **Sprache** | UI auf Englisch; Behördendokumentation auf Deutsch |

### Behördenspezifische Dokumentation

| Dokument | Beschreibung |
|---|---|
| [BSI KI Checklist](BSI_KI_CHECKLIST.md) | BSI-Kriterienkatalog für KI-Modelle |
| [AI Literacy Concept](AI_LITERACY_CONCEPT.md) | Schulungskonzept gemäß EU AI Act Art. 4 |
| [Accessibility](ACCESSIBILITY.md) | BITV 2.0 / WCAG 2.1 Barrierefreiheitskonzept |
| [Data Protection](DATA_PROTECTION.md) | DSGVO-Compliance |
| [Security](SECURITY.md) | Sicherheitsarchitektur |
| [Deployment Checklist](DEPLOYMENT_CHECKLIST.md) | Behörden-Deployment-Checkliste |
| [Knowledge Conservation](USE_CASE_WISSENSKONSERVIERUNG.md) | Use Case: Wissenskonservierung in Behörden |
| [Verwaltungsintegration](VERWALTUNGSINTEGRATION.md) | FIM/115/XÖV-Integrations-Roadmap |

---

## Konfiguration für souveränen Betrieb

### Maximale Datensouveränität (Air-Gapped)

```bash
# Keine externen Netzwerkverbindungen
LLM_PROVIDER=LOCAL_ONNX
TAXONOMY_EMBEDDING_ENABLED=true
TAXONOMY_EMBEDDING_MODEL_DIR=/app/models/bge-small-en-v1.5

# Produktion-Profil mit Sicherheits-Defaults
SPRING_PROFILES_ACTIVE=production,postgres
TAXONOMY_ADMIN_PASSWORD=<sicheres-passwort>
ADMIN_PASSWORD=<admin-panel-geheimnis>
TAXONOMY_AUDIT_LOGGING=true
TAXONOMY_REQUIRE_PASSWORD_CHANGE=true
TAXONOMY_SPRINGDOC_ENABLED=false
```

### EU-Datenresidenz (Cloud-LLM in der EU)

```bash
# Mistral AI — gehostet in Frankreich/EU
LLM_PROVIDER=MISTRAL
MISTRAL_API_KEY=<api-schlüssel>

# Alle anderen Einstellungen wie Air-Gapped
SPRING_PROFILES_ACTIVE=production,postgres
TAXONOMY_ADMIN_PASSWORD=<sicheres-passwort>
TAXONOMY_AUDIT_LOGGING=true
```

Weitere Details: [AI Transparency — Configuration for Government Use](AI_TRANSPARENCY.md#configuration-for-government-use)

---

## Verwandte Dokumentation

- [AI Transparency](AI_TRANSPARENCY.md) — KI-Transparenz und Datenflüsse
- [Security](SECURITY.md) — Sicherheitsarchitektur
- [Data Protection](DATA_PROTECTION.md) — Datenschutz und DSGVO
- [Deployment Checklist](DEPLOYMENT_CHECKLIST.md) — Deployment-Checkliste für Behördenumgebungen
- [Deployment Guide](DEPLOYMENT_GUIDE.md) — Docker und Deployment-Anleitungen
- [Verwaltungsintegration](VERWALTUNGSINTEGRATION.md) — FIM/115/XÖV-Integrations-Roadmap
