package com.taxonomy.templates;

import com.taxonomy.templates.DocumentTemplateGitRepository.TemplateConflictException;
import com.taxonomy.templates.DocumentTemplateGitRepository.TemplateDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/**
 * Idempotently seeds report templates that are required for an immediately usable install.
 *
 * <p>An existing repository version is never overwritten. Consequently an application
 * update cannot silently replace an organisation's edited Word template.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public final class DefaultDocumentTemplateBootstrap implements ApplicationRunner {

    private static final Logger log =
            LoggerFactory.getLogger(DefaultDocumentTemplateBootstrap.class);

    private final DocumentTemplateService templates;
    private final Resource defaultDecisionTemplate;

    @Autowired
    public DefaultDocumentTemplateBootstrap(DocumentTemplateService templates) {
        this(templates, new ClassPathResource(
                DecisionRationaleTemplateContract.DEFAULT_RESOURCE));
    }

    DefaultDocumentTemplateBootstrap(
            DocumentTemplateService templates,
            Resource defaultDecisionTemplate) {
        this.templates = Objects.requireNonNull(templates, "templates");
        this.defaultDecisionTemplate =
                Objects.requireNonNull(defaultDecisionTemplate, "defaultDecisionTemplate");
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        seedIfMissing();
    }

    void seedIfMissing() throws IOException {
        String templateId = DecisionRationaleTemplateContract.TEMPLATE_ID;
        if (templates.exists(templateId)) {
            log.debug("Required document template {} already exists", templateId);
            return;
        }
        if (!defaultDecisionTemplate.exists()) {
            throw new IllegalStateException(
                    "Required bundled document template is missing: "
                            + DecisionRationaleTemplateContract.DEFAULT_RESOURCE);
        }

        try (InputStream input = defaultDecisionTemplate.getInputStream()) {
            TemplateDescriptor created = templates.upload(
                    templateId,
                    DecisionRationaleTemplateContract.DISPLAY_NAME,
                    input,
                    null,
                    "taxonomy-bootstrap",
                    "Seed bundled decision rationale report template");
            log.info("Seeded required document template {} at commit {}",
                    templateId, created.headCommit());
        } catch (TemplateConflictException race) {
            // Multiple application instances may perform the first-start check together.
            // A winner is sufficient; never force-update or overwrite its commit.
            if (!templates.exists(templateId)) {
                throw race;
            }
            log.info("Required document template {} was seeded concurrently", templateId);
        }
    }
}
