package com.taxonomy.portfolio.reformulation;

import com.taxonomy.identity.StableIdentityHash;
import com.taxonomy.portfolio.service.ProjectPortfolioService;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.net.http.HttpClient;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static com.taxonomy.portfolio.reformulation.ReformulationReportChecks.check;
import static com.taxonomy.portfolio.reformulation.ReformulationReportChecks.get;

/** Fault injection is confined to the parent test's PRIVATE temporary database, always restored. */
final class ReformulationReportBoundaryChecks {
    private static final String SOURCE_CONFLICT = "Adoption evidence does not match its immutable source";

    private ReformulationReportBoundaryChecks() {}

    static void verify(ConfigurableApplicationContext app, HttpClient http, String base,
            ReformulationReportChecks.Fixture fixture, String commandId) throws Exception {
        var jdbc = app.getBean(JdbcTemplate.class);
        var json = app.getBean(ObjectMapper.class);
        var proposals = app.getBean(ReformulationService.class);
        var projects = app.getBean(ProjectPortfolioService.class);
        String proposalUrl = base + fixture.path();
        String receiptUrl = proposalUrl + "/adoptions/" + commandId + "/export?format=json";
        String validReport = get(http, receiptUrl, 200, true).body();
        var receipt = json.readTree(validReport).path("adoption");
        String previewId = receipt.path("previewId").asText();
        String receiptId = jdbc.queryForObject("select id from reformulation_adoption where preview_id=?", String.class, previewId);
        String receiptPayload = jdbc.queryForObject("select receipt_payload from reformulation_adoption where id=?", String.class, receiptId);
        String previewPayload = jdbc.queryForObject("select preview_payload from reformulation_adoption_preview where id=?", String.class, previewId);
        String previewHash = jdbc.queryForObject("select content_hash from reformulation_adoption_preview where id=?", String.class, previewId);
        var beforeRequirement = projects.getRequirement(fixture.project(), fixture.requirement(), "admin", fixture.scope());
        var beforeProposal = proposals.get(fixture.project(), fixture.requirement(), fixture.proposal(), "admin", fixture.scope());
        long beforeReceipts = count(jdbc, "reformulation_adoption");
        long beforeVersions = projects.listRequirementVersions(fixture.project(), fixture.requirement(), "admin", fixture.scope()).size();

        for (long revision : List.of(0L, -1L)) {
            problem(http, json, proposalUrl + "/revisions/" + revision + "/export", 400,
                    "A positive saved revision is required");
        }
        for (String id : List.of("not-a-uuid", "1-1-1-1-1", "AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA")) {
            problem(http, json, proposalUrl + "/adoptions/" + id + "/export", 400,
                    "A canonical adoption command UUID is required");
        }

        // An existing receipt in the SAME authorized scope must not be exported under another offer.
        var other = proposals.create(fixture.project(), fixture.requirement(),
                new ReformulationDtos.CreateRequest(fixture.version(), fixture.snapshot(), "de"), "admin", fixture.scope());
        check(!other.id().equals(fixture.proposal()), "Wrong-offer fixture is not independent");
        problem(http, json, proposalUrl.replace(fixture.proposal(), other.id()) + "/adoptions/" + commandId + "/export",
                404, "Adoption receipt not found");

        // Keep the physical FK and lookup key valid; deliberately falsify one payload identity at a time.
        for (String field : List.of("proposalId", "commandId", "proposalRevision")) {
            String altered = changed(json, receiptPayload, node -> {
                if (field.equals("proposalRevision")) node.put(field, receipt.path(field).asLong() + 1);
                else node.put(field, UUID.randomUUID().toString());
            });
            try {
                check(jdbc.update("update reformulation_adoption set receipt_payload=? where id=?", altered, receiptId) == 1,
                        "Missing receipt fault target");
                boolean sourceMismatch = field.equals("proposalRevision");
                problem(http, json, receiptUrl, sourceMismatch ? 409 : 404,
                        sourceMismatch ? SOURCE_CONFLICT : "Adoption receipt not found");
            } finally {
                jdbc.update("update reformulation_adoption set receipt_payload=? where id=?", receiptPayload, receiptId);
            }
            check(get(http, receiptUrl, 200, true).body().equals(validReport), "Receipt was not restored after " + field);
        }

        // A broken hash must be rejected independently of source-consistency validation.
        try {
            jdbc.update("update reformulation_adoption_preview set preview_payload=? where id=?", previewPayload + " ", previewId);
            problem(http, json, receiptUrl, 409, "Stored preview integrity check failed");
        } finally {
            jdbc.update("update reformulation_adoption_preview set preview_payload=? where id=?", previewPayload, previewId);
        }

        // Even correctly hashed, well-formed evidence must agree with the immutable source.
        for (String field : List.of("sourceVersionId", "originalText", "analysisSnapshotId")) {
            String altered = changed(json, previewPayload, node -> {
                if (field.equals("sourceVersionId")) node.put(field, fixture.version() + 1);
                else if (field.equals("originalText")) node.put(field, "tampered original must never be exported");
                else node.put(field, UUID.randomUUID().toString());
            });
            try {
                jdbc.update("update reformulation_adoption_preview set preview_payload=?,content_hash=? where id=?",
                        altered, StableIdentityHash.sha256(altered), previewId);
                problem(http, json, receiptUrl, 409, SOURCE_CONFLICT);
            } finally {
                jdbc.update("update reformulation_adoption_preview set preview_payload=?,content_hash=? where id=?",
                        previewPayload, previewHash, previewId);
            }
            check(get(http, receiptUrl, 200, true).body().equals(validReport), "Preview was not restored after " + field);
        }
        check(beforeRequirement.equals(projects.getRequirement(fixture.project(), fixture.requirement(), "admin", fixture.scope())),
                "Rejected report changed the active requirement");
        check(beforeProposal.equals(proposals.get(fixture.project(), fixture.requirement(), fixture.proposal(), "admin", fixture.scope())),
                "Rejected report changed the proposal or decisions");
        check(beforeReceipts == count(jdbc, "reformulation_adoption") && beforeVersions == projects.listRequirementVersions(
                fixture.project(), fixture.requirement(), "admin", fixture.scope()).size(), "Report failure created a version or receipt");
        check(count(jdbc, "reformulation_usage_attempt") == 0, "Report validation invoked a provider");
        System.out.println("REFORMULATION_REPORT_BOUNDARIES_OK 13 rejected requests; restored history unchanged");
    }

    private static long count(JdbcTemplate jdbc, String table) {
        // Only fixed test-owned table names above reach this helper, never request input.
        return jdbc.queryForObject("select count(*) from " + table, Long.class);
    }

    private static String changed(ObjectMapper json, String payload, Consumer<ObjectNode> change) {
        var node = (ObjectNode) json.readTree(payload);
        change.accept(node);
        return json.writeValueAsString(node);
    }

    private static void problem(HttpClient http, ObjectMapper json, String url, int status, String detail) throws Exception {
        String body = get(http, url, status, true).body();
        var error = json.readTree(body);
        check(error.path("detail").asText().equals(detail), "Wrong rejection boundary: " + body);
        check(!error.has("revision") && !error.has("preview") && !error.has("adoption"), "Rejected export leaked report data");
        check(!body.contains("tampered original must never be exported"), "Rejected export leaked conflicting source text");
    }
}
