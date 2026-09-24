import test from 'node:test';
import assert from 'node:assert/strict';
import {createExporter} from '../src/export-file.mjs';

function harness(options = {}) {
  const files = new Map(), calls = [], directories = new Set();
  let release;
  const gate = new Promise(resolve => { release = resolve; });
  const filesystem = {
    async mkdir({path}) { calls.push(['mkdir', path]); if (directories.has(path)) throw Object.assign(new Error('目录已存在'),{code:'OS-PLUG-FILE-0010'}); directories.add(path); },
    async writeFile({path, data}) {
      calls.push(['write', path, data.length]);
      if (options.failWrite) throw new Error('write failed');
      if (options.pauseWrite) await gate;
      files.set(path, Buffer.from(data, 'base64'));
    },
    async appendFile({path, data}) {
      calls.push(['append', path, data.length]);
      if (options.failAppend) throw new Error('append failed');
      files.set(path, Buffer.concat([files.get(path), Buffer.from(data, 'base64')]));
    },
    async getUri({path}) { calls.push(['uri', path]); return {uri: `file:///private/${path}`}; },
    async rmdir({path}) {
      calls.push(['rmdir', path]); directories.delete(path);
      for (const key of files.keys()) if (key.startsWith(path + '/')) files.delete(key);
    },
  };
  const share = {async share(request) { calls.push(['share', request]); if (options.cancelShare) throw Object.assign(new Error('cancelled'), {name:'AbortError'}); return {}; }};
  const exporter = createExporter({filesystem, share, directory:'DOCUMENTS', makeId:()=>options.id||'unique-1'});
  return {exporter, files, calls, directories, release};
}

test('preserves bytes across 256 KiB chunks and shares saved URI', async () => {
  const h = harness();
  const bytes = Buffer.alloc(256*1024*2+37);
  for (let i=0;i<bytes.length;i++) bytes[i]=i%251;
  const saved = await h.exporter.saveBlob(new Blob([bytes]), '笔记.pdf');
  assert.equal(saved.path, 'QingYueExports/unique-1/笔记.pdf');
  assert.deepEqual(h.files.get(saved.path), bytes);
  assert.equal(h.calls.filter(x=>x[0]==='write').length,1);
  assert.equal(h.calls.filter(x=>x[0]==='append').length,2);
  await h.exporter.shareFile(saved);
  assert.deepEqual(h.calls.at(-1)[1], {files:[saved.uri],title:'笔记.pdf',dialogTitle:'导出文件'});
});

test('writes a zero-byte file', async () => {
  const h=harness();
  const saved=await h.exporter.saveBlob(new Blob([]),'empty.qyweb');
  assert.equal(h.files.get(saved.path).length,0);
  assert.equal(h.calls.filter(x=>x[0]==='append').length,0);
});

test('cleans filename without losing extension or traversing directories', async () => {
  const h=harness();
  const saved=await h.exporter.saveBlob(new Blob(['x']),'../../坏\\名称?:.pdf');
  assert.equal(saved.path,'QingYueExports/unique-1/.._.._坏_名称__.pdf');
  assert.equal(h.files.size,1);
  await assert.rejects(h.exporter.saveBlob(new Blob(['x']),'...'),/文件名无效/);
});

test('failed append removes only this export directory', async () => {
  const h=harness({failAppend:true});
  h.files.set('QingYueExports/older/document.pdf',Buffer.from('keep'));
  await assert.rejects(h.exporter.saveBlob(new Blob([Buffer.alloc(256*1024+1)]),'bad.pdf'),/append failed/);
  assert.equal(h.files.get('QingYueExports/older/document.pdf').toString(),'keep');
  assert.equal(h.files.size,1);
  assert.deepEqual(h.calls.filter(x=>x[0]==='rmdir').map(x=>x[1]),['QingYueExports/unique-1']);
});

test('rejects concurrent save without interrupting first save', async () => {
  const h=harness({pauseWrite:true});
  const first=h.exporter.saveBlob(new Blob(['first']),'first.pdf');
  await new Promise(resolve=>setImmediate(resolve));
  await assert.rejects(h.exporter.saveBlob(new Blob(['second']),'second.pdf'),/已有文件正在保存/);
  h.release();
  const saved=await first;
  assert.equal(h.files.get(saved.path).toString(),'first');
  assert.equal(h.calls.filter(x=>x[0]==='mkdir').length,2);
});

test('refuses oversized blobs before creating a directory', async () => {
  const h=harness();
  const giant=new Blob([Buffer.alloc(105*1024*1024+1)]);
  await assert.rejects(h.exporter.saveBlob(giant,'giant.qyweb'),/超过 105 MB/);
  assert.equal(h.calls.length,0);
});

test('a second export reuses the root but requires a fresh ID directory', async () => {
  let id=0;
  const h=harness();
  const exporter=createExporter({filesystem:{
    mkdir:async args=>{h.calls.push(['mkdir',args.path]);if(h.directories.has(args.path))throw Object.assign(new Error('目录已存在'),{code:'OS-PLUG-FILE-0010'});h.directories.add(args.path);},
    writeFile:async ({path,data})=>h.files.set(path,Buffer.from(data,'base64')),
    appendFile:async ({path,data})=>h.files.set(path,Buffer.concat([h.files.get(path),Buffer.from(data,'base64')])),
    getUri:async ({path})=>({uri:'file:///private/'+path}),
    rmdir:async ({path})=>{h.directories.delete(path);for(const key of h.files.keys())if(key.startsWith(path+'/'))h.files.delete(key);},
  },share:{share:async()=>({})},directory:'DOCUMENTS',makeId:()=>`id-${++id}`});
  const first=await exporter.saveBlob(new Blob(['one']),'a.pdf');
  const second=await exporter.saveBlob(new Blob(['two']),'b.pdf');
  assert.equal(h.files.get(first.path).toString(),'one');
  assert.equal(h.files.get(second.path).toString(),'two');
});

test('a canceled native share leaves the saved file intact', async () => {
  const h=harness({cancelShare:true});
  const saved=await h.exporter.saveBlob(new Blob(['keep']),'keep.pdf');
  await assert.rejects(h.exporter.shareFile(saved),/cancelled/);
  assert.equal(h.files.get(saved.path).toString(),'keep');
  assert.equal(h.calls.filter(x=>x[0]==='rmdir').length,0);
});
