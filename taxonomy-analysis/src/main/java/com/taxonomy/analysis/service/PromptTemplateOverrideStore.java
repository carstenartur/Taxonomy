package com.taxonomy.analysis.service;

import java.util.Map;

/** Runtime overrides for the application-wide prompt catalogue. */
public interface PromptTemplateOverrideStore {
    Map<String, String> snapshot();

    void save(String code, String template);

    void reset(String code);

    final class UnavailableException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public UnavailableException(Throwable cause) {
            super("Prompt template storage is unavailable", cause);
        }
    }
}
