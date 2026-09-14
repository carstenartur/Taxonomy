from pathlib import Path
ROOT=Path('taxonomy-app/src/main')
def change(path,old,new):
    p=Path(path);s=p.read_text();assert s.count(old)==1,(str(path),old[:100],s.count(old))
    p.write_text(s.replace(old,new))
controller=ROOT/'java/com/taxonomy/analysis/controller/AnalysisApiController.java'
change(controller,'import java.util.concurrent.atomic.AtomicLong;','''import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.RejectedExecutionException;
import com.taxonomy.analysis.service.AnalysisRunControl;
import org.springframework.web.server.ResponseStatusException;''')
change(controller,'''        SseEmitter emitter = new SseEmitter(1_800_000L);''','''        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                && attributes.getRequest().getHeader("Last-Event-ID") != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A disconnected stream is not restarted. Observe the existing analysis operation instead.");
        }
        SseEmitter emitter = new SseEmitter(1_800_000L);
        AtomicBoolean completed = new AtomicBoolean();
        AtomicBoolean disconnected = new AtomicBoolean();
        AtomicReference<Thread> worker = new AtomicReference<>();
        Runnable cancelWorker = () -> {
            if (completed.get()) return;
            disconnected.set(true);
            Thread thread = worker.get();
            if (thread != null) thread.interrupt();
        };
        emitter.onTimeout(cancelWorker);
        emitter.onError(error -> cancelWorker.run());
        emitter.onCompletion(cancelWorker);''')
change(controller,'''        resolveWorkspaceContext(username);

        StreamRequirementAnalysisCommand''','''        WorkspaceContext streamContext = resolveWorkspaceContext(username);

        StreamRequirementAnalysisCommand''')
change(controller,'''        analysisExecutor.execute(() -> {
            try {
                streamRequirementAnalysisUseCase.stream(command, event -> {''','''        try {
        analysisExecutor.execute(() -> {
            worker.set(Thread.currentThread());
            try (var run = analysisProgressRegistry == null ? null
                    : analysisProgressRegistry.open(operationId, username, streamContext, null)) {
                if (disconnected.get()) Thread.currentThread().interrupt();
                AnalysisRunControl.checkpoint();
                streamRequirementAnalysisUseCase.stream(command, event -> {''')
change(controller,'''                    if (event instanceof AnalysisStreamEvent.Complete
                            || event instanceof AnalysisStreamEvent.Error) {
                        emitter.complete();
                    }''','''                    if (event instanceof AnalysisStreamEvent.Complete
                            || event instanceof AnalysisStreamEvent.Error) {
                        if (run != null) run.finish(String.valueOf(mapped.payload().getOrDefault("status", "ERROR")));
                        completed.set(true);
                        emitter.complete();
                    }''')
# Covers normal, exceptional, timeout and queue-rejection paths without leaking interrupt state into pooled threads.
change(controller,'''                emitter.complete();
            }
        });
        return emitter;''','''                emitter.complete();
            } finally {
                completed.set(true);
                worker.set(null);
                if (disconnected.get()) Thread.interrupted();
            }
        });
        } catch (RejectedExecutionException full) {
            completed.set(true);
            emitter.complete();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Analysis queue is full; retry after an active run finishes.");
        }
        return emitter;''')
# Call details must pass the generation fence again after network completion.
js=ROOT/'resources/static/js/core/taxonomy-analysis-progress.js'
change(js,'''            return response.json();
        }
        timer = options.setTimeout(poll, 0);''','''            var data = await response.json();
            now = options.context();
            if (now.workspaceId !== initial.workspaceId || now.generation !== initial.generation || now.invalidating) {
                throw new Error('STALE_ANALYSIS');
            }
            return data;
        }
        timer = options.setTimeout(poll, 0);''')
# Real warning state is independent from display-only clock updates.
change(js,"""                omitted.textContent = snapshot.omittedCalls
                    ? snapshot.omittedCalls + text(' ältere Diagnoseeinträge nicht mehr im Live-Puffer.', ' older diagnostic entries no longer retained.') : '';""",
"""                omitted.textContent = (snapshot.omittedCalls
                    ? snapshot.omittedCalls + text(' ältere Diagnoseeinträge nicht mehr im Live-Puffer.', ' older diagnostic entries no longer retained.') : '')
                    + (snapshot.scoresTruncated ? text(' Live-Bewertungen gekürzt; das Endergebnis bleibt vollständig.',
                        ' Live score preview truncated; the final result remains complete.') : '');""")
print('Fenced stream lifecycle and late call details; added explicit queue backpressure')
