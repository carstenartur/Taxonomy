package com.taxonomy.templates;

import com.taxonomy.templates.DocumentTemplateGitRepository.TemplateNotFoundException;
import com.taxonomy.templates.DocumentTemplateService.TemplatePartView;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;

/** Read-only comparison using the existing validated, canonical template service. */
@Controller
public final class DocumentTemplatePartComparisonController {
    private final DocumentTemplateService templates;

    public DocumentTemplatePartComparisonController(DocumentTemplateService templates) {
        this.templates = templates;
    }

    @GetMapping("/admin/document-templates/{templateId}/compare-part")
    public String comparePart(
            @PathVariable String templateId,
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam String partPath,
            Model model) throws IOException {
        if (from == null || to == null || !from.matches("[0-9a-f]{40}")
                || !to.matches("[0-9a-f]{40}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Two immutable revisions and a relative package-part path are required");
        }
        try {
            OoxmlTemplatePackageCodec.validatePartPath(partPath);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A relative package-part path is required", exception);
        }
        try {
            var comparison = templates.comparePart(templateId, from, to, partPath);
            var change = comparison.change();
            TemplatePartView before = comparison.before();
            TemplatePartView after = comparison.after();
            model.addAttribute("templateId", templateId);
            model.addAttribute("fromRevision", from);
            model.addAttribute("toRevision", to);
            model.addAttribute("partPath", partPath);
            model.addAttribute("partChange", change == null ? "UNCHANGED" : change.name());
            model.addAttribute("beforePart", before);
            model.addAttribute("afterPart", after);
            if (tooLarge(before) || tooLarge(after)) {
                model.addAttribute("comparisonMode", "LIMIT");
            } else if (notText(before) || notText(after)) {
                model.addAttribute("comparisonMode", "BINARY");
            } else {
                var result = TemplateTextDiff.compare(text(before), text(after));
                model.addAttribute("comparisonMode", result.limited() ? "LIMIT" : "TEXT");
                model.addAttribute("comparisonRows", result.rows());
                model.addAttribute("comparisonBlocks", result.blocks());
            }
            return "document-template-part-comparison";
        } catch (TemplateNotFoundException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "The selected template revision or package part does not exist", exception);
        }
    }

    private static boolean tooLarge(TemplatePartView part) {
        return part != null && part.size() > TemplateTextDiff.MAX_CHARACTERS;
    }

    private static boolean notText(TemplatePartView part) {
        // The existing part reader decodes UTF-8. Never present a lossy/UTF-16 preview as exact text.
        return part != null && (part.textContent() == null
                || part.textContent().indexOf('\0') >= 0 || part.textContent().indexOf('\uFFFD') >= 0);
    }

    private static String text(TemplatePartView part) {
        return part == null ? "" : part.textContent();
    }
}
