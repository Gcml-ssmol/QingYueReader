# Abaqus/CAE 6.14: abaqus cae noGUI=verify_submission.py
from abaqus import *
from odbAccess import openOdb
import os,json,step,part,assembly,interaction,job
checks=[]
for name,expected in (('Pliers_Main',5),('Pliers_Fine',1)):
    openMdb(pathName=name+'.cae')
    model=mdb.models['Model-1']
    assert len(model.parts)==2
    assert len(model.rootAssembly.instances)==3
    assert len(model.interactions)==2
    assert len(model.steps)==expected+1
    assert len(model.parts['Lever'].elements)>0
    assert 'ContactTotals_LEFT_JAW' in model.historyOutputRequests
    odb=openOdb(name+'.odb',readOnly=True)
    assert len(odb.steps)==expected
    for st in odb.steps.values():
        assert st.frames[-1].frameValue>=0.99999
        assert 'S' in st.frames[-1].fieldOutputs
        assert any(k.startswith('CPRESS') for k in st.frames[-1].fieldOutputs.keys())
        assert any(any(k.startswith('CFN1') for k in h.historyOutputs.keys()) for h in st.historyRegions.values())
    checks.append({'job':name,'CAE_open':True,'parts':len(model.parts),'instances':len(model.rootAssembly.instances),'completed_load_steps':expected,'ODB_fields_and_contact_history':True})
    odb.close()
with open('submission_checks.json','w') as f:json.dump(checks,f,indent=2)
print('SUBMISSION_VERIFIED')
