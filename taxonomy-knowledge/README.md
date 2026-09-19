# Taxonomy Knowledge

Maven library owning `com.taxonomy.catalog`, `com.taxonomy.relations` and
`com.taxonomy.search`, including catalogue resources, repository-scoped relation
projections, Hibernate Search mappings and optional local ONNX embeddings.

`taxonomy-app` assembles and deploys this library; this module must never depend
on the executable application. Workspace operations use the existing
`taxonomy-workspace` boundary. The application supplies the cross-context
framework-import materialization adapters under `com.taxonomy.composition.importer`.

The catalogue files keep their `data/` classpath paths. Search bean names and
configuration properties are unchanged. Flyway migrations stay in `taxonomy-app`.

Run module-owned tests from the repository root with:

```sh
./mvnw -pl taxonomy-knowledge -am test
```

Run the complete architecture suite from the repository root with:

```sh
./mvnw test -Parchitecture-tests -Dsurefire.failIfNoSpecifiedTests=false
```

Cross-context application tests remain in `taxonomy-app`; boot-jar ownership is
verified by `KnowledgeModulePackagingIT` in `taxonomy-build`. See
[the context boundaries](../docs/en/MODULE_BOUNDARIES.md).
