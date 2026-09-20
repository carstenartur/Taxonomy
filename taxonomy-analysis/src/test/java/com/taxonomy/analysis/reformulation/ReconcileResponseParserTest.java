package com.taxonomy.analysis.reformulation;
import com.taxonomy.reformulation.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class ReconcileResponseParserTest {
    final ObjectMapper json=new ObjectMapper();
    final ReconcileResponseParser parser=new ReconcileResponseParser(json);
    ReconciliationInput input() {
        var fixture=new CrossTaxonomyReconciliationTest();var d=fixture.draft(List.of(fixture.question("q-p","BP","global")));
        return new ReconciliationInput(fixture.baseline(),1,d.sections(),d.statements(),d.questions(),List.of(),Map.of(),Map.of(),List.of());
    }
    @Test void rejectsForgedSourceResolutionsUnknownIdsAndUnsupportedAnswerValues() {
        var in=input();String original=in.baseline().originalText();
        var valid=Map.of("affectedSectionIds",List.of(),"findings",List.of(),"sourceResolutions",List.of(Map.of("questionId","q-p","values",List.of("Terminal"),"rationale","Explicit original","sourceSpans",List.of(new Statement.SourceSpan(0,original.length(),original)))));
        String response=json.writeValueAsString(valid);
        assertThat(parser.parse(response,in).sourceResolutions()).containsKey("q-p");
        for(String invalid:List.of(response.replace("q-p","unknown"),response.replace("\"values\":[\"Terminal\"]","\"values\":[\"Invented\"]"),response.replace("\"start\":0","\"start\":4294967296"),response.replace(original,"Forged original")))
            assertThatThrownBy(()->parser.parse(invalid,in)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void modelCannotCertifyStructuralCoverageOrRequestUnexplainedRewording() {
        var in=input();
        assertThatThrownBy(()->parser.parse("{\"affectedSectionIds\":[\"BP\"],\"sourceResolutions\":[],\"findings\":[]}",in)).isInstanceOf(IllegalArgumentException.class);
        String response=json.writeValueAsString(Map.of("affectedSectionIds",List.of(),"sourceResolutions",List.of(),"findings",List.of(Map.of("kind","STRUCTURAL_LOSS","code","forged","message","Model structural judgment","statementIds",List.of(),"sourceSpans",List.of()))));
        assertThatThrownBy(()->parser.parse(response,in)).isInstanceOf(IllegalArgumentException.class);
    }
}
