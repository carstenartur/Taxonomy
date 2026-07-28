function roleName(scenario) {
  return String(scenario.env?.TAXONOMY_ROLE || '').trim().toLowerCase();
}

export function isolationGroupId(scenario) {
  if (['ui', 'accessibility', 'special-modes'].includes(scenario.suite)) {
    return 'shared-browser-nonmutating';
  }
  if (scenario.suite === 'role-state') {
    const role = roleName(scenario);
    if (!role) throw new Error(`Role-state scenario ${scenario.id} has no TAXONOMY_ROLE`);
    return `role-state-${role}`;
  }
  return `${scenario.suite}-${scenario.id}`;
}

export function groupScenarios(scenarios) {
  const groups = new Map();
  for (const scenario of scenarios) {
    const id = isolationGroupId(scenario);
    if (!groups.has(id)) groups.set(id, { id, scenarios: [] });
    groups.get(id).scenarios.push(scenario);
  }
  return [...groups.values()];
}
