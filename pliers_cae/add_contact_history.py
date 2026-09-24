from abaqus import *
from abaqusConstants import *
import step,job,os
for name in ('Pliers_Main','Pliers_Fine'):
 openMdb(pathName=name+'.cae')
 m=mdb.models['Model-1']
 first='Grip_020N' if name=='Pliers_Main' else 'Grip_100N'
 for jaw in ('LEFT_JAW','RIGHT_JAW'):
  m.HistoryOutputRequest(name='ContactTotals_'+jaw,createStepName=first,variables=('CFN1','CFN2','CFN3','CMN1','CMN2','CMN3','CAREA'),region=MODEL,interactions=('Contact_'+jaw,))
 mdb.jobs[name].writeInput(consistencyChecking=ON)
 mdb.save()
print('HISTORY_ADDED')
