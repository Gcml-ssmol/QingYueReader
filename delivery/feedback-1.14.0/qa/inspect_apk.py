"""Read-only APK/resource evidence extraction. Outputs stay in this QA directory."""
import pathlib,re,struct,zipfile,subprocess,json
root=pathlib.Path(__file__).parent
out=root/'reference';out.mkdir(exist_ok=True)
apk=pathlib.Path('C:/Users/xsh12/xwechat_files/wxid_zotrj64rl8dl22_6895/msg/file/2026-09/base.apk(2).1')
aapt='C:/Users/xsh12/Documents/Codex/2026-09-22/new-chat/work/android-sdk/build-tools/35.0.0/aapt2.exe'
resources=root.parents[1]/'feedback-1.13.4/interaction/reference/apk2-resources.txt'
lines=resources.read_text(encoding='utf-8-sig').splitlines()
entries={};key=None;begin=0;block=[]
for no,line in enumerate(lines,1):
    m=re.match(r'\s*resource (0x[0-9a-f]+) (\S+)',line)
    if m:
        if key: entries[key]={'id':rid,'line':begin,'body':'\n'.join(block)}
        rid,key=m.groups();begin=no;block=[]
    elif key:block.append(line)
if key: entries[key]={'id':rid,'line':begin,'body':'\n'.join(block)}
(out/'resource-map.json').write_text(json.dumps(entries,ensure_ascii=False,indent=2),encoding='utf-8')
pattern=re.compile(r'草稿|套索|录音|图形|橡皮|双指|长按|复制页面|删除页面|书签|模板|笔刷|激光|关联|页序|压感|缩放|翻页|图层|导出|背景|自定义|工具栏|纸张|笔盒|剪切|粘贴|旋转|形状|文本|选区|护眼|左右手|手势|图片|页面')
selected=[]
for key,item in entries.items():
    if not key.startswith('string/'):continue
    zh=re.search(r'\(zh-rCN\) "(.*)',item['body'],re.S)
    value=zh.group(1) if zh else item['body']
    if pattern.search(value):
        selected.append(f"{item['line']}\t{key}\t"+value.replace('\n',' ').strip())
(out/'feature-strings.tsv').write_text('\n'.join(selected),encoding='utf-8')
idmap={v['id']:k for k,v in entries.items()}
layouts=['activity_note_detail','fragment_nb_note_detail','fragment_note_detail','popupwin_detail_menu','include_detail_more_menu','phone_popupwin_detail_more','phone_note_menu_bottom_sheet_dialog','view_note_side_bar','detail_outline_pop_menu','detail_outline_menu_op','popup_add_blank_page','popupwin_change_template','include_change_template','pop_tool_pen','ink_pen_pop','pencil_pop_pad','eraser_pen_pop','pop_eraser','property_view_eraser','lasso_type_pop','phone_lasso_type_pop','pop_lasso','lasso_hint_pop','property_view_addtext','popup_drag_text_menu','popupwin_detail_image_editor','popupwin_detail_image_crop','pop_graph_tool','standard_graph_pop','property_view_graph','include_pen_box','popup_pen_box_batch_edit','include_record_tool_bar','phone_sound_record_more_menu','popupwin_detail_export_note','popupwin_detail_exportpdf','layout_note_detail_search','view_sidebar_search','include_set_page_direction','include_set_paper_template','detail_menu_preview_setting','layout_relate_notes','layout_tab_page','property_view_laser_pointer_pen']
with zipfile.ZipFile(apk) as z:
    for layout in layouts:
        name=f'res/layout/{layout}.xml'
        if name not in z.namelist():continue
        p=subprocess.run([aapt,'dump','xmltree',str(apk),'--file',name],capture_output=True)
        if p.returncode: raise RuntimeError(p.stderr.decode(errors='replace'))
        s=p.stdout.decode('utf-8',errors='replace')
        for rid,k in idmap.items():
            if '@'+rid in s:s=s.replace('@'+rid,'@'+rid+' ['+k+']')
        (out/(layout+'.xmltree.txt')).write_text(s,encoding='utf-8')
    classes=[];methods=[];string_hits=[];summaries=[]
    for name in sorted(z.namelist()):
        if not re.fullmatch(r'classes\d*\.dex',name):continue
        data=z.read(name);u32=lambda o:struct.unpack_from('<I',data,o)[0]
        n,off=u32(56),u32(60);strings=[]
        for i in range(n):
            pos=u32(off+4*i)
            while data[pos]&128:pos+=1
            pos+=1
            end=data.index(0,pos)
            strings.append(data[pos:end].decode('utf-8',errors='replace'))
        n,off=u32(64),u32(68);types=[strings[u32(off+4*i)] for i in range(n)]
        count,off=u32(96),u32(100)
        own=set()
        for i in range(count):
            t=types[u32(off+32*i)]
            if t.startswith(('Lcom/jideos/','Lcom/jnotes/')):
                classes.append(name+'\t'+t);own.add(t)
        n,off=u32(88),u32(92);owned=0
        for i in range(n):
            cl,pr,idx=struct.unpack_from('<HHI',data,off+8*i);t=types[cl]
            if t in own: methods.append(name+'\t'+t+'\t'+strings[idx]);owned+=1
        hit=[s for s in strings if re.search(r'(?i)(nbnote|draft|lasso|eraser|toolpanel|note.*(?:page|detail)|bookmark|stroke.*shape)',s)]
        string_hits.extend(name+'\t'+s.replace('\n',' ') for s in hit if len(s)<400)
        summaries.append({'dex':name,'ownedClasses':len(own),'ownedMethods':owned,'matchingStrings':len(hit)})
    (out/'dex-owned-classes.tsv').write_text('\n'.join(classes),encoding='utf-8')
    (out/'dex-owned-methods.tsv').write_text('\n'.join(methods),encoding='utf-8')
    (out/'dex-feature-strings.tsv').write_text('\n'.join(string_hits),encoding='utf-8')
    (out/'dex-summary.json').write_text(json.dumps(summaries,indent=2),encoding='utf-8')
print(json.dumps({'resourceStrings':len(selected),'xmlLayouts':len(layouts),'ownedClasses':len(classes),'ownedMethods':len(methods),'dex':summaries},ensure_ascii=False))

