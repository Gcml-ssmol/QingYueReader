const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const {chromium}=require(process.env.QY_PLAYWRIGHT_MODULE||'playwright');
const output=process.env.QY_UI_OUTPUT||'test-results/ui-refresh';fs.mkdirSync(output,{recursive:true});
const url=process.env.QY_TEST_URL||'http://127.0.0.1:5181/';
(async()=>{
 const browser=await chromium.launch({headless:true,channel:'msedge'});
 try{
  const context=await browser.newContext({viewport:{width:390,height:844},deviceScaleFactor:1,colorScheme:'light',isMobile:true,hasTouch:true});
  const page=await context.newPage(),errors=[];page.on('pageerror',e=>errors.push(e.message));
  const shot=async name=>{await page.locator('#toast').waitFor({state:'hidden',timeout:10000});await page.evaluate(()=>document.fonts.ready);await page.screenshot({path:path.join(output,name),fullPage:false});};
  await page.goto(url);await page.locator('#library-count').filter({hasText:'0 个文件夹'}).waitFor();
  assert.equal(await page.locator('html').getAttribute('data-ui'),'warm');
  const before=await page.evaluate(async()=>{
   const s=await import('./store.mjs'),p=await import('./pdf.mjs');const blank=await p.blankPdf();
   for(const [name,note] of [['概率论与数理统计.pdf',false],['复变函数与积分变换.pdf',false],['课堂笔记',true]]){
    const d=s.blankDocument(name,blank,'',1,await s.sha256(blank));d.isNote=note;
    if(name.startsWith('概率论')){
     const text=(id,value,x,y,size,color='#30251e')=>({id,type:'text',text:value,x,y,size,color});
     const circle=(id,cx,cy)=>({id,type:'stroke',width:.003,color:'#ab501e',opacity:1,points:Array.from({length:65},(_,i)=>[cx+Math.cos(i*Math.PI/32)*.19,cy+Math.sin(i*Math.PI/32)*.14])});
     d.annotations={1:[text('chapter','第 1 章  随机事件与概率',.07,.09,.055),text('section','1.1   随机事件',.07,.23,.047),text('body','在一定的条件下可能出现也可能不出现的现象，\n称为随机现象（或随机试验）。\n\n随机试验的每一个可能的结果，称为一个样本点。\n全体样本点的集合记为 Ω，称为样本空间。\n\n任一子集称为一个随机事件，通常用 A、B、C 表示。',.07,.34,.034),text('note','☆  重要概念',.1,.73,.043,'#d85917'),circle('event-a',.37,1.0),circle('event-b',.60,1.0),text('a','A',.33,.96,.044,'#8a401e'),text('b','B',.63,.96,.044,'#8a401e'),text('caption','A ∩ B  表示事件 A 与 B 同时发生。',.08,1.23,.034,'#8a401e')]};
    }
    await s.insertDocument(d);
   }
   return (await s.listDocuments()).map(({blob,...d})=>d);
  });
  await page.reload();await page.locator('.item').nth(2).waitFor();
  await shot('phone-library.png');
  const overflow=async()=>assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'true'==='true','page must not overflow horizontally');
  await overflow();
  await page.locator('[data-nav=search]').click();await page.locator('#search').fill('概率论');
  assert.equal(await page.locator('.item').count(),1);assert.match(await page.locator('.item').innerText(),/概率论/);
  await page.locator('#search').evaluate(input=>input.blur());await shot('phone-search.png');
  await page.locator('#clear-search').click();await page.locator('[data-kind=note]').click();assert.equal(await page.locator('.item').count(),1);assert.match(await page.locator('.item').innerText(),/课堂笔记/);
  await page.locator('[data-kind=pdf]').click();assert.equal(await page.locator('.item').count(),2);
  await page.locator('[data-nav=trash]').click();assert.match(await page.locator('.empty').innerText(),/最近删除是空的/);
  await page.locator('[data-nav=library]').click();assert.equal(await page.locator('.item').count(),3);
  await page.locator('[data-nav=settings]').click();await page.getByRole('button',{name:'外观',exact:true}).click();
  assert(await page.locator('#modal-body').evaluate(body=>body.scrollHeight<=body.clientHeight+3),'Appearance choices should fit a standard phone viewport');await shot('phone-appearance.png');
  await page.getByRole('button',{name:'深色 · 中性',exact:true}).click();assert.equal(await page.locator('html').getAttribute('data-theme'),'dark');
  await page.reload();await page.locator('.item').nth(2).waitFor();assert.equal(await page.locator('html').getAttribute('data-theme'),'dark');
  await shot('phone-dark.png');
  await page.locator('[data-nav=settings]').click();await page.getByRole('button',{name:'外观',exact:true}).click();
  await page.getByRole('button',{name:'跟随系统',exact:true}).click();assert.equal(await page.locator('html').getAttribute('data-theme'),'light');
  await page.emulateMedia({colorScheme:'dark'});await page.waitForFunction(()=>document.documentElement.dataset.theme==='dark');assert.equal(await page.locator('html').getAttribute('data-theme'),'dark');
  await page.getByRole('button',{name:'浅色 · 中性',exact:true}).click();assert.equal(await page.locator('html').getAttribute('data-theme'),'light');
  await page.getByRole('button',{name:'经典 · 原版',exact:true}).click();await page.locator('#modal-close').click();
  assert.equal(await page.locator('html').getAttribute('data-ui'),'classic');assert.equal(await page.locator('#bottom-nav').isVisible(),false);assert.equal(await page.locator('#settings-button').isVisible(),true);assert.equal(await page.locator('.welcome-copy').isVisible(),false);
  await shot('phone-classic.png');
  await page.reload();await page.locator('.item').nth(2).waitFor();assert.equal(await page.locator('html').getAttribute('data-ui'),'classic');
  assert.deepEqual(await page.evaluate(async()=>(await(await import('./store.mjs')).listDocuments()).map(({blob,...d})=>d)),before,'UI choices must not change documents');
  await page.locator('#settings-button').click();await page.getByRole('button',{name:'外观',exact:true}).click();await page.getByRole('button',{name:'Codex · 极简',exact:true}).click();await page.locator('#modal-close').click();
  await page.getByRole('button',{name:/PDF 概率论与数理统计/}).click();await page.waitForFunction(()=>document.getElementById('busy').hidden&&document.getElementById('pdf-canvas').width>0);
  await shot('phone-reader.png');await overflow();await page.locator('[data-tool=highlight]').click();assert.equal(await page.locator('#page-stage').getAttribute('data-tool'),'highlight');
  const returnToLibrary=async()=>{await page.locator('#reader-back').click();await page.waitForFunction(()=>!document.getElementById('library').hidden&&document.getElementById('continue-reading').textContent.includes('继续阅读'));await page.locator('.item').nth(2).waitFor();};
  await returnToLibrary();assert.match(await page.locator('#continue-reading').innerText(),/继续阅读/);await page.locator('#continue-reading').click();await page.waitForFunction(()=>document.getElementById('busy').hidden&&!document.getElementById('reader').hidden);assert.equal(await page.locator('#document-name').innerText(),'概率论与数理统计.pdf');await returnToLibrary();
  for(const viewport of [{width:320,height:740},{width:834,height:1194},{width:1194,height:834}]){
   await page.setViewportSize(viewport);await page.waitForFunction(width=>innerWidth===width,viewport.width);await overflow();await shot(`library-${viewport.width}.png`);
   if(viewport.width>=760){assert.equal(await page.locator('#library-sidebar').isVisible(),true);await page.locator('#continue-reading').click();await page.waitForFunction(()=>document.getElementById('busy').hidden&&!document.getElementById('reader').hidden&&Math.abs(document.getElementById('page-stage').getBoundingClientRect().width-(document.getElementById('page-scroll').clientWidth-20))<2);await overflow();await shot(`reader-${viewport.width}.png`);await page.locator('#reader-page-toggle').click();assert.equal(await page.locator('#page-sidebar').isVisible(),false);await page.locator('#reader-page-toggle').click();await returnToLibrary();}
  }
  await page.evaluate(async()=>await navigator.serviceWorker.ready);await page.reload();await context.setOffline(true);await page.reload();await page.locator('.item').nth(2).waitFor();assert.equal(await page.locator('html').getAttribute('data-ui'),'warm');await context.setOffline(false);
  assert.deepEqual(errors,[]);console.log('PASS: appearance/default/classic/theme persistence, OS theme, non-mutating documents, search/type filters, navigation, resume, phone/tablet layouts, reader toolbar/sidebar, offline warm assets; desktop Edge only.');
 }finally{await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
