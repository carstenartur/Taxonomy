# Task: Add a DSL Property

## Goal

Add a new property to an existing DSL block type (e.g., add `priority` to the
`requirement` block) or add an entirely new DSL block type (e.g., a new `decision`
block) to the Architecture DSL.

> Start with the stable extension anchor in
> [`docs/dev/07-extension-points.md#dsl-grammar-and-property-additions`](../07-extension-points.md#dsl-grammar-and-property-additions).
> Use this page for the end-to-end file/test/doc checklist.

---

## Primary entry points

| File | What to do |
|---|---|
| `taxonomy-dsl/src/main/java/com/taxonomy/dsl/parser/TaxDslParser.java` | Register the new block type or accept the new property key |
| `taxonomy-dsl/src/main/java/com/taxonomy/dsl/serializer/TaxDslSerializer.java` | Add the property to the canonical property order |
| `taxonomy-dsl/src/main/java/com/taxonomy/dsl/model/` | Add or extend the model class |
| `taxonomy-dsl/src/main/java/com/taxonomy/dsl/mapper/AstToModelMapper.java` | Map the AST property to the model field |
| `taxonomy-dsl/src/main/java/com/taxonomy/dsl/mapper/ModelToAstMapper.java` | Map the model field back to the AST |

---

## Files usually touched

**All five files below must be updated together** — a partial update breaks the
round-trip invariant (`parse → serialize → parse → serialize` must be idempotent):

- `taxonomy-dsl/…/parser/TaxDslParser.java`
  - For a new block type: add it to `KNOWN_BLOCK_TYPES`
  - For a new property: no parser change needed (the parser accepts any property key inside a known block)
- `taxonomy-dsl/…/serializer/TaxDslSerializer.java`
  - Add the property to `PROPERTY_ORDER` for the relevant block kind
- `taxonomy-dsl/…/model/<BlockModel>.java`
  - Add the new field
- `taxonomy-dsl/…/mapper/AstToModelMapper.java`
  - Map the AST `PropertyAst` for the new key to the model field
- `taxonomy-dsl/…/mapper/ModelToAstMapper.java`
  - Map the model field back to a `PropertyAst`

**If you add a new block type**, also update:

- `taxonomy-dsl/…/parser/DslTokenizer.java` — add the block keyword to `STRUCTURE_TOKENS`
- `taxonomy-dsl/…/validation/DslValidator.java` — add validation rules for the new block

**If the new property/block is used in the provenance layer**, also update:

- `taxonomy-app/…/versioning/service/DslOperationsFacade.java` — if the new block is stored/retrieved
- `taxonomy-app/…/architecture/service/CommitIndexService.java` — if the new block is indexed

---

## Files usually not touched

- `taxonomy-domain/` — the DSL layer is independent of domain DTOs;
  add a DTO only if the new DSL data needs to be exposed via a REST API
- `taxonomy-export/` — export services consume the canonical model;
  update them only if the new block/property must appear in exported diagrams
- `taxonomy-app/…/controller/` — DSL controller routes are generic;
  no controller change needed unless you add a new endpoint
- `taxonomy-app/src/main/resources/templates/index.html` — the DSL editor
  displays raw DSL text; no template change needed for a property addition

---

## Backend endpoint(s)

| Endpoint | Controller |
|---|---|
| `POST /api/dsl/commit` | `DslApiController` |
| `GET /api/dsl/read` | `DslApiController` |
| `GET /api/dsl/diff` | `DslApiController` |

The DSL controller is generic (operates on raw DSL text).
No controller changes are needed for a new property or block type.

---

## Frontend module(s)

- `taxonomy-app/src/main/resources/static/js/dsl.js` — the DSL editor;
  add syntax hint or autocomplete support for the new keyword only if the
  editor provides per-keyword hints
- No template change needed for property additions

---

## DTOs / domain types

The DSL model lives in `taxonomy-dsl/…/model/`, not in `taxonomy-domain/…/dto/`.
Add or extend the relevant model class there.

If the new property needs to be exposed via the REST API (e.g., in an architecture
view response), add a new DTO in `taxonomy-domain/…/dto/` and map from the model.

---

## Tests to run

```bash
# DSL module unit tests (fast, no Spring context, no Docker)
mvn test -pl taxonomy-dsl

# App module tests (if you changed the commit index or facade)
mvn test -pl taxonomy-app
```

Relevant test classes:
- `TaxDslParserTest` — add a test that parses a document containing the new property/block
- `TaxDslSerializerTest` — add a round-trip test (`parse → serialize → parse → compare`)
- `DslValidatorTest` — add a test for the new block's validation rules
- `AstToModelMapperTest` / `ModelToAstMapperTest` — add mapping correctness tests
- `DslGitRepositoryTest` — if the new block is stored in Git, verify commit/read round-trip

---

## Documentation / screenshot updates

- `docs/en/DEVELOPER_GUIDE.md` — the "DSL and JGit Storage" section lists known block types;
  add the new block type there
- `docs/en/ARCHITECTURE.md` — if the new block is architecturally significant
- Screenshots: not typically required for a DSL-only change

---

## Common pitfalls

1. **Round-trip stability is critical:** The DSL is used as Git content.
   A non-deterministic serializer produces unnecessary Git diffs.
   Always write a round-trip test: `parse → serialize → parse → serialize`
   must produce identical output both times.

2. **`PROPERTY_ORDER` is exhaustive:** Properties not listed in `PROPERTY_ORDER`
   are serialized after known properties in alphabetical order.
   For a new well-known property, add it to `PROPERTY_ORDER` explicitly.

3. **`KNOWN_BLOCK_TYPES` guards parsing:** Unknown block keywords cause a parse
   error. Always add a new block type to `KNOWN_BLOCK_TYPES` before writing tests.

4. **`STRUCTURE_TOKENS` for indexing:** `DslTokenizer` uses `STRUCTURE_TOKENS`
   to segment DSL text for Hibernate Search indexing.
   Missing keywords cause incomplete search index entries for the new block type.

5. **`DfsBlockCache` collisions:** If your change involves running multiple
   `DslGitRepositoryTest` tests in the same JVM, ensure pack names remain unique
   (per-instance counters, not static counters).
