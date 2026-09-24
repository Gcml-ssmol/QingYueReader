# -*- coding: utf-8 -*-
"""Abaqus/CAE 6.14, mm-N-MPa. Run: abaqus cae noGUI=build_pliers.py"""
from abaqus import *
from abaqusConstants import *
import regionToolset, mesh, os, json, traceback
import part, material, section, assembly, step, interaction, load, job, sketch, visualization

ROOT = os.getcwd()
os.chdir(ROOT)
Mdb()
m = mdb.models['Model-1']
m.setValues(description='Reference-size idealized long nose pliers; elastic Cr-V proxy; not a reverse-engineered product.')
m.Material(name='CrV_Elastic_Proxy')
m.materials['CrV_Elastic_Proxy'].Elastic(table=((210000.0, 0.30),))
m.Material(name='Steel_Workpiece')
m.materials['Steel_Workpiece'].Elastic(table=((210000.0, 0.30),))
m.HomogeneousSolidSection(name='ToolSteel', material='CrV_Elastic_Proxy')
m.HomogeneousSolidSection(name='BlockSteel', material='Steel_Workpiece')

outline=[(-5,73),(-8,73),(-17,30),(-18,15),(-14,8),(-13,-5),(-5,-15),(7,-22),(15,-55),(21,-95),(23,-127),(35,-127),(34,-95),(28,-55),(20,-22),(14,-10),(14,5),(8,15),(-5,23)]
s=m.ConstrainedSketch(name='LeverOutline',sheetSize=450.)
for i,xy in enumerate(outline): s.Line(point1=xy,point2=outline[(i+1)%len(outline)])
s.CircleByCenterPerimeter(center=(0.,0.),point1=(3.,0.))
p=m.Part(name='Lever',dimensionality=THREE_D,type=DEFORMABLE_BODY)
p.BaseSolidExtrude(sketch=s,depth=9.5)
# Half-lap pivot: remove upper half of the joint region. The other arm is flipped.
datum=p.DatumPlaneByPrincipalPlane(principalPlane=XYPLANE,offset=9.5)
axis=p.DatumAxisByPrincipalAxis(principalAxis=YAXIS)
t=p.MakeSketchTransform(sketchPlane=p.datums[datum.id],sketchUpEdge=p.datums[axis.id],sketchPlaneSide=SIDE1,origin=(0.,0.,9.5))
cut=m.ConstrainedSketch(name='HalfLapCut',sheetSize=450.,transform=t)
cut.rectangle(point1=(-60.,-24.),point2=(60.,24.))
p.CutExtrude(sketchPlane=p.datums[datum.id],sketchUpEdge=p.datums[axis.id],sketchPlaneSide=SIDE1,sketchOrientation=RIGHT,sketch=cut,depth=4.75,flipExtrudeDirection=OFF)
p.SectionAssignment(region=regionToolset.Region(cells=p.cells),sectionName='ToolSteel')
p.Surface(name='Jaw',side1Faces=p.faces.findAt(((-5.,60.,4.75),)))
p.Surface(name='Grip',side1Faces=p.faces.findAt(((34.5,-111.,4.75),)))
p.Surface(name='PinBore',side1Faces=p.faces.findAt(((3.,0.,2.375),)))
p.Set(name='ALL',cells=p.cells)

s=m.ConstrainedSketch(name='BlockOutline',sheetSize=100.)
s.rectangle(point1=(-5.,58.),point2=(5.,68.))
b=m.Part(name='Workpiece',dimensionality=THREE_D,type=DEFORMABLE_BODY)
b.BaseSolidExtrude(sketch=s,depth=9.5)
b.SectionAssignment(region=regionToolset.Region(cells=b.cells),sectionName='BlockSteel')
b.Surface(name='Left',side1Faces=b.faces.findAt(((-5.,63.,4.75),)))
b.Surface(name='Right',side1Faces=b.faces.findAt(((5.,63.,4.75),)))
# Symmetric centre-plane constraints suppress rigid motion while permitting compression.
for plane,offset in ((YZPLANE,0.),(XYPLANE,4.75)):
    d=b.DatumPlaneByPrincipalPlane(principalPlane=plane,offset=offset)
    b.PartitionCellByDatumPlane(datumPlane=b.datums[d.id],cells=b.cells)
b.Set(name='MID_X',faces=b.faces.getByBoundingBox(xMin=-0.001,xMax=0.001,yMin=57.9,yMax=68.1,zMin=-.1,zMax=9.6))
b.Set(name='MID_Z',faces=b.faces.getByBoundingBox(xMin=-5.1,xMax=5.1,yMin=57.9,yMax=68.1,zMin=4.749,zMax=4.751))
b.Set(name='BOTTOM',faces=b.faces.getByBoundingBox(xMin=-5.1,xMax=5.1,yMin=57.999,yMax=58.001,zMin=-.1,zMax=9.6))

a=m.rootAssembly
a.DatumCsysByDefault(CARTESIAN)
left=a.Instance(name='LEFT_JAW',part=p,dependent=ON)
right=a.Instance(name='RIGHT_JAW',part=p,dependent=ON)
a.rotate(instanceList=('RIGHT_JAW',),axisPoint=(0.,0.,0.),axisDirection=(0.,1.,0.),angle=180.)
a.translate(instanceList=('RIGHT_JAW',),vector=(0.,0.,9.5))
block=a.Instance(name='BLOCK',part=b,dependent=ON)
for name,z in (('LEFT_JAW',2.375),('RIGHT_JAW',7.125)):
    rp=a.ReferencePoint(point=(0.,0.,z))
    a.Set(name='PIN_'+name,referencePoints=(a.referencePoints[rp.id],))
    m.Coupling(name='PinCoupling_'+name,controlPoint=a.sets['PIN_'+name],surface=a.instances[name].surfaces['PinBore'],influenceRadius=WHOLE_SURFACE,couplingType=KINEMATIC,localCsys=None,u1=ON,u2=ON,u3=ON,ur1=ON,ur2=ON,ur3=ON)
    m.DisplacementBC(name='PinBC_'+name,createStepName='Initial',region=a.sets['PIN_'+name],u1=0.,u2=0.,u3=0.,ur1=0.,ur2=0.,ur3=UNSET)
m.DisplacementBC(name='BlockX',createStepName='Initial',region=block.sets['MID_X'],u1=0.)
m.DisplacementBC(name='BlockZ',createStepName='Initial',region=block.sets['MID_Z'],u3=0.)
m.DisplacementBC(name='BlockY',createStepName='Initial',region=block.sets['BOTTOM'],u2=0.)

m.ContactProperty('FrictionlessHard')
m.interactionProperties['FrictionlessHard'].NormalBehavior(pressureOverclosure=HARD,allowSeparation=ON,constraintEnforcementMethod=DEFAULT)
for jaw,face in (('LEFT_JAW','Left'),('RIGHT_JAW','Right')):
    m.SurfaceToSurfaceContactStd(name='Contact_'+jaw,createStepName='Initial',master=block.surfaces[face],slave=a.instances[jaw].surfaces['Jaw'],sliding=FINITE,interactionProperty='FrictionlessHard',adjustMethod=NONE,thickness=ON)

forces=(100,) if os.environ.get('PLIERS_FINAL_ONLY')=='1' else (20,40,60,80,100)
first_step='Grip_%03dN'%forces[0]
previous='Initial'
for force in forces:
    name='Grip_%03dN'%force
    m.StaticStep(name=name,previous=previous,nlgeom=ON,initialInc=.1,maxInc=.25,minInc=1.e-7,maxNumInc=200)
    previous=name
m.fieldOutputRequests['F-Output-1'].setValues(variables=('S','U','RF','CF','CSTRESS','CDISP','CFORCE'),frequency=LAST_INCREMENT)
m.HistoryOutputRequest(name='Energy',createStepName=first_step,variables=('ALLSE','ALLSD','ALLWK'),region=MODEL)
for jaw in ('LEFT_JAW','RIGHT_JAW'):
    m.HistoryOutputRequest(name='ContactTotals_'+jaw,createStepName=first_step,variables=('CFN1','CFN2','CFN3','CMN1','CMN2','CMN3','CAREA'),region=MODEL,interactions=('Contact_'+jaw,))
area=9.5*(32.**2+1.)**.5
for jaw,direction in (('LEFT_JAW',-1.),('RIGHT_JAW',1.)):
    load=m.SurfaceTraction(name='GripLoad_'+jaw,createStepName=first_step,region=a.instances[jaw].surfaces['Grip'],magnitude=forces[0]/area,directionVector=((0.,0.,0.),(direction,0.,0.)),distributionType=UNIFORM,traction=GENERAL,follower=OFF,resultant=ON)
    for force in forces[1:]:load.setValuesInStep(stepName='Grip_%03dN'%force,magnitude=force/area)

size=float(os.environ.get('PLIERS_MESH','2.5'))
p.seedPart(size=size,deviationFactor=.1,minSizeFactor=.1)
# Refine the contact strip and bore locally.
jawedges=p.edges.getByBoundingBox(xMin=-20.,xMax=-4.9,yMin=24.01,yMax=73.01,zMin=-.01,zMax=9.51)
p.seedEdgeBySize(edges=jawedges,size=size*.45,deviationFactor=.1,minSizeFactor=.1,constraint=FINER)
p.setMeshControls(regions=p.cells,elemShape=TET,technique=FREE)
p.setElementType(regions=(p.cells,),elemTypes=(mesh.ElemType(elemCode=C3D10,elemLibrary=STANDARD),))
p.generateMesh()
b.seedPart(size=size*.45,deviationFactor=.1,minSizeFactor=.1)
b.setMeshControls(regions=b.cells,elemShape=TET,technique=FREE)
b.setElementType(regions=(b.cells,),elemTypes=(mesh.ElemType(elemCode=C3D10,elemLibrary=STANDARD),))
b.generateMesh()
a.regenerate()
jobname=os.environ.get('PLIERS_JOB','Pliers_Main')
mdb.Job(name=jobname,model='Model-1',description='Quasistatic pliers contact, per-handle force 20 to 100 N',numCpus=2,numDomains=2,memory=80,memoryUnits=PERCENTAGE)
mdb.jobs[jobname].writeInput(consistencyChecking=ON)
mdb.saveAs(pathName=os.path.join(ROOT,jobname+'.cae'))
with open(jobname+'_mesh.json','w') as f:json.dump({'mesh_size':size,'lever_elements':len(p.elements),'lever_nodes':len(p.nodes),'block_elements':len(b.elements),'block_nodes':len(b.nodes),'grip_area_mm2':area,'outline':outline},f,indent=2)
v=session.viewports[session.currentViewportName]
v.setValues(displayedObject=a)
v.assemblyDisplay.setValues(mesh=OFF,referencePoints=OFF,datumPlanes=OFF)
v.view.setValues(session.views['Front'])
v.view.fitView()
session.printOptions.setValues(vpDecorations=OFF)
session.pngOptions.setValues(imageSize=(1800,1400))
session.printToFile(fileName=jobname+'_geometry',format=PNG,canvasObjects=(v,))
print('BUILD_COMPLETE '+jobname)
