package com.taxonomy.workspace.service;

import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import java.io.IOException;

/** Separate transactions make prepared Git identities recoverable after process or database failure. */
@Component
public class BranchAdoptionWorker {
    private final BranchAdoptionService rules;

    public BranchAdoptionWorker(BranchAdoptionService rules) {
        this.rules = rules;
    }

    /** An explicit retry can use a fresh interactive grant that an unattended worker cannot renew. */
    public BranchAdoptionRule.View retryReviewed(RepositoryContext target, BranchAdoptionRule.View admitted,
            String source) {
        var current = admitted;
        for (int phase = 0; phase < 3 && current.enabled(); phase++) {
            if (!java.util.Set.of("ENABLED", "SELECTED", "PREPARED").contains(current.status())
                    || !source.equals(current.sourceCommit()))
                break;
            var due = new BranchAdoptionService.Due(BranchAdoptionRule.key(target), current.revision());
            try {
                rules.advance(due, false, source);
            } catch (AccessDeniedException | WorkspaceFetchAuthority.FreshLoginRequired denied) {
                rules.failed(due, true);
            } catch (IOException | RuntimeException failed) {
                rules.failed(due, NestedExceptionUtils.getMostSpecificCause(failed) instanceof AccessDeniedException);
            }
            var next = rules.inspect(target);
            if (next.revision() == current.revision())
                break;
            current = next;
        }
        return current;
    }

    @Scheduled(fixedDelayString = "${taxonomy.workspace.adoption-poll:15000}")
    public void checkEnabledRules() {
        for (var due : rules.due()) {
            try {
                rules.advance(due);
            } catch (AccessDeniedException | WorkspaceFetchAuthority.FreshLoginRequired denied) {
                rules.failed(due, true);
            } catch (IOException | RuntimeException failure) {
                LoggerFactory.getLogger(BranchAdoptionWorker.class).info("Branch adoption paused for rule {}",
                        due.id());
                rules.failed(due, NestedExceptionUtils.getMostSpecificCause(failure) instanceof AccessDeniedException);
            }
        }
    }
}
