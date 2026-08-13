# Task: Add a New Relation Type

## Goal

Add a new semantic relation type, for example `INFLUENCES` or `CONSTRAINS`, and
keep its validation, DSL, projected reads, Git-authoritative commands, UI choices,
and imports consistent.

> Start with the stable extension anchor in
> [`docs/dev/07-extension-points.md#relation-types-and-compatibility-rules`](../07-extension-points.md#relation-types-and-compatibility-rules).
> Use this page for the end-to-end file, test, and documentation checklist.

---

## Primary entry points

| File | What to do |
|---|---|
| `taxonomy-domain/src/main/java/com/taxonomy/model/RelationType.java` | Add the persisted enum constant |
| `taxonomy-app/src/main/java/com/taxonomy/relations/service/RelationCompatibilityMatrix.java` | Add allowed source/target type combinations |
| `taxonomy-dsl/src/main/java/com/taxonomy/dsl/validation/DslValidator.java` | Keep the DSL relation-type mirror and compatibility rules aligned |
| `taxonomy-app/src/main/resources/templates/index.html` | Add the type to both hard-coded relation selectors |

There is currently no relation-type registry and no
`GET /api/relations/types` endpoint. Adding the enum value alone therefore does
not populate the UI.

---

## Files usually touched

- `taxonomy-domain/.../model/RelationType.java` — new enum constant
- `taxonomy-app/.../relations/service/RelationCompatibilityMatrix.java` —
  allowed source/target combinations
- `taxonomy-dsl/.../validation/DslValidator.java` — DSL validation mirror
- `taxonomy-app/src/main/resources/templates/index.html` — both relation-type
  selectors
- `taxonomy-domain/src/test/java/com/taxonomy/model/RelationTypeTest.java` —
  assertion for the new constant
- compatibility and DSL validation tests for allowed and forbidden combinations

Review these when the new type has special behavior:

- `RelationValidationService`
- `RelationCandidateService`
- `RelationProposalService`
- `AnalysisRelationGenerator`
- graph traversal, impact, coverage, or materialization services
- import profiles and seed parsers that map external relation names
- `taxonomy-app/src/main/resources/data/relations.csv`
- `docs/en/RELATION_SEEDS.md`

---

## Files usually not touched

- `taxonomy-dsl/.../parser/TaxDslParser.java` — relation type names are carried as
  strings; adding a simple enum-backed type normally needs no grammar change
- `taxonomy-export/` — exporters generally handle relation types generically
- `RelationApiController` — projected read endpoints are type-agnostic
- `GitRelationCommandApiController` — identity-based commands parse the enum
  generically
- `taxonomy-app/src/main/resources/static/js/relations/taxonomy-relations.js` —
  table rendering is type-agnostic
- `taxonomy-app/src/main/resources/static/js/relations/taxonomy-relations-git-commands.js`
  — command routing is type-agnostic

Touch these only when the new type changes payload shape, validation semantics,
or presentation rather than merely adding another enum value.

---

## Relation API contract

### Product reads

| Endpoint | Purpose |
|---|---|
| `GET /api/relations` | Complete relation projection for the selected repository/workspace; optional `type` filter |
| `GET /api/node/{code}/relations` | Incoming and outgoing projected relations for one node |
| `GET /api/relations/count` | Count from the same complete projected snapshot |

These reads return the authoritative branch `ETag` and explicit projection/read
model headers. Unsafe stale or corrupt projections fail closed.

### Git-authoritative writes

| Endpoint | Purpose |
|---|---|
| `PUT /api/architecture/relations/{sourceCode}/{relationType}/{targetCode}` | Add or update one relation identity |
| `DELETE /api/architecture/relations/{sourceCode}/{relationType}/{targetCode}` | Remove one relation identity |

Existing branches require a strong `If-Match` containing the full quoted commit
ID. Creating an absent branch requires `If-None-Match: *`. Every command requires
`Idempotency-Key`.

The old DB-first routes `POST /api/relations` and
`DELETE /api/relations/{id}` are retired and return HTTP 410. Do not add new
features or clients to those compatibility routes.

---

## Frontend modules

- `taxonomy-app/src/main/resources/templates/index.html` — owns the two current
  hard-coded type selectors
- `taxonomy-app/src/main/resources/static/js/relations/taxonomy-relations.js` —
  renders projected relation reads and impact analysis
- `taxonomy-app/src/main/resources/static/js/relations/taxonomy-relations-git-commands.js`
  — binds Create/Delete to the displayed projection ETag and stable relation
  identity
- i18n properties — add labels/help text when the enum name is not an adequate
  user-facing label

The command adapter intentionally does not maintain its own type list. It submits
whatever valid enum-backed choice the selectors expose.

---

## DTOs and persisted identity

- `RelationType` is the source of truth for persisted names
- `TaxonomyRelationDto` carries the relation type as a string and needs no
  structural change
- Git relation identity is the tuple
  `(sourceCode, relationType, targetCode)`
- projection database IDs are rebuild-local implementation details and must not
  be used as mutation identities

Do not rely on `RelationType.ordinal()`. Persist and compare `name()` values only.

---

## Tests to run

```bash
# Domain enum contract
./mvnw test -pl taxonomy-domain

# Application validation, Git commands, projected reads, UI contracts
./mvnw test -pl taxonomy-app -am

# DSL parsing, validation, and round trips
./mvnw test -pl taxonomy-dsl -am
```

Relevant tests include:

- `RelationTypeTest`
- `RelationValidationServiceTest`
- `RelationCompatibilityMatrixTest`, when present
- `GitAuthoritativeRelationMutationServiceTest`
- `RelationProjectionReadServiceTest`
- `RelationApiControllerRepositoryScopeTest`
- `RelationGitCommandUiContractTest`
- `DslValidatorTest`
- parser/serializer round-trip tests when DSL behavior changes
- import/seed tests when mappings or seed data change

Add at least one accepted combination and one rejected combination for the new
relation type. Also prove that the exact enum name survives DSL and JSON
round trips.

---

## Documentation and screenshot updates

- `docs/en/RELATION_SEEDS.md` — when seed data or guidance changes
- `docs/en/API_REFERENCE.md` — when it lists relation-type values or command
  examples
- this task and the extension-points page — only if the extension workflow or
  API architecture changes
- screenshots — only when a documented picker visibly enumerates all relation
  types

---

## Common pitfalls

1. **Compatibility rules are incomplete.** The enum exists, but the application
   or DSL validator rejects every use.
2. **Only one selector was updated.** Manual relation creation and proposal
   workflows then expose different type sets.
3. **A fictitious metadata endpoint is assumed.** There is no
   `/api/relations/types`; the UI list is currently core template data.
4. **A client uses the retired DB-first route.** New writes must carry Git
   preconditions and an idempotency key.
5. **Projection IDs are treated as identities.** Rebuilds may replace those IDs;
   mutations must use source/type/target.
6. **Proposal, graph, or import code assumes the old closed set.** Search for
   switches and type-specific maps before considering the change complete.
7. **Seed data contradicts the matrix.** Validate new seed/import mappings against
   both application and DSL compatibility rules.
8. **Enum ordinals are persisted.** Only stable enum names belong in storage,
   DSL, and API payloads.
