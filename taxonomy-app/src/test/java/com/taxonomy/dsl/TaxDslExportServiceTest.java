package com.taxonomy.dsl;

import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.model.TaxonomyRelation;
import com.taxonomy.catalog.repository.TaxonomyNodeRepository;
import com.taxonomy.catalog.repository.TaxonomyRelationRepository;
import com.taxonomy.dsl.export.TaxDslExportService;
import com.taxonomy.dsl.mapper.AstToModelMapper;
import com.taxonomy.dsl.mapper.ModelToAstMapper;
import com.taxonomy.dsl.model.CanonicalArchitectureModel;
import com.taxonomy.dsl.parser.TaxDslParser;
import com.taxonomy.dsl.serializer.TaxDslSerializer;
import com.taxonomy.model.RelationType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link TaxDslExportService} — verifies that the export pipeline
 * produces correct DSL output at each intermediate step.
 */
@SpringBootTest
@WithMockUser(roles = "ADMIN")
class TaxDslExportServiceTest {

    @Autowired
    private TaxDslExportService exportService;

    @Autowired
    private TaxonomyNodeRepository nodeRepository;

    @Autowired
    private TaxonomyRelationRepository relationRepository;

    /** Unique workspace used by test-created relations — cleaned up after each test. */
    private static final String TEST_WORKSPACE = "export-service-test";

    @AfterEach
    void cleanupTestRelations() {
        relationRepository.findByWorkspaceId(TEST_WORKSPACE).forEach(relationRepository::delete);
    }

    // ── exportAll() ─────────────────────────────────────────────────────────

    @Test
    void exportAllReturnsNonEmptyDsl() {
        String dsl = exportService.exportAll("default");

        assertThat(dsl).isNotBlank();
    }

    @Test
    void exportAllContainsMetaBlock() {
        String dsl = exportService.exportAll("default");

        assertThat(dsl).contains("meta");
        assertThat(dsl).contains("language:");
    }

    @Test
    void exportAllContainsElementBlocksWhenNodesExist() {
        // The test database is pre-populated from the taxonomy Excel workbook (~2500 non-root nodes).
        // A successful export must contain element blocks.
        String dsl = exportService.exportAll("default");

        assertThat(dsl).contains("element");
    }

    @Test
    void exportAllContainsRelationBlocksAfterRelationCreated() {
        // Find two non-root nodes to use as source/target
        List<TaxonomyNode> nonRootNodes = nodeRepository.findAll(
                org.springframework.data.domain.PageRequest.of(0, 10)).getContent()
                .stream().filter(n -> n.getLevel() > 0).limit(2).toList();
        assertThat(nonRootNodes).hasSizeGreaterThanOrEqualTo(2);

        TaxonomyNode source = nonRootNodes.get(0);
        TaxonomyNode target = nonRootNodes.get(1);

        TaxonomyRelation rel = new TaxonomyRelation();
        rel.setSourceNode(source);
        rel.setTargetNode(target);
        rel.setRelationType(RelationType.REALIZES);
        rel.setWorkspaceId(TEST_WORKSPACE);
        relationRepository.save(rel);

        String dsl = exportService.exportAll("default");

        assertThat(dsl).contains("relation");
    }

    // ── Round-trip: exportAll → parse → map → toDocument → serialize ────────

    @Test
    void exportRoundTripProducesEquivalentOutput() {
        String original = exportService.exportAll("default");

        // parse → model → AST → serialize
        TaxDslParser parser = new TaxDslParser();
        AstToModelMapper astMapper = new AstToModelMapper();
        ModelToAstMapper modelMapper = new ModelToAstMapper();
        TaxDslSerializer serializer = new TaxDslSerializer();

        var ast = parser.parse(original);
        CanonicalArchitectureModel model = astMapper.map(ast);
        var docAst = modelMapper.toDocument(model, "default");
        String roundTripped = serializer.serialize(docAst);

        // Re-parse both to compare element counts
        var originalAst = parser.parse(original);
        var roundTrippedAst = parser.parse(roundTripped);

        assertThat(roundTrippedAst.getBlocks()).hasSameSizeAs(originalAst.getBlocks());
    }

    @Test
    void exportNamespaceIsReflectedInMetaBlock() {
        String namespace = "test.export.service";
        String dsl = exportService.exportAll(namespace);

        assertThat(dsl).contains("namespace: \"" + namespace + "\"");
    }
}
