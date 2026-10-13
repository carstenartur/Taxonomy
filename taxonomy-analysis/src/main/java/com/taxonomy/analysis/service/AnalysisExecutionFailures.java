package com.taxonomy.analysis.service;

import jakarta.persistence.PersistenceException;
import org.springframework.dao.DataAccessException;
import org.springframework.web.server.ResponseStatusException;
import java.util.Collections;
import java.util.IdentityHashMap;

/** Failures of execution authority or durable storage are not negative model assessments. */
public final class AnalysisExecutionFailures {
    private AnalysisExecutionFailures() { }
    public static void rethrow(Throwable failure) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        for (Throwable cause = failure; cause != null && seen.size() < 32 && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof DataAccessException || cause instanceof PersistenceException || cause instanceof SecurityException
                    || cause instanceof ResponseStatusException || cause instanceof ProviderPluginUnavailableException
                    || cause instanceof LlmTransportMeter.JournalStartException
                    || cause instanceof ProviderRateQuotas.UnavailableException
                    || cause instanceof org.springframework.security.access.AccessDeniedException
                    || cause instanceof org.springframework.security.core.AuthenticationException) {
                if (failure instanceof RuntimeException runtime) throw runtime;
                throw new IllegalStateException("Analysis execution cannot continue", failure);
            }
        }
    }
}
