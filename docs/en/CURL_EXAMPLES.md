# Curl Examples for Automation & Integration

> ⚠️ **Note:** The following examples are intended for **developers and automation**
> (CI/CD pipelines, batch processing, system integration).
> For daily work, use the graphical user interface. See:
> - [User Guide](USER_GUIDE.md) for the Web UI
> - [Examples](EXAMPLES.md) for GUI-based workflows

End-to-end curl workflows for common tasks. For the full endpoint reference, see [API Reference](API_REFERENCE.md)
or [Swagger UI](http://localhost:8080/swagger-ui.html).

**Authentication:** All API endpoints require HTTP Basic auth (`-u admin:admin`).

---

> 💡 **GUI equivalent:** **Analyze** tab → Enter text → **Analyze** → Export buttons in Architecture View. See [Example 1](EXAMPLES.md#1-requirement--architecture).

## Workflow 1: Analyze a Requirement and Export ArchiMate

Analyze a business requirement, then export the resulting architecture as ArchiMate XML.

```bash
# Step 1 — Analyze the requirement
curl -u admin:admin -X POST http://localhost:8080/api/analyze \
  -H "Content-Type: application/json" \
  -d '{"businessText":"Provide integrated communication services for hospital staff","includeArchitectureView":true}'

# Step 2 — Export the architecture as ArchiMate XML
curl -u admin:admin -X POST http://localhost:8080/api/diagram/archimate \
  -H "Content-Type: application/json" \
  -d '{"businessText":"Provide integrated communication services for hospital staff"}' \
  --output architecture.xml

# Alternative exports:
# Visio
curl -u admin:admin -X POST http://localhost:8080/api/diagram/visio \
  -H "Content-Type: application/json" \
  -d '{"businessText":"Provide integrated communication services for hospital staff"}' \
  --output architecture.vsdx

# Mermaid
curl -u admin:admin -X POST http://localhost:8080/api/diagram/mermaid \
  -H "Content-Type: application/json" \
  -d '{"businessText":"Provide integrated communication services for hospital staff"}'
```

---

> 💡 **GUI equivalent:** **Analyze** tab → Analyze → **Gaps** tab → **🔍 Start Gap Analysis** → **Recommendations** tab. See [Example 3](EXAMPLES.md#3-architecture-gap-analysis).

## Workflow 2: Analyze → Gap Analysis → Recommendation

Identify architecture gaps and get AI-generated recommendations.

```bash
# Step 1 — Analyze the requirement
curl -u admin:admin -X POST http://localhost:8080/api/analyze \
  -H "Content-Type: application/json" \
  -d '{"businessText":"Integrated hospital communication services","includeArchitectureView":true}'

# Step 2 — Run gap analysis on the scored nodes
curl -u admin:admin -X POST http://localhost:8080/api/gap/analyze \
  -H "Content-Type: application/json" \
  -d '{"scores":{"CP":85,"BP":72},"businessText":"Integrated hospital communication services","minScore":50}'

# Step 3 — Get architecture recommendations
curl -u admin:admin -X POST http://localhost:8080/api/recommend \
  -H "Content-Type: application/json" \
  -d '{"scores":{"CP":85,"BP":72},"businessText":"Integrated hospital communication services","minScore":50}'
```

---

> 💡 **GUI equivalent:** **Relation Proposals** panel → **Propose Relations** → Accept/Reject → Graph Explorer. See [Example 4](EXAMPLES.md#4-relation-proposals).

## Workflow 3: Propose Relations → Review → Verify in Graph

Generate relation proposals, accept or reject them, and verify the result in the graph.

```bash
# Step 1 — Trigger relation proposals for a node
curl -u admin:admin -X POST http://localhost:8080/api/proposals/propose \
  -H "Content-Type: application/json" \
  -d '{"sourceCode":"CR-1047","relationType":"SUPPORTS","limit":"10"}'

# Step 2 — Review pending proposals
curl -u admin:admin http://localhost:8080/api/proposals/pending

# Step 3 — Accept a proposal (replace 1 with the actual proposal ID)
curl -u admin:admin -X POST http://localhost:8080/api/proposals/1/accept

# Step 4 — Verify the new relation in the graph
curl -u admin:admin "http://localhost:8080/api/graph/node/CR-1047/downstream?maxHops=2"

# Alternatively: bulk accept/reject multiple proposals
curl -u admin:admin -X POST http://localhost:8080/api/proposals/bulk \
  -H "Content-Type: application/json" \
  -d '{"ids":[1,2,3],"action":"ACCEPT"}'
```

---

> 💡 **GUI equivalent:** **DSL Editor** (DSL tab) → Edit → **💾 Save** → **Variants Panel** for merge. See [Example 8](EXAMPLES.md#8-architecture-dsl-workflow).

## Workflow 4: DSL Export → Edit → Commit → Diff → Merge

Export the architecture as DSL text, edit it, commit changes, and merge branches.

```bash
# Step 1 — Export the current architecture as DSL text
curl -u admin:admin http://localhost:8080/api/dsl/export

# Step 2 — Commit edited DSL to a feature branch
curl -u admin:admin -X POST http://localhost:8080/api/dsl/commit \
  -H "Content-Type: application/json" \
  -d '{"dslText":"element CP-1023 type Capability {\n  title: \"Communication and Information System Capabilities\";\n}","branch":"feature/add-comms","message":"Add CP-1023 capability"}'

# Step 3 — Compare branches
curl -u admin:admin "http://localhost:8080/api/dsl/diff?sourceBranch=feature/add-comms&targetBranch=main"

# Step 4 — Merge the feature branch into main
curl -u admin:admin -X POST http://localhost:8080/api/dsl/merge \
  -H "Content-Type: application/json" \
  -d '{"sourceBranch":"feature/add-comms","targetBranch":"main"}'

# Step 5 — View commit history
curl -u admin:admin "http://localhost:8080/api/dsl/history?branch=main"
```

---

> 💡 **GUI equivalent:** All endpoints listed here are also accessible via the graphical user interface. See [User Guide](USER_GUIDE.md).

## Quick Reference: Individual Endpoints

For one-off commands not part of a workflow, see the [API Reference](API_REFERENCE.md). Common examples:

```bash
# Get full taxonomy tree
curl -u admin:admin http://localhost:8080/api/taxonomy

# Full-text search
curl -u admin:admin "http://localhost:8080/api/search?q=voice+communications&maxResults=20"

# Semantic search
curl -u admin:admin "http://localhost:8080/api/search/semantic?q=voice+communications&maxResults=20"

# Check AI/LLM provider status
curl -u admin:admin http://localhost:8080/api/ai-status

# Upstream graph exploration
curl -u admin:admin "http://localhost:8080/api/graph/node/CR-1047/upstream?maxHops=2"

# Failure impact analysis
curl -u admin:admin "http://localhost:8080/api/graph/node/CR-1047/failure-impact?maxHops=3"

# Export report as Markdown
curl -u admin:admin -X POST http://localhost:8080/api/report/markdown \
  -H "Content-Type: application/json" \
  -d '{"businessText":"Hospital communications","scores":{"CP-1023":92}}' --output report.md
```
