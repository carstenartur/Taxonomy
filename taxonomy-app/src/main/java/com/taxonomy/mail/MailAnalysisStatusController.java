package com.taxonomy.mail;

import com.taxonomy.shared.features.ConditionalOnFeature;
import io.swagger.v3.oas.annotations.Operation;
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

    static final Pattern TOKEN = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
    private final MailAnalysisQueue queue;

    MailAnalysisStatusController(MailAnalysisQueue queue) {
        this.queue = queue;
    }

    @GetMapping("/{token}")
    String page(@PathVariable String token, Model model, HttpServletResponse response) {
        privateStatusHeaders(response);
        if (TOKEN.matcher(token).matches()) {
            var status = queue.status(token);
            if (status.isPresent()) {
                model.addAttribute("status", status.get());
                model.addAttribute("jobToken", token);
                return "mail-analysis-status";
            }
        }
        response.setStatus(HttpStatus.NOT_FOUND.value());
        return "mail-analysis-link-unavailable";
    }

    @GetMapping("/{token}/status")
    @ResponseBody
    @Operation(summary = "Read the status of an email analysis job",
            description = "Returns the receipt job, current queue counts and anonymous active jobs with classified analysis settings. "
                    + "The estimated wait uses recent measured analysis times and is null when unavailable. "
                    + "No-store responses include no mail contents, addresses, attachments or bearer tokens. "
                    + "Invalid, unknown, expired or rejected tokens return HTTP 404. Available when mail analysis is enabled.")
    MailAnalysisQueue.Snapshot status(@PathVariable String token, HttpServletResponse response) {
        privateStatusHeaders(response);
        if (!TOKEN.matcher(token).matches()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return queue.snapshot(token).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private static void privateStatusHeaders(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("X-Robots-Tag", "noindex, nofollow");
    }
}
