package com.nato.taxonomy.config;

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
 * OpenAPI 3.0 configuration for the NATO NC3T Taxonomy Analyser.
 * <p>
 * Provides auto-generated interactive API documentation at {@code /swagger-ui.html}.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI taxonomyOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("NATO NC3T Taxonomy Analyser API")
                        .description("""
                                REST API for the NATO NC3T Taxonomy Browser.
                                Analyse business requirements against the C3 Taxonomy catalogue,
                                manage taxonomy relations, run graph-based architecture queries,
                                and export results as Visio or ArchiMate diagrams.""")
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("NATO NC3T Taxonomy Browser")
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
                        new Tag().name("Export").description("Visio (.vsdx) and ArchiMate XML diagram export"),
                        new Tag().name("Administration").description("Admin status, diagnostics, and prompt template management"),
                        new Tag().name("Embedding").description("Embedding model status and configuration")));
    }
}
