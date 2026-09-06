package com.taxonomy.templates;

import com.taxonomy.templates.DocumentTemplateGitRepository.TemplateConflictException;
import com.taxonomy.templates.DocumentTemplateGitRepository.TemplateDescriptor;
import com.taxonomy.templates.DocumentTemplateGitRepository.TemplateDiff;
import com.taxonomy.templates.DocumentTemplateGitRepository.TemplateNotFoundException;
import com.taxonomy.templates.DocumentTemplateService.TemplateFile;
import com.taxonomy.templates.DocumentTemplateService.TemplatePartView;
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
public final class DocumentTemplateDetailController {

    private final DocumentTemplateService templates;
    private final DocumentTemplateReportPreview preview;

    public DocumentTemplateDetailController(
            DocumentTemplateService templates,
            DocumentTemplateReportPreview preview) {
        this.templates = templates;
        this.preview = preview;
    }

    /** The URL, download and upload all retain the same immutable starting revision. */
    @GetMapping("/admin/document-templates/{templateId}/local-edit")
    public String localEdit(
            @PathVariable String templateId,
            @RequestParam String revision,
            Model model) throws IOException {
        if (revision == null || !revision.matches("[0-9a-f]{40}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A full immutable template revision is required");
        }
        TemplateFile original;
        try {
            original = templates.download(templateId, revision);
        } catch (TemplateNotFoundException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "The requested template starting revision does not exist", exception);
        }
        model.addAttribute("template", descriptor(original));
        model.addAttribute("maxArchiveBytes", OoxmlTemplatePackageCodec.MAX_ARCHIVE_BYTES);
        model.addAttribute("decisionReportTemplate",
                DecisionRationaleTemplateContract.TEMPLATE_ID.equals(templateId));
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
        model.addAttribute("decisionReportTemplate",
                DecisionRationaleTemplateContract.TEMPLATE_ID.equals(templateId));

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
        requireImmutableRevision(revision);
        requireImmutableRevision(expectedHead);
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
        requireImmutableRevision(revision);
        requireImmutableRevision(expectedHead);
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

    private static void requireImmutableRevision(String revision) {
        if (revision == null || !revision.matches("[0-9a-f]{40}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A full immutable template revision is required");
        }
    }

    private static ResponseStatusException missingRestoreVersion(TemplateNotFoundException cause) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND,
                "The requested template version does not exist", cause);
    }

    @GetMapping("/admin/document-templates/{templateId}/test.docx")
    public ResponseEntity<byte[]> testReport(@PathVariable String templateId) {
        if (!DecisionRationaleTemplateContract.TEMPLATE_ID.equals(templateId)) {
            throw new IllegalArgumentException(
                    "A generated test report is available only for the decision report template");
        }
        byte[] docx = preview.renderPreview();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .contentLength(docx.length)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename("decision-rationale-template-test.docx")
                                .build().toString())
                .body(docx);
    }

    private static TemplateDescriptor descriptor(TemplateFile file) {
        var manifest = file.manifest();
        return new TemplateDescriptor(
                manifest.templateId(), manifest.displayName(), manifest.fileName(),
                file.commitId(), manifest.updatedAt(), manifest.updatedBy(),
                manifest.uncompressedSize(), manifest.partCount(), manifest.packageSha256());
    }
}
