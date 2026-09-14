from pathlib import Path
root=Path('taxonomy-app/src/main/resources/static/js/core')
p=root/'taxonomy-analysis-progress.js'
s=p.read_text()
old='''            button: button,
            unavailable: function (reason) {'''
new='''            button: button,
            finished: function (status) {
                title.textContent = text('Analyse beendet', 'Analysis finished');
                state.textContent = status === 'SUCCESS'
                    ? text('Vollständiges Ergebnis empfangen.', 'Complete result received.')
                    : text('Teilergebnis oder Fehler empfangen; Einzelheiten stehen im Analysestatus.',
                        'Partial result or error received; see the analysis status for details.');
                button.disabled = true;
            },
            unavailable: function (reason) {'''
assert s.count(old)==1;s=s.replace(old,new)
old='''        view.button.addEventListener('click', monitor.cancel);
        active = monitor;'''
new='''        monitor.finish = function (status) {
            monitor.stop();
            view.finished(status);
        };
        view.button.addEventListener('click', monitor.cancel);
        active = monitor;'''
assert s.count(old)==1;s=s.replace(old,new);p.write_text(s)
p=root/'taxonomy-scoring.js';s=p.read_text()
old='''            .then(result => {
                if (progress) progress.stop();'''
new='''            .then(result => {
                if (progress) progress.finish(result.status);'''
assert s.count(old)==1;s=s.replace(old,new);p.write_text(s)
p=Path('taxonomy-app/src/test/java/com/taxonomy/AnalysisLiveProgressUiIT.java');s=p.read_text()
marker='    @Test void explicitCancellationIsPinnedAndNeverStartsAnotherAnalysis() {'
assert s.count(marker)==1
s=s.replace(marker,'''    @Test void authoritativeResponseFinishesThePanelBeforeTheNextPoll() {
        driver.executeScript("window.monitor.finish('SUCCESS')");
        assertThat(driver.findElement(By.id("analysisLiveProgress")).getText())
                .contains("Analyse beendet", "Vollständiges Ergebnis empfangen.");
        assertThat(driver.findElement(By.cssSelector("#analysisLiveProgress button")).isEnabled()).isFalse();
        assertThat(CANCEL_REQUESTS.get()).isZero();
    }

'''+marker);p.write_text(s)
p=Path('docs/de/CONFIGURATION_REFERENCE.md');s=p.read_text()
old='Warning < stop <= 98 percent; at least 1 MiB reserve; nonnegative pressure grace; positive deadline. Limits are checked cooperatively before calls and during rate-limit/retry waits. In-flight HTTP calls retain their configured timeout. Neither native memory nor one large allocation can be guaranteed safe by a heap sample. Completed scores are retained when the next step is stopped. Live telemetry is process-local, owner/workspace/repository/branch-scoped, limited to 4 active and 16 retained runs (10-minute terminal retention), 32 call previews and 8192 score entries. Preview text is limited to 8192 characters per prompt/response and loaded separately; omitted entries are counted. Durable portfolio jobs and semantic history remain separate and unchanged.'
new='Die Warnschwelle muss unter der Stoppschwelle liegen; diese darf höchstens 98 Prozent betragen. Mindestens 1 MiB Reserve, eine nichtnegative Drucktoleranz und eine positive Laufzeitgrenze sind erforderlich. Die Grenzen werden vor Aufrufen und während Ratenlimit- oder Wiederholungswartezeiten geprüft. Laufende HTTP-Aufrufe behalten ihr konfiguriertes Timeout. Eine Heap-Stichprobe garantiert weder Sicherheit für nativen Speicher noch für einzelne große Allokationen. Bereits abgeschlossene Bewertungen bleiben beim Stopp des nächsten Schritts erhalten. Die Live-Diagnose ist prozesslokal und strikt nach Benutzer, Workspace, Repository und Branch getrennt. Sie ist auf vier aktive und 16 vorgehaltene Läufe begrenzt; abgeschlossene Läufe bleiben höchstens zehn Minuten verfügbar. Höchstens 32 Aufrufvorschauen und 8192 Bewertungseinträge werden gehalten. Prompt- und Antwortvorschauen sind jeweils auf 8192 Zeichen begrenzt und werden separat nachgeladen; ausgelassene Einträge werden gezählt. Dauerhafte Portfolio-Jobs und die semantische Historie bleiben davon getrennt und unverändert.'
assert s.count(old)==1;s=s.replace(old,new);p.write_text(s)
print('Final response closes the live panel independently of the polling schedule')
