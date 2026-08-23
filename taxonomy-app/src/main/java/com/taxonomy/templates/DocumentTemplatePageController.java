package com.taxonomy.templates;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/** Serves the administrator-facing template management workspace. */
@Controller
public class DocumentTemplatePageController {

    @Value("${taxonomy.document-templates.direct-word-enabled:true}")
    private boolean directWordEnabled = true;

    @GetMapping("/admin/document-templates")
    public String documentTemplates(Model model) {
        model.addAttribute("directWordEnabled", directWordEnabled);
        return documentTemplates();
    }

    String documentTemplates() {
        return "document-templates";
    }
}
