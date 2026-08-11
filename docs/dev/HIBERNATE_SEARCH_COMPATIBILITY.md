# Hibernate Search compatibility contract

The application uses the Hibernate Search **platform BOM**, not independently versioned mapper and backend artifacts.

## Selected release set

| Component | Version line | Source of truth |
|---|---:|---|
| Hibernate Search mapper/backend | `8.4.0.Final` | `hibernate-search.version` and `hibernate-search-platform-bom` |
| Hibernate ORM | `7.4.x` | Hibernate Search platform BOM |
| Apache Lucene | `9.12.3` | Hibernate Search platform BOM and `lucene.version` |

Hibernate Search 8.4 targets Hibernate ORM 7.4 and its Lucene backend uses Lucene 9.12.3. The platform BOM coordinates these dependencies and prevents mapper/backend minor-version skew.

## Verification

The canonical command is:

```bash
./mvnw -B verify -Pci
```

`taxonomy-app` writes the resolved Maven dependency tree to:

```text
target/hibernate-search-dependencies.txt
```

Because `taxonomy-build` is the final reactor module, `HibernateSearchAlignmentPolicyIT` consumes that exact resolved tree after the application module has completed. The Failsafe/JUnit gate requires:

- at least one `org.hibernate.search` artifact;
- exactly the configured `hibernate-search.version` for every Search artifact;
- exactly one `org.hibernate.orm:hibernate-core` version from the `7.4.x` line;
- exactly the configured `lucene.version` for `org.apache.lucene:lucene-core`.

Positive and negative parser/policy fixtures live in `HibernateSearchAlignmentPolicyTest`. A focused unit run is:

```bash
./mvnw -B -pl taxonomy-build -am test \
  -Dtest=HibernateSearchAlignmentPolicyTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

The real resolved-tree decision remains a post-reactor `verify` gate and is not simulated by the unit fixtures. CI retains the dependency tree as evidence for every relevant change.

## Upgrade procedure

1. Read the Hibernate Search compatibility and migration documentation.
2. Update only `hibernate-search.version`.
3. Keep `hibernate-search-backend.version` as an alias or remove it together with all remaining child-POM references.
4. Run the full Maven reactor, persistent-index restart tests, database compatibility matrix, mass-index/search tests, and the strict bounded-context cycle gate.
5. Confirm whether a reindex is required before release and document that decision.

A release is incomplete until the resolved dependency evidence, security scan, strict architecture gate, and persistent-index restart test are all green.
