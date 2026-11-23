package com.taxonomy.catalog.controller;

import com.taxonomy.dsl.validation.RelationMetamodel;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class TaxonomyController {
    @GetMapping("/")
    public String index(Model model) {
        // Render the shell independently of catalogue loading and search recovery.
        // The browser obtains the tree through the readiness-guarded /api/taxonomy route.
        model.addAttribute("relationTypes", RelationMetamodel.relationTypes().stream().sorted().toList());
        return "index";
    }
}
