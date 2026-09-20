package com.taxonomy.portfolio.reformulation;
import com.taxonomy.reformulation.*;
import java.time.Instant;
import java.util.List;
public final class ReformulationDtos {
    private ReformulationDtos(){}
    public record CreateRequest(Long sourceVersionId,String snapshotId,String language) {}
    public record SaveDraftRequest(String text,String rationale,AnswerRequest answer,String statementId,StatementRequest statement) {
        public SaveDraftRequest(String text,String rationale) {this(text,rationale,null,null,null);}
    }
    public record AnswerRequest(String questionId,String action,List<String> values,String otherText,String rationale) {}
    public record StatementRequest(String action,String text,String rationale) {}
    public record VariantRequest(String rationale) {}
    public record VariantOrigin(String proposalId,long revision) {}
    public record Revision(long number,Long predecessor,String text,List<Section> sections,List<Statement> statements,
            List<DecisionQuestion> questions,List<DecisionAnswer> answers,ValidationReport validation,
            String actor,Instant createdAt,String rationale,ReformulationImpact impact,VariantOrigin variantOrigin) {
        public Revision(long number,Long predecessor,String text,List<Section> sections,List<Statement> statements,
                List<DecisionQuestion> questions,List<DecisionAnswer> answers,ValidationReport validation,
                String actor,Instant createdAt,String rationale) {
            this(number,predecessor,text,sections,statements,questions,answers,validation,actor,createdAt,rationale,ReformulationImpact.empty(),null);
        }
        public Revision { impact=impact==null?ReformulationImpact.empty():impact; sections=List.copyOf(sections);statements=List.copyOf(statements);questions=List.copyOf(questions);answers=List.copyOf(answers); }
    }
    public record Run(String id,String proposalId,long sourceRevision,String status,String provider,String model,
            String promptVersion,String schemaVersion,String promptContent,String failureCode,Long resultRevision,ReformulationDocument candidate,
            String actor,Instant createdAt,java.util.Map<String,String> reconcileContext) {
        public Run {reconcileContext=reconcileContext==null?java.util.Map.of():java.util.Map.copyOf(reconcileContext);}
    }
    public record Proposal(String id,ReformulationBaseline baseline,String creator,Instant createdAt,String status,Revision currentRevision) {}
}
