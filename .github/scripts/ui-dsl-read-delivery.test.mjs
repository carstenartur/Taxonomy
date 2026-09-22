import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
const root = new URL('../../', import.meta.url);
const read = file => readFileSync(new URL(file, root), 'utf8');
test('draft-read feedback is included in both server and browser message sources',()=>{
 const config=read('taxonomy-app/src/main/java/com/taxonomy/shared/config/I18nConfig.java');
 assert.ok(config.includes('"messages_dsl_reading"'));
 for(const locale of ['', '_de']) {
  const source=read(`taxonomy-app/src/main/resources/i18n/messages_dsl_reading${locale}.properties`);
  for(const key of ['dsl.reading.invalidResponse','dsl.reading.newerDraft']) assert.ok(source.includes(key+'='));
 }
});
test('both canonical UI verification commands include the read-safety regressions',()=>{
 const {scripts}=JSON.parse(read('.github/package.json'));
 for(const key of ['verify:ui','verify:ui-contracts']) assert.ok(scripts[key].includes('npm run test:dsl-text-read'),key);
 assert.ok(scripts['test:dsl-text-read'].includes('ui-dsl-text-read.test.mjs'));
 assert.ok(scripts['test:dsl-text-read'].includes('ui-dsl-read-delivery.test.mjs'));
});
