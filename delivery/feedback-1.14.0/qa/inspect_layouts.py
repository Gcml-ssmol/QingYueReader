import pathlib,json,subprocess,re
root=pathlib.Path(__file__).parent;out=root/'reference'
m=json.loads((out/'resource-map.json').read_text(encoding='utf-8'));ids={v['id']:k for k,v in m.items()}
apk='C:/Users/xsh12/xwechat_files/wxid_zotrj64rl8dl22_6895/msg/file/2026-09/base.apk(2).1'
aapt='C:/Users/xsh12/Documents/Codex/2026-09-22/new-chat/work/android-sdk/build-tools/35.0.0/aapt2.exe'
for name in ['nb_draw_panel','edit_tool_bar_v2','include_record_simple_tool_bar','pop_create_note_layout','toast_scale']:
 p=subprocess.run([aapt,'dump','xmltree',apk,'--file','res/layout/'+name+'.xml'],capture_output=True,check=True)
 s=p.stdout.decode('utf-8').replace('\r\n','\n')
 s=re.sub(r'@(0x[0-9a-f]+)',lambda t:'@'+t[1]+' ['+ids.get(t[1],'?')+']',s)
 (out/(name+'.xmltree.txt')).write_text(s,encoding='utf-8')
summaries=[]
for p in out.glob('*.xmltree.txt'):
 rows=[]
 for line in p.read_text(encoding='utf-8').splitlines():
  matches=re.findall(r'\[(id/[^]]+|string/[^]]+|layout/[^]]+)\]',line)
  for key in matches:
   if key.startswith('string/'):
    body=m[key]['body'];zh=re.search(r'\(zh-rCN\) "(.*)',body,re.S)
    value=(zh[1] if zh else body).strip().replace('\n',' ')
    rows.append(key+' = '+value)
   else:rows.append(key)
 summaries.append('### '+p.name+'\n'+'\n'.join(rows))
(out/'layout-controls.txt').write_text('\n\n'.join(summaries),encoding='utf-8')
print('PASS layouts:',len(summaries))

