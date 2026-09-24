const wide=()=>matchMedia('(min-width:760px)').matches;
const node=(tag,text)=>{const element=document.createElement(tag);if(text!==undefined)element.textContent=text;return element;};
export function renderLibraryTree(folders,documents,folderId,filter,onSelect){
 const tree=document.getElementById('library-tree');tree.replaceChildren();
 const add=(label,id,mode,depth=0)=>{const b=node('button',label);b.type='button';b.className='tree-row';b.style.paddingLeft=(14+Math.min(depth,6)*13)+'px';b.title=label;if(mode===filter&&id===folderId){b.classList.add('selected');b.setAttribute('aria-current','page');}b.onclick=()=>onSelect(id,mode);tree.append(b);};
 add('全部资料','','all');add('未分类','','unfiled');tree.append(node('h3','文件夹'));
 const seen=new Set();const visit=(parent,depth)=>{for(const f of folders.filter(f=>f.parentId===parent).sort((a,b)=>a.name.localeCompare(b.name,'zh'))){if(seen.has(f.id))continue;seen.add(f.id);add('▰ '+f.name,f.id,'all',depth);visit(f.id,depth+1);}};visit('',0);
 if(!folders.length)tree.append(node('p','创建文件夹，整理学习资料'));
 tree.append(node('hr'));add('最近删除 · '+documents.filter(d=>d.trashed).length,'','trash');
}
let generation=0,lastPdf,lastKey='',task=null;
export function invalidatePageRail(){generation++;lastKey='';lastPdf=null;task?.cancel();task=null;}
export function refreshRailAnnotations(document,paint){
 for(const preview of globalThis.document.querySelectorAll('#page-rail [data-preview-page]')){
  const number=+preview.dataset.previewPage,ratio=+preview.dataset.ratio;if(!Number.isFinite(ratio)||ratio<=0)continue;paint(preview.querySelector('svg'),document.annotations[number]||[],ratio);
  const label=preview.closest('button').querySelector('.rail-label');label.textContent='第 '+number+' 页'+(document.bookmarks.includes(number)?' ★':'');
 }
}
export async function renderPageRail(pdf,document,page,onPage,paint){
 if(!wide()||globalThis.document.getElementById('reader').classList.contains('rail-collapsed')){invalidatePageRail();return;}
 const key=document.id+':'+document.pages+':'+page;
 if(lastKey===key&&lastPdf===pdf){refreshRailAnnotations(document,paint);return;}
 invalidatePageRail();lastKey=key;lastPdf=pdf;const version=generation;
 const rail=globalThis.document.getElementById('page-rail');rail.replaceChildren();
 globalThis.document.getElementById('rail-add-page').hidden=!document.isNote;
 const first=Math.max(1,page-2),last=Math.min(document.pages,first+4);
 const jump=(label,target)=>{const b=node('button',label);b.className='rail-jump';b.onclick=()=>onPage(target);rail.append(b);};
 if(first>1)jump('↑ 前几页',Math.max(1,page-5));
 for(let number=first;number<=last;number++){
  const button=node('button');button.className='rail-page'+(number===page?' selected':'');button.setAttribute('aria-label','预览第 '+number+' 页');if(number===page)button.setAttribute('aria-current','page');button.onclick=()=>onPage(number);
  const preview=node('span');preview.className='rail-preview';preview.dataset.previewPage=number;
  const canvas=node('canvas'),overlay=globalThis.document.createElementNS('http://www.w3.org/2000/svg','svg');preview.append(canvas,overlay);
  const label=node('span','第 '+number+' 页'+(document.bookmarks.includes(number)?' ★':''));label.className='rail-label';button.append(preview,label);rail.append(button);
  try{
   const p=await pdf.getPage(number);if(version!==generation)return;const base=p.getViewport({scale:1}),ratio=base.height/base.width;if(ratio>12){preview.textContent='长页面';continue;}
   const view=p.getViewport({scale:120/base.width});canvas.width=Math.ceil(view.width);canvas.height=Math.ceil(view.height);preview.dataset.ratio=ratio;
   task=p.render({canvasContext:canvas.getContext('2d'),viewport:view});await task.promise;task=null;if(version!==generation)return;paint(overlay,document.annotations[number]||[],ratio);
  }catch(error){if(version!==generation)return;preview.textContent='点击查看页面';}
 }
 if(last<document.pages)jump('后几页 ↓',Math.min(document.pages,page+5));
}
