/* A disclosure list for the selected model revision. Virtual catalogue parents are navigation only. */
(function () {
    'use strict';

    const PAGE_SIZE = 50;
    const ROOT = Symbol('top level');

    function buildIndex(elements, hierarchy, t) {
        const nodes = new Map(), parents = new Map(), children = new Map();
        for (const item of elements) {
            nodes.set(item.id, {id:item.id, label:item.title || item.id, item});
        }
        for (const item of elements) {
            const parent = Object.hasOwn(hierarchy, item.id) && hierarchy[item.id]?.parentId;
            if (!parent || parent === item.id) continue;
            if (!nodes.has(parent)) nodes.set(parent, {id:parent, label:t('editor.tree.catalogueRoot', parent)});
            parents.set(item.id, parent);
        }

        // A legacy cycle must not make an entire branch unreachable.
        const visited = new Set();
        for (const id of parents.keys()) {
            const path = new Set();
            let cursor = id;
            while (parents.has(cursor) && !visited.has(cursor)) {
                if (path.has(cursor)) { parents.delete(cursor); break; }
                path.add(cursor);
                cursor = parents.get(cursor);
            }
            for (const seen of path) visited.add(seen);
        }

        for (const node of nodes.values()) {
            const parent = parents.get(node.id) ?? ROOT;
            if (!children.has(parent)) children.set(parent, []);
            children.get(parent).push(node);
        }
        function order(node) {
            const value = node.item?.extensions?.['x-catalogue-order'];
            return value === undefined || value === null || value === '' || !Number.isFinite(Number(value))
                ? Infinity : Number(value);
        }
        const compare = new Intl.Collator(undefined, {numeric:true});
        for (const siblings of children.values()) {
            siblings.sort((a, b) => order(a) - order(b) || compare.compare(a.label, b.label) || compare.compare(a.id, b.id));
        }
        return {nodes, parents, children};
    }

    function create({root, t, onSelect}) {
        const document = root.ownerDocument;
        const expanded = new Set(), queryCollapsed = new Set(), pages = new Map();
        let index, elements, hierarchy, selected, query = '', enteredQuery = '', extraSearchSelection;
        let focusedControls = new Map();

        function ancestors(id, output) {
            for (let parent = index.parents.get(id); parent !== undefined && !output.has(parent);
                 parent = index.parents.get(parent)) output.add(parent);
        }

        function button(kind, id) {
            const control = document.createElement('button');
            control.type = 'button';
            control.dataset[kind] = id;
            focusedControls.set(kind + '/' + id, control);
            return control;
        }

        function searchPaths() {
            if (!query) return null;
            const included = new Set(), open = new Set();
            for (const node of index.nodes.values()) {
                const item = node.item;
                if (!item) continue;
                const searchable = [item.id, item.title, item.type, item.type && t('editor.type.' + item.type)].filter(Boolean).join(' ').toLocaleLowerCase();
                if (node.id === extraSearchSelection || searchable.includes(query)) {
                    included.add(node.id);
                    ancestors(node.id, open);
                }
            }
            for (const ancestor of open) included.add(ancestor);
            return {included, open};
        }

        function paint(revealSelected, returnFocus) {
            const active = document.activeElement;
            if (!returnFocus && active && root.contains(active)) {
                for (const [key, control] of focusedControls) {
                    if (active === control) { returnFocus = key; break; }
                }
            }
            focusedControls = new Map();
            const search = searchPaths(), selectedPath = new Set();
            if (selected) {
                selectedPath.add(selected);
                ancestors(selected, selectedPath);
            }

            const list = document.createElement('ul');
            list.className = 'responsibility-branches';
            const pending = [{parent:ROOT, list}];
            while (pending.length) {
                const group = pending.pop();
                const siblings = index.children.get(group.parent) || [];
                const visible = search ? siblings.filter(child => search.included.has(child.id)) : siblings;
                const shown = pages.get(group.parent) || PAGE_SIZE;
                for (const [position, node] of visible.entries()) {
                    if (position >= shown && !selectedPath.has(node.id)) continue;
                    const li = document.createElement('li'), row = document.createElement('div');
                    row.className = 'responsibility-row';
                    const childNodes = index.children.get(node.id);
                    const hasChildren = childNodes?.some(child => !search || search.included.has(child.id));
                    const open = search ? search.open.has(node.id) && !queryCollapsed.has(node.id) : expanded.has(node.id);
                    if (hasChildren) {
                        const toggle = button('toggleId', node.id);
                        toggle.className = 'responsibility-toggle';
                        toggle.setAttribute('aria-expanded', String(open));
                        const name = node.item && node.label !== node.id ? node.label + ' (' + node.id + ')' : node.label;
                        toggle.setAttribute('aria-label', t(open ? 'editor.tree.collapse' : 'editor.tree.expand', name));
                        toggle.textContent = open ? '▾' : '▸';
                        toggle.addEventListener('click', () => {
                            if (search) { if (open) queryCollapsed.add(node.id); else queryCollapsed.delete(node.id); }
                            else if (open) expanded.delete(node.id);
                            else expanded.add(node.id);
                            paint(false, 'toggleId/' + node.id);
                        });
                        row.append(toggle);
                    }
                    if (node.item) {
                        const select = button('selectId', node.id);
                        select.className = 'responsibility-select';
                        select.setAttribute('aria-current', String(node.id === selected));
                        select.textContent = node.label;
                        if (node.label !== node.id) {
                            const id = document.createElement('small'); id.textContent = node.id;
                            select.append(id);
                        }
                        select.addEventListener('click', () => onSelect(node.id));
                        row.append(select);
                    } else {
                        const label = document.createElement('span'); label.textContent = node.label; row.append(label);
                    }
                    li.append(row); group.list.append(li);
                    if (hasChildren && open) {
                        const nested = document.createElement('ul'); li.append(nested);
                        pending.push({parent:node.id, list:nested});
                    }
                }
                if (visible.length > shown) {
                    const li = document.createElement('li'), more = button('moreId', group.parent === ROOT ? '' : group.parent);
                    more.textContent = t('editor.tree.more', Math.min(PAGE_SIZE, visible.length - shown));
                    more.addEventListener('click', () => {
                        pages.set(group.parent, shown + PAGE_SIZE);
                        const next = visible[shown];
                        paint(false, (next.item ? 'selectId/' : 'toggleId/') + next.id);
                    });
                    li.append(more); group.list.append(li);
                }
            }
            if (list.children.length) root.replaceChildren(list);
            else {
                const empty = document.createElement('p');
                empty.setAttribute('role', 'status');
                empty.textContent = query ? t('editor.tree.noMatches', enteredQuery) : t('editor.tree.empty');
                root.replaceChildren(empty);
            }
            focusedControls.get(returnFocus)?.focus({preventScroll:true});
            const current = revealSelected && focusedControls.get('selectId/' + selected);
            if (current && root.clientHeight && root.scrollHeight > root.clientHeight) {
                const row = current.getBoundingClientRect(), box = root.getBoundingClientRect();
                if (row.top < box.top) root.scrollTop += row.top - box.top;
                else if (row.bottom > box.bottom) root.scrollTop += row.bottom - box.bottom;
            }
        }

        function render(data) {
            const changed = !index || elements !== data.elements || hierarchy !== data.hierarchy;
            if (changed) {
                elements = data.elements; hierarchy = data.hierarchy;
                index = buildIndex(elements || [], hierarchy || {}, t);
            }
            enteredQuery = String(data.query || '').trim();
            const nextQuery = enteredQuery.toLocaleLowerCase();
            const selectionChanged = selected !== data.selected;
            if (query !== nextQuery) {
                queryCollapsed.clear(); pages.clear();
                extraSearchSelection = null;
            } else if (nextQuery && selectionChanged) {
                extraSearchSelection = data.selected;
                queryCollapsed.clear();
            }
            selected = data.selected; query = nextQuery;
            if (selected && (selectionChanged || changed)) {
                const path = new Set();
                ancestors(selected, path);
                for (const ancestor of path) expanded.add(ancestor);
            }
            paint(selectionChanged, null);
        }

        function reset() {
            index = null; elements = null; hierarchy = null; selected = null; query = ''; extraSearchSelection = null;
            expanded.clear(); pages.clear(); queryCollapsed.clear(); focusedControls.clear();
            root.replaceChildren();
        }
        return {render, reset};
    }

    window.TaxonomyResponsibilityTree = {create};
}());
