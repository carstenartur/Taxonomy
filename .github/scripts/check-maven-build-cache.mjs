#!/usr/bin/env node

import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const root = resolve(import.meta.dirname, '../..');
const read = path => readFileSync(resolve(root, path), 'utf8');
const requireContract = (condition, message) => {
  if (!condition) {
    throw new Error(`maven build-cache contract failed: ${message}`);
  }
};

const extensions = read('.mvn/extensions.xml');
requireContract(extensions.includes('<groupId>org.apache.maven.extensions</groupId>'), 'unexpected extension groupId');
requireContract(extensions.includes('<artifactId>maven-build-cache-extension</artifactId>'), 'unexpected extension artifactId');
requireContract(extensions.includes('<version>1.3.0</version>'), 'build-cache extension must stay pinned to 1.3.0');

const cache = read('.mvn/maven-build-cache-config.xml');
requireContract(cache.includes('<enabled>true</enabled>'), 'cache must be enabled');
requireContract(cache.includes('<mandatoryClean>false</mandatoryClean>'), 'PR cache must not require clean');
for (const output of ['classes', 'test-classes', 'surefire-reports', 'failsafe-reports', 'site']) {
  requireContract(cache.includes(`<dirName>${output}</dirName>`), `missing attached output ${output}`);
}
requireContract(cache.includes('<dirName glob="jacoco.exec"></dirName>'), 'missing attached jacoco.exec output');
for (const execution of ['generate-aggregate-sbom', 'download-pinned-embedding-model']) {
  requireContract(cache.includes(`<execId>${execution}</execId>`), `${execution} must run even on cache hits`);
}
for (const property of ['skipTests', 'skipITs']) {
  requireContract(cache.includes(`propertyName="${property}"`), `missing runtime reconciliation for ${property}`);
}
requireContract((cache.match(/<reconcile propertyName="test"\/>/g) ?? []).length >= 2,
  'Surefire -Dtest and Failsafe -Dit.test selectors must participate in cache reconciliation');

for (const pom of ['taxonomy-coverage/pom.xml', 'taxonomy-build/pom.xml', '.github/ui-verification-pom.xml']) {
  const content = read(pom);
  requireContract(content.includes('<maven.build.cache.enabled>false</maven.build.cache.enabled>'),
    `${pom} must disable caching entirely for transient evidence/setup outputs`);
}

const workflow = read('.github/workflows/ci-cd.yml');
requireContract((workflow.match(/path: ~\/\.m2\/build-cache/g) ?? []).length >= 2,
  'application and core jobs must persist the Maven build cache');
requireContract(workflow.includes('maven.build.cache.skipCache=true'),
  'authoritative non-PR verification must bypass cache reads');
const coreStart = workflow.indexOf('\n  core:\n');
const observabilityStart = workflow.indexOf('\n  observability:\n', coreStart);
requireContract(coreStart >= 0 && observabilityStart > coreStart,
  'core workflow section must be identifiable');
const coreWorkflow = workflow.slice(coreStart, observabilityStart);
const cleanupStart = coreWorkflow.indexOf('      - name: Clean reactor before producing evidence\n');
const contractsStart = coreWorkflow.indexOf('      - name: Verify release and delivery contracts\n');
const helmStart = coreWorkflow.indexOf('      - name: Validate production and Rancher Helm profiles\n');
requireContract(cleanupStart >= 0 && cleanupStart < contractsStart && contractsStart < helmStart,
  'non-PR cleanup must precede contracts and Helm evidence generation');
const cleanup = coreWorkflow.slice(cleanupStart, contractsStart);
requireContract(cleanup.includes("if: github.event_name != 'pull_request'")
    && cleanup.includes('run: ./mvnw -B clean -Dmaven.build.cache.enabled=false'),
  'only non-PR builds must clean the complete reactor before producing evidence');
requireContract(!coreWorkflow.includes('./mvnw -B clean verify'),
  'clean must not remove prepared evidence or the open Maven log');
requireContract(coreWorkflow.includes('./mvnw -B verify -Pci')
    && coreWorkflow.includes('-Dmaven.build.cache.skipCache=true'),
  'non-PR verification must still run the full suite without cache reads');
requireContract(coreWorkflow.includes('${{ runner.os }}-taxonomy-maven-build-cache-verify-v2-'),
  'core verification must restore prior verification entries');
requireContract(!coreWorkflow.includes('${{ runner.os }}-taxonomy-maven-build-cache-app-v2-'),
  'core verification cache must not restore package -DskipTests entries');

console.log('Maven build-cache contract OK');
