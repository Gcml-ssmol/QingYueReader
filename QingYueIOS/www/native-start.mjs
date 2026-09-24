try {
  if (!globalThis.indexedDB || !globalThis.crypto?.subtle || !globalThis.structuredClone) {
    throw new Error('当前系统缺少本机存储或文件校验能力，请使用 iOS／iPadOS 18 或更新版本。');
  }
  await import('./app.mjs');
} catch (error) {
  console.error(error);
  const panel = document.createElement('div');
  panel.setAttribute('role', 'alert');
  panel.style.cssText = 'position:fixed;inset:0;z-index:1000;padding:calc(32px + env(safe-area-inset-top)) 24px;background:var(--paper,#fff8ef);color:var(--ink,#2d241e)';
  const title = document.createElement('h2'); title.textContent = '晴阅暂时无法启动';
  const detail = document.createElement('p'); detail.textContent = error.message || '请退出后重新打开。';
  const hint = document.createElement('p'); hint.textContent = '请保留 App 与本机资料，不要通过卸载解决。可将此提示交给开发者检查。';
  panel.append(title, detail, hint); document.body.append(panel);
}
