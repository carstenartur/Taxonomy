package com.taxonomy.portfolio.service;

import com.taxonomy.portfolio.dto.PortfolioDtos.ConflictView;
import com.taxonomy.portfolio.dto.PortfolioDtos.ReviewConflictRequest;
import com.taxonomy.portfolio.model.ArchitectureProject;
import com.taxonomy.portfolio.model.PortfolioTypes.ConflictStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.ConflictType;
import com.taxonomy.portfolio.model.ProjectConflict;
import com.taxonomy.portfolio.model.ProjectRequirement;
import com.taxonomy.portfolio.model.ProjectRequirementVersion;
import com.taxonomy.portfolio.repository.ProjectConflictRepository;
import com.taxonomy.portfolio.repository.ProjectRequirementRepository;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Deterministic conflict hypotheses that always require human confirmation. */
@Service
public class ProjectConflictService {

    private record ConflictRule(
            ConflictType type,
            String title,
            Pattern requiresPattern,
            Pattern forbidsPattern,
            double confidence) {
    }

    private static final List<ConflictRule> RULES = List.of(
            rule(ConflictType.HOSTING,
                    "Cloud-/Hosting-Vorgaben widersprechen sich",
                    "\\b(public cloud|cloud[- ]pflicht|cloud required|must use cloud|saas required|externes hosting erforderlich)\\b",
                    "\\b(no cloud|keine cloud|cloud verboten|on[- ]premises only|nur on[- ]prem|external hosting prohibited|externes hosting verboten)\\b",
                    0.88),
            rule(ConflictType.DATA_LOCATION,
                    "Vorgaben zum Speicherort widersprechen sich",
                    "\\b(global storage|worldwide replication|außerhalb (der )?eu|outside (the )?eu|cross[- ]border storage)\\b",
                    "\\b(nur in deutschland|ausschließlich in deutschland|eu only|only in the eu|daten dürfen die eu nicht verlassen|must not leave the eu)\\b",
                    0.86),
            rule(ConflictType.LIFECYCLE,
                    "Lebenszyklusentscheidungen widersprechen sich",
                    "\\b(expand|extend|modernize|retain|ausbauen|erweitern|modernisieren|beibehalten)\\b",
                    "\\b(retire|replace|decommission|remove|ablösen|ersetzen|stilllegen|abschaffen)\\b",
                    0.66),
            rule(ConflictType.AVAILABILITY,
                    "Betriebs-/Verfügbarkeitsvorgaben widersprechen sich",
                    "\\b(always online|permanent connectivity|ständig online|dauerhafte verbindung|online only)\\b",
                    "\\b(offline capable|offline first|ohne netz|offline[- ]fähig|air[- ]gapped)\\b",
                    0.72),
            rule(ConflictType.PLATFORM,
                    "Plattformvorgaben widersprechen sich",
                    "\\b(vendor[- ]specific|proprietary only|herstellerspezifisch|nur proprietär)\\b",
                    "\\b(vendor neutral|open standards only|herstellerneutral|nur offene standards)\\b",
                    0.70)
    );

    private final ProjectConflictRepository conflictRepository;
    private final ProjectRequirementRepository requirementRepository;
    private final ProjectPortfolioService projectService;
    private final PortfolioFingerprintService fingerprintService;

    public ProjectConflictService(ProjectConflictRepository conflictRepository,
                                  ProjectRequirementRepository requirementRepository,
                                  ProjectPortfolioService projectService,
                                  PortfolioFingerprintService fingerprintService) {
        this.conflictRepository = conflictRepository;
        this.requirementRepository = requirementRepository;
        this.projectService = projectService;
        this.fingerprintService = fingerprintService;
    }

    @Transactional
    public List<ConflictView> detect(Long projectId,
                                     String username,
                                     WorkspaceContext context) {
        ArchitectureProject project = projectService.requireProject(projectId, username, context);
        List<ProjectRequirement> requirements =
                requirementRepository.findByProjectIdOrderByRequirementKeyAsc(projectId);
        Instant now = Instant.now();

        for (int leftIndex = 0; leftIndex < requirements.size(); leftIndex++) {
            ProjectRequirement left = requirements.get(leftIndex);
            ProjectRequirementVersion leftVersion = projectService.currentVersion(left);
            String leftText = normalize(leftVersion.getText());
            for (int rightIndex = leftIndex + 1; rightIndex < requirements.size(); rightIndex++) {
                ProjectRequirement right = requirements.get(rightIndex);
                ProjectRequirementVersion rightVersion = projectService.currentVersion(right);
                String rightText = normalize(rightVersion.getText());
                for (ConflictRule rule : RULES) {
                    boolean leftRequiresRightForbids = rule.requiresPattern().matcher(leftText).find()
                            && rule.forbidsPattern().matcher(rightText).find();
                    boolean rightRequiresLeftForbids = rule.requiresPattern().matcher(rightText).find()
                            && rule.forbidsPattern().matcher(leftText).find();
                    if (!leftRequiresRightForbids && !rightRequiresLeftForbids) continue;

                    String evidence = "Potential conflict between " + left.getRequirementKey()
                            + " (version " + leftVersion.getVersionNumber() + ") and "
                            + right.getRequirementKey() + " (version " + rightVersion.getVersionNumber()
                            + "). One text matches the positive constraint and the other the prohibiting constraint."
                            + " Human review is required.";
                    String fingerprint = fingerprintService.contentFingerprint(
                            projectId + "|" + left.getId() + "|" + right.getId() + "|"
                                    + rule.type() + "|" + leftVersion.getContentHash() + "|"
                                    + rightVersion.getContentHash());
                    if (conflictRepository.findByProjectIdAndFingerprint(projectId, fingerprint).isEmpty()) {
                        conflictRepository.save(new ProjectConflict(
                                project,
                                left,
                                right,
                                rule.type(),
                                fingerprint,
                                rule.title(),
                                evidence,
                                rule.confidence(),
                                now));
                    }
                }
            }
        }
        return list(projectId, username, context);
    }

    @Transactional(readOnly = true)
    public List<ConflictView> list(Long projectId,
                                   String username,
                                   WorkspaceContext context) {
        projectService.requireProject(projectId, username, context);
        return conflictRepository.findByProjectIdOrderByConfidenceDescDetectedAtDesc(projectId)
                .stream().map(this::toView).toList();
    }

    @Transactional
    public ConflictView review(Long projectId,
                               Long conflictId,
                               ReviewConflictRequest request,
                               String username,
                               WorkspaceContext context) {
        projectService.requireProject(projectId, username, context);
        if (request == null || request.status() == null) {
            throw PortfolioException.validation("conflict review status is required");
        }
        if (request.status() == ConflictStatus.PROPOSED) {
            throw PortfolioException.validation("A review cannot reset a conflict to PROPOSED");
        }
        ProjectConflict conflict = conflictRepository.findByIdAndProjectId(conflictId, projectId)
                .orElseThrow(() -> PortfolioException.notFound("Project conflict not found: " + conflictId));
        conflict.review(
                request.status(),
                ProjectPortfolioService.limited(request.resolutionNote(), 4000, "resolutionNote"),
                PortfolioScope.username(username, context),
                Instant.now());
        return toView(conflict);
    }

    public ConflictView toView(ProjectConflict conflict) {
        return new ConflictView(
                conflict.getId(),
                conflict.getProject().getId(),
                conflict.getRequirementA().getId(),
                conflict.getRequirementA().getRequirementKey(),
                conflict.getRequirementB().getId(),
                conflict.getRequirementB().getRequirementKey(),
                conflict.getConflictType(),
                conflict.getStatus(),
                conflict.getTitle(),
                conflict.getEvidence(),
                conflict.getConfidence(),
                conflict.getResolutionNote(),
                conflict.getDetectedAt(),
                conflict.getReviewedBy(),
                conflict.getReviewedAt());
    }

    private static ConflictRule rule(ConflictType type,
                                     String title,
                                     String positive,
                                     String negative,
                                     double confidence) {
        int flags = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        return new ConflictRule(
                type,
                title,
                Pattern.compile(positive, flags),
                Pattern.compile(negative, flags),
                confidence);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}
