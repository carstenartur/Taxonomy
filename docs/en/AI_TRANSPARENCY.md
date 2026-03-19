# AI Transparency

This document provides transparency about the AI/LLM components used in the Taxonomy Architecture Analyzer, in accordance with the EU AI Act (KI-Verordnung / KI-VO) and applicable transparency requirements for AI systems used in government and enterprise contexts.

---

## Table of Contents

1. [AI System Classification](#ai-system-classification)
2. [AI Components Overview](#ai-components-overview)
3. [Supported LLM Providers](#supported-llm-providers)
4. [Data Flows](#data-flows)
5. [Scoring and Explainability](#scoring-and-explainability)
6. [Local Embedding Model](#local-embedding-model)
7. [Human Oversight](#human-oversight)
8. [Limitations and Risks](#limitations-and-risks)
9. [Diagnostics and Monitoring](#diagnostics-and-monitoring)
10. [Configuration for Government Use](#configuration-for-government-use)
11. [EU AI Act Compliance Timeline](#eu-ai-act-compliance-timeline)
12. [BSI Compliance](#bsi-compliance)

---

## AI System Classification

Under the EU AI Act (Regulation (EU) 2024/1689), the Taxonomy Architecture Analyzer is classified as:

| Aspect | Assessment |
|---|---|
| **Risk category** | **Minimal risk** — The system assists with architecture analysis, a technical advisory task. It does not make autonomous decisions affecting individuals. |
| **Purpose** | Decision-support tool for IT architects. All AI outputs are advisory and require human review. |
| **Autonomy level** | **None** — The system presents scored suggestions. Human architects accept, reject, or modify all outputs. |
| **Personal data processing** | Minimal — only user account data. Business requirement texts should not contain personal data. |

---

## AI Components Overview

The application uses AI in two distinct areas:

### 1. LLM-Based Analysis (Cloud or Local)

| Aspect | Detail |
|---|---|
| **Purpose** | Score taxonomy nodes (0–100) against a business requirement |
| **Input** | Business requirement text + taxonomy node descriptions |
| **Output** | Relevance scores, justification text, architecture recommendations |
| **Models** | See [Supported LLM Providers](#supported-llm-providers) |
| **Human review** | Required — scores are presented for review, not auto-applied |

### 2. Local Embedding Model (On-Premises)

| Aspect | Detail |
|---|---|
| **Purpose** | Semantic search across taxonomy and architecture elements |
| **Input** | Search queries and taxonomy node descriptions |
| **Output** | Vector embeddings for similarity ranking |
| **Model** | BAAI/bge-small-en-v1.5 (384 dimensions, ONNX Runtime) |
| **Data location** | Entirely local — no external API calls |

### 3. AI in Document Import

The document import feature offers two AI-powered modes in addition to the
rule-based extraction:

| Mode | Prompt Code | Purpose | Data Sent to LLM |
|------|-------------|---------|-------------------|
| **AI-Assisted Extraction** | `extract-default` / `extract-regulation` | Extract actionable requirements from document text | Document text (truncated to 15,000 chars) |
| **Direct Architecture Mapping** | `reg-map-default` | Map regulation text to taxonomy nodes | Document text + full taxonomy node list |

**Data sensitivity notes:**

- Document text is sent to the configured LLM provider (cloud or local)
- The full taxonomy node list (code + name, ~2,500 nodes) is included in
  regulation mapping prompts
- No personal data should be included in uploaded documents
- All AI outputs are presented for human review before being applied
- Prompts are customizable via the Admin panel (see [Document Import](DOCUMENT_IMPORT.md))

---

## Supported LLM Providers

| Provider | Model | API Endpoint | Data Location | Free Tier |
|---|---|---|---|---|
| **Google Gemini** | gemini-2.0-flash | `generativelanguage.googleapis.com` | Google Cloud (US/EU) | 15 RPM, 1500 RPD |
| **OpenAI** | gpt-4o-mini | `api.openai.com` | OpenAI Cloud (US) | Paid only |
| **DeepSeek** | deepseek-chat | `api.deepseek.com` | DeepSeek Cloud (China) | Limited free |
| **Qwen** (Alibaba) | qwen-turbo | `dashscope.aliyuncs.com` | Alibaba Cloud (China) | Limited free |
| **Llama** (Meta via API) | llama-3.3-70b | `api.llama-api.com` | Llama API Cloud | Limited free |
| **Mistral** | mistral-small-latest | `api.mistral.ai` | Mistral Cloud (EU) | Limited free |
| **LOCAL_ONNX** | Built-in scoring model | None (local) | **On-premises only** | Unlimited |

### Provider Selection

The provider is selected in the following priority:

1. **Per-request override** — API clients can specify a provider in the request body
2. **Explicit configuration** — `LLM_PROVIDER` environment variable
3. **Auto-detection** — First available provider by key detection (Gemini → OpenAI → DeepSeek → Qwen → Llama → Mistral)
4. **Fallback** — If no provider is available, analysis returns an error; browsing and search remain functional

---

## Data Flows

### Cloud LLM Analysis (Gemini, OpenAI, etc.)

```
┌─────────────┐     Business requirement text     ┌──────────────┐
│  User        │ ──────────────────────────────────▶│  Application  │
│  (Browser)   │                                    │  Server       │
└─────────────┘                                    └──────┬───────┘
                                                          │
                           Prompt (requirement + taxonomy  │
                           node names/descriptions)        │
                                                          ▼
                                                   ┌──────────────┐
                                                   │  LLM Provider │
                                                   │  (Cloud API)  │
                                                   └──────┬───────┘
                                                          │
                           Scored response (JSON with      │
                           scores and justifications)      │
                                                          ▼
┌─────────────┐     Scored taxonomy tree            ┌──────────────┐
│  User        │ ◀──────────────────────────────────│  Application  │
│  (Browser)   │                                    │  Server       │
└─────────────┘                                    └──────────────┘
```

**Data sent to LLM provider:**

- Business requirement text (user-provided)
- Taxonomy node names and descriptions (from the C3 catalogue)
- Prompt template (system instructions)

**Data NOT sent:**

- User credentials or account information
- IP addresses or session data
- Previous analysis results (unless explicitly included in the prompt)

### Local Analysis (LOCAL_ONNX)

```
┌─────────────┐     Business requirement text     ┌──────────────┐
│  User        │ ──────────────────────────────────▶│  Application  │
│  (Browser)   │                                    │  Server       │
└─────────────┘                                    │  (local ONNX  │
                                                   │   inference)   │
                                                   └──────┬───────┘
                                                          │
┌─────────────┐     Scored taxonomy tree            │
│  User        │ ◀──────────────────────────────────┘
│  (Browser)   │
└─────────────┘
```

**No external data transfer.** All processing happens within the application JVM.

---

## Scoring and Explainability

### How Scores Are Generated

1. The business requirement text is sent to the configured LLM provider along with taxonomy node descriptions
2. The LLM returns a relevance score (0–100) for each node
3. Scores are propagated through the taxonomy tree (parent/child relationships)
4. Nodes with scores ≥ 70 (configurable via `TAXONOMY_ANALYSIS_MIN_SCORE`) are included in architecture views

### Explanation Traces

Each analysis result includes:

- **Score** (0–100) — numeric relevance rating
- **Justification** — text explanation from the LLM for why a node received its score
- **Confidence indicators** — based on the model's self-reported certainty

### Limitations of Explainability

- LLM justifications are generated by the model and may not perfectly reflect the internal scoring logic
- Different providers may score the same requirement differently
- Scores should be interpreted as **advisory suggestions**, not definitive assessments
- The `LOCAL_ONNX` provider uses a simpler scoring algorithm with less nuanced explanations

---

## Local Embedding Model

| Aspect | Detail |
|---|---|
| **Model** | BAAI/bge-small-en-v1.5 |
| **Architecture** | Transformer (BERT-based), 33M parameters |
| **Dimensions** | 384 |
| **Runtime** | ONNX Runtime via DJL (Deep Java Library) |
| **Download** | Auto-downloaded on first use from HuggingFace Hub |
| **Size** | ~50 MB (model files) |
| **Pre-download** | Set `TAXONOMY_EMBEDDING_MODEL_DIR` for air-gapped environments |

### What the Embedding Model Does

- Converts text (taxonomy descriptions, search queries) into 384-dimensional vectors
- Enables semantic similarity search (find relevant nodes by meaning, not just keywords)
- Uses asymmetric retrieval: query texts are prefixed with `"Represent this sentence for searching relevant passages: "` for improved accuracy

### What the Embedding Model Does NOT Do

- Does not send data to external servers
- Does not generate text or make decisions
- Does not process personal data (only taxonomy descriptions)

---

## Human Oversight

The application is designed as a **human-in-the-loop** system:

| Stage | Human Role |
|---|---|
| **Input** | Architect writes the business requirement — controls what is analyzed |
| **Analysis review** | Architect reviews scored taxonomy tree — can accept or reject scores |
| **Relation proposals** | AI proposes architecture relations — architect must explicitly accept |
| **Export** | Architect triggers export — reviews output before distribution |
| **Version control** | Architect commits DSL changes — full version history with attribution |

**No autonomous actions:** The AI never modifies data, commits changes, or produces outputs without explicit user action.

---

## Limitations and Risks

| Risk | Mitigation |
|---|---|
| **Hallucination** | Scores are always shown alongside the full taxonomy context; architects can verify against the catalogue |
| **Bias** | Multiple providers available for cross-validation; local model available as baseline |
| **Data leakage** | Air-gapped operation possible with `LOCAL_ONNX`; no personal data required in prompts |
| **Availability** | Application functions without AI (browsing, search, export); only analysis requires LLM |
| **Inconsistency** | Same input may produce different scores across providers or across runs; this is inherent to LLM technology |
| **Prompt injection** | Input text is size-limited (`TAXONOMY_LIMITS_MAX_BUSINESS_TEXT`); prompt templates are admin-controlled |

---

## Diagnostics and Monitoring

### AI Status Indicator

The application displays an AI status badge in the navigation bar:

| Status | Meaning |
|---|---|
| 🟢 **Connected** | LLM provider is reachable and responding |
| 🟡 **Degraded** | Provider is slow or rate-limited |
| 🔴 **Unavailable** | No LLM provider configured or reachable |

### Admin Diagnostics Endpoint

`GET /api/ai-diagnostics` (ADMIN role required) returns:

- Active LLM provider name and model
- Request/response statistics
- Average response time
- Error rate and last error
- Rate limit status

### LLM Communication Log

The admin panel includes a communication log showing:

- Full prompt text sent to the LLM
- Full response text received
- Timestamps, duration, and token counts
- Provider and model used

This log is available only to administrators and is stored in-memory (not persisted).

---

## Configuration for Government Use

### Recommended Configuration

For government deployments requiring maximum data sovereignty:

```bash
# Use local model — no external API calls
LLM_PROVIDER=LOCAL_ONNX

# Enable local embedding for semantic search
TAXONOMY_EMBEDDING_ENABLED=true

# Pre-download embedding model for air-gapped operation
TAXONOMY_EMBEDDING_MODEL_DIR=/app/models/bge-small-en-v1.5

# Enable audit logging
TAXONOMY_AUDIT_LOGGING=true

# Activate production profile
SPRING_PROFILES_ACTIVE=production,postgres
```

### EU-Based Provider Alternative

If cloud LLM analysis is acceptable but data must stay within the EU:

```bash
# Mistral is hosted in the EU (France)
LLM_PROVIDER=MISTRAL
MISTRAL_API_KEY=your-key

# Or Google Gemini with EU data residency (check current terms)
LLM_PROVIDER=GEMINI
GEMINI_API_KEY=your-key
```

---

## EU AI Act Compliance Timeline

The following table maps the EU AI Act (Regulation (EU) 2024/1689) obligations to the Taxonomy Architecture Analyzer and their respective effective dates:

| Obligation | Effective Date | Status | Assessment |
|---|---|---|---|
| **Prohibited AI practices** (Art. 5) | 02.02.2025 ✅ | ✅ Not affected | No social scoring, emotion recognition, or manipulative techniques |
| **AI Literacy** (Art. 4) | 02.02.2025 ✅ | ✅ Documented | Operators must ensure AI-competent personnel — see [AI Literacy Concept](AI_LITERACY_CONCEPT.md) |
| **GPAI model obligations** (Art. 51–56) | 02.08.2025 ✅ | ✅ Not affected | Taxonomy *uses* GPAI but does not *provide* GPAI models — provider responsibility applies |
| **Full applicability** (Art. 6–49) | 02.08.2026 | ✅ Compliant | Minimal Risk classification — no registration required; transparency obligations met |

### GPAI Provider Responsibility Mapping

Since the Taxonomy Architecture Analyzer consumes General Purpose AI (GPAI) models but does not develop or distribute them, the GPAI-specific obligations under Art. 51–56 apply to the model providers, not to the Taxonomy application:

| GPAI Obligation | Responsible Party | Taxonomy Role |
|---|---|---|
| Technical documentation (Art. 53) | LLM Provider (Google, OpenAI, Mistral, etc.) | Consumer — no obligation |
| Copyright compliance (Art. 53) | LLM Provider | Consumer — no obligation |
| Downstream information (Art. 53) | LLM Provider | Recipient — document provider info in [AI Providers](AI_PROVIDERS.md) |
| Systemic risk assessment (Art. 55) | LLM Provider (if applicable) | Consumer — no obligation |

### AI Literacy Obligations

Since 02.02.2025, Art. 4 requires operators to ensure that personnel interacting with AI systems have sufficient AI literacy. The Taxonomy Architecture Analyzer addresses this through:

- **[AI Literacy Concept](AI_LITERACY_CONCEPT.md)** — training concept with role-specific curricula
- **Explanation Traces** — every AI output includes justification text
- **Human-in-the-Loop design** — no autonomous actions; all outputs require human review

---

## BSI Compliance

For German federal administration deployments, the BSI (Bundesamt für Sicherheit in der Informationstechnik) has published criteria for AI systems in government use. The Taxonomy Architecture Analyzer's compliance is documented in:

- **[BSI KI Checklist](BSI_KI_CHECKLIST.md)** — detailed mapping of BSI criteria to Taxonomy implementation

---

## Related Documentation

- [AI Providers](AI_PROVIDERS.md) — detailed provider configuration and API keys
- [AI Literacy Concept](AI_LITERACY_CONCEPT.md) — training concept per EU AI Act Art. 4
- [BSI KI Checklist](BSI_KI_CHECKLIST.md) — BSI criteria checklist for AI models
- [Data Protection](DATA_PROTECTION.md) — personal data processing and GDPR compliance
- [Security](SECURITY.md) — authentication, authorization, and security architecture
- [Configuration Reference](CONFIGURATION_REFERENCE.md) — all environment variables
- [Digital Sovereignty](DIGITAL_SOVEREIGNTY.md) — digital sovereignty and openCode compatibility
