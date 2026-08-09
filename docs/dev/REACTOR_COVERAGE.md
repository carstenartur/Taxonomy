# Reactor-wide test coverage

The authoritative coverage evidence for Taxonomy is produced by the final coverage module, `taxonomy-coverage`, using JaCoCo `report-aggregate`.

## Included production modules

The report must contain all shipped modules as separate JaCoCo groups:

1. `taxonomy-domain`
2. `taxonomy-dsl`
3. `taxonomy-export`
4. `taxonomy-extension-api`
5. `taxonomy-app`

The gate normalizes Maven display names and artifact IDs, but it still fails when any required module is missing. This prevents a highly covered application module from hiding an uninstrumented or untested library module.

## Multi-counter ratchet

`.github/coverage-policy.json` is the single versioned policy for the aggregate gate. It requires and publishes all of these JaCoCo counters:

- instructions;
- lines;
- branches;
- methods;
- classes.

Every required counter must exist and have a measurable total both at aggregate level and for every module group. The build fails closed when a counter is missing, empty or below its configured aggregate minimum.

The branch threshold is an explicit ratchet based on verified reactor evidence. It must not be removed or silently replaced by instruction-only coverage. Thresholds should move upward as tests improve. Critical-package budgets, diff coverage and time-limited exceptions are tracked separately under issue #624.

## Single source of truth

The following outputs all consume the same XML file and policy:

```text
taxonomy-coverage/target/site/jacoco-aggregate/jacoco.xml
.github/coverage-policy.json
```

- CI coverage gate;
- published and archived coverage evidence;
- release coverage claims;
- the human-readable `target/coverage-gate.txt` summary.

Module-local reports may still exist for diagnosis, but they are not added together and are not authoritative.

## Local verification

```bash
./mvnw install -DexcludedGroups=real-llm
python3 .github/scripts/check-coverage.py \
  --xml taxonomy-coverage/target/site/jacoco-aggregate/jacoco.xml \
  --policy .github/coverage-policy.json \
  --report target/coverage-gate.txt
```

The gate's deterministic regression tests run with:

```bash
python3 .github/scripts/test-check-coverage.py
```

## Adding or removing a module

A shipped module change is incomplete until all of the following are updated:

- root reactor `<modules>` list;
- direct dependencies in `taxonomy-coverage/pom.xml`;
- `expectedGroups` in `.github/coverage-policy.json`;
- this document.

CI deliberately fails when those views drift apart.
