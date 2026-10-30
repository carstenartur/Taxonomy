package com.taxonomy.portfolio.service;

import com.taxonomy.portfolio.dto.RequirementIntakeDtos.*;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/** Freezes authorized candidates, calls the provider outside transactions, then rechecks disclosure. */
@Service
public class RequirementIntakeService {
    private final RequirementIntakeCandidates candidates;
    private final RequirementIntakeMatcher matcher;
    public RequirementIntakeService(RequirementIntakeCandidates candidates, RequirementIntakeMatcher matcher) {
        this.candidates = candidates; this.matcher = matcher;
    }
    public Response review(Request request, String username, WorkspaceContext context) {
        if (request == null || request.text() == null || request.text().isBlank() || request.text().length() > 100_000
                || request.title() != null && request.title().length() > 240 || request.projectId() != null && request.projectId() < 1)
            throw PortfolioException.validation("A requirement text of at most 100000 characters is required");
        var frozen = candidates.discover(candidates.freeze(request.projectId(), username, context), username);
        // Authorization may have changed while canonical documents were being captured.
        candidates.recheck(frozen, username, context);
        var result = frozen.providerAllowed() ? matcher.review(request.text(), frozen.candidates(), request.analysisLanguage())
                : new RequirementIntakeMatcher.Result(Status.UNAVAILABLE, "PROJECT_PROVIDER_BOUND", null, frozen.candidates());
        candidates.recheckSources(frozen, username);
        candidates.recheck(frozen, username, context);
        // This deterministic fallback proves equality only, never semantic absence or provider success.
        List<Candidate> assessed = result.candidates().stream().map(candidate ->
                "REQUIREMENT".equals(candidate.kind()) && request.text().equals(candidate.text())
                        ? candidate.assessed(Classification.DUPLICATE, "The stored text is exactly identical.", List.of(request.text()))
                        : candidate).toList();
        Status status = result.status() == Status.COMPLETE && frozen.coverage().truncated() ? Status.PARTIAL : result.status();
        return new Response(status, result.code(), inputHash(request.text()), frozen.scope(), result.provider(),
                frozen.coverage(), assessed, status != Status.COMPLETE);
    }
    public static String inputHash(String text) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
