import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile,access} from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {createHash} from 'node:crypto';
const root=fileURLToPath(new URL('../',import.meta.url));
const read=rel=>readFile(path.join(root,rel),'utf8');
test('app bundles verified local assets, identical in Xcode public resources',async()=>{
  const manifest=JSON.parse(await read('www/bundle-integrity.json'));
  assert(Object.keys(manifest.files).length>200);
  for(const [file,hash] of Object.entries(manifest.files)) {
    for(const base of ['www','ios/App/App/public']) {
      const bytes=await readFile(path.join(root,base,file));
      assert.equal(createHash('sha256').update(bytes).digest('hex'),hash,`${base}/${file}`);
    }
  }
  for(const file of ['native-bridge.js','native-export-ui.mjs','native-start.mjs','assets/ocr-font.ttf','vendor/pdfjs/pdf.worker.min.mjs'])assert(manifest.files[file],file);
  assert(!manifest.files['sw.js']);
  assert(!manifest.files['offline.mjs']);
  assert(!manifest.files['manifest.webmanifest']);
  assert(manifest.files['appearance.mjs']);
});
test('local origin and both iPhone/iPad configured without credentials or remote URL',async()=>{
  const config=JSON.parse(await read('capacitor.config.json'));
  assert.equal(config.webDir,'www');assert.equal(config.server.hostname,'localhost');
  assert.equal(config.server.iosScheme,'capacitor');assert.equal(config.server.url,undefined);
  const pbx=await read('ios/App/App.xcodeproj/project.pbxproj');
  assert.equal((pbx.match(/TARGETED_DEVICE_FAMILY = "1,2"/g)||[]).length,2);
  assert.equal((pbx.match(/IPHONEOS_DEPLOYMENT_TARGET = 18.0;/g)||[]).length,4);
  assert(!pbx.includes('DEVELOPMENT_TEAM ='));
  assert.match(pbx,/PrivacyInfo.xcprivacy in Resources/);
  const spm=await read('ios/App/CapApp-SPM/Package.swift');
  assert.match(spm,/exact: "8.5.2"/);assert.match(spm,/ion-ios-filesystem.git", exact: "1.1.1"/);
  assert(!spm.includes('.pnpm/'));assert(!spm.includes('C:/'));
  for(const plugin of ['filesystem','share'])await access(path.join(root,'node_modules/@capacitor',plugin,'Package.swift'));
});
test('Files visibility, privacy reasons, native launch and export integration',async()=>{
  const plist=await read('ios/App/App/Info.plist');
  for(const key of ['UIFileSharingEnabled','LSSupportsOpeningDocumentsInPlace'])assert.match(plist,new RegExp(`<key>${key}</key>\\s*<true/>`));
  assert(!/<key>UIUserInterfaceStyle<\/key>\s*<string>Light<\/string>/.test(plist),'system theme must reach WKWebView');
  const privacy=await read('ios/App/App/PrivacyInfo.xcprivacy');
  assert(privacy.includes('C617.1'));assert(privacy.includes('CA92.1'));
  const html=await read('www/index.html'),app=await read('www/app.mjs');
  assert(html.indexOf('native-bridge.js')<html.indexOf('native-start.mjs'));
  assert(app.includes('offerNativeFile(blob,name,{modal,el,button,toast,busy})'));
  assert(!app.includes('serviceWorker'));assert(!app.includes('添加到 iPad / iPhone 主屏幕'));
  assert(!/offlineMessage|prepareOffline|startOffline/.test(app));
  assert(app.includes('阅读、字体与批注资源已内建，可离线使用。'));
  assert(app.includes("from './appearance.mjs'"));
  assert(app.includes('Safari 与 App 的资料各自保存'));
});
