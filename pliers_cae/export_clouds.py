# -*- coding: utf-8 -*-
from abaqus import *
from abaqusConstants import *
import visualization,displayGroupOdbToolset as dgo, os
name=os.environ.get('PLIERS_JOB','Pliers_Main')
odb=session.openOdb(name+'.odb')
v=session.viewports[session.currentViewportName]
v.setValues(displayedObject=odb)
v.viewportAnnotationOptions.setValues(triad=ON,title=OFF,state=OFF,annotations=OFF,compass=OFF)
v.odbDisplay.commonOptions.setValues(visibleEdges=FEATURE,deformationScaling=UNIFORM,uniformScaleFactor=1.)
v.odbDisplay.display.setValues(plotState=(CONTOURS_ON_DEF,))
v.view.setValues(session.views['Front'])
v.view.fitView()
session.graphicsOptions.setValues(backgroundStyle=SOLID,backgroundColor='#FFFFFF')
session.printOptions.setValues(vpDecorations=OFF,reduceColors=False)
session.pngOptions.setValues(imageSize=(2000,1600))
v.odbDisplay.setFrame(step=len(odb.steps)-1,frame=-1)
v.odbDisplay.setPrimaryVariable(variableLabel='S',outputPosition=INTEGRATION_POINT,refinement=(INVARIANT,'Mises'))
session.printToFile(fileName=name+'_mises_100N',format=PNG,canvasObjects=(v,))
v.odbDisplay.setPrimaryVariable(variableLabel='U',outputPosition=NODAL,refinement=(INVARIANT,'Magnitude'))
session.printToFile(fileName=name+'_displacement_100N',format=PNG,canvasObjects=(v,))
v.odbDisplay.displayGroup.replace(leaf=dgo.LeafFromPartInstance(partInstanceName=('LEFT_JAW',)))
# View the jaw's inner surface from the workpiece side.
v.view.setValues(session.views['Right'])
v.view.setValues(cameraTarget=(-5.,62.5,4.75),cameraPosition=(200.,62.5,4.75),cameraUpVector=(0.,1.,0.),width=22.,height=17.6)
v.odbDisplay.setPrimaryVariable(variableLabel='CPRESS',outputPosition=ELEMENT_NODAL)
for index,stepname in enumerate(odb.steps.keys()):
    v.odbDisplay.setFrame(step=index,frame=-1)
    session.printToFile(fileName=name+'_cpress_'+stepname,format=PNG,canvasObjects=(v,))
v.odbDisplay.displayGroup.replace(leaf=dgo.LeafFromPartInstance(partInstanceName=('LEFT_JAW','RIGHT_JAW','BLOCK')))
v.view.setValues(session.views['Iso'])
v.view.fitView()
v.odbDisplay.display.setValues(plotState=(UNDEFORMED,))
session.printToFile(fileName=name+'_model_iso',format=PNG,canvasObjects=(v,))
print('EXPORT_COMPLETE')
