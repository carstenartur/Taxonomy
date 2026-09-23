package com.taxonomy.portfolio.reformulation;

import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.portfolio.dto.PortfolioDtos.RequirementView;
import com.taxonomy.portfolio.service.PortfolioScope;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Uses the real catalogue and stored snapshots; exporting must not invoke synthesis. */
@SpringBootTest(properties = "llm.mock=true")
@AutoConfigureMockMvc
@WithMockUser(username = "architect", roles = "ARCHITECT")
class ReformulationReportTest extends ReformulationWorkflowFixture {
    @Autowired TaxonomyService catalogue;

    @Override
    String snapshot(RequirementView req) {
        var job = analyses.createOrReuseJob(project.id(), List.of(req.id()), null, 25,
                UUID.randomUUID().toString(), "architect", context);
        var root = catalogue.getFullTree().stream().filter(n -> "BP".equals(n.getCode())).findFirst().orElseThrow();
        var result = new AnalysisResult(Map.of(root.getCode(), 50), List.of(root));
        result.setStatus("SUCCESS");
        String id = UUID.randomUUID().toString();
        analyses.persistSnapshot(job.items().getFirst().id(), job.id(), project.id(), PortfolioScope.key("architect", context),
                id, "report-fixture", result, null, null, null, null, null,
                "authored-report-fixture", "real-catalogue", "architect", context, 1);
        return id;
    }

    @Test
    void exportsExactSavedRevisionWithoutChangingTheRequirement() throws Exception {
        var proposal = reformulations.create(project.id(), requirement.id(),
                new ReformulationDtos.CreateRequest(requirement.currentVersionId(), snapshot, "de"), "architect", context);
        String revision = base() + "/" + proposal.id() + "/revisions/1";
        mvc.perform(get(revision)).andExpect(status().isOk());
        var before = projects.getRequirement(project.id(), requirement.id(), "architect", context);
        // Compare persisted state on both sides: create() can retain sub-column timestamp precision.
        var beforeProposal = reformulations.get(project.id(), requirement.id(), proposal.id(), "architect", context);
        mvc.perform(get(revision + "/export").param("format", "json"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.schemaVersion").value("reformulation-report-v1"))
                .andExpect(jsonPath("$.source.originalText").value(ORIGINAL))
                .andExpect(jsonPath("$.proposal.revision").value(1));
        assertThat(projects.getRequirement(project.id(), requirement.id(), "architect", context)).isEqualTo(before);
        assertThat(reformulations.get(project.id(), requirement.id(), proposal.id(), "architect", context)).isEqualTo(beforeProposal);
        assertThat(reformulations.runs(project.id(), requirement.id(), proposal.id(), "architect", context)).isEmpty();
    }
}
