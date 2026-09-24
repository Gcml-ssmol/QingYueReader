const {chromium}=require(process.env.QY_PLAYWRIGHT_MODULE||'playwright');
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const output=process.env.QY_TEST_OUTPUT||'test-results';fs.mkdirSync(output,{recursive:true});
(async()=>{
 const browser=await chromium.launch({channel:'msedge',headless:true});
 try{
  const page=await browser.newPage({viewport:{width:390,height:844},colorScheme:'dark'}),errors=[];
  page.on('pageerror',error=>errors.push(error.message));
  await page.goto(process.env.QY_TEST_URL||'http://127.0.0.1:5181/');
  await page.waitForFunction(()=>document.getElementById('library-count').textContent.includes('0 个文件夹'));
  assert.deepEqual(await page.evaluate(()=>[document.documentElement.dataset.ui,document.documentElement.dataset.theme]),['warm','dark']);
  await page.locator('#bottom-nav [data-nav=settings]').click();
  const notice=await page.locator('#modal-body').textContent();
  assert(notice.includes('阅读、字体与批注资源已内建，可离线使用。'));
  assert(!notice.includes('重试离线准备'));
  await page.getByRole('button',{name:'外观',exact:true}).click();
  await page.locator('[data-preference=mode][data-value=light]').click();
  assert.equal(await page.locator('html').getAttribute('data-theme'),'light');
  await page.locator('[data-preference=style][data-value=classic]').click();
  assert.equal(await page.locator('html').getAttribute('data-ui'),'classic');
  await page.reload();
  await page.waitForFunction(()=>document.getElementById('library-count').textContent.includes('0 个文件夹'));
  assert.deepEqual(await page.evaluate(()=>[document.documentElement.dataset.ui,document.documentElement.dataset.theme]),['classic','light']);
  assert.equal(await page.locator('#bottom-nav').isVisible(),false);
  await page.locator('#settings-button').click();
  await page.getByRole('button',{name:'外观',exact:true}).click();
  await page.locator('[data-preference=style][data-value=warm]').click();
  await page.locator('[data-preference=mode][data-value=system]').click();
  assert.equal(await page.locator('html').getAttribute('data-theme'),'dark');
  await page.emulateMedia({colorScheme:'light'});
  await page.waitForFunction(()=>document.documentElement.dataset.theme==='light');
  await page.screenshot({path:path.join(output,'iphone-appearance.png'),fullPage:true});
  await page.locator('#modal-close').click();
  for(const viewport of [{width:320,height:720},{width:390,height:844},{width:834,height:1194},{width:1194,height:834}]){
   await page.setViewportSize(viewport);
   assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'No horizontal overflow at '+viewport.width);
  }
  assert.equal(await page.evaluate(async()=> (await navigator.serviceWorker.getRegistrations()).length),0,'Native output must not register a web Service Worker');
  assert.deepEqual(errors,[]);
  console.log('PASS native bundle: cream/classic persistence, light/dark/system transitions, built-in offline message, no Service Worker, phone/tablet layout; desktop Chromium only');
 }finally{await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
