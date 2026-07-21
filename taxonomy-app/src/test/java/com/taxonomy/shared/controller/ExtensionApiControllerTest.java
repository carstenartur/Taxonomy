package com.taxonomy.shared.controller;

import com.taxonomy.shared.extension.ExtensionDescriptor;
import com.taxonomy.shared.extension.ExtensionKind;
import com.taxonomy.shared.extension.runtime.ExtensionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ExtensionApiControllerTest {

    @Mock
    private ExtensionRegistry extensionRegistry;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                new ExtensionApiController(extensionRegistry)).build();
    }

    @Test
    void listAllReturnsDescriptorMetadataOnly() throws Exception {
        when(extensionRegistry.listAll()).thenReturn(List.of(
                new ExtensionDescriptor("mermaid", "Mermaid",
                        "Exports as Mermaid flowcharts", ExtensionKind.EXPORT_FORMAT),
                new ExtensionDescriptor("gemini", "Gemini",
                        "Google Gemini LLM", ExtensionKind.LLM_PROVIDER)));

        mockMvc.perform(get("/api/extensions").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value("mermaid"))
                .andExpect(jsonPath("$[0].kind").value("EXPORT_FORMAT"))
                .andExpect(jsonPath("$[1].id").value("gemini"))
                .andExpect(jsonPath("$[1].kind").value("LLM_PROVIDER"));
    }

    @Test
    void listAllReturnsEmptyListWhenNoExtensionsRegistered() throws Exception {
        when(extensionRegistry.listAll()).thenReturn(List.of());
        mockMvc.perform(get("/api/extensions").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void listByKindIsCaseInsensitiveAndRejectsUnknownKind() throws Exception {
        when(extensionRegistry.listByKind(ExtensionKind.LLM_PROVIDER)).thenReturn(List.of(
                new ExtensionDescriptor("gemini", "Gemini",
                        "Google Gemini LLM", ExtensionKind.LLM_PROVIDER)));
        mockMvc.perform(get("/api/extensions/llm_provider").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("gemini"));
        mockMvc.perform(get("/api/extensions/UNKNOWN_KIND").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    @Test
    void listByKindReturnsEmptyListForKindWithoutExtensions() throws Exception {
        when(extensionRegistry.listByKind(ExtensionKind.IMPORT_PROFILE)).thenReturn(List.of());
        mockMvc.perform(get("/api/extensions/IMPORT_PROFILE").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
