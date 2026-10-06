/* Print the displayed workbench and its current evidence without changing the graph. */
(function () {
    'use strict';

    const svg = document.getElementById('architectureCanvas');
    const button = document.getElementById('printArchitectureView');
    const fit = document.getElementById('fitArchitecture');
    const target = document.getElementById('architecturePrintDiagram');
    const shell = document.getElementById('architectureCanvasShell');
    if (!svg || !button || !fit || !target) return;

    let sequence = 0;
    let savedSourceSize = null;
    const sourceWidth = '--architecture-print-source-width';
    const sourceHeight = '--architecture-print-source-height';

    function graphBounds() {
        const viewport = svg.querySelector('.architecture-viewport');
        if (!viewport) return null;
        try {
            // getBBox is local to this group; its own D3 pan/zoom does not alter it.
            const bounds = viewport.getBBox();
            return [bounds.x, bounds.y, bounds.width, bounds.height].every(Number.isFinite)
                && bounds.width > 0 && bounds.height > 0 ? bounds : null;
        } catch (error) {
            return null;
        }
    }

    function isReady() {
        return !fit.disabled && Boolean(graphBounds());
    }

    function updateReadiness() {
        button.disabled = !isReady();
        if (button.disabled) target.replaceChildren();
    }

    function preserveSourceSize() {
        if (!shell) return;
        const viewBox = (svg.getAttribute('viewBox') || '').trim().split(/\s+/).map(Number);
        if (viewBox.length !== 4 || !viewBox.every(Number.isFinite)
                || viewBox[2] <= 0 || viewBox[3] <= 0) return;
        if (!savedSourceSize) {
            savedSourceSize = [shell.style.getPropertyValue(sourceWidth), shell.style.getPropertyValue(sourceHeight)];
        }
        // Keep the existing ResizeObserver's source viewport stable during print layout.
        // These properties apply only in print CSS; no live SVG attributes change.
        shell.style.setProperty(sourceWidth, viewBox[2] + 'px');
        shell.style.setProperty(sourceHeight, viewBox[3] + 'px');
    }

    function cleanClone(clone) {
        const nodes = [clone, ...clone.querySelectorAll('*')];
        const ids = new Map();
        const prefix = 'architecture-print-' + (++sequence) + '-';
        nodes.forEach(node => {
            const id = node.getAttribute('id');
            if (id) {
                ids.set(id, prefix + id);
                node.setAttribute('id', prefix + id);
            }
        });
        function references(value) {
            return value.replace(/url\(\s*(['"]?)#([^\s'")]+)\1\s*\)/g,
                (original, quote, id) => ids.has(id) ? 'url(' + quote + '#' + ids.get(id) + quote + ')' : original);
        }
        nodes.forEach(node => {
            node.removeAttribute('tabindex');
            node.removeAttribute('role');
            node.removeAttribute('aria-pressed');
            node.removeAttribute('aria-selected');
            node.getAttributeNames().forEach(name => {
                if (/^on/i.test(name)) { node.removeAttribute(name); return; }
                const value = node.getAttribute(name);
                if ((name === 'href' || name === 'xlink:href') && ids.has(value.slice(1)) && value.startsWith('#')) {
                    node.setAttribute(name, '#' + ids.get(value.slice(1)));
                } else if (name === 'aria-labelledby' || name === 'aria-describedby') {
                    node.setAttribute(name, value.split(/\s+/).map(id => ids.get(id) || id).join(' '));
                } else {
                    node.setAttribute(name, references(value));
                }
            });
            if (String(node.tagName).toLowerCase() === 'style') node.textContent = references(node.textContent);
        });
        clone.setAttribute('role', 'img');
        clone.setAttribute('aria-labelledby', 'architectureTitle architecturePrintScope');
        clone.setAttribute('preserveAspectRatio', 'xMidYMid meet');
    }

    function preparePrint() {
        target.replaceChildren();
        if (fit.disabled) return false;
        preserveSourceSize();
        const bounds = graphBounds();
        if (!bounds) return false;
        const clone = svg.cloneNode(true);
        clone.querySelector('.architecture-viewport').removeAttribute('transform');
        cleanClone(clone);
        const margin = 28;
        clone.setAttribute('viewBox', [bounds.x - margin, bounds.y - margin,
            bounds.width + 2 * margin, bounds.height + 2 * margin].join(' '));
        target.replaceChildren(clone);
        return true;
    }

    button.addEventListener('click', function () {
        updateReadiness();
        if (!button.disabled) window.print();
    });
    window.addEventListener('beforeprint', preparePrint);
    window.addEventListener('afterprint', function () {
        target.replaceChildren();
        if (shell && savedSourceSize) {
            [sourceWidth, sourceHeight].forEach((property, index) => {
                if (savedSourceSize[index]) shell.style.setProperty(property, savedSourceSize[index]);
                else shell.style.removeProperty(property);
            });
            savedSourceSize = null;
        }
    });
    new MutationObserver(updateReadiness).observe(fit, {attributes: true, attributeFilter: ['disabled']});
    updateReadiness();
}());
