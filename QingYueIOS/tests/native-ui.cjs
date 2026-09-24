const {chromium}=require(process.env.QY_PLAYWRIGHT_MODULE||'playwright');
const assert=require('node:assert/strict'),fs=require('node:fs');
const path=require('node:path'),output=process.env.QY_TEST_OUTPUT||'test-results';
fs.mkdirSync(output,{recursive:true});
(async()=>{
  const browser=await chromium.launch({channel:'msedge',headless:true});
  try {
    const page=await browser.newPage({viewport:{width:1194,height:834}}),errors=[];
    page.on('pageerror',e=>errors.push(e.message));
    await page.goto(process.env.QY_TEST_URL||'http://127.0.0.1:5181/');
    await page.waitForFunction(()=>document.getElementById('library-count').textContent.includes('0 个文件夹'));
    assert.equal(await page.evaluate(()=>window.QYNative.isNative),false);
    await page.evaluate(()=>{
      window.exportsSeen=[];window.sharesSeen=[];
      window.QYNative={isNative:true,saveBlob:async(blob,name)=>{window.exportsSeen.push({name,bytes:Array.from(new Uint8Array(await blob.arrayBuffer()))});return {uri:'file:///mock/export.pdf',path:'QingYueExports/mock/'+name,name};},shareFile:async file=>{window.sharesSeen.push(file);throw Object.assign(new Error('Share canceled'),{name:'AbortError'});}};
    });
    await page.locator('#new-note').click();
    await page.locator('#modal-body input').fill('iPad 原生导出检查');
    await page.locator('#modal-actions button[type=submit]').click();
    await page.getByRole('button',{name:/笔记 iPad 原生导出检查/}).click();
    await page.waitForFunction(()=>document.getElementById('pdf-canvas').width>0&&document.getElementById('busy').hidden);
    await page.locator('#export-pdf').click();
    await page.getByRole('button',{name:'保存到 App 文件',exact:true}).click();
    await page.getByRole('status').filter({hasText:'已保存：QingYueExports/'}).waitFor();
    await page.getByRole('button',{name:'系统分享 / 存储到文件',exact:true}).click();
    await page.waitForFunction(()=>window.sharesSeen.length===1);
    const exported=await page.evaluate(()=>window.exportsSeen);
    assert.equal(exported.length,1,'share should reuse saved file');
    assert.equal(Buffer.from(exported[0].bytes).subarray(0,5).toString(),'%PDF-');
    assert.equal(await page.locator('#modal-title').textContent(),'导出文件');
    await page.screenshot({path:path.join(output,'ipad-native-export.png')});
    await page.locator('#modal-close').click();await page.locator('#reader-back').click();
    await page.locator('#bottom-nav [data-nav=settings]').click();
    assert((await page.locator('#modal-body').textContent()).includes('Safari 与 App 的资料各自保存'));
    assert.equal(await page.getByRole('button',{name:'添加到 iPad / iPhone 主屏幕',exact:true}).count(),0);
    await page.getByRole('button',{name:'导出完整备份',exact:true}).click();
    await page.getByRole('button',{name:'保存到 App 文件',exact:true}).click();
    await page.waitForFunction(()=>window.exportsSeen.length===2);
    const backup=await page.evaluate(()=>window.exportsSeen[1]);assert(backup.name.endsWith('.qyweb'));
    assert.equal(Buffer.from(backup.bytes).subarray(0,2).toString(),'PK');
    assert.deepEqual(errors,[]);
    console.log('PASS native UI injection: PDF export, same-file reuse, share cancellation, settings/migration notice, qyweb backup export; plugins mocked in desktop Chromium');
  } finally {await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
