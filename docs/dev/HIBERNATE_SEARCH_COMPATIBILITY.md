# Hibernate Search compatibility contract

The application uses the Hibernate Search **platform BOM**, not independently versioned mapper and backend artifacts.

## Selected release set

| Component | Version line | Source of truth |
|---|---:|---|
| Hibernate Search mapper/backend | `8.4.0.Final` | `hibernate-search.version` and `hibernate-search-platform-bom` |
| Hibernate ORM | `7.4.x` | Hibernate Search platform BOM |
| Apache Lucene | `9.12.3` | Hibernate Search platform BOM and `lucene.version` |

Hibernate Search 8.4 targets Hibernate ORM 7.4 and its Lucene backend uses Lucene 9.12.3. The platform BOM coordinates these dependencies and prevents mapper/backend minor-version skew.

## Local verification

```bash
mvn -B -q -pl taxonomy-app -am install -DskipTests
mvn -B -q -pl taxonomy-app dependency:tree \
  -Dincludes='org.hibernate.search:*,org.hibernate.orm:hibernate-core,org.apache.lucene:lucene-core' \
  -DoutputFile=../target/hibernate-search-dependencies.txt
python3 .github/scripts/check-hibernate-search-alignment.py \
  --tree target/hibernate-search-dependencies.txt \
  --search-version 8.4.0.Final
```

The same check runs in `Hibernate Search Alignment` and archives the resolved tree for each POM change.

## Upgrade procedure

1. Read the Hibernate Search compatibility and migration documentation.
2. Update only `hibernate-search.version`.
3. Keep `hibernate-search-backend.version` as an alias or remove it together with all remaining child-POM references.
4. Run the full Maven reactor, persistent-index restart tests, database compatibility matrix, mass-index/search tests, and the strict bounded-context cycle gate.
5. Confirm whether a reindex is required before release and document that decision.

A release is incomplete until the resolved dependency evidence, security scan, strict architecture gate, and persistent-index restart test are all green.
