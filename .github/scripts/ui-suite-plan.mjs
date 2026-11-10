import { scenarioKey } from './ui-shard-plan.mjs';

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

export function groupScenariosByShard(scenarios, shardPlan) {
  return shardPlan.shards.flatMap(shard => {
    const keys = new Set(shard.scenarios);
    const selected = scenarios.filter(scenario => keys.has(scenarioKey(scenario.suite, scenario.id)));
    return groupScenarios(selected).map(group => ({ ...group, id: `${shard.id}-${group.id}` }));
  });
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
  const publishedGraphFixtures = group.scenarios?.some(scenario => scenario.suite === 'primary');
  return {
    baseUrl: applicationBaseUrl,
    environment: {
      ...(contextPath ? { SERVER_SERVLET_CONTEXT_PATH: contextPath } : {}),
      // Primary workflows publish a real PUBLIC repository. Its lifetime is this
      // disposable application, so inherited persistent datasource settings cannot apply.
      ...(publishedGraphFixtures ? {
        SPRING_PROFILES_ACTIVE: 'hsqldb',
        SPRING_DATASOURCE_URL: 'jdbc:hsqldb:mem:ui-primary-fixtures;shutdown=true',
        TAXONOMY_DATASOURCE_URL: 'jdbc:hsqldb:mem:ui-primary-fixtures;shutdown=true',
        SPRING_DATASOURCE_DRIVER_CLASS_NAME: 'org.hsqldb.jdbc.JDBCDriver',
        SPRING_DATASOURCE_USERNAME: 'sa',
        SPRING_DATASOURCE_PASSWORD: '',
        TAXONOMY_FEATURES_MULTI_REPOSITORY_API_ENABLED: 'true'
      } : {})
    }
  };
}
