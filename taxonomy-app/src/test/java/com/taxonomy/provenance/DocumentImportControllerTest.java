package com.taxonomy.provenance;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for the Document Import and Provenance REST APIs.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "spring.ai.gemini.api-key=",
    "spring.ai.openai.api-key=",
    "spring.ai.deepseek.api-key=",
    "spring.ai.qwen.api-key=",
    "spring.ai.llama.api-key=",
    "spring.ai.mistral.api-key="
})
@WithMockUser
class DocumentImportControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void uploadEmptyFileReturnsBadRequest() throws Exception {
        MockMultipartFile emptyFile = new MockMultipartFile(
                "file", "empty.pdf", "application/pdf", new byte[0]);

        mockMvc.perform(multipart("/api/documents/upload").file(emptyFile))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listSourcesReturnsOk() throws Exception {
        mockMvc.perform(get("/api/provenance/sources")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void getLinksForNonExistentRequirementReturnsEmptyList() throws Exception {
        mockMvc.perform(get("/api/provenance/links/NONEXISTENT-REQ")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void uploadDocxFileSucceeds() throws Exception {
        // Create a minimal DOCX file for testing
        byte[] docxContent = createMinimalDocx();

        MockMultipartFile docxFile = new MockMultipartFile(
                "file", "test-regulation.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                docxContent);

        mockMvc.perform(multipart("/api/documents/upload")
                .file(docxFile)
                .param("title", "Test Regulation")
                .param("sourceType", "REGULATION"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileName").value("test-regulation.docx"))
                .andExpect(jsonPath("$.sourceArtifactId").isNumber())
                .andExpect(jsonPath("$.sourceVersionId").isNumber());
    }

    @Test
    void uploadWithInvalidSourceTypeDefaultsToUploadedDocument() throws Exception {
        byte[] docxContent = createMinimalDocx();

        MockMultipartFile docxFile = new MockMultipartFile(
                "file", "test.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                docxContent);

        mockMvc.perform(multipart("/api/documents/upload")
                .file(docxFile)
                .param("sourceType", "INVALID_TYPE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceArtifactId").isNumber());
    }

    /**
     * Creates a minimal DOCX file using Apache POI for testing.
     */
    private byte[] createMinimalDocx() {
        try (var doc = new org.apache.poi.xwpf.usermodel.XWPFDocument()) {
            var para = doc.createParagraph();
            para.createRun().setText("§ 1 Test Requirement");
            var para2 = doc.createParagraph();
            para2.createRun().setText(
                    "The authority must ensure that all applications are processed " +
                    "within 30 days. This deadline applies to all administrative procedures.");
            var out = new java.io.ByteArrayOutputStream();
            doc.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to create test DOCX", e);
        }
    }
}
