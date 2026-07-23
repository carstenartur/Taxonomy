# Reactor-wide test coverage

The authoritative coverage value for Taxonomy is produced by the final Maven module, `taxonomy-coverage`, using JaCoCo `report-aggregate`.

## Included production modules

The report must contain all shipped modules as separate JaCoCo groups:

1. `Taxonomy Domain`
2. `Taxonomy DSL`
3. `Taxonomy Export`
4. `Taxonomy Extension API`
5. `Taxonomy Application`

The coverage gate fails when any required group is missing, even if the remaining modules exceed the percentage threshold. This prevents a highly covered application module from hiding an uninstrumented or untested library module.

## Single source of truth

The following outputs all consume the same file:

```text
taxonomy-coverage/target/site/jacoco-aggregate/jacoco.xml
```

- CI coverage gate
- coverage badge
- published HTML report
- archived coverage evidence
- release coverage claims

Module-local reports may still exist for diagnosis, but they are not added together and are not authoritative.

## Local verification

```bash
mvn install -DexcludedGroups=real-llm
python3 .github/scripts/check-coverage.py \
  --xml taxonomy-coverage/target/site/jacoco-aggregate/jacoco.xml \
  --minimum 0.81 \
  --expected-group "Taxonomy Domain" \
  --expected-group "Taxonomy DSL" \
  --expected-group "Taxonomy Export" \
  --expected-group "Taxonomy Extension API" \
  --expected-group "Taxonomy Application"
```

The gate's own regression tests run with:

```bash
python3 .github/scripts/test-check-coverage.py
```

## Adding or removing a module

A shipped module change is incomplete until all of the following are updated:

- root reactor `<modules>` list;
- direct dependencies in `taxonomy-coverage/pom.xml`;
- required `--expected-group` arguments in CI;
- this document.

CI deliberately fails when those four views drift apart.
