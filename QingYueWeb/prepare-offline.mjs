import fs from 'node:fs';import path from 'node:path';import crypto from 'node:crypto';import {fileURLToPath} from 'node:url';
const base=fileURLToPath(new URL('./dist/',import.meta.url));const files=fs.readdirSync(base,{recursive:true}).filter(f=>fs.statSync(path.join(base,f)).isFile()&&f!=='sw.js'&&!f.startsWith('licenses')).sort();
const version=crypto.createHash('sha256').update(fs.readFileSync(fileURLToPath(import.meta.url)));for(const f of files)version.update(f.replaceAll('\\','/')).update('\0').update(fs.readFileSync(path.join(base,f)));
const urls=files.map(f=>'./'+f.replaceAll('\\','/'));
const code=`const CACHE='qingyue-${version.digest('hex').slice(0,12)}';const ASSETS=${JSON.stringify(urls)};
self.addEventListener('install',event=>event.waitUntil((async()=>{const cache=await caches.open(CACHE);for(let i=0;i<ASSETS.length;i+=8)await Promise.all(ASSETS.slice(i,i+8).map(async path=>{const response=await fetch(new Request(path,{cache:'reload'}));if(!response.ok||response.redirected)throw new Error('Offline asset unavailable');await cache.put(path,response);}));await self.skipWaiting();})()));
self.addEventListener('activate',event=>event.waitUntil((async()=>{for(const key of await caches.keys())if(key.startsWith('qingyue-')&&key!==CACHE)await caches.delete(key);await self.clients.claim();})()));
// A cached installation must open even when the hosting connection never responds.
// Prefer the installed release's HTML over a newer network shell.
self.addEventListener('fetch',event=>{const url=new URL(event.request.url);if(event.request.method!=='GET'||url.origin!==self.location.origin)return;if(event.request.mode==='navigate'){event.respondWith(caches.open(CACHE).then(cache=>cache.match('./index.html')).then(cached=>cached||fetch(event.request)));return;}if(ASSETS.some(p=>new URL(p,self.location).pathname===url.pathname))event.respondWith(caches.open(CACHE).then(cache=>cache.match(event.request).then(hit=>hit||fetch(event.request))));});`;
fs.writeFileSync(path.join(base,'sw.js'),code);console.log('Prepared '+files.length+' offline assets');
