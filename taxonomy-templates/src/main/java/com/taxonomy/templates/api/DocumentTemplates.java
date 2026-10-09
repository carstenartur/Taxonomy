package com.taxonomy.templates.api;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Application boundary for versioned document templates.
 *
 * <p>Revision and ETag values are opaque identities. Implementations preserve
 * the selected immutable revision, validate OOXML before storage/materialization,
 * and enforce optimistic preconditions without exposing their persistence engine.</p>
 */
public interface DocumentTemplates {

    List<TemplateDescriptor> list() throws IOException;

    boolean exists(String templateId) throws IOException;

    TemplateDescriptor upload(
            String templateId,
            String displayName,
            InputStream dotx,
            String expectedVersion,
            String actor,
            String message) throws IOException;

    TemplateDescriptor describeCurrent(String templateId) throws IOException;

    TemplateDescriptor describe(String templateId, String revision) throws IOException;

    TemplateFile downloadCurrent(String templateId) throws IOException;

    TemplateFile downloadCurrentValidated(String templateId) throws IOException;

    TemplateFile download(String templateId, String revision) throws IOException;

    List<TemplateRevision> history(String templateId) throws IOException;

    TemplateDiff diff(String templateId, String fromRevision, String toRevision)
            throws IOException;

    TemplatePartView readPart(
            String templateId,
            String revision,
            String path) throws IOException;

    TemplatePartComparison comparePart(
            String templateId, String fromRevision, String toRevision, String path) throws IOException;

    TemplateDescriptor restore(
            String templateId,
            String revision,
            String expectedVersion,
            String actor) throws IOException;

    String headCommit() throws IOException;
}
