package com.taxonomy.repository;

import com.taxonomy.model.ContextHistoryRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * JPA repository for {@link ContextHistoryRecord} entities.
 */
@Repository
public interface ContextHistoryRecordRepository extends JpaRepository<ContextHistoryRecord, Long> {

    List<ContextHistoryRecord> findByUsernameOrderByCreatedAtDesc(String username);

    List<ContextHistoryRecord> findTop50ByUsernameOrderByCreatedAtDesc(String username);

    long countByUsername(String username);

    @Transactional
    void deleteByUsername(String username);
}
