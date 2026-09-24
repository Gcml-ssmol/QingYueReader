import pathlib,zipfile,struct,json,re
root=pathlib.Path(__file__).parent;out=root/'reference'
apk='C:/Users/xsh12/xwechat_files/wxid_zotrj64rl8dl22_6895/msg/file/2026-09/base.apk(2).1'
classes=[];methods=[];summaries=[];hits=[]
with zipfile.ZipFile(apk) as z:
 for name in z.namelist():
  if not (name.endswith('.dex') or name.startswith('assets/baiduprotect') and name.endswith('.jar')):continue
  data=z.read(name)
  if not data.startswith(b'dex\n'):
   summaries.append({'file':name,'size':len(data),'magic':data[:8].hex(),'readableDex':False});continue
  u32=lambda o:struct.unpack_from('<I',data,o)[0]
  n,off=u32(56),u32(60);strings=[]
  for i in range(n):
   pos=u32(off+4*i)
   while data[pos]&128:pos+=1
   pos+=1;end=data.index(0,pos);strings.append(data[pos:end].decode('utf-8',errors='replace'))
  n,off=u32(64),u32(68);types=[strings[u32(off+4*i)] for i in range(n)]
  n,off=u32(96),u32(100);own=set()
  for i in range(n):
   t=types[u32(off+32*i)]
   if t.startswith(('Lcom/jideos/','Lcom/jnotes/')):
    own.add(t);classes.append(name+'\t'+t)
  n,off=u32(88),u32(92);owned=0
  for i in range(n):
   cl,pr,idx=struct.unpack_from('<HHI',data,off+8*i);t=types[cl]
   if t in own:methods.append(name+'\t'+t+'\t'+strings[idx]);owned+=1
  hits.extend(name+'\t'+s.replace('\n',' ') for s in strings if re.search(r'(?i)(nbnote|nb_boundary|draft|lasso|eraser|toolpanel|bookmark)',s) and len(s)<400)
  summaries.append({'file':name,'size':len(data),'ownedClasses':len(own),'ownedMethods':owned,'readableDex':True})
(out/'dex-owned-classes.tsv').write_text('\n'.join(classes),encoding='utf-8')
(out/'dex-owned-methods.tsv').write_text('\n'.join(methods),encoding='utf-8')
(out/'dex-feature-strings.tsv').write_text('\n'.join(hits),encoding='utf-8')
(out/'dex-summary.json').write_text(json.dumps(summaries,indent=2),encoding='utf-8')
print(json.dumps({'ownedClasses':len(classes),'ownedMethods':len(methods),'entries':len(summaries),'readableDex':sum(s['readableDex'] for s in summaries)}))

