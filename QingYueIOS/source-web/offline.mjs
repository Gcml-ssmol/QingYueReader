const messages = {
  preparing: '离线资源准备中，请保持联网。',
  ready: '离线资源已准备；退出前请保存备份。',
  failed: '离线资源准备失败。请确认网络可用，再点击“重试离线准备”。',
  unsupported: '此入口无法准备离线资源，请使用 Safari 打开 HTTPS 网站。',
};
let state = 'preparing', notify = () => {}, pending;
const watched = new WeakSet();
export const offlineMessage = () => messages[state];
function update(next) {
  if (state === next) return;
  state = next;
  notify(state, offlineMessage());
}
function watchWorker(worker, registration) {
  if (!worker || watched.has(worker)) return;
  watched.add(worker);
  const check = () => {
    if (worker.state === 'activated') update('ready');
    else if (worker.state === 'redundant') update(registration.active ? 'ready' : 'failed');
  };
  worker.addEventListener('statechange', check);
  check();
}
export function prepareOffline() {
  if (!window.isSecureContext || !('serviceWorker' in navigator)) {
    update('unsupported');
    return Promise.resolve();
  }
  if (pending) return pending;
  update('preparing');
  pending = navigator.serviceWorker.register('./sw.js').then(registration => {
    if (!watched.has(registration)) {
      watched.add(registration);
      registration.addEventListener('updatefound', () => watchWorker(registration.installing, registration));
    }
    watchWorker(registration.installing, registration);
    watchWorker(registration.waiting, registration);
    watchWorker(registration.active, registration);
    if (registration.active?.state === 'activated') update('ready');
    else if (!registration.installing && !registration.waiting && !registration.active) update('failed');
  }).catch(() => update('failed')).finally(() => { pending = undefined; });
  return pending;
}
export function startOffline(onChange) {
  notify = onChange;
  return prepareOffline();
}
