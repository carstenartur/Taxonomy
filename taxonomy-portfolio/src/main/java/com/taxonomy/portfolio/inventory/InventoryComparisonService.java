package com.taxonomy.portfolio.inventory;

import com.taxonomy.extension.api.inventory.InventoryComparisonContracts.*;
import com.taxonomy.extension.api.inventory.InventoryEvidencePort;
import com.taxonomy.extension.api.inventory.InventoryEvidencePort.Scope;
import com.taxonomy.portfolio.dto.PortfolioDtos.*;
import com.taxonomy.portfolio.model.PortfolioTypes.*;
import com.taxonomy.portfolio.service.*;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;

/** Frozen saved-demand comparisons and explicit reviewed portfolio application. No analysis rescoring. */
@org.springframework.boot.autoconfigure.condition.ConditionalOnBean(com.taxonomy.extension.api.inventory.InventoryEvidencePort.class)
@Service
public class InventoryComparisonService {
    private final ProductCatalogService products;private final PortfolioAnalysisPersistenceService snapshots;
    private final ProjectPortfolioService projects;private final SolutionPortfolioService solutions;private final InventoryEvidencePort evidence;private final PortfolioJsonCodec codec;
    private final InventoryCriterionEvaluator evaluator=new InventoryCriterionEvaluator();private final CuratedInventoryProjection projection=new CuratedInventoryProjection();
    public InventoryComparisonService(ProductCatalogService products,PortfolioAnalysisPersistenceService snapshots,ProjectPortfolioService projects,SolutionPortfolioService solutions,InventoryEvidencePort evidence,PortfolioJsonCodec codec) {
        this.products=products;this.snapshots=snapshots;this.projects=projects;this.solutions=solutions;this.evidence=evidence;this.codec=codec;
    }
    public record CreateComparisonRequest(List<String> selectedNodes,Question question,List<String> sourceProfiles,List<RelationshipRequirement> relationships,List<VariantInput> variants) {
        public CreateComparisonRequest(List<String> selectedNodes,Question question,List<String> sourceProfiles) {this(selectedNodes,question,sourceProfiles,List.of(),List.of());}
        public CreateComparisonRequest { selectedNodes=List.copyOf(selectedNodes);sourceProfiles=sourceProfiles==null?List.of():List.copyOf(sourceProfiles);Objects.requireNonNull(question);relationships=relationships==null?List.of():List.copyOf(relationships);variants=variants==null?List.of():List.copyOf(variants);if(relationships.size()>100 || variants.size()>20)throw new IllegalArgumentException("Too many comparison inputs"); }
    }
    public record MapCandidateRequest(UUID id,Long productId,String comparisonFingerprint,String rationale) { }
    public Comparison compare(Long project,String snapshot,CreateComparisonRequest request,String actor,WorkspaceContext context) {
        var detail=snapshots.getSnapshot(project,snapshot,actor,context);var summary=detail.summary();var scope=scope(actor,context);
        var frozenNodes=new TreeMap<String,String>();
        if(detail.analysis()!=null && detail.analysis().getTree()!=null)freezeRecordedNodes(detail.analysis().getTree(),detail.analysis().getRawScores(),frozenNodes);
        detail.elementMappings().forEach(m->frozenNodes.put(m.nodeCode(),codec.write(m)));
        Set<String> recorded=frozenNodes.keySet();
        if(request.selectedNodes().isEmpty() || !recorded.containsAll(request.selectedNodes()) || new HashSet<>(request.selectedNodes()).size()!=request.selectedNodes().size())throw PortfolioException.validation("Select distinct nodes recorded in this exact analysis snapshot");
        frozenNodes.keySet().retainAll(request.selectedNodes());
        String requirementText=projects.listRequirementVersions(project,summary.requirementId(),actor,context).stream().filter(v->v.id().equals(summary.requirementVersionId())).map(RequirementVersionView::text).findFirst().orElseThrow(()->PortfolioException.notFound("Exact saved requirement version is unavailable"));
        var demand=new Demand(scope.repositoryId(),scope.scopeKey(),scope.branch(),project,summary.requirementId(),summary.requirementVersionId(),snapshot,summary.taxonomyFingerprint(),evidence.fingerprint(codec.write(detail)),request.selectedNodes(),request.question(),requirementText,frozenNodes,projects.getRequirement(project,summary.requirementId(),actor,context).currentVersionId());
        var candidates=new ArrayList<Candidate>();
        var catalogue=products.listProducts(actor,context);
        if(catalogue.size()>100)throw PortfolioException.validation("Catalogue candidate limit exceeded; narrow the sourced catalogue before comparing");
        for(var product:catalogue) candidates.add(curated(product,scope,request.question()));
        var remote=evidence.readSources(scope,demand,request.sourceProfiles());
        for(var external:remote.candidates()) {
            var mappings=evidence.mappings(scope,external.id());
            var externalMetadata=new TreeMap<>(external.metadata());externalMetadata.put("mappingStateFingerprint",evidence.fingerprint(mappings));
            external=new Candidate(external.id(),external.productId(),external.name(),external.kind(),external.fingerprint(),externalMetadata,external.assertions());
            if(mappings.isEmpty()) {candidates.add(external);continue;}
            var mapping=mappings.stream().max(Comparator.comparing(Mapping::mappedAt)).orElseThrow();var product=products.getProduct(mapping.productId(),actor,context);
            if(!mapping.productFingerprint().equals(evidence.fingerprint(product))) {
                var stale=new TreeMap<>(external.metadata());stale.put("mappingStale","Mapped product changed; review identity again");stale.put("previousMappingId",mapping.id().toString());
                candidates.add(new Candidate(external.id(),null,external.name(),external.kind(),external.fingerprint(),stale,external.assertions()));continue;
            }
            var metadata=new TreeMap<>(external.metadata());metadata.put("mappingId",mapping.id().toString());metadata.put("mappedProductFingerprint",mapping.productFingerprint());metadata.put("mappingRationale",mapping.rationale());metadata.put("curatedAssertionsFingerprint",evidence.fingerprint(evidence.assertions(scope,"product:"+product.id())));
            var assertions=new ArrayList<>(external.assertions());
            for(var a:projection.project(product,mapping.productFingerprint(),evidence.assertions(scope,"product:"+product.id()),request.question()).assertions())
                assertions.add(new Assertion(a.id(),external.id(),a.criterion(),a.nodeCode(),a.positive(),a.start(),a.endExclusive(),a.organization(),a.environment(),a.kind(),a.citation(),a.recordedAt(),a.relationship()));
            candidates.add(new Candidate(external.id(),product.id(),external.name(),external.kind(),evidence.fingerprint(List.of(external.fingerprint(),mapping)),metadata,assertions));
        }
        if(candidates.size()>100)throw PortfolioException.validation("Comparison candidate limit exceeded");
        // Only reviewed identity mappings permit cross-source corroboration/conflict.
        // Keep each source object row and every original assertion ID visible.
        var joined=new ArrayList<Candidate>();
        for(var candidate:candidates) {
            var assertions=new ArrayList<>(candidate.assertions());
            if(candidate.productId()!=null) for(var other:candidates) if(!other.id().equals(candidate.id()) && candidate.productId().equals(other.productId()))
                for(var a:other.assertions()) assertions.add(new Assertion(a.id(),candidate.id(),a.criterion(),a.nodeCode(),a.positive(),a.start(),a.endExclusive(),a.organization(),a.environment(),a.kind(),a.citation(),a.recordedAt(),a.relationship()));
            joined.add(new Candidate(candidate.id(),candidate.productId(),candidate.name(),candidate.kind(),candidate.fingerprint(),candidate.metadata(),assertions.stream().distinct().toList()));
        }
        candidates=joined;
        var findings=new ArrayList<Finding>();
        for(var candidate:candidates) {
            for(var node:demand.selectedNodes())findings.add(evaluator.evaluate(candidate.id(),Criterion.CAPABILITY,node,demand.question(),candidate.assertions(),true));
            for(var criterion:List.of(Criterion.AVAILABILITY,Criterion.SUPPORT,Criterion.ORGANIZATION,Criterion.ENVIRONMENT,Criterion.RELATIONSHIPS))
                findings.add(evaluator.evaluate(candidate.id(),criterion,null,demand.question(),candidate.assertions(),criterion!=Criterion.RELATIONSHIPS));
        }
        var candidateIds=candidates.stream().map(Candidate::id).collect(java.util.stream.Collectors.toSet());
        var relationshipIds=new HashSet<String>();var relationshipFindings=new ArrayList<RelationshipFinding>();
        var relationshipEvaluator=new InventoryRelationshipEvaluator();
        for(var requirement:request.relationships()) {
            if(!relationshipIds.add(requirement.id()) || !candidateIds.contains(requirement.sourceCandidateId()) || !candidateIds.contains(requirement.targetCandidateId()))throw PortfolioException.validation("Relationship must have a distinct ID and exact retained endpoints");
            var sourceCandidate=candidates.stream().filter(c->c.id().equals(requirement.sourceCandidateId())).findFirst().orElseThrow();
            relationshipFindings.add(relationshipEvaluator.evaluate(requirement,request.question(),sourceCandidate.assertions()));
        }
        var variants=new ArrayList<VariantAssessment>();var variantIds=new HashSet<Long>();
        var existing=request.variants().isEmpty()?List.<ProjectSolutionView>of():solutions.listProjectSolutions(project,actor,context);
        for(var input:request.variants()) {
            if(!variantIds.add(input.projectSolutionId()) || !candidateIds.containsAll(input.candidateIds()))throw PortfolioException.validation("Variant must name a distinct existing project solution and retained evidence candidates");
            var solution=existing.stream().filter(v->v.id().equals(input.projectSolutionId())).findFirst().orElseThrow(()->PortfolioException.validation("Variant is not an existing solution of this project"));
            variants.add(new InventoryVariantEvaluator().evaluate(solution,evidence.fingerprint(solution),codec.write(solution),input,demand,findings,relationshipFindings));
        }
        UUID id=UUID.randomUUID();Instant now=Instant.now();String fingerprint=evidence.fingerprint(List.of(id,demand,InventoryCriterionEvaluator.VERSION,now,candidates,findings,remote.sources(),request.relationships(),relationshipFindings,variants));
        var result=new Comparison(id,demand,InventoryCriterionEvaluator.VERSION,now,candidates,findings,remote.sources(),fingerprint,request.relationships(),relationshipFindings,variants);evidence.save(scope,result);return result;
    }

    public List<Comparison> list(Long project,String snapshot,String actor,WorkspaceContext context) {
        snapshots.getSnapshot(project,snapshot,actor,context);
        return evidence.list(scope(actor,context),project,snapshot).stream().filter(c->{try{evidence.authorize(scope(actor,context),c.demand(),c.sources());return true;}catch(InventoryEvidencePort.Failure denied){if(denied.status()==403||denied.status()==404)return false;throw denied;}}).toList();
    }
    public Comparison read(UUID id,String actor,WorkspaceContext context) {
        var comparison=evidence.read(scope(actor,context),id);projects.requireProject(comparison.demand().projectId(),actor,context);evidence.authorize(scope(actor,context),comparison.demand(),comparison.sources());return comparison;
    }
    @org.springframework.transaction.annotation.Transactional
    public Assertion appendAssertion(Long productId,Assertion assertion,String actor,WorkspaceContext context) {
        products.requireProductForUpdate(productId,actor,context);
        if(!assertion.candidateId().equals("product:"+productId))throw PortfolioException.validation("Assertion must name this existing product");
        if(assertion.recordedAt().isAfter(Instant.now()))throw PortfolioException.validation("Assertion recording date cannot be in the future");
        evidence.appendAssertion(scope(actor,context),assertion);return assertion;
    }
    public Mapping map(UUID comparisonId,String candidateId,MapCandidateRequest request,String actor,WorkspaceContext context) {
        var comparison=read(comparisonId,actor,context);
        if(!comparison.fingerprint().equals(request.comparisonFingerprint()))throw PortfolioException.conflict("Comparison changed");
        var candidate=candidate(comparison,candidateId);
        if(!candidate.metadata().containsKey("sourceProfile"))throw PortfolioException.validation("Only an explicit external source identity may be mapped");
        revalidateDemand(comparison,actor,context);
        var product=products.getProduct(request.productId(),actor,context);
        var source=comparison.sources().stream().filter(s->s.profileId().equals(candidate.metadata().get("sourceProfile"))).findFirst().orElseThrow(()->PortfolioException.validation("External candidate has no retained source identity"));
        var previous=evidence.mappings(scope(actor,context),candidate.id()).stream().filter(m->m.id().equals(request.id())).findFirst();
        if(previous.isPresent()) {
            var prior=previous.get();
            if(!prior.productId().equals(product.id()) || !prior.productFingerprint().equals(evidence.fingerprint(product)) || !prior.rationale().equals(request.rationale()) || !prior.actor().equals(actor))throw PortfolioException.conflict("Mapping ID was reused with a different payload");return prior;
        }
        var mapping=new Mapping(request.id(),candidate.id(),product.id(),evidence.fingerprint(product),source.profileId(),source.revision(),candidate.fingerprint(),request.rationale(),actor,Instant.now());
        evidence.map(scope(actor,context),mapping);return mapping;
    }
    public Reviewed review(UUID id,Review review,String actor,WorkspaceContext context) {
        var comparison=read(id,actor,context);
        if(review.variantDecision()!=null)return reviewVariant(comparison,review,actor,context);
        var candidate=candidate(comparison,review.candidateId());
        var findings=comparison.findings().stream().filter(f->f.candidateId().equals(candidate.id())).toList();
        String hard=findings.stream().filter(f->f.hardExclusion() || f.outcome()==Outcome.CONTRADICTED || f.outcome()==Outcome.CONFLICT).map(f->f.criterion()+":"+Objects.toString(f.nodeCode(),"")+":"+f.outcome()).collect(java.util.stream.Collectors.joining("; "));
        var directedExclusions=comparison.relationshipFindings().stream().filter(f->f.sourceCandidateId().equals(candidate.id()) && f.finding().hardExclusion()).map(f->f.requirementId()+":"+f.finding().outcome()).toList();
        if(!directedExclusions.isEmpty())hard=hard+"; "+String.join("; ",directedExclusions);
        final String retainedHard=hard.length()<=4000?hard:"Hard exclusions retained in inventory comparison "+comparison.id()+" / "+comparison.fingerprint()+"; candidate "+candidate.id();
        if(review.selectProduct() && !hard.isEmpty())throw PortfolioException.validation("A hard contradiction or conflict prevents product selection");
        if(review.selectProduct() && candidate.productId()==null)throw PortfolioException.validation("Explicitly map the external candidate to an existing catalogue product before selection");
        return evidence.review(scope(actor,context),id,review,()->{
            // Existing identical reviews replay in the journal without applying again.
            if(!InventoryCriterionEvaluator.VERSION.equals(comparison.evaluatorVersion()))throw PortfolioException.conflict("Inventory evaluation rules changed; compare again");
            // The journal owns the transaction. Every mutable input writer observes
            // these aggregate locks; validation and the portfolio write share them.
            projects.requireProjectForUpdate(comparison.demand().projectId(),actor,context);
            if(candidate.productId()!=null)products.requireProductForUpdate(candidate.productId(),actor,context);
            revalidateDemand(comparison,actor,context);
            if(candidate.productId()!=null) {
                var product=products.getProduct(candidate.productId(),actor,context);
                String expected=candidate.metadata().getOrDefault("mappedProductFingerprint",candidate.metadata().getOrDefault("catalogueFingerprint",candidate.fingerprint()));
                if(!expected.equals(evidence.fingerprint(product)))throw PortfolioException.conflict("Catalogue product or confirmed mapping changed; compare again");
                String assertionFingerprint=candidate.metadata().get("curatedAssertionsFingerprint");
                if(assertionFingerprint!=null && !assertionFingerprint.equals(evidence.fingerprint(evidence.assertions(scope(actor,context),"product:"+product.id()))))throw PortfolioException.conflict("Curated inventory assertions changed; compare again");
            }
            if(review.projectSolutionId()!=null) {
                if(review.coveragePercent()==null)throw PortfolioException.validation("Portfolio application requires an explicit reviewed coverage percentage");
                if(candidate.productId()==null)throw PortfolioException.validation("Map the external object before portfolio application");
                String reference=reviewReference(comparison,review);
                products.applyReviewedInventoryCandidate(comparison.demand().projectId(),review.projectSolutionId(),new UpsertProductCandidateRequest(candidate.productId(),review.coveragePercent(),retainedHard.isEmpty()?null:retainedHard,null,null,reference,0.0,ReviewStatus.CONFIRMED,review.selectProduct()?ProductSelectionStatus.SELECTED:retainedHard.isEmpty()?ProductSelectionStatus.SHORTLISTED:ProductSelectionStatus.REJECTED),comparison.id().toString(),comparison.fingerprint(),retainedHard,actor,context);
                solutions.linkRequirement(comparison.demand().projectId(),review.projectSolutionId(),new LinkRequirementSolutionRequest(comparison.demand().requirementId(),comparison.demand().snapshotId(),review.coveragePercent(),RequirementSolutionRole.USES,ReviewStatus.CONFIRMED,reference),actor,context);
            }
            return null;
        });
    }
    private Reviewed reviewVariant(Comparison comparison,Review review,String actor,WorkspaceContext context) {
        var decision=review.variantDecision();
        var variant=comparison.variants().stream().filter(v->v.projectSolutionId().equals(decision.projectSolutionId())).findFirst().orElseThrow(()->PortfolioException.validation("Variant is not part of this retained comparison"));
        if(review.coveragePercent()==null)throw PortfolioException.validation("Variant application requires explicit reviewed coverage");
        if(decision.outcome()==VariantOutcome.SELECTED && !variant.hardExclusions().isEmpty())throw PortfolioException.validation("Hard exclusions prevent selecting this solution variant");
        if(decision.outcome()==VariantOutcome.SELECTED && !variant.openQuestions().isEmpty() && !decision.acknowledgeUnknowns())throw PortfolioException.validation("Acknowledge unresolved evidence before selection");
        return evidence.review(scope(actor,context),comparison.id(),review,()->{
            if(!InventoryCriterionEvaluator.VERSION.equals(comparison.evaluatorVersion()))throw PortfolioException.conflict("Inventory evaluation rules changed; compare again");
            projects.requireProjectForUpdate(comparison.demand().projectId(),actor,context);
            solutions.requireSolutionForUpdate(variant.solutionId(),actor,context);
            var current=solutions.listProjectSolutions(comparison.demand().projectId(),actor,context).stream().filter(v->v.id().equals(variant.projectSolutionId())).findFirst().orElseThrow(()->PortfolioException.conflict("Project solution changed; compare again"));
            var productIds=new TreeSet<Long>();current.productCandidates().forEach(p->productIds.add(p.product().id()));
            for(var candidateId:variant.input().candidateIds()) {var c=candidate(comparison,candidateId);if(c.productId()!=null)productIds.add(c.productId());}
            productIds.forEach(p->products.requireProductForUpdate(p,actor,context));
            revalidateDemand(comparison,actor,context);
            current=solutions.listProjectSolutions(comparison.demand().projectId(),actor,context).stream().filter(v->v.id().equals(variant.projectSolutionId())).findFirst().orElseThrow(()->PortfolioException.conflict("Project solution changed; compare again"));
            if(!variant.fingerprint().equals(evidence.fingerprint(current)))throw PortfolioException.conflict("Solution, coverage, membership or requirement links changed; compare again");
            for(var candidateId:variant.input().candidateIds())revalidateCandidate(comparison,candidate(comparison,candidateId),actor,context);
            String reference=reviewReference(comparison,review);
            String exclusions=String.join("; ",variant.hardExclusions());
            if(exclusions.length()>4000)exclusions="Hard exclusions retained in inventory comparison "+comparison.id()+" / "+comparison.fingerprint()+"; variant "+variant.projectSolutionId()+" ("+variant.hardExclusions().size()+" complete reasons).";
            solutions.applyInventoryVariant(comparison.demand().projectId(),variant.projectSolutionId(),ProjectSolutionStatus.valueOf(decision.outcome().name()),ActionStatus.valueOf(variant.input().action().name()),reference,comparison.id().toString(),comparison.fingerprint(),exclusions,actor,context);
            solutions.linkRequirement(comparison.demand().projectId(),variant.projectSolutionId(),new LinkRequirementSolutionRequest(comparison.demand().requirementId(),comparison.demand().snapshotId(),review.coveragePercent(),RequirementSolutionRole.USES,ReviewStatus.CONFIRMED,reference),actor,context);
            return null;
        });
    }
    private void revalidateCandidate(Comparison comparison,Candidate candidate,String actor,WorkspaceContext context) {
        if(candidate.productId()==null)return;
        var product=products.getProduct(candidate.productId(),actor,context);
        String expected=candidate.metadata().getOrDefault("mappedProductFingerprint",candidate.metadata().getOrDefault("catalogueFingerprint",candidate.fingerprint()));
        if(!expected.equals(evidence.fingerprint(product)))throw PortfolioException.conflict("Catalogue product changed; compare again");
        String assertions=candidate.metadata().get("curatedAssertionsFingerprint");
        if(assertions!=null && !assertions.equals(evidence.fingerprint(evidence.assertions(scope(actor,context),"product:"+product.id()))))throw PortfolioException.conflict("Curated assertions changed; compare again");
    }
    private String reviewReference(Comparison comparison,Review review) {
        String prefix="Inventory comparison "+comparison.id()+" / "+comparison.fingerprint()+"; snapshot "+comparison.demand().snapshotId()+"; review "+review.id()+"; ";
        return prefix.length()+review.rationale().length()<=2000?prefix+review.rationale():prefix+"Full rationale retained in this review.";
    }
    private record FrozenNode(String code,String nameEn,String nameDe,String descriptionEn,String descriptionDe,String parentCode,String analysisRole,String source,String externalId,Double originalScore) { }
    private void freezeRecordedNodes(List<com.taxonomy.dto.TaxonomyNodeDto> nodes,Map<String,Double> scores,Map<String,String> frozen) {
        for(var node:nodes) {
            if(scores!=null && scores.containsKey(node.getCode()) && scores.get(node.getCode())!=null)
                frozen.put(node.getCode(),codec.write(new FrozenNode(node.getCode(),node.getNameEn(),node.getNameDe(),node.getDescriptionEn(),node.getDescriptionDe(),node.getParentCode(),node.getAnalysisRole(),node.getSource(),node.getExternalId(),scores.get(node.getCode()))));
            if(node.getChildren()!=null)freezeRecordedNodes(node.getChildren(),scores,frozen);
        }
    }
    public List<Reviewed> reviews(UUID id,String actor,WorkspaceContext context) {read(id,actor,context);return evidence.reviews(scope(actor,context),id);}
    private Candidate curated(ProductView product,Scope scope,Question question) {
        var assertions=evidence.assertions(scope,"product:"+product.id());
        var candidate=projection.project(product,evidence.fingerprint(product),assertions,question);
        var metadata=new TreeMap<>(candidate.metadata());metadata.put("catalogueFingerprint",candidate.fingerprint());metadata.put("curatedAssertionsFingerprint",evidence.fingerprint(assertions));
        return new Candidate(candidate.id(),candidate.productId(),candidate.name(),candidate.kind(),evidence.fingerprint(List.of(product,assertions)),metadata,candidate.assertions());
    }
    private void revalidateDemand(Comparison c,String actor,WorkspaceContext context) {
        evidence.revalidate(scope(actor,context),c.demand(),c.sources());
        for(var candidate:c.candidates()) {
            String expected=candidate.metadata().get("mappingStateFingerprint");
            if(expected!=null && !expected.equals(evidence.fingerprint(evidence.mappings(scope(actor,context),candidate.id()))))throw PortfolioException.conflict("Source identity mapping authority changed; compare again");
        }
        var current=snapshots.getSnapshot(c.demand().projectId(),c.demand().snapshotId(),actor,context);
        if(!c.demand().snapshotFingerprint().equals(evidence.fingerprint(codec.write(current))))throw PortfolioException.conflict("Saved snapshot changed; compare again");
        var requirement=projects.getRequirement(c.demand().projectId(),c.demand().requirementId(),actor,context);
        if(!Objects.equals(requirement.currentVersionId(),c.demand().observedRequirementVersionId()!=null?c.demand().observedRequirementVersionId():c.demand().requirementVersionId()))throw PortfolioException.conflict("Current requirement version changed; compare the intended saved version again");
    }
    private Candidate candidate(Comparison c,String id) {return c.candidates().stream().filter(v->v.id().equals(id)).findFirst().orElseThrow(()->PortfolioException.validation("Candidate is not part of this retained comparison"));}
    private Scope scope(String actor,WorkspaceContext context) {return new Scope(PortfolioScope.repositoryId(context),PortfolioScope.key(actor,context),PortfolioScope.branch(context),PortfolioScope.username(actor,context));}
}
