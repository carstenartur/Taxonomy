# Digital Sovereignty

This document describes the positioning of the Taxonomy Architecture Analyzer with regard to **digital sovereignty**, **openCode compatibility**, and **German Federal Cloud (DVC) architecture conformity** for deployment in the German federal administration.

---

## Table of Contents

1. [Sovereignty Criteria](#sovereignty-criteria)
2. [openCode Compatibility](#opencode-compatibility)
3. [German Federal Cloud (DVC) Compatibility](#german-federal-cloud-dvc-compatibility)
4. [Reusability by Government Agencies](#reusability-by-government-agencies)
5. [Configuration for Sovereign Operation](#configuration-for-sovereign-operation)

---

## Sovereignty Criteria

| Criterion | Taxonomy Implementation | Status |
|---|---|---|
| **Open Source** | MIT license; full source code on [GitHub](https://github.com/carstenartur/Taxonomy) | ✅ |
| **Open Standards** | ArchiMate 3.x XML, JSON, Mermaid, ONNX, OpenAPI, CycloneDX SBOM | ✅ |
| **No Vendor Lock-In** | 7 interchangeable LLM providers; standard databases (PostgreSQL, SQL Server, Oracle) | ✅ |
| **Air-Gapped Operation** | `LOCAL_ONNX` mode for fully local inference; pre-loaded embedding models | ✅ |
| **SBOM / Supply Chain** | CycloneDX SBOM automatically generated during `mvn package` | ✅ |
| **Modular Architecture** | 4 Maven modules (domain, dsl, export, app); extensible and interchangeable | ✅ |
| **Data Portability** | Export in 5+ formats (ArchiMate XML, Visio, Mermaid, JSON, Reports); JGit repository exportable | ✅ |
| **EU Data Residency** | Mistral AI (France/EU) or LOCAL_ONNX configurable for on-premises operation | ✅ |
| **Standard Technologies** | Java 21 (LTS), Spring Boot 4, Maven, Docker — widely available skill sets | ✅ |
| **No Cloud Dependency** | Fully deployable on-premises; no cloud services required | ✅ |

---

## openCode Compatibility

The Taxonomy Architecture Analyzer meets the criteria for publication on the [openCode platform](https://opencode.de/) (formerly Open CoDE) of the Center for Digital Sovereignty (ZenDiS):

| openCode Criterion | Status | Evidence |
|---|---|---|
| **Open Source License** | ✅ | MIT license ([LICENSE](../../LICENSE)) |
| **Publicly Accessible Source Code** | ✅ | [GitHub Repository](https://github.com/carstenartur/Taxonomy) |
| **Documented Build Instructions** | ✅ | [README](../../README.md) + [Developer Guide](DEVELOPER_GUIDE.md) |
| **SBOM Available** | ✅ | CycloneDX (`target/taxonomy-sbom.json`) |
| **Government-Relevant Use Case** | ✅ | Architecture analysis and knowledge preservation for government agencies (see [Use Case](USE_CASE_WISSENSKONSERVIERUNG.md)) |
| **Reuse by Other Agencies** | ✅ | Docker deployment; extensive configuration options |
| **Documentation in German** | ✅ | Government-readiness documentation available in German |
| **Third-Party Transparency** | ✅ | [THIRD-PARTY-NOTICES.md](../../THIRD-PARTY-NOTICES.md) |
| **Security Documentation** | ✅ | [SECURITY.md](SECURITY.md) |
| **Data Protection Documentation** | ✅ | [DATA_PROTECTION.md](DATA_PROTECTION.md) |

---

## German Federal Cloud (DVC) Compatibility

The Taxonomy Architecture Analyzer is compatible with the target architecture of the German Federal Cloud (Deutsche Verwaltungscloud):

| DVC Requirement | Taxonomy Implementation | Status |
|---|---|---|
| **Container-Based Deployment** | Docker image available; Kubernetes-ready (Dockerfile in repository) | ✅ |
| **Multi-Tenancy** | per user repository (see [Workspace Design](../internal/WORKSPACE_DESIGN.md)) | ✅  |
| **Federal Reuse** | Open Source (MIT); configurable for various agency contexts | ✅ |
| **Standardized Interfaces** | REST API with OpenAPI/Swagger documentation | ✅ |
| **Data Sovereignty** | On-premises operation + air-gapped mode possible | ✅ |
| **Interoperability** | Export in open formats (ArchiMate XML, JSON, Mermaid) | ✅ |
| **Monitoring** | Spring Boot Actuator (Health, Metrics, Prometheus) | ✅ |
| **Scalability** | Stateless REST API; interchangeable database backend | ✅ |
| **High Availability** | Docker-based; load-balancer compatible (health endpoint) | ✅ |

---

## Reusability by Government Agencies

### Easy Deployment

```bash
# Minimal deployment (air-gapped, no external dependencies)
docker run -p 8080:8080 \
  -e LLM_PROVIDER=LOCAL_ONNX \
  -e SPRING_PROFILES_ACTIVE=production \
  -e TAXONOMY_ADMIN_PASSWORD=<secure-password> \
  ghcr.io/carstenartur/taxonomy:latest
```

### Customizability

| Area | Configuration Options |
|---|---|
| **Database** | PostgreSQL, SQL Server, Oracle, HSQLDB (default) |
| **LLM Provider** | 7 providers or fully offline (LOCAL_ONNX) |
| **Authentication** | Local user management or Keycloak/OIDC (SSO) |
| **Taxonomy Data** | C3 catalog pre-loaded; additional catalogs importable |
| **Export Formats** | ArchiMate XML, Visio, Mermaid, JSON, Reports |
| **Language** | UI in English; government documentation in German |

### Agency-Specific Documentation

| Document | Description |
|---|---|
| [BSI KI Checklist](BSI_KI_CHECKLIST.md) | BSI criteria catalog for AI models |
| [AI Literacy Concept](AI_LITERACY_CONCEPT.md) | Training concept per EU AI Act Art. 4 |
| [Accessibility](ACCESSIBILITY.md) | BITV 2.0 / WCAG 2.1 accessibility concept |
| [Data Protection](DATA_PROTECTION.md) | GDPR compliance |
| [Security](SECURITY.md) | Security architecture |
| [Deployment Checklist](DEPLOYMENT_CHECKLIST.md) | Government deployment checklist |
| [Knowledge Preservation](USE_CASE_WISSENSKONSERVIERUNG.md) | Use case: knowledge preservation in government agencies |
| [Administration Integration](VERWALTUNGSINTEGRATION.md) | FIM/115/XÖV integration roadmap |

---

## Configuration for Sovereign Operation

### Maximum Data Sovereignty (Air-Gapped)

```bash
# No external network connections
LLM_PROVIDER=LOCAL_ONNX
TAXONOMY_EMBEDDING_ENABLED=true
TAXONOMY_EMBEDDING_MODEL_DIR=/app/models/bge-small-en-v1.5

# Production profile with security defaults
SPRING_PROFILES_ACTIVE=production,postgres
TAXONOMY_ADMIN_PASSWORD=<secure-password>
ADMIN_PASSWORD=<admin-panel-secret>
TAXONOMY_AUDIT_LOGGING=true
TAXONOMY_REQUIRE_PASSWORD_CHANGE=true
TAXONOMY_SPRINGDOC_ENABLED=false
```

### EU Data Residency (Cloud LLM in the EU)

```bash
# Mistral AI — hosted in France/EU
LLM_PROVIDER=MISTRAL
MISTRAL_API_KEY=<api-key>

# All other settings same as air-gapped
SPRING_PROFILES_ACTIVE=production,postgres
TAXONOMY_ADMIN_PASSWORD=<secure-password>
TAXONOMY_AUDIT_LOGGING=true
```

Further details: [AI Transparency — Configuration for Government Use](AI_TRANSPARENCY.md#configuration-for-government-use)

---

## Deutschland-Stack Architecture Principles

The Taxonomy Architecture Analyzer has been assessed against the 8 architecture
principles of the [Deutschland-Stack](https://deutschland-stack.gov.de/gesamtbild/#architekturprinzipien):

| # | Principle | Status |
|---|---|---|
| 1 | Offene Standards | ✅ |
| 2 | Open Source First | ✅ |
| 3 | Interoperabilität und Anschlussfähigkeit | ✅ |
| 4 | Modularität und Wiederverwendbarkeit | ✅ |
| 5 | Integration bestehender Lösungen | ✅ |
| 6 | Skalierbarkeit und Zukunftsfähigkeit | ✅ |
| 7 | Sichere, effiziente, vertrauenswürdige Systeme | ✅ |
| 8 | Kooperationsorientiertes Ökosystem | ✅ |

See [Deutschland-Stack Conformity Assessment](DEUTSCHLAND_STACK_CONFORMITY.md) for
the detailed mapping with evidence references.

---

## Related Documentation

- [AI Transparency](AI_TRANSPARENCY.md) — AI transparency and data flows
- [Security](SECURITY.md) — Security architecture
- [Data Protection](DATA_PROTECTION.md) — Data protection and GDPR
- [Deployment Checklist](DEPLOYMENT_CHECKLIST.md) — Deployment checklist for government environments
- [Deployment Guide](DEPLOYMENT_GUIDE.md) — Docker and deployment instructions
- [Administration Integration](VERWALTUNGSINTEGRATION.md) — FIM/115/XÖV integration roadmap
- [Deutschland-Stack Conformity](DEUTSCHLAND_STACK_CONFORMITY.md) — Full conformity assessment
