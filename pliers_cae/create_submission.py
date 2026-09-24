from pathlib import Path
import shutil,json,zipfile,hashlib
root=Path(__file__).resolve().parent
target=root/'submission'/'pliers_CAE_submission'
target.mkdir(parents=True,exist_ok=True)
names=['README.md','参数与来源.md','build_pliers.py','extract_results.py','export_clouds.py','make_report.py','重新计算.ps1','run_analysis.ps1','verify_submission.py']
for job in ('Pliers_Main','Pliers_Fine'):
    for suffix in ('.cae','.inp','.odb','.jnl','.sta','.dat','.msg','_mesh.json','_results.json'):names.append(job+suffix)
names+=['Pliers_Main_mises_100N.png','Pliers_Main_displacement_100N.png','Pliers_Main_model_iso.png']
for name in names:shutil.copy2(root/name,target/name)
for path in list((root/'output').glob('*'))+list((root/'output'/'figures').glob('*')):
    if path.is_file() and path.suffix!='.zip' and path.name!='manifest.json':
        dest=target/path.relative_to(root);dest.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(path,dest)
intro='''钳子CAE完整提交工程
适用软件：Abaqus/CAE 6.14-2（本机已实际求解）。
本包包含运行程序、可编辑模型、求解输入、原始结果、运行日志、报告与云图。
需要已安装且具有有效许可的Abaqus；包内不包含Abaqus安装程序。

一、提交与查看
1. 提交整个ZIP即可，或解压后提交整个pliers_CAE_submission文件夹。
2. 如果只允许提交一个CAE模型文件，提交Pliers_Main.cae。
3. 在CAE中用File > Open打开Pliers_Main.cae，查看模型设置。
4. 在Visualization中打开Pliers_Main.odb，查看20、40、60、80、100 N五档结果。
5. Pliers_Fine.cae/odb是100 N细网格对照。
6. 中文报告在output目录，图片在output/figures目录。

二、运行
解压ZIP后，在Abaqus Command窗口中切换到工程目录：
  powershell -NoProfile -ExecutionPolicy Bypass -File .\run_analysis.ps1
也可双击RUN_ANALYSIS.cmd。默认直接运行随附INP并导出结果。
如需从几何建模开始完整重建并求解，双击REBUILD_AND_RUN.cmd，或：
  powershell -NoProfile -ExecutionPolicy Bypass -File .\run_analysis.ps1 -Rebuild
若abaqus命令不在PATH中，通过-AbaqusCommand指定abaqus.bat完整路径。
运行器使用英文临时工作目录，并将新结果存入rerun_results独立子目录。
原始已提交结果不会被覆盖。不要从ZIP预览窗口直接运行脚本。

三、主要文件
Pliers_Main.cae         完整、可编辑的主模型
Pliers_Main.inp         可直接提交求解的输入文件
Pliers_Main.odb         已完成的五档计算结果
Pliers_Fine.*           细网格100 N对照模型与结果
build_pliers.py         参数化建模程序，Abaqus自带Python2.7执行
extract_results.py      提取CFN、CAREA、CPRESS、Mises、位移与校核数据
export_clouds.py        导出Abaqus原生应力/位移/接触云图
run_analysis.ps1        自动运行、可选择重建，保留全部运行文件
verify_submission.py   在CAE中检查模型及ODB可读性
make_report.py         可选：Python3生成图表和报告，不是求解必需程序
requirements-report.txt 可选报告生成依赖；CAE求解无需pip安装
参数与来源.md           同类产品公开参数、来源与模型假设
submission_checks.json 本包在Abaqus中的打开与结果检查记录
SHA256SUMS.txt          提交文件校验清单

四、结果性质
参考同类产品参数的简化模型，不是照片实物的精确逆向模型。
100 N/每侧时单侧夹紧力约190.71 N，名义平均压强约2.007 MPa。
局部压强峰值尚未收敛，限制已在报告和README中标明，请保留说明一并提交。
'''
(target/'00_提交与运行说明.txt').write_text(intro,encoding='utf-8-sig')
for filename,arg in [('RUN_ANALYSIS.cmd',''),('REBUILD_AND_RUN.cmd',' -Rebuild')]:
    cmd='@echo off\r\npowershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0run_analysis.ps1"'+arg+'\r\nset "run_code=%ERRORLEVEL%"\r\necho.\r\nif not "%run_code%"=="0" echo Run failed. Read the message above.\r\npause\r\nexit /b %run_code%\r\n'
    (target/filename).write_bytes(cmd.encode('ascii'))
(target/'requirements-report.txt').write_text('numpy\nmatplotlib\nPillow\nreportlab\n',encoding='ascii')
print(str(target))
