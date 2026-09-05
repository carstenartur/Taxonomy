package com.taxonomy;

import com.taxonomy.architecture.decision.DecisionRationaleReportService;
import com.taxonomy.architecture.decision.DecisionRationaleReportService.DecisionAnalysisInput;
import com.taxonomy.architecture.decision.DecisionReportBuildMetadataService;
import com.taxonomy.architecture.decision.TaxonomyCatalogueMetadataService;
import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.repository.TaxonomyNodeRepository;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.dto.TaxonomyDataFingerprint;
import com.taxonomy.dto.TaxonomyNodeDto;
import com.taxonomy.portfolio.service.PortfolioFingerprintService;
import com.taxonomy.workspace.service.WorkspaceContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** Uses the same real-catalogue Spring context as DecisionRationaleReportTests. */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(username = "decision-auditor", roles = "ADMIN")
@Transactional
class TaxonomyFingerprintProjectionTests {

    @Autowired private TaxonomyService taxonomyService;
    @Autowired private TaxonomyNodeRepository repository;
    @Autowired private TaxonomyCatalogueMetadataService catalogueMetadata;
    @Autowired private DecisionReportBuildMetadataService buildMetadata;
    @PersistenceContext private EntityManager entityManager;

    @Test
    void projectionPreservesEveryRealNodeRoleAndBothFingerprintFormats() {
        List<TaxonomyNodeDto> full = taxonomyService.getFullTree();
        List<TaxonomyNodeDto> projected = taxonomyService.getFingerprintTree();

        assertThat(TaxonomyDataFingerprint.sha256(projected))
                .isEqualTo(TaxonomyDataFingerprint.sha256(full));
        assertThat(TaxonomyDataFingerprint.legacySha256(projected))
                .isEqualTo(TaxonomyDataFingerprint.legacySha256(full));
        assertThat(flatten(projected)).hasSameSizeAs(flatten(full));
        assertThat(flatten(projected)).anyMatch(node -> "PRODUCT".equals(node.getAnalysisRole()));
        assertThat(flatten(projected)).allSatisfy(node -> {
            assertThat(node.getIncomingRelations()).isEmpty();
            assertThat(node.getOutgoingRelations()).isEmpty();
        });
    }

    @Test
    void reusingBulkLoadedNodesDoesNotInitializeAnyLazyAssociation() {
        entityManager.clear();
        List<TaxonomyNode> nodes = repository.findAll();
        assertAssociationsUninitialized(nodes);

        assertThat(flatten(taxonomyService.toFingerprintTree(nodes))).hasSize(nodes.size());

        assertAssociationsUninitialized(nodes);
    }

    @Test
    void portfolioUsesTheLightweightReaderInsteadOfTheFullDtoTree() {
        String expected = TaxonomyDataFingerprint.sha256(taxonomyService.getFullTree());
        TaxonomyService observed = observedService();
        PortfolioFingerprintService service = new PortfolioFingerprintService(observed, null);

        assertThat(service.taxonomyFingerprint()).isEqualTo(expected);

        verify(observed).getFingerprintTree();
        verify(observed, never()).getFullTree();
    }

    @Test
    void liveReportReusesItsHierarchyInsteadOfFetchingAnotherTree() {
        String expected = TaxonomyDataFingerprint.sha256(taxonomyService.getFullTree());
        TaxonomyService observed = observedService();

        var report = reportService(observed).generate(input(List.of()),
                WorkspaceContext.SHARED, null, Locale.ENGLISH);

        assertThat(report.metadata().taxonomyDataFingerprintSha256()).isEqualTo(expected);
        verify(observed).getRootNodes();
        verify(observed).getChildrenMap();
        verify(observed).toFingerprintTree(anyCollection());
        verify(observed, never()).getFullTree();
        verify(observed, never()).getFingerprintTree();
    }

    @Test
    void frozenReportNeverConsultsTheLiveTaxonomyService() {
        List<TaxonomyNodeDto> frozen = taxonomyService.getFullTree();
        String expected = TaxonomyDataFingerprint.sha256(frozen);
        TaxonomyService observed = observedService();

        var report = reportService(observed).generate(input(frozen),
                WorkspaceContext.SHARED, null, Locale.ENGLISH);

        assertThat(report.metadata().hierarchyFromImmutableSnapshot()).isTrue();
        assertThat(report.metadata().taxonomyDataFingerprintSha256()).isEqualTo(expected);
        verifyNoInteractions(observed);
    }

    @Test
    void currentChangesAreVisibleWithoutExpiringATimeCache() {
        List<TaxonomyNodeDto> frozen = taxonomyService.getFingerprintTree();
        String before = TaxonomyDataFingerprint.sha256(frozen);
        TaxonomyNode actual = repository.findAll().getFirst();
        actual.setDescriptionEn(actual.getDescriptionEn() + "\nFingerprint regression change");
        entityManager.flush();

        assertThat(TaxonomyDataFingerprint.sha256(taxonomyService.getFingerprintTree()))
                .isNotEqualTo(before);
        assertThat(TaxonomyDataFingerprint.sha256(frozen)).isEqualTo(before);
        // The surrounding test transaction rolls back the real catalogue mutation.
    }

    @Test
    void malformedCopiesOfRealEvidenceFailClosed() {
        List<TaxonomyNode> nodes = repository.findAll();
        List<TaxonomyNode> duplicated = new ArrayList<>(nodes);
        duplicated.add(nodes.getFirst());
        assertThatThrownBy(() -> taxonomyService.toFingerprintTree(duplicated))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Duplicate");

        TaxonomyNode child = nodes.stream()
                .filter(node -> node.getParentCode() != null && !node.getParentCode().isBlank())
                .findFirst().orElseThrow();
        TaxonomyNode parent = nodes.stream()
                .filter(node -> node.getCode().equals(child.getParentCode()))
                .findFirst().orElseThrow();
        List<TaxonomyNode> orphaned = new ArrayList<>(nodes);
        orphaned.remove(parent);
        assertThatThrownBy(() -> taxonomyService.toFingerprintTree(orphaned))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Missing");

        List<TaxonomyNode> cyclic = nodes.stream().map(TaxonomyFingerprintProjectionTests::copy).toList();
        cyclic.stream().filter(node -> node.getCode().equals(parent.getCode())).findFirst()
                .orElseThrow().setParentCode(child.getCode());
        assertThatThrownBy(() -> taxonomyService.toFingerprintTree(cyclic))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cycle");
    }

    @Test
    void orderingAndEmptyEvidenceKeepTheExistingCanonicalHashContract() {
        List<TaxonomyNode> nodes = new ArrayList<>(repository.findAll());
        String expected = TaxonomyDataFingerprint.sha256(taxonomyService.toFingerprintTree(nodes));
        Collections.reverse(nodes);
        assertThat(TaxonomyDataFingerprint.sha256(taxonomyService.toFingerprintTree(nodes)))
                .isEqualTo(expected);
        assertThat(TaxonomyDataFingerprint.sha256(taxonomyService.toFingerprintTree(List.of())))
                .isEqualTo(TaxonomyDataFingerprint.sha256(List.of()));
    }

    private TaxonomyService observedService() {
        // Observe calls while retaining real catalogue data and the actual service implementation.
        return mock(TaxonomyService.class, delegatesTo(taxonomyService));
    }

    private DecisionRationaleReportService reportService(TaxonomyService observed) {
        return new DecisionRationaleReportService(observed, catalogueMetadata, buildMetadata, "Europe/Berlin");
    }

    private static DecisionAnalysisInput input(List<TaxonomyNodeDto> tree) {
        return new DecisionAnalysisInput("Catalogue fingerprint regression", Map.of(), Map.of(),
                "MOCK", "PARTIAL", List.of(), tree, null);
    }

    private static void assertAssociationsUninitialized(List<TaxonomyNode> nodes) {
        assertThat(nodes).isNotEmpty().allSatisfy(node -> {
            assertThat(Hibernate.isInitialized(node.getChildren())).isFalse();
            assertThat(Hibernate.isInitialized(node.getIncomingRelations())).isFalse();
            assertThat(Hibernate.isInitialized(node.getOutgoingRelations())).isFalse();
        });
    }

    private static List<TaxonomyNodeDto> flatten(List<TaxonomyNodeDto> roots) {
        List<TaxonomyNodeDto> result = new ArrayList<>();
        var pending = new ArrayDeque<>(roots);
        while (!pending.isEmpty()) {
            TaxonomyNodeDto node = pending.removeFirst();
            result.add(node);
            pending.addAll(node.getChildren());
        }
        return result;
    }

    private static TaxonomyNode copy(TaxonomyNode original) {
        TaxonomyNode result = new TaxonomyNode();
        result.setCode(original.getCode());
        result.setNameEn(original.getNameEn());
        result.setDescriptionEn(original.getDescriptionEn());
        result.setTaxonomyRoot(original.getTaxonomyRoot());
        result.setLevel(original.getLevel());
        result.setParentCode(original.getParentCode());
        return result;
    }
}
