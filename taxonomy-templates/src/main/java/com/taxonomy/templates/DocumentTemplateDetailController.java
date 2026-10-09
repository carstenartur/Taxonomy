package com.taxonomy.templates;

import com.taxonomy.templates.api.TemplateContribution;
import com.taxonomy.templates.api.DocumentTemplates;

import com.taxonomy.templates.api.TemplateConflictException;
import com.taxonomy.templates.api.TemplateDescriptor;
import com.taxonomy.templates.api.TemplateDiff;
import com.taxonomy.templates.api.TemplateNotFoundException;
import com.taxonomy.templates.api.TemplateFile;
import com.taxonomy.templates.api.TemplatePartView;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.security.Principal;

/** Accessible server-rendered history, comparison, inspection and restore workspace. */
@Controller
@Tag(name = "Document templates")
public final class DocumentTemplateDetailController {

    private final DocumentTemplates templates;
    private final java.util.Map<String, TemplateContribution> contributions;

    public DocumentTemplateDetailController(
            DocumentTemplates templates,
            java.util.List<TemplateContribution> contributions) {
        this.templates = templates;
        this.contributions = TemplateContributions.index(contributions);
    }

    /** The URL, download and upload all retain the same immutable starting revision. */
    @GetMapping("/admin/document-templates/{templateId}/local-edit")
    public String localEdit(
            @PathVariable String templateId,
            @RequestParam String revision,
            Model model) throws IOException {
        revision = canonicalImmutableRevision(revision);
        TemplateFile original;
        try {
            original = templates.download(templateId, revision);
        } catch (TemplateNotFoundException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "The requested template starting revision does not exist", exception);
        }
        model.addAttribute("template", descriptor(original));
        model.addAttribute("maxArchiveBytes", OoxmlTemplatePackageCodec.MAX_ARCHIVE_BYTES);
        model.addAttribute("templatePreviewAvailable", hasPreview(templateId));
        return "document-template-local-edit";
    }

    @GetMapping("/admin/document-templates/{templateId}")
    public String detail(
            @PathVariable String templateId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String partRevision,
            @RequestParam(required = false) String partPath,
            Model model) throws IOException {
        TemplateFile current = templates.downloadCurrent(templateId);
        TemplateDescriptor descriptor = descriptor(current);
        model.addAttribute("template", descriptor);
        model.addAttribute("history", templates.history(templateId));
        model.addAttribute("templatePreviewAvailable", hasPreview(templateId));

        if (from != null && !from.isBlank()) {
            String right = to == null || to.isBlank() ? current.commitId() : to;
            TemplateDiff diff = templates.diff(templateId, from, right);
            model.addAttribute("diff", diff);
            model.addAttribute("fromRevision", from);
            model.addAttribute("toRevision", right);
        }
        if (partRevision != null && !partRevision.isBlank()
                && partPath != null && !partPath.isBlank()) {
            TemplatePartView part = templates.readPart(
                    templateId, partRevision, partPath);
            model.addAttribute("part", part);
            model.addAttribute("partRevision", partRevision);
        }
        return "document-template-detail";
    }

    /** A read-only, bookmarkable confirmation; reloading never replaces its precondition. */
    @GetMapping("/admin/document-templates/{templateId}/restore")
    public String confirmRestore(
            @PathVariable String templateId,
            @RequestParam String revision,
            @RequestParam String expectedHead,
            Model model) throws IOException {
        revision = canonicalImmutableRevision(revision);
        expectedHead = canonicalImmutableRevision(expectedHead);
        try {
            TemplateDescriptor target = templates.describe(templateId, revision);
            TemplateDescriptor current = templates.describeCurrent(templateId);
            model.addAttribute("template", current);
            model.addAttribute("restoreTarget", target);
            model.addAttribute("restoreRevision", revision);
            model.addAttribute("restoreExpectedHead", expectedHead);
            model.addAttribute("restoreConflict", !expectedHead.equals(current.headCommit()));
            return "document-template-restore";
        } catch (TemplateNotFoundException exception) {
            throw missingRestoreVersion(exception);
        }
    }

    @PostMapping("/admin/document-templates/{templateId}/restore")
    public String restore(
            @PathVariable String templateId,
            @RequestParam String revision,
            @RequestParam String expectedHead,
            @RequestParam(defaultValue = "false") boolean confirmed,
            Principal principal,
            Model model,
            HttpServletResponse response,
            RedirectAttributes redirect) throws IOException {
        revision = canonicalImmutableRevision(revision);
        expectedHead = canonicalImmutableRevision(expectedHead);
        if (!confirmed) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Explicit confirmation is required to restore a template");
        }
        try {
            TemplateDescriptor restored = templates.restore(
                    templateId, revision, expectedHead, principal.getName());
            redirect.addFlashAttribute("restoredRevision", restored.headCommit());
            return "redirect:/admin/document-templates/" + templateId;
        } catch (TemplateConflictException exception) {
            // Do not substitute the new head or retry the mutation. Preserve both original choices.
            String view = confirmRestore(templateId, revision, expectedHead, model);
            model.addAttribute("restoreConflict", true);
            response.setStatus(HttpStatus.PRECONDITION_FAILED.value());
            return view;
        } catch (TemplateNotFoundException exception) {
            throw missingRestoreVersion(exception);
        }
    }

    private static String canonicalImmutableRevision(String revision) {
        if (revision == null || !revision.matches("[0-9a-fA-F]{40}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A full immutable template revision is required");
        }
        return revision.toLowerCase(java.util.Locale.ROOT);
    }

    private static ResponseStatusException missingRestoreVersion(TemplateNotFoundException cause) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND,
                "The requested template version does not exist", cause);
    }

    @GetMapping("/admin/document-templates/{templateId}/test.docx")
    @io.swagger.v3.oas.annotations.Operation(summary = "Render a document-template test report",
            description = "Generates a DOCX preview using the decision-rationale template and the fixed preview data. Other template IDs are rejected. Returns a no-store attachment; this test report is not evidence of a user analysis.")
    @ApiResponse(responseCode = "200", description = "Render a document-template test report response")
    public ResponseEntity<byte[]> testReport(@PathVariable String templateId) {
        var contribution = contributions.get(templateId);
        if (!hasPreview(templateId)) {
            throw new IllegalArgumentException("A generated test report is unavailable for this template family");
        }
        byte[] docx = contribution.preview().renderPreview();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .contentLength(docx.length)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(contribution.previewFileName())
                                .build().toString())
                .body(docx);
    }

    private boolean hasPreview(String templateId) {
        var contribution = contributions.get(templateId);
        return contribution != null && contribution.preview() != null;
    }

    private static TemplateDescriptor descriptor(TemplateFile file) {
        var manifest = file.manifest();
        return new TemplateDescriptor(
                manifest.templateId(), manifest.displayName(), manifest.fileName(),
                file.commitId(), manifest.updatedAt(), manifest.updatedBy(),
                manifest.uncompressedSize(), manifest.partCount(), manifest.packageSha256());
    }
}
