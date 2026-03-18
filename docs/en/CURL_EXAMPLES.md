# Taxonomy Architecture Analyzer — Curl Workflow Examples

> **Purpose:** These examples show how to automate Taxonomy Architecture Analyzer
> workflows using command-line tools. They are intended for CI/CD pipelines,
> batch processing, and system integration — **not** as a substitute for the
> [web-based GUI](USER_GUIDE.md) for interactive use.

End-to-end curl workflows for common tasks. For the full endpoint reference, see [API Reference](API_REFERENCE.md)
or [Swagger UI](http://localhost:8080/swagger-ui.html).

**Authentication:** All API endpoints require HTTP Basic auth (`-u admin:admin`).

---

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
