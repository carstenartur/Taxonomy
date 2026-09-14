from pathlib import Path
p=Path('taxonomy-app/src/main/java/com/taxonomy/analysis/controller/AnalysisApiController.java')
s=p.read_text()
old='''        Runnable cancelWorker = () -> {
            if (completed.get()) return;
            disconnected.set(true);
            Thread thread = worker.get();
            if (thread != null) thread.interrupt();
        };'''
new='''        Runnable cancelWorker = () -> {
            synchronized (worker) {
                if (completed.get()) return;
                disconnected.set(true);
                Thread thread = worker.get();
                if (thread != null) thread.interrupt();
            }
        };'''
assert old in s;s=s.replace(old,new)
old='''                completed.set(true);
                worker.set(null);
                if (disconnected.get()) Thread.interrupted();'''
new='''                synchronized (worker) {
                    completed.set(true);
                    worker.set(null);
                    if (disconnected.get()) Thread.interrupted();
                }'''
assert old in s;s=s.replace(old,new)
old='if (run != null) run.finish(String.valueOf(mapped.payload().getOrDefault("status", "ERROR")));'
new='''if (run != null) run.finish(event instanceof AnalysisStreamEvent.Complete complete
                                ? complete.status() : ((AnalysisStreamEvent.Error) event).status());'''
assert old in s;s=s.replace(old,new)
p.write_text(s)
print('Worker completion/cancellation uses one lifecycle fence')
