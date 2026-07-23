package com.taxonomy.provenance;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.ai.gemini.api-key=",
        "spring.ai.openai.api-key=",
        "spring.ai.deepseek.api-key=",
        "spring.ai.qwen.api-key=",
        "spring.ai.llama.api-key=",
        "spring.ai.mistral.api-key=",
        "taxonomy.limits.document.max-upload-bytes=10240"
})
@WithMockUser(roles = "ARCHITECT")
class DocumentImportControllerTest {

    private static final byte[] OVERSIZED_PAYLOAD = new byte[10 * 1024 + 1];

    @Autowired
    private MockMvc mockMvc;

    @Test
    void uploadEmptyFileReturnsBadRequest() throws Exception {
        MockMultipartFile emptyFile = new MockMultipartFile(
                "file", "empty.pdf", "application/pdf", new byte[0]);

        mockMvc.perform(multipart("/api/documents/upload").file(emptyFile))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("EMPTY_FILE"));
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
        MockMultipartFile docxFile = new MockMultipartFile(
                "file", "test-regulation.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                createMinimalDocx());

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
        MockMultipartFile docxFile = new MockMultipartFile(
                "file", "test.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                createMinimalDocx());

        mockMvc.perform(multipart("/api/documents/upload")
                        .file(docxFile)
                        .param("sourceType", "INVALID_TYPE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceArtifactId").isNumber());
    }

    @Test
    void extractWithAiEmptyFileReturnsBadRequest() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "empty.pdf", "application/pdf", new byte[0]);

        mockMvc.perform(multipart("/api/documents/extract-ai").file(file))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("EMPTY_FILE"));
    }

    @Test
    void extractAiOversizedFileReturnsPayloadTooLarge() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "large.pdf", "application/pdf", OVERSIZED_PAYLOAD);

        mockMvc.perform(multipart("/api/documents/extract-ai").file(file))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error").value("UPLOAD_TOO_LARGE"));
    }

    @Test
    void mapRegulationEmptyFileReturnsBadRequest() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "empty.pdf", "application/pdf", new byte[0]);

        mockMvc.perform(multipart("/api/documents/map-regulation").file(file))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("EMPTY_FILE"));
    }

    @Test
    void mapRegulationOversizedFileReturnsPayloadTooLarge() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "large.pdf", "application/pdf", OVERSIZED_PAYLOAD);

        mockMvc.perform(multipart("/api/documents/map-regulation").file(file))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error").value("UPLOAD_TOO_LARGE"));
    }

    @Test
    void confirmCandidatesNoCandidatesReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/documents/confirm-candidates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sourceArtifactId": 1,
                                  "sourceVersionId": 1,
                                  "candidates": []
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("NO_CANDIDATES"));
    }

    @Test
    void confirmCandidatesMissingArtifactReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/documents/confirm-candidates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sourceArtifactId": 999999,
                                  "sourceVersionId": 999999,
                                  "candidates": [{"text": "some text", "sectionHeading": "section"}]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("SOURCE_NOT_FOUND"));
    }

    @Test
    void uploadOversizedFileReturnsPayloadTooLarge() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "large.pdf", "application/pdf", OVERSIZED_PAYLOAD);

        mockMvc.perform(multipart("/api/documents/upload").file(file))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error").value("UPLOAD_TOO_LARGE"));
    }

    private byte[] createMinimalDocx() {
        try (var document = new org.apache.poi.xwpf.usermodel.XWPFDocument()) {
            document.createParagraph().createRun().setText("§ 1 Test Requirement");
            document.createParagraph().createRun().setText(
                    "The authority must ensure that all applications are processed "
                            + "within 30 days. This deadline applies to all administrative procedures.");
            var output = new java.io.ByteArrayOutputStream();
            document.write(output);
            return output.toByteArray();
        } catch (Exception error) {
            throw new RuntimeException("Failed to create test DOCX", error);
        }
    }
}