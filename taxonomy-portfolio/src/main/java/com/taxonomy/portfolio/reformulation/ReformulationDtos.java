package com.taxonomy.portfolio.reformulation;
import com.taxonomy.reformulation.*;
import java.time.Instant;
import java.util.List;
public final class ReformulationDtos {
    private ReformulationDtos(){}
    public record CreateRequest(Long sourceVersionId,String snapshotId,String language) {}
    public record SaveDraftRequest(String text,String rationale) {}
    public record Revision(long number,Long predecessor,String text,List<Section> sections,List<Statement> statements,
            List<DecisionQuestion> questions,List<DecisionAnswer> answers,ValidationReport validation,
            String actor,Instant createdAt,String rationale) {
        public Revision { sections=List.copyOf(sections);statements=List.copyOf(statements);questions=List.copyOf(questions);answers=List.copyOf(answers); }
    }
    public record Proposal(String id,ReformulationBaseline baseline,String creator,Instant createdAt,String status,Revision currentRevision) {}
}
