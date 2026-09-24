require('node:fs').mkdirSync('test-results',{recursive:true});
const {chromium}=require(process.env.QY_PLAYWRIGHT_MODULE||'playwright');
const fs=require('node:fs');
(async()=>{
 const browser=await chromium.launch({headless:true,channel:'msedge'});
 const page=await browser.newPage({viewport:{width:390,height:844}});
 await page.goto(process.env.QY_TEST_URL||'http://127.0.0.1:5180/');
 await page.waitForFunction(()=>document.getElementById('library-count').textContent.includes('0 个文件夹'));
 const result=await page.evaluate(async()=>{
   const assert=(value,message)=>{if(!value)throw new Error(message);};
   const store=await import('./store.mjs'), pdf=await import('./pdf.mjs'), backup=await import('./backup.mjs'),geometry=await import('./geometry.mjs');
   const original=await PDFLib.PDFDocument.create();for(const angle of [0,90,180,270]){const p=original.addPage([400,600]);p.setRotation(PDFLib.degrees(angle));}
   const blob=new Blob([await original.save()],{type:'application/pdf'}), hash=await store.sha256(blob);
   const doc=store.blankDocument('四种旋转 PDF',blob,'',4,hash);
   for(let i=1;i<=4;i++)doc.annotations[i]=[
     {id:'stroke-'+i,type:'stroke',color:'#ff0000',opacity:.3,width:.06,points:[[.1,.1],[.7,.1],[.7,.2]]},
     {id:'point-'+i,type:'stroke',color:'#0000ff',opacity:1,width:.04,points:[[.4,.2]]},
     {id:'text-'+i,type:'text',color:'#111111',text:'晴阅测试 Hello',size:.025,x:.1,y:.3}];
   const exported=await pdf.exportPdf(doc), parsed=await pdf.loadPdf(exported), rotations=[];
   assert(hash===await store.sha256(doc.blob),'Original PDF modified');
   for(let i=1;i<=4;i++){
     const p=await parsed.getPage(i),v=p.getViewport({scale:1}),canvas=document.createElement('canvas');canvas.width=v.width;canvas.height=v.height;const ctx=canvas.getContext('2d');
     await p.render({canvasContext:ctx,viewport:v}).promise;
     const pixel=(x,y)=>Array.from(ctx.getImageData(Math.round(v.width*x),Math.round(v.width*y),1,1).data);
     const red=pixel(.3,.1),join=pixel(.7,.1),blue=pixel(.4,.2);
     assert(red[0]>245&&red[1]>150&&red[1]<200&&red[2]>150,'Highlighter rotation/opacity '+i+': '+red);
     assert(Math.abs(join[1]-red[1])<4,'Highlighter join became darker '+i);
     assert(blue[2]>245&&blue[0]<10&&blue[1]<10,'Dot position on rotated page '+i);
     const text=(await p.getTextContent()).items.map(x=>x.str).join('');assert(text.includes('晴阅测试 Hello'),'Unicode extraction '+i+': '+text);
     rotations.push({angle:[0,90,180,270][i-1],red,join,blue,text});
   }await parsed.destroy();
   const stroke={type:'stroke',color:'#000000',opacity:1,width:.02,points:[[0,0],[1,0]]};const fragments=geometry.eraseStroke(stroke,[.5,0],.1);
   assert(fragments.length===2&&fragments[0].points.at(-1)[0]<.4&&fragments[1].points[0][0]>.6,'Local eraser must split line');
   assert(geometry.clampZoom(10)===50&&geometry.clampZoom(500)===300,'Zoom bounds');
   await store.insertDocument(doc);await store.updateDocument(doc.id,1,d=>({...d,name:'已修改'}));
   let rejected=false;try{await store.updateDocument(doc.id,1,d=>({...d,name:'错误覆盖'}));}catch{rejected=true;}
   assert(rejected&&(await store.getDocument(doc.id)).name==='已修改','Stale writer must not overwrite');
   const archive=await backup.createBackup(),zip=await JSZip.loadAsync(archive),manifest=JSON.parse(await zip.file('manifest.json').async('string'));manifest.documents[0].hash='0'.repeat(64);zip.file('manifest.json',JSON.stringify(manifest));const corrupt=await zip.generateAsync({type:'blob'});rejected=false;try{await backup.restoreBackup(corrupt);}catch{rejected=true;}
   assert(rejected&&(await store.listDocuments()).length===1&&(await store.listFolders()).length===0,'Failed backup must not partially write');
   await store.saveFolder({id:'a',name:'A',parentId:''});await store.saveFolder({id:'b',name:'B',parentId:'a'});rejected=false;try{await store.saveFolder({id:'a',name:'A',parentId:'b'});}catch{rejected=true;}
   assert(rejected&&(await store.listFolders()).find(f=>f.id==='a').parentId==='','Folder cycle rejected atomically');
   return {rotations,bytes:exported.size,sourceHash:hash,checks:['original unchanged','local eraser','zoom bounds','stale writer','corrupt backup atomicity','folder cycle']};
 });
 console.log(JSON.stringify(result,null,2));console.log('PASS PDF rotation, vector marker/dot, searchable Chinese, data integrity checks');
 await browser.close();
})().catch(e=>{console.error(e);process.exit(1);});
