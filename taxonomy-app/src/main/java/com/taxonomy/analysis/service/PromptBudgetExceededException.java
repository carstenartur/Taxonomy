package com.taxonomy.analysis.service;

import com.taxonomy.analysis.dto.AiTargetDtos.AiTargetDescriptor;

/** Raised before provider dispatch when the selected AI target cannot accept the input. */
public class PromptBudgetExceededException extends IllegalArgumentException {

    public static final String CODE = "PROMPT_BUDGET_EXCEEDED";

    private final String targetId;
    private final int inputCharacters;
    private final int inputBytes;
    private final int estimatedInputTokens;
    private final int maxInputCharacters;
    private final int maxInputBytes;
    private final int estimatedMaxInputTokens;

    public PromptBudgetExceededException(
            AiTargetDescriptor target,
            int inputCharacters,
            int inputBytes,
            int estimatedInputTokens) {
        super(message(target, inputCharacters, inputBytes, estimatedInputTokens));
        this.targetId = target.targetId();
        this.inputCharacters = inputCharacters;
        this.inputBytes = inputBytes;
        this.estimatedInputTokens = estimatedInputTokens;
        this.maxInputCharacters = target.promptBudget().maxInputCharacters();
        this.maxInputBytes = target.promptBudget().maxInputBytes();
        this.estimatedMaxInputTokens = target.promptBudget().estimatedMaxInputTokens();
    }

    public String getCode() {
        return CODE;
    }

    public String getTargetId() {
        return targetId;
    }

    public int getInputCharacters() {
        return inputCharacters;
    }

    public int getInputBytes() {
        return inputBytes;
    }

    public int getEstimatedInputTokens() {
        return estimatedInputTokens;
    }

    public int getMaxInputCharacters() {
        return maxInputCharacters;
    }

    public int getMaxInputBytes() {
        return maxInputBytes;
    }

    public int getEstimatedMaxInputTokens() {
        return estimatedMaxInputTokens;
    }

    private static String message(
            AiTargetDescriptor target,
            int characters,
            int bytes,
            int tokens) {
        return CODE + ": AI target " + target.targetId()
                + " cannot accept the requirement within its configured prompt budget "
                + "(characters " + characters + "/" + target.promptBudget().maxInputCharacters()
                + ", UTF-8 bytes " + bytes + "/" + target.promptBudget().maxInputBytes()
                + ", estimated input tokens " + tokens + "/"
                + target.promptBudget().estimatedMaxInputTokens() + "). "
                + "Reduce or split the requirement, select a target with a larger budget, "
                + "or resume from preserved partial evidence.";
    }
}
