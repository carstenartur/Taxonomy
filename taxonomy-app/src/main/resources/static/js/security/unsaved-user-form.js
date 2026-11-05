/* Preserve edits when using the shared navigation from user-management forms. */
(function () {
    'use strict';
    var forms = ['userProfileForm', 'userPasswordForm'].map(function (id) { return document.getElementById(id); }).filter(Boolean);
    if (!forms.length) return;

    // If the browser prefilled a secret before this script ran, remember only
    // whether it was subsequently edited, never its original or current value.
    var initiallyFilled = new WeakSet(), editedPrefill = new WeakSet();
    forms.forEach(function (form) {
        Array.from(form.elements).forEach(function (field) {
            if ((field.type === 'password' || field.type === 'file') && field.value) {
                initiallyFilled.add(field);
                field.addEventListener('input', function () { editedPrefill.add(field); });
                field.addEventListener('change', function () { editedPrefill.add(field); });
            }
        });
    });
    function snapshot(form) {
        return JSON.stringify(Array.from(form.elements).filter(function (field) {
            return field.name && !field.disabled && !/^(hidden|button|submit|reset)$/.test(field.type);
        }).map(function (field) {
            var value;
            if (field.type === 'password' || field.type === 'file') value = [Boolean(field.value), initiallyFilled.has(field) && editedPrefill.has(field)];
            else if (field.type === 'checkbox' || field.type === 'radio') value = field.checked;
            else if (field.multiple && field.options) value = Array.from(field.selectedOptions, function (option) { return option.value; });
            else value = field.value;
            return [field.name, value];
        }));
    }
    // Passwords are represented only by presence and are never copied into the baseline.
    var baseline = new Map(forms.map(function (form) { return [form, snapshot(form)]; }));
    var submitting = null;
    forms.forEach(function (form) {
        form.addEventListener('submit', function (event) {
            submitting = form;
            // A later listener can cancel submission; keep the guard in that case.
            window.setTimeout(function () { if (event.defaultPrevented && submitting === form) submitting = null; }, 0);
        });
    });
    window.addEventListener('beforeunload', function (event) {
        var committed = submitting;
        submitting = null; // A canceled navigation must guard the form again.
        if (!forms.some(function (form) { return form !== committed && snapshot(form) !== baseline.get(form); })) return;
        event.preventDefault();
        event.returnValue = '';
    });
}());
