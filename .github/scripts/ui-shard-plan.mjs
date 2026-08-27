export function scenarioKey(suite, id) {
  return `${suite}/${id}`;
}

export function validateShardPlan(plan, expectedScenarioKeys) {
  if (!plan || plan.schemaVersion !== 1 || !Array.isArray(plan.shards)) {
    throw new Error('UI shard plan must use schemaVersion 1 and contain a shards array');
  }

  const expected = new Set(expectedScenarioKeys);
  if (expected.size !== expectedScenarioKeys.length) {
    throw new Error('Expected UI scenario inventory contains duplicates');
  }

  const shardIds = new Set();
  const assigned = new Map();
  for (const shard of plan.shards) {
    if (!shard || typeof shard.id !== 'string' || !/^[a-z0-9][a-z0-9-]*$/.test(shard.id)) {
      throw new Error(`Invalid UI shard id: ${String(shard?.id)}`);
    }
    if (shardIds.has(shard.id)) {
      throw new Error(`Duplicate UI shard id: ${shard.id}`);
    }
    shardIds.add(shard.id);
    if (!Array.isArray(shard.scenarios) || shard.scenarios.length === 0) {
      throw new Error(`UI shard ${shard.id} must contain at least one scenario`);
    }
    for (const key of shard.scenarios) {
      if (typeof key !== 'string' || !expected.has(key) {
        throw new Error(`UI shard ${shard.id} references unknown scenario ${String(key)}`);
      }
      if (assigned.has(key)) {
        throw new Error(
          `UI scenario ${key} is assigned to both ${assigned.get(key)} and ${shard.id}`);
      }
      assigned.set(key, shard.id);
    }
  }

  const missing = [...expected].filter(key => !assigned.has(key));
  if (missing.length > 0) {
    throw new Error(`UI shard plan is missing scenarios: ${missing.join(', ')}`);
  }
  if (assigned.size !== expected.size) {
    throw new Error(
      `UI shard plan assigned ${assigned.size} scenarios, expected ${expected.size}`);
  }
  return plan;
}

export function shardScenarioKeys(plan, shardId) {
  const shard = plan.shards.find(candidate => candidate.id === shardId);
  if (!shard) {
    throw new Error(`Unknown UI shard: ${shardId}`);
  }
  return new Set(shard.scenarios);
}
