# Task: Add a New Relation Type

## Goal

Add a new semantic relation type (e.g., `INFLUENCES`, `CONSTRAINS`) to the
`RelationType` enum and ensure it is handled correctly throughout the
validation, scoring, and DSL layers.

> Start with the stable extension anchor in
> [`docs/dev/07-extension-points.md#relation-types-and-compatibility-rules`](../07-extension-points.md#relation-types-and-compatibility-rules).
> Use this page for the end-to-end file/test/doc checklist.

---

## Primary entry points

| File | What to do |
|---|---|
| `taxonomy-domain/src/main/java/com/taxonomy/model/RelationType.java` | Add the new enum constant |
| `taxonomy-app/src/main/java/com/taxonomy/relations/service/RelationCompatibilityMatrix.java` | Add the allowed source/target type combinations |
| `taxonomy-dsl/src/main/java/com/taxonomy/dsl/validation/DslValidator.java` | Add any DSL-level validation rules |

---

## Files usually touched

- `taxonomy-domain/…/model/RelationType.java` — new enum constant
- `taxonomy-app/…/relations/service/RelationCompatibilityMatrix.java` — allowed source/target combinations
- `taxonomy-app/…/relations/service/RelationValidationService.java` — validation logic that references the compatibility matrix
- `taxonomy-dsl/…/validation/DslValidator.java` — if DSL blocks use the new type, add validation there
- `taxonomy-app/src/main/resources/data/relation_seeds.csv` — optional: seed data for typical relations of this type
- `docs/en/RELATION_SEEDS.md` — document any new seed relations
- `taxonomy-domain/src/test/java/com/taxonomy/model/RelationTypeTest.java` — add a test asserting the new constant exists

---

## Files usually not touched

- `taxonomy-dsl/…/parser/` — the DSL parser uses the `RelationType` enum by string
  matching; adding the enum constant is sufficient; no parser grammar change needed
- `taxonomy-export/` — export services handle relation types generically
- `taxonomy-app/…/controller/` — relation controllers are type-agnostic
- `taxonomy-app/src/main/resources/templates/index.html` — the relation type
  dropdown is populated from the enum; no template change needed unless you need
  a custom label or icon

---

## Backend endpoint(s)

| Endpoint | Controller |
|---|---|
| `GET /api/relations` | `RelationApiController` |
| `POST /api/relations` | `RelationApiController` |
| `GET /api/relations/types` | `RelationApiController` |

The relation endpoints are type-agnostic.
After adding the enum constant, the new type appears automatically in
`GET /api/relations/types`.

---

## Frontend module(s)

- `taxonomy-app/src/main/resources/static/js/relations.js` — reads the type list
  from `/api/relations/types`; no change needed unless you add a custom display label
- i18n: if the type name needs a German translation, add it to `messages_de.properties`

---

## DTOs / domain types

- `com.taxonomy.model.RelationType` — the source of truth; all other files reference
  this enum
- `com.taxonomy.dto.TaxonomyRelationDto` — carries the relation type as a string;
  no change needed
- `com.taxonomy.dto.RelationSeedRow` — used for CSV seed data; no structural change needed

---

## Tests to run

```bash
# Domain module: verify enum constant is present
mvn test -pl taxonomy-domain

# App module: verify compatibility matrix and validation
mvn test -pl taxonomy-app
```

Relevant test classes:
- `RelationTypeTest` — add an assertion for the new constant
- `RelationValidationServiceTest` — add a test for the new type's allowed combinations
- `RelationCompatibilityMatrixTest` (if it exists) — add allowed/forbidden pair tests

---

## Documentation / screenshot updates

- `docs/en/RELATION_SEEDS.md` — list any seed relations of the new type
- `docs/en/API_REFERENCE.md` — update the `RelationType` values table if present
- Screenshots: not required unless the relation type picker screenshot exists and
  shows an explicit list

---

## Common pitfalls

1. **Compatibility matrix must be exhaustive:** If you add a new type but do not
   add it to `RelationCompatibilityMatrix`, the validator will reject all relations
   of that type as invalid combinations.

2. **DSL serialization order:** If the new type is used in DSL blocks, ensure
   `TaxDslSerializer.PROPERTY_ORDER` includes any new property names for this type.
   See [add-dsl-property](add-dsl-property.md) for details.

3. **Existing seed data:** The seed CSV is loaded at startup.
   If you add seeds for a type that the compatibility matrix rejects, startup
   will log validation warnings. Add the type to the matrix before adding seeds.

4. **Enum ordinal stability:** Do not rely on `RelationType.ordinal()` — only
   `name()` (the string value) is persisted in the database.
