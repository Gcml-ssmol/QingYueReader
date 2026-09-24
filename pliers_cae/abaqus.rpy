# -*- coding: mbcs -*-
#
# Abaqus/CAE Release 6.14-2 replay file
# Internal Version: 2014_08_22-22.00.46 134497
# Run by xsh12 on Thu Sep 24 22:25:18 2026
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
execfile('add_contact_history.py', __main__.__dict__)
#: 模型数据库 "C:\Users\xsh12\Documents\ChatGPT\笔记app\pliers_cae\Pliers_Main.cae" 已打开.
session.viewports['Viewport: 1'].setValues(displayedObject=None)
#: 模型数据库已保存到 "C:\Users\xsh12\Documents\ChatGPT\笔记app\pliers_cae\Pliers_Main.cae".
#: 模型数据库 "C:\Users\xsh12\Documents\ChatGPT\笔记app\pliers_cae\Pliers_Fine.cae" 已打开.
session.viewports['Viewport: 1'].setValues(displayedObject=None)
#: 模型数据库已保存到 "C:\Users\xsh12\Documents\ChatGPT\笔记app\pliers_cae\Pliers_Fine.cae".
#: HISTORY_ADDED
print 'RT script done'
#: RT script done
