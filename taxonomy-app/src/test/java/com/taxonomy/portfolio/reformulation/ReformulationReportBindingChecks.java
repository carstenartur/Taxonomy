package com.taxonomy.portfolio.reformulation;

import com.taxonomy.identity.StableIdentityHash;
import com.taxonomy.portfolio.dto.PortfolioDtos.CreateRequirementRequest;
import com.taxonomy.portfolio.model.PortfolioTypes.*;
import com.taxonomy.portfolio.service.ProjectPortfolioService;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static com.taxonomy.portfolio.reformulation.ReformulationReportChecks.check;
import static com.taxonomy.portfolio.reformulation.ReformulationReportChecks.get;

/** Corrupt only a PRIVATE fixture DB, retaining valid foreign keys and restoring every row. */
final class ReformulationReportBindingChecks {
    static void verify(ConfigurableApplicationContext app, HttpClient http, String base,
            ReformulationReportChecks.Fixture f, String command) throws Exception {
        var jdbc = app.getBean(JdbcTemplate.class);
        var json = app.getBean(ObjectMapper.class);
        var proposals = app.getBean(ReformulationService.class);
        var adoptions = app.getBean(ReformulationAdoptionService.class);
        var projects = app.getBean(ProjectPortfolioService.class);
        String url = base + f.path() + "/adoptions/" + command + "/export?format=json";
        String valid = get(http, url, 200, true).body();
        var result = json.readTree(valid).path("adoption");
        String previewId = result.path("previewId").asText();
        String id = jdbc.queryForObject("select id from reformulation_adoption where preview_id=?", String.class, previewId);
        String payload = jdbc.queryForObject("select receipt_payload from reformulation_adoption where id=?", String.class, id);
        String previewPayload = jdbc.queryForObject("select preview_payload from reformulation_adoption_preview where id=?", String.class, previewId);
        String hash = jdbc.queryForObject("select content_hash from reformulation_adoption_preview where id=?", String.class, previewId);
        long target = result.path("targetVersionId").asLong();
        long revision = result.path("proposalRevision").asLong();
        var alternative = adoptions.preview(f.project(), f.requirement(), f.proposal(), revision, "admin", f.scope());
        var other = proposals.create(f.project(), f.requirement(),
                new ReformulationDtos.CreateRequest(f.version(), f.snapshot(), "de"), "admin", f.scope());
        var otherPreview = adoptions.preview(f.project(), f.requirement(), other.id(), 1, "admin", f.scope());
        var otherRequirement = projects.createRequirement(f.project(), new CreateRequirementRequest(
                "binding-" + UUID.randomUUID(), "Independent requirement", "Independent source",
                RequirementStatus.DRAFT, 50, Criticality.HIGH, RequirementType.FUNCTIONAL,
                ReviewStatus.PROPOSED, "admin", "Private binding fixture", null), "admin", f.scope());
        var failures = new ArrayList<String>();

        // Valid FK combinations, deliberately malformed JSON: scope/identity must be checked FIRST.
        try {
            jdbc.update("update reformulation_adoption set proposal_id=?,preview_id=?,receipt_payload=? where id=?",
                    other.id(), otherPreview.content().id(), "not-json", id);
            reject("persisted proposal before JSON", http, json, url, 404, failures);
        } finally {
            jdbc.update("update reformulation_adoption set proposal_id=?,preview_id=?,receipt_payload=? where id=?",
                    f.proposal(), previewId, payload, id);
        }
        try {
            jdbc.update("update reformulation_adoption set requirement_id=?,target_version_id=?,receipt_payload=? where id=?",
                    otherRequirement.id(), otherRequirement.currentVersionId(), "not-json", id);
            reject("persisted requirement before JSON", http, json, url, 404, failures);
        } finally {
            jdbc.update("update reformulation_adoption set requirement_id=?,target_version_id=?,receipt_payload=? where id=?",
                    f.requirement(), target, payload, id);
        }
        try {
            jdbc.update("update reformulation_adoption set preview_id=? where id=?", alternative.content().id(), id);
            reject("persisted preview binding", http, json, url, 404, failures);
        } finally {
            jdbc.update("update reformulation_adoption set preview_id=? where id=?", previewId, id);
        }
        for (String field : List.of("previewId", "targetVersionId", "previousVersionId")) {
            String changed = changed(json, payload, node -> {
                if (field.equals("previewId")) node.put(field, alternative.content().id());
                else node.put(field, otherRequirement.currentVersionId());
            });
            try {
                jdbc.update("update reformulation_adoption set receipt_payload=? where id=?", changed, id);
                reject("receipt " + field, http, json, url, field.equals("previousVersionId") ? 409 : 404, failures);
            } finally {
                jdbc.update("update reformulation_adoption set receipt_payload=? where id=?", payload, id);
            }
        }
        for (String field : List.of("id", "proposalId", "requirementId", "projectId")) {
            String changed = changed(json, previewPayload, node -> {
                if (field.equals("id")) node.put(field, alternative.content().id());
                else if (field.equals("proposalId")) node.put(field, other.id());
                else if (field.equals("requirementId")) ((ObjectNode) node.get("currentRequirement")).put("id", otherRequirement.id());
                else ((ObjectNode) node.get("currentRequirement")).put("projectId", f.project() + 1);
            });
            try {
                jdbc.update("update reformulation_adoption_preview set preview_payload=?,content_hash=? where id=?",
                        changed, StableIdentityHash.sha256(changed), previewId);
                reject("preview " + field, http, json, url, 409, failures);
            } finally {
                jdbc.update("update reformulation_adoption_preview set preview_payload=?,content_hash=? where id=?", previewPayload, hash, previewId);
            }
        }
        check(get(http, url, 200, true).body().equals(valid), "Restored receipt changed after binding checks");
        check(failures.isEmpty(), "Report binding violations: " + String.join("; ", failures));
        System.out.println("REFORMULATION_REPORT_BINDINGS_OK 10 rejected requests; original receipt unchanged");
    }

    private static String changed(ObjectMapper json, String payload, Consumer<ObjectNode> change) {
        var node = (ObjectNode) json.readTree(payload); change.accept(node); return json.writeValueAsString(node);
    }

    private static void reject(String name, HttpClient http, ObjectMapper json, String url, int status, List<String> failures) throws Exception {
        try {
            var error = json.readTree(get(http, url, status, true).body());
            check(!error.has("revision") && !error.has("preview") && !error.has("adoption"), "Rejected binding leaked report data");
        } catch (AssertionError failure) {
            failures.add(name + ": " + failure.getMessage().substring(0, Math.min(90, failure.getMessage().length())));
        }
    }
}
