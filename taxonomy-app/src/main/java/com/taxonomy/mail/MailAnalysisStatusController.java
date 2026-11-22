package com.taxonomy.mail;

import com.taxonomy.shared.features.ConditionalOnFeature;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;

import java.util.regex.Pattern;

/** The token grants read-only access to one job's operational status. */
@Controller
@RequestMapping("/mail-analysis/jobs")
@ConditionalOnProperty(name = "taxonomy.mail.enabled", havingValue = "true")
@ConditionalOnFeature({"analysis", "architecture", "reporting"})
class MailAnalysisStatusController {

    private static final Pattern TOKEN = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
    private final MailAnalysisQueue queue;

    MailAnalysisStatusController(MailAnalysisQueue queue) {
        this.queue = queue;
    }

    @GetMapping("/{token}")
    String page(@PathVariable String token, Model model, HttpServletResponse response) {
        model.addAttribute("status", lookup(token, response));
        model.addAttribute("jobToken", token);
        return "mail-analysis-status";
    }

    @GetMapping("/{token}/status")
    @ResponseBody
    JobProgress status(@PathVariable String token, HttpServletResponse response) {
        return lookup(token, response);
    }

    private JobProgress lookup(String token, HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("X-Robots-Tag", "noindex, nofollow");
        if (!TOKEN.matcher(token).matches()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var job = queue.status(token).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        var state = job.state().name();
        return new JobProgress(state, job.position(), job.queuedJobs(), "COMPLETED".equals(state) || "FAILED".equals(state));
    }

    record JobProgress(String state, Integer position, int queuedJobs, boolean finished) { }
}
