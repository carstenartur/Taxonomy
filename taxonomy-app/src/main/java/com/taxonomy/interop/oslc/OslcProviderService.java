package com.taxonomy.interop.oslc;

import com.taxonomy.editor.persistence.EditorJournal;
import com.taxonomy.exchange.OslcRdf;
import com.taxonomy.interop.IntegrationDomainAdapter;
import com.taxonomy.interop.IntegrationProblem;
import com.taxonomy.portfolio.dto.PortfolioDtos.RequirementView;
import com.taxonomy.portfolio.model.PortfolioTypes.RequirementStatus;
import com.taxonomy.portfolio.service.ProjectPortfolioService;
import com.taxonomy.workspace.service.*;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.util.List;
import static com.taxonomy.exchange.OslcRdf.*;

/** Read-only OSLC resources use existing scoped authorities; no second requirements database or LLM analysis. */
@Service
public class OslcProviderService {
    private final ProjectPortfolioService projects;
    private final WorkspaceArchitectureReadPort architecture;
    private final SystemRepositoryService repositories;
    private final RepositoryMembershipService memberships;
    private final WorkspaceAccessService workspaceAccess;
    public OslcProviderService(ProjectPortfolioService projects, WorkspaceArchitectureReadPort architecture, SystemRepositoryService repositories, RepositoryMembershipService memberships, WorkspaceAccessService workspaceAccess) {
        this.projects = projects; this.architecture = architecture; this.repositories = repositories; this.memberships = memberships;
        this.workspaceAccess = workspaceAccess;
    }
    public void authorize(RepositoryContext context, String scope) {
        if (!workspaceAccess.canUsePrivateWorkspace(context) || !EditorJournal.scope(context).equals(scope)
                || !memberships.canRead(repositories.getRepository(context.repositoryId()), context.username())) throw IntegrationProblem.missing();
    }
    public interface Links { String uri(String path); }
    public OslcRdf catalog(RepositoryContext context, Links links) {
        OslcRdf graph = new OslcRdf(); String catalog = links.uri("/catalog");
        graph.type(catalog, OSLC + "ServiceProviderCatalog").literal(catalog, DCT + "title", "Taxonomy requirements").link(catalog, OSLC + "domain", RM);
        for (var project : projects.listProjects(context.username(), IntegrationDomainAdapter.workspace(context))) {
            String provider = links.uri("/projects/" + project.id() + "/service");
            graph.link(catalog, OSLC + "serviceProvider", provider).type(provider, OSLC + "ServiceProvider").literal(provider, DCT + "title", project.title());
        }
        return graph;
    }
    public OslcRdf service(RepositoryContext context, long projectId, Links links) {
        var project = projects.getProject(projectId, context.username(), IntegrationDomainAdapter.workspace(context));
        String provider = links.uri("/projects/" + projectId + "/service"), service = provider + "#rm", query = provider + "#query";
        OslcRdf graph = new OslcRdf();
        graph.type(provider, OSLC + "ServiceProvider").literal(provider, DCT + "title", project.title()).link(provider, OSLC + "service", service)
                .type(service, OSLC + "Service").link(service, OSLC + "domain", RM).link(service, OSLC + "queryCapability", query)
                .type(query, OSLC + "QueryCapability").literal(query, DCT + "title", "Approved current requirements")
                .link(query, OSLC + "queryBase", links.uri("/projects/" + projectId + "/requirements"))
                .link(query, OSLC + "resourceType", RM + "Requirement").link(query, OSLC + "resourceShape", links.uri("/shapes/requirement"));
        graph.link(provider, "http://open-services.net/ns/config#configuration", links.uri("/configurations/current"));
        return graph;
    }
    public OslcRdf configuration(RepositoryContext context, Links links) {
        String uri = links.uri("/configurations/current");
        return new OslcRdf().type(uri, "http://open-services.net/ns/config#Stream")
                .literal(uri, DCT + "title", "Current approved requirements")
                .literal(uri, TAX + "repositoryId", context.repositoryId()).literal(uri, TAX + "branch", context.branch());
    }
    public OslcRdf query(RepositoryContext context, long projectId, int page, int pageSize, Links links) {
        if (page < 0 || pageSize < 1 || pageSize > 100 || page > 100000) throw new IllegalArgumentException("Query page is outside supported bounds");
        var approved = projects.listApprovedRequirements(projectId, context.username(), IntegrationDomainAdapter.workspace(context), page, pageSize);
        String path = "/projects/" + projectId + "/requirements";
        String resource = links.uri(path) + "&page=" + page + "&oslc.pageSize=" + pageSize;
        OslcRdf graph = new OslcRdf().type(resource, OSLC + "ResponseInfo").literal(resource, DCT + "title", "Approved requirements");
        for (RequirementView requirement : approved.requirements()) {
            String uri = links.uri(path + "/" + requirement.id());
            graph.link(resource, "http://www.w3.org/2000/01/rdf-schema#member", uri); requirement(graph, requirement, projectId, links, false);
        }
        if (approved.hasNext()) graph.link(resource, OSLC + "nextPage", links.uri(path) + "&page=" + (page + 1) + "&oslc.pageSize=" + pageSize);
        return graph;
    }
    public OslcRdf requirement(RepositoryContext context, long projectId, long requirementId, Long versionId, Links links) {
        RequirementView requirement = projects.getRequirement(projectId, requirementId, context.username(), IntegrationDomainAdapter.workspace(context));
        if (requirement.status() != RequirementStatus.APPROVED || versionId != null && !versionId.equals(requirement.currentVersionId())) throw IntegrationProblem.missing();
        OslcRdf graph = new OslcRdf(); requirement(graph, requirement, projectId, links, versionId != null); return graph;
    }
    private void requirement(OslcRdf graph, RequirementView requirement, long projectId, Links links, boolean version) {
        String current = links.uri("/projects/" + projectId + "/requirements/" + requirement.id());
        String immutable = links.uri("/projects/" + projectId + "/requirements/" + requirement.id() + "/versions/" + requirement.currentVersionId());
        String uri = version ? immutable : current;
        graph.type(uri, RM + "Requirement").literal(uri, DCT + "identifier", version ? requirement.currentVersionId().toString() : requirement.requirementKey())
                .literal(uri, DCT + "title", version ? requirement.requirementKey() : requirement.title()).literal(uri, DCT + "description", requirement.currentVersion().text())
                .literal(uri, DCT + "modified", version ? requirement.currentVersion().createdAt().toString() : requirement.updatedAt().toString())
                .link(uri, OSLC + "instanceShape", links.uri("/shapes/requirement"));
        if (version) graph.link(uri, DCT + "isVersionOf", current); else graph.link(uri, DCT + "hasVersion", immutable);
    }
    public OslcRdf shape(Links links) {
        String shape = links.uri("/shapes/requirement"); OslcRdf graph = new OslcRdf();
        graph.type(shape, OSLC + "ResourceShape").literal(shape, DCT + "title", "Read-only approved requirement").link(shape, OSLC + "describes", RM + "Requirement");
        for (String name : List.of("identifier", "title", "description")) {
            String property = shape + "#" + name;
            graph.link(shape, OSLC + "property", property).type(property, OSLC + "Property").literal(property, OSLC + "name", name)
                    .link(property, OSLC + "propertyDefinition", DCT + name).link(property, OSLC + "valueType", "http://www.w3.org/2001/XMLSchema#string")
                    .link(property, OSLC + "occurs", OSLC + "Exactly-one");
        }
        return graph;
    }
    public OslcRdf architectureVersion(RepositoryContext context, String commit, Links links) throws IOException {
        var document = architecture.read(context, commit); String uri = links.uri("/architecture/versions/" + commit);
        return new OslcRdf().type(uri, TAX + "ArchitectureVersion").literal(uri, DCT + "identifier", document.state().commitId())
                .literal(uri, DCT + "title", "Architecture checkpoint " + document.state().commitId())
                .literal(uri, TAX + "canonicalDsl", document.dsl());
    }
}
