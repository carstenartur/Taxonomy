# Workspace module

This library owns `com.taxonomy.workspace`, `com.taxonomy.versioning` and
`com.taxonomy.editor`: repository/workspace authority, Git storage, versioning,
semantic operation history, undo/redo and checkpoints.

`taxonomy-app` depends on this ordinary JAR and remains the only deployable
Spring Boot application. Existing package names, HTTP routes, entities and SQL
migration resources are unchanged. Application configuration and migrations
remain in `taxonomy-app`; component/entity/repository scanning uses the same
`com.taxonomy` package root across the classpath.

Module-local unit tests exercise repository context, access, membership,
version comparison, semantic Git merging and publication. Cross-context,
application restart, journal persistence and database integration tests remain
in `taxonomy-app`, exercising this library through its Maven dependency.
JaCoCo aggregation includes execution data from both modules and retains the
existing workspace/versioning package coverage floors.

Build from the repository root with `./mvnw verify`. The architecture profile
checks actual compiled ownership and rejects a dependency back to the app.
Other unextracted contexts may still contain cycles; this independent module
must not participate in or reach one. Issue #628 is not complete after this
first extraction.
