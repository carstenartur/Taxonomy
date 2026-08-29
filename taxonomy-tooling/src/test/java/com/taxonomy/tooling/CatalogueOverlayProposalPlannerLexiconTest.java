package com.taxonomy.tooling;

import com.taxonomy.tooling.CatalogueOverlayProposalModel.CandidateScore;
import com.taxonomy.tooling.CatalogueOverlayProposalModel.OverlayModel;
import com.taxonomy.tooling.CatalogueOverlayProposalModel.Patch;
import com.taxonomy.tooling.CatalogueOverlayProposalModel.Proposal;
import com.taxonomy.tooling.CatalogueOverlayProposalModel.SourceCatalogue;
import com.taxonomy.tooling.CatalogueOverlayProposalModel.SourceNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogueOverlayProposalPlannerLexiconTest {

    @Test
    void commonDefinitionProseCannotCreateAPrimaryOrSecondaryMapping() {
        Proposal proposal = proposalFor(
                "Recognized Pictures",
                "Information about facts or objects that constitute context for an operation.",
                "Hazards",
                "Information about facts or objects that constitute context for an operation.");

        assertThat(proposal.status()).isEqualTo("NEW_UNRESOLVED");
        assertThat(proposal.unresolved()).isTrue();
        assertThat(proposal.proposedParentCode()).isNull();
        assertThat(proposal.proposedSecondaryClassificationCodes()).isEmpty();
        assertThat(proposal.reason())
                .contains("best score 8")
                .contains("expert review is required");

        assertThat(proposal.rankedCandidates()).hasSize(1);
        CandidateScore candidate = proposal.rankedCandidates().getFirst();
        assertThat(candidate.code()).isEqualTo("IP-2100");
        assertThat(candidate.score()).isEqualTo(8);
        assertThat(candidate.matchedTokens()).containsExactly("operation");
    }

    @Test
    void specificDomainEvidenceStillSelectsAProductFamily() {
        Proposal proposal = proposalFor(
                "Medical Information Products",
                "Clinical health treatment information.",
                "Medical Treatment Report",
                "Clinical treatment record.");

        assertThat(proposal.status()).isEqualTo("NEW_MAPPING");
        assertThat(proposal.unresolved()).isFalse();
        assertThat(proposal.proposedParentCode()).isEqualTo("IP-2100");
        assertThat(proposal.reason())
                .contains("Deterministic lexical evidence selected IP-2100")
                .contains("medical");
        assertThat(proposal.rankedCandidates().getFirst().score()).isGreaterThanOrEqualTo(220);
    }

    private static Proposal proposalFor(
            String familyTitle,
            String familyDescription,
            String productTitle,
            String productDescription) {
        SourceNode root = new SourceNode(
                "IP", "root", "Information Products", "", "", "approved", 0);
        SourceNode family = new SourceNode(
                "IP-2100", "family", familyTitle, familyDescription,
                "IP", "approved", 2);
        SourceNode product = new SourceNode(
                "IP-3999", "product", productTitle, productDescription,
                "IP-2100", "draft", 3);

        Patch familyPatch = new Patch(
                "IP-2100",
                familyTitle,
                "approved",
                "IP",
                CatalogueOverlayProposalGenerator.ROLE_PRODUCT_FAMILY,
                List.of(),
                new BigDecimal("0.950"),
                false,
                "Reviewed product family.");

        OverlayModel overlay = new OverlayModel(
                2,
                "OVERLAY",
                "fixture.xlsx",
                "fixture-v2",
                "IP",
                "draft",
                Map.of("IP-2100", familyPatch));
        SourceCatalogue source = new SourceCatalogue(
                "IP",
                Map.of(
                        "IP", root,
                        "IP-2100", family,
                        "IP-3999", product));

        List<Proposal> proposals = CatalogueOverlayProposalPlanner.buildProposals(
                overlay,
                source,
                Map.of(
                        "IP-2100", "IP",
                        "IP-3999", "IP-2100"),
                Set.of("IP-2100"));

        assertThat(proposals).hasSize(1);
        return proposals.getFirst();
    }
}
