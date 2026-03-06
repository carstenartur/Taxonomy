package com.nato.taxonomy.service;

import com.nato.taxonomy.dto.TaxonomyNodeDto;
import com.nato.taxonomy.model.TaxonomyNode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.search.mapper.orm.Search;
import org.hibernate.search.mapper.orm.session.SearchSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Full-text taxonomy search backed by Hibernate Search (Lucene backend).
 *
 * <p>Replaces the previous raw-Lucene {@code ByteBuffersDirectory} + {@code IndexWriter}
 * approach. Hibernate Search auto-indexes {@link TaxonomyNode} entities on JPA persist
 * and commit, so no manual {@code buildIndex()} call is required.
 *
 * <h2>Query strategy</h2>
 * <ul>
 *   <li>Full-text match across {@code nameEn}, {@code descriptionEn}, {@code nameDe},
 *       {@code descriptionDe} using the configured English/German analyzers.</li>
 *   <li>Keyword prefix match on {@code code}, {@code uuid}, {@code externalId} for
 *       case-insensitive exact/prefix lookups (e.g. "BP", "BP.001").</li>
 * </ul>
 */
@Service
public class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Search the taxonomy index and return up to {@code maxResults} flat (no children) DTOs.
     */
    @Transactional(readOnly = true)
    public List<TaxonomyNodeDto> search(String queryString, int maxResults) {
        if (queryString == null || queryString.isBlank()) {
            return Collections.emptyList();
        }
        try {
            SearchSession session = Search.session(entityManager);
            String lower = queryString.toLowerCase(Locale.ROOT);

            List<TaxonomyNode> hits = session.search(TaxonomyNode.class)
                    .where(f -> f.bool()
                            .should(f.match()
                                    .fields("nameEn", "descriptionEn", "nameDe", "descriptionDe")
                                    .matching(queryString))
                            .should(f.wildcard()
                                    .fields("code", "uuid", "externalId")
                                    .matching(lower + "*"))
                            .should(f.match()
                                    .fields("code", "uuid", "externalId")
                                    .matching(lower)))
                    .sort(f -> f.score())
                    .fetchHits(maxResults);

            return hits.stream().map(this::toFlatDto).collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Hibernate Search full-text search failed for '{}': {}", queryString, e.getMessage());
            return Collections.emptyList();
        }
    }

    /** Convert a {@link TaxonomyNode} to a flat DTO (no children, no relations). */
    private TaxonomyNodeDto toFlatDto(TaxonomyNode node) {
        TaxonomyNodeDto dto = new TaxonomyNodeDto();
        dto.setId(node.getId());
        dto.setCode(node.getCode());
        dto.setUuid(node.getUuid());
        dto.setNameEn(node.getNameEn());
        dto.setNameDe(node.getNameDe());
        dto.setDescriptionEn(node.getDescriptionEn());
        dto.setDescriptionDe(node.getDescriptionDe());
        dto.setParentCode(node.getParentCode());
        dto.setTaxonomyRoot(node.getTaxonomyRoot());
        dto.setLevel(node.getLevel());
        dto.setDataset(node.getDataset());
        dto.setExternalId(node.getExternalId());
        dto.setSource(node.getSource());
        dto.setReference(node.getReference());
        dto.setSortOrder(node.getSortOrder());
        dto.setState(node.getState());
        return dto;
    }
}
