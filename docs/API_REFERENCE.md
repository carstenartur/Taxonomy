# Taxonomy Architecture Analyzer — API Reference

> The API is fully auto-documented from the source code via [springdoc-openapi](https://springdoc.org/).
> This file is a redirect to the live, always-up-to-date interactive documentation.

---

## Interactive API Documentation

| URL | Description |
|---|---|
| [`/swagger-ui.html`](/swagger-ui.html) | **Swagger UI** — browse and test every endpoint interactively |
| [`/v3/api-docs`](/v3/api-docs) | OpenAPI 3.0 specification (JSON) |
| [`/v3/api-docs.yaml`](/v3/api-docs.yaml) | OpenAPI 3.0 specification (YAML) |

All endpoints are grouped by functional area (Taxonomy, Analysis, Search, Relations, Proposals,
Graph Queries, Quality Metrics, Requirement Coverage, Gap Analysis, Architecture Recommendation,
Pattern Detection, Export, Report Export, Architecture DSL, Administration, Embedding, and more).

## Deployment

A `render.yaml` blueprint is included for one-click deployment to [Render.com](https://render.com).
See [docs/DEPLOYMENT_GUIDE.md](DEPLOYMENT_GUIDE.md) for Docker and Render.com setup instructions.

## Additional References

| Document | Contents |
|---|---|
| [docs/CURL_EXAMPLES.md](CURL_EXAMPLES.md) | Quick-reference curl commands for every endpoint |
| [docs/USER_GUIDE.md](USER_GUIDE.md) | End-user guide including best practices |
| [docs/ARCHITECTURE.md](ARCHITECTURE.md) | System architecture, modules, and API versioning strategy |
| [docs/CONCEPTS.md](CONCEPTS.md) | Glossary of terms used throughout the API |

## Error Responses

| HTTP Code | Meaning | Notes |
|---|---|---|
| `200 OK` | Success | Normal response; analysis errors are reported in the body |
| `400 Bad Request` | Invalid input | Missing required parameters or blank text fields |
| `401 Unauthorized` | Authentication required | Admin-only endpoints when `ADMIN_PASSWORD` is set |
| `503 Service Unavailable` | Taxonomy loading | Taxonomy data is still initialising; poll `/api/status/startup` |
| `500 Internal Server Error` | Server error | Unexpected exception (LLM timeout, I/O error during export) |
