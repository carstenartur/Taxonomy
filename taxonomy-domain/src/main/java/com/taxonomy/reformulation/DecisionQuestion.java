package com.taxonomy.reformulation;
import java.util.*;
/** Semantic identity, discovery evidence and original aliases survive reconciliation. */
public record DecisionQuestion(String id, Key key, String wording, List<Discovery> discoveries,
        List<String> affectedStatementIds, AnswerSchema answerSchema, List<String> prerequisites,
        List<String> dependentQuestionIds, String consequences, State state, List<String> aliases,
        List<Origin> origins,List<SourceResolution> sourceResolutions) {
    public enum State { OPEN, ANSWERED, DEFERRED, NOT_APPLICABLE, CONFLICT }
    public record Key(String subject,String dimension,String scope) {}
    public record Discovery(String location,String context,String rationale,List<Statement.SourceSpan> sourceSpans,
            List<String> nodeIds,List<String> edgeIds) {
        public Discovery { sourceSpans=List.copyOf(sourceSpans); nodeIds=List.copyOf(nodeIds); edgeIds=List.copyOf(edgeIds); }
    }
    public record AnswerSchema(Kind kind,List<String> options,String unit,Double minimum,Double maximum,
            Map<String,OptionMeaning> optionMeanings,List<List<String>> incompatibleOptions,List<AnswerCondition> applicability) {
        public enum Kind { SINGLE_CHOICE, MULTIPLE_CHOICE, TEXT, NUMBER, BOOLEAN }
        public enum OptionMeaning { VALUE, OTHER, OPEN, NOT_NEEDED }
        public record AnswerCondition(String questionId,List<String> anyOf) {
            public AnswerCondition {anyOf=List.copyOf(anyOf);}
        }
        public AnswerSchema(Kind kind,List<String> options,String unit,Double minimum,Double maximum) {
            this(kind,options,unit,minimum,maximum,Map.of(),List.of(),List.of());
        }
        public AnswerSchema {
            options=List.copyOf(options); Objects.requireNonNull(kind);
            optionMeanings=optionMeanings==null?Map.of():Map.copyOf(optionMeanings);
            incompatibleOptions=incompatibleOptions==null?List.of():incompatibleOptions.stream().map(List::copyOf).toList();
            applicability=applicability==null?List.of():List.copyOf(applicability);
        }
    }
    /** Exact pre-merge identity and meaning, including all local references. Never a new vote. */
    public record Origin(String id,Key key,String wording,List<Discovery> discoveries,List<String> affectedStatementIds,
            AnswerSchema answerSchema,List<String> prerequisites,List<String> dependentQuestionIds,String consequences,State state) {
        public Origin {discoveries=List.copyOf(discoveries);affectedStatementIds=List.copyOf(affectedStatementIds);
            prerequisites=List.copyOf(prerequisites);dependentQuestionIds=List.copyOf(dependentQuestionIds);}
    }
    /** Source evidence is distinct from a human DecisionAnswer. Semantic entailment remains reviewable. */
    public record SourceResolution(List<String> values,List<Statement.SourceSpan> sourceSpans,String rationale) {
        public SourceResolution {values=List.copyOf(values);sourceSpans=List.copyOf(sourceSpans);}
    }
    public DecisionQuestion(String id,Key key,String wording,List<Discovery> discoveries,List<String> affectedStatementIds,
            AnswerSchema answerSchema,List<String> prerequisites,List<String> dependentQuestionIds,String consequences,State state) {
        this(id,key,wording,discoveries,affectedStatementIds,answerSchema,prerequisites,dependentQuestionIds,consequences,state,List.of(),List.of(),List.of());
    }
    public DecisionQuestion { discoveries=List.copyOf(discoveries); affectedStatementIds=List.copyOf(affectedStatementIds);
        prerequisites=List.copyOf(prerequisites); dependentQuestionIds=List.copyOf(dependentQuestionIds);
        aliases=aliases==null?List.of():List.copyOf(aliases);origins=origins==null?List.of():List.copyOf(origins);
        sourceResolutions=sourceResolutions==null?List.of():List.copyOf(sourceResolutions); }
    public Origin origin() {return new Origin(id,key,wording,discoveries,affectedStatementIds,answerSchema,prerequisites,dependentQuestionIds,consequences,state);}
    public Set<String> referenceIds() {var ids=new LinkedHashSet<>(aliases);ids.add(id);return Collections.unmodifiableSet(ids);}
    /** Conservative publication proof: compatible identity and all previous source/discovery/meaning evidence retained. */
    public boolean retains(DecisionQuestion prior) {
        return referenceIds().containsAll(prior.referenceIds()) && key.equals(prior.key()) && compatible(answerSchema,prior.answerSchema())
            && discoveries.containsAll(prior.discoveries()) && affectedStatementIds.containsAll(prior.affectedStatementIds())
            && prerequisites.containsAll(prior.prerequisites()) && dependentQuestionIds.containsAll(prior.dependentQuestionIds())
            && sourceResolutions.containsAll(prior.sourceResolutions()) && origins.containsAll(prior.origins())
            && (origin().equals(prior.origin()) || origins.contains(prior.origin()));
    }
    public static boolean compatible(AnswerSchema a,AnswerSchema b) {
        return a.kind()==b.kind() && new HashSet<>(a.options()).equals(new HashSet<>(b.options())) && Objects.equals(a.unit(),b.unit())
            && Objects.equals(a.minimum(),b.minimum()) && Objects.equals(a.maximum(),b.maximum())
            && a.optionMeanings().equals(b.optionMeanings()) && a.incompatibleOptions().equals(b.incompatibleOptions()) && a.applicability().equals(b.applicability());
    }
}
