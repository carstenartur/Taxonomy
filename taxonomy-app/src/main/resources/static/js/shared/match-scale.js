/** The same opaque match colour is used on rows, diagram nodes and legend samples. */
window.TaxonomyMatchScale = (function () {
    'use strict';

    function positive(score) {
        return Number.isFinite(score) && score > 0 && score <= 100;
    }

    function background(score) {
        const fraction = score / 100;
        const redBlue = Math.round(255 * (1 - fraction));
        const green = Math.round(255 - 127 * fraction);
        return '#' + [redBlue, green, redBlue].map(channel => channel.toString(16).padStart(2, '0')).join('');
    }

    function linearChannel(value) {
        const channel = parseInt(value, 16) / 255;
        return channel <= 0.04045 ? channel / 12.92 : Math.pow((channel + 0.055) / 1.055, 2.4);
    }

    function foreground(hex) {
        const luminance = 0.2126 * linearChannel(hex.slice(1, 3))
            + 0.7152 * linearChannel(hex.slice(3, 5)) + 0.0722 * linearChannel(hex.slice(5, 7));
        // Choose the stronger WCAG contrast ratio against the actual opaque surface.
        return (luminance + 0.05) / 0.05 >= 1.05 / (luminance + 0.05) ? '#000000' : '#ffffff';
    }

    function apply(element, score) {
        const fill = positive(score) ? background(score) : '';
        element.classList.toggle('tax-scored', !!fill);
        element.style.backgroundColor = fill;
        element.style.color = fill ? foreground(fill) : '';
    }

    function initializeLegends() {
        document.querySelectorAll('.score-legend .legend-box[data-score]').forEach(swatch => {
            apply(swatch, Number(swatch.dataset.score));
        });
    }

    document.addEventListener('DOMContentLoaded', initializeLegends, {once: true});
    return {positive, background, foreground, apply};
})();
