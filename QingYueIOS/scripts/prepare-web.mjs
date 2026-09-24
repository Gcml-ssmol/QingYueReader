import {cp, mkdir, readFile, writeFile, readdir, lstat, realpath, rm} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import path from 'node:path';
import {createHash} from 'node:crypto';
import {build} from 'esbuild';

const root = fileURLToPath(new URL('../', import.meta.url));
const source = path.join(root, 'source-web'), out = path.join(root, 'www');
const provenance = JSON.parse(await readFile(path.join(source, 'SOURCE.json'), 'utf8'));
for (const [file, hash] of Object.entries(provenance.files)) {
  const actual = createHash('sha256').update(await readFile(path.join(source, file))).digest('hex');
  if (actual !== hash) throw new Error(`网页源文件校验失败：${file}`);
}
// This directory contains generated program assets only, never user documents.
// Verify the absolute output location before replacing the generated tree.
const resolvedRoot=await realpath(root);
let outInfo;
try { outInfo=await lstat(out); } catch(error) { if(error.code!=='ENOENT')throw error; }
if(outInfo) {
  if(outInfo.isSymbolicLink() || await realpath(out)!==path.join(resolvedRoot,'www'))throw new Error('拒绝清理非项目内的 www 目录');
  await rm(out,{recursive:true});
}
await mkdir(out, {recursive:true});
await cp(source, out, {recursive:true, filter: file => !['SOURCE.json', 'sw.js', 'offline.mjs', 'offline-assets.json', 'manifest.webmanifest'].includes(path.basename(file))});
function once(text, before, after) {
  if (text.split(before).length !== 2) throw new Error(`包装适配匹配失败：${before.slice(0,90)}`);
  return text.replace(before, after);
}
let app = await readFile(path.join(out, 'app.mjs'), 'utf8');
app = "import {offerNativeFile} from './native-export-ui.mjs';\n" + app;
app = once(app, 'function offerFile(blob,name){', 'function offerFile(blob,name){if(window.QYNative?.isNative)return offerNativeFile(blob,name,{modal,el,button,toast,busy});');
app = once(app, `晴阅网页版 · ${provenance.version}，优先适配 iPad，兼容 iPhone。`, '晴阅 App · 0.1.0，优先适配 iPad，兼容 iPhone。');
const installStart = "button('添加到 iPad / iPhone 主屏幕'";
const start = app.indexOf(installStart), end = app.indexOf("},'menu-action')", start);
if (start < 0 || end < start) throw new Error('未找到主屏幕帮助入口');
app = app.slice(0,start) + "el('p','这是内建阅读资源的 App。Safari 与 App 的资料各自保存；使用 .qyweb 备份转移。卸载 App 会删除本机资料，请定期通过系统分享把备份存到 App 以外的位置。')" + app.slice(end + "},'menu-action')".length);
app = once(app, '网页备份使用 .qyweb。', '本 App 与网页版均使用 .qyweb 备份。');
const builtInStatus = " b.append(el('p','阅读、字体与批注资源已内建，可离线使用。系统分享可能取决于目标 App 和网络。'));";
if (app.includes("from './offline.mjs'")) {
  app = once(app, "import {offlineMessage,prepareOffline,startOffline} from './offline.mjs';", '');
  app = once(app, " const status=el('p',offlineMessage());status.id='offline-status';status.setAttribute('role','status');b.append(status,button('重试离线准备',prepareOffline,'menu-action'));", builtInStatus);
  app = once(app, "startOffline((state,message)=>{if($('offline-status'))$('offline-status').textContent=message;if(state==='ready'||state==='failed')toast(message);});", '');
} else {
  const statusStart = " const status=el('p','正在检查离线资源…');";
  const statusEnd = "navigator.serviceWorker?.getRegistration().then(r=>status.textContent=r?.active?'离线资源已准备；退出前请保存备份。':'离线资源准备中，请保持联网并稍后重开。');";
  const s = app.indexOf(statusStart), e = app.indexOf(statusEnd, s);
  if (s < 0 || e < 0) throw new Error('未找到离线资源状态');
  app = app.slice(0,s) + builtInStatus + app.slice(e + statusEnd.length);
  app = once(app, "if('serviceWorker'in navigator&&window.isSecureContext)navigator.serviceWorker.register('./sw.js').then(()=>navigator.serviceWorker.ready).then(()=>toast('离线资源已准备')).catch(()=>toast('离线资源未就绪，当前请保持联网'));", '');
}
app = once(app, '请使用普通 Safari 页面，退出无痕模式并确认设备有可用空间。', '请确认设备有可用空间并重启 App；请勿卸载，以免删除本机资料。');
if (/serviceWorker|startOffline\(|prepareOffline|offlineMessage/.test(app)) throw new Error('原生包装仍包含网页离线准备逻辑');
await writeFile(path.join(out, 'app.mjs'), app);
const pdfPath=path.join(out,'pdf.mjs');
await writeFile(pdfPath,once(await readFile(pdfPath,'utf8'),'文字字体不可用，请联网打开一次后重试','内建文字字体读取失败，请退出并重开 App 后重试'));
let html = await readFile(path.join(out, 'index.html'), 'utf8');
html = once(html, '<link rel="manifest" href="./manifest.webmanifest">', '');
html = html.replaceAll('晴阅 · 网页版', '晴阅').replace('<small>网页版</small>', '<small>阅读 · 笔记</small>');
html = once(html, '<script type="module" src="./app.mjs"></script>', '<script src="./native-bridge.js"></script><script type="module" src="./native-start.mjs"></script>');
await writeFile(path.join(out, 'index.html'), html);
for (const file of ['native-start.mjs', 'native-export-ui.mjs']) await cp(path.join(root, 'src', file), path.join(out, file));
await build({entryPoints:[path.join(root, 'src/native-bridge.mjs')], outfile:path.join(out, 'native-bridge.js'), bundle:true, format:'iife', platform:'browser', target:'safari18', legalComments:'eof'});
for(const component of ['core','ios','filesystem','share'])await cp(path.join(root,'node_modules','@capacitor',component,'LICENSE'),path.join(out,'licenses',`capacitor-${component}.txt`));
const assets = {};
async function scan(dir, prefix='') {
  for (const entry of await readdir(dir,{withFileTypes:true})) {
    const rel = prefix + entry.name;
    if (entry.isDirectory()) await scan(path.join(dir, entry.name), rel + '/');
    else if (rel !== 'bundle-integrity.json') assets[rel] = createHash('sha256').update(await readFile(path.join(dir,entry.name))).digest('hex');
  }
}
await scan(out);
await writeFile(path.join(out, 'bundle-integrity.json'), JSON.stringify({version:'0.1.0',files:assets},null,2)+'\n');
console.log(`已准备 ${Object.keys(assets).length} 个本地 App 资源；无远程站点与 Service Worker 依赖。`);
