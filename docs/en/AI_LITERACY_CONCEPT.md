# AI Literacy Training Concept

This document describes the training concept for users of the Taxonomy Architecture Analyzer in accordance with the **AI Literacy obligation under EU AI Act Art. 4** (in effect since 02.02.2025). Operators and users of AI systems must ensure that their personnel have sufficient AI competence.

---

## Table of Contents

1. [Legal Background](#legal-background)
2. [Target Groups and Competence Levels](#target-groups-and-competence-levels)
3. [Training Content by Role](#training-content-by-role)
4. [What the AI Does in the Taxonomy Analyzer](#what-the-ai-does-in-the-taxonomy-analyzer)
5. [What the AI Does Not Do](#what-the-ai-does-not-do)
6. [Explainability of AI Results](#explainability-of-ai-results)
7. [Recommendations for Introductory Workshops](#recommendations-for-introductory-workshops)
8. [Learning Resources](#learning-resources)
9. [Documentation Obligations](#documentation-obligations)

---

## Legal Background

| Aspect | Detail |
|---|---|
| **Legal Basis** | EU AI Act (Regulation (EU) 2024/1689), Article 4 — AI Literacy |
| **In Effect Since** | 02 February 2025 |
| **Obligation** | Providers and deployers must ensure that their personnel have a sufficient level of AI competence |
| **Application to Taxonomy** | As the deployer of the Taxonomy Architecture Analyzer (risk class: Minimal Risk), the deploying authority must implement AI literacy measures |

> **Art. 4 EU AI Act:** "Providers and deployers of AI systems shall take measures to ensure, to their best extent, a sufficient level of AI literacy of their staff and other persons dealing with the operation and use of AI systems on their behalf, taking into account their technical knowledge, experience, education and training and the context the AI systems are to be used in, and considering the persons or groups of persons on whom the AI systems are to be used."

---

## Target Groups and Competence Levels

| Target Group | Role in System | Competence Level | Training Scope |
|---|---|---|---|
| **IT Architects** (End Users) | USER / ARCHITECT | Basic Competence | 2–4 Hours |
| **Team Leads / Project Managers** | USER (read-only) | Orientation Knowledge | 1–2 Hours |
| **Administrators** | ADMIN | Advanced Competence | 4–8 Hours |
| **IT Security Officers** | Advisory | Specialist Competence in AI Security | 4 Hours |
| **Data Protection Officers** | Advisory | Specialist Competence in AI Data Protection | 2 Hours |

---

## Training Content by Role

### For All Users (Basic Competence)

| # | Topic | Learning Objective |
|---|---|---|
| 1 | **What Is an LLM?** | Understand that Large Language Models are text-based probability models, not knowledgeable systems |
| 2 | **Interpreting AI Scores Correctly** | Know that scores (0–100) are relevance estimates, not facts; different providers may produce different results |
| 3 | **Evaluating Justification Texts** | Ability to critically review AI-generated justifications and compare them with one's own domain expertise |
| 4 | **Recognizing Limitations** | Awareness of hallucinations, bias, and inconsistencies as inherent LLM characteristics |
| 5 | **Human-in-the-Loop Principle** | Understanding one's own responsibility: AI suggests, humans decide |

### For Architects (Advanced Competence)

| # | Topic | Learning Objective |
|---|---|---|
| 6 | **Formulating Requirements for AI** | Ability to formulate business requirements in a way that yields relevant AI results |
| 7 | **Provider Comparison** | Know when and how different LLM providers should be compared |
| 8 | **Accept/Reject Workflow** | Structured evaluation of AI suggestions for architecture relations |
| 9 | **No Personal Data in Prompts** | Responsibility to avoid including PII in business requirement texts |

### For Administrators (Specialist Competence)

| # | Topic | Learning Objective |
|---|---|---|
| 10 | **Provider Configuration** | Secure setup of LLM providers; air-gapped operation with LOCAL_ONNX |
| 11 | **Prompt Template Management** | Responsibility for prompt templates; understanding prompt injection risks |
| 12 | **LLM Communication Log** | Monitoring AI communication; detecting anomalous behavior |
| 13 | **Rate Limiting and Costs** | Configuration of rate limits; cost control for cloud providers |
| 14 | **AI Diagnostics Endpoint** | Using `GET /api/ai-diagnostics` for system monitoring |

---

## What the AI Does in the Taxonomy Analyzer

| Function | Description | Intervenes in Decisions? |
|---|---|---|
| **Taxonomy Scoring** | Evaluates the relevance of ~2,500 taxonomy nodes for a business requirement | ❌ No — suggestion only |
| **Justification Generation** | Creates textual justifications for the score assignment | ❌ No — explanation only |
| **Architecture Suggestions** | Suggests relations between architecture elements | ❌ No — requires Accept/Reject |
| **Semantic Search** | Finds taxonomy nodes based on semantic similarity (local embedding model) | ❌ No — search results only |

**Key Statement:** The AI in the Taxonomy Analyzer is a **recommendation system** (Minimal Risk according to EU AI Act). It does not make decisions and does not modify data autonomously.

---

## What the AI Does Not Do

| Action | Status |
|---|---|
| Make autonomous architecture decisions | ❌ Not implemented |
| Modify data without user interaction | ❌ Not implemented |
| Process personal data | ❌ Not intended |
| Perform its own model training | ❌ Not implemented |
| Access external systems (other than the configured LLM provider) | ❌ Not implemented |
| Social scoring or emotion recognition | ❌ Not implemented (Art. 5 EU AI Act — prohibited practices) |

---

## Explainability of AI Results

The Taxonomy Analyzer provides several mechanisms for traceability:

### 1. Score and Justification

Each evaluated taxonomy node displays:
- **Score** (0–100): Numerical relevance estimate
- **Justification**: Textual explanation from the LLM for why this score was assigned

### 2. LLM Communication Log (Admin)

Administrators can view in the Admin Panel:
- Complete prompt text sent to the LLM
- Complete response from the LLM
- Timestamp, duration, token counts
- Provider and model used

### 3. Version Control

All architecture decisions are versioned:
- JGit-based repository with commit history
- Author attribution for each commit
- Diff view between versions

**Reference:** See [AI Transparency — Scoring and Explainability](AI_TRANSPARENCY.md#scoring-and-explainability)

---

## Recommendations for Introductory Workshops

### Workshop 1: Fundamentals Workshop (all users, 2 hours)

| Block | Duration | Content |
|---|---|---|
| **Introduction** | 20 min | What is AI/LLM? How does the Taxonomy Analyzer work? |
| **Live Demo** | 30 min | Enter a requirement → Scoring → Interpret results |
| **Hands-On** | 40 min | Participants perform their own analysis; discuss results |
| **Critical Thinking** | 20 min | Examples of incorrect/inaccurate AI results; how to recognize them? |
| **Q&A** | 10 min | Open questions |

### Workshop 2: Architect Workshop (ARCHITECT role, 2 hours)

| Block | Duration | Content |
|---|---|---|
| **Requirement Design** | 30 min | How do I formulate requirements for optimal AI results? |
| **Provider Comparison** | 30 min | Analyze the same requirement with different providers |
| **Accept/Reject Workflow** | 30 min | Systematically evaluate architecture suggestions |
| **DSL and Versioning** | 20 min | Commit changes, use diff view |
| **Best Practices** | 10 min | Summary of recommendations |

### Workshop 3: Admin Workshop (ADMIN role, 4 hours)

| Block | Duration | Content |
|---|---|---|
| **Provider Configuration** | 60 min | Set up LLM providers; air-gapped operation; rate limits |
| **Prompt Template Management** | 30 min | Customize templates; prompt injection risks |
| **Monitoring and Diagnostics** | 30 min | LLM Communication Log; AI Diagnostics endpoint |
| **Security and Data Protection** | 60 min | Audit logging; BSI checklist; GDPR compliance |
| **Troubleshooting** | 30 min | Common errors and their resolution |
| **Q&A** | 30 min | Open questions and experience sharing |

---

## Learning Resources

### Internal Documentation

| Document | Relevance |
|---|---|
| [AI Transparency](AI_TRANSPARENCY.md) | AI components, data flows, limitations |
| [AI Providers](AI_PROVIDERS.md) | Provider details and configuration |
| [User Guide](USER_GUIDE.md) | Step-by-step instructions |
| [BSI KI Checklist](BSI_KI_CHECKLIST.md) | BSI criteria for AI deployment |
| [Data Protection](DATA_PROTECTION.md) | GDPR compliance |

### External Resources

| Resource | Description |
|---|---|
| [EU AI Act (Full Text)](https://eur-lex.europa.eu/eli/reg/2024/1689/oj) | Regulation (EU) 2024/1689 |
| [BSI — Artificial Intelligence](https://www.bsi.bund.de/DE/Themen/Unternehmen-und-Organisationen/Informationen-und-Empfehlungen/Kuenstliche-Intelligenz/kuenstliche-intelligenz_node.html) | BSI recommendations for AI security |
| [BfDI — AI and Data Protection](https://www.bfdi.bund.de/) | Data protection assessment of AI systems |

---

## Documentation Obligations

The deploying authority should maintain the following records:

| Record | Content | Retention |
|---|---|---|
| **Training Attendance** | List of trained personnel with date and workshop type | At least 3 years |
| **Competence Confirmation** | Participants confirm understanding of AI limitations and their responsibilities | At least 3 years |
| **Refresher Training** | Annual refresher upon significant changes to the system | Ongoing |
| **New Employees** | Training before first use of the system | Before first use |

---

## Related Documentation

- [AI Transparency](AI_TRANSPARENCY.md) — AI transparency and data flows
- [BSI KI Checklist](BSI_KI_CHECKLIST.md) — BSI criteria catalog checklist
- [Data Protection](DATA_PROTECTION.md) — Data protection and GDPR
- [Security](SECURITY.md) — Security architecture
- [User Guide](USER_GUIDE.md) — User manual
