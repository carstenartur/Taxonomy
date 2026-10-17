// JUnit commands each callback. No node:test runner or XML/report writer is used.
import { registerHooks } from 'node:module';
import { createInterface } from 'node:readline';
import { pathToFileURL } from 'node:url';
import { readFileSync } from 'node:fs';
import callbacks from './junit-callbacks.cjs';
const source = process.argv[2];
const shim = new URL('./junit-callbacks.cjs', import.meta.url).href;
registerHooks({ resolve(specifier, context, next) {
  return specifier === 'node:test' ? {url:context.conditions.includes('require') ? shim : new URL('./junit-callbacks.mjs', import.meta.url).href, shortCircuit:true} : next(specifier, context);
}});
const send = payload => process.stdout.write(`JUNIT\t${JSON.stringify(payload)}\n`);
const state = callbacks.state;
const standalone = !/\b(?:node:test)\b/.test(readFileSync(source, 'utf8'));
let started = false;
async function hooks(values) { for (const callback of values) await callback(); }
async function invoke(entry, prefix = '') {
  const name = prefix + entry.name;
  const outcomes = [];
  if (entry.options.skip || entry.options.todo) return [{name, status:'skipped', detail:String(entry.options.skip || entry.options.todo)}];
  const cleanup = [], children = [];
  const pending = [];
  const context = {
    name:entry.name,
    after:callback => cleanup.push(callback),
    test:(childName, options, body) => {
      if (typeof options === 'function') { body=options; options={}; }
      const execution = invoke({name:childName, options:options || {}, body}, `${name} / `).then(result => { children.push(...result); });
      pending.push(execution);
      return execution;
    },
    skip:reason => { throw Object.assign(Error(reason || 'skipped'), {junitSkipped:true}); },
    todo:reason => { throw Object.assign(Error(reason || 'todo'), {junitSkipped:true}); }
  };
  let failure, timer;
  try {
    await hooks(state.beforeEach);
    await Promise.race([
      Promise.resolve().then(() => entry.body(context)).then(() => Promise.all(pending)),
      new Promise((_, reject) => { timer=setTimeout(() => reject(Error(`Callback timed out: ${name}`)), entry.options.timeout || 120000); })
    ]);
    if (children.some(child => child.status === 'failed')) failure=Error('One or more nested callbacks failed');
  } catch(error) { failure=error; }
  finally {
    clearTimeout(timer);
    try { await hooks(cleanup); await hooks(state.afterEach); } catch(error) { failure=error; }
  }
  outcomes.push({name, status:failure ? (failure.junitSkipped ? 'skipped' : 'failed') : 'passed', detail:failure?.stack || ''}, ...children);
  return outcomes;
}
try {
  if (standalone) state.cases.push({name:source.split('/').at(-1), options:{}, body:() => import(pathToFileURL(source).href)});
  else await import(pathToFileURL(source).href);
  if (!state.cases.length) throw Error('No callback declarations discovered');
  send({names:state.cases.map(entry => entry.name)});
  const input=createInterface({input:process.stdin, terminal:false});
  for await (const command of input) {
    if (command === 'close') {
      try { if(started) await hooks(state.after); send({closed:true}); }
      catch(error) { send({fatal:error.stack}); process.exitCode=1; }
      input.close(); process.stdin.destroy(); break;
    }
    try {
      const index = Number(command);
      if(!Number.isInteger(index) || !state.cases[index]) throw Error('Invalid callback index');
      if(!started) { await hooks(state.before); started=true; }
      send({outcomes:await invoke(state.cases[index])});
    } catch(error) { send({fatal:error.stack}); }
  }
} catch(error) { send({fatal:error.stack}); process.exitCode=1; }
