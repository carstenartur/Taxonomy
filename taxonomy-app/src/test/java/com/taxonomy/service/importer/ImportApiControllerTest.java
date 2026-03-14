package com.taxonomy.service.importer;

import com.taxonomy.dto.FrameworkImportResult;
import com.taxonomy.dto.ProfileInfo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests for the {@link com.taxonomy.controller.ImportApiController}.
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
@WithMockUser(roles = "ADMIN")
class ImportApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void listProfilesReturnsJson() throws Exception {
        mockMvc.perform(get("/api/import/profiles"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].profileId").exists())
                .andExpect(jsonPath("$[0].displayName").exists())
                .andExpect(jsonPath("$[0].acceptedFileFormat").exists());
    }

    @Test
    void listProfilesContainsUaf() throws Exception {
        mockMvc.perform(get("/api/import/profiles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.profileId == 'uaf')]").exists());
    }

    @Test
    void listProfilesContainsApqc() throws Exception {
        mockMvc.perform(get("/api/import/profiles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.profileId == 'apqc')]").exists());
    }

    @Test
    void listProfilesContainsC4() throws Exception {
        mockMvc.perform(get("/api/import/profiles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.profileId == 'c4')]").exists());
    }

    @Test
    void previewWithCsvReturnsResult() throws Exception {
        String csv = "PCF ID,Name,Level,Description\n1.0,Strategy,1,Test\n";
        MockMultipartFile file = new MockMultipartFile("file", "apqc.csv",
                "text/csv", csv.getBytes());

        mockMvc.perform(multipart("/api/import/preview/apqc").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileId").value("apqc"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.elementsTotal").value(1));
    }

    @Test
    void previewWithXmlReturnsResult() throws Exception {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <xmi:XMI xmlns:xmi="http://www.omg.org/spec/XMI/20131001"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                  <packagedElement xmi:id="e1" xsi:type="uaf:Capability" name="TestCap"/>
                </xmi:XMI>
                """;
        MockMultipartFile file = new MockMultipartFile("file", "uaf.xml",
                "application/xml", xml.getBytes());

        mockMvc.perform(multipart("/api/import/preview/uaf").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileId").value("uaf"))
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void previewEmptyFileReturnsBadRequest() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "empty.csv",
                "text/csv", new byte[0]);

        mockMvc.perform(multipart("/api/import/preview/apqc").file(file))
                .andExpect(status().isBadRequest());
    }

    @Test
    void importWithCsvReturnsResult() throws Exception {
        String csv = "PCF ID,Name,Level,Description\n1.0,Strategy,1,Test\n";
        MockMultipartFile file = new MockMultipartFile("file", "apqc.csv",
                "text/csv", csv.getBytes());

        mockMvc.perform(multipart("/api/import/apqc").file(file)
                        .param("branch", "test-branch"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileId").value("apqc"));
    }
}
