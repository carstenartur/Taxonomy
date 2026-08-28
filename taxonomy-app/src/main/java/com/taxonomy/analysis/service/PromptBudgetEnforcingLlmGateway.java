package com.taxonomy.analysis.service;

import java.util.Objects;

/**
 * Enforces the selected AI target's input budget on the complete provider prompt.
 *
 * <p>The earlier use-case preflight can reject obviously oversized requirement text,
 * but only this boundary sees the final prompt after templates, node lists and other
 * context have been added. The provider is never called when that final prompt exceeds
 * the target budget.</p>
 */
final class PromptBudgetEnforcingLlmGateway implements LlmGateway {

    private final LlmGateway delegate;
    private final AiPromptBudgetPolicy promptBudgetPolicy;

    PromptBudgetEnforcingLlmGateway(
            LlmGateway delegate,
            AiPromptBudgetPolicy promptBudgetPolicy) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.promptBudgetPolicy = Objects.requireNonNull(
                promptBudgetPolicy, "promptBudgetPolicy");
    }

    @Override
    public String sendHttpRequest(String prompt, String apiKey) {
        promptBudgetPolicy.requireWithinBudget(prompt, delegate.providerName());
        return delegate.sendHttpRequest(prompt, apiKey);
    }

    @Override
    public String extractResponseText(String rawResponseBody) {
        return delegate.extractResponseText(rawResponseBody);
    }

    @Override
    public String providerName() {
        return delegate.providerName();
    }

    LlmGateway delegate() {
        return delegate;
    }
}
