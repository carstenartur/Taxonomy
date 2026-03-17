# BSI Criteria Checklist for AI Models

This checklist maps the BSI criteria for the use of AI models in federal administration to the Taxonomy Architecture Analyzer implementation. It serves as an auditable document for BSI audits and internal security assessments.

---

## Table of Contents

1. [Model Selection and Provenance](#model-selection-and-provenance)
2. [Integration Control](#integration-control)
3. [Logging and Traceability](#logging-and-traceability)
4. [Data Sovereignty](#data-sovereignty)
5. [Robustness and Resilience](#robustness-and-resilience)
6. [Transparency and Explainability](#transparency-and-explainability)
7. [Human Oversight](#human-oversight)
8. [Prompt Injection Protection](#prompt-injection-protection)
9. [SBOM and Supply Chain](#sbom-and-supply-chain)
10. [Bias Protection](#bias-protection)
11. [Regular Review](#regular-review)
12. [Summary](#summary)

---

## Model Selection and Provenance

| BSI Criterion | Taxonomy Implementation | Status |
|---|---|---|
| Documentation of deployed AI models | 7 providers documented in [AI_PROVIDERS.md](AI_PROVIDERS.md) | ✅ Met |
| Traceable model provenance | Provider overview with model names, API endpoints, data locations | ✅ Met |
| Sovereign operation possible | `LOCAL_ONNX` mode for fully local inference without external API calls | ✅ Met |
| EU-based provider available | Mistral AI (France) configurable as EU alternative | ✅ Met |

**Evidence:** See [AI Transparency — Supported LLM Providers](AI_TRANSPARENCY.md#supported-llm-providers)

---

## Integration Control

| BSI Criterion | Taxonomy Implementation | Status |
|---|---|---|
| Prompt templates administrable | Admin-controlled prompt templates via Admin Panel | ✅ Met |
| Input sizes limited | `TAXONOMY_LIMITS_MAX_BUSINESS_TEXT` (default: 5000 characters) | ✅ Met |
| No autonomous application of AI results | All AI outputs require human review (Accept/Reject workflow) | ✅ Met |
| Provider selection controllable | `LLM_PROVIDER` environment variable; per-request override possible | ✅ Met |
| Rate limiting configurable | `TAXONOMY_LLM_RPM` for provider quota adjustment | ✅ Met |

**Evidence:** See [Configuration Reference](CONFIGURATION_REFERENCE.md) and [Security](SECURITY.md)

---

## Logging and Traceability

| BSI Criterion | Taxonomy Implementation | Status |
|---|---|---|
| Logging of AI usage | LLM Communication Log in Admin Panel (prompts, responses, timestamps, token counts) | ✅ Met |
| Audit logging for security events | `TAXONOMY_AUDIT_LOGGING=true` logs login, user management, system events | ✅ Met |
| Traceability of analysis results | Explanation Traces: score + justification for each taxonomy node | ✅ Met |
| Versioning of architecture decisions | JGit-based version control with commit author attribution | ✅ Met |

**Evidence:** See [AI Transparency — Diagnostics and Monitoring](AI_TRANSPARENCY.md#diagnostics-and-monitoring) and [Security — Audit Logging](SECURITY.md#audit-logging)

---

## Data Sovereignty

| BSI Criterion | Taxonomy Implementation | Status |
|---|---|---|
| Air-gapped operation possible | `LLM_PROVIDER=LOCAL_ONNX` + pre-loaded embedding models | ✅ Met |
| No data transfer to third countries required | LOCAL_ONNX (local) or Mistral (EU/France) configurable | ✅ Met |
| Data residency controllable | On-premises deployment; all data in local database and JGit repository | ✅ Met |
| No training with customer data | No custom model training; prompts contain no personal data | ✅ Met |

**Evidence:** See [AI Transparency — Configuration for Government Use](AI_TRANSPARENCY.md#configuration-for-government-use) and [Data Protection](DATA_PROTECTION.md)

---

## Robustness and Resilience

| BSI Criterion | Taxonomy Implementation | Status |
|---|---|---|
| Application functions without AI | Browse, search, and export functionality available without LLM | ✅ Met |
| Graceful Degradation | AI status indicator (🟢 Connected / 🟡 Degraded / 🔴 Unavailable); errors are caught | ✅ Met |
| Rate Limiting | Provider-specific rate limiting configurable | ✅ Met |
| Error handling | LLM errors are logged and presented as user feedback, not as system crashes | ✅ Met |

**Evidence:** See [AI Transparency — Diagnostics and Monitoring](AI_TRANSPARENCY.md#diagnostics-and-monitoring)

---

## Transparency and Explainability

| BSI Criterion | Taxonomy Implementation | Status |
|---|---|---|
| Scoring traceable | Score (0–100) + justification text for each node | ✅ Met |
| Model behavior documented | [AI_TRANSPARENCY.md](AI_TRANSPARENCY.md) describes data flows, limitations, risks | ✅ Met |
| Limitations transparent | Hallucination, bias, inconsistency documented as risks with mitigation measures | ✅ Met |
| Results comparable | Multi-provider comparison possible; different models evaluable for the same requirement | ✅ Met |

**Evidence:** See [AI Transparency — Scoring and Explainability](AI_TRANSPARENCY.md#scoring-and-explainability)

---

## Human Oversight

| BSI Criterion | Taxonomy Implementation | Status |
|---|---|---|
| Human-in-the-loop principle | No autonomous action; all AI outputs require explicit user action | ✅ Met |
| Accept/Reject workflow | Architecture proposals are presented as proposals; architect accepts or rejects | ✅ Met |
| No auto-apply | AI scores are displayed for review, not automatically applied | ✅ Met |
| Version control | DSL changes require explicit commit by an authorized user | ✅ Met |

**Evidence:** See [AI Transparency — Human Oversight](AI_TRANSPARENCY.md#human-oversight)

---

## Prompt Injection Protection

| BSI Criterion | Taxonomy Implementation | Status |
|---|---|---|
| Input size limitation | `TAXONOMY_LIMITS_MAX_BUSINESS_TEXT` limits input text length | ✅ Met |
| Admin-controlled templates | Prompt templates editable only by ADMIN role | ✅ Met |
| No direct user prompt manipulation | User text is embedded into a structured prompt template | ✅ Met |
| No code execution by AI | AI outputs are interpreted as text, not as executable code | ✅ Met |

**Evidence:** See [Security](SECURITY.md) and [AI Transparency — Limitations and Risks](AI_TRANSPARENCY.md#limitations-and-risks)

---

## SBOM and Supply Chain

| BSI Criterion | Taxonomy Implementation | Status |
|---|---|---|
| Software Bill of Materials available | CycloneDX SBOM automatically generated (`mvn package`) | ✅ Met |
| Dependencies documented | `target/taxonomy-sbom.json` and `target/taxonomy-sbom.xml` with package names, versions, licenses, hashes | ✅ Met |
| Third-party transparency | [THIRD-PARTY-NOTICES.md](../../THIRD-PARTY-NOTICES.md) documents all third-party licenses | ✅ Met |
| Open-source license | MIT License — fully open source | ✅ Met |

**Evidence:** See [Security — SBOM](SECURITY.md#sbom-software-bill-of-materials)

---

## Bias Protection

| BSI Criterion | Taxonomy Implementation | Status |
|---|---|---|
| Multi-provider comparison possible | 7 LLM providers enable cross-validation of results | ✅ Met |
| Local model as baseline | `LOCAL_ONNX` as reference baseline without cloud dependency | ✅ Met |
| Bias documentation | Bias documented as a risk in [AI Transparency](AI_TRANSPARENCY.md) | ⚠️ Recommendation |
| Systematic bias monitoring | No automated bias monitoring implemented yet | ⚠️ Recommendation |

**Recommendation:** Conduct regular comparative analyses between different providers and document deviations. A bias monitoring process should be incorporated into the operational manuals.

---

## Regular Review

| BSI Criterion | Taxonomy Implementation | Status |
|---|---|---|
| Review cadence defined | ❌ Not yet formally defined | ⚠️ To Be Added |
| AI model updates tracked | Provider model versions documented in configuration | ✅ Met |
| Security updates | SBOM-based vulnerability scanning; Dependabot/Renovate recommended | ✅ Met |

**Recommendation:** Introduce the following review cadence:

| Interval | Activity | Responsible |
|---|---|---|
| **Monthly** | SBOM review for known vulnerabilities | IT Security Officer |
| **Quarterly** | Provider comparison and bias sampling | AI Subject Matter Expert |
| **Semi-annually** | Review of BSI checklist against current BSI guidelines | IT Security Officer |
| **Annually** | Full AI audit including risk classification | Data Protection Officer + IT Security |

---

## Summary

| Criteria Area | Met | Partial | Open |
|---|:---:|:---:|:---:|
| Model Selection and Provenance | ✅ | | |
| Integration Control | ✅ | | |
| Logging and Traceability | ✅ | | |
| Data Sovereignty | ✅ | | |
| Robustness and Resilience | ✅ | | |
| Transparency and Explainability | ✅ | | |
| Human Oversight | ✅ | | |
| Prompt Injection Protection | ✅ | | |
| SBOM and Supply Chain | ✅ | | |
| Bias Protection | | ⚠️ | |
| Regular Review | | ⚠️ | |

**Overall Assessment:** 9 of 11 criteria fully met; 2 criteria with concrete recommendations for supplementation.

---

## Related Documentation

- [AI Transparency](AI_TRANSPARENCY.md) — AI transparency and data flows
- [AI Providers](AI_PROVIDERS.md) — LLM provider configuration
- [Security](SECURITY.md) — Security architecture
- [Data Protection](DATA_PROTECTION.md) — Data protection and GDPR
- [Deployment Checklist](DEPLOYMENT_CHECKLIST.md) — Deployment checklist for government environments
- [AI Literacy Concept](AI_LITERACY_CONCEPT.md) — Training concept per EU AI Act Art. 4
