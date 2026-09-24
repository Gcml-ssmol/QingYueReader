# -*- coding: utf-8 -*-
from odbAccess import openOdb
from abaqusConstants import *
import os,json,math
name=os.environ.get('PLIERS_JOB','Pliers_Main')
odb=openOdb(name+'.odb',readOnly=True)
result={'job':name,'steps':[],'instances':{}}
for insname,ins in odb.rootAssembly.instances.items():
    if insname in ('LEFT_JAW','RIGHT_JAW','BLOCK'):
        result['instances'][insname]={'nodes':len(ins.nodes),'elements':len(ins.elements)}
for stepname,st in odb.steps.items():
    f=st.frames[-1]
    if f.frameValue<.99999:raise RuntimeError('Incomplete step '+stepname)
    out={'step':stepname,'force_per_handle_N':int(stepname.split('_')[1][:-1]),'fields':list(f.fieldOutputs.keys()),'jaws':{},'energy':{}}
    u=f.fieldOutputs['U']
    cp=f.fieldOutputs['CPRESS']
    cf=f.fieldOutputs['CNORMF']
    for insname in ('LEFT_JAW','RIGHT_JAW'):
        ins=odb.rootAssembly.instances[insname]
        coords={n.label:tuple(n.coordinates) for n in ins.nodes}
        disp={v.nodeLabel:tuple(v.data) for v in u.getSubset(region=ins).values}
        cv=cf.getSubset(region=ins).values
        force=[sum(v.data[i] for v in cv) for i in range(3)]
        mz=sum((coords[v.nodeLabel][0]+disp[v.nodeLabel][0])*v.data[1]-(coords[v.nodeLabel][1]+disp[v.nodeLabel][1])*v.data[0] for v in cv)
        active=[]
        allpressure=[]
        for v in cp.getSubset(region=ins).values:
            xyz=coords[v.nodeLabel]
            if xyz[1]>53.:allpressure.append([v.nodeLabel,xyz[0],xyz[1],xyz[2],float(v.data)])
            if v.data>1.e-9:active.append([v.nodeLabel,xyz[0],xyz[1],xyz[2],float(v.data)])
        stress=f.fieldOutputs['S'].getSubset(region=ins,position=INTEGRATION_POINT).values
        # Output raw integration-point maximum, not averaged contours.
        maxs=max(stress,key=lambda v:v.mises)
        jawels=set(e.label for e in ins.elements if min(coords[n][1] for n in e.connectivity)>30.)
        jawstress=[v.mises for v in stress if v.elementLabel in jawels]
        us=[math.sqrt(sum(c*c for c in vec)) for vec in disp.values()]
        # Applied traction is uniform over y=-127..-95, compute deformed centroid
        gripnodes=[n.label for n in ins.nodes if abs(abs(n.coordinates[0])-(35.+(n.coordinates[1]+127.)*(-1./32.)))<1.e-4 and -127.00001<=n.coordinates[1]<=-94.99999]
        gripuy=sum(disp[n][1] for n in gripnodes)/len(gripnodes)
        out['jaws'][insname]={'contact_force_vector_N':force,'clamping_force_N':abs(force[0]),'contact_moment_Nmm':mz,'effective_jaw_arm_mm':abs(mz/force[0]),'nominal_pressure_MPa':abs(force[0])/95.,'peak_pressure_MPa':max([v[-1] for v in active] or [0.]),'max_mises_MPa':maxs.mises,'max_mises_element':maxs.elementLabel,'jaw_max_mises_MPa':max(jawstress),'max_displacement_mm':max(us),'active_pressure_nodes':active,'grip_mean_U2_mm':gripuy,'moment_error_percent':100.*abs(abs(mz)-out['force_per_handle_N']*(111.-gripuy))/(out['force_per_handle_N']*111.)}
        out['jaws'][insname]['pressure_map_nodes']=allpressure
    rf=f.fieldOutputs['RF']
    out['total_reaction_N']=[sum(v.data[i] for v in rf.values) for i in range(3)]
    for region in st.historyRegions.values():
        for key,history in region.historyOutputs.items():
            if key in ('ALLSE','ALLSD','ALLWK'):out['energy'][key]=history.data[-1][1]
    result['steps'].append(out)
    # Whole-contact history is the authoritative resultant. Keep nodal sums as diagnostics.
    for insname in ('LEFT_JAW','RIGHT_JAW'):
        matching=[h for key,h in st.historyRegions.items() if any(n.startswith('CFN1') and insname in n for n in h.historyOutputs.keys())]
        if len(matching)!=1:raise RuntimeError('Missing/ambiguous contact history for '+insname+': '+str(st.historyRegions.keys()))
        hist=matching[0].historyOutputs
        vals={key:next(h.data[-1][1] for n,h in hist.items() if n.startswith(key) and insname in n) for key in ('CFN1','CFN2','CFN3','CMN1','CMN2','CMN3','CAREA')}
        j=out['jaws'][insname]
        j['nodal_contact_force_sum_N']=j['clamping_force_N']
        j['nodal_contact_moment_sum_Nmm']=j['contact_moment_Nmm']
        j['contact_history']=vals
        j['clamping_force_N']=abs(vals['CFN1'])
        j['contact_moment_Nmm']=vals['CMN3']
        j['effective_jaw_arm_mm']=abs(vals['CMN3']/vals['CFN1'])
        j['nominal_pressure_MPa']=j['clamping_force_N']/95.
        j['contact_area_mm2']=vals['CAREA']
        j['active_mean_pressure_MPa']=j['clamping_force_N']/vals['CAREA']
        j['moment_error_percent']=100.*abs(abs(vals['CMN3'])-out['force_per_handle_N']*(111.-j['grip_mean_U2_mm']))/(out['force_per_handle_N']*111.)
        pin=odb.rootAssembly.nodeSets['PIN_'+insname]
        rx=sum(v.data[0] for v in rf.getSubset(region=pin).values)
        j['pin_reaction_x_N']=rx
        j['reaction_derived_force_N']=abs(rx)-out['force_per_handle_N']
        j['force_balance_error_percent']=100.*abs(j['reaction_derived_force_N']-j['clamping_force_N'])/j['clamping_force_N']
        j['nodal_sum_difference_percent']=100.*(j['nodal_contact_force_sum_N']-j['clamping_force_N'])/j['clamping_force_N']
with open(name+'_results.json','w') as stream:json.dump(result,stream,indent=2,default=float)
print(json.dumps([{'force':s['force_per_handle_N'],'jaws':{k:{n:v[n] for n in ('clamping_force_N','nominal_pressure_MPa','peak_pressure_MPa','max_mises_MPa','moment_error_percent')} for k,v in s['jaws'].items()}} for s in result['steps']],indent=2,default=float))
odb.close()
