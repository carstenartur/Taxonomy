# Taxonomy Architecture Analyzer — Curl Workflow Examples

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

## Workflow 5: Git State Monitoring & Staleness Detection

Check repository state, detect stale projections, and monitor branch status.

```bash
# Step 1 — Check full repository state for the draft branch
curl -u admin:admin "http://localhost:8080/api/git/state?branch=draft"

# Step 2 — Quick staleness check (used by UI polling every 10s)
curl -u admin:admin "http://localhost:8080/api/git/stale?branch=draft"

# Step 3 — Detailed projection/index freshness
curl -u admin:admin "http://localhost:8080/api/git/projection?branch=draft"

# Step 4 — List all branches
curl -u admin:admin "http://localhost:8080/api/git/branches"
```

---

## Workflow 6: Context Navigation — Browse, Compare, Transfer

Open versioned architecture snapshots, compare branches, and selectively transfer changes.

```bash
# Step 1 — Check current context
curl -u admin:admin http://localhost:8080/api/context/current

# Step 2 — Open a read-only context for the "main" branch
curl -u admin:admin -X POST "http://localhost:8080/api/context/open?branch=main&readOnly=true"

# Step 3 — Compare two branches
curl -u admin:admin "http://localhost:8080/api/context/compare?leftBranch=draft&rightBranch=main"

# Step 4 — Create a variant branch from the current context
curl -u admin:admin -X POST "http://localhost:8080/api/context/variant?name=experiment-1"

# Step 5 — Preview selective transfer
curl -u admin:admin -X POST http://localhost:8080/api/context/copy-back/preview \
  -H "Content-Type: application/json" \
  -d '{"sourceContextId":"ctx-002","targetContextId":"ctx-001","selectedElementIds":["CP-1023"],"selectedRelationIds":[],"mode":"COPY"}'

# Step 6 — Apply the transfer
curl -u admin:admin -X POST http://localhost:8080/api/context/copy-back/apply \
  -H "Content-Type: application/json" \
  -d '{"sourceContextId":"ctx-002","targetContextId":"ctx-001","selectedElementIds":["CP-1023"],"selectedRelationIds":[],"mode":"COPY"}'

# Step 7 — Navigate back to the previous context
curl -u admin:admin -X POST http://localhost:8080/api/context/back

# Step 8 — Return to the origin context
curl -u admin:admin -X POST http://localhost:8080/api/context/return-to-origin

# Step 9 — View navigation history
curl -u admin:admin http://localhost:8080/api/context/history
```

---

## Workflow 7: Preferences Management (Admin)

View, update, and audit runtime preferences.

```bash
# Step 1 — View all current preferences
curl -u admin:admin http://localhost:8080/api/preferences

# Step 2 — Update LLM rate limit and timeout
curl -u admin:admin -X PUT http://localhost:8080/api/preferences \
  -H "Content-Type: application/json" \
  -d '{"llm.rpm":"10","llm.timeout.seconds":"45"}'

# Step 3 — View the change history (Git log)
curl -u admin:admin http://localhost:8080/api/preferences/history

# Step 4 — Reset all preferences to defaults
curl -u admin:admin -X POST http://localhost:8080/api/preferences/reset
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

# Git repository state
curl -u admin:admin "http://localhost:8080/api/git/state?branch=draft"

# Quick staleness check
curl -u admin:admin "http://localhost:8080/api/git/stale?branch=draft"

# View all preferences (admin)
curl -u admin:admin http://localhost:8080/api/preferences

# Browse documentation table of contents
curl -u admin:admin http://localhost:8080/help

# Format DSL text
curl -u admin:admin -X POST http://localhost:8080/api/dsl/format \
  -H "Content-Type: text/plain" \
  -d 'element CP-1023 type Capability { title: "CIS"; }'

# Undo last commit
curl -u admin:admin -X POST "http://localhost:8080/api/dsl/undo?branch=draft"

# Versioned search
curl -u admin:admin "http://localhost:8080/api/dsl/history/search-versioned?query=communication&currentBranch=draft"
```
