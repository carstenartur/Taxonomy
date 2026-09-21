package com.taxonomy.analysis.reformulation;
import com.taxonomy.reformulation.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class ReformulationCoverageValidatorTest {
    @Test void missingNegationAndSmallNumericConditionAreStructuralLossNotSemanticCertification() {
        var baseline=WalkUpReformulationTest.baseline();
        var statement=new Statement("addition","Use a browser",List.of(),Statement.Provenance.MODEL_ADDITION,List.of(),List.of(),null,Statement.EditingOrigin.MODEL,"UNREVIEWED");
        var candidate=new ReformulationDocument(statement.wording(),List.of(),List.of(statement),List.of(),new ValidationReport(List.of()),List.of());
        var report=new ReformulationCoverageValidator().validate(baseline,candidate,candidate);
        assertThat(report.findings()).anySatisfy(f->{assertThat(f.kind()).isEqualTo(ValidationReport.Kind.STRUCTURAL_LOSS);assertThat(f.sourceSpans().getFirst().exactText()).contains("keine Browseroberfläche","2 Sekunden");});
        assertThat(report.findings()).anySatisfy(f->{assertThat(f.kind()).isEqualTo(ValidationReport.Kind.SEMANTIC_REVIEW);assertThat(f.code()).isEqualTo("UNCONFIRMED_ADDITION");});
    }
    @Test void verbatimRetentionDoesNotCertifyModelAdditionsOrForgedProvenance() {
        var b=WalkUpReformulationTest.baseline();var span=new Statement.SourceSpan(0,b.originalText().length(),b.originalText());
        var source=new Statement("source",b.originalText(),List.of(span),Statement.Provenance.ORIGINAL,List.of(),List.of(),null,Statement.EditingOrigin.SOURCE,"UNREVIEWED");
        var addition=new Statement("addition","Mandatory retention 10 years",List.of(),Statement.Provenance.MODEL_ADDITION,List.of(),List.of(),null,Statement.EditingOrigin.MODEL,"UNREVIEWED");
        var before=new ReformulationDocument(b.originalText(),List.of(),List.of(source),List.of(),new ValidationReport(List.of()),List.of());
        var candidate=new ReformulationDocument(b.originalText()+"\n"+addition.wording(),List.of(),List.of(source,addition),List.of(),new ValidationReport(List.of()),List.of());
        var report=new ReformulationCoverageValidator().validate(b,before,candidate);
        assertThat(report.findings()).noneSatisfy(f->assertThat(f.kind()).isEqualTo(ValidationReport.Kind.STRUCTURAL_LOSS));
        assertThat(report.findings()).anySatisfy(f->{assertThat(f.code()).isEqualTo("UNCONFIRMED_ADDITION");assertThat(f.statementIds()).containsExactly("addition");});
    }
}
