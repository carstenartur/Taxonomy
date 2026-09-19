package com.taxonomy.analysis.service;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PromptBudgetEnforcingLlmGatewayTest {

    @Test
    void checksTheCompletePromptBeforeCallingTheProvider() {
        LlmGateway delegate = mock(LlmGateway.class);
        AiPromptBudgetPolicy policy = mock(AiPromptBudgetPolicy.class);
        when(delegate.providerName()).thenReturn("GEMINI");
        when(delegate.sendHttpRequest("complete prompt", "secret")).thenReturn("raw response");
        PromptBudgetEnforcingLlmGateway gateway =
                new PromptBudgetEnforcingLlmGateway(delegate, policy);

        assertThat(gateway.sendHttpRequest("complete prompt", "secret"))
                .isEqualTo("raw response");

        InOrder ordered = inOrder(policy, delegate);
        ordered.verify(policy).requireWithinBudget("complete prompt", "GEMINI");
        ordered.verify(delegate).sendHttpRequest("complete prompt", "secret");
    }

    @Test
    void oversizedCompletePromptNeverReachesTheProvider() {
        LlmGateway delegate = mock(LlmGateway.class);
        AiPromptBudgetPolicy policy = mock(AiPromptBudgetPolicy.class);
        RuntimeException failure = new RuntimeException("prompt budget exceeded");
        when(delegate.providerName()).thenReturn("OPENAI");
        when(policy.requireWithinBudget("oversized prompt", "OPENAI"))
                .thenThrow(failure);
        PromptBudgetEnforcingLlmGateway gateway =
                new PromptBudgetEnforcingLlmGateway(delegate, policy);

        assertThatThrownBy(() -> gateway.sendHttpRequest("oversized prompt", "secret"))
                .isSameAs(failure);

        verify(delegate, never()).sendHttpRequest("oversized prompt", "secret");
    }

    @Test
    void responseExtractionAndIdentityRemainProviderOwned() {
        LlmGateway delegate = mock(LlmGateway.class);
        AiPromptBudgetPolicy policy = mock(AiPromptBudgetPolicy.class);
        when(delegate.providerName()).thenReturn("MISTRAL");
        when(delegate.extractResponseText("raw")).thenReturn("answer");
        PromptBudgetEnforcingLlmGateway gateway =
                new PromptBudgetEnforcingLlmGateway(delegate, policy);

        assertThat(gateway.providerName()).isEqualTo("MISTRAL");
        assertThat(gateway.extractResponseText("raw")).isEqualTo("answer");
        assertThat(gateway.delegate()).isSameAs(delegate);
    }
}
