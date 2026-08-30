package com.taxonomy.analysis.service;

import com.taxonomy.analysis.dto.AiTargetDtos.AiTargetDescriptor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/** Validates the size of analysis input before an AI request is created. */
@Component
public class AiPromptBudgetPolicy {

    private static final int ESTIMATED_CHARACTERS_PER_TOKEN = 4;

    private final AiTargetCatalogService targetCatalog;

    public AiPromptBudgetPolicy(AiTargetCatalogService targetCatalog) {
        this.targetCatalog = targetCatalog;
    }

    public AiTargetDescriptor requireWithinBudget(String text, String provider) {
        return requireWithinBudget(text, targetCatalog.describeProvider(provider));
    }

    public AiTargetDescriptor requireWithinBudget(String text, AiTargetDescriptor target) {
        String value = text == null ? "" : text;
        int characters = value.codePointCount(0, value.length());
        int bytes = value.getBytes(StandardCharsets.UTF_8).length;
        int tokens = Math.max(1, (characters + ESTIMATED_CHARACTERS_PER_TOKEN - 1)
                / ESTIMATED_CHARACTERS_PER_TOKEN);
        if (characters > target.promptBudget().maxInputCharacters()
                || bytes > target.promptBudget().maxInputBytes()
                || tokens > target.promptBudget().estimatedMaxInputTokens()) {
            throw new PromptBudgetExceededException(target, characters, bytes, tokens);
        }
        return target;
    }
}
