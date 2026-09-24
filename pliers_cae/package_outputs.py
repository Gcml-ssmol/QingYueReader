from pathlib import Path
import json,zipfile,hashlib
root=Path(__file__).resolve().parent
names=['README.md','参数与来源.md','build_pliers.py','extract_results.py','export_clouds.py','make_report.py','重新计算.ps1']
for job in ('Pliers_Main','Pliers_Fine'):
 for suffix in ('.cae','.inp','.odb','.sta','.msg','.dat','_mesh.json','_results.json'):
  names.append(job+suffix)
names+=['Pliers_Main_mises_100N.png','Pliers_Main_displacement_100N.png','Pliers_Main_model_iso.png']
files=[root/n for n in names]+list((root/'output').rglob('*'))
files=[p for p in files if p.is_file() and p.suffix!='.zip' and p.name!='manifest.json']
manifest=[]
for p in files:
 manifest.append({'file':p.relative_to(root).as_posix(),'bytes':p.stat().st_size,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()})
(root/'output'/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding='utf-8')
with zipfile.ZipFile(root/'output'/'钳子CAE模型与受力报告.zip','w',zipfile.ZIP_DEFLATED,compresslevel=5) as z:
 for p in files+[root/'output'/'manifest.json']:z.write(p,'pliers_cae/'+p.relative_to(root).as_posix())
print('PACKAGED',len(files),'files')
