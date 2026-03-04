package com.nato.taxonomy.controller;

import com.nato.taxonomy.dto.TaxonomyNodeDto;
import com.nato.taxonomy.service.TaxonomyService;
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
        return "index";
    }
}
