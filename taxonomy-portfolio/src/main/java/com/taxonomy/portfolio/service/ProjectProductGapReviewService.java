package com.taxonomy.portfolio.service;

import com.taxonomy.portfolio.model.ProductGapReview;
import com.taxonomy.portfolio.model.ProductGapReview.Classification;
import com.taxonomy.portfolio.repository.ProductGapReviewRepository;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ProjectProductGapReviewService {
    public record ReviewRequest(Integer expectedRevision, String evidenceFingerprint, Classification classification, String rationale) { }
    public record Decision(int revision, Classification classification, String rationale, String reviewer, Instant reviewedAt, String evidenceFingerprint) {
        static Decision from(ProductGapReview row) {
            return new Decision(row.getRevision(), row.getClassification(), row.getRationale(), row.getReviewer(), row.getReviewedAt(), row.getEvidenceFingerprint());
        }
    }
    public record ReviewedFamily(String reviewKey, String familyCode, String familyName, String catalogueFingerprint,
                                String evidenceFingerprint, int revision, Classification classification, Boolean currentReviewEvidence,
                                List<ProjectProductGaps.Observation> observations, List<Decision> history) { }

    private final ProjectPortfolioService projects;
    private final ProjectGapEvidenceReader evidence;
    private final ProductGapReviewRepository reviews;

    public ProjectProductGapReviewService(ProjectPortfolioService projects, ProjectGapEvidenceReader evidence, ProductGapReviewRepository reviews) {
        this.projects = projects;
        this.evidence = evidence;
        this.reviews = reviews;
    }

    @Transactional(readOnly = true)
    public List<ReviewedFamily> list(Long project, String actor, WorkspaceContext context) {
        return forReport(project, null, projects.requireProject(project, actor, context).getScopeKey());
    }

    List<ReviewedFamily> forReport(Long project, Long requirementId, String scope) {
        var families = evidence.read(project, requirementId, scope);
        if (families.isEmpty()) return List.of();
        Map<String, List<ProductGapReview>> history = reviews.findByProjectIdAndScopeKeyOrderByGapKeyAscRevisionAsc(project, scope)
                .stream().collect(Collectors.groupingBy(ProductGapReview::getGapKey));
        return families.stream().map(family -> view(family, history.getOrDefault(family.reviewKey(), List.of()), requirementId == null)).toList();
    }

    @Transactional
    public ReviewedFamily review(Long project, String key, ReviewRequest request, String actor, WorkspaceContext context) {
        String scope = projects.requireProjectForUpdate(project, actor, context).getScopeKey();
        if (request == null || request.expectedRevision() == null || request.expectedRevision() < 0 || request.classification() == null
                || request.rationale() == null || request.rationale().isBlank() || request.rationale().strip().length() > 2000) {
            throw PortfolioException.validation("Provide the expected revision, classification and a rationale of up to 2000 characters");
        }
        var family = evidence.read(project, null, scope).stream().filter(item -> item.reviewKey().equals(key))
                .findFirst().orElseThrow(() -> PortfolioException.notFound("Product coverage gap not found"));
        var history = reviews.findByProjectIdAndScopeKeyAndGapKeyOrderByRevisionAsc(project, scope, key);
        int revision = history.isEmpty() ? 0 : history.getLast().getRevision();
        if (request.expectedRevision() != revision || !family.evidenceFingerprint().equals(request.evidenceFingerprint()))
            throw PortfolioException.conflict("The project evidence or its review changed; reload the gap before reviewing");
        var decision = new ProductGapReview(project, scope, key, revision + 1, family.evidenceFingerprint(),
                request.classification(), request.rationale().strip(), actor, Instant.now());
        reviews.saveAndFlush(decision);
        var updated = new java.util.ArrayList<>(history);
        updated.add(decision);
        return view(family, updated, true);
    }

    private static ReviewedFamily view(ProjectProductGaps.Family family, List<ProductGapReview> history, boolean fullProject) {
        var last = history.isEmpty() ? null : history.getLast();
        return new ReviewedFamily(family.reviewKey(), family.familyCode(), family.familyName(), family.catalogueFingerprint(),
                family.evidenceFingerprint(), last == null ? 0 : last.getRevision(),
                last == null ? Classification.UNDECIDED : last.getClassification(),
                last == null || !fullProject ? null : family.evidenceFingerprint().equals(last.getEvidenceFingerprint()), family.observations(),
                history.stream().map(Decision::from).toList());
    }
}
