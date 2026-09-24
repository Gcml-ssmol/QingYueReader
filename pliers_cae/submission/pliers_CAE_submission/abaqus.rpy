# -*- coding: mbcs -*-
#
# Abaqus/CAE Release 6.14-2 replay file
# Internal Version: 2014_08_22-22.00.46 134497
# Run by xsh12 on Thu Sep 24 22:46:14 2026
#

# from driverUtils import executeOnCaeGraphicsStartup
# executeOnCaeGraphicsStartup()
#: Executing "onCaeGraphicsStartup()" in the site directory ...
from abaqus import *
from abaqusConstants import *
session.Viewport(name='Viewport: 1', origin=(1.00762, 1.0075), width=148.321, 
    height=99.9438)
session.viewports['Viewport: 1'].makeCurrent()
from driverUtils import executeOnCaeStartup
executeOnCaeStartup()
execfile('verify_submission.py', __main__.__dict__)
#: 模型数据库 "C:\Users\xsh12\Documents\ChatGPT\笔记app\pliers_cae\submission\pliers_CAE_submission\Pliers_Main.cae" 已打开.
session.viewports['Viewport: 1'].setValues(displayedObject=None)
#: 模型: C:/Users/xsh12/Documents/ChatGPT/笔记app/pliers_cae/submission/pliers_CAE_submission/Pliers_Main.odb
#: 装配件个数:         1
#: 装配件实例个数: 0
#: 部件实例的个数:     3
#: 网格数:             4
#: 单元集合数:       6
#: 结点集合数:          10
#: 分析步的个数:              5
#: 模型数据库 "C:\Users\xsh12\Documents\ChatGPT\笔记app\pliers_cae\submission\pliers_CAE_submission\Pliers_Fine.cae" 已打开.
session.viewports['Viewport: 1'].setValues(displayedObject=None)
#: 模型: C:/Users/xsh12/Documents/ChatGPT/笔记app/pliers_cae/submission/pliers_CAE_submission/Pliers_Fine.odb
#: 装配件个数:         1
#: 装配件实例个数: 0
#: 部件实例的个数:     3
#: 网格数:             4
#: 单元集合数:       6
#: 结点集合数:          10
#: 分析步的个数:              1
#: SUBMISSION_VERIFIED
print 'RT script done'
#: RT script done
