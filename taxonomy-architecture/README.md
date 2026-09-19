# Taxonomy Architecture

An ordinary Maven library owning architecture derivation, scoring, gap and pattern analysis,
recommendations, diagram preparation, report renderers and architecture persistence.
`taxonomy-app` remains the only deployable application and supplies report preferences via
`ArchitectureReportMetadataPort`. Metadata is resolved for each report, not cached at startup.

Production packages retain their Java names. Their owned unit tests live in this module;
cross-context Spring, database, security and recovery tests remain in the application.
Database migrations and application configuration have not moved. The Maven Enforcer rule,
`ArchitectureDerivationModuleTest`, the module graph and `ArchitectureModulePackagingIT`
verify that the library never depends back on the application and is packaged exactly once.

Part of #628. Architecture, analysis and portfolio must remain separately reviewable changes.
