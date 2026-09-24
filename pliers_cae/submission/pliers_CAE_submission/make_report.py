# -*- coding: utf-8 -*-
"""Build scientific plots and a Chinese PDF from real Abaqus ODB exports."""
from pathlib import Path
import sys, json, math, shutil
ROOT=Path(__file__).resolve().parent
sys.path.insert(0,str(ROOT/'python_packages'))
import numpy as np
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.patches import Polygon,Rectangle,Circle
from matplotlib.colors import Normalize
from PIL import Image as PILImage
from reportlab.pdfgen import canvas
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.lib.colors import HexColor, white
from reportlab.platypus import Paragraph,Table,TableStyle
from reportlab.lib.styles import ParagraphStyle

OUT=ROOT/'output'; FIG=OUT/'figures'; OUT.mkdir(exist_ok=True); FIG.mkdir(exist_ok=True)
main=json.loads((ROOT/'Pliers_Main_results.json').read_text())
fine=json.loads((ROOT/'Pliers_Fine_results.json').read_text())
mesh=json.loads((ROOT/'Pliers_Main_mesh.json').read_text())
fmesh=json.loads((ROOT/'Pliers_Fine_mesh.json').read_text())
steps=main['steps']; last=steps[-1]['jaws']['LEFT_JAW']; refined=fine['steps'][-1]['jaws']['LEFT_JAW']
Fs=np.array([s['force_per_handle_N'] for s in steps]); js=[s['jaws']['LEFT_JAW'] for s in steps]
Q=np.array([j['clamping_force_N'] for j in js]); nominal=np.array([j['nominal_pressure_MPa'] for j in js]); peak=np.array([j['peak_pressure_MPa'] for j in js]); active=np.array([j['active_mean_pressure_MPa'] for j in js])
changes={k:100.*(refined[k]-last[k])/last[k] for k in ('clamping_force_N','peak_pressure_MPa','max_mises_MPa','max_displacement_mm','active_mean_pressure_MPa','contact_area_mm2')}
plt.rcParams.update({'font.family':'Microsoft YaHei','axes.unicode_minus':False,'font.size':11,'axes.spines.top':False,'axes.spines.right':False,'savefig.dpi':200})
BLUE='#176B8A'; ORANGE='#DA793C'; INK='#19324A'; GRAY='#64758B'

def save(fig,name):
    fig.savefig(FIG/name,bbox_inches='tight',facecolor='white');plt.close(fig)

# Model and external-force diagram; all coordinates match build_pliers.py.
fig,ax=plt.subplots(figsize=(6.5,9))
xy=np.asarray(mesh['outline'])
ax.add_patch(Polygon(xy,fc='#9BC7DC',ec=INK,lw=1.1,zorder=2))
mir=xy.copy();mir[:,0]*=-1
ax.add_patch(Polygon(mir,fc='#D0DFEA',ec=INK,lw=1.1,zorder=3))
ax.add_patch(Rectangle((-5,58),10,10,fc=ORANGE,ec=INK,zorder=5))
ax.add_patch(Circle((0,0),3,fc='white',ec=INK,zorder=5))
for sign in (-1,1):
 ax.annotate('',xy=(sign*33.5,-111),xytext=(sign*68,-111),arrowprops=dict(arrowstyle='-|>',lw=2,color=ORANGE))
 ax.text(sign*56,-101,'F',ha='center',color=ORANGE,fontsize=17)
 ax.annotate('',xy=(sign*20,63),xytext=(sign*5.5,63),arrowprops=dict(arrowstyle='-|>',lw=2,color=BLUE),zorder=6)
ax.annotate('10 mm 钢块\ny = 58~68 mm',xy=(0,63),xytext=(28,85),fontsize=11,arrowprops=dict(arrowstyle='-',color=GRAY))
ax.annotate('理想铰接 / 转轴孔 Ø6',xy=(0,0),xytext=(27,22),fontsize=11,arrowprops=dict(arrowstyle='-',color=GRAY))
ax.annotate('',xy=(-55,73),xytext=(-55,-127),arrowprops=dict(arrowstyle='<->',color=GRAY))
ax.text(-60,-25,'总高 200 mm',rotation=90,ha='center',va='center',color=GRAY)
ax.text(0,-143,'每侧手柄 F = 20、40、60、80、100 N\n蓝色箭头为工件作用在钳嘴上的反力',ha='center',va='top',fontsize=11)
ax.set(xlim=(-76,91),ylim=(-159,105),aspect='equal');ax.axis('off')
save(fig,'01_model_and_loads.png')

fig,axs=plt.subplots(1,2,figsize=(11,4.2),layout='constrained')
axs[0].plot(Fs,Q,'o-',lw=2,color=BLUE,label='左钳嘴：整面合力 CFN1')
axs[0].plot(Fs,Fs*111/63,'--',color=GRAY,label='中心力臂估算：111/63 × F')
axs[0].set(xlabel='每侧手柄力 F / N',ylabel='单侧夹紧力 Q / N',ylim=(0,max(Q)*1.17),xlim=(0,110));axs[0].legend(fontsize=9);axs[0].grid(alpha=.2)
axs[1].plot(Fs,nominal,'o-',label='名义平均 Q / 95',color=BLUE,lw=2)
axs[1].plot(Fs,peak,'^-',label='节点 CPRESS 峰值',color=ORANGE,lw=2)
axs[1].set(xlabel='每侧手柄力 F / N',ylabel='压强 / MPa',ylim=(0,max(peak)*1.15),xlim=(0,110));axs[1].legend(fontsize=9);axs[1].grid(alpha=.2)
save(fig,'02_force_pressure_trends.png')

def pressure(ax,j,vmax,title):
 d=np.asarray(j['pressure_map_nodes'])
 # Replot the actual ODB nodal CPRESS with explicitly stated linear interpolation.
 tri=matplotlib.tri.Triangulation(d[:,3],d[:,2])
 im=ax.tripcolor(tri,np.maximum(d[:,4],0),shading='gouraud',cmap='turbo',norm=Normalize(0,vmax))
 ax.set(xlim=(0,9.5),ylim=(56.5,70),xlabel='厚度坐标 z / mm',title=title,aspect='equal')
 ax.axhline(58,color='black',lw=.7,ls='--');ax.axhline(68,color='black',lw=.7,ls='--')
 ax.set_ylabel('转轴起算 y / mm')
 return im
fig,axs=plt.subplots(1,5,figsize=(14,4.5),layout='constrained')
for ax,F,j in zip(axs,Fs,js):im=pressure(ax,j,max(peak),f'{F} N / 每侧')
fig.colorbar(im,ax=axs,shrink=.72,label='CPRESS / MPa')
save(fig,'03_pressure_five_loads.png')
for F,j in zip(Fs,js):
 fig,ax=plt.subplots(figsize=(5.5,6),layout='constrained');im=pressure(ax,j,max(peak),f'左钳嘴接触压强 | 每侧 {F} N')
 fig.colorbar(im,ax=ax,label='CPRESS / MPa',shrink=.8)
 save(fig,f'CPRESS_{F:03d}N.png')
fig,axs=plt.subplots(1,2,figsize=(8,5.4),layout='constrained')
vmax=max(last['peak_pressure_MPa'],refined['peak_pressure_MPa'])
pressure(axs[0],last,vmax,'主网格：局部 1.125 mm')
im=pressure(axs[1],refined,vmax,'细化网格：局部 0.900 mm')
fig.colorbar(im,ax=axs,label='CPRESS / MPa',shrink=.75)
save(fig,'04_pressure_mesh_comparison.png')

# Annotate original Abaqus exports without altering their numerical colours.
for raw,dest,title,note in (
 ('Pliers_Main_mises_100N.png','05_mises_100N.png','钳体等效应力 | 每侧手柄 100 N','单位 MPa；节点外推与平均；变形显示倍率 1'),
 ('Pliers_Main_displacement_100N.png','06_displacement_100N.png','总位移 | 每侧手柄 100 N','单位 mm；变形显示倍率 1'),
 ('Pliers_Main_model_iso.png','07_model_iso.png','Abaqus 三维有限元模型','半搭接交叉钳体 + 钢块；理想铰接')):
 fig,ax=plt.subplots(figsize=(9,8));ax.imshow(PILImage.open(ROOT/raw));ax.axis('off');ax.set_title(title,pad=10,color=INK,fontsize=16)
 fig.text(.5,.03,note,ha='center',fontsize=11,color=GRAY);fig.subplots_adjust(left=0,right=1,top=.92,bottom=.065)
 save(fig,dest)

fontpath='C:/Windows/Fonts/msyh.ttc'
pdfmetrics.registerFont(TTFont('CJK',fontpath,subfontIndex=0))
W,H=595.28,841.89; M=42; CW=W-2*M
pdf=canvas.Canvas(str(OUT/'钳子受力分析报告.pdf'),pagesize=(W,H))
pdf.setTitle('长嘴钳夹持受力分析 | Abaqus 6.14-2');pdf.setAuthor('CAE analysis')
style=ParagraphStyle('body',fontName='CJK',fontSize=10,leading=16,textColor=HexColor(INK),spaceAfter=6,wordWrap='CJK')
small=ParagraphStyle('small',parent=style,fontSize=8.4,leading=13)
cell=ParagraphStyle('cell',parent=style,fontSize=8.5,leading=13)
pageno=0
def start(title,subtitle=''):
 global pageno
 pageno+=1;pdf.setFillColor(HexColor(INK));pdf.rect(0,H-12,W,12,fill=1,stroke=0)
 pdf.setFont('CJK',9);pdf.setFillColor(HexColor(GRAY));pdf.drawString(M,H-39,'CAE / 长嘴钳夹持研究 · 2026-09-24')
 pdf.setFillColor(HexColor(INK));pdf.setFont('CJK',21);pdf.drawString(M,H-78,title)
 if subtitle:para(subtitle,H-99,small)
 pdf.setStrokeColor(HexColor('#DCE4EB'));pdf.line(M,37,W-M,37)
 pdf.setFillColor(HexColor(GRAY));pdf.setFont('CJK',8);pdf.drawString(M,23,'参考尺寸简化模型 · mm / N / MPa · Abaqus/Standard 6.14-2');pdf.drawRightString(W-M,23,str(pageno))
 return H-124
def para(text,y,st=style,x=M,width=CW):
 p=Paragraph(text,st);w,h=p.wrap(width,1000);p.drawOn(pdf,x,y-h);return y-h-7
def section(text,y):
 pdf.setFillColor(HexColor(BLUE));pdf.setFont('CJK',12);pdf.drawString(M,y-15,text);return y-26
def pic(name,x,y,width,height):
 pdf.drawImage(str(FIG/name),x,y-height,width=width,height=height,preserveAspectRatio=True,anchor='c',mask='auto');return y-height
def table(rows,y,widths=None):
 data=[[Paragraph(str(c),cell) for c in row] for row in rows]
 t=Table(data,colWidths=widths or [CW/len(rows[0])]*len(rows[0]))
 t.setStyle(TableStyle([('BACKGROUND',(0,0),(-1,0),HexColor('#E5F0F5')),('ROWBACKGROUNDS',(0,1),(-1,-1),[white,HexColor('#F6F8FA')]),('VALIGN',(0,0),(-1,-1),'TOP'),('LEFTPADDING',(0,0),(-1,-1),7),('RIGHTPADDING',(0,0),(-1,-1),7),('TOPPADDING',(0,0),(-1,-1),6),('BOTTOMPADDING',(0,0),(-1,-1),6),('LINEBELOW',(0,0),(-1,0),.7,HexColor('#9FB9CA'))]))
 w,h=t.wrap(CW,1000);t.drawOn(pdf,M,y-h);return y-h-12
def end():pdf.showPage()

y=start('钳嘴压力如何随手柄力变化','真实 Abaqus 求解结果；参考同类产品尺寸与材料，非照片实物的测量模型。')
y=para(f'在每侧手柄施力 20~100 N、前端夹持 10 mm 钢块的示例工况下，单侧夹紧力从 <b>{Q[0]:.2f} N</b> 增至 <b>{Q[-1]:.2f} N</b>，在本范围内近似随施力成正比。',y)
y=table([['100 N / 每侧','结果'],['单侧夹紧力（主网格）',f'{last["clamping_force_N"]:.2f} N'],['名义平均压强 Q / 95',f'{last["nominal_pressure_MPa"]:.3f} MPa'],['单侧夹紧力（细网格）',f'{refined["clamping_force_N"]:.2f} N'],['CPRESS节点显示峰值',f'{last["peak_pressure_MPa"]:.2f} MPa（尚未充分解析）']],y,[CW*.58,CW*.42])
pic('01_model_and_loads.png',M,y,245,310)
pic('07_model_iso.png',M+255,y,255,310)
y-=320
y=para('关键解释：钳嘴弯曲和铰接转动使接触集中在试块边缘附近，局部压强明显高于 Q/95。结果用于研究载荷传递和变化趋势；没有实物齿形、材料热处理及屈服数据，不能据此判定实际钳子的安全载荷。',y,small)
end()

y=start('模型尺寸、材料与边界条件','公开厂家信息作为参考；未公开的结构细节与材料本构明确列为假设。')
y=table([['项目','采用值','依据 / 限制'],['整体尺寸','总高200 mm；钳尖 y=73；柄端 y=-127','参考KNIPEX 200 mm长嘴钳；坐标由本模型定义'],['主体厚度','9.5 mm；搭接区单片4.75 mm','参考关节厚度；未复现真实钳尖2.5 mm厚度渐变'],['铰接','孔径6 mm；绕z自由','假设；无轴摩擦及轴承间隙'],['夹持钢块','10×10×9.5 mm；y=58~68','10 mm厚度为确认工况；其余尺寸/位置为假设'],['钳体材料','Cr-V类钢材弹性代理','同类钳子材料类别；不是照片实物的已知牌号'],['弹性本构','E=210000 MPa；ν=0.30','钳体和钢块均为工程假设；未输入塑性'],['手柄载荷','每侧20、40、60、80、100 N','相向施力；均布在y=-127~-95的外侧面'],['名义力臂','柄111 mm；试块中心63 mm','接触合力的实际力臂由求解输出确定']],y,[95,185,CW-280])
y=section('接触、约束及简化',y)
y=para('两片钳体为三维可变形实体，半搭接避免初始体积重叠。铰孔运动耦合到参考点，约束平移与绕x、y转动，放开绕z转动。钳体间不设置接触；转轴不传递绕z的驱动力矩。',y)
y=para('钢块 x=0 中面约束U1，z=4.75 中面约束U3，底面 y=58 约束U2，以定位试块。钳嘴与钢块采用有限滑移、面到面、无摩擦硬接触，允许分离。初始表面刚好接触。',y)
y=para('求解采用 Static General，NLGEOM=ON。忽略锯齿、剥线孔、剪切刃、橡胶套与真实圆角；柄部用折线近似。等厚钳尖通常比真实薄尖更硬，压强分布与位移不能直接当作照片实物的精确结果。',y)
end()

y=start('五档载荷的计算结果','表格为左钳嘴结果；两侧手柄均施加表中 F，不能把双侧接触力相加称为单侧夹紧力。')
rows=[['F / N','Q / N','Q/95\nMPa','力放大比\nQ/F','显示峰值\nMPa','CAREA\nmm²']]
for F,j in zip(Fs,js):rows.append([F,f'{j["clamping_force_N"]:.2f}',f'{j["nominal_pressure_MPa"]:.3f}',f'{j["clamping_force_N"]/F:.3f}',f'{j["peak_pressure_MPa"]:.2f}',f'{j["contact_area_mm2"]:.3f}'])
y=table(rows,y,[55,75,85,100,85,CW-400])
y=pic('02_force_pressure_trends.png',M,y,CW,235)-14
y=section('变化规律',y)
ratio=Q/Fs
y=para(f'本范围内单侧夹紧力约为每侧手柄力的 {ratio.mean():.3f} 倍。20 N与100 N之间夹紧力增幅为 {Q[-1]/Q[0]:.3f} 倍。实际合力力臂在100 N时为 {last["effective_jaw_arm_mm"]:.2f} mm，偏向钢块靠近转轴的一边，故夹紧力高于按试块中心63 mm估算的数值。',y)
y=para('名义平均压强 Q/95 是统一面积上的比较指标。CAREA是求解器报告的接触面积；节点CPRESS经过输出处理。当前窄边缘接触没有得到充分解析，表中“显示峰值”不能当作真实局部最大压强。',y)
y=para('本模型输入为力（N），不是手对橡胶套的实测压强。若输入条件改为手柄接触压强，需要先根据真实受压面积与方向换算合力并重新施加载荷。',y,small)
end()

y=start('钳嘴接触压强云图','左钳嘴内侧；统一色标，单位 MPa。图中虚线标记试块下缘 y=58 和上缘 y=68。')
y=pic('03_pressure_five_loads.png',M,y,CW,205)-10
y=para('图由ODB中的CPRESS节点值绘制，采用三角网格线性插值；没有对结果进行放大或人工着色。图中的高压带靠近试块下缘，在厚度方向也存在局部不均匀，这是本模型半搭接结构和弯曲变形共同作用的结果。',y,small)
y=section('100 N工况：局部网格细化对照',y)
y=pic('04_pressure_mesh_comparison.png',M,y,CW,270)-7
y=para(f'局部网格由1.125 mm细化到0.900 mm后，CPRESS显示峰值由 {last["peak_pressure_MPa"]:.2f} 变为 {refined["peak_pressure_MPa"]:.2f} MPa（变化 {changes["peak_pressure_MPa"]:+.1f}%）。本例100 N的Q/CAREA约为 {last["active_mean_pressure_MPa"]:.1f} MPa，反而高于节点显示峰值，说明不能把输出平滑后的节点场当作已充分解析的真实局部压强分布。需要实物圆角和更细局部网格才能报告可信峰值。',y,small)
end()

y=start('钳体应力与变形','每侧手柄 100 N；下图为 Abaqus 直接导出的场结果，均使用真实变形倍率1。')
pic('05_mises_100N.png',M,y,CW*.5,300);pic('06_displacement_100N.png',M+CW*.5,y,CW*.5,300);y-=312
y=table([['输出量','主网格','细化网格'],['钳体最大Mises应力（积分点）',f'{last["max_mises_MPa"]:.2f} MPa',f'{refined["max_mises_MPa"]:.2f} MPa'],['钳嘴 y>30 区域最大Mises应力',f'{last["jaw_max_mises_MPa"]:.2f} MPa',f'{refined["jaw_max_mises_MPa"]:.2f} MPa'],['钳体最大位移模长',f'{last["max_displacement_mm"]:.4f} mm',f'{refined["max_displacement_mm"]:.4f} mm']],y,[CW*.52,CW*.24,CW*.24])
y=para('S.Mises 是金属内部等效应力，CPRESS 是接触界面的法向压强。二者物理含义不同，不能用钳体的红色应力区域替代钳嘴压强分析。',y)
y=para('云图中的应力由积分点向节点外推，并按Abaqus的75%阈值进行平均，所以色标峰值可能不同于上表的原始积分点最大值。局部尖角和搭接厚度突变使最大应力具有网格敏感性。',y,small)
y=para('未取得实际钢材牌号、热处理状态及屈服/塑性曲线，因此本报告不计算安全系数，不宣称100 N为安全上限。',y,small)
end()

y=start('校核与结果适用范围','仅将已完成的求解和可核对的数据写入结论。')
maxfb=max(j['force_balance_error_percent'] for s in steps for j in s['jaws'].values())
maxmb=max(j['moment_error_percent'] for s in steps for j in s['jaws'].values())
maxsym=max(abs(s['jaws']['LEFT_JAW']['clamping_force_N']-s['jaws']['RIGHT_JAW']['clamping_force_N'])/s['jaws']['LEFT_JAW']['clamping_force_N']*100 for s in steps)
y=table([['校核项','结果'],['求解完成','主模型5个载荷步及细网格100 N载荷步均正常完成'],['单片钳体力平衡',f'整面CFN1与转轴RF1减手柄力最大相差 {maxfb:.4f}%'],['绕转轴力矩平衡',f'CMN3与手柄力矩最大相差约 {maxmb:.4f}%（柄面位移用节点均值近似）'],['左右对称性',f'单侧夹紧力最大相差 {maxsym:.4f}%'],['稳定化','未启用自动静力稳定化；ALLSD在本工况为零'],['求解器提示','2条关于运动耦合的通用警告；没有求解错误或负特征值警告']],y,[155,CW-155])
y=section('网格敏感性：100 N / 每侧',y)
rows=[['指标','主网格','细化网格','变化']]
for label,k in [('夹紧力 / N','clamping_force_N'),('峰值压强 / MPa','peak_pressure_MPa'),('最大Mises / MPa','max_mises_MPa'),('最大位移 / mm','max_displacement_mm')]:rows.append([label,f'{last[k]:.4f}',f'{refined[k]:.4f}',f'{changes[k]:+.2f}%'])
y=table(rows,y,[155,115,115,CW-385])
y=para(f'主网格共 {2*mesh["lever_elements"]+mesh["block_elements"]:,} 个C3D10单元，细网格共 {2*fmesh["lever_elements"]+fmesh["block_elements"]:,} 个。两级网格用于敏感性检查，不能代替对局部峰值的完整收敛研究。',y,small)
y=para(f'后处理核对发现，100 N时直接相加节点CNORMF得到 {last["nodal_contact_force_sum_N"]:.2f} N，与整面合力相差 {last["nodal_sum_difference_percent"]:.2f}%。最终夹紧力采用整面历史输出CFN1，并以转轴反力独立验证；未用节点简单求和作为最终合力。',y,small)
y=para('可支持的结论：在当前固定几何、弹性材料与夹持位置下，增大手柄力使夹紧力和接触压强增加；局部边缘压强高于名义平均值。不能外推到不同夹持位置、带齿钳口、摩擦夹持、实际塑性或疲劳寿命。',y)
end()

y=start('来源、文件与复现方法','检索日期：2026-09-24。厂家参数与建模假设分别记录在“参数与来源.md”。')
sources=[('KNIPEX 26 11 200 官方产品页','尺寸：200 mm、L3=73 mm、T1=9.5 mm；其他尺寸未全部复制。','https://www.knipex.com/products/combination-and-multifunctional-pliers/snipe-nose-side-cutting-pliers-stork-beak-pliers/snipe-nose-side-cutting-pliers-stork-beak-pliers/2611200'),('SATA 世达 2023 官方目录','同类尖嘴钳 Cr-V 锻造材质类别；不证明照片实物的牌号。','https://oss.satatools.com/sataportal2022/202309/28/202309281637520544.pdf'),('FUJIYA F-110CS 官方产品页','230 mm多功能尖嘴钳，锻造及热处理合金钢，作同类交叉参考。','https://www.fujiyatw.com.tw/f-110cs-8-combination-plier'),('Ovako 51CrV4 官方材料资料','说明材料性能需要结合牌号变体和热处理条件；本模型未采用其屈服强度。','https://steelnavigator.ovako.com/steel-grades/51crv4-en100892002/'),('Abaqus 接触输出变量说明','CPRESS、CFORCE及整面接触输出的定义。','https://docs.software.vt.edu/abaqusv2025/English/SIMACAEOUTRefMap/simaout-c-std-surfacevariables.htm')]
for i,(title,desc,url) in enumerate(sources,1):
 y=para(f'{i}. <a href="{url}" color="#176B8A">{title}</a><br/>{desc}',y,small)
y=section('交付文件',y)
y=table([['文件','用途'],['Pliers_Main.cae / .inp / .odb','主模型、求解输入与五档载荷结果'],['Pliers_Fine.cae / .inp / .odb','细化模型及100 N网格敏感性结果'],['output/figures/*.png','受力示意、各档CPRESS、应力、位移与趋势图'],['output/受力数据.rpt、*_results.json','可核对的文本表和ODB提取数据'],['build_pliers.py、extract_results.py','参数化建模与原始结果提取脚本'],['export_clouds.py、make_report.py','Abaqus场图导出与本报告生成']],y,[CW*.47,CW*.53])
y=section('在 Abaqus/CAE 中查看',y)
y=para('File → Open 打开 Pliers_Main.cae 查看零件、装配、接触、载荷与网格。进入 Visualization 后打开 Pliers_Main.odb，在 Step/Frame 中选择 Grip_020N 至 Grip_100N；变量选择 CPRESS、S → Mises 或 U → Magnitude。',y,small)
y=para('重新求解时请将工程文件复制到英文目录，再运行随附“重新计算.ps1”。本机6.14-2的求解器在中文工作路径下出现输入处理异常；报告结果来自英文临时目录中的成功求解，最终文件已复制回项目目录。',y,small)
end();pdf.save()

with (OUT/'受力数据.rpt').open('w',encoding='utf-8') as f:
 f.write('Abaqus 6.14-2 | mm, N, MPa | each handle force; per-jaw quantities\n')
 f.write('CAUTION: Q/CAREA is an output diagnostic; CPRESS peak is a smoothed nodal display value, not a resolved true local maximum.\n')
 f.write('F_N\tJaw\tCFN1_abs_N\tp_nom_MPa\tQ_over_CAREA_diagnostic_MPa\tCPRESS_display_max_MPa\tCAREA_mm2\tS_mises_IP_max_MPa\tU_max_mm\n')
 for s in steps:
  for side,j in s['jaws'].items():f.write('\t'.join(str(x) for x in [s['force_per_handle_N'],side]+[round(j[k],7) for k in ['clamping_force_N','nominal_pressure_MPa','active_mean_pressure_MPa','peak_pressure_MPa','contact_area_mm2','max_mises_MPa','max_displacement_mm']])+'\n')
summary={'main_100N_left':{k:last[k] for k in ['clamping_force_N','nominal_pressure_MPa','active_mean_pressure_MPa','peak_pressure_MPa','max_mises_MPa','max_displacement_mm','contact_area_mm2']},'mesh_change_percent':changes,'max_force_balance_error_percent':maxfb,'max_moment_balance_error_percent':maxmb,'max_left_right_difference_percent':maxsym}
(OUT/'validation_summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(summary,ensure_ascii=False,indent=2));print('PDF_PAGES',pageno)
