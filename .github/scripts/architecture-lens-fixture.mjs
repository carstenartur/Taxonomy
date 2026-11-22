import { readFile } from 'node:fs/promises';
import path from 'node:path';

export async function architectureLensMarkup(resources, prefix, actionId = prefix + 'Open') {
    const fragments = path.join(resources, 'templates/fragments');
    const [panel, controls] = await Promise.all([
        readFile(path.join(fragments, 'architecture-source-lens.html'), 'utf8'),
        readFile(path.join(fragments, 'architecture-diagram-controls.html'), 'utf8')
    ]);
    const body = template => template.split('<body>')[1].split('</body>')[0];
    return {
        panel: body(panel).replace(/th:id="\$\{idPrefix(?: \+ '([^']+)')?\}"/g,
            (_match, suffix) => `id="${prefix}${suffix || ''}"`),
        controls: body(controls).replace('th:id="${actionId}"', `id="${actionId}"`)
            .replace('th:id="${hoverId}"', `id="${prefix}Enabled"`)
            .replace('th:attr="data-architecture-lens-hover=${lensPrefix}"', `data-architecture-lens-hover="${prefix}"`)
    };
}
