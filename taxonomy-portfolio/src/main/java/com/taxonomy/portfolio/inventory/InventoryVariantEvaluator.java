package com.taxonomy.portfolio.inventory;
import com.taxonomy.extension.api.inventory.InventoryComparisonContracts.*;
import com.taxonomy.extension.api.inventory.InventoryReadContracts.AssertionKind;
import com.taxonomy.portfolio.dto.PortfolioDtos.ProjectSolutionView;
import com.taxonomy.portfolio.model.PortfolioTypes.ReviewStatus;
import java.util.*;
/** Existing solution evidence is retained alongside additional candidate evidence; membership never changes. */
public final class InventoryVariantEvaluator {
    public VariantAssessment evaluate(ProjectSolutionView solution,String fingerprint,String frozen,VariantInput input,Demand demand,List<Finding> candidateFindings,List<RelationshipFinding> relationships) {
        var findings=new ArrayList<Finding>();var hard=new ArrayList<String>();var open=new ArrayList<String>();
        String identity="solution:"+solution.solution().id();
        for(var node:demand.selectedNodes()) {
            var coverage=solution.solution().taxonomyCoverage().stream().filter(c->c.nodeCode().equals(node)&&c.reviewStatus()==ReviewStatus.CONFIRMED).findFirst();
            Outcome outcome=coverage.isEmpty()?Outcome.UNKNOWN:coverage.get().coveragePercent()==0?Outcome.CONTRADICTED:Outcome.SUPPORTED;
            findings.add(new Finding(identity,Criterion.CAPABILITY,node,outcome,coverage.map(c->List.of("solution-coverage:"+c.id())).orElse(List.of()),coverage.isPresent()?Set.of(AssertionKind.DECLARED):Set.of(),Set.of("SEMANTIC_MAPPING_NOT_AVAILABILITY"),"Review retained solution coverage",outcome==Outcome.CONTRADICTED));
        }
        findings.add(new Finding(identity,Criterion.AVAILABILITY,null,Outcome.UNKNOWN,List.of(),Set.of(),Set.of("NO_DATED_SOLUTION_AVAILABILITY"),"Obtain organizational availability evidence for the existing solution",false));
        findings.addAll(candidateFindings.stream().filter(f->input.candidateIds().contains(f.candidateId())).toList());
        for(var relation:relationships)if(input.candidateIds().contains(relation.sourceCandidateId())) {
            findings.add(relation.finding());
            if(relation.finding().hardExclusion())hard.add("Relationship "+relation.requirementId()+": "+relation.finding().outcome());
        }
        for(var finding:findings) {
            if(finding.hardExclusion() || (finding.criterion()!=Criterion.RELATIONSHIPS && (finding.outcome()==Outcome.CONTRADICTED || finding.outcome()==Outcome.CONFLICT)))hard.add(finding.candidateId()+" "+finding.criterion()+" "+Objects.toString(finding.nodeCode(),"")+": "+finding.outcome());
            if(finding.outcome()==Outcome.UNKNOWN || finding.outcome()==Outcome.UNEXAMINED)open.add(finding.candidateId()+" "+finding.criterion()+": "+finding.nextClarification());
        }
        if(solution.inventoryHardExclusions()!=null && !solution.inventoryHardExclusions().isBlank())hard.add("Retained solution exclusion: "+solution.inventoryHardExclusions());
        for(var product:solution.productCandidates())if(product.hardExclusions()!=null && !product.hardExclusions().isBlank())hard.add("Existing product "+product.product().id()+": "+product.hardExclusions());
        for(var note:input.prerequisites())note("Prerequisite",note,hard,open);
        for(var note:input.risks())note("Risk",note,hard,open);
        return new VariantAssessment(solution.id(),solution.solution().id(),solution.solution().solutionKey(),solution.solution().title(),fingerprint,frozen,input,findings,hard,open);
    }
    private void note(String kind,AssessmentNote note,List<String> hard,List<String> open) {
        String reason=kind+": "+note.text()+" ["+note.citation()+"]";
        if(note.status()==AssessmentStatus.BLOCKING)hard.add(reason);else if(note.status()==AssessmentStatus.OPEN)open.add(reason);
    }
}
