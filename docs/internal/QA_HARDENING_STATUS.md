# Senior Architecture and Ergonomics QA Hardening

This document tracks the implementation status of the comprehensive QA review performed in July 2026.

## Implemented on the hardening branch

- Persistent production defaults for embedded HSQLDB and Lucene.
- Fail-fast production administrator credentials.
- ROLE_ADMIN-based administration without a second application password.
- CSRF protection for browser sessions with explicit stateless API exceptions.
- Explicit authorization of import, provenance, workspace, prompt, and diagnostic mutations.
- Canonical TaxDSL v2 serialization and validation for generated relation hypotheses.
- Workspace-specific Git repository routing for hypothesis commits.
- Honest SBOM companion/VEX metadata without unsupported vulnerability claims.
- Accessibility regression workflow using Playwright and axe-core.
- Keyboard navigation, dynamic ARIA synchronization, code suggestions, touch targets, zoom support, and reduced-motion handling.
- Updated accessibility, deployment, and extension-boundary documentation.
- Export SPI moved from the generic extension API to the Spring-free export module.

## Quality gates before merge

- Maven reactor build and all tests pass.
- Architecture and dependency rules pass.
- Docker image builds successfully.
- Accessibility audit passes on all listed application sections.
- Documentation link check passes.
- Production persistence restart test passes.
- Documentation screenshot fixtures remain clearly separated from product acceptance tests.

Documentation screenshots may use deterministic fixtures. They are not evidence that live integrations, search backends, or external AI providers are healthy. Acceptance tests must exercise real application contracts without DOM result injection.
