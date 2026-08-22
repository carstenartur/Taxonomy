package com.taxonomy.templates;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Serves the administrator-facing template management workspace. */
@Controller
public class DocumentTemplatePageController {

    @GetMapping("/admin/document-templates")
    public String documentTemplates() {
        return "document-templates";
    }
}
