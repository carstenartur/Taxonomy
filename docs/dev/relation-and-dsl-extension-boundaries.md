# Relation and DSL Extension Boundaries

## Purpose

This note defines which relation and DSL metadata could be described through an
internal extension descriptor and which parts must remain core code.

## Short version

- **Safe for descriptors:** human-readable labels, help text, examples,
  grouping, icons, and other metadata that does **not** change parsing,
  validation, persistence, indexing, or runtime semantics.
- **Must stay core:** enum values, parser/serializer rules, compatibility
  matrices, validation logic, indexing tokens, REST payload contracts, and any
  invariant covered by round-trip or semantic tests.

---

## 1. Relation semantics

### Current path for adding or changing relation semantics

Changing relation semantics is currently a **core-code change** across multiple
modules.

1. Add or change the enum in
   `taxonomy-domain/src/main/java/com/taxonomy/model/RelationType.java`.
2. Update the application compatibility rules in
   `taxonomy-app/src/main/java/com/taxonomy/relations/service/RelationCompatibilityMatrix.java`.
3. Keep the DSL-side mirror in sync in
   `taxonomy-dsl/src/main/java/com/taxonomy/dsl/validation/DslValidator.java`
   (`VALID_RELATION_TYPES` and `TYPE_MATRIX`).
4. Review services that derive behavior from relation types, especially:
   - `taxonomy-app/src/main/java/com/taxonomy/relations/service/RelationValidationService.java`
   - `taxonomy-app/src/main/java/com/taxonomy/relations/service/RelationCandidateService.java`
   - `taxonomy-app/src/main/java/com/taxonomy/relations/service/RelationProposalService.java`
   - `taxonomy-app/src/main/java/com/taxonomy/analysis/service/AnalysisRelationGenerator.java`
   - `taxonomy-app/src/main/java/com/taxonomy/dsl/export/DslMaterializeService.java`
5. Review relation consumers in the DSL layer, especially:
   - `taxonomy-dsl/src/main/java/com/taxonomy/dsl/mapper/AstToModelMapper.java`
   - `taxonomy-dsl/src/main/java/com/taxonomy/dsl/mapper/ModelToAstMapper.java`
   - `taxonomy-dsl/src/main/java/com/taxonomy/dsl/parser/DslTokenizer.java`
   - `taxonomy-dsl/src/main/java/com/taxonomy/dsl/diff/SemanticDiffDescriber.java`
6. Update the current UI entry points. Relation types are currently hard-coded in
   `taxonomy-app/src/main/resources/templates/index.html` and used by
   `taxonomy-app/src/main/resources/static/js/relations/taxonomy-relations.js`.
   There is currently no `/api/relations/types` endpoint to centralize this. A
   future descriptor-based UI would first need a core-backed registry or API to
   expose relation metadata consistently, but that refactoring is separate from
   the semantic rules described here.
7. Review seed/import paths that persist or generate relation types, including:
   - `taxonomy-app/src/main/resources/data/relations.csv`
   - `taxonomy-app/src/main/java/com/taxonomy/catalog/service/RelationSeedParser.java`
   - `taxonomy-app/src/main/java/com/taxonomy/catalog/service/ArchiMateXmlImporter.java`
   - `taxonomy-app/src/main/java/com/taxonomy/catalog/service/importer/UafImportProfileExtension.java`
   - `taxonomy-app/src/main/java/com/taxonomy/catalog/service/importer/C4ImportProfileExtension.java`
   - `taxonomy-app/src/main/java/com/taxonomy/catalog/service/importer/ApqcCsvImportProfileExtension.java`
   - `taxonomy-app/src/main/java/com/taxonomy/catalog/service/importer/ApqcExcelImportProfileExtension.java`
   - `taxonomy-app/src/main/java/com/taxonomy/catalog/service/importer/StructurizrDslParser.java`

### Files, services, UI modules, and tests affected by relation changes

| Area | Current files/services to review |
|---|---|
| Source of truth | `taxonomy-domain/.../RelationType.java` |
| App validation | `taxonomy-app/.../RelationCompatibilityMatrix.java`, `RelationValidationService.java` |
| Relation CRUD/proposals | `TaxonomyRelationService.java`, `RelationProposalService.java`, `RelationReviewService.java`, `ProposalApiController.java`, `RelationApiController.java`, `QualityApiController.java` |
| Analysis / graph usage | `AnalysisRelationGenerator.java`, `GraphSearchService.java`, `RelationTraversalService.java`, `RelationshipBuildStep.java`, `ImpactRelationStep.java`, `ProvisionalRelationStep.java` |
| DSL mirror | `taxonomy-dsl/.../DslValidator.java`, `AstToModelMapper.java`, `ModelToAstMapper.java`, `DslTokenizer.java`, `ModelDiffer.java`, `SemanticDiffDescriber.java` |
| UI | `taxonomy-app/src/main/resources/templates/index.html`, `taxonomy-app/src/main/resources/static/js/relations/taxonomy-relations.js` |
| Seed/import data | `taxonomy-app/src/main/resources/data/relations.csv`, `RelationSeedParser.java`, `ArchiMateXmlImporter.java`, `UafImportProfileExtension.java`, `C4ImportProfileExtension.java`, `ApqcCsvImportProfileExtension.java`, `ApqcExcelImportProfileExtension.java`, `StructurizrDslParser.java` |
| Tests | `taxonomy-domain/src/test/java/com/taxonomy/model/RelationTypeTest.java`, `taxonomy-app/src/test/java/com/taxonomy/TaxonomyRelationTests.java`, `RelationProposalTests.java`, `RelationQualityTests.java`, `AnalysisRelationGeneratorTests.java`, `RequirementCoverageTests.java`, `taxonomy-app/src/test/java/com/taxonomy/catalog/service/RelationSeedParserTest.java`, `taxonomy-app/src/test/java/com/taxonomy/architecture/pipeline/ProvisionalRelationStepTest.java`, plus DSL tests that assert relation parsing/validation/tokenization |

### Relation metadata that can safely move to an extension descriptor

Only **descriptive metadata** is a safe descriptor candidate:

- display label / human-readable name for a relation type
- help text or documentation blurb shown in UI
- icon, badge color, or grouping/category in pickers
- sort order for menus
- example source/target combinations shown as hints
- external-framework aliases used only for documentation or UI hints

These are safe because they do not change persisted values, parser behavior, or
validation outcomes.

### Relation invariants that should remain hard-coded and tested

Keep these in core code:

- the `RelationType` enum names used in persistence, JSON, and DSL text
- compatibility rules in `RelationCompatibilityMatrix`
- the DSL validator mirror in `DslValidator`
- tokenization/indexing behavior in `DslTokenizer`
- any relation-specific behavior in analysis, traversal, coverage, proposal, or
  materialization services
- the current UI wiring until the UI is refactored away from hard-coded options

If a change alters whether a relation is valid, searchable, serialized, or
materialized, it is **not** descriptor-only metadata.

### Test checklist for relation changes

- [ ] `taxonomy-domain/src/test/java/com/taxonomy/model/RelationTypeTest.java`
- [ ] `taxonomy-app/src/test/java/com/taxonomy/TaxonomyRelationTests.java`
- [ ] `taxonomy-app/src/test/java/com/taxonomy/RelationProposalTests.java`
- [ ] `taxonomy-app/src/test/java/com/taxonomy/RelationQualityTests.java`
- [ ] `taxonomy-app/src/test/java/com/taxonomy/AnalysisRelationGeneratorTests.java`
- [ ] `taxonomy-app/src/test/java/com/taxonomy/catalog/service/RelationSeedParserTest.java`
- [ ] `taxonomy-app/src/test/java/com/taxonomy/architecture/pipeline/ProvisionalRelationStepTest.java`
- [ ] `taxonomy-dsl/src/test/java/com/taxonomy/dsl/DslValidatorTest.java`
- [ ] `taxonomy-dsl/src/test/java/com/taxonomy/dsl/DslTokenizerTest.java`
- [ ] `taxonomy-dsl/src/test/java/com/taxonomy/dsl/TaxDslParserTest.java`
- [ ] `taxonomy-dsl/src/test/java/com/taxonomy/dsl/TaxDslSerializerTest.java`

---

## 2. DSL metadata

### Current path for adding or changing DSL metadata

Changing DSL metadata is also a **core-code change** whenever the metadata is
interpreted by the parser, serializer, mapper, validator, indexer, or REST API.

1. Decide whether the change is about:
   - the document `meta` block,
   - a known block kind,
   - a known property on an existing block, or
   - a free-form `x-*` extension attribute.
2. For `meta` block semantics, update:
   - `taxonomy-dsl/src/main/java/com/taxonomy/dsl/ast/MetaAst.java`
   - `taxonomy-dsl/src/main/java/com/taxonomy/dsl/parser/TaxDslParser.java`
   - `taxonomy-dsl/src/main/java/com/taxonomy/dsl/mapper/ModelToAstMapper.java`
3. For known block kinds or known properties, update together:
   - `taxonomy-dsl/src/main/java/com/taxonomy/dsl/parser/TaxDslParser.java`
   - `taxonomy-dsl/src/main/java/com/taxonomy/dsl/serializer/TaxDslSerializer.java`
   - `taxonomy-dsl/src/main/java/com/taxonomy/dsl/mapper/AstToModelMapper.java`
   - `taxonomy-dsl/src/main/java/com/taxonomy/dsl/mapper/ModelToAstMapper.java`
   - the affected model class under `taxonomy-dsl/src/main/java/com/taxonomy/dsl/model/`
4. If the change introduces a new block kind or searchable structure, also update:
   - `taxonomy-dsl/src/main/java/com/taxonomy/dsl/parser/DslTokenizer.java`
   - `taxonomy-dsl/src/main/java/com/taxonomy/dsl/validation/DslValidator.java`
   - any diff/index/materialization consumer that assumes the old shape
5. Review the REST/UI layer that exercises canonical parsing and formatting:
   - `taxonomy-app/src/main/java/com/taxonomy/versioning/controller/DslApiController.java`
   - `taxonomy-app/src/main/resources/static/js/shared/taxonomy-dsl-editor.js`
6. Review materialization/indexing consumers when metadata becomes operational:
   - `taxonomy-app/src/main/java/com/taxonomy/dsl/export/DslMaterializeService.java`
   - `taxonomy-app/src/test/java/com/taxonomy/dsl/CommitIndexHibernateSearchTest.java`
   - `taxonomy-app/src/test/java/com/taxonomy/dsl/DslApiControllerTest.java`

### Files, services, UI modules, and tests affected by DSL metadata changes

| Area | Current files/services to review |
|---|---|
| Document metadata | `taxonomy-dsl/.../MetaAst.java`, `DocumentAst.java`, `TaxDslParser.java`, `ModelToAstMapper.java` |
| Parsing / canonical serialization | `TaxDslParser.java`, `TaxDslSerializer.java` |
| Typed model mapping | `AstToModelMapper.java`, `ModelToAstMapper.java`, model classes under `taxonomy-dsl/.../model/` |
| Validation / indexing / diff | `DslValidator.java`, `DslTokenizer.java`, `ModelDiffer.java`, `SemanticDiffDescriber.java` |
| App-layer DSL consumers | `taxonomy-app/.../versioning/controller/DslApiController.java`, `taxonomy-app/.../dsl/export/DslMaterializeService.java`, `taxonomy-app/.../versioning/service/DslOperationsFacade.java` |
| UI | `taxonomy-app/src/main/resources/static/js/shared/taxonomy-dsl-editor.js` |
| Tests | `taxonomy-dsl/src/test/java/com/taxonomy/dsl/TaxDslParserTest.java`, `TaxDslSerializerTest.java`, `TaxDslRoundtripTest.java`, `DslValidatorTest.java`, `DslTokenizerTest.java`, `AstToModelMapperTest.java`, `ModelToAstMapperTest.java`, plus app tests such as `taxonomy-app/src/test/java/com/taxonomy/dsl/DslApiControllerTest.java`, `DslMaterializeServiceTest.java`, `CommitIndexHibernateSearchTest.java`, and `dsl/storage/DslGitRepositoryTest.java` when storage/index behavior is affected |

### DSL metadata that can safely move to an extension descriptor

Descriptor-only DSL metadata should stay **non-semantic**:

- block/property display labels shown in the editor or documentation
- help text, examples, and authoring guidance
- editor grouping, palette sections, or autocomplete presentation order
- documentation-only aliases
- optional UI hints about expected value shape

Within DSL documents themselves, the current safe escape hatch is still `x-*`
extension attributes. They are preserved round-trip and intentionally left
outside strict validation. Those `x-*` values are document content, not SPI
descriptors: they extend a specific DSL document, while a descriptor would
describe shared editor or UI metadata for the whole system.

### DSL invariants that should remain hard-coded and tested

Keep these in core code:

- `MetaAst.LANGUAGE_ID` and `MetaAst.CURRENT_VERSION`
- `TaxDslParser.KNOWN_BLOCK_TYPES`
- canonical property ordering in `TaxDslSerializer.PROPERTY_ORDER`
- typed model fields and the AST/model mappers
- validation rules in `DslValidator`
- index token rules in `DslTokenizer`
- the parse → map → serialize → parse round-trip invariant
- REST behaviors in `DslApiController` that depend on canonical DSL structure

If metadata changes how the document parses, serializes, validates, indexes, or
materializes, it must remain core.

### Test checklist for DSL metadata changes

- [ ] `taxonomy-dsl/src/test/java/com/taxonomy/dsl/TaxDslParserTest.java`
- [ ] `taxonomy-dsl/src/test/java/com/taxonomy/dsl/TaxDslSerializerTest.java`
- [ ] `taxonomy-dsl/src/test/java/com/taxonomy/dsl/TaxDslRoundtripTest.java`
- [ ] `taxonomy-dsl/src/test/java/com/taxonomy/dsl/DslValidatorTest.java`
- [ ] `taxonomy-dsl/src/test/java/com/taxonomy/dsl/DslTokenizerTest.java`
- [ ] `taxonomy-dsl/src/test/java/com/taxonomy/dsl/AstToModelMapperTest.java`
- [ ] `taxonomy-dsl/src/test/java/com/taxonomy/dsl/ModelToAstMapperTest.java`
- [ ] `taxonomy-app/src/test/java/com/taxonomy/dsl/DslApiControllerTest.java`
- [ ] `taxonomy-app/src/test/java/com/taxonomy/dsl/DslMaterializeServiceTest.java`
- [ ] `taxonomy-app/src/test/java/com/taxonomy/dsl/CommitIndexHibernateSearchTest.java`
- [ ] `taxonomy-app/src/test/java/com/taxonomy/dsl/storage/DslGitRepositoryTest.java` when storage or round-trip persistence is affected

---

## 3. Boundary rule for future SPI work

A relation or DSL descriptor is a good candidate for the internal extension SPI
only when all of the following are true:

- the value is descriptive rather than behavioral
- changing it does not require parser/validator/matrix changes
- changing it does not alter persisted enum/property values
- existing round-trip, validation, indexing, and materialization tests should
  still pass unchanged

If any of those statements is false, the change belongs in core code rather than
in an extension descriptor.
