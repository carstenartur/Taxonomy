document.addEventListener('DOMContentLoaded', function () {
    var refresh = document.getElementById('refreshPortfolioBtn');
    if (!refresh) return;
    // The primary module owns the actual refresh handler. This capture listener
    // only makes repeated clicks while an operation is active harmless.
    refresh.addEventListener('click', function (event) {
        if (document.getElementById('portfolioBusy')?.classList.contains('d-none') === false) {
            event.preventDefault();
            event.stopImmediatePropagation();
        }
    }, true);
});
