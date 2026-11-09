import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

function workerHarness({cached, network = async () => new Response('network')}) {
  const handlers = {}, opened = [];
  let requests = 0, response;
  const self = {location: new URL('https://example.test/qingyue/sw.js'), addEventListener: (name, fn) => {handlers[name] = fn;}};
  vm.runInNewContext(fs.readFileSync(new URL('../dist/sw.js', import.meta.url), 'utf8'), {
    self, URL, fetch: request => {requests++; return network(request);},
    caches: {open: async name => {opened.push(name); return {match: async path => path === './index.html' ? cached : undefined};}},
  });
  return {navigate() {handlers.fetch({request:{url:'https://example.test/qingyue/', method:'GET', mode:'navigate'},respondWith: value => {response=value;}});return response;},requests:()=>requests,opened};
}
test('cached app opens without waiting for a hanging hosting connection', async () => {
  const cached = new Response('cached release');
  const worker = workerHarness({cached, network: () => new Promise(() => {})});
  const result = await Promise.race([worker.navigate(),new Promise(resolve => setTimeout(()=>resolve('timed out'),100))]);
  assert.equal(result,cached);
  assert.equal(worker.requests(),0);
  assert.match(worker.opened[0],/^qingyue-/);
});
test('a hosting block cannot replace an already cached page', async () => {
  const worker = workerHarness({cached:new Response('app'),network:async()=>new Response('blocked',{status:403})});
  assert.equal(await (await worker.navigate()).text(),'app');
});
test('without a cached app, preserve the real hosting response', async () => {
  const worker = workerHarness({network:async()=>new Response('blocked',{status:403})});
  assert.equal((await worker.navigate()).status,403);
  assert.equal(worker.requests(),1);
});
class Worker extends EventTarget {
  constructor(state='installing'){super();this.state=state;}
  change(state){this.state=state;this.dispatchEvent(new Event('statechange'));}
}
async function offlineModule(register, secure=true) {
  const oldNavigator = Object.getOwnPropertyDescriptor(globalThis,'navigator');
  const oldWindow = Object.getOwnPropertyDescriptor(globalThis,'window');
  Object.defineProperty(globalThis,'navigator',{configurable:true,value:{serviceWorker:{register}}});
  Object.defineProperty(globalThis,'window',{configurable:true,value:{isSecureContext:secure}});
  const module = await import(new URL('../dist/offline.mjs',import.meta.url).href+'?test='+Math.random());
  return {module,restore(){
    if(oldNavigator)Object.defineProperty(globalThis,'navigator',oldNavigator);else delete globalThis.navigator;
    if(oldWindow)Object.defineProperty(globalThis,'window',oldWindow);else delete globalThis.window;
  }};
}
test('installation failure is reported after successful registration; retry can recover', async () => {
  const registration = new EventTarget();registration.installing=new Worker();
  const {module,restore}=await offlineModule(async()=>registration);
  try {
    const states=[];await module.startOffline(state=>states.push(state));
    registration.installing.change('redundant');
    assert.match(module.offlineMessage(),/准备失败/);
    registration.installing=new Worker();await module.prepareOffline();
    registration.active=registration.installing;registration.active.change('activated');
    assert.match(module.offlineMessage(),/已准备/);
    assert.deepEqual(states,['failed','preparing','ready']);
  } finally {restore();}
});
test('failed update leaves the existing offline installation available', async () => {
  const registration=new EventTarget();registration.active=new Worker('activated');registration.installing=new Worker();
  const {module,restore}=await offlineModule(async()=>registration);
  try {await module.startOffline(()=>{});registration.installing.change('redundant');assert.match(module.offlineMessage(),/已准备/);} finally {restore();}
});
test('insecure origin has a clear message and never registers a worker', async () => {
  let called=false;const {module,restore}=await offlineModule(async()=>{called=true;},false);
  try {await module.startOffline(()=>{});assert.match(module.offlineMessage(),/HTTPS/);assert.equal(called,false);} finally {restore();}
});
