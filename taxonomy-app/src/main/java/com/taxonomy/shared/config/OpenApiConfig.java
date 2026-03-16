package com.taxonomy.shared.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * OpenAPI 3.0 configuration for the Taxonomy Architecture Analyzer.
 * <p>
 * Provides auto-generated interactive API documentation at {@code /swagger-ui.html}.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI taxonomyOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Taxonomy Architecture Analyzer API")
                        .description("""
                                REST API for the Taxonomy Architecture Analyzer — maps free-text business \
                                requirements to structured C3 Taxonomy elements using LLM-powered scoring.
                                Manage taxonomy relations, run graph-based architecture queries, \
                                and export results as Visio, ArchiMate, or Mermaid diagrams.""")
                        .version("1.1.1-SNAPSHOT")
                        .contact(new Contact()
                                .name("Taxonomy Architecture Analyzer")
                                .url("https://github.com/carstenartur/Taxonomy"))
                        .license(new License()
                                .name("MIT License")
                                .url("https://opensource.org/licenses/MIT")))
                .servers(List.of(
                        new Server().url("/").description("Current server")))
                .tags(List.of(
                        new Tag().name("Taxonomy").description("Browse and query the taxonomy tree"),
                        new Tag().name("Analysis").description("AI-powered business requirement analysis"),
                        new Tag().name("Search").description("Full-text, semantic, hybrid, and graph search"),
                        new Tag().name("Relations").description("Manage taxonomy relations"),
                        new Tag().name("Proposals").description("Relation proposal pipeline — propose, review, accept/reject"),
                        new Tag().name("Graph Queries").description("Graph-based architecture impact and neighbourhood queries"),
                        new Tag().name("Quality Metrics").description("Relation quality dashboard and provenance metrics"),
                        new Tag().name("Requirement Coverage").description("Track which taxonomy nodes are covered by recorded requirements"),
                        new Tag().name("Gap Analysis").description("Identify missing relations and incomplete architecture patterns"),
                        new Tag().name("Architecture Recommendation").description("AI-generated architecture improvement suggestions"),
                        new Tag().name("Pattern Detection").description("Detect predefined architecture patterns in the relation graph"),
                        new Tag().name("Export").description("Visio (.vsdx), ArchiMate XML, and Mermaid diagram export"),
                        new Tag().name("Report Export").description("Export architecture analysis as Markdown, HTML, DOCX, or JSON reports"),
                        new Tag().name("Architecture DSL").description("Architecture DSL parsing, validation, versioning (JGit), and materialisation"),
                        new Tag().name("Architecture Intelligence").description("Architecture summary, derived metadata, and next-step guidance"),
                        new Tag().name("Explainable Reasoning").description("Structured explanation traces for scored taxonomy nodes"),
                        new Tag().name("ArchiMate Import").description("Import ArchiMate 3.x XML models into the taxonomy knowledge base"),
                        new Tag().name("Administration").description("Admin status, diagnostics, and prompt template management"),
                        new Tag().name("Embedding").description("Embedding model status and configuration"),
                        new Tag().name("Status").description("Application startup and initialisation status"),
                        new Tag().name("About").description("Application version, build info, license, and third-party notices")));
    }
}
