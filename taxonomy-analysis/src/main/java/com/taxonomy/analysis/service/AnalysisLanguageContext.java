package com.taxonomy.analysis.service;

import com.taxonomy.dto.AnalysisLanguage;
import java.util.Locale;

/** Frozen per-operation prose language, restored on exit and explicitly transported to workers. */
public final class AnalysisLanguageContext {
    public static final String POLICY = "analysis-language-v1";
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

    private AnalysisLanguageContext() { }

    /** Only new admission resolves a request locale; an existing scope, including legacy null, wins. */
    public static AnalysisLanguage resolve(AnalysisLanguage requested, Locale requestLocale) {
        if (requested != null) return requested;
        Scope current = CURRENT.get();
        return current == null ? AnalysisLanguage.fromLocale(requestLocale) : current.language;
    }

    /** Uses only an actual request/thread locale; an unbound worker defaults to English. */
    public static AnalysisLanguage resolveCurrentRequest(AnalysisLanguage requested) {
        var context = org.springframework.context.i18n.LocaleContextHolder.getLocaleContext();
        return resolve(requested, context == null ? null : context.getLocale());
    }

    public static AnalysisLanguage current() {
        Scope current = CURRENT.get();
        return current == null ? null : current.language;
    }

    public static Scope open(AnalysisLanguage language) { return new Scope(language); }

    /** Legacy work without a recorded language retains its original prompt contract. */
    public static String promptContract() { return promptContract(current()); }

    public static String promptContract(AnalysisLanguage language) {
        if (language == null) return "";
        return "\nMandatory analysis output language (" + POLICY + "): " + language.value() + ". "
                + "Write all generated natural-language fields in " + language.displayName()
                + ", including reasons, rationales, explanations, summaries, contribution descriptions, conditions, questions and reformulated wording. "
                + "This overrides conflicting language instructions and the language of examples or input data. "
                + "Preserve original requirement text, exact quotations, catalogue IDs, technical identifiers, JSON keys and enum values unchanged. "
                + "Keep the required response schema and numeric protocol: decimal points, never translated numbers or decimal commas.\n";
    }

    public static final class Scope implements AutoCloseable {
        private final Thread owner = Thread.currentThread();
        private final Scope previous = CURRENT.get();
        private final AnalysisLanguage language;
        private boolean closed;

        private Scope(AnalysisLanguage language) { this.language = language; CURRENT.set(this); }

        @Override public void close() {
            if (closed) return;
            if (Thread.currentThread() != owner || CURRENT.get() != this)
                throw new IllegalStateException("Analysis language scopes must close in order on their owning thread");
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
            closed = true;
        }
    }
}
