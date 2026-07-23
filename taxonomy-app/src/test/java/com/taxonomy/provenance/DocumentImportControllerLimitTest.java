package com.taxonomy.provenance;

import jakarta.servlet.MultipartConfigElement;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "taxonomy.limits.document.max-upload-bytes=1024",
        "taxonomy.limits.document.max-llm-characters=128"
})
@AutoConfigureMockMvc
@WithMockUser(roles = "ARCHITECT")
class DocumentImportControllerLimitTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private MultipartConfigElement multipartConfig;

    @Test
    void servletLimitUsesTheSameConfiguredFileLimit() {
        assertThat(multipartConfig.getMaxFileSize()).isEqualTo(1024);
        assertThat(multipartConfig.getMaxRequestSize()).isEqualTo(1024 + 1024 * 1024);
    }

    @Test
    void oversizedUploadReturnsStable413Response() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "large.pdf", MediaType.APPLICATION_PDF_VALUE, new byte[1025]);

        mockMvc.perform(multipart("/api/documents/upload").file(file))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error").value("UPLOAD_TOO_LARGE"));
    }

    @Test
    void emptyUploadReturnsStable400Response() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "empty.pdf", MediaType.APPLICATION_PDF_VALUE, new byte[0]);

        mockMvc.perform(multipart("/api/documents/upload").file(file))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("EMPTY_FILE"));
    }
}