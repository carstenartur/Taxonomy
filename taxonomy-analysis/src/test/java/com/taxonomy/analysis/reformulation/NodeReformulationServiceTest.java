package com.taxonomy.analysis.reformulation;

import com.taxonomy.analysis.service.*;
import com.taxonomy.reformulation.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class NodeReformulationServiceTest {
    @Test void everyCallAndSingleRepairContainFullOriginalAndAllChildDetails() {
        var prompts=new ArrayList<String>();
        var registry=mock(LlmGatewayRegistry.class); var config=mock(LlmProviderConfig.class);
        when(config.getActiveProvider()).thenReturn(LlmProvider.OPENAI);when(config.isProviderConfigured(LlmProvider.OPENAI)).thenReturn(true);when(config.getApiKey(LlmProvider.OPENAI)).thenReturn("test");
        var gateway=new LlmGateway() {
            public String providerName(){return "test";} public String extractResponseText(String raw){return raw;}
            public String sendHttpRequest(String prompt){return null;}
            public String sendHttpRequest(String prompt,String key){prompts.add(prompt);return prompts.size()==1?"{":ReformulationResponseParserTest.EMPTY;}
        };
        when(registry.getGateway(LlmProvider.OPENAI)).thenReturn(gateway);
        var service=new NodeReformulationService(registry,config,new ObjectMapper());
        var result=service.synthesize(ReformulationResponseParserTest.input());
        assertThat(result.summary()).isEqualTo("Zusammenfassung");
        assertThat(prompts).hasSize(2).allSatisfy(p->assertThat(p).contains(WalkUpReformulationTest.baseline().originalText()));
        assertThat(prompts.getLast()).contains("VALIDATION_ERRORS");
    }
    @Test void configuredKeylessCustomProviderUsesExistingReadinessContract() {
        var registry=mock(LlmGatewayRegistry.class);var config=mock(LlmProviderConfig.class);var gateway=mock(LlmGateway.class);
        when(config.getActiveProvider()).thenReturn(LlmProvider.CUSTOM_OPENAI);
        when(config.isProviderConfigured(LlmProvider.CUSTOM_OPENAI)).thenReturn(true);
        when(registry.getGateway(LlmProvider.CUSTOM_OPENAI)).thenReturn(gateway);
        when(gateway.sendHttpRequest(anyString(),isNull())).thenReturn("raw");
        when(gateway.extractResponseText("raw")).thenReturn(ReformulationResponseParserTest.EMPTY);
        assertThat(new NodeReformulationService(registry,config,new ObjectMapper()).synthesize(ReformulationResponseParserTest.input()).summary()).isEqualTo("Zusammenfassung");
    }
    @Test void rejectsProviderTruncationEvenIfReturnedTextHappensToBeValidJson() {
        var registry=mock(LlmGatewayRegistry.class);var config=mock(LlmProviderConfig.class);var gateway=mock(LlmGateway.class);
        when(config.getActiveProvider()).thenReturn(LlmProvider.OPENAI);when(config.isProviderConfigured(LlmProvider.OPENAI)).thenReturn(true);
        when(config.getApiKey(LlmProvider.OPENAI)).thenReturn("test");when(registry.getGateway(LlmProvider.OPENAI)).thenReturn(gateway);
        String raw="{\"choices\":[{\"finish_reason\":\"length\"}]}";
        when(gateway.sendHttpRequest(anyString(),anyString())).thenReturn(raw);when(gateway.extractResponseText(raw)).thenReturn(ReformulationResponseParserTest.EMPTY);
        assertThatThrownBy(()->new NodeReformulationService(registry,config,new ObjectMapper()).synthesize(ReformulationResponseParserTest.input())).hasMessageContaining("INVALID_MODEL_RESPONSE");
        verify(gateway,times(2)).sendHttpRequest(anyString(),anyString());
    }
    @Test void malformedResponseStopsAfterOneRepairAndNoProviderFailsHonestly() {
        var registry=mock(LlmGatewayRegistry.class); var config=mock(LlmProviderConfig.class);
        when(config.getActiveProvider()).thenReturn(LlmProvider.OPENAI);when(config.isProviderConfigured(LlmProvider.OPENAI)).thenReturn(true);when(config.getApiKey(LlmProvider.OPENAI)).thenReturn("test");
        var gateway=mock(LlmGateway.class);when(registry.getGateway(LlmProvider.OPENAI)).thenReturn(gateway);
        when(gateway.sendHttpRequest(anyString(),anyString())).thenReturn("raw");when(gateway.extractResponseText("raw")).thenReturn("{");
        var service=new NodeReformulationService(registry,config,new ObjectMapper());
        assertThatThrownBy(()->service.synthesize(ReformulationResponseParserTest.input())).hasMessageContaining("INVALID_MODEL_RESPONSE");
        verify(gateway,times(2)).sendHttpRequest(anyString(),anyString());
        when(config.isProviderConfigured(LlmProvider.OPENAI)).thenReturn(false);
        assertThatThrownBy(()->service.synthesize(ReformulationResponseParserTest.input())).hasMessageContaining("PROVIDER_NOT_CONFIGURED");
    }
}
