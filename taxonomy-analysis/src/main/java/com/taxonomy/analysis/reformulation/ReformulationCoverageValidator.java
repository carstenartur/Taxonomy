package com.taxonomy.analysis.reformulation;
import com.taxonomy.reformulation.*;
import java.util.*;
/** Mechanical preservation findings are never a semantic approval. */
public class ReformulationCoverageValidator {
    public ValidationReport validate(ReformulationBaseline baseline,ReformulationDocument original,ReformulationDocument candidate) {
        var findings=new ArrayList<ValidationReport.Finding>();var ids=new HashMap<String,Statement>();candidate.statements().forEach(s->ids.put(s.id(),s));
        for(var prior:original.statements()) if(!prior.equals(ids.get(prior.id()))) findings.add(new ValidationReport.Finding(ValidationReport.Kind.STRUCTURAL_LOSS,"STATEMENT_CHANGED_OR_LOST","Previously frozen detail must remain visible",List.of(),prior.sourceSpans()));
        var covered=new BitSet(baseline.originalText().length());
        for(var s:candidate.statements()) {
            for(var span:s.sourceSpans()) {
                if(!span.matches(baseline.originalText())) findings.add(new ValidationReport.Finding(ValidationReport.Kind.STRUCTURAL_LOSS,"INVALID_SOURCE_SPAN","Source evidence does not match the frozen original",List.of(s.id()),List.of()));
                else if(s.provenance()==Statement.Provenance.ORIGINAL && s.wording().contains(span.exactText()) && candidate.text().contains(span.exactText()))covered.set(span.start(),span.end());
            }
            if(s.provenance()!=Statement.Provenance.ORIGINAL && s.provenance()!=Statement.Provenance.HUMAN_DECISION)
                findings.add(new ValidationReport.Finding(ValidationReport.Kind.SEMANTIC_REVIEW,"UNCONFIRMED_ADDITION","Unconfirmed architecture/model wording; repeated origins provide no independent confirmation",List.of(s.id()),List.of()));
        }
        for(int start=covered.nextClearBit(0);start<baseline.originalText().length();) {
            int end=Math.min(covered.nextSetBit(start)<0?baseline.originalText().length():covered.nextSetBit(start),baseline.originalText().length());
            findings.add(new ValidationReport.Finding(ValidationReport.Kind.STRUCTURAL_LOSS,"ORIGINAL_NOT_VISIBLE","Original conditions, exceptions, numbers and negations must stay visible",List.of(),List.of(new Statement.SourceSpan(start,end,baseline.originalText().substring(start,end)))));
            start=covered.nextClearBit(end);
        }
        findings.add(new ValidationReport.Finding(ValidationReport.Kind.SEMANTIC_REVIEW,"SEMANTIC_REVIEW_REQUIRED","Structural preservation is not semantic confirmation or expert approval",List.of(),List.of()));
        return new ValidationReport(findings);
    }
}
