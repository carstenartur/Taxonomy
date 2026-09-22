/** One editor's live validation requests, including visibility, navigation and stale results. */
export function createDslValidationSource(apiClient, lifecycle) {
    let active = null;
    let suspended = false;
    let disposed = false;
    let wasVisible = false;

    function available(view) {
        return !disposed && !suspended && view.inView === true && view.dom.isConnected;
    }

    function cancel() {
        const previous = active;
        active = null;
        previous?.abort();
    }

    function hide() {
        suspended = true;
        wasVisible = false;
        cancel();
    }

    function show() {
        suspended = false;
    }

    lifecycle.addEventListener('pagehide', hide);
    lifecycle.addEventListener('pageshow', show);

    return {
        // CodeMirror reports visibility through its measured view state. Schedule
        // one ordinary debounced lint when an editor returns, not on every scroll
        // or on the transaction that publishes the resulting diagnostics.
        needsRefresh({ view }) {
            const visible = available(view);
            const refresh = visible && !wasVisible;
            wasVisible = visible;
            if (!visible) cancel();
            return refresh;
        },
        async lint(view) {
            cancel();
            wasVisible = available(view);
            if (!wasVisible) return [];
            const controller = new AbortController();
            active = controller;
            const document = view.state.doc;
            const text = document.toString();
            try {
                const response = await apiClient.request('/api/dsl/validate', {
                    method: 'POST',
                    headers: { 'Content-Type': 'text/plain' },
                    body: text
                }, { timeoutMillis: 10000, signal: controller.signal });
                const data = await response.json();
                if (!available(view)) {
                    wasVisible = false;
                    return [];
                }
                if (controller.signal.aborted || active !== controller
                        || view.state.doc !== document) return [];
                const diagnostics = [];
                function add(message, severity) {
                    message = typeof message === 'string' ? message : JSON.stringify(message);
                    let from = 0;
                    let to = Math.min(text.length, 1);
                    const match = message.match(/line\s+(\d+)/i);
                    if (match) {
                        const lineNumber = Number.parseInt(match[1], 10);
                        if (lineNumber >= 1 && lineNumber <= document.lines) {
                            const line = document.line(lineNumber);
                            from = line.from;
                            to = line.to;
                        }
                    }
                    diagnostics.push({ from, to, severity, message });
                }
                for (const error of data.errors || []) add(error, 'error');
                for (const warning of data.warnings || []) add(warning, 'warning');
                return diagnostics;
            } catch {
                return [];
            } finally {
                if (active === controller) active = null;
            }
        },
        dispose() {
            disposed = true;
            wasVisible = false;
            cancel();
            lifecycle.removeEventListener('pagehide', hide);
            lifecycle.removeEventListener('pageshow', show);
        }
    };
}
