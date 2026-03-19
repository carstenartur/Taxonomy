package com.taxonomy.provenance.repository;

import com.taxonomy.provenance.model.SourceFragment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SourceFragmentRepository extends JpaRepository<SourceFragment, Long> {

    List<SourceFragment> findBySourceVersionId(Long sourceVersionId);
}
