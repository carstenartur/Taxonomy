package com.taxonomy.portfolio.inventory;

import com.taxonomy.extension.api.inventory.InventoryComparisonContracts.*;
import com.taxonomy.extension.api.inventory.InventoryReadContracts.AssertionKind;
import java.util.*;
import java.time.LocalDate;

/** Deterministic conservative interval evaluator. Missing boundaries are never infinity. */
public final class InventoryCriterionEvaluator {
    public static final String VERSION = "inventory-criteria/2";
    public Finding evaluate(String candidate, Criterion criterion, String node, Question question, List<Assertion> assertions, boolean examined) {
        if (!examined) return new Finding(candidate,criterion,node,Outcome.UNEXAMINED,List.of(),Set.of(),Set.of("NOT_EXAMINED"),"Examine the required relationship evidence",false);
        boolean positive=false,negative=false;
        var ids=new ArrayList<String>(); var kinds=EnumSet.noneOf(AssertionKind.class); var limits=new TreeSet<String>();var excludedStarts=new ArrayList<Interval>();
        for(var assertion:assertions) {
            if(!candidate.equals(assertion.candidateId()) || assertion.criterion()!=criterion || !Objects.equals(node,assertion.nodeCode())) continue;
            boolean semanticMapping=criterion==Criterion.CAPABILITY && assertion.organization()==null && assertion.environment()==null && assertion.start()==null && assertion.endExclusive()==null;
            if(!semanticMapping && (assertion.organization()==null || !assertion.organization().equals(question.organization()) ||
                    (question.environment()!=null && !question.environment().equals(assertion.environment())))) continue;
            ids.add(assertion.id()); kinds.add(assertion.kind());
            if(assertion.kind()==AssertionKind.PLANNED) limits.add("PLANNED_NOT_ACTUAL");
            if(semanticMapping) {
                if(assertion.positive())positive=true;else negative=true;
                limits.add("SEMANTIC_MAPPING_NOT_AVAILABILITY");continue;
            }
            var start=assertion.start(); var end=assertion.endExclusive();
            if(!assertion.positive() && start!=null && end!=null && end.earliest().isAfter(start.latest()))excludedStarts.add(new Interval(start.latest(),end.earliest()));
            boolean covers=start!=null && end!=null && !start.latest().isAfter(question.start()) && !end.earliest().isBefore(question.end());
            boolean excluded=(end!=null && !end.latest().isAfter(question.start())) || (start!=null && !start.earliest().isBefore(question.end()));
            if(covers) { if(assertion.positive()) positive=true; else negative=true; }
            else if(assertion.positive() && (excluded || (question.mode()==TimeMode.CONTINUOUS_PERIOD &&
                    ((end!=null && end.latest().isBefore(question.end())) || (start!=null && start.earliest().isAfter(question.start())))))) negative=true;
            else if(!assertion.positive() && question.mode()==TimeMode.CONTINUOUS_PERIOD && start!=null && end!=null && start.latest().isBefore(question.end()) && end.earliest().isAfter(question.start())) negative=true;
            else limits.add(start==null || end==null ? "MISSING_BOUNDARY" : "PARTIAL_OR_IMPRECISE_COVERAGE");
        }
        if(question.mode()==TimeMode.START_WINDOW && excludesAllStarts(question,excludedStarts)) {negative=true;limits.add("ALL_STARTS_EXPLICITLY_EXCLUDED");}
        if(positive && question.mode()==TimeMode.START_WINDOW && excludedStarts.stream().anyMatch(i->i.start().isBefore(question.end()) && i.end().isAfter(question.start()))) {
            negative=true;limits.add("OVERLAPPING_POSITIVE_AND_EXPLICIT_EXCLUSION");
        }
        var outcome=positive && negative?Outcome.CONFLICT:negative?Outcome.CONTRADICTED:positive?Outcome.SUPPORTED:Outcome.UNKNOWN;
        if(ids.isEmpty()) limits.add("NO_ADMISSIBLE_ASSERTION");
        return new Finding(candidate,criterion,node,outcome,ids,kinds,limits,
                outcome==Outcome.SUPPORTED?"Review the cited assertion and its planned/actual status":"Obtain explicit dated evidence for this criterion, organization and environment",negative);
    }
    private record Interval(LocalDate start,LocalDate end) { }
    private boolean excludesAllStarts(Question question,List<Interval> intervals) {
        intervals.sort(Comparator.comparing(Interval::start));LocalDate cursor=question.start();
        for(var interval:intervals) {
            if(interval.start().isAfter(cursor))return false;
            if(interval.end().isAfter(cursor))cursor=interval.end();
            if(!cursor.isBefore(question.end()))return true;
        }
        return false;
    }

}
