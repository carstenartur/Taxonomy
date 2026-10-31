function roleName(scenario) {
  return String(scenario.env?.TAXONOMY_ROLE || '').trim().toLowerCase();
}

function servletContextPath(scenario) {
  const contextPath = scenario.contextPath ?? '';
  if (typeof contextPath !== 'string'
      || (contextPath !== '' && !/^\/[a-zA-Z0-9_-]+$/.test(contextPath))) {
    throw new Error(`Invalid UI servlet context path: ${String(contextPath)}`);
  }
  return contextPath;
}

export function isolationGroupId(scenario) {
  const contextPath = servletContextPath(scenario);
  const suffix = contextPath ? `-context-${contextPath.slice(1)}` : '';
  if (['ui', 'accessibility'].includes(scenario.suite)) {
    return `shared-browser-nonmutating${suffix}`;
  }
  if (scenario.suite === 'role-state') {
    const role = roleName(scenario);
    if (!role) throw new Error(`Role-state scenario ${scenario.id} has no TAXONOMY_ROLE`);
    return `role-state-${role}${suffix}`;
  }
  return `${scenario.suite}-${scenario.id}${suffix}`;
}

export function groupScenarios(scenarios) {
  const groups = new Map();
  for (const scenario of scenarios) {
    const id = isolationGroupId(scenario);
    if (!groups.has(id)) groups.set(id, {
      id, contextPath: servletContextPath(scenario), scenarios: []
    });
    groups.get(id).scenarios.push(scenario);
  }
  return [...groups.values()];
}

export function applicationConfiguration(group, baseUrl) {
  const contextPath = servletContextPath(group);
  let applicationBaseUrl = baseUrl;
  if (contextPath) {
    const url = new URL(baseUrl);
    if (!['http:', 'https:'].includes(url.protocol)
        || url.username || url.password || url.search || url.hash) {
      throw new Error('Context-path UI groups require an HTTP(S) base URL without credentials, query or fragment');
    }
    // This group starts its own servlet context, replacing any caller context.
    url.pathname = contextPath;
    applicationBaseUrl = url.href;
  }
  return {
    baseUrl: applicationBaseUrl,
    environment: contextPath ? { SERVER_SERVLET_CONTEXT_PATH: contextPath } : {}
  };
}
