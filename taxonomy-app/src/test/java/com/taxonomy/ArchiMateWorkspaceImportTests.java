package com.taxonomy;

import com.taxonomy.catalog.repository.TaxonomyRelationRepository;
import com.taxonomy.catalog.service.ArchiMateImportException;
import com.taxonomy.catalog.service.ArchiMateXmlImporter;
import com.taxonomy.dto.ArchiMateImportResult;
import com.taxonomy.workspace.service.WorkspaceContext;
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

    @Test
    void previewReportsEligibleRelationWithoutWriting() {
        WorkspaceContext context = new WorkspaceContext("alice", "qa-archimate-a", "draft");
        long before = relationRepository.count();

        ArchiMateImportResult result = importer.previewXml(stream(validModel()), context);

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

        ArchiMateImportResult first = importer.importXml(stream(validModel()), firstContext);
        ArchiMateImportResult second = importer.importXml(stream(validModel()), secondContext);

        assertThat(first.getRelationsImported()).isEqualTo(1);
        assertThat(second.getRelationsImported()).isEqualTo(1);
        assertThat(relationRepository.findByWorkspaceId("qa-archimate-a")).hasSize(1);
        assertThat(relationRepository.findByWorkspaceId("qa-archimate-b")).hasSize(1);
        assertThat(relationRepository.countByWorkspaceIdIsNull()).isEqualTo(sharedBefore);
    }

    @Test
    void repeatedImportInSameWorkspaceIsReportedAsDuplicate() {
        WorkspaceContext context = new WorkspaceContext("alice", "qa-archimate-a", "draft");
        importer.importXml(stream(validModel()), context);

        ArchiMateImportResult duplicate = importer.importXml(stream(validModel()), context);

        assertThat(duplicate.getRelationsImported()).isZero();
        assertThat(duplicate.getRelationsSkipped()).isEqualTo(1);
        assertThat(relationRepository.findByWorkspaceId("qa-archimate-a")).hasSize(1);
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

    private static ByteArrayInputStream stream(String xml) {
        return new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
    }

    private static String validModel() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <model xmlns="http://www.opengroup.org/xsd/archimate/3.0/"
                       xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                       identifier="id-model-1">
                  <elements>
                    <element identifier="id-e1" xsi:type="Capability">
                      <name xml:lang="en">Capabilities</name>
                    </element>
                    <element identifier="id-e2" xsi:type="ApplicationService">
                      <name xml:lang="en">Core Services</name>
                    </element>
                  </elements>
                  <relationships>
                    <relationship identifier="id-r1" xsi:type="Realization"
                                  source="id-e1" target="id-e2"/>
                  </relationships>
                </model>
                """;
    }
}