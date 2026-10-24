package com.taxonomy.relations.repository;

import com.taxonomy.relations.model.RelationEvidence;
import org.springframework.stereotype.Repository;
import java.util.List;

/** Evidence is readable only through an already-authorized parent tuple. */
@Repository
public interface RelationEvidenceRepository extends org.springframework.data.repository.Repository<RelationEvidence, Long> {
    <S extends RelationEvidence> S save(S entity);
    <S extends RelationEvidence> S saveAndFlush(S entity);
    void flush();
    List<RelationEvidence> findByHypothesisIdAndRepositoryIdAndWorkspaceScopeKeyAndBranchName(
            Long hypothesisId, String repositoryId, String workspaceScopeKey, String branchName);
}
