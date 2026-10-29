package com.taxonomy;

import com.taxonomy.catalog.repository.TaxonomyRelationRepository;
import com.taxonomy.catalog.service.ArchiMateImportException;
import com.taxonomy.catalog.service.ArchiMateXmlImporter;
import com.taxonomy.dto.ArchiMateImportResult;
import com.taxonomy.model.RelationType;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@WithMockUser(username = "qa-architect", roles = {"USER", "ARCHITECT"})
class ArchiMateWorkspaceImportTests {

    @Autowired
    private ArchiMateXmlImporter importer;

    @Autowired
    private TaxonomyRelationRepository relationRepository;

    @Autowired
    private MockMvc mockMvc;

    @Autowired private com.taxonomy.workspace.repository.UserWorkspaceRepository workspaceRepository;
    @Autowired private com.taxonomy.workspace.service.SystemRepositoryService repositories;
    @Autowired private com.taxonomy.workspace.service.ArchitectureRepositoryProvisioningService repositoryProvisioning;


    @BeforeEach
    void removeEligibleFixtureRelationFromAllScopes() {
        relationRepository.deleteAll(
                relationRepository.findBySourceNodeCodeAndTargetNodeCodeAndRelationType(
                        "CP", "CR", RelationType.COMMUNICATES_WITH));
        relationRepository.flush();
        var primary = repositories.getPrimaryRepository();
        savedWorkspace("qa-architect", primary.getRepositoryId(), primary.getDefaultBranch(), true);
    }

    @Test
    void previewReportsEligibleRelationWithoutWriting() {
        WorkspaceContext context = new WorkspaceContext("alice", "qa-archimate-a", "draft");
        long before = relationRepository.count();

        ArchiMateImportResult result = importer.previewXml(stream(eligibleModel()), context);

        assertThat(result.isPreview()).isTrue();
        assertThat(result.getElementsMatched()).isEqualTo(2);
        assertThat(result.getRelationsParsed()).isEqualTo(1);
        assertThat(result.getRelationsImported()).isZero();
        assertThat(result.getRelationsSkipped()).isZero();
        assertThat(result.getRelationsRejected()).isZero();
        assertThat(relationRepository.count()).isEqualTo(before);
    }

    @Test
    void equivalentModelsCanBeImportedIntoSeparateWorkspaces() {
        WorkspaceContext firstContext = new WorkspaceContext("alice", "qa-archimate-a", "draft");
        WorkspaceContext secondContext = new WorkspaceContext("bob", "qa-archimate-b", "draft");
        long sharedBefore = relationRepository.countByWorkspaceIdIsNull();

        ArchiMateImportResult first = importer.importXml(stream(eligibleModel()), firstContext);
        ArchiMateImportResult second = importer.importXml(stream(eligibleModel()), secondContext);

        assertThat(first.getRelationsImported()).isEqualTo(1);
        assertThat(second.getRelationsImported()).isEqualTo(1);
        assertThat(relationRepository.findByWorkspaceId("qa-archimate-a")).hasSize(1);
        assertThat(relationRepository.findByWorkspaceId("qa-archimate-b")).hasSize(1);
        assertThat(relationRepository.countByWorkspaceIdIsNull()).isEqualTo(sharedBefore);
    }

    @Test
    void repeatedImportInSameWorkspaceIsReportedAsDuplicate() {
        WorkspaceContext context = new WorkspaceContext("alice", "qa-archimate-a", "draft");
        importer.importXml(stream(eligibleModel()), context);

        ArchiMateImportResult duplicate = importer.importXml(stream(eligibleModel()), context);

        assertThat(duplicate.getRelationsImported()).isZero();
        assertThat(duplicate.getRelationsSkipped()).isEqualTo(1);
        assertThat(relationRepository.findByWorkspaceId("qa-archimate-a")).hasSize(1);
    }

    @Test
    void sharedBaselineRelationIsVisibleAsDuplicateInPersonalWorkspace() {
        WorkspaceContext context = new WorkspaceContext("alice", "qa-archimate-a", "draft");
        // Establish the baseline idempotently inside this transaction. Depending
        // on suite order or catalogue fixtures it may already exist; importing it
        // into shared scope either creates it or reports the existing duplicate.
        importer.importXml(stream(sharedDuplicateModel()), WorkspaceContext.SHARED);

        ArchiMateImportResult result = importer.previewXml(stream(sharedDuplicateModel()), context);

        assertThat(result.getRelationsImported()).isZero();
        assertThat(result.getRelationsSkipped()).isEqualTo(1);
        assertThat(relationRepository.findByWorkspaceId("qa-archimate-a")).isEmpty();
    }

    @Test
    void malformedXmlFailsInsteadOfReturningSuccessfulResult() {
        assertThatThrownBy(() -> importer.importXml(
                stream("<model><elements>"), WorkspaceContext.SHARED))
                .isInstanceOf(ArchiMateImportException.class)
                .hasMessageContaining("Malformed");
    }

    @Test
    void endpointReturns422ForMalformedXmlAndDoesNotMutateRelations() throws Exception {
        long before = relationRepository.count();
        MockMultipartFile file = new MockMultipartFile(
                "file", "broken.xml", MediaType.APPLICATION_XML_VALUE,
                "<model><elements>".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/import/archimate").file(file))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("INVALID_ARCHIMATE_XML"));

        assertThat(relationRepository.count()).isEqualTo(before);
    }

    @Test
    void endpointRejectsDoctypeAndExternalEntityInput() throws Exception {
        String xml = """
                <?xml version="1.0"?>
                <!DOCTYPE model [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <model xmlns="http://www.opengroup.org/xsd/archimate/3.0/"
                       xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                  <elements>
                    <element identifier="id-e1" xsi:type="Capability"><name>&xxe;</name></element>
                  </elements>
                </model>
                """;
        MockMultipartFile file = new MockMultipartFile(
                "file", "xxe.xml", MediaType.APPLICATION_XML_VALUE,
                xml.getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/import/preview/archimate").file(file))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("INVALID_ARCHIMATE_XML"));
    }

    @Test
    void explicitReturnEndpointPreviewsReviewsAppliesAndRejectsChangedReview() throws Exception {
        byte[] original = returnOriginal();
        byte[] returned = new String(original, StandardCharsets.UTF_8).replace("type=\"number\"", "type=\"string\"")
                .replace("type=\"boolean\"", "type=\"string\"").getBytes(StandardCharsets.UTF_8);
        var sourceFile = new MockMultipartFile("original", "original.xml", MediaType.APPLICATION_XML_VALUE, original);
        var returnFile = new MockMultipartFile("file", "returned.xml", MediaType.APPLICATION_XML_VALUE, returned);
        long before = relationRepository.count();
        String response = mockMvc.perform(multipart("/api/import/preview/archimate-return").file(sourceFile).file(returnFile))
                .andExpect(status().isOk()).andExpect(jsonPath("$.preview").value(true))
                .andExpect(jsonPath("$.mappingProfile").value("taxonomy-archi-5.10-return-v1"))
                .andExpect(jsonPath("$.writableContext").value(true))
                .andExpect(jsonPath("$.relationsImported").value(0)).andReturn().getResponse().getContentAsString();
        assertThat(relationRepository.count()).isEqualTo(before);
        String fingerprint = tools.jackson.databind.json.JsonMapper.builder().build().readTree(response).get("reviewFingerprint").asString();
        mockMvc.perform(multipart("/api/import/archimate-return").file(sourceFile).file(returnFile).param("reviewedFingerprint", "wrong"))
                .andExpect(status().isUnprocessableEntity());
        assertThat(relationRepository.count()).isEqualTo(before);
        mockMvc.perform(multipart("/api/import/archimate-return").file(sourceFile).file(returnFile).param("reviewedFingerprint", fingerprint))
                .andExpect(status().isOk()).andExpect(jsonPath("$.relationsImported").value(1));
        mockMvc.perform(multipart("/api/import/archimate-return").file(sourceFile).file(returnFile).param("reviewedFingerprint", fingerprint))
                .andExpect(status().isOk()).andExpect(jsonPath("$.relationsImported").value(0)).andExpect(jsonPath("$.relationsSkipped").value(1));
        assertThat(relationRepository.count()).isEqualTo(before + 1);
        mockMvc.perform(multipart("/api/import/preview/archimate").file(returnFile)).andExpect(status().isUnprocessableEntity());
    }

    @Test
    @WithMockUser(username = "return-reader", roles = {"USER"})
    void returnApplicationRetainsArchitectAuthorization() throws Exception {
        byte[] original = returnOriginal();
        var source = new MockMultipartFile("original", "original.xml", MediaType.APPLICATION_XML_VALUE, original);
        var returned = new MockMultipartFile("file", "returned.xml", MediaType.APPLICATION_XML_VALUE, original);
        mockMvc.perform(multipart("/api/import/preview/archimate-return").file(source).file(returned)).andExpect(status().isOk());
        mockMvc.perform(multipart("/api/import/archimate-return").file(source).file(returned).param("reviewedFingerprint", "irrelevant"))
                .andExpect(status().isForbidden());
    }

    @Test
    void returnHttpUsesPersistedNonPrimaryRepositoryAndNeverWritesPrimaryTenant() throws Exception {
        var sourceRepository = repositoryProvisioning.createRepository("Archi return B", "archi-return-" + java.util.UUID.randomUUID(),
                "isolated return regression", com.taxonomy.workspace.model.RepositoryVisibility.PRIVATE, "qa-architect", "branch-b");
        var workspace = savedWorkspace("qa-architect", sourceRepository.getRepositoryId(), "branch-b", false);
        byte[] original = returnOriginal();
        var source = new MockMultipartFile("original", "source.xml", MediaType.APPLICATION_XML_VALUE, original);
        var returned = new MockMultipartFile("file", "returned.xml", MediaType.APPLICATION_XML_VALUE, original);
        String response = mockMvc.perform(multipart("/api/import/preview/archimate-return").file(source).file(returned)
                        .header("X-Taxonomy-Workspace-Id", workspace.getWorkspaceId()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String fingerprint = tools.jackson.databind.json.JsonMapper.builder().build().readTree(response).get("reviewFingerprint").asString();
        mockMvc.perform(multipart("/api/import/archimate-return").file(source).file(returned)
                        .param("reviewedFingerprint", fingerprint))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(multipart("/api/import/archimate-return").file(source).file(returned)
                        .header("X-Taxonomy-Workspace-Id", workspace.getWorkspaceId()).param("reviewedFingerprint", fingerprint))
                .andExpect(status().isOk()).andExpect(jsonPath("$.relationsImported").value(1));
        var rows = relationRepository.findByRepositoryIdAndWorkspaceIdAndSourceNodeCodeAndTargetNodeCodeAndRelationType(
                sourceRepository.getRepositoryId(), workspace.getWorkspaceId(), "CP", "CR", RelationType.COMMUNICATES_WITH);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().getOwnerUsername()).isEqualTo("qa-architect");
        assertThat(rows.getFirst().getProvenance()).isEqualTo("ARCHIMATE_RETURN");
        assertThat(relationRepository.findByRepositoryIdAndWorkspaceIdAndSourceNodeCodeAndTargetNodeCodeAndRelationType(
                repositories.getPrimaryRepository().getRepositoryId(), workspace.getWorkspaceId(), "CP", "CR", RelationType.COMMUNICATES_WITH)).isEmpty();
    }

    @Test
    void centralHttpPreviewBindsAuthenticatedUserAndDoesNotGrantWriteAuthority() throws Exception {
        byte[] original = returnOriginal();
        var source = new MockMultipartFile("original", "source.xml", MediaType.APPLICATION_XML_VALUE, original);
        var returned = new MockMultipartFile("file", "returned.xml", MediaType.APPLICATION_XML_VALUE, original);
        java.util.List<String> fingerprints = new java.util.ArrayList<>();
        for (String user : java.util.List.of("central-return-alice", "central-return-bob")) {
            String response = mockMvc.perform(multipart("/api/import/preview/archimate-return").file(source).file(returned)
                            .header("X-Taxonomy-Workspace-Id", "")
                            .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(user).roles("USER", "ARCHITECT")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.writableContext").value(false))
                    .andReturn().getResponse().getContentAsString();
            fingerprints.add(tools.jackson.databind.json.JsonMapper.builder().build().readTree(response).get("reviewFingerprint").asString());
        }
        assertThat(fingerprints.getFirst()).isNotEqualTo(fingerprints.getLast());
        long before = relationRepository.count();
        mockMvc.perform(multipart("/api/import/archimate-return").file(source).file(returned)
                        .header("X-Taxonomy-Workspace-Id", "").param("reviewedFingerprint", fingerprints.getFirst())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("central-return-alice").roles("USER", "ARCHITECT")))
                .andExpect(status().isForbidden());
        mockMvc.perform(multipart("/api/import/archimate").file(returned).header("X-Taxonomy-Workspace-Id", ""))
                .andExpect(status().isForbidden());
        assertThat(relationRepository.count()).isEqualTo(before);
    }

    @Test
    void ordinaryHttpImportRetainsNonPrimaryRepositoryIdentity() throws Exception {
        var repository = repositoryProvisioning.createRepository("Ordinary B", "ordinary-" + java.util.UUID.randomUUID(),
                "ordinary import compatibility", com.taxonomy.workspace.model.RepositoryVisibility.PRIVATE, "qa-architect", "branch-b");
        var workspace = savedWorkspace("qa-architect", repository.getRepositoryId(), "branch-b", false);
        var file = new MockMultipartFile("file", "model.xml", MediaType.APPLICATION_XML_VALUE, eligibleModel().getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(multipart("/api/import/archimate").file(file).header("X-Taxonomy-Workspace-Id", workspace.getWorkspaceId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.relationsImported").value(1));
        assertThat(relationRepository.findByRepositoryIdAndWorkspaceId(repository.getRepositoryId(), workspace.getWorkspaceId())).hasSize(1);
        assertThat(relationRepository.findByRepositoryIdAndWorkspaceId(repositories.getPrimaryRepository().getRepositoryId(), workspace.getWorkspaceId())).isEmpty();
    }

    private com.taxonomy.workspace.model.UserWorkspace savedWorkspace(String username, String repositoryId, String branch, boolean defaultWorkspace) {
        var workspace = new com.taxonomy.workspace.model.UserWorkspace();
        workspace.setWorkspaceId("archi-return-" + java.util.UUID.randomUUID());
        workspace.setUsername(username); workspace.setDisplayName(workspace.getWorkspaceId());
        workspace.setSourceRepositoryId(repositoryId); workspace.setCurrentBranch(branch);
        workspace.setSourceBranch(branch); workspace.setCreatedAt(java.time.Instant.now());
        workspace.setDefault(defaultWorkspace);
        return workspaceRepository.saveAndFlush(workspace);
    }

    private static byte[] returnOriginal() {
        var graph = new com.taxonomy.diagram.DiagramModel("Return endpoint fixture", java.util.List.of(
                new com.taxonomy.diagram.DiagramNode("CP", "Capabilities", "Capabilities", .9, true, 1),
                new com.taxonomy.diagram.DiagramNode("CR", "Core Services", "Core Services", .8, false, 2)),
                java.util.List.of(new com.taxonomy.diagram.DiagramEdge("return-r", "CP", "CR", "COMMUNICATES_WITH", .8)),
                new com.taxonomy.diagram.DiagramLayout("LR", true));
        return new com.taxonomy.archimate.exchange.ArchiMateXmlExporter().export(new com.taxonomy.export.ArchiMateDiagramService().convert(graph));
    }

    private static ByteArrayInputStream stream(String xml) {
        return new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
    }

    private static String eligibleModel() {
        return modelWithRelationshipType("Flow");
    }

    private static String sharedDuplicateModel() {
        return modelWithRelationshipType("Realization");
    }

    private static String modelWithRelationshipType(String relationshipType) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <model xmlns="http://www.opengroup.org/xsd/archimate/3.0/"
                       xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                       identifier="id-model-1">
                  <name xml:lang="en">Workspace import fixture</name>
                  <elements>
                    <element identifier="id-e1" xsi:type="Capability">
                      <name xml:lang="en">Capabilities</name>
                    </element>
                    <element identifier="id-e2" xsi:type="ApplicationService">
                      <name xml:lang="en">Core Services</name>
                    </element>
                  </elements>
                  <relationships>
                    <relationship identifier="id-r1" xsi:type="%s"
                                  source="id-e1" target="id-e2"/>
                  </relationships>
                </model>
                """.formatted(relationshipType);
    }
}
