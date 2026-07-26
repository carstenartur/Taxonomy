package com.taxonomy.provenance.service;

import com.taxonomy.model.LinkType;
import com.taxonomy.model.SourceType;
import com.taxonomy.provenance.config.DocumentImportLimits;
import com.taxonomy.provenance.model.RequirementSourceLink;
import com.taxonomy.provenance.model.SourceArtifact;
import com.taxonomy.provenance.model.SourceFragment;
import com.taxonomy.provenance.model.SourceVersion;
import com.taxonomy.provenance.repository.RequirementSourceLinkRepository;
import com.taxonomy.provenance.repository.SourceArtifactRepository;
import com.taxonomy.provenance.repository.SourceFragmentRepository;
import com.taxonomy.provenance.repository.SourceVersionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Atomic command boundary for document registration and provenance materialization.
 *
 * <p>Parsing and hashing happen before these commands are called. Once a command
 * enters its transaction, either the complete provenance graph is committed or
 * no part of it is retained.</p>
 */
@Service
public class DocumentProvenanceCommandService {

    private static final int MAX_CANDIDATE_TEXT_LENGTH = 2_000;
    private static final int MAX_SECTION_PATH_LENGTH = 500;

    private final SourceArtifactRepository artifactRepository;
    private final SourceVersionRepository versionRepository;
    private final SourceFragmentRepository fragmentRepository;
    private final RequirementSourceLinkRepository linkRepository;
    private final DocumentImportLimits limits;

    public DocumentProvenanceCommandService(SourceArtifactRepository artifactRepository,
                                            SourceVersionRepository versionRepository,
                                            SourceFragmentRepository fragmentRepository,
                                            RequirementSourceLinkRepository linkRepository,
                                            DocumentImportLimits limits) {
        this.artifactRepository = artifactRepository;
        this.versionRepository = versionRepository;
        this.fragmentRepository = fragmentRepository;
        this.linkRepository = linkRepository;
        this.limits = limits;
    }

    /** Create the logical artifact and its concrete version in one transaction. */
    @Transactional
    public RegistrationResult registerDocument(SourceType sourceType,
                                               String title,
                                               String mimeType,
                                               String contentHash) {
        if (sourceType == null) {
            throw new ProvenanceCommandException("SOURCE_TYPE_REQUIRED", "Source type is required");
        }
        if (title == null || title.isBlank()) {
            throw new ProvenanceCommandException("SOURCE_TITLE_REQUIRED", "Source title is required");
        }
        if (title.length() > 500) {
            throw new ProvenanceCommandException("SOURCE_TITLE_TOO_LARGE",
                    "Source title exceeds 500 characters");
        }
        if (mimeType == null || mimeType.isBlank()) {
            throw new ProvenanceCommandException("MIME_TYPE_REQUIRED", "MIME type is required");
        }
        if (contentHash == null || contentHash.isBlank()) {
            throw new ProvenanceCommandException("CONTENT_HASH_REQUIRED", "Content hash is required");
        }

        SourceArtifact artifact = artifactRepository.save(new SourceArtifact(sourceType, title));
        SourceVersion version = new SourceVersion(artifact);
        version.setMimeType(mimeType);
        version.setContentHash(contentHash);
        version = versionRepository.save(version);

        return new RegistrationResult(artifact.getId(), version.getId());
    }

    /**
     * Validate and materialize all selected candidates atomically.
     * Repeating the same command is idempotent because logical requirement IDs
     * are derived from source-version identity and normalized candidate content.
     */
    @Transactional
    public ConfirmationResult confirmCandidates(long artifactId,
                                                long versionId,
                                                List<CandidateInput> requestedCandidates) {
        List<CandidateInput> candidates = validateCandidates(requestedCandidates);

        SourceArtifact artifact = artifactRepository.findById(artifactId)
                .orElseThrow(() -> new ProvenanceCommandException(
                        "SOURCE_NOT_FOUND", "Source artifact was not found"));
        SourceVersion version = versionRepository.findById(versionId)
                .orElseThrow(() -> new ProvenanceCommandException(
                        "SOURCE_NOT_FOUND", "Source version was not found"));

        if (version.getSourceArtifact() == null
                || !Objects.equals(version.getSourceArtifact().getId(), artifact.getId())) {
            throw new ProvenanceCommandException(
                    "SOURCE_VERSION_MISMATCH",
                    "The selected source version does not belong to the selected artifact");
        }

        int linked = 0;
        int alreadyLinked = 0;
        Set<String> identitiesInRequest = new HashSet<>();

        for (CandidateInput candidate : candidates) {
            String fragmentHash = candidateHash(candidate);
            String requirementId = "DOC-" + artifactId + "-" + versionId + "-" + fragmentHash;

            if (!identitiesInRequest.add(requirementId)
                    || linkRepository.existsByRequirementIdAndSourceArtifactIdAndSourceVersionIdAndLinkType(
                            requirementId, artifactId, versionId, LinkType.EXTRACTED_FROM)) {
                alreadyLinked++;
                continue;
            }

            SourceFragment fragment = new SourceFragment(version, candidate.text());
            fragment.setSectionPath(candidate.sectionHeading());
            fragment.setFragmentHash(fragmentHash);
            fragment = fragmentRepository.save(fragment);

            RequirementSourceLink link = new RequirementSourceLink(
                    requirementId, artifact, LinkType.EXTRACTED_FROM);
            link.setSourceVersion(version);
            link.setSourceFragment(fragment);
            linkRepository.save(link);
            linked++;
        }

        return new ConfirmationResult(linked, alreadyLinked);
    }

    private List<CandidateInput> validateCandidates(List<CandidateInput> requestedCandidates) {
        if (requestedCandidates == null || requestedCandidates.isEmpty()) {
            throw new ProvenanceCommandException("NO_CANDIDATES", "No candidates were selected");
        }
        if (requestedCandidates.size() > limits.getMaxCandidates()) {
            throw new DocumentLimitException(
                    "CANDIDATE_LIMIT_EXCEEDED",
                    "Candidate count exceeds the configured limit of " + limits.getMaxCandidates());
        }

        List<CandidateInput> validated = new ArrayList<>(requestedCandidates.size());
        for (CandidateInput candidate : requestedCandidates) {
            if (candidate == null || candidate.text() == null || candidate.text().isBlank()) {
                throw new ProvenanceCommandException(
                        "CANDIDATE_TEXT_REQUIRED", "Every candidate must contain text");
            }
            String text = candidate.text().strip();
            if (text.length() > MAX_CANDIDATE_TEXT_LENGTH) {
                throw new DocumentLimitException(
                        "CANDIDATE_TEXT_TOO_LARGE",
                        "A requirement candidate exceeds " + MAX_CANDIDATE_TEXT_LENGTH + " characters");
            }

            String section = candidate.sectionHeading();
            if (section != null) {
                section = section.strip();
                if (section.isEmpty()) {
                    section = null;
                } else if (section.length() > MAX_SECTION_PATH_LENGTH) {
                    throw new DocumentLimitException(
                            "CANDIDATE_SECTION_TOO_LARGE",
                            "A candidate section path exceeds " + MAX_SECTION_PATH_LENGTH + " characters");
                }
            }
            validated.add(new CandidateInput(text, section));
        }
        return List.copyOf(validated);
    }

    private static String candidateHash(CandidateInput candidate) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String canonical = (candidate.sectionHeading() == null ? "" : candidate.sectionHeading())
                    + '\u0000' + candidate.text();
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }

    public record CandidateInput(String text, String sectionHeading) {
    }

    public record RegistrationResult(long sourceArtifactId, long sourceVersionId) {
    }

    public record ConfirmationResult(int linked, int alreadyLinked) {
    }

    /** Typed validation failure converted to a bounded API response by the controller. */
    public static class ProvenanceCommandException extends RuntimeException {
        private final String code;

        public ProvenanceCommandException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String getCode() {
            return code;
        }
    }
}
