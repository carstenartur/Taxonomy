package com.taxonomy.provenance.service;

import com.taxonomy.dto.RequirementSourceLinkDto;
import com.taxonomy.dto.SourceArtifactDto;
import com.taxonomy.model.LinkType;
import com.taxonomy.model.SourceType;
import com.taxonomy.provenance.model.RequirementSourceLink;
import com.taxonomy.provenance.model.SourceArtifact;
import com.taxonomy.provenance.model.SourceFragment;
import com.taxonomy.provenance.model.SourceVersion;
import com.taxonomy.provenance.repository.RequirementSourceLinkRepository;
import com.taxonomy.provenance.repository.SourceArtifactRepository;
import com.taxonomy.provenance.repository.SourceFragmentRepository;
import com.taxonomy.provenance.repository.SourceVersionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Central service for managing source provenance.  Every requirement should be
 * traceable to its source via this service.
 */
@Service
public class SourceProvenanceService {

    private static final Logger log = LoggerFactory.getLogger(SourceProvenanceService.class);

    private final SourceArtifactRepository artifactRepo;
    private final SourceVersionRepository versionRepo;
    private final SourceFragmentRepository fragmentRepo;
    private final RequirementSourceLinkRepository linkRepo;

    public SourceProvenanceService(SourceArtifactRepository artifactRepo,
                                    SourceVersionRepository versionRepo,
                                    SourceFragmentRepository fragmentRepo,
                                    RequirementSourceLinkRepository linkRepo) {
        this.artifactRepo = artifactRepo;
        this.versionRepo = versionRepo;
        this.fragmentRepo = fragmentRepo;
        this.linkRepo = linkRepo;
    }

    // ── Artifact operations ────────────────────────────────────────────────────

    @Transactional
    public SourceArtifact createArtifact(SourceType sourceType, String title) {
        SourceArtifact artifact = new SourceArtifact(sourceType, title);
        artifact = artifactRepo.save(artifact);
        log.info("Created source artifact id={} type={} title=\"{}\"", artifact.getId(), sourceType, title);
        return artifact;
    }

    @Transactional(readOnly = true)
    public List<SourceArtifact> findArtifactsByType(SourceType sourceType) {
        return artifactRepo.findBySourceType(sourceType);
    }

    @Transactional(readOnly = true)
    public List<SourceArtifactDto> listAllArtifacts() {
        return artifactRepo.findAll().stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public Optional<SourceArtifact> findArtifactById(Long id) {
        return artifactRepo.findById(id);
    }

    // ── Version operations ─────────────────────────────────────────────────────

    @Transactional
    public SourceVersion createVersion(SourceArtifact artifact, String mimeType, String contentHash) {
        SourceVersion version = new SourceVersion(artifact);
        version.setMimeType(mimeType);
        version.setContentHash(contentHash);
        return versionRepo.save(version);
    }

    @Transactional(readOnly = true)
    public Optional<SourceVersion> findVersionById(Long id) {
        return versionRepo.findById(id);
    }

    // ── Fragment operations ────────────────────────────────────────────────────

    @Transactional
    public SourceFragment createFragment(SourceVersion version, String fragmentText,
                                          String sectionPath, Integer pageFrom) {
        SourceFragment fragment = new SourceFragment(version, fragmentText);
        fragment.setSectionPath(sectionPath);
        fragment.setPageFrom(pageFrom);
        return fragmentRepo.save(fragment);
    }

    // ── Link operations ────────────────────────────────────────────────────────

    @Transactional
    public RequirementSourceLink linkRequirement(String requirementId,
                                                  SourceArtifact artifact,
                                                  SourceVersion version,
                                                  SourceFragment fragment,
                                                  LinkType linkType) {
        RequirementSourceLink link = new RequirementSourceLink(requirementId, artifact, linkType);
        link.setSourceVersion(version);
        link.setSourceFragment(fragment);
        link = linkRepo.save(link);
        log.debug("Linked requirement {} → artifact {} ({})", requirementId, artifact.getId(), linkType);
        return link;
    }

    @Transactional(readOnly = true)
    public List<RequirementSourceLinkDto> getLinksForRequirement(String requirementId) {
        return linkRepo.findByRequirementId(requirementId).stream()
                .map(this::toLinkDto)
                .toList();
    }

    // ── DTO mapping ────────────────────────────────────────────────────────────

    private SourceArtifactDto toDto(SourceArtifact entity) {
        SourceArtifactDto dto = new SourceArtifactDto(entity.getSourceType(), entity.getTitle());
        dto.setId(entity.getId());
        dto.setCanonicalIdentifier(entity.getCanonicalIdentifier());
        dto.setCanonicalUrl(entity.getCanonicalUrl());
        dto.setOriginSystem(entity.getOriginSystem());
        dto.setAuthor(entity.getAuthor());
        dto.setDescription(entity.getDescription());
        dto.setLanguage(entity.getLanguage());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        return dto;
    }

    private RequirementSourceLinkDto toLinkDto(RequirementSourceLink entity) {
        RequirementSourceLinkDto dto = new RequirementSourceLinkDto();
        dto.setId(entity.getId());
        dto.setRequirementId(entity.getRequirementId());
        dto.setSourceArtifactId(entity.getSourceArtifact().getId());
        dto.setLinkType(entity.getLinkType());
        dto.setConfidence(entity.getConfidence());
        dto.setNote(entity.getNote());
        dto.setSourceTitle(entity.getSourceArtifact().getTitle());
        dto.setSourceTypeName(entity.getSourceArtifact().getSourceType().name());
        if (entity.getSourceVersion() != null) {
            dto.setSourceVersionId(entity.getSourceVersion().getId());
        }
        if (entity.getSourceFragment() != null) {
            dto.setSourceFragmentId(entity.getSourceFragment().getId());
        }
        return dto;
    }
}
