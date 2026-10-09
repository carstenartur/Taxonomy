/* Host-owned capability client: data only, no plugin script or URL execution. */
(function () {
    'use strict';
    function formats(values, family) {
        if (!Array.isArray(values)) return [];
        const ids = new Set();
        return values.filter(value => {
            if (!value || (family != null && value.reportType !== family)
                    || !/^[A-Za-z0-9._-]{1,128}$/.test(value.id)
                    || !/^[A-Za-z0-9][A-Za-z0-9._-]{0,31}$/.test(value.fileExtension)
                    || !/^[\w!#$&^.+-]+\/[\w!#$&^.+-]+(?:\s*;[^\r\n]*)?$/.test(value.contentType)
                    || typeof value.displayName !== 'string' || value.displayName.length > 256
                    || !value.plugin || !/^[a-f0-9]{64}$/.test(value.plugin.artifactSha256)
                    || ids.has((value.reportType || '') + ':' + value.id)) return false;
            ids.add((value.reportType || '') + ':' + value.id); return true;
        }).map(value => Object.freeze({...value, plugin:Object.freeze({...value.plugin})}));
    }
    async function load() {
        const response = await fetch('/api/capabilities', {credentials:'same-origin', cache:'no-store', headers:{Accept:'application/json'}});
        if (!response.ok || response.redirected) throw new Error('Capabilities unavailable (HTTP ' + response.status + ')');
        const data = await response.json();
        return Object.freeze({revision:data.revision, features:Object.freeze(Array.isArray(data.features) ? [...data.features] : []),
            exports:Object.freeze(formats(data.exports)), reports:Object.freeze(formats(data.reports))});
    }
    window.TaxonomyCapabilities = Object.freeze({load, formats});
}());
