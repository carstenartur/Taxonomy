#!/usr/bin/env python3
"""One-time extraction of inline CSRF logic and identifier assistance."""

from pathlib import Path


INDEX = Path("taxonomy-app/src/main/resources/templates/index.html")

CSRF_BLOCK = '''<script>
    /* ── CSRF token support for fetch() ──────────────────────────────────────────
       Reads the Spring Security CSRF token from meta tags and automatically
       attaches it to all non-GET/HEAD fetch requests. */
    (function() {
        const csrfToken  = document.querySelector('meta[name="_csrf"]')?.content;
        const csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.content;
        if (!csrfToken || !csrfHeader) return;
        const originalFetch = window.fetch;
        window.fetch = function(url, init) {
            init = init || {};
            const method = (init.method || 'GET').toUpperCase();
            if (method !== 'GET' && method !== 'HEAD') {
                init.headers = init.headers instanceof Headers
                    ? init.headers : new Headers(init.headers || {});
                if (!init.headers.has(csrfHeader)) {
                    init.headers.set(csrfHeader, csrfToken);
                }
            }
            return originalFetch.call(this, url, init);
        };
    })();
</script>
'''

TRANSFER_SCRIPT = '<script th:src="@{/js/versioning/taxonomy-context-transfer.js}"></script>\n'
ASSISTANCE_SCRIPT = '<script th:src="@{/js/versioning/taxonomy-recognition-assistance.js}"></script>\n'


def main() -> None:
    text = INDEX.read_text(encoding="utf-8")
    if CSRF_BLOCK in text:
        text = text.replace(CSRF_BLOCK, "", 1)
    elif "CSRF token support for fetch()" in text:
        raise SystemExit("Inline CSRF block changed unexpectedly; refusing partial patch")

    if ASSISTANCE_SCRIPT not in text:
        if TRANSFER_SCRIPT not in text:
            raise SystemExit("Cannot locate context-transfer script anchor")
        text = text.replace(TRANSFER_SCRIPT, TRANSFER_SCRIPT + ASSISTANCE_SCRIPT, 1)

    INDEX.write_text(text, encoding="utf-8")


if __name__ == "__main__":
    main()
