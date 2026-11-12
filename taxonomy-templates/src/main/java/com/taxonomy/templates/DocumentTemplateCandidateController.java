package com.taxonomy.templates;

import com.taxonomy.templates.api.TemplateConflictException;
import com.taxonomy.templates.api.TemplateNotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.Principal;
import java.util.Map;

@Controller
@RequestMapping("/admin/document-templates/{templateId}/candidates")
class DocumentTemplateCandidateController {
    private static final String PAGE = "document-template-candidate";
    private final DocumentTemplateCandidateService candidates;
    private final DocumentTemplateService templates;

    DocumentTemplateCandidateController(DocumentTemplateCandidateService candidates, DocumentTemplateService templates) {
        this.candidates = candidates;
        this.templates = templates;
    }

    @GetMapping
    String form(@PathVariable String templateId, Model model) throws IOException {
        model.addAttribute("template", templates.describeCurrent(templateId));
        return PAGE;
    }

    @PostMapping
    String stage(@PathVariable String templateId, @RequestParam String displayName, @RequestParam String expectedHead,
                 @RequestParam MultipartFile file, Principal actor, HttpServletResponse response, Model model) throws IOException {
        try {
            if (file.isEmpty() || file.getSize() > OoxmlTemplatePackageCodec.MAX_ARCHIVE_BYTES) {
                throw new IllegalArgumentException();
            }
            var candidate = candidates.stage(templateId, displayName, expectedHead, file.getBytes(), actor.getName());
            return "redirect:/admin/document-templates/" + templateId + "/candidates/" + candidate.version();
        } catch (IllegalArgumentException | TemplateConflictException rejected) {
            response.setStatus(rejected instanceof TemplateConflictException ? 412 : 400);
            model.addAttribute("candidateError", rejected instanceof TemplateConflictException ? "changed" : "invalid");
            return form(templateId, model);
        }
    }

    @GetMapping("/{version}")
    String detail(@PathVariable String templateId, @PathVariable String version, Model model) throws IOException {
        model.addAttribute("candidate", candidates.describe(templateId, version));
        return form(templateId, model);
    }

    @PostMapping("/{version}/activate")
    String activate(@PathVariable String templateId, @PathVariable String version, Principal actor,
                    HttpServletResponse response, Model model) throws IOException {
        try {
            candidates.activate(templateId, version, actor.getName());
            return "redirect:/admin/document-templates/" + templateId;
        } catch (IllegalArgumentException | TemplateConflictException rejected) {
            response.setStatus(rejected instanceof TemplateConflictException ? 412 : 400);
            model.addAttribute("candidateError", rejected instanceof TemplateConflictException ? "changed" : "sample");
            return detail(templateId, version, model);
        }
    }

    @Operation(summary = "Download the candidate's sample document",
            description = "Administrator access. Returns the DOCX rendered from this exact staged template revision as a private attachment.")
    @GetMapping("/{version}/sample.docx")
    ResponseEntity<byte[]> sample(@PathVariable String templateId, @PathVariable String version) throws IOException {
        return attachment("sample.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                candidates.sample(templateId, version));
    }

    @Operation(summary = "Download the original staged template",
            description = "Administrator access. Returns the original uploaded DOTX bytes for the selected template and candidate revision.")
    @GetMapping("/{version}/original.dotx")
    ResponseEntity<byte[]> original(@PathVariable String templateId, @PathVariable String version) throws IOException {
        return attachment("original.dotx", com.taxonomy.templates.api.OoxmlTemplateMediaTypes.DOTX_MEDIA_TYPE,
                candidates.original(templateId, version));
    }

    @Operation(summary = "Read the candidate validation report",
            description = "Administrator access. Returns the validation evidence for the specified staged template revision before activation.")
    @GetMapping("/{version}/validation.json")
    ResponseEntity<Map<String, Object>> validation(@PathVariable String templateId, @PathVariable String version) throws IOException {
        var candidate = candidates.describe(templateId, version);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=validation.json")
                .body(Map.of("templateId", templateId, "candidateVersion", version, "baseVersion", candidate.baseVersion(),
                        "originalSha256", candidate.originalSha256(), "packageSha256", candidate.packageSha256(),
                        "packageValidation", "PASSED", "sample", candidate.ready() ? "PASSED" : "FAILED"));
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(TemplateNotFoundException.class)
    ResponseEntity<Void> missing() {
        return ResponseEntity.notFound().build();
    }

    private static ResponseEntity<byte[]> attachment(String name, String type, byte[] content) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType(type))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + name).body(content);
    }
}
