# Taxonomy Portfolio

Runtime library extracted for #628. The application depends on this library, never the reverse.
Owned unit tests run without the executable application. Cross-context Spring, database,
security and recovery acceptance stays in `taxonomy-app`. Database migrations and application
configuration remain deployment-owned. See `docs/dev/MODULE_EXTRACTION_COMPLETION.md`.
