from pathlib import Path
import json,hashlib,zipfile,shutil
root=Path(__file__).resolve().parent
folder=root/'submission'/'pliers_CAE_submission'
shutil.copy2(root/'run_analysis.ps1',folder/'run_analysis.ps1')
(folder/'重新计算.ps1').write_text("& (Join-Path $PSScriptRoot 'run_analysis.ps1') @args\n",encoding='ascii')
checks=json.loads((folder/'submission_checks.json').read_text())
assert len(checks)==2 and all(c['CAE_open'] and c['ODB_fields_and_contact_history'] for c in checks)
allowed=list(folder.glob('*'))+list((folder/'output').glob('*'))+list((folder/'output'/'figures').glob('*'))
files=sorted(p for p in allowed if p.is_file() and not p.name.startswith('abaqus') and p.suffix not in ('.lck','.rec','.zip') and p.name not in ('SHA256SUMS.txt','submission_manifest.json'))
hashes={p.relative_to(folder).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in files}
(folder/'SHA256SUMS.txt').write_text(''.join(h+'  '+n+'\n' for n,h in hashes.items()),encoding='utf-8')
(folder/'submission_manifest.json').write_text(json.dumps({'tested_abaqus_version':'6.14-2','checks':checks,'files':[{'path':n,'sha256':h} for n,h in hashes.items()]},ensure_ascii=False,indent=2),encoding='utf-8')
zip_path=root/'output'/'钳子CAE完整运行程序_提交版.zip'
with zipfile.ZipFile(zip_path,'w',zipfile.ZIP_DEFLATED,compresslevel=5) as archive:
    for p in files+[folder/'SHA256SUMS.txt',folder/'submission_manifest.json']:
        archive.write(p,'pliers_CAE_submission/'+p.relative_to(folder).as_posix())
with zipfile.ZipFile(zip_path) as archive:
    assert archive.testzip() is None
    for n,h in hashes.items():assert hashlib.sha256(archive.read('pliers_CAE_submission/'+n)).hexdigest()==h
print(json.dumps({'zip':str(zip_path),'size_MiB':round(zip_path.stat().st_size/1024**2,2),'files':len(files)+2,'integrity':'passed'},ensure_ascii=False))
