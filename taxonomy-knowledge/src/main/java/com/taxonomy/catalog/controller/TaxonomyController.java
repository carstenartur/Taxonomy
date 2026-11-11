package com.taxonomy.catalog.controller;

import com.taxonomy.dto.TaxonomyNodeDto;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.dsl.validation.RelationMetamodel;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;

@Controller
public class TaxonomyController {

    private final TaxonomyService taxonomyService;

    public TaxonomyController(TaxonomyService taxonomyService) {
        this.taxonomyService = taxonomyService;
    }

    @GetMapping("/")
    public String index(Model model) {
        List<TaxonomyNodeDto> tree = taxonomyService.getFullTree();
        model.addAttribute("rootCount", tree.size());
        model.addAttribute("relationTypes", RelationMetamodel.relationTypes().stream().sorted().toList());
        return "index";
    }
}
