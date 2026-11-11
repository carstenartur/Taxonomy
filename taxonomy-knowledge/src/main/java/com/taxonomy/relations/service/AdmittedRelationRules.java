package com.taxonomy.relations.service;

import com.taxonomy.dto.AnalysisModelBinding;
import com.taxonomy.model.RelationType;
import java.util.Set;

/** Additional restrictions from the accepted architecture brief, scoped to one execution thread. */
public final class AdmittedRelationRules implements AutoCloseable {
    private static final ThreadLocal<AdmittedRelationRules> CURRENT = new ThreadLocal<>();
    private final Set<String> excluded;
    private final AdmittedRelationRules previous;
    private boolean closed;

    private AdmittedRelationRules(Set<String> excluded) {
        this.excluded = excluded;
        previous = CURRENT.get();
        CURRENT.set(this);
    }

    public static AdmittedRelationRules open(AnalysisModelBinding binding) {
        return new AdmittedRelationRules(binding == null ? Set.of() : binding.excludedRelationTypes());
    }

    static boolean excludes(RelationType type) {
        var policy = CURRENT.get();
        return policy != null && type != null && policy.excluded.contains(type.name());
    }

    @Override public void close() {
        if (closed) return;
        if (CURRENT.get() != this) throw new IllegalStateException("Analysis profile scopes must close in order on their owner thread");
        if (previous == null) CURRENT.remove();
        else CURRENT.set(previous);
        closed = true;
    }
}
