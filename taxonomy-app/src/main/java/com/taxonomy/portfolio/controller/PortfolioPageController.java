package com.taxonomy.portfolio.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/** Dedicated GUI entry points for project portfolio work. */
@Controller
public class PortfolioPageController {

    @GetMapping({"/projects", "/portfolio"})
    public String projects() {
        return "projects";
    }

    @GetMapping("/projects/{projectId}/requirements/{requirementId}")
    public String requirementDetail(@PathVariable Long projectId,
                                    @PathVariable Long requirementId) {
        return "requirement-detail";
    }

    @GetMapping("/projects/{projectId}/matrices")
    public String matrices(@PathVariable Long projectId) {
        return "portfolio-matrices";
    }
}
