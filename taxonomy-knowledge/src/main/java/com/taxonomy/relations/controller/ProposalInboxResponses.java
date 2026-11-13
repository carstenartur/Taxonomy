package com.taxonomy.relations.controller;

import com.taxonomy.identity.StableIdentityHash;
import com.taxonomy.workspace.service.RepositoryContext;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** The mutable inbox shares review history across branches within its authorized repository/workspace overlay. */
@Component
public class ProposalInboxResponses {
    private final ObjectMapper json;

    public ProposalInboxResponses(ObjectMapper json) {
        this.json = json;
    }

    <T> ResponseEntity<T> read(RepositoryContext context, String view, Supplier<T> load) {
        return read(context, view, null, load);
    }

    <T> ResponseEntity<T> read(RepositoryContext context, String view, Integer limit, Supplier<T> load) {
        var request = RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes servlet
                ? servlet.getRequest() : null;
        if (request != null && request.getParameter("commitId") != null) {
            return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).build();
        }
        T value = load.get();
        String workspace = Objects.toString(context.workspaceId(), "");
        String tag = StableIdentityHash.sha256(json.writeValueAsString(List.of(
                "proposal-review-history-v1", context.repositoryId(), workspace, view, Objects.toString(limit, ""), value)));
        var location = UriComponentsBuilder.newInstance().queryParam("repositoryId", "{repo}")
                .queryParam("workspaceId", "{workspace}").queryParam("branch", "{branch}");
        if (limit != null && request != null && request.getParameter("limit") != null) {
            location.queryParam("limit", limit);
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).eTag("W/\"" + tag + "\"")
                .header(HttpHeaders.CONTENT_LOCATION, location.encode().buildAndExpand(context.repositoryId(), workspace,
                        context.branch()).toUriString())
                .header(RelationApiController.READ_MODEL_HEADER, "PROPOSAL_REVIEW_HISTORY").body(value);
    }
}
