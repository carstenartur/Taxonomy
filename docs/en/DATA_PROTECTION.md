# Data Protection

This document describes the personal data processing activities of the Taxonomy Architecture Analyzer, in compliance with the EU General Data Protection Regulation (GDPR / DSGVO) and applicable German federal and state data protection laws.

---

## Table of Contents

1. [Purpose of Data Processing](#purpose-of-data-processing)
2. [Categories of Personal Data](#categories-of-personal-data)
3. [Data Storage Locations](#data-storage-locations)
4. [Legal Basis](#legal-basis)
5. [Data Retention and Deletion](#data-retention-and-deletion)
6. [Third-Party Data Transfers](#third-party-data-transfers)
7. [Technical and Organizational Measures](#technical-and-organizational-measures)
8. [Data Subject Rights](#data-subject-rights)
9. [Data Protection Impact Assessment](#data-protection-impact-assessment)
10. [BfDI Guidelines for AI in Federal Administration](#bfdi-guidelines-for-ai-in-federal-administration)

---

## Purpose of Data Processing

The Taxonomy Architecture Analyzer processes data for the purpose of **architecture analysis and knowledge management**. The application:

1. Manages user accounts for authentication and authorization
2. Records audit events for security compliance
3. Stores architecture analysis results and versioned DSL documents
4. Optionally sends business requirement text to external LLM providers for AI-powered analysis

The application does **not** process personal data of end-users' customers or citizens. It is an internal tool for IT architects and analysts.

---

## Categories of Personal Data

### User Account Data

| Data Field | Purpose | Storage | Mandatory |
|---|---|---|---|
| **Username** | Authentication, audit attribution | Database (JPA) | Yes |
| **Password hash** (BCrypt) | Authentication | Database (JPA) | Yes |
| **Display name** | UI display, audit logs | Database (JPA) | Optional |
| **Email address** | User identification | Database (JPA) | Optional |
| **Roles** | Authorization (USER, ARCHITECT, ADMIN) | Database (JPA) | Yes |
| **Enabled flag** | Account lifecycle (soft delete) | Database (JPA) | Yes |
| **Created/updated timestamps** | Audit trail | Database (JPA) | Automatic |

### Audit Log Data

When `TAXONOMY_AUDIT_LOGGING=true` (default in production profile):

| Data Field | Purpose | Storage | Retention |
|---|---|---|---|
| **Username** | Attribution of security events | Application logs | Configurable |
| **IP address** | Security forensics, brute-force detection | Application logs, in-memory (rate limiter) | Log rotation policy |
| **Timestamp** | Event ordering | Application logs | Log rotation policy |
| **Event type** | Compliance reporting | Application logs | Log rotation policy |

### Workspace and Analysis Data

| Data Field | Personal Data? | Purpose | Storage |
|---|---|---|---|
| **Business requirement text** | Potentially (if user includes names/references) | AI analysis input | In-memory (transient) |
| **Analysis results** (scores) | No | Architecture mapping | In-memory / export files |
| **DSL documents** | No (architecture descriptions) | Versioned architecture models | JGit repository |
| **Commit metadata** | Yes (author name/email) | Version history attribution | JGit repository |

---

## Data Storage Locations

| Component | Default Location | Contains Personal Data | Encryption |
|---|---|---|---|
| **Database** (HSQLDB/PostgreSQL/MSSQL/Oracle) | In-memory or configured URL | User accounts, password hashes | Database-level (TDE for enterprise DBs) |
| **Application logs** | stdout / `/app/logs/` | Audit events (username, IP) | File-system level |
| **JGit repository** | `/app/data/git` | Commit author metadata | File-system level |
| **Lucene index** | `/app/data/lucene-index` | No (taxonomy data only) | None required |
| **Docker volumes** | Host-configured | All of the above | Host-level encryption |

---

## Legal Basis

Processing of personal data is based on:

| Legal Basis (GDPR) | Applicable To |
|---|---|
| **Art. 6(1)(b) — Performance of contract** | User account management for employment/service relationship |
| **Art. 6(1)(c) — Legal obligation** | Audit logging for IT security compliance (BSI IT-Grundschutz, ISO 27001) |
| **Art. 6(1)(f) — Legitimate interest** | Brute-force protection (IP tracking), application security |

For government agencies, processing may additionally be based on applicable administrative regulations (e.g., BDSG §26 for employee data processing).

---

## Data Retention and Deletion

### Retention Periods

| Data Category | Retention Period | Deletion Method |
|---|---|---|
| **Active user accounts** | Duration of employment/assignment | Admin disables account via API |
| **Disabled user accounts** | 90 days after disabling (recommended) | Manual deletion from database |
| **Audit logs** | 1 year (recommended per BSI) | Log rotation (see [Operations Guide](OPERATIONS_GUIDE.md)) |
| **JGit commit history** | Indefinite (architecture knowledge base) | `git filter-branch` for specific commits |
| **Analysis results** | Session duration (in-memory) | Automatically cleared on session end |
| **Rate limiter data** (IP → attempt count) | 5 minutes (lockout duration) | Automatically expired |

### Deletion Procedure

To completely remove a user's personal data:

1. **Disable the user account**: `DELETE /api/admin/users/{id}`
2. **Delete the database record**: Direct database DELETE (after retention period)
3. **Purge audit logs**: Remove entries containing the username from log files
4. **Rewrite JGit history** (if required): Use `git filter-branch` to remove author metadata

> **Note:** JGit commits are append-only by design. Removing commit metadata requires a full repository rewrite, which may affect data integrity for other users.

---

## Third-Party Data Transfers

### External LLM Providers

When using cloud-based LLM providers (Gemini, OpenAI, DeepSeek, Qwen, Llama, Mistral), the following data is sent to external servers:

| Data Sent | Recipient | Purpose | Location |
|---|---|---|---|
| Business requirement text | LLM provider API | AI-powered analysis | Provider's cloud infrastructure |
| Taxonomy node names/descriptions | LLM provider API | Scoring context | Provider's cloud infrastructure |

**Important considerations:**

- **No personal data should be included** in business requirement texts when using external LLM providers
- The LLM providers' data processing terms apply (see each provider's privacy policy)
- For government deployments, use `LLM_PROVIDER=LOCAL_ONNX` to keep all data on-premises
- A **data processing agreement (DPA)** should be in place with the LLM provider if personal data may be included in prompts

### Air-Gapped Operation

Set `LLM_PROVIDER=LOCAL_ONNX` and `TAXONOMY_EMBEDDING_ENABLED=true` with a pre-downloaded model to operate without any external data transfers:

```bash
LLM_PROVIDER=LOCAL_ONNX
TAXONOMY_EMBEDDING_MODEL_DIR=/app/models/bge-small-en-v1.5
```

See [AI Transparency](AI_TRANSPARENCY.md) for details on which data flows where.

---

## Technical and Organizational Measures (TOMs)

### Technical Measures

| Measure | Implementation |
|---|---|
| **Password hashing** | BCrypt with default strength (10 rounds) |
| **Transport encryption** | HTTPS via reverse proxy; HSTS header enforced |
| **Access control** | Role-based (USER, ARCHITECT, ADMIN) via Spring Security |
| **Brute-force protection** | IP-based rate limiting on login endpoints |
| **CSRF protection** | Enabled for browser sessions |
| **Security headers** | X-Content-Type-Options, X-Frame-Options, HSTS, Referrer-Policy |
| **Session management** | Server-side sessions; stateless REST API |
| **Audit logging** | Authentication events logged with username and IP |
| **Input validation** | Size limits on business text, architecture nodes, export nodes |

### Organizational Measures

| Measure | Recommendation |
|---|---|
| **Access management** | Assign minimum necessary roles; review quarterly |
| **Admin separation** | Separate `TAXONOMY_ADMIN_PASSWORD` and `ADMIN_PASSWORD` |
| **Password policy** | Enforce password changes via `TAXONOMY_REQUIRE_PASSWORD_CHANGE=true` |
| **Security training** | Ensure administrators are trained on secure configuration |
| **Incident response** | Monitor audit logs; define escalation procedures |
| **Regular updates** | Update application and dependencies; review SBOM for vulnerabilities |

---

## Data Subject Rights

Under GDPR, data subjects (users of the application) have the following rights:

| Right | How to Exercise |
|---|---|
| **Right of access** (Art. 15) | Admin exports user record via `GET /api/admin/users/{id}` |
| **Right to rectification** (Art. 16) | Admin updates user via `PUT /api/admin/users/{id}` |
| **Right to erasure** (Art. 17) | Admin disables user → database deletion after retention |
| **Right to restriction** (Art. 18) | Admin disables user account (soft delete) |
| **Right to data portability** (Art. 20) | User data is available via REST API in JSON format |

---

## Data Protection Impact Assessment

A Data Protection Impact Assessment (DPIA) according to GDPR Art. 35 may be required if:

- The application processes personal data in business requirement texts
- The application is integrated with external LLM providers (profiling risk)
- The application is used across multiple organizational units

**Recommendation:** Conduct a DPIA before deploying in environments where personal data may be included in analysis inputs. For architecture-only use cases with no personal data in requirements, a DPIA is typically not required.

---

## BfDI Guidelines for AI in Federal Administration

The German Federal Commissioner for Data Protection and Freedom of Information (BfDI) has published guidance for the use of AI/LLM systems in the federal administration. The following table maps BfDI requirements to the Taxonomy Architecture Analyzer implementation:

| BfDI Requirement | Taxonomy Implementation | Status |
|---|---|---|
| **No training with personal data** | No custom model training; prompts should not contain personally identifiable information (PII) | ✅ Fulfilled |
| **Logging of AI usage** | LLM Communication Log in admin panel (prompts, responses, timestamps, token counts); Audit logging for security events | ✅ Fulfilled |
| **Data protection supervisory authority remains responsible** | Referenced in DPIA recommendation above; supervisory authority jurisdiction not affected by AI usage | ✅ Fulfilled |
| **Transparency obligation towards data subjects** | [AI Transparency](AI_TRANSPARENCY.md) documents all AI components, data flows, and limitations | ✅ Fulfilled |
| **Data must not leave Germany/EU** | `LOCAL_ONNX` for fully local processing; `MISTRAL` (France/EU) for cloud-based EU data residency | ✅ Fulfilled |
| **Purpose limitation** | AI used exclusively for architecture analysis; no profiling, scoring of individuals, or decision automation | ✅ Fulfilled |
| **Data minimization** | Only taxonomy node names/descriptions and business requirement text sent to LLM; no user account data or IP addresses | ✅ Fulfilled |

### Recommendations for Government Operators

1. **Use `LLM_PROVIDER=LOCAL_ONNX`** for maximum data protection — no data leaves the application server
2. **If cloud LLM is required**, prefer EU-based providers (Mistral) and establish a **data processing agreement (DPA)** with the provider
3. **Instruct users** not to include personal data in business requirement texts (see [AI Literacy Concept](AI_LITERACY_CONCEPT.md))
4. **Enable audit logging** (`TAXONOMY_AUDIT_LOGGING=true`) for compliance documentation
5. **Conduct a DPIA** if personal data may be included in analysis inputs

---

## Related Documentation

- [Security](SECURITY.md) — authentication, authorization, and security architecture
- [AI Transparency](AI_TRANSPARENCY.md) — AI model details and data flows
- [AI Literacy Concept](AI_LITERACY_CONCEPT.md) — AI literacy training concept per EU AI Act Art. 4
- [BSI KI Checklist](BSI_KI_CHECKLIST.md) — BSI criteria checklist for AI models
- [Operations Guide](OPERATIONS_GUIDE.md) — backup, recovery, and log management
- [Configuration Reference](CONFIGURATION_REFERENCE.md) — all environment variables
- [Digital Sovereignty](DIGITAL_SOVEREIGNTY.md) — digital sovereignty and data residency
