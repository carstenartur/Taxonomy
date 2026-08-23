package com.taxonomy.templates;

import com.taxonomy.templates.DocumentTemplateGitRepository.TemplateDescriptor;
import com.taxonomy.templates.DocumentTemplateGitRepository.TemplateDiff;
import com.taxonomy.templates.DocumentTemplateService.TemplateFile;
import com.taxonomy.templates.DocumentTemplateService.TemplatePartView;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

    @PostMapping("/admin/document-templates/{templateId}/restore")
    public String restore(
            @PathVariable String templateId,
            @RequestParam String revision,
            @RequestParam String expectedHead,
            Principal principal,
            RedirectAttributes redirect) throws IOException {
        TemplateDescriptor restored = templates.restore(
                templateId,
                revision,
                expectedHead,
                principal.getName());
        redirect.addFlashAttribute("successMessage",
                "Restored as new Git version " + restored.headCommit().substring(0, 12));
        return "redirect:/admin/document-templates/" + templateId;
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
