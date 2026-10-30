package com.taxonomy.portfolio.inventory;

import com.taxonomy.extension.api.inventory.InventoryComparisonContracts.*;
import com.taxonomy.extension.api.inventory.InventoryReadContracts.AssertionKind;
import java.util.*;

/** Exact directed edge evidence; absent edges and expired positive claims remain unknown. */
public final class InventoryRelationshipEvaluator {
    public RelationshipFinding evaluate(RelationshipRequirement requirement, Question question, List<Assertion> assertions) {
        var normalized=new ArrayList<Assertion>();
        var ids=new ArrayList<String>();var kinds=EnumSet.noneOf(AssertionKind.class);var limits=new TreeSet<String>();
        for(var a:assertions) {
            var edge=a.relationship();
            if(a.criterion()!=Criterion.RELATIONSHIPS || edge==null || !requirement.sourceCandidateId().equals(a.candidateId()) ||
                    !requirement.targetCandidateId().equals(edge.targetCandidateId()) || requirement.kind()!=edge.kind() || !Objects.equals(requirement.qualifier(),edge.qualifier()) ||
                    !question.organization().equals(a.organization()) || (question.environment()!=null && !question.environment().equals(a.environment())))continue;
            ids.add(a.id());kinds.add(a.kind());if(a.kind()==AssertionKind.PLANNED)limits.add("PLANNED_NOT_ACTUAL");
            boolean covers=a.start()!=null && a.endExclusive()!=null && !a.start().latest().isAfter(question.start()) && !a.endExclusive().earliest().isBefore(question.end());
            boolean supports=requirement.kind()==RelationshipKind.HARD_EXCLUSION?!a.positive():a.positive();
            if(requirement.qualifier()==null) {limits.add("MISSING_CONTRACT_QUALIFICATION");continue;}
            // An expired/partial positive edge is not evidence of a negative edge.
            // Explicit negative intervals retain the existing scalar start-window union/conflict rules.
            if(!supports || covers)normalized.add(new Assertion(a.id(),a.candidateId(),Criterion.AVAILABILITY,null,supports,a.start(),a.endExclusive(),a.organization(),a.environment(),a.kind(),a.citation(),a.recordedAt()));
            else limits.add(a.start()==null || a.endExclusive()==null?"MISSING_BOUNDARY":"PARTIAL_EXPIRED_OR_IMPRECISE_EDGE");
        }
        var evaluated=new InventoryCriterionEvaluator().evaluate(requirement.sourceCandidateId(),Criterion.AVAILABILITY,null,question,normalized,true);
        var outcome=evaluated.outcome();limits.addAll(evaluated.limitations());
        if(ids.isEmpty())limits.add("NO_ADMISSIBLE_ASSERTION");
        var finding=new Finding(requirement.sourceCandidateId(),Criterion.RELATIONSHIPS,null,outcome,ids,kinds,limits,
                outcome==Outcome.SUPPORTED?"Review cited exact directed edge and planned/actual status":"Obtain dated evidence for these exact endpoints, direction, kind and qualifier",evaluated.hardExclusion()&&(requirement.required()||requirement.kind()==RelationshipKind.HARD_EXCLUSION));
        return new RelationshipFinding(requirement.id(),requirement.sourceCandidateId(),requirement.targetCandidateId(),requirement.kind(),requirement.qualifier(),requirement.required(),finding);
    }
}
