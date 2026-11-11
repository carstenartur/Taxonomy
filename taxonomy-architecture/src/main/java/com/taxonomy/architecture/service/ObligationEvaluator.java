package com.taxonomy.architecture.service;

import com.taxonomy.dto.*;
import java.util.*;

/** Pure evaluation over already-scoped evidence. Scores are deliberately absent from this contract. */
public final class ObligationEvaluator {
    public record Relation(String sourceId, String targetId, String type, String status) {}
    public List<ObligationJudgment> evaluate(ObligationProfile profile, Map<String, String> elementRoots,
            List<Relation> relations, EvidenceCompleteness completeness) {
        Objects.requireNonNull(profile); Objects.requireNonNull(completeness);
        List<ObligationJudgment> result = new ArrayList<>();
        for (var source : new TreeMap<>(elementRoots).entrySet()) for (var rule : profile.rules()) {
            if (!rule.sourceRoots().contains(source.getValue())) continue;
            int count = (int) relations.stream()
                    .filter(r -> source.getKey().equals(r.sourceId()) && rule.relationType().equals(r.type()))
                    .filter(r -> "accepted".equals(r.status()))
                    .filter(r -> elementRoots.containsKey(r.targetId()) && rule.targetRoots().contains(elementRoots.get(r.targetId())))
                    .map(Relation::targetId).distinct().count();
            ObligationJudgment.Status status;
            if (completeness == EvidenceCompleteness.INCOMPLETE) status = ObligationJudgment.Status.INCONCLUSIVE;
            else if (count < rule.minCount() || rule.maxCount() != null && count > rule.maxCount()) status = ObligationJudgment.Status.VIOLATED;
            else status = ObligationJudgment.Status.SATISFIED;
            String description = status == ObligationJudgment.Status.INCONCLUSIVE
                    ? "Evidence is incomplete for this profile and revision; absence is not established."
                    : "Observed " + count + " distinct accepted target elements across alternatives " + new TreeSet<>(rule.targetRoots())
                      + "; required cardinality " + rule.minCount() + ".." + (rule.maxCount() == null ? "*" : rule.maxCount()) + ".";
            result.add(new ObligationJudgment(rule.id(), source.getKey(), source.getValue(), rule.relationType(), rule.targetRoots(),
                    rule.minCount(), rule.maxCount(), count, rule.severity(), status, description));
        }
        return List.copyOf(result);
    }
}
