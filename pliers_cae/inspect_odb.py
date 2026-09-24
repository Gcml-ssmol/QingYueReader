from odbAccess import openOdb
o=openOdb('Pliers_Main.odb',readOnly=True)
f=o.steps['Grip_100N'].frames[-1]
print('FIELDS',f.fieldOutputs.keys())
for k in f.fieldOutputs.keys():
 if k.startswith('CNORMF'):
  v=f.fieldOutputs[k].getSubset(region=o.rootAssembly.instances['LEFT_JAW']).values
  print(k,len(v),len(set(x.nodeLabel for x in v)),[sum(x.data[i] for x in v) for i in range(3)])
for st in o.rootAssembly.nodeSets.keys():
 if st.startswith('PIN'):
  print(st,[(v.nodeLabel,tuple(v.data)) for v in f.fieldOutputs['RF'].getSubset(region=o.rootAssembly.nodeSets[st]).values])
for region in o.steps['Grip_100N'].historyRegions.values():
 print('HIST',region.name,region.description,list(region.historyOutputs.keys()))
 for k,h in region.historyOutputs.items():
  if k.startswith(('CFN','CMN','CAREA')): print(k,h.description,h.data[-1])
o.close()
