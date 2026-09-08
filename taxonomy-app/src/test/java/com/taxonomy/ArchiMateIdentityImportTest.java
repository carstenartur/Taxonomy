package com.taxonomy;

import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.repository.TaxonomyNodeRepository;
import com.taxonomy.catalog.service.*;
import com.taxonomy.diagram.*;
import com.taxonomy.dto.TaxonomyRelationDto;
import com.taxonomy.export.*;
import com.taxonomy.model.RelationType;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.RepositoryContext;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Proxy;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Real serializer and importer; only the catalogue boundary is an in-memory fixture. */
class ArchiMateIdentityImportTest {
    @Test
    void duplicateOrChangedLabelsNeverOverrideExactIdsAndOriginalRelationshipTypes() {
        var nodes = catalogue("CP", "CR");
        var relations = new InMemoryRelations();
        var importer = new ArchiMateXmlImporter(nodes, relations);
        var context = new WorkspaceContext("alice", "workspace", "branch");
        byte[] xml = export("CP", "CR");
        var preview = importer.previewXml(new ByteArrayInputStream(xml), context);
        assertEquals(2, preview.getElementsMatched());
        assertEquals(0, preview.getRelationsImported());
        assertTrue(relations.rows.isEmpty());
        assertEquals(ArchiMateExchangeProfile.VERSION, preview.getMappingProfile());
        assertTrue(preview.getLosses().stream().anyMatch(loss -> loss.scope().equals("view") && loss.kind().equals("OMITTED")));
        var imported = importer.importXml(new ByteArrayInputStream(xml), context);
        assertEquals(1, imported.getRelationsImported());
        assertEquals(Set.of("workspace|CR|CP|FULFILLS"), relations.rows);
        var repeated = importer.importXml(new ByteArrayInputStream(xml), context);
        assertEquals(0, repeated.getRelationsImported());
        assertEquals(1, repeated.getRelationsSkipped());
        var other = new WorkspaceContext("bob", "other-workspace", "branch");
        assertEquals(1, importer.importXml(new ByteArrayInputStream(xml), other).getRelationsImported());
    }

    @Test
    void unknownExactIdentityIsReportedWithoutLabelFallbackAndMalformedXmlDoesNotWrite() {
        var relations = new InMemoryRelations();
        var importer = new ArchiMateXmlImporter(catalogue("CP", "CR"), relations);
        var result = importer.importXml(new ByteArrayInputStream(export("missing", "CR")), WorkspaceContext.SHARED);
        assertEquals(1, result.getElementsUnmatched());
        assertEquals(1, result.getRelationsRejected());
        assertTrue(relations.rows.isEmpty());
        assertThrows(ArchiMateImportException.class, () -> importer.importXml(new ByteArrayInputStream("<model/>".getBytes()), WorkspaceContext.SHARED));
        assertTrue(relations.rows.isEmpty());
    }

    private static byte[] export(String first, String second) {
        var graph = new DiagramModel("Identity fixture", List.of(
                new DiagramNode(first, "Identical external display label", "Capabilities", .9, true, 1),
                new DiagramNode(second, "Identical external display label", "Core Services", .8, false, 2)),
                List.of(new DiagramEdge("original-r", second, first, "FULFILLS", .8)), new DiagramLayout("LR", true));
        return new ArchiMateXmlExporter().export(new ArchiMateDiagramService().convert(graph));
    }

    @Test
    void identicalWorkspaceNamesRemainIsolatedAcrossExplicitRepositories() {
        var relations = new InMemoryRelations();
        var importer = new ArchiMateXmlImporter(catalogue("CP", "CR"), relations);
        byte[] bytes = export("CP", "CR");
        for (String repository : List.of("repository-a", "repository-b")) {
            var context = new WorkspaceContext("alice", "same-workspace-name", "branch", repository);
            assertEquals(1, importer.importXml(new ByteArrayInputStream(bytes), context).getRelationsImported());
            assertEquals(1, importer.importXml(new ByteArrayInputStream(bytes), context).getRelationsSkipped());
        }
        assertEquals(Set.of("repository-a|same-workspace-name|CR|CP|FULFILLS",
                "repository-b|same-workspace-name|CR|CP|FULFILLS"), relations.rows);
    }

    private static TaxonomyNodeRepository catalogue(String... ids) {
        Map<String, TaxonomyNode> nodes = new HashMap<>();
        for (String id : ids) {
            var node = new TaxonomyNode();
            node.setCode(id);
            node.setNameEn("Different current catalogue label");
            nodes.put(id, node);
        }
        return (TaxonomyNodeRepository) Proxy.newProxyInstance(TaxonomyNodeRepository.class.getClassLoader(),
                new Class<?>[] {TaxonomyNodeRepository.class}, (proxy, method, args) -> {
                    if (method.getName().equals("findByCode")) return Optional.ofNullable(nodes.get(args[0]));
                    throw new AssertionError("Unexpected catalogue call; label matching is forbidden: " + method.getName());
                });
    }

    private static final class InMemoryRelations extends TaxonomyRelationService {
        final Set<String> rows = new HashSet<>();
        InMemoryRelations() { super(null, null, null); }
        @Override public boolean relationExistsVisible(String source, String target, RelationType type, String workspace) {
            return rows.contains(workspace + "|" + source + "|" + target + "|" + type);
        }
        @Override public TaxonomyRelationDto createRelation(String source, String target, RelationType type,
                                                            String description, String provenance, String workspace, String owner) {
            assertTrue(rows.add(workspace + "|" + source + "|" + target + "|" + type));
            return new TaxonomyRelationDto();
        }
        @Override public boolean relationExistsVisibleInContext(String source, String target, RelationType type, RepositoryContext context) {
            return rows.contains(context.repositoryId() + "|" + context.workspaceId() + "|" + source + "|" + target + "|" + type);
        }
        @Override public TaxonomyRelationDto createRelationInContext(String source, String target, RelationType type,
                                                                    String description, String provenance, RepositoryContext context) {
            assertEquals("alice", context.username());
            assertEquals("branch", context.branch());
            assertTrue(rows.add(context.repositoryId() + "|" + context.workspaceId() + "|" + source + "|" + target + "|" + type));
            return new TaxonomyRelationDto();
        }
    }
}
