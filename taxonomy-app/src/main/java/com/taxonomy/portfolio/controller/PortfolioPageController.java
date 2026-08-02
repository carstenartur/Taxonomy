package com.taxonomy.portfolio.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Dedicated GUI entry point for project requirement and solution portfolio work. */
@Controller
public class PortfolioPageController {

    @GetMapping({"/projects", "/portfolio"})
    public String projects() {
        return "projects";
    }
}
