package com.taxonomy.provenance.repository;

import com.taxonomy.model.LinkType;
import com.taxonomy.provenance.model.RequirementSourceLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RequirementSourceLinkRepository extends JpaRepository<RequirementSourceLink, Long> {

    List<RequirementSourceLink> findByRequirementId(String requirementId);

    List<RequirementSourceLink> findBySourceArtifactId(Long sourceArtifactId);

    boolean existsByRequirementIdAndSourceArtifactIdAndSourceVersionIdAndLinkType(
            String requirementId,
            Long sourceArtifactId,
            Long sourceVersionId,
            LinkType linkType);
}
