# Deployment Checklist — Government / Enterprise Environments

This checklist is a release gate for controlled Taxonomy deployments. A checked item must be backed by an observed result, configuration record, or test protocol — not only by the presence of documentation.

## 1. Architecture and persistence decision

- [ ] **Deployment topology approved** — VM, Kubernetes, Docker host, or managed platform documented.
- [ ] **Database selected**:
  - PostgreSQL / SQL Server / Oracle for multi-user production, or
  - file-backed HSQLDB only for small controlled installations.
- [ ] **No in-memory production database** — `jdbc:hsqldb:mem:` is not used for persistent deployments.
- [ ] **Schema management defined** — use the production profile; plan migration tooling before incompatible schema changes.
- [ ] **Persistent Lucene index configured** — `TAXONOMY_SEARCH_DIRECTORY_TYPE=local-filesystem` and a persistent root path.
- [ ] **Persistent storage mounted** — `/app/data` or the database volume survives container recreation.
- [ ] **Restart persistence test passed** — create a user/workspace/change, restart or recreate the container, and verify the state remains available.
- [ ] **Backup and restore test completed** — database, Git/JGit state, uploaded provenance data and configuration secrets are covered.

### Supported embedded production baseline

The supplied `docker-compose.prod.yml` configures:

```text
SPRING_PROFILES_ACTIVE=hsqldb
TAXONOMY_DATASOURCE_URL=jdbc:hsqldb:file:/app/data/taxonomydb;hsqldb.default_table_type=cached;shutdown=true
TAXONOMY_DDL_AUTO=update
TAXONOMY_SEARCH_DIRECTORY_TYPE=local-filesystem
TAXONOMY_SEARCH_DIRECTORY_ROOT=/app/data/lucene-index
```

For larger or business-critical deployments, use the `production` profile together with an external database profile, for example `production,postgres`.

## 2. Security configuration

- [ ] **Strong initial administrator password supplied** — `TAXONOMY_ADMIN_PASSWORD` is set; production Compose refuses a missing value.
- [ ] **Default `admin/admin` rejected** — verify the deployment does not start or go live with the default password.
- [ ] **Password change policy enabled** — `TAXONOMY_REQUIRE_PASSWORD_CHANGE=true`.
- [ ] **Brute-force protection enabled** — `TAXONOMY_LOGIN_RATE_LIMIT=true`.
- [ ] **Security audit logging enabled** — `TAXONOMY_AUDIT_LOGGING=true`.
- [ ] **Swagger restricted or disabled** — `TAXONOMY_SWAGGER_PUBLIC=false` and preferably `TAXONOMY_SPRINGDOC_ENABLED=false`.
- [ ] **TLS termination configured** — use Caddy, nginx, ingress, or native TLS; port 8080 remains internal.
- [ ] **Role assignment reviewed** — USER, ARCHITECT and ADMIN follow least privilege.
- [ ] **Admin functions verified** — diagnostics, prompt templates, logs and preferences require `ROLE_ADMIN`; no second shared admin password is used.
- [ ] **CSRF verified for browser sessions** — unsafe requests with a form-login session fail without a CSRF token.
- [ ] **Stateless API authentication verified** — Basic/Bearer clients are explicitly authenticated and do not reuse browser session cookies.
- [ ] **Mutating endpoint matrix reviewed** — import, document/provenance, relation, DSL, Git, context and workspace writes require the intended role.

## 3. Network and AI configuration

- [ ] **Provider selected and documented** — provider, model, region, data processing and retention are recorded.
- [ ] **Secrets stored outside source control** — platform secret store or Docker/Kubernetes secrets used.
- [ ] **LLM quotas configured** — `TAXONOMY_LLM_RPM` and timeout values match the provider contract.
- [ ] **Cloud provider outbound destinations restricted** where network policy supports allowlisting.
- [ ] **Local model policy decided** — runtime downloads are disabled for controlled production with `TAXONOMY_EMBEDDING_ALLOW_DOWNLOAD=false`.
- [ ] **Model revision and checksum recorded** when a local embedding model is deployed.
- [ ] **Network-isolated claim verified** — all browser assets and model files must be locally available; `LOCAL_ONNX` alone only makes the AI execution local.

## 4. Data protection and AI governance

- [ ] Data protection impact assessment completed where required.
- [ ] Data retention/deletion policy covers requirements, documents, prompts, model responses, logs, users and workspaces.
- [ ] Data processing agreement exists for every cloud LLM provider.
- [ ] Prompt and response logging policy reviewed for confidential information.
- [ ] AI transparency documentation adapted to the actual provider and deployment.
- [ ] Human review responsibilities for proposed relations and architecture recommendations are assigned.
- [ ] AI literacy training completed for relevant users.

## 5. Supply chain and release integrity

- [ ] Release version and Git commit recorded.
- [ ] Zenodo DOI recorded when the deployment is tied to an archived research release.
- [ ] CycloneDX SBOM reviewed.
- [ ] Vulnerability scanner executed against dependencies and container image.
- [ ] The SBOM companion document is not treated as a vulnerability or exploitability assessment.
- [ ] Base image, Java runtime, Maven dependencies, browser libraries and AI model revisions are pinned.
- [ ] Image digest used for reproducible production deployments instead of a mutable `latest` tag.
- [ ] Third-party licenses and notices reviewed.

## 6. Accessibility and software ergonomics

- [ ] Authenticated axe workflow passes for the release commit.
- [ ] Full primary workflow completed using keyboard only.
- [ ] NVDA/JAWS and VoiceOver tests completed for the primary workflow.
- [ ] 200 % and 400 % zoom tests passed.
- [ ] 320 CSS-pixel and touch-device tests passed.
- [ ] Architecture and graph views have equivalent table/structured-text alternatives.
- [ ] A deployment-specific accessibility statement and feedback channel are published.
- [ ] Expert-only functions are separated or clearly labelled; diagnostics are not shown in the default work area.

## 7. Monitoring and operations

- [ ] Load balancer/container health check uses `/actuator/health/readiness`.
- [ ] Liveness check uses `/actuator/health/liveness`.
- [ ] Prometheus endpoint access is restricted to monitoring infrastructure.
- [ ] Log retention, redaction and rotation are configured.
- [ ] Alerts exist for readiness failures, authentication anomalies, high error rate, memory pressure and failed background initialization.
- [ ] Recovery procedure for failed taxonomy initialization documented.
- [ ] Workspace and repository consistency checks scheduled.

## 8. Post-deployment acceptance

- [ ] Login and forced password change behave as configured.
- [ ] Taxonomy loads and exposes the expected eight roots.
- [ ] Requirement analysis succeeds with the selected provider.
- [ ] Interactive and non-interactive analyses produce consistent, traceable results.
- [ ] Hypotheses are stored in the active workspace and their committed TaxDSL parses successfully.
- [ ] Relation acceptance/rejection respects workspace isolation.
- [ ] Branch, compare, merge, cherry-pick and restore workflows pass with real data.
- [ ] Imports use the configured working branch rather than an undocumented hard-coded branch.
- [ ] ArchiMate, Visio, Mermaid, JSON and report exports open in their target tools.
- [ ] Restart persistence test is repeated against the deployed environment.
- [ ] Backup restoration is verified on a separate instance.

## Sign-off

| Checkpoint | Responsible | Evidence / ticket | Date | Approval |
|---|---|---|---|---|
| Architecture and persistence | | | | |
| Security | | | | |
| Data protection / AI governance | | | | |
| Accessibility | | | | |
| Operations and recovery | | | | |
| Go-live decision | | | | |

## Related documentation

- [Deployment Guide](DEPLOYMENT_GUIDE.md)
- [Configuration Reference](CONFIGURATION_REFERENCE.md)
- [Database Setup](DATABASE_SETUP.md)
- [Security](SECURITY.md)
- [Operations Guide](OPERATIONS_GUIDE.md)
- [Accessibility Evidence](ACCESSIBILITY.md)
- [AI Transparency](AI_TRANSPARENCY.md)
- [Data Protection](DATA_PROTECTION.md)
