# Taxonomy Architecture Analyzer — Curl Examples

Quick-reference curl examples for common workflows. All examples assume the application runs on `http://localhost:8080`.

For the full interactive API reference, see [Swagger UI](http://localhost:8080/swagger-ui.html) or the [API Reference redirect](API_REFERENCE.md) which links to the auto-generated Swagger documentation.

---

## Taxonomy & Status

```bash
# Get full taxonomy tree
curl http://localhost:8080/api/taxonomy

# Check AI/LLM provider status
curl http://localhost:8080/api/ai-status

# Check startup status
curl http://localhost:8080/api/status/startup
```

## Analysis

```bash
# Standard analysis (POST, JSON body)
curl -X POST http://localhost:8080/api/analyze \
  -H "Content-Type: application/json" \
  -d '{"businessText":"Provide secure voice communications","includeArchitectureView":true}'

# Streaming analysis (SSE)
curl -N "http://localhost:8080/api/analyze-stream?businessText=voice+comms+for+deployed+forces"

# Interactive mode — analyze children of a specific node
curl "http://localhost:8080/api/analyze-node?parentCode=C3_ROOT&businessText=voice+comms"

# Leaf justification
curl -X POST http://localhost:8080/api/justify-leaf \
  -H "Content-Type: application/json" \
  -d '{"nodeCode":"CR-1047","businessText":"Secure voice communications","scores":{"CR-1047":87},"reasons":{"CR-1047":"Matched on voice"}}'
```

## Search

```bash
# Full-text search
curl "http://localhost:8080/api/search?q=voice+communications&maxResults=20"

# Semantic search (requires embedding enabled)
curl "http://localhost:8080/api/search/semantic?q=voice+communications&maxResults=20"

# Hybrid search (RRF)
curl "http://localhost:8080/api/search/hybrid?q=voice+communications&maxResults=20"

# Find similar nodes
curl "http://localhost:8080/api/search/similar/CR-1047?topK=5"

# Graph-semantic search
curl "http://localhost:8080/api/search/graph?q=voice+communications&maxResults=20"

# Check embedding status
curl http://localhost:8080/api/embedding/status
```

## Graph Explorer

```bash
# Upstream neighbourhood
curl "http://localhost:8080/api/graph/node/CR-1047/upstream?maxHops=2"

# Downstream neighbourhood
curl "http://localhost:8080/api/graph/node/CR-1047/downstream?maxHops=2"

# Failure impact analysis
curl "http://localhost:8080/api/graph/node/CR-1047/failure-impact?maxHops=3"

# Enriched failure impact (with requirement coverage correlation)
curl "http://localhost:8080/api/graph/node/CR-1047/enriched-failure-impact?maxHops=3"

# Requirement impact analysis
curl -X POST http://localhost:8080/api/graph/impact \
  -H "Content-Type: application/json" \
  -d '{"scores":{"CR-1047":87,"CP-1022":72},"businessText":"Secure voice","maxHops":2}'
```

## Relations & Proposals

```bash
# List all relations
curl http://localhost:8080/api/relations

# List relations filtered by type
curl "http://localhost:8080/api/relations?type=REALIZES"

# Relations for a specific node
curl http://localhost:8080/api/node/CP-1023/relations

# Get relation count
curl http://localhost:8080/api/relations/count

# Create a relation manually
curl -X POST http://localhost:8080/api/relations \
  -H "Content-Type: application/json" \
  -d '{"sourceCode":"CP-1023","targetCode":"CR-1047","relationType":"REALIZES","provenance":"MANUAL"}'

# Trigger relation proposals
curl -X POST http://localhost:8080/api/proposals/propose \
  -H "Content-Type: application/json" \
  -d '{"sourceCode":"CR-1047","relationType":"SUPPORTS","limit":"10"}'

# List all proposals
curl http://localhost:8080/api/proposals

# List pending proposals only
curl http://localhost:8080/api/proposals/pending

# Accept a proposal
curl -X POST http://localhost:8080/api/proposals/1/accept

# Reject a proposal
curl -X POST http://localhost:8080/api/proposals/1/reject

# Revert a decision
curl -X POST http://localhost:8080/api/proposals/1/revert

# Bulk accept/reject
curl -X POST http://localhost:8080/api/proposals/bulk \
  -H "Content-Type: application/json" \
  -d '{"ids":[1,2,3],"action":"ACCEPT"}'
```

## Quality & Coverage

```bash
# Quality dashboard
curl http://localhost:8080/api/relations/metrics

# Quality by type
curl http://localhost:8080/api/relations/metrics/by-type

# Quality by provenance
curl http://localhost:8080/api/relations/metrics/by-provenance

# Top rejected proposals
curl "http://localhost:8080/api/relations/metrics/top-rejected?limit=10"

# Record requirement coverage
curl -X POST http://localhost:8080/api/coverage/record \
  -H "Content-Type: application/json" \
  -d '{"requirementId":"REQ-101","requirementText":"Secure comms","scores":{"CP-1023":85,"BP-1327":72},"minScore":50}'

# Coverage statistics
curl http://localhost:8080/api/coverage/statistics

# Coverage density map
curl http://localhost:8080/api/coverage/density

# Coverage for a specific node
curl http://localhost:8080/api/coverage/node/CP-1023
```

## Gap Analysis, Recommendations & Patterns

```bash
# Gap analysis
curl -X POST http://localhost:8080/api/gap/analyze \
  -H "Content-Type: application/json" \
  -d '{"scores":{"CP":85,"BP":72},"businessText":"Secure voice","minScore":50}'

# Architecture recommendation
curl -X POST http://localhost:8080/api/recommend \
  -H "Content-Type: application/json" \
  -d '{"scores":{"CP":85,"BP":72},"businessText":"Secure voice","minScore":50}'

# Pattern detection (by node)
curl "http://localhost:8080/api/patterns/detect?nodeCode=CP"

# Pattern detection (by scores)
curl -X POST http://localhost:8080/api/patterns/detect \
  -H "Content-Type: application/json" \
  -d '{"scores":{"CP":85,"BP":72},"minScore":50}'
```

## Export & Import

```bash
# Export scores as JSON
curl -X POST http://localhost:8080/api/scores/export \
  -H "Content-Type: application/json" \
  -d '{"requirement":"Secure voice","scores":{"CO":90,"CR":70},"reasons":{"CO":"Voice in scope"},"provider":"GEMINI"}'

# Import scores from JSON
curl -X POST http://localhost:8080/api/scores/import \
  -H "Content-Type: application/json" \
  -d '{"version":1,"requirement":"Secure voice","scores":{"CO":90,"CR":70}}'

# Export Visio diagram
curl -X POST http://localhost:8080/api/diagram/visio \
  -H "Content-Type: application/json" \
  -d '{"businessText":"Secure voice"}' --output architecture.vsdx

# Export ArchiMate XML
curl -X POST http://localhost:8080/api/diagram/archimate \
  -H "Content-Type: application/json" \
  -d '{"businessText":"Secure voice"}' --output architecture.xml

# Export Mermaid flowchart
curl -X POST http://localhost:8080/api/diagram/mermaid \
  -H "Content-Type: application/json" \
  -d '{"businessText":"Secure voice"}'

# Export report (Markdown, HTML, DOCX, JSON)
curl -X POST http://localhost:8080/api/report/markdown \
  -H "Content-Type: application/json" \
  -d '{"businessText":"Secure voice","scores":{"CP-1023":92}}' --output report.md

curl -X POST http://localhost:8080/api/report/html \
  -H "Content-Type: application/json" \
  -d '{"businessText":"Secure voice","scores":{"CP-1023":92}}' --output report.html

# Import ArchiMate XML model
curl -X POST http://localhost:8080/api/import/archimate \
  -F "file=@model.xml"
```

## Architecture DSL

```bash
# Export current architecture as DSL text
curl http://localhost:8080/api/dsl/export

# Get current architecture as JSON
curl http://localhost:8080/api/dsl/current

# Parse DSL text
curl -X POST http://localhost:8080/api/dsl/parse \
  -H "Content-Type: text/plain" \
  -d 'meta
  language "taxdsl"
  version "1.0"
element CP-1023 type Capability
  title "Communication and Information System Capabilities"'

# Validate DSL text
curl -X POST http://localhost:8080/api/dsl/validate \
  -H "Content-Type: text/plain" \
  -d 'element CP-1023 type Capability'

# Commit DSL to a branch
curl -X POST http://localhost:8080/api/dsl/commit \
  -H "Content-Type: application/json" \
  -d '{"dslText":"element CP-1023 type Capability\n  title \"Communication and Information System Capabilities\"","branch":"main","message":"Add CP-1023"}'

# List branches
curl http://localhost:8080/api/dsl/branches

# Get commit history
curl "http://localhost:8080/api/dsl/history?branch=main"

# Architecture summary
curl http://localhost:8080/api/architecture/summary
```

## Administration

```bash
# Check admin auth status
curl http://localhost:8080/api/admin/status

# Verify admin password
curl -X POST http://localhost:8080/api/admin/verify \
  -H "Content-Type: application/json" \
  -d '{"password":"your_admin_password"}'

# Get LLM diagnostics (requires admin token)
curl http://localhost:8080/api/diagnostics \
  -H "X-Admin-Token: your_admin_password"

# List prompt templates (requires admin token)
curl http://localhost:8080/api/prompts \
  -H "X-Admin-Token: your_admin_password"

# Explanation trace for a single node
curl -X POST http://localhost:8080/api/explain/CP-1023 \
  -H "Content-Type: application/json" \
  -d '{"businessText":"Secure voice","scores":{"CP-1023":92}}'

# Explanation traces for all scored nodes
curl -X POST http://localhost:8080/api/explain \
  -H "Content-Type: application/json" \
  -d '{"businessText":"Secure voice","scores":{"CP-1023":92,"CO-1011":88}}'
```
