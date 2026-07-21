package com.taxonomy.analysis.usecase;

/** Input for evaluating the direct children of one taxonomy node. */
public record AnalyzeNodeChildrenCommand(
        String parentCode,
        String businessText,
        int parentScore) {

    public AnalyzeNodeChildrenCommand {
        if (parentCode == null || parentCode.isBlank()) {
            throw new IllegalArgumentException("parentCode must not be blank");
        }
        if (businessText == null || businessText.isBlank()) {
            throw new IllegalArgumentException("businessText must not be blank");
        }
        if (parentScore < 0 || parentScore > 100) {
            throw new IllegalArgumentException("parentScore must be between 0 and 100");
        }
    }
}
