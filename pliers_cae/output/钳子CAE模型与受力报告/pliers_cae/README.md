# 长嘴钳 Abaqus 受力分析

已完成：Abaqus/CAE 6.14-2 三维简化实体建模、5档静态夹持载荷、100 N细网格复算、ODB结果、受力图及7页中文PDF报告。

## 先打开这些文件

- `output/钳子受力分析报告.pdf`：分析报告、尺寸材料依据、结果表、云图、网格敏感性和限制。
- `Pliers_Main.cae`：可编辑的零件、装配、材料、接触、边界条件和网格。
- `Pliers_Main.odb`：Visualization模块中查看五档工况的结果。
- `output/figures/`：可直接用于报告的PNG图。
- `output/受力数据.rpt`：左右钳嘴的文本数据表。
- `参数与来源.md`：网上参考产品链接及全部主要假设。

## 关键结果与边界

每侧手柄施力20、40、60、80、100 N；钳嘴前端夹持10 mm钢块。
100 N/每侧时，主网格单侧夹紧力190.71 N，名义平均压强2.007 MPa；细网格夹紧力190.83 N，两级差约0.065%。

模型采用同类200 mm钳子尺寸、Cr-V类钢材弹性代理材料（E=210000 MPa，ν=0.30），不是照片实物的精确复刻。没有复制钳口齿形、钳尖厚度渐变和实际热处理本构。

**局部接触压强尚未充分解析。** 主网格CPRESS节点显示峰值27.06 MPa，细网格19.00 MPa，变化约-29.8%。不能把其中任一数值当作真实最大接触压强或强度判据。窄边缘接触的Q/CAREA诊断比值还高于输出平滑后的CPRESS峰值，进一步说明需要真实圆角和更细局部网格。

最终夹紧力采用整个接触面的CFN1历史输出，并与转轴RF1及CMN3进行平衡校核。直接相加CNORMF节点输出存在约5.4%的差异，已保留为后处理诊断，未作为最终夹紧力。

## 在CAE查看结果

1. File → Open → `Pliers_Main.cae` 查看模型。
2. 切换 Visualization，打开 `Pliers_Main.odb`。
3. Step/Frame选择 `Grip_020N` 至 `Grip_100N`。
4. 变量 `CPRESS`：接触压强；`S → Mises`：钳体等效应力；`U → Magnitude`：位移。
5. 历史输出中的 `CFN1 ... LEFT_JAW...` / `RIGHT_JAW...`：各侧整面接触合力；`CAREA`：算法接触面积。

## 复现

直接运行 `重新计算.ps1`，调用本机Abaqus命令在新的英文临时目录重新求解现有INP，随后将ODB、日志和JSON数据复制回来。
本机Abaqus 6.14-2在中文工作路径中运行pre.exe失败，因此求解使用英文临时目录。不要在中文路径直接提交Job。

如需重建几何：在有建模脚本的目录执行 `abaqus cae noGUI=build_pliers.py`。默认生成主网格；设置环境变量 `PLIERS_MESH=2.0`、`PLIERS_JOB=Pliers_Fine`、`PLIERS_FINAL_ONLY=1` 可重建细网格。

图表和PDF由 `make_report.py` 从实际ODB提取的JSON生成。需要Python3、numpy、matplotlib、Pillow、reportlab；字体使用Windows微软雅黑。该脚本不是Abaqus的Python2脚本。建模、提取及原生云图导出脚本使用Abaqus自带Python2.7。

没有虚构求解结果；Abaqus的 `.sta`、`.dat`、`.msg` 和原始 `.odb` 随工程保留。报告中的局部云图为初步结果，不构成实物承载能力认证。
